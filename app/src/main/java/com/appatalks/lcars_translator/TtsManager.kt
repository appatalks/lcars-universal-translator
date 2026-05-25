package com.appatalks.lcars_translator

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

private const val TAG = "LcarsTts"
private const val UTTERANCE_PREFIX = "lcars_utt_"

class TtsManager(private val context: Context) {

    private var tts: TextToSpeech? = null
    private var isReady = false
    private var pendingLocale: Locale? = null

    /** The Android audio stream currently used for TTS output. */
    private var currentStream = AudioManager.STREAM_VOICE_CALL

    /** Monotonically increasing ID so each speak() call gets a unique utterance ID. */
    private val utteranceCounter = AtomicInteger(0)

    /** The continuation for the currently active speak() call — cancelled if a new one arrives. */
    @Volatile
    private var activeContinuation: CancellableContinuation<Unit>? = null

    private val audioAttributes
        get() = AudioAttributes.Builder()
            .setUsage(
                if (currentStream == AudioManager.STREAM_VOICE_CALL)
                    AudioAttributes.USAGE_VOICE_COMMUNICATION
                else
                    AudioAttributes.USAGE_MEDIA
            )
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                isReady = true
                Log.d(TAG, "TTS initialized")
                tts?.setAudioAttributes(audioAttributes)
                pendingLocale?.let { applyLocale(it) }
            } else {
                Log.e(TAG, "TTS initialization failed: $status")
            }
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                Log.d(TAG, "TTS done: $utteranceId")
                activeContinuation?.let { cont ->
                    activeContinuation = null
                    cont.resume(Unit)
                }
            }
            @Suppress("DEPRECATION")
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                Log.e(TAG, "TTS error: $utteranceId")
                activeContinuation?.let { cont ->
                    activeContinuation = null
                    cont.resume(Unit)
                }
            }
            override fun onError(utteranceId: String?, errorCode: Int) {
                Log.e(TAG, "TTS error: $utteranceId code=$errorCode")
                activeContinuation?.let { cont ->
                    activeContinuation = null
                    cont.resume(Unit)
                }
            }
        })
    }

    // ── Routing ───────────────────────────────────────────────────────────

    /** Route TTS through BT SCO / earpiece (STREAM_VOICE_CALL). */
    fun routeToSco() {
        currentStream = AudioManager.STREAM_VOICE_CALL
        tts?.setAudioAttributes(audioAttributes)
    }

    /** Route TTS through phone speaker / media (STREAM_MUSIC). */
    fun routeToSpeaker() {
        currentStream = AudioManager.STREAM_MUSIC
        tts?.setAudioAttributes(audioAttributes)
    }

    // ── Settings ──────────────────────────────────────────────────────────

    fun setLanguage(bcp47: String) = applyLocale(Locale.forLanguageTag(bcp47))

    fun applySettings(rate: Float, pitch: Float) {
        tts?.setSpeechRate(rate.coerceIn(0.1f, 4.0f))
        tts?.setPitch(pitch.coerceIn(0.1f, 4.0f))
    }

    // ── Speak ─────────────────────────────────────────────────────────────

    suspend fun speak(text: String): Unit = suspendCancellableCoroutine { cont ->
        if (!isReady || tts == null) { cont.resume(Unit); return@suspendCancellableCoroutine }

        // Cancel any previous in-flight speak() continuation so it doesn't hang
        activeContinuation?.let { prev ->
            activeContinuation = null
            prev.resume(Unit)
        }
        activeContinuation = cont

        val params = Bundle().apply {
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, currentStream)
        }

        val uttId = "$UTTERANCE_PREFIX${utteranceCounter.incrementAndGet()}"
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, uttId)

        cont.invokeOnCancellation {
            tts?.stop()
            activeContinuation = null
        }
    }

    fun stop() { tts?.stop() }

    fun release() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isReady = false
    }

    // ── Internals ─────────────────────────────────────────────────────────

    private fun applyLocale(locale: Locale) {
        if (!isReady) { pendingLocale = locale; return }
        val result = tts?.setLanguage(locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.w(TAG, "TTS language not supported: $locale — falling back to English")
            tts?.setLanguage(Locale.ENGLISH)
        } else {
            Log.d(TAG, "TTS language set to: $locale")
        }
    }
}
