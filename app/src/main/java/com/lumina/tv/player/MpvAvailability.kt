package com.lumina.tv.player

import android.content.Context
import android.os.Build
import android.os.Process
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal fun mpvLibrariesInstalled(context: Context): Boolean =
    context.applicationInfo.nativeLibraryDir?.let { directory ->
        listOf("libmpv.so", "libplayer.so").all { File(directory, it).isFile }
    } == true

/** The last observed initialization outlives a controller and is independent of engine choice. */
internal class MpvAvailabilityProbe {
    private data class Initialization(val failureReason: String?)

    @Volatile private var initialization: Initialization? = null

    fun recordInitialization(failureReason: String? = null) {
        initialization = Initialization(failureReason)
    }

    fun snapshot(context: Context): MpvAvailabilitySnapshot =
        snapshot(mpvLibrariesInstalled(context), Build.SUPPORTED_ABIS.toList(), Process.is64Bit())

    fun snapshot(installed: Boolean, supportedAbis: List<String>, process64Bit: Boolean): MpvAvailabilitySnapshot {
        val result = initialization
        return MpvAvailabilitySnapshot(
            installed, supportedAbis, process64Bit,
            initialization = when {
                result == null -> "not_attempted"
                result.failureReason == null -> "ready"
                else -> "failed"
            },
            unavailableReason = if (installed) result?.failureReason else "missing-library",
        )
    }

    companion object {
        val process = MpvAvailabilityProbe()
    }
}

internal data class MpvAvailabilitySnapshot(
    val installed: Boolean,
    val supportedAbis: List<String>,
    val process64Bit: Boolean,
    val initialization: String,
    val unavailableReason: String?,
) {
    fun addTo(engine: JSONObject): JSONObject = engine
        .put("mpvAvailable", mpvAvailabilityDiagnostic(initialization != "not_attempted" || !installed, unavailableReason))
        .put("mpvUnavailableReason", unavailableReason ?: JSONObject.NULL)
        .put("mpvAvailability", JSONObject().put("installed", installed)
            .put("supportedAbis", JSONArray(supportedAbis)).put("process64Bit", process64Bit)
            .put("initialization", initialization).put("failureReason", unavailableReason ?: JSONObject.NULL))
}
