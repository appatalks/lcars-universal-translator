package com.appatalks.lcars_translator

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "LcarsBluetooth"

enum class BtState {
    UNAVAILABLE,      // Device doesn't have Bluetooth
    DISABLED,         // Bluetooth is off
    IDLE,             // BT on, no device selected
    CONNECTING,       // Trying to connect headset profile
    CONNECTED,        // Headset profile connected
    SCO_CONNECTING,   // Starting SCO audio channel
    SCO_ACTIVE,       // SCO audio channel is live (mic + speaker via BT)
    ERROR
}

class LcarsBluetoothManager(private val context: Context) {

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter
    private val audioManager: AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _state = MutableStateFlow(BtState.IDLE)
    val state: StateFlow<BtState> = _state.asStateFlow()

    private val _connectedDeviceName = MutableStateFlow<String?>(null)
    val connectedDeviceName: StateFlow<String?> = _connectedDeviceName.asStateFlow()

    private var headsetProxy: BluetoothHeadset? = null
    private var selectedDevice: BluetoothDevice? = null

    // ── Profile proxy listener ─────────────────────────────────────────────
    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile == BluetoothProfile.HEADSET) {
                headsetProxy = proxy as BluetoothHeadset
                Log.d(TAG, "Headset profile proxy connected")
                selectedDevice?.let { connectSco() }
            }
        }
        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.HEADSET) {
                headsetProxy = null
                Log.d(TAG, "Headset profile proxy disconnected")
            }
        }
    }

    // ── SCO state broadcast receiver ──────────────────────────────────────
    private val scoReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)) {
                AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
                    Log.d(TAG, "SCO audio connected")
                    _state.value = BtState.SCO_ACTIVE
                }
                AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> {
                    Log.d(TAG, "SCO audio disconnected")
                    if (_state.value == BtState.SCO_ACTIVE || _state.value == BtState.SCO_CONNECTING) {
                        _state.value = BtState.CONNECTED
                    }
                }
                AudioManager.SCO_AUDIO_STATE_ERROR -> {
                    Log.e(TAG, "SCO audio error")
                    _state.value = BtState.ERROR
                }
            }
        }
    }

    init {
        if (bluetoothAdapter == null) {
            _state.value = BtState.UNAVAILABLE
        } else if (!bluetoothAdapter.isEnabled) {
            _state.value = BtState.DISABLED
        }

        val filter = IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(scoReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(scoReceiver, filter)
        }
    }

    // ── Public API ────────────────────────────────────────────────────────

    /** Returns the list of already-paired Bluetooth devices (no scan needed). */
    @SuppressLint("MissingPermission")
    fun getPairedDevices(): List<BluetoothDevice> {
        return bluetoothAdapter?.bondedDevices?.toList() ?: emptyList()
    }

    /**
     * Selects a device and initiates the headset profile connection + SCO audio.
     */
    @SuppressLint("MissingPermission")
    fun connectDevice(device: BluetoothDevice) {
        selectedDevice = device
        _connectedDeviceName.value = device.name ?: device.address
        _state.value = BtState.CONNECTING

        // Get headset profile proxy (triggers onServiceConnected)
        bluetoothAdapter?.getProfileProxy(context, profileListener, BluetoothProfile.HEADSET)
    }

    /** Starts the Bluetooth SCO audio channel for mic + speaker routing. */
    @Suppress("DEPRECATION")
    fun connectSco() {
        _state.value = BtState.SCO_CONNECTING
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isBluetoothScoOn = true
        audioManager.startBluetoothSco()
        Log.d(TAG, "startBluetoothSco() called")
    }

    /** Stops SCO and releases the audio mode. */
    @Suppress("DEPRECATION")
    fun disconnectSco() {
        audioManager.stopBluetoothSco()
        audioManager.isBluetoothScoOn = false
        audioManager.mode = AudioManager.MODE_NORMAL
        _state.value = BtState.CONNECTED
        Log.d(TAG, "stopBluetoothSco() called")
    }

    /** Fully disconnects the selected device. */
    @SuppressLint("MissingPermission")
    fun disconnect() {
        disconnectSco()
        headsetProxy?.let {
            bluetoothAdapter?.closeProfileProxy(BluetoothProfile.HEADSET, it)
        }
        headsetProxy = null
        selectedDevice = null
        _connectedDeviceName.value = null
        _state.value = BtState.IDLE
    }

    /** Returns true if SCO is currently active (audio routing through BT). */
    fun isScoActive(): Boolean = _state.value == BtState.SCO_ACTIVE

    /** Must be called from Activity.onDestroy() to clean up. */
    fun release() {
        try {
            context.unregisterReceiver(scoReceiver)
        } catch (_: Exception) {}
        disconnect()
    }
}




