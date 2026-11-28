package com.lumina.tv

import com.lumina.tv.ui.screens.LoginRequiredField
import com.lumina.tv.ui.screens.loginRequiredField
import com.lumina.tv.ui.screens.timelineNoticeMayClear
import java.io.File

/** M01 checks for password-optional login and scoped audio notice expiry. */
object M01Checks {
    @JvmStatic
    fun main(args: Array<String>) {
        loginValidation()
        noticeLifecycle()
        println("M01 PASS: empty-password login validation and timed audio notice lifecycle")
    }

    private fun loginValidation() {
        check(loginRequiredField("https://server.test", "alice") == null)
        check(loginRequiredField("", "alice") == LoginRequiredField.Host)
        check(loginRequiredField("https://server.test", "") == LoginRequiredField.User)
        check(loginRequiredField("https://server.test", "alice") == null) // Empty password is not an input to validation.

        val login = source("ui/screens/LoginScreen.kt")
        val validation = login.substringAfter("fun validateAndSubmit() {").substringBefore("\n    }\n    Column(")
        check("loginRequiredField(host, user)" in validation)
        check("hostBad = host.isBlank()" in validation)
        check("userBad = user.isBlank()" in validation)
        check("hostFocusRequester.requestFocus()" in validation)
        check("userFocusRequester.requestFocus()" in validation)
        check("passFocus.requestFocus()" !in validation)
        check("請填寫密碼" !in validation)
        check("passBad = p" !in validation)
        check("else -> onLogin()" in validation)
        check("host.isBlank() || name.isBlank()" in login) // submit() used by pairing and recent-server shortcuts.
        check("message.contains(\"401\")" in login && "账号或密码错误" in login) // Existing credential error path remains.
        check("else if (!scanUser.isNullOrBlank())" in login)
        check("pass = scanPass.orEmpty()" in login)
        check("scanPass != null" !in login)
        check("pass = \"\"" in login && "补上密码即可登录" !in login)
    }

    private fun noticeLifecycle() {
        val audioNotice = "audio-track-unresolved"
        var notice: String? = audioNotice

        // At the four-second timeout, the still-current notice is eligible for clearing.
        check(timelineNoticeMayClear(notice, audioNotice))

        // Selecting a mapped track clears its own pending notice immediately.
        notice = null
        check(isCleared(notice))

        // An older timeout cannot remove a later, unrelated notice.
        notice = "subtitle-track-unresolved"
        check(!timelineNoticeMayClear(notice, audioNotice))

        val player = source("ui/screens/PlayerScreen.kt")
        check("delay(4_000L)" in player)
        check("timelineNoticeMayClear(playbackStatusNotice, message)" in player)
        check("showTimelineNotice(AUDIO_TRACK_UNRESOLVED_NOTICE)" in player)
        check("if (mapped && playbackStatusNotice == AUDIO_TRACK_UNRESOLVED_NOTICE) playbackStatusNotice = null" in player)
        check("playbackStatusNotice = \"字幕对应关系不确定，可在菜单选择播放器字幕\"" in player)
    }

    private fun source(path: String): String = File("src/main/java/com/lumina/tv", path).readText(Charsets.UTF_8)

    private fun isCleared(notice: String?): Boolean = notice == null
}
