package com.lumina.tv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.lumina.tv.diag.PageLog

/** 开机自启（设置页可开关，默认关）。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        runCatching {
            AppRuntime.init(context)
            if (AppRuntime.prefs?.bootLaunch != true) {
                PageLog.add("bootLaunch disabled")
                Log.i("BootReceiver", "Boot launch disabled")
                return
            }
            val i = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
            PageLog.add("bootLaunch activity requested")
            Log.i("BootReceiver", "MainActivity launch requested after boot")
        }.onFailure { error ->
            PageLog.add("bootLaunch failed=${error.javaClass.simpleName}: ${error.message}")
            Log.e("BootReceiver", "Unable to launch MainActivity after boot", error)
        }
    }
}
