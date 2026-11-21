package com.lumina.tv.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.lumina.tv.diag.PageLog

internal enum class MpvFocusSignal { GAIN, LOSS, TRANSIENT_LOSS, DUCK }
internal enum class MpvFocusEffect { NONE, PAUSE, PAUSE_AND_UNDUCK, RESUME, DUCK, UNDUCK, UNDUCK_AND_RESUME }

internal data class MpvFocusPolicyState(val resumeOnGain: Boolean = false, val ducked: Boolean = false)

internal data class MpvFocusPolicyResult(val state: MpvFocusPolicyState, val effect: MpvFocusEffect)

/** Render/load restoration must respect both independent pause owners. */
internal fun shouldPauseMpv(pauseIntent: Boolean, audioFocusHold: Boolean): Boolean = pauseIntent || audioFocusHold

internal fun reduceMpvFocus(
    state: MpvFocusPolicyState,
    signal: MpvFocusSignal,
    desiredPlaying: Boolean,
    currentlyPlaying: Boolean,
): MpvFocusPolicyResult = when (signal) {
    MpvFocusSignal.GAIN -> {
        val resume = state.resumeOnGain && desiredPlaying
        MpvFocusPolicyResult(
            MpvFocusPolicyState(),
            when {
                state.ducked && resume -> MpvFocusEffect.UNDUCK_AND_RESUME
                state.ducked -> MpvFocusEffect.UNDUCK
                resume -> MpvFocusEffect.RESUME
                else -> MpvFocusEffect.NONE
            },
        )
    }
    MpvFocusSignal.LOSS -> MpvFocusPolicyResult(
        MpvFocusPolicyState(),
        if (state.ducked) MpvFocusEffect.PAUSE_AND_UNDUCK else MpvFocusEffect.PAUSE,
    )
    MpvFocusSignal.TRANSIENT_LOSS -> MpvFocusPolicyResult(
        MpvFocusPolicyState(resumeOnGain = desiredPlaying && currentlyPlaying, ducked = false),
        when {
            desiredPlaying && currentlyPlaying && state.ducked -> MpvFocusEffect.PAUSE_AND_UNDUCK
            desiredPlaying && currentlyPlaying -> MpvFocusEffect.PAUSE
            state.ducked -> MpvFocusEffect.UNDUCK
            else -> MpvFocusEffect.NONE
        },
    )
    MpvFocusSignal.DUCK -> MpvFocusPolicyResult(state.copy(ducked = true), MpvFocusEffect.DUCK)
}

/** Owns one platform focus request for one mpv controller and never changes user play intent. */
class MpvAudioFocusController(context: Context) {
    data class PlaybackTarget(
        val isCurrent: () -> Boolean,
        val isPlaying: () -> Boolean,
        val userWantsPlayback: () -> Boolean,
        val pause: () -> Unit,
        val resume: () -> Unit,
        val duck: (Boolean) -> Boolean,
    )

    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val main = Handler(Looper.getMainLooper())
    private var focusRequest: AudioFocusRequest? = null
    private var legacyListener: AudioManager.OnAudioFocusChangeListener? = null
    private var target: PlaybackTarget? = null
    private var policy = MpvFocusPolicyState()
    private var hasFocus = false
    private var focusRequested = false
    private var released = false

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        main.post {
            if (released || !focusRequested) return@post
            val signal = when (change) {
                AudioManager.AUDIOFOCUS_GAIN -> MpvFocusSignal.GAIN
                AudioManager.AUDIOFOCUS_LOSS -> MpvFocusSignal.LOSS
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> MpvFocusSignal.TRANSIENT_LOSS
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> MpvFocusSignal.DUCK
                else -> return@post
            }
            hasFocus = signal == MpvFocusSignal.GAIN || signal == MpvFocusSignal.DUCK
            if (signal == MpvFocusSignal.LOSS) {
                focusRequested = false
                abandonPlatformFocus()
            }
            val owner = target?.takeIf { it.isCurrent() } ?: return@post
            val result = reduceMpvFocus(policy, signal, owner.userWantsPlayback(), owner.isPlaying())
            policy = result.state
            when (result.effect) {
                MpvFocusEffect.NONE -> Unit
                MpvFocusEffect.PAUSE -> owner.pause()
                MpvFocusEffect.PAUSE_AND_UNDUCK -> {
                    owner.duck(false)
                    owner.pause()
                }
                MpvFocusEffect.RESUME -> owner.resume()
                MpvFocusEffect.DUCK -> if (!owner.duck(true)) {
                    policy = policy.copy(resumeOnGain = owner.userWantsPlayback() && owner.isPlaying())
                    owner.pause()
                }
                MpvFocusEffect.UNDUCK -> owner.duck(false)
                MpvFocusEffect.UNDUCK_AND_RESUME -> {
                    owner.duck(false)
                    owner.resume()
                }
            }
            PageLog.add("mpvAudioFocus action=${signal.name} desired=${owner.userWantsPlayback()} playing=${owner.isPlaying()}")
        }
    }

    val ownsFocus: Boolean get() = hasFocus

    @Synchronized
    fun acquire(owner: PlaybackTarget): Boolean {
        if (released || !owner.isCurrent()) return false
        target = owner
        if (hasFocus) return true
        val result = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val request = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                            .build(),
                    )
                    // Let mpv handle AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK using its own volume.
                    .setWillPauseWhenDucked(false)
                    .setAcceptsDelayedFocusGain(true)
                    .setOnAudioFocusChangeListener(focusListener, main)
                    .build().also { focusRequest = it }
                manager.requestAudioFocus(request)
            } else {
                val listener = legacyListener ?: focusListener.also { legacyListener = it }
                manager.requestAudioFocus(listener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
            }
        }.getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        hasFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        focusRequested = hasFocus || result == AudioManager.AUDIOFOCUS_REQUEST_DELAYED
        if (result == AudioManager.AUDIOFOCUS_REQUEST_DELAYED) {
            policy = policy.copy(resumeOnGain = owner.userWantsPlayback())
        }
        if (!hasFocus) {
            owner.pause()
            PageLog.add("mpvAudioFocus action=request-$result")
        } else {
            PageLog.add("mpvAudioFocus action=request-granted")
        }
        return hasFocus
    }

    @Synchronized
    fun play(owner: PlaybackTarget): Boolean {
        policy = MpvFocusPolicyState()
        val acquired = acquire(owner)
        if (acquired && owner.isCurrent() && owner.userWantsPlayback()) owner.resume()
        return acquired
    }

    @Synchronized
    fun pauseByUser() {
        val wasDucked = policy.ducked
        policy = MpvFocusPolicyState()
        target?.takeIf { it.isCurrent() }?.let { owner -> if (wasDucked) owner.duck(false) }
        val request = focusRequest
        val listener = legacyListener
        hasFocus = false
        focusRequested = false
        abandonPlatformFocus(request, listener)
        PageLog.add("mpvAudioFocus action=user-pause")
    }

    private fun abandonPlatformFocus(
        request: AudioFocusRequest? = focusRequest,
        listener: AudioManager.OnAudioFocusChangeListener? = legacyListener,
    ) {
        runCatching {
            if (request != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) manager.abandonAudioFocusRequest(request)
            else if (listener != null) manager.abandonAudioFocus(listener)
        }
    }

    @Synchronized
    fun release() {
        if (released) return
        pauseByUser()
        released = true
        target = null
        focusRequest = null
        legacyListener = null
    }

}
