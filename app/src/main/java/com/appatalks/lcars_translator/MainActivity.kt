package com.appatalks.lcars_translator

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    // ── Managers ──────────────────────────────────────────────────────────
    private lateinit var btManager: LcarsBluetoothManager
    private lateinit var speechManager: SpeechInputManager
    private lateinit var translationManager: TranslationManager
    private lateinit var ttsManager: TtsManager
    private lateinit var appSettings: AppSettings
    private lateinit var billingManager: BillingManager

    // ── State ─────────────────────────────────────────────────────────────
    /** "My language" — the user's own language. */
    private var myLang = "ko"
    /** "Their language" — the host/foreign speaker's language. */
    private var theirLang = "en"

    private enum class PttMode { NONE, SPEAK, LISTEN }
    private var activeMode = PttMode.NONE
    private var pttJob: Job? = null

    /** Accumulated final results during a single hold. */
    private val accumulatedFinals = StringBuilder()
    /** Most recent partial text (not yet committed by recognizer). */
    @Volatile private var lastPartialText = ""

    // ── Views ─────────────────────────────────────────────────────────────
    private lateinit var btnBluetooth: Button
    private lateinit var btnSettings: Button
    private lateinit var btnMyLang: Button
    private lateinit var btnTheirLang: Button
    private lateinit var tvHostToListen: TextView      // Top box — hold to listen to host
    private lateinit var tvSpeakToTranslate: TextView  // Bottom box — hold to speak your language
    private lateinit var tvBtStatus: TextView
    private lateinit var tvSystemStatus: TextView
    private lateinit var tvStardate: TextView
    private lateinit var tvBtDeviceName: TextView
    private lateinit var tvBottomStatus: TextView
    private lateinit var viewBtDot: View
    private lateinit var viewListenDot: View
    private lateinit var progressModelDownload: ProgressBar

    // ── Permission launcher ───────────────────────────────────────────────
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val audioGranted = grants[Manifest.permission.RECORD_AUDIO] == true
        if (!audioGranted) {
            showPermissionDeniedDialog(getString(R.string.msg_permission_audio))
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        initManagers()
        updateStardate()
        updateLanguageButtons()

        setupButtonListeners()
        setupPushToTalk()
        observeBluetoothState()
        requestRequiredPermissions()
    }

    override fun onDestroy() {
        super.onDestroy()
        pttJob?.cancel()
        speechManager.release()
        ttsManager.release()
        translationManager.release()
        btManager.release()
        billingManager.release()
    }

    // ── Initialisation ────────────────────────────────────────────────────
    private fun bindViews() {
        btnBluetooth        = findViewById(R.id.btnBluetooth)
        btnSettings         = findViewById(R.id.btnSettings)
        btnMyLang           = findViewById(R.id.btnSourceLang)
        btnTheirLang        = findViewById(R.id.btnTargetLang)
        tvSpeakToTranslate  = findViewById(R.id.tvDetectedSpeech)   // Top box — your language
        tvHostToListen      = findViewById(R.id.tvTranslation)       // Bottom box — their language
        tvBtStatus          = findViewById(R.id.tvBtStatus)
        tvSystemStatus      = findViewById(R.id.tvSystemStatus)
        tvStardate          = findViewById(R.id.tvStardate)
        tvBtDeviceName      = findViewById(R.id.tvBtDeviceName)
        tvBottomStatus      = findViewById(R.id.tvBottomStatus)
        viewBtDot           = findViewById(R.id.viewBtDot)
        viewListenDot       = findViewById(R.id.viewListenDot)
        progressModelDownload = findViewById(R.id.progressModelDownload)
    }

    private fun initManagers() {
        appSettings = AppSettings(this)
        btManager = LcarsBluetoothManager(this)
        speechManager = SpeechInputManager(this)
        translationManager = TranslationManager()
        ttsManager = TtsManager(this)
        billingManager = BillingManager(this, appSettings)
        billingManager.startConnection()
        applyCurrentSettings()
    }

    private fun setupButtonListeners() {
        btnBluetooth.setOnClickListener { showBluetoothSheet() }
        btnMyLang.setOnClickListener { openMyLangPicker() }
        btnTheirLang.setOnClickListener { openTheirLangPicker() }
        btnSettings.setOnClickListener { openSettings() }
    }

    private fun applyCurrentSettings() {
        ttsManager.applySettings(appSettings.ttsRate, appSettings.ttsPitch)
        if (appSettings.usePhoneMic) ttsManager.routeToSpeaker() else ttsManager.routeToSco()
        speechManager.recognitionModel       = appSettings.recognitionModel
        speechManager.silenceTimeoutMs       = appSettings.silenceTimeoutMs
        speechManager.preferOnlineRecognition = appSettings.speechOnlineRecognition
    }

    // ══════════════════════════════════════════════════════════════════════
    //  PUSH-TO-TALK — Bidirectional Translation
    // ══════════════════════════════════════════════════════════════════════

    @SuppressLint("ClickableViewAccessibility")
    private fun setupPushToTalk() {
        // ── Bottom box: "SPEAK TO TRANSLATE" ─────────────────────────────
        // User holds → speaks in MY language → translation appears in TOP box
        tvSpeakToTranslate.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (activeMode != PttMode.NONE) return@setOnTouchListener false
                    if (!hasAudioPermission()) { requestAudioPermission(); return@setOnTouchListener false }
                    if (!speechManager.isAvailable()) { showNoSpeechEngineDialog(); return@setOnTouchListener false }
                    activeMode = PttMode.SPEAK
                    v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    beginHold(
                        recognizeLang  = myLang,
                        partialDisplay = tvSpeakToTranslate,
                        activeBox      = tvSpeakToTranslate
                    )
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (activeMode != PttMode.SPEAK) return@setOnTouchListener false
                    endHold(
                        translateFrom = myLang,
                        translateTo   = theirLang,
                        resultBox     = tvHostToListen,
                        speakInLang   = theirLang,
                        activeBox     = tvSpeakToTranslate
                    )
                    activeMode = PttMode.NONE
                    true
                }
                else -> false
            }
        }

        // ── Top box: "HOST TO LISTEN" ────────────────────────────────────
        // User holds → host speaks in THEIR language → translation appears in BOTTOM box
        tvHostToListen.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (activeMode != PttMode.NONE) return@setOnTouchListener false
                    if (!hasAudioPermission()) { requestAudioPermission(); return@setOnTouchListener false }
                    if (!speechManager.isAvailable()) { showNoSpeechEngineDialog(); return@setOnTouchListener false }
                    activeMode = PttMode.LISTEN
                    v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    beginHold(
                        recognizeLang  = theirLang,
                        partialDisplay = tvHostToListen,
                        activeBox      = tvHostToListen
                    )
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (activeMode != PttMode.LISTEN) return@setOnTouchListener false
                    endHold(
                        translateFrom = theirLang,
                        translateTo   = myLang,
                        resultBox     = tvSpeakToTranslate,
                        speakInLang   = myLang,
                        activeBox     = tvHostToListen
                    )
                    activeMode = PttMode.NONE
                    true
                }
                else -> false
            }
        }
    }

    /**
     * Called on ACTION_DOWN — starts recognition, highlights box, collects partials/finals.
     */
    private fun beginHold(recognizeLang: String, partialDisplay: TextView, activeBox: TextView) {
        accumulatedFinals.clear()
        lastPartialText = ""
        partialDisplay.text = ""

        // Visual: yellow border = active listening
        activeBox.setBackgroundResource(R.drawable.lcars_display_active_bg)
        setStatus(getString(R.string.status_listening))
        setListenDot(0xFFFFFF33.toInt())

        // Stop any in-flight TTS
        ttsManager.stop()

        // Start speech recognition (loops so long speech isn't clipped)
        speechManager.startListening(recognizeLang)

        pttJob = lifecycleScope.launch {
            // Partials → live display
            launch {
                speechManager.partials.collect { text ->
                    lastPartialText = text
                    val display = if (accumulatedFinals.isNotEmpty()) {
                        "${accumulatedFinals}$text"
                    } else text
                    partialDisplay.text = display
                }
            }
            // Finals → accumulate sentences
            launch {
                speechManager.finals.collect { text ->
                    accumulatedFinals.append(text).append(" ")
                    lastPartialText = ""
                    partialDisplay.text = accumulatedFinals.toString().trim()
                }
            }
        }
    }

    /**
     * Called on ACTION_UP — stops recognition, translates accumulated text,
     * displays result in the OTHER box, speaks it aloud.
     */
    private fun endHold(
        translateFrom: String,
        translateTo: String,
        resultBox: TextView,
        speakInLang: String,
        activeBox: TextView
    ) {
        speechManager.stopListening()
        pttJob?.cancel()
        pttJob = null

        // Restore original box background
        if (activeBox === tvHostToListen) {
            activeBox.setBackgroundResource(R.drawable.lcars_display_bg)
        } else {
            activeBox.setBackgroundResource(R.drawable.lcars_display_blue_bg)
        }

        // Best text = accumulated finals + any trailing partial
        val text = buildString {
            append(accumulatedFinals)
            if (lastPartialText.isNotBlank()) append(lastPartialText)
        }.trim()

        if (text.isBlank()) {
            setStatus(getString(R.string.status_no_speech))
            setListenDot(0xFF888888.toInt())
            return
        }

        // Show captured text in the active box
        activeBox.text = text

        // Translate and display in the other box
        setStatus(getString(R.string.status_translating))
        setListenDot(0xFFCC44CC.toInt()) // Purple = translating

        lifecycleScope.launch {
            val translated = translationManager.translate(
                text        = text,
                sourceLang  = translateFrom,
                targetLang  = translateTo,
                onModelDownloading = {
                    runOnUiThread {
                        progressModelDownload.visibility = View.VISIBLE
                        setStatus(getString(R.string.status_downloading))
                    }
                },
                onModelReady = {
                    runOnUiThread { progressModelDownload.visibility = View.GONE }
                }
            )

            runOnUiThread {
                resultBox.text = translated
                progressModelDownload.visibility = View.GONE
            }

            // Speak the translation aloud
            if (appSettings.autoSpeak && translated.isNotBlank()) {
                runOnUiThread {
                    setStatus(getString(R.string.status_speaking))
                    setListenDot(0xFF9999FF.toInt())
                }
                ttsManager.setLanguage(speakInLang)
                ttsManager.speak(translated)
            }

            runOnUiThread {
                setStatus(getString(R.string.status_idle))
                setListenDot(0xFF888888.toInt())
            }
        }
    }

    // ── Bluetooth ─────────────────────────────────────────────────────────
    private fun showBluetoothSheet() {
        if (!hasBtPermission()) {
            requestBtPermission()
            return
        }
        val paired = btManager.getPairedDevices()
        if (paired.isEmpty()) {
            setStatus("No paired Bluetooth devices found")
            return
        }
        BluetoothSelectorSheet.newInstance(paired) { device ->
            setStatus(getString(R.string.status_bt_connecting))
            btManager.connectDevice(device)
        }.show(supportFragmentManager, "bt_selector")
    }

    private fun observeBluetoothState() {
        lifecycleScope.launch {
            btManager.state.collectLatest { state ->
                when (state) {
                    BtState.UNAVAILABLE -> {
                        setBtStatus(getString(R.string.status_no_bt), 0xFF888888.toInt())
                        btnBluetooth.isEnabled = false
                    }
                    BtState.DISABLED -> {
                        setBtStatus("Bluetooth off", 0xFFFF3300.toInt())
                    }
                    BtState.IDLE -> {
                        setBtStatus(getString(R.string.status_bt_disconnected), 0xFFFF3300.toInt())
                        btnBluetooth.setText(R.string.btn_bt_label)
                        tvBtDeviceName.visibility = View.GONE
                    }
                    BtState.CONNECTING, BtState.SCO_CONNECTING -> {
                        setBtStatus(getString(R.string.status_bt_connecting), 0xFFFFFF33.toInt())
                    }
                    BtState.CONNECTED -> {
                        setBtStatus("BT Connected", 0xFF33FF33.toInt())
                        showDeviceChip()
                        if (appSettings.usePhoneMic) {
                            setBtStatus("BT Ready (Phone Mic)", 0xFF33FF33.toInt())
                            btnBluetooth.setText(R.string.btn_bt_active)
                            ttsManager.routeToSpeaker()
                        } else {
                            btManager.connectSco()
                        }
                    }
                    BtState.SCO_ACTIVE -> {
                        setBtStatus(getString(R.string.status_bt_connected), 0xFF33FF33.toInt())
                        showDeviceChip()
                        btnBluetooth.setText(R.string.btn_bt_active)
                        val am = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
                        val maxVol = am.getStreamMaxVolume(android.media.AudioManager.STREAM_VOICE_CALL)
                        val vol = (maxVol * appSettings.outputVolume / 100.0f).toInt().coerceIn(0, maxVol)
                        am.setStreamVolume(android.media.AudioManager.STREAM_VOICE_CALL, vol, 0)
                    }
                    BtState.ERROR -> {
                        setBtStatus("BT Error — using phone mic", 0xFFFF3300.toInt())
                    }
                }
            }
        }
    }

    private fun setBtStatus(text: String, dotColor: Int) {
        tvBtStatus.text = text
        viewBtDot.backgroundTintList =
            android.content.res.ColorStateList.valueOf(dotColor)
    }

    private fun showDeviceChip() {
        val name = btManager.connectedDeviceName.value ?: return
        tvBtDeviceName.text = name.uppercase()
        tvBtDeviceName.visibility = View.VISIBLE
    }

    // ── Settings ──────────────────────────────────────────────────────────
    private fun openSettings() {
        supportFragmentManager.setFragmentResultListener(SettingsSheet.RESULT_KEY, this) { _, _ ->
            appSettings = AppSettings(this)
            applyCurrentSettings()
            setStatus("Settings applied")
        }
        supportFragmentManager.setFragmentResultListener(SettingsSheet.RESULT_KEY_SUPPORT, this) { _, _ ->
            billingManager.launchSupportPurchase()
        }
        SettingsSheet.newInstance().show(supportFragmentManager, "settings")
    }

    private fun openMyLangPicker() {
        LanguageSelectorSheet.newInstance(isSource = true) { entry ->
            myLang = entry.bcp47Tag
            updateLanguageButtons()
            if (myLang != LanguageData.AUTO_DETECT) {
                lifecycleScope.launch {
                    translationManager.warmUp(myLang, theirLang,
                        onDownloading = { runOnUiThread { progressModelDownload.visibility = View.VISIBLE } },
                        onReady = { runOnUiThread { progressModelDownload.visibility = View.GONE } }
                    )
                    translationManager.warmUp(theirLang, myLang,
                        onDownloading = { runOnUiThread { progressModelDownload.visibility = View.VISIBLE } },
                        onReady = { runOnUiThread { progressModelDownload.visibility = View.GONE } }
                    )
                }
            }
        }.show(supportFragmentManager, "my_lang")
    }

    private fun openTheirLangPicker() {
        LanguageSelectorSheet.newInstance(isSource = false) { entry ->
            theirLang = entry.bcp47Tag
            updateLanguageButtons()
            lifecycleScope.launch {
                val myResolved = if (myLang == LanguageData.AUTO_DETECT) "en" else myLang
                translationManager.warmUp(myResolved, theirLang,
                    onDownloading = { runOnUiThread { progressModelDownload.visibility = View.VISIBLE } },
                    onReady = { runOnUiThread { progressModelDownload.visibility = View.GONE } }
                )
                translationManager.warmUp(theirLang, myResolved,
                    onDownloading = { runOnUiThread { progressModelDownload.visibility = View.VISIBLE } },
                    onReady = { runOnUiThread { progressModelDownload.visibility = View.GONE } }
                )
            }
        }.show(supportFragmentManager, "their_lang")
    }

    private fun updateLanguageButtons() {
        val myName = LanguageData.findByTag(myLang)?.displayName ?: "Auto"
        val theirName = LanguageData.findByTag(theirLang)?.displayName ?: theirLang
        btnMyLang.text = myName.uppercase()
        btnTheirLang.text = theirName.uppercase()
    }

    // ── Stardate ──────────────────────────────────────────────────────────
    private fun updateStardate() {
        val dayOfYear = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_YEAR)
        val year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        val stardate = ((year - 2323) * 1000 + dayOfYear * 2.7).toInt()
        tvStardate.text = getString(R.string.stardate_format, stardate)
    }

    // ── UI Helpers ────────────────────────────────────────────────────────
    private fun setStatus(text: String) {
        runOnUiThread { tvSystemStatus.text = text }
    }

    private fun setListenDot(color: Int) {
        runOnUiThread {
            viewListenDot.backgroundTintList =
                android.content.res.ColorStateList.valueOf(color)
        }
    }

    // ── Permissions ───────────────────────────────────────────────────────
    private fun requestRequiredPermissions() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(perms.toTypedArray())
    }

    private fun requestAudioPermission() {
        permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
    }

    private fun requestBtPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissionLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT))
        }
    }

    private fun hasAudioPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

    private fun hasBtPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
                    PackageManager.PERMISSION_GRANTED
        } else true
    }

    // ── Dialogs ───────────────────────────────────────────────────────────
    private fun showPermissionDeniedDialog(message: String) {
        AlertDialog.Builder(this)
            .setTitle("Permission Required")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showNoSpeechEngineDialog() {
        AlertDialog.Builder(this)
            .setTitle("Speech Recognition Unavailable")
            .setMessage(getString(R.string.msg_no_speech_engine))
            .setPositiveButton("OK", null)
            .show()
    }
}





