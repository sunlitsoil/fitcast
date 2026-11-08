package ai.fitcast.app

import ai.fitcast.core.DemoRoute
import ai.fitcast.core.DriveSnapshot
import ai.fitcast.core.FitCastEngine
import ai.fitcast.core.GearState
import ai.fitcast.core.Load
import ai.fitcast.core.SampleBriefing
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
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.widget.Button
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

    private lateinit var loadView: TextView
    private lateinit var reasonView: TextView
    private lateinit var captionView: TextView
    private lateinit var itemView: TextView
    private lateinit var planView: TextView
    private lateinit var logView: TextView
    private lateinit var demoButton: Button

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
            engine.onTick(snapshot(SystemClock.elapsedRealtime()))
            render()
            main.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        loadView.text = when (v.load) {
            Load.HIGH -> "집중 — 잠시 멈춤"
            Load.MEDIUM -> "주의 — 읽던 문장만"
            Load.LOW -> "여유 — 듣는 중"
            Load.PARKED -> "주차 — 남은 항목 보기"
        }
        loadView.setBackgroundColor(
            when (v.load) {
                Load.HIGH -> Color.rgb(140, 40, 36); Load.MEDIUM -> Color.rgb(120, 92, 24)
                Load.LOW -> Color.rgb(28, 80, 56); Load.PARKED -> Color.rgb(36, 56, 96)
            }
        )
        reasonView.text = v.reasons.take(2).joinToString(" · ")
        captionView.text = when {
            speakingText.isNotEmpty() -> "▶ “$speakingText”"
            v.load == Load.HIGH && engine.pendingSentence != null -> "⏸ 멈춤 — 여유가 생기면 이 문장부터 다시: “${engine.pendingSentence}”"
            engine.pendingSentence != null -> "⏳ 다음 문장 대기: “${engine.pendingSentence}”"
            else -> "(말하지 않음)"
        }
        val title = engine.currentTitle
        itemView.text = if (title == null) "대기" else "$title · ${engine.currentDepth?.label() ?: ""}"
        planView.text = if (v.load == Load.PARKED) {
            val left = engine.leftovers().map { "· ${it.title}" }
            val saved = engine.savedForLater.map { "★ ${it.title}" }
            "주차 후 볼 항목 ${left.size}개\n" + (saved + left).take(8).joinToString("\n")
        } else {
            "다음 계획\n" + engine.upcoming.take(6).joinToString("\n") { "· ${it.item.title} — ${it.depth.label()}" }
        }
        val start = demoStartMs
        val demo = if (start != null) {
            val t = ((SystemClock.elapsedRealtime() - start) / 1000).toInt()
            "시연 주행 %d:%02d / 5:00\n".format(t / 60, t % 60)
        } else ""
        logView.text = demo + engine.events.takeLast(6).joinToString("\n") { "${it.kind}: ${it.detail.take(70)}" }
    }

    private fun buildUi(): LinearLayout {
        fun label(size: Float, bold: Boolean = false, color: Int = Color.WHITE) = TextView(this).apply {
            textSize = size; setTextColor(color); gravity = Gravity.CENTER; setPadding(24, 14, 24, 14)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }
        fun button(text: String, onClick: () -> Unit) = Button(this).apply {
            this.text = text; textSize = 26f; minHeight = 140
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(10, 10, 10, 10) }
            setOnClickListener { onClick() }
        }
        loadView = label(36f, bold = true)
        reasonView = label(20f, color = Color.rgb(200, 200, 200))
        captionView = label(30f, color = Color.rgb(255, 236, 170)).apply { minLines = 2 }
        itemView = label(22f, color = Color.rgb(170, 200, 255))
        planView = label(18f, color = Color.rgb(210, 210, 210)).apply { gravity = Gravity.START }
        logView = label(15f, color = Color.rgb(150, 150, 150)).apply { gravity = Gravity.START }
        demoButton = button("시연 주행") {
            if (demoStartMs == null) { demoStartMs = SystemClock.elapsedRealtime(); engine = FitCastEngine(SampleBriefing.items, sink) }
            else demoStartMs = null
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(button("말로 명령") { stt.request() })
            addView(button("다음") { engine.command(VoiceCommand.NEXT, SystemClock.elapsedRealtime()) })
            addView(button("저장") { engine.command(VoiceCommand.SAVE, SystemClock.elapsedRealtime()) })
            addView(demoButton)
        }
        val lower = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(planView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(logView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(18, 18, 20))
            addView(loadView); addView(reasonView); addView(captionView); addView(itemView); addView(buttons); addView(lower)
        }
    }

    private companion object {
        const val TAG = "FitCast"
        const val FOCUS_WAIT = "오디오 포커스 대기"
    }
}
