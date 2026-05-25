package com.appatalks.lcars_translator

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.channels.BufferOverflow

private const val TAG = "LcarsSpeech"

class SpeechInputManager(private val context: Context) {

    // ── Public Flows ──────────────────────────────────────────────────────
    /**
     * Live partial text — update the display, never trigger translation.
     * Uses DROP_OLDEST so the UI always shows the freshest in-progress text.
     */
    private val _partials = MutableSharedFlow<String>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val partials: SharedFlow<String> = _partials.asSharedFlow()

    /**
     * Committed final sentences — trigger translation from here.
     * Buffers up to 4 sentences; drops oldest if the translator is overwhelmed.
     */
    private val _finals = MutableSharedFlow<String>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val finals: SharedFlow<String> = _finals.asSharedFlow()

    // ── Settings ──────────────────────────────────────────────────────────
    var recognitionModel: String = AppSettings.MODEL_FREE_FORM
    var preferOnlineRecognition: Boolean = true
    /** Lower = snappier response, less gap between sentences. Default 1500 ms. */
    var silenceTimeoutMs: Int = 1500

    // ── Internal state ────────────────────────────────────────────────────
    private var recognizer: SpeechRecognizer? = null
    private var isListening = false
    private var shouldLoop = false
    private var currentLanguage = "und"
    private val handler = Handler(Looper.getMainLooper())

    /**
     * Safety-net restart: if onResults never fires within 2 s of onEndOfSpeech
     * (e.g. server stall), force a restart so we don't get stuck silent.
     */
    private val restartFallback = Runnable {
        Log.w(TAG, "Fallback restart — recognizer stalled")
        if (shouldLoop) { destroyAndRestart(0) }
    }

    // ── RecognitionListener ──────────────────────────────────────────────
    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partial: Bundle?) {
            partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { _partials.tryEmit(it) }
        }

        override fun onEndOfSpeech() {
            Log.d(TAG, "End of speech — awaiting results")
            // Schedule fallback in case the server stalls
            handler.postDelayed(restartFallback, 2000)
        }

        override fun onResults(results: Bundle?) {
            handler.removeCallbacks(restartFallback)
            results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { text ->
                    Log.d(TAG, "Final: $text")
                    _partials.tryEmit(text)   // freeze display on final text
                    _finals.tryEmit(text)     // trigger translation
                }
            // Restart immediately — this is the critical path.
            // The sooner we restart, the smaller the gap between sentences.
            destroyAndRestart(0)
        }

        override fun onError(error: Int) {
            handler.removeCallbacks(restartFallback)
            Log.w(TAG, "SpeechRecognizer error: ${errorName(error)}")
            if (!shouldLoop) { destroyOnly(); return }

            val delay = when (error) {
                // Silence / no match is not a real error — restart instantly
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT   -> 50L
                // Busy: another session is still active — wait a bit
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY  -> 400L
                // No permission: don't loop anymore
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    shouldLoop = false; destroyOnly(); return
                }
                else -> 150L
            }
            destroyAndRestart(delay)
        }
    }

    // ── Public API ────────────────────────────────────────────────────────

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    fun startListening(languageBcp47: String = "und") {
        if (isListening) return
        currentLanguage = languageBcp47
        isListening = true
        shouldLoop = true
        startNow()
    }

    fun stopListening() {
        shouldLoop = false
        isListening = false
        handler.removeCallbacks(restartFallback)
        destroyOnly()
        Log.d(TAG, "Listening stopped")
    }

    fun release() = stopListening()

    // ── Internals ─────────────────────────────────────────────────────────

    private fun startNow() {
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).also {
            it.setRecognitionListener(listener)
            it.startListening(buildIntent())
        }
        Log.d(TAG, "Recognizer started (lang=$currentLanguage, online=$preferOnlineRecognition, silence=${silenceTimeoutMs}ms)")
    }

    private fun destroyOnly() {
        recognizer?.stopListening()
        recognizer?.destroy()
        recognizer = null
    }

    private fun destroyAndRestart(delayMs: Long) {
        destroyOnly()
        if (!shouldLoop) return
        if (delayMs == 0L) startNow()
        else handler.postDelayed({ if (shouldLoop) startNow() }, delayMs)
    }

    private fun buildIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(
            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            if (recognitionModel == AppSettings.MODEL_WEB_SEARCH)
                RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH
            else
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
        )
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)          // Top 3 candidates → better accuracy
        // Silence tuning — drives latency between sentences
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,          silenceTimeoutMs.toLong())
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, (silenceTimeoutMs / 2).toLong())
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 0L) // capture single words
        // Language
        if (currentLanguage.isNotBlank() && currentLanguage != "und") {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE,            currentLanguage)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, currentLanguage)
        }
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, !preferOnlineRecognition)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
    }

    private fun errorName(e: Int) = when (e) {
        SpeechRecognizer.ERROR_AUDIO                   -> "AUDIO"
        SpeechRecognizer.ERROR_CLIENT                  -> "CLIENT"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS-> "NO_PERMISSION"
        SpeechRecognizer.ERROR_NETWORK                 -> "NETWORK"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT         -> "NETWORK_TIMEOUT"
        SpeechRecognizer.ERROR_NO_MATCH                -> "NO_MATCH"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY         -> "BUSY"
        SpeechRecognizer.ERROR_SERVER                  -> "SERVER"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT          -> "SPEECH_TIMEOUT"
        else                                           -> "UNKNOWN($e)"
    }
}
