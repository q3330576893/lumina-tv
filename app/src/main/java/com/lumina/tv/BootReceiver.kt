package com.lumina.tv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 开机自启（设置页可开关，默认关）。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (AppRuntime.prefs?.bootLaunch != true) return
        runCatching {
            val i = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
        }
    }
}
