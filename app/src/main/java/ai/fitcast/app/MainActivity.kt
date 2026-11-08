package ai.fitcast.app

import ai.fitcast.core.DemoRoute
import ai.fitcast.core.DriveSnapshot
import ai.fitcast.core.FitCastEngine
import ai.fitcast.core.GearState
import ai.fitcast.core.Load
import ai.fitcast.core.SampleBriefing
import ai.fitcast.core.SpeechClock
import ai.fitcast.core.SpeechSink
import ai.fitcast.core.Turn
import ai.fitcast.core.VoiceCommand
import ai.fitcast.core.label
import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.ErrorType as SttError
import ai.pleos.playground.stt.listener.ResultListener
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType as TtsError
import ai.pleos.playground.tts.constant.Mode as TtsMode
import ai.pleos.playground.stt.constant.Mode as SttMode
import ai.pleos.playground.tts.listener.EventListener
import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale
import kotlin.math.abs

/**
 * 주행 중 조작은 말로 한다(「다음」「자세히」「저장」). 화면은 상태·지금 읽는 문장·계획만 보여 준다.
 * 0.5초마다 차량·내비 신호를 엔진에 넘기고, 엔진이 말할지 멈출지 정한다.
 *
 * 「시연 주행」은 내비 경로 없이 판단을 보여 주려고 5분 합성 경로(DemoRoute)를 실제 시간으로 흘리고,
 * 그 위에 실제 차량 신호(Virtual Console에서 바꾼 기어·방향지시등·와이퍼·조향각)를 덮어쓴다.
 */
class MainActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var signals: PleosSignals
    private lateinit var tts: TextToSpeech
    private lateinit var stt: SpeechToText
    private lateinit var engine: FitCastEngine
    private var speakingId: String? = null
    private var speakingText: String = ""
    private var demoStartMs: Long? = null
    private lateinit var focus: AudioFocusGate

    private lateinit var pal: Palette
    private val clock = SpeechClock()
    private var lastSnap: DriveSnapshot? = null
    private lateinit var stateDot: View
    private lateinit var stateView: TextView
    private lateinit var reasonView: TextView
    private lateinit var etaView: TextView
    private lateinit var bar: ArrivalBar
    private lateinit var planSumView: TextView
    private lateinit var itemTitle: TextView
    private lateinit var itemMeta: TextView
    private lateinit var statusLine: TextView
    private lateinit var sentence: TextView
    private lateinit var nextHeader: TextView
    private lateinit var nextRows: List<LinearLayout>
    private lateinit var driveGroup: LinearLayout
    private lateinit var parkedGroup: LinearLayout
    private lateinit var parkedTitle: TextView
    private lateinit var parkedRows: List<LinearLayout>
    private lateinit var demoNote: TextView
    private lateinit var demoButton: TextView

    private val sink = object : SpeechSink {
        override fun speak(utteranceId: String, text: String) {
            speakingId = utteranceId
            speakingText = text
            Log.i(TAG, "speak $utteranceId $text")
            // 포커스를 못 받으면 말하지 않고 멈춤으로 처리한다. 다음 틱에서 다시 요청한다
            if (!focus.acquire()) { engine.audioHold = FOCUS_WAIT; return }
            tts.speak(text, true)
        }
        override fun stop() {
            Log.i(TAG, "stop $speakingId")
            speakingId = null
            speakingText = ""
            tts.stop()
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (engine.audioHold == FOCUS_WAIT) focus.acquire()
            val snap = snapshot(SystemClock.elapsedRealtime())
            lastSnap = snap
            engine.onTick(snap)
            render()
            main.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pal = Palette.forSystem(this)
        window.statusBarColor = pal.bg
        setContentView(buildUi())

        signals = PleosSignals(this)
        focus = AudioFocusGate(this) { reason -> main.post { engine.audioHold = reason } }
        tts = TextToSpeech(this, TtsMode.ON_DEVICE).apply {
            initialize()
            setLocale(Locale.KOREA)
            addEventListener(object : EventListener {
                override fun onStart() { Log.i(TAG, "tts onStart") }
                override fun onReady() {}
                override fun onDone() {
                    Log.i(TAG, "tts onDone")
                    main.post { speakingId?.let { id -> speakingId = null; speakingText = ""; engine.onSpeechDone(id) } }
                }
                override fun onError(error: TtsError) { Log.w(TAG, "tts onError $error"); main.post { speakingId = null; speakingText = "" } }
                override fun onStop() {}
                override fun onUpdatedRms(rms: Double) {}
            })
        }
        stt = SpeechToText(this, SttMode.ON_DEVICE).apply {
            initialize()
            setLocale(Locale.KOREA)
            addListener(object : ResultListener {
                override fun onUpdated(text: String, isFinal: Boolean) {
                    if (!isFinal) return
                    Log.i(TAG, "stt $text")
                    VoiceCommand.parse(text)?.let { c -> main.post { engine.command(c, SystemClock.elapsedRealtime()) } }
                }
                override fun onUpdatedEpdData(start: Long, end: Long) {}
                override fun onStartedRecognition() {}
                override fun onEndedRecognition() {}
                override fun onError(error: SttError) { Log.w(TAG, "stt onError $error") }
                override fun onReady() {}
                override fun onUpdatedRms(rms: Double) {}
            })
        }
        // 시제품은 예시 브리핑을 쓴다. 실제 서비스는 일정·메일·구독 피드를 요약 모델이 문장 단위로 만든다
        engine = FitCastEngine(SampleBriefing.items, sink)
        signals.start()
        main.post(tick)
    }

    override fun onDestroy() {
        main.removeCallbacks(tick)
        signals.stop()
        runCatching { tts.stop(); tts.release() }
        runCatching { stt.release() }
        focus.release()
        super.onDestroy()
    }

    /** 실제 신호. 시연 주행 중이면 합성 경로 위에 실제 신호를 덮어쓴다. */
    private fun snapshot(nowMs: Long): DriveSnapshot {
        val real = signals.snapshot(nowMs)
        val start = demoStartMs ?: return real
        val t = ((nowMs - start) / 1000).toInt().coerceAtMost(DemoRoute.arriveSec)
        val d = DemoRoute.snapshot(t)
        return d.copy(
            tMs = nowMs,
            gear = if (real.gear == GearState.PARK || real.gear == GearState.REVERSE) real.gear else d.gear,
            turnSignal = if (real.turnSignal != Turn.OFF) real.turnSignal else d.turnSignal,
            steeringDeg = if (abs(real.steeringDeg) > abs(d.steeringDeg)) real.steeringDeg else d.steeringDeg,
            wiperOn = real.wiperOn || d.wiperOn,
            brake = real.brake || d.brake,
        )
    }

    private fun render() {
        val v = engine.lastVerdict ?: return
        val accent = pal.of(v.load)
        stateDot.background = circle(accent)
        stateView.text = v.load.title()
        stateView.setTextColor(accent)
        reasonView.text = v.reasons.take(2).joinToString(", ")

        val remain = lastSnap?.remainSec
        etaView.text = remain?.let { "%d:%02d".format(it / 60, it % 60) } ?: "경로 없음"

        val parked = v.load == Load.PARKED
        driveGroup.visibility = if (parked) View.GONE else View.VISIBLE
        parkedGroup.visibility = if (parked) View.VISIBLE else View.GONE
        if (parked) { renderParked(); return }

        // 도착 맞춤 막대: 지금 항목의 남은 문장 + 계획된 항목들의 낭독 시간
        val pos = engine.sentencePosition
        val curSec = engine.currentEntry?.let { e -> clock.seconds(e.sentences().drop(pos?.first ?: 0)) }
        val segs = listOfNotNull(curSec) + engine.upcoming.map { clock.seconds(it.sentences()) }
        bar.palette = pal; bar.accent = accent; bar.segments = segs; bar.remainSec = remain; bar.invalidate()
        planSumView.text = "읽을 분량 %d:%02d".format(segs.sum().toInt() / 60, segs.sum().toInt() % 60)

        val title = engine.currentTitle
        itemTitle.text = title ?: "다음 항목을 기다리는 중"
        itemMeta.text = if (title != null && pos != null)
            "${engine.currentDepth?.label() ?: ""}  ${minOf(pos.first + 1, pos.second)}/${pos.second}문장" else ""

        val pending = engine.pendingSentence
        when {
            speakingText.isNotEmpty() -> { statusLine.text = "읽는 중"; statusLine.setTextColor(pal.sub); sentence.text = speakingText; sentence.setTextColor(pal.text) }
            v.load == Load.HIGH && pending != null -> { statusLine.text = "멈춤. 여유가 생기면 이 문장 처음부터 다시 읽습니다"; statusLine.setTextColor(accent); sentence.text = pending; sentence.setTextColor(pal.faint) }
            pending != null -> { statusLine.text = "다음 집중 구간 전에 끝낼 수 있을 때 시작합니다"; statusLine.setTextColor(pal.sub); sentence.text = pending; sentence.setTextColor(pal.faint) }
            else -> { statusLine.text = ""; sentence.text = ""; }
        }

        val next = engine.upcoming.take(4)
        nextRows.forEachIndexed { i, row ->
            val e = next.getOrNull(i)
            row.visibility = if (e == null) View.INVISIBLE else View.VISIBLE
            if (e != null) { (row.getChildAt(0) as TextView).text = e.item.title; (row.getChildAt(1) as TextView).text = e.depth.label() }
        }
        nextHeader.text = if (next.isEmpty()) "남은 계획 없음" else "다음 순서"
        renderDemo()
    }

    private fun renderParked() {
        val saved = engine.savedForLater
        val left = engine.leftovers().filter { it !in saved }
        parkedTitle.text = "못 들은 항목 ${left.size + saved.size}개"
        val heard = engine.heardSentences()
        val rows = saved.map { it to "저장함" } + left.map { it to if ((heard[it.id] ?: 0) > 0) "일부 들음" else "못 들음" }
        parkedRows.forEachIndexed { i, row ->
            val r = rows.getOrNull(i)
            row.visibility = if (r == null) View.GONE else View.VISIBLE
            if (r != null) {
                (row.getChildAt(0) as TextView).text = r.first.title
                (row.getChildAt(1) as TextView).apply { text = r.second; setTextColor(if (r.second == "저장함") pal.text else pal.sub) }
            }
        }
        renderDemo()
    }

    private fun renderDemo() {
        val start = demoStartMs
        demoNote.visibility = if (start == null) View.INVISIBLE else View.VISIBLE
        if (start != null) {
            val t = ((SystemClock.elapsedRealtime() - start) / 1000).toInt()
            demoNote.text = "시연 주행 %d:%02d / 5:00   합성 경로 위에 실제 차량 신호(기어·조향각·와이퍼·브레이크)를 덮어 씁니다".format(t / 60, t % 60)
        }
        demoButton.text = if (start == null) "시연 주행" else "시연 끝내기"
    }

    private fun circle(color: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
    private fun rounded(color: Int, r: Float = 14f, stroke: Int? = null) = GradientDrawable().apply {
        cornerRadius = r; setColor(color); stroke?.let { setStroke(2, it) }
    }

    private fun buildUi(): View {
        val regular = resources.getFont(R.font.pretendard_regular)
        val semi = resources.getFont(R.font.pretendard_semibold)
        val bold = resources.getFont(R.font.pretendard_bold)
        fun text(size: Float, face: Typeface = regular, color: Int = pal.text) = TextView(this).apply {
            textSize = size; typeface = face; setTextColor(color); includeFontPadding = false
            letterSpacing = -0.01f; fontFeatureSettings = "tnum"
        }
        fun lp(w: Int = LinearLayout.LayoutParams.MATCH_PARENT, h: Int = LinearLayout.LayoutParams.WRAP_CONTENT, weight: Float = 0f, top: Int = 0) =
            LinearLayout.LayoutParams(w, h, weight).apply { topMargin = top }
        fun row(): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 18, 0, 18)
            addView(text(26f, regular), lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(text(22f, regular, pal.sub))
        }
        fun button(label: String, primary: Boolean, onClick: () -> Unit) = TextView(this).apply {
            text = label; textSize = 28f; typeface = semi; gravity = Gravity.CENTER; includeFontPadding = false
            setTextColor(if (primary) pal.onAccent else pal.text)
            background = if (primary) rounded(pal.text) else rounded(pal.surface, stroke = pal.line)
            minHeight = 104; isClickable = true; isFocusable = true
            setOnClickListener { onClick() }
        }

        // 머리: 운전 단계와 도착까지 남은 시간
        stateDot = View(this)
        stateView = text(40f, bold)
        reasonView = text(22f, regular, pal.sub)
        etaView = text(48f, semi)
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(stateDot, LinearLayout.LayoutParams(28, 28).apply { rightMargin = 22 })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(stateView); addView(reasonView, lp(top = 10))
            }, lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.END
                addView(text(22f, regular, pal.sub).apply { text = "도착까지" }, lp(LinearLayout.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.END })
                addView(etaView, lp(LinearLayout.LayoutParams.WRAP_CONTENT, top = 6).apply { gravity = Gravity.END })
            })
        }

        // 도착 맞춤 막대
        bar = ArrivalBar(this, regular)
        planSumView = text(22f, regular, pal.sub)

        // 지금 읽는 문장
        itemTitle = text(26f, semi)
        itemMeta = text(22f, regular, pal.sub)
        statusLine = text(24f, semi, pal.sub)
        sentence = text(44f, semi).apply { setLineSpacing(0f, 1.35f); maxLines = 3 }
        val itemHead = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM
            addView(itemTitle, lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)); addView(itemMeta)
        }

        // 다음 순서
        nextHeader = text(22f, semi, pal.sub)
        nextRows = List(4) { row() }
        val nextList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(nextHeader)
            nextRows.forEachIndexed { i, r ->
                if (i > 0) addView(View(context).apply { setBackgroundColor(pal.line) }, lp(h = 2))
                addView(r)
            }
        }

        driveGroup = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(bar, lp(h = 92, top = 36))
            addView(planSumView, lp(top = 4))
            addView(itemHead, lp(top = 52))
            addView(View(context).apply { setBackgroundColor(pal.line) }, lp(h = 2, top = 18))
            addView(statusLine, lp(top = 26))
            addView(sentence, lp(top = 16).apply { height = 190 })
            addView(nextList, lp(top = 30))
        }

        // 주차 중: 못 들은 항목
        parkedTitle = text(32f, semi)
        parkedRows = List(7) { row() }
        parkedGroup = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; visibility = View.GONE
            addView(parkedTitle, lp(top = 48))
            addView(text(22f, regular, pal.sub).apply { text = "저장한 항목이 먼저 나옵니다." }, lp(top = 10))
            addView(View(context).apply { setBackgroundColor(pal.line) }, lp(h = 2, top = 24))
            parkedRows.forEach { addView(it) }
        }

        demoButton = button("시연 주행", false) {
            if (demoStartMs == null) { demoStartMs = SystemClock.elapsedRealtime(); engine = FitCastEngine(SampleBriefing.items, sink) }
            else demoStartMs = null
        }.apply { textSize = 22f; minHeight = 76 }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(button("말로 명령", true) { stt.request() }, lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f))
            addView(button("다음 항목", false) { engine.command(VoiceCommand.NEXT, SystemClock.elapsedRealtime()) }, lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = 20 })
            addView(button("나중에 보기", false) { engine.command(VoiceCommand.SAVE, SystemClock.elapsedRealtime()) }, lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = 20 })
        }
        demoNote = text(18f, regular, pal.faint)
        val foot = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(demoNote, lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(demoButton, LinearLayout.LayoutParams(220, LinearLayout.LayoutParams.WRAP_CONTENT))
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 48, 64, 36)
            addView(head)
            addView(driveGroup)
            addView(parkedGroup, lp(0, 0, 1f).apply { width = LinearLayout.LayoutParams.MATCH_PARENT })
            addView(buttons, lp(top = 24))
            addView(foot, lp(top = 20))
        }
        return column.apply { setBackgroundColor(pal.bg) }
    }

    private companion object {
        const val TAG = "FitCast"
        const val FOCUS_WAIT = "오디오 포커스 대기"
    }
}
