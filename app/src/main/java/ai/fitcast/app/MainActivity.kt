package ai.fitcast.app

import ai.fitcast.core.FitCastEngine
import ai.fitcast.core.Load
import ai.fitcast.core.SampleBriefing
import ai.fitcast.core.SpeechSink
import ai.fitcast.core.VoiceCommand
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
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

/**
 * 화면은 상태 표시와 큰 버튼 세 개뿐이다. 주행 중 조작은 말로 한다(「다음」「자세히」「저장」).
 * 0.5초마다 차량·내비 신호를 엔진에 넘기고, 엔진이 말할지 멈출지 정한다.
 */
class MainActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var signals: PleosSignals
    private lateinit var tts: TextToSpeech
    private lateinit var stt: SpeechToText
    private lateinit var engine: FitCastEngine
    private var speakingId: String? = null

    private lateinit var loadView: TextView
    private lateinit var itemView: TextView
    private lateinit var reasonView: TextView

    private val sink = object : SpeechSink {
        override fun speak(utteranceId: String, text: String) {
            speakingId = utteranceId
            tts.speak(text, true)
        }
        override fun stop() {
            speakingId = null
            tts.stop()
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            engine.onTick(signals.snapshot(SystemClock.elapsedRealtime()))
            render()
            main.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())

        signals = PleosSignals(this)
        tts = TextToSpeech(this, TtsMode.ON_DEVICE).apply {
            initialize()
            setLocale(Locale.KOREA)
            addEventListener(object : EventListener {
                override fun onStart() {}
                override fun onReady() {}
                override fun onDone() { main.post { speakingId?.let { id -> speakingId = null; engine.onSpeechDone(id) } } }
                override fun onError(error: TtsError) { main.post { speakingId = null } }
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
                    VoiceCommand.parse(text)?.let { c -> main.post { engine.command(c, SystemClock.elapsedRealtime()) } }
                }
                override fun onUpdatedEpdData(start: Long, end: Long) {}
                override fun onStartedRecognition() {}
                override fun onEndedRecognition() {}
                override fun onError(error: SttError) {}
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
        super.onDestroy()
    }

    private fun render() {
        val v = engine.lastVerdict ?: return
        loadView.text = when (v.load) {
            Load.HIGH -> "잠시 멈춤"
            Load.MEDIUM -> "짧게만"
            Load.LOW -> "듣는 중"
            Load.PARKED -> "주차 — 남은 항목 보기"
        }
        loadView.setBackgroundColor(
            when (v.load) { Load.HIGH -> Color.rgb(120, 40, 40); Load.MEDIUM -> Color.rgb(110, 90, 30); else -> Color.rgb(30, 70, 50) }
        )
        itemView.text = engine.currentTitle ?: "대기"
        reasonView.text = v.reasons.firstOrNull() ?: ""
    }

    private fun buildUi(): LinearLayout {
        fun label(size: Float) = TextView(this).apply {
            textSize = size; setTextColor(Color.WHITE); gravity = Gravity.CENTER; setPadding(24, 24, 24, 24)
        }
        fun button(text: String, onClick: () -> Unit) = Button(this).apply {
            this.text = text; textSize = 28f; minHeight = 160
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(12, 12, 12, 12) }
            setOnClickListener { onClick() }
        }
        loadView = label(40f)
        itemView = label(30f)
        reasonView = label(20f)
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(button("말로 명령") { stt.request() })
            addView(button("다음") { engine.command(VoiceCommand.NEXT, SystemClock.elapsedRealtime()) })
            addView(button("저장") { engine.command(VoiceCommand.SAVE, SystemClock.elapsedRealtime()) })
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(18, 18, 20))
            addView(loadView); addView(itemView); addView(reasonView); addView(buttons)
        }
    }
}
