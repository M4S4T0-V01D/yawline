package dev.m4s4t0.yawline.notes

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import dev.m4s4t0.yawline.Prefs
import java.util.Locale

/**
 * The co-driver's voice: the phone's text-to-speech engine, routed as navigation guidance so it plays
 * through Bluetooth intercoms and ducks music. Works offline with the engine's downloaded voice.
 */
object Voice {
    private var tts: TextToSpeech? = null
    private var ready = false
    private val queued = ArrayList<String>()

    fun init(ctx: Context) {
        if (tts != null) return
        tts = TextToSpeech(ctx.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                tts?.language = Locale.UK
                tts?.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                queued.forEach { say(it) }
                queued.clear()
            }
        }
    }

    /** Speaks after whatever is being said now; [urgent] cuts it off instead. */
    fun say(text: String, urgent: Boolean = false) {
        val t = tts
        if (t == null || !ready) { queued.add(text); return }
        t.setSpeechRate(Prefs.voiceRate)
        t.speak(text, if (urgent) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "n" + System.nanoTime())
    }

    fun stop() { tts?.stop() }

    fun shutdown() {
        tts?.shutdown()
        tts = null
        ready = false
    }
}
