package com.appatalks.lcars_translator

import android.content.Context

/**
 * Persistent settings backed by SharedPreferences.
 * All properties read/write immediately — no explicit save() needed.
 */
class AppSettings(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "lcars_settings"

        // Speech recognition model constants
        const val MODEL_FREE_FORM  = "FREE_FORM"   // General conversation
        const val MODEL_WEB_SEARCH = "WEB_SEARCH"  // Shorter, command-style phrases

        // Translation engine constants
        const val ENGINE_ON_DEVICE = "ON_DEVICE"   // Google ML Kit — offline, ~30 MB/pair
    }

    /** TTS / speaker output volume 0–100 (percent of STREAM_VOICE_CALL max). */
    var outputVolume: Int
        get() = prefs.getInt("output_volume", 80)
        set(v) { prefs.edit().putInt("output_volume", v).apply() }

    /** TextToSpeech playback speed. Range 0.5x (slow) – 2.0x (fast). Default 1.0x. */
    var ttsRate: Float
        get() = prefs.getFloat("tts_rate", 1.0f)
        set(v) { prefs.edit().putFloat("tts_rate", v).apply() }

    /** TextToSpeech pitch. Range 0.5 (low) – 2.0 (high). Default 1.0. */
    var ttsPitch: Float
        get() = prefs.getFloat("tts_pitch", 1.0f)
        set(v) { prefs.edit().putFloat("tts_pitch", v).apply() }

    /** Whether to automatically speak the translation after it's ready. */
    var autoSpeak: Boolean
        get() = prefs.getBoolean("auto_speak", true)
        set(v) { prefs.edit().putBoolean("auto_speak", v).apply() }

    /** SpeechRecognizer language model — FREE_FORM or WEB_SEARCH. */
    var recognitionModel: String
        get() = prefs.getString("recognition_model", MODEL_FREE_FORM) ?: MODEL_FREE_FORM
        set(v) { prefs.edit().putString("recognition_model", v).apply() }

    /**
     * Milliseconds of silence after speech ends before the recognizer commits a result.
     * Lower = faster response but may cut off speech. Range 500–3000 ms.
     */
    var silenceTimeoutMs: Int
        get() = prefs.getInt("silence_timeout_ms", 1500)
        set(v) { prefs.edit().putInt("silence_timeout_ms", v).apply() }


    /**
     * When true: use the phone's built-in mic (higher audio quality, better recognition accuracy).
     * When false: use the Bluetooth headset mic via SCO (wireless, but 8kHz narrowband).
     */
    var usePhoneMic: Boolean
        get() = prefs.getBoolean("use_phone_mic", false)
        set(v) { prefs.edit().putBoolean("use_phone_mic", v).apply() }

    /**
     * When true: SpeechRecognizer is allowed to use Google's servers (better accuracy, needs internet).
     * When false: forces fully on-device recognition (works offline, lower accuracy).
     */
    var speechOnlineRecognition: Boolean
        get() = prefs.getBoolean("speech_online_recognition", true)
        set(v) { prefs.edit().putBoolean("speech_online_recognition", v).apply() }
}


