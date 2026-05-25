package com.appatalks.lcars_translator

import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.os.bundleOf
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class SettingsSheet : BottomSheetDialogFragment() {

    companion object {
        /** Key used for FragmentResultListener to know settings were applied. */
        const val RESULT_KEY = "settings_applied"

        fun newInstance(): SettingsSheet = SettingsSheet()
    }

    // Working copies — loaded from prefs when sheet opens
    private var outputVolume         = 80
    private var ttsRate              = 1.0f
    private var ttsPitch             = 1.0f
    private var autoSpeak            = true
    private var usePhoneMic          = false
    private var speechOnline         = true
    private var recognitionModel     = AppSettings.MODEL_FREE_FORM
    private var silenceTimeoutMs     = 1500

    private lateinit var settings: AppSettings
    private lateinit var audioManager: AudioManager

    // ── Lifecycle ─────────────────────────────────────────────────────────
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.bottom_sheet_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        settings     = AppSettings(requireContext())
        audioManager = requireContext().getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // Load current values
        outputVolume     = settings.outputVolume
        ttsRate          = settings.ttsRate
        ttsPitch         = settings.ttsPitch
        autoSpeak        = settings.autoSpeak
        usePhoneMic      = settings.usePhoneMic
        speechOnline     = settings.speechOnlineRecognition
        recognitionModel = settings.recognitionModel
        silenceTimeoutMs = settings.silenceTimeoutMs

        setupVolumeSeekBar(view)
        setupAutoSpeakSwitch(view)
        setupMicSourceToggle(view)
        setupTtsRateSeekBar(view)
        setupTtsPitchSeekBar(view)
        setupRecognitionQualityToggle(view)
        setupRecognitionModelToggle(view)
        setupSilenceTimeoutSeekBar(view)
        setupEngageButton(view)
    }

    // ── Audio Output ──────────────────────────────────────────────────────

    private fun setupVolumeSeekBar(view: View) {
        val maxVol  = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
        val seekBar = view.findViewById<SeekBar>(R.id.seekVolume)
        val tvValue = view.findViewById<TextView>(R.id.tvVolumeValue)

        seekBar.max      = 100
        seekBar.progress = outputVolume
        tvValue.text     = "$outputVolume%"

        seekBar.setOnSeekBarChangeListener(onProgress { p ->
            outputVolume = p
            tvValue.text = "$p%"
            // Live preview
            val vol = (maxVol * p / 100.0f).toInt().coerceIn(0, maxVol)
            audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, vol, 0)
        })
    }

    private fun setupAutoSpeakSwitch(view: View) {
        val btnOn  = view.findViewById<Button>(R.id.btnAutoSpeakOn)
        val btnOff = view.findViewById<Button>(R.id.btnAutoSpeakOff)

        fun refresh() {
            btnOn.backgroundTintList  = csl(if (autoSpeak)  0xFFFF7700.toInt() else 0xFF333333.toInt())
            btnOff.backgroundTintList = csl(if (!autoSpeak) 0xFF9999FF.toInt() else 0xFF333333.toInt())
        }
        refresh()
        btnOn.setOnClickListener  { autoSpeak = true;  refresh() }
        btnOff.setOnClickListener { autoSpeak = false; refresh() }
    }

    // ── Mic Source ────────────────────────────────────────────────────────

    private fun setupMicSourceToggle(view: View) {
        val btnPhone = view.findViewById<Button>(R.id.btnMicPhone)
        val btnBt    = view.findViewById<Button>(R.id.btnMicBluetooth)

        fun refresh() {
            btnPhone.backgroundTintList = csl(if (usePhoneMic)  0xFFFF9966.toInt() else 0xFF333333.toInt())
            btnBt.backgroundTintList    = csl(if (!usePhoneMic) 0xFF9999FF.toInt() else 0xFF333333.toInt())
        }
        refresh()
        btnPhone.setOnClickListener { usePhoneMic = true;  refresh() }
        btnBt.setOnClickListener    { usePhoneMic = false; refresh() }
    }

    // ── Voice Synthesis ───────────────────────────────────────────────────

    private fun setupTtsRateSeekBar(view: View) {
        // 0–30 → 0.5×–2.0× in 0.05 steps
        val seekBar = view.findViewById<SeekBar>(R.id.seekTtsRate)
        val tvValue = view.findViewById<TextView>(R.id.tvTtsRateValue)

        seekBar.max      = 30
        seekBar.progress = ((ttsRate - 0.5f) / 0.05f).toInt().coerceIn(0, 30)
        tvValue.text     = String.format("%.1f×", ttsRate)

        seekBar.setOnSeekBarChangeListener(onProgress { p ->
            ttsRate      = 0.5f + p * 0.05f
            tvValue.text = String.format("%.1f×", ttsRate)
        })
    }

    private fun setupTtsPitchSeekBar(view: View) {
        // 0–30 → 0.5–2.0
        val seekBar = view.findViewById<SeekBar>(R.id.seekTtsPitch)
        val tvValue = view.findViewById<TextView>(R.id.tvTtsPitchValue)

        seekBar.max      = 30
        seekBar.progress = ((ttsPitch - 0.5f) / 0.05f).toInt().coerceIn(0, 30)
        tvValue.text     = String.format("%.2f", ttsPitch)

        seekBar.setOnSeekBarChangeListener(onProgress { p ->
            ttsPitch     = 0.5f + p * 0.05f
            tvValue.text = String.format("%.2f", ttsPitch)
        })
    }

    // ── Speech Recognition ────────────────────────────────────────────────

    private fun setupRecognitionQualityToggle(view: View) {
        val btnEnhanced = view.findViewById<Button>(R.id.btnQualityEnhanced)
        val btnOffline  = view.findViewById<Button>(R.id.btnQualityOffline)

        fun refresh() {
            btnEnhanced.backgroundTintList = csl(if (speechOnline)  0xFFCC44CC.toInt() else 0xFF333333.toInt())
            btnOffline.backgroundTintList  = csl(if (!speechOnline) 0xFF9999FF.toInt() else 0xFF333333.toInt())
        }
        refresh()
        btnEnhanced.setOnClickListener { speechOnline = true;  refresh() }
        btnOffline.setOnClickListener  { speechOnline = false; refresh() }
    }

    private fun setupRecognitionModelToggle(view: View) {
        val btnFree   = view.findViewById<Button>(R.id.btnModelFreeForm)
        val btnSearch = view.findViewById<Button>(R.id.btnModelWebSearch)

        fun refresh() {
            val isFree = recognitionModel == AppSettings.MODEL_FREE_FORM
            btnFree.backgroundTintList   = csl(if (isFree)  0xFFFF9966.toInt() else 0xFF333333.toInt())
            btnSearch.backgroundTintList = csl(if (!isFree) 0xFF9999FF.toInt() else 0xFF333333.toInt())
        }
        refresh()
        btnFree.setOnClickListener   { recognitionModel = AppSettings.MODEL_FREE_FORM;  refresh() }
        btnSearch.setOnClickListener { recognitionModel = AppSettings.MODEL_WEB_SEARCH; refresh() }
    }

    private fun setupSilenceTimeoutSeekBar(view: View) {
        // 0–25 → 500ms–3000ms in 100ms steps
        val seekBar = view.findViewById<SeekBar>(R.id.seekSilenceTimeout)
        val tvValue = view.findViewById<TextView>(R.id.tvSilenceTimeoutValue)

        seekBar.max      = 25
        seekBar.progress = ((silenceTimeoutMs - 500) / 100).coerceIn(0, 25)
        tvValue.text     = "${silenceTimeoutMs}ms"

        seekBar.setOnSeekBarChangeListener(onProgress { p ->
            silenceTimeoutMs = 500 + p * 100
            tvValue.text     = "${silenceTimeoutMs}ms"
        })
    }

    // ── Engage ────────────────────────────────────────────────────────────

    private fun setupEngageButton(view: View) {
        view.findViewById<Button>(R.id.btnEngageSettings).setOnClickListener {
            // Batch all writes into a single SharedPreferences transaction
            val prefs = requireContext().getSharedPreferences("lcars_settings", Context.MODE_PRIVATE)
            prefs.edit()
                .putInt("output_volume", outputVolume)
                .putFloat("tts_rate", ttsRate)
                .putFloat("tts_pitch", ttsPitch)
                .putBoolean("auto_speak", autoSpeak)
                .putBoolean("use_phone_mic", usePhoneMic)
                .putBoolean("speech_online_recognition", speechOnline)
                .putString("recognition_model", recognitionModel)
                .putInt("silence_timeout_ms", silenceTimeoutMs)
                .apply()

            // Notify the host activity via Fragment Result API (survives config changes)
            parentFragmentManager.setFragmentResult(RESULT_KEY, bundleOf())
            dismiss()
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun csl(color: Int) = android.content.res.ColorStateList.valueOf(color)

    private fun onProgress(block: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) = block(p)
        override fun onStartTrackingTouch(sb: SeekBar) {}
        override fun onStopTrackingTouch(sb: SeekBar) {}
    }
}
