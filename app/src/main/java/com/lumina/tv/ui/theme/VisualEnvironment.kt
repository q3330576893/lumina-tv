package com.lumina.tv.ui.theme

import android.app.ActivityManager
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

/** Observe all animation scales so accessibility changes apply without an activity restart. */
@Composable
internal fun ObserveVisualEnvironment() {
    val context = LocalContext.current
    DisposableEffect(context) {
        val manager = context.getSystemService(ActivityManager::class.java)
        ThemeRuntime.updateDeviceCapabilities(Build.VERSION.SDK_INT, manager?.isLowRamDevice != false)
        val resolver = context.contentResolver
        val keys = listOf(Settings.Global.ANIMATOR_DURATION_SCALE,
            Settings.Global.TRANSITION_ANIMATION_SCALE, Settings.Global.WINDOW_ANIMATION_SCALE)
        fun updateMotion() {
            ThemeRuntime.updateSystemMotion(keys.any { Settings.Global.getFloat(resolver, it, 1f) == 0f })
        }
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = updateMotion()
        }
        keys.forEach { resolver.registerContentObserver(Settings.Global.getUriFor(it), false, observer) }
        updateMotion()
        onDispose { resolver.unregisterContentObserver(observer) }
    }
}
