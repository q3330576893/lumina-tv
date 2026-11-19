package com.lumina.tv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.app.admin.DevicePolicyManager
import android.os.Build
import android.util.Log
import com.lumina.tv.diag.PageLog

/** Best effort only: BOOT_COMPLETED does not grant a background Activity launch exemption. */
class BootReceiver : BroadcastReceiver() {
    companion object {
        const val EXTRA_BOOT_LAUNCH = "com.lumina.tv.BOOT_LAUNCH"

        fun capability(context: Context): BootLaunchCapability = BootLaunchCapability(
            Build.VERSION.SDK_INT,
            runCatching {
                (context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager)
                    ?.isDeviceOwnerApp(context.packageName)
            }.getOrNull(),
        )
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        runCatching {
            AppRuntime.init(context)
            val capability = capability(context)
            PageLog.add("bootLaunch capability=${capability.status} sdk=${capability.sdkInt} target=${context.applicationInfo.targetSdkVersion}")
            if (AppRuntime.prefs?.bootLaunch != true) {
                PageLog.add("bootLaunch disabled")
                Log.i("BootReceiver", "Boot launch disabled")
                return
            }
            val i = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_BOOT_LAUNCH, true)
            context.startActivity(i)
            PageLog.add("bootLaunch requested; BAL acceptance unobservable here, check activity_created or system BAL logs")
            Log.i("BootReceiver", "MainActivity requested; background launch may be blocked (${capability.status})")
        }.onFailure { error ->
            PageLog.add("bootLaunch failed=${error.javaClass.simpleName}: ${error.message}")
            Log.e("BootReceiver", "Unable to launch MainActivity after boot", error)
        }
    }
}
