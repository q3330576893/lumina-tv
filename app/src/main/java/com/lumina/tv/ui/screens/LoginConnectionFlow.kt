package com.lumina.tv.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Presentation states only. Authentication, credentials and generation ownership stay unchanged. */
internal enum class LoginConnectionState(val message: String) {
    Idle("等待连接"),
    Probing("正在探测服务器…"),
    Authenticating("正在验证账号…"),
    PairPending("已接收配对配置，等待电视确认"),
    Connected("连接成功"),
    Error("连接未完成，请检查提示后重试"),
    Cancelled("连接已取消"),
}

internal class LoginConnectionFlow(private val attempts: LoginAttemptGuard) {
    var state by mutableStateOf(LoginConnectionState.Idle)
        private set
    val acceptsEntry: Boolean get() = state !in terminalOrBusy

    fun pairPending(generation: Long): Boolean {
        if (!attempts.isCurrent(generation) || state in terminalOrBusy) return false
        state = LoginConnectionState.PairPending
        return true
    }

    fun begin(): Long? {
        if (!acceptsEntry) return null
        val generation = attempts.begin() ?: return null
        state = LoginConnectionState.Probing
        return generation
    }

    fun authenticating(generation: Long): Boolean {
        if (!attempts.isCurrent(generation) || state != LoginConnectionState.Probing) return false
        state = LoginConnectionState.Authenticating
        return true
    }

    fun connected(generation: Long, authenticated: Boolean, persisted: Boolean): Boolean {
        if (!attempts.isCurrent(generation) || state != LoginConnectionState.Authenticating ||
            !authenticated || !persisted) return false
        state = LoginConnectionState.Connected
        return true
    }

    /** No completion, including a duplicate or cancelled one, may write before this check. */
    fun persistAuthenticated(generation: Long, persist: () -> Boolean): Boolean {
        if (!attempts.isCurrent(generation) || state != LoginConnectionState.Authenticating) return false
        if (!persist()) { error(generation); return false }
        return connected(generation, authenticated = true, persisted = true)
    }

    fun error(generation: Long) {
        if (attempts.isCurrent(generation) && state != LoginConnectionState.Connected &&
            state != LoginConnectionState.Cancelled) state = LoginConnectionState.Error
    }

    fun invalidInput() {
        if (state !in terminalOrBusy) state = LoginConnectionState.Error
    }

    fun editEntry() {
        if (state !in terminalOrBusy) state = LoginConnectionState.Idle
    }

    fun cancel() {
        attempts.close()
        state = LoginConnectionState.Cancelled
    }

    private val terminalOrBusy = setOf(LoginConnectionState.Probing, LoginConnectionState.Authenticating,
        LoginConnectionState.Connected, LoginConnectionState.Cancelled)
}

internal enum class LoginField { Host, Port, User, Password }

internal fun loginFieldState(required: Boolean, filled: Boolean, busy: Boolean, error: String?): String =
    listOfNotNull(if (required) "必填" else "选填", if (filled) "已填" else "未填",
        if (busy) "busy，正在连接" else null, error?.let { "错误：$it" }).joinToString("，")

/** At 960dp reserve a readable form before allocating the brand column. */
internal fun loginFormWidth(availableWidth: Float): Float = (availableWidth * 0.52f).coerceIn(420f, 560f)
