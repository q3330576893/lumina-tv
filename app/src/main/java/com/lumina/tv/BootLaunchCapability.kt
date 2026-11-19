package com.lumina.tv

/** An observed exemption is not proof that a particular vendor accepted a launch. */
data class BootLaunchCapability(val sdkInt: Int, val deviceOwner: Boolean?) {
    val status: String get() = when {
        sdkInt < 29 -> "pre_android_10"
        deviceOwner == true -> "device_owner_exemption"
        deviceOwner == false -> "background_activity_launch_restricted"
        else -> "background_activity_launch_capability_unknown"
    }

    val description: String get() = when {
        sdkInt < 29 -> "开机后尝试打开；能否自动启动取决于设备系统设置"
        deviceOwner == true -> "已具备设备所有者身份；开机后尝试打开，结果仍取决于设备系统"
        else -> "开机后尝试打开；Android 10 及以上系统可能拦截，需设备系统或受管设备授权"
    }
}
