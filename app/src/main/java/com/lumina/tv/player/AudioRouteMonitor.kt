package com.lumina.tv.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log

class AudioRouteMonitor(
    context: Context,
    private val onCapsChanged: (AudioCaps) -> Unit,
) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var lastCaps = detect(appContext)
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = refresh()
    }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refresh()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refresh()
    }

    fun register() {
        if (registered) return
        registered = true
        val filter = IntentFilter(AudioManager.ACTION_HDMI_AUDIO_PLUG)
        if (Build.VERSION.SDK_INT >= 33) appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") appContext.registerReceiver(receiver, filter)
        audioManager.registerAudioDeviceCallback(deviceCallback, null)
    }

    fun unregister() {
        if (!registered) return
        registered = false
        runCatching { appContext.unregisterReceiver(receiver) }
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
    }

    private fun refresh() {
        val newCaps = detect(appContext)
        if (newCaps == lastCaps) return
        lastCaps = newCaps
        val encodings = newCaps.profiles.keys.filter { it != android.media.AudioFormat.ENCODING_PCM_16BIT }.sorted()
        val maxChannels = newCaps.profiles.values.maxOfOrNull { it.first } ?: 2
        Log.i("LuminaTV", "audioRoute changed encodings=$encodings maxCh=$maxChannels")
        onCapsChanged(newCaps)
    }
}
