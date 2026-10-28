package com.alatta.nativeapp

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioAttributes
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.webrtc.MediaConstraints
import org.webrtc.audio.JavaAudioDeviceModule

internal fun watchAudioModule(context: Context): JavaAudioDeviceModule = JavaAudioDeviceModule.builder(context)
    .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
    .setUseHardwareAcousticEchoCanceler(true).setUseHardwareNoiseSuppressor(true)
    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()).createAudioDeviceModule()

internal fun watchMicrophoneConstraints() = MediaConstraints().apply {
    mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
    mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
    mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
}

/** Communication routing for the mic-only room; never captures playback or requests exclusive media focus. */
@Suppress("DEPRECATION")
internal class WatchAudioRoute(context: Context) {
    private val manager = context.getSystemService(AudioManager::class.java)
    private val previousMode = manager.mode
    private val previousSpeaker = manager.isSpeakerphoneOn
    private val previousDevice = if (Build.VERSION.SDK_INT >= 31) runCatching { manager.communicationDevice }.getOrNull() else null
    private val previousSco = manager.isBluetoothScoOn
    private var startedSco = false
    private var started = false
    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(devices: Array<out AudioDeviceInfo>?) { route() }
        override fun onAudioDevicesRemoved(devices: Array<out AudioDeviceInfo>?) { route() }
    }
    fun start() {
        if (started) return
        started = true
        manager.mode = AudioManager.MODE_IN_COMMUNICATION
        manager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        route()
    }
    private fun route() {
        if (!started) return
        if (Build.VERSION.SDK_INT >= 31) {
            val devices = runCatching { manager.availableCommunicationDevices }.getOrDefault(emptyList())
            val preferred = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }
                ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it.type == AudioDeviceInfo.TYPE_USB_HEADSET }
                ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            if (preferred != null) runCatching { manager.setCommunicationDevice(preferred) }
        } else {
            val headset = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
            }
            manager.isSpeakerphoneOn = !headset
            if (!previousSco && !startedSco && manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }) {
                runCatching { manager.startBluetoothSco(); manager.isBluetoothScoOn = true; startedSco = true }
            }
        }
    }
    fun close() {
        if (!started) return
        started = false
        runCatching { manager.unregisterAudioDeviceCallback(callback) }
        if (Build.VERSION.SDK_INT >= 31) runCatching {
            if (previousDevice != null) manager.setCommunicationDevice(previousDevice) else manager.clearCommunicationDevice()
        } else if (startedSco) runCatching { manager.stopBluetoothSco(); manager.isBluetoothScoOn = previousSco }
        runCatching { manager.isSpeakerphoneOn = previousSpeaker; manager.mode = previousMode }
    }
}
