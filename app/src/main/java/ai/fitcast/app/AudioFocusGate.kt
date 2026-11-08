package ai.fitcast.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log

/**
 * Pleos Connect 오디오 포커스 가이드에 맞춘 포커스 처리.
 *
 * 브리핑은 말소리라서, 내비 안내가 들어와도 음량만 낮춘 채 겹쳐 말하면 내용을 놓친다.
 * 그래서 setWillPauseWhenDucked(true)로 덕킹 대신 멈춤을 택하고, 포커스를 잠시 잃은 동안은
 * 엔진이 운전 부담 큰 순간과 똑같이 멈췄다가 포커스를 되찾으면 그 문장을 처음부터 다시 읽는다.
 * Gleo 음성 명령 호출(AUDIOFOCUS_LOSS_TRANSIENT)도 같은 경로로 즉시 멈춘다.
 */
class AudioFocusGate(context: Context, private val onHold: (String?) -> Unit) {
    private val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var held = false

    private val listener = AudioManager.OnAudioFocusChangeListener { change ->
        Log.i("FitCast", "audio focus change $change")
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> { held = true; onHold(null) }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> onHold("음성 명령·안내 음성(오디오 포커스)")
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> onHold("내비 안내 음성(오디오 포커스)")
            AudioManager.AUDIOFOCUS_LOSS -> { held = false; onHold("다른 오디오 앱 재생(오디오 포커스)") }
        }
    }

    private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        .setWillPauseWhenDucked(true)
        .setOnAudioFocusChangeListener(listener)
        .build()

    /** 말하기 전에 부른다. 포커스를 못 받으면 false. */
    fun acquire(): Boolean {
        if (held) return true
        val r = am.requestAudioFocus(request)
        Log.i("FitCast", "audio focus request result $r")
        held = r == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (held) onHold(null)
        return held
    }

    fun release() {
        if (held) am.abandonAudioFocusRequest(request)
        held = false
    }
}
