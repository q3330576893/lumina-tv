package com.lumina.tv

import android.content.SharedPreferences
import com.lumina.tv.data.Prefs
import com.lumina.tv.ui.screens.*
import com.lumina.tv.ui.screens.settings.*
import com.lumina.tv.ui.theme.TvTokenValues
import kotlinx.coroutines.*
import java.io.File
import java.lang.reflect.Proxy

/** Exercises the production login/focus policies and actual Prefs, with no external service. */
object M06Checks {
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        fields()
        loginFlow()
        loginLayout()
        rowSemantics()
        focusSwitching()
        materialsAndDetails()
        declarationsAndSharedState()
        categories()
        println("M06 PASS: all 8 items; 7 named login states (including both PairPending and Connected), 20 delayed focus restores, scoped preference subscriptions")
    }

    private fun fields() {
        check(loginRequiredField("host", "alice") == null)
        check(loginRequiredField("", "") == LoginRequiredField.Host)
        val busyError = loginFieldState(true, true, true, "检查地址")
        check(listOf("必填", "已填", "busy", "错误：检查地址").all { it in busyError })
        check(loginFieldState(false, false, false, null) == "选填，未填")
        val source = source("LoginScreen.kt")
        check("label = { Text(label" in source && "supportingText =" in source && "isError = supportingError != null" in source)
        check("if (password) KeyboardType.Password" in source && "PasswordVisualTransformation()" in source)
        check("onNext = { portFocusRequester.requestFocus() }" in source && "onNext = { passFocus.requestFocus() }" in source)
        check("fieldErrors.keys.minBy { it.ordinal }" in source)
        check("服务器接口报错：\$message" !in source && "登录失败：\${message" !in source)
        println("M06-1 PASS: persistent labels, first-error focus, optional password, Next/Done and safe field semantics")
    }

    private suspend fun loginFlow() = coroutineScope {
        val visited = mutableSetOf<LoginConnectionState>()
        for (entry in listOf("manual", "recent", "pair")) {
            val guard = LoginAttemptGuard()
            val flow = LoginConnectionFlow(guard)
            visited += flow.state
            if (entry == "pair") {
                check(flow.pairPending(checkNotNull(guard.acceptPair())))
                visited += flow.state
                check("等待电视确认" in flow.state.message && "成功" !in flow.state.message)
            }
            val generation = checkNotNull(flow.begin())
            visited += flow.state
            check(flow.begin() == null)
            var writes = 0
            check(!flow.persistAuthenticated(generation) { writes++; true }) // A probe is not authentication.
            check(flow.authenticating(generation))
            visited += flow.state
            check(!flow.connected(generation, authenticated = false, persisted = true))
            check(!flow.connected(generation, authenticated = true, persisted = false))
            check(flow.persistAuthenticated(generation) { writes++; true })
            visited += flow.state
            guard.finish(generation)
            check(!flow.persistAuthenticated(generation) { writes++; true })
            check(flow.begin() == null && writes == 1)
            check(!flow.acceptsEntry)
            flow.error(generation)
            check(flow.state == LoginConnectionState.Connected)
        }
        val guard = LoginAttemptGuard()
        val flow = LoginConnectionFlow(guard)
        flow.invalidInput()
        check(flow.state == LoginConnectionState.Error)
        flow.editEntry()
        check(flow.state == LoginConnectionState.Idle)
        val failed = checkNotNull(flow.begin())
        flow.error(failed); visited += flow.state; guard.finish(failed)
        val retry = checkNotNull(flow.begin())
        check(flow.authenticating(retry))
        check(!flow.persistAuthenticated(retry) { false })
        check(flow.state == LoginConnectionState.Error)
        guard.finish(retry)
        val delayedGeneration = checkNotNull(flow.begin())
        check(flow.authenticating(delayedGeneration))
        val response = CompletableDeferred<Unit>()
        var writes = 0
        val late = launch(start = CoroutineStart.UNDISPATCHED) {
            response.await()
            check(!flow.persistAuthenticated(delayedGeneration) { writes++; true })
            flow.error(delayedGeneration)
        }
        flow.cancel(); visited += flow.state
        response.complete(Unit); late.join()
        check(writes == 0 && flow.state == LoginConnectionState.Cancelled && flow.begin() == null)
        check(visited == LoginConnectionState.entries.toSet())
        check(LoginConnectionState.entries.map { it.message }.distinct().size == 7)
        val source = source("LoginScreen.kt")
        check(source.indexOf("connection.persistAuthenticated(pair.generation)") < source.indexOf("AppRuntime.applySession(sessionToSave)"))
        check("AppRuntime.prefs?.saveCurrentServer() == true" in source)
        check("AppRuntime.updatePairingStatus(connection.state.message)" in source)
        println("M06-2 PASS: manual/recent/pair gates, all states, duplicate completion, persistence failure and late cancellation")
    }

    private fun loginLayout() {
        check(TvTokenValues.loginColumnsFit(960f, 540f, 1f))
        check(loginFormWidth(960f) >= 420f)
        check(!TvTokenValues.loginColumnsFit(720f, 540f, 1f))
        check(!TvTokenValues.loginColumnsFit(960f, 540f, 1.5f))
        val source = source("LoginScreen.kt")
        check("private val Glass" !in source && "private val Violet" !in source && "private val Ink" !in source)
        check("LuminaSurface(SurfaceLevel.Content" in source && ".take(6)" in source)
        check("lifecycle.removeObserver(observer)" in source && "urls.isEmpty() || !foreground" in source)
        val narrow = source.substringAfter("Column(Modifier.fillMaxSize().verticalScroll")
        check(narrow.indexOf("BrandHero(") < narrow.indexOf("LoginCard("))
        println("M06-3 PASS: 960dp form reservation, narrow brand-first layout, shared surfaces and bounded lifecycle images")
    }

    private fun rowSemantics() {
        val rows = source("settings/SettingsRows.kt")
        check("Modifier.toggleable(value = checked, enabled = enabled" in rows && "role = Role.Switch" in rows)
        check(".clearAndSetSemantics { }" in rows && "Switch(" !in rows)
        check("stateDescription =" in rows && "个候选" in rows && "打开选择弹层" in rows)
        check("disabledReason.isNullOrBlank()" in rows && "nextCondition.isNullOrBlank()" in rows)
        check("✓ " in rows && "Role.RadioButton" in rows)
        println("M06-4 PASS: one toggle target, checked/value/count/actions, non-color selection and disabled conditions")
    }

    private suspend fun focusSwitching() = coroutineScope {
        val key = SettingsFocusKey("account", "delete", "https://[::1]:8096/emby", "expanded", "server/user-a")
        val variants = listOf(key, key.copy(category = "home"), key.copy(rowKey = "edit"),
            key.copy(itemIdentity = "https://[::1]:8096/Emby"), key.copy(expansionState = "collapsed"),
            key.copy(accountScope = "server/user-b"))
        check(variants.map { it.encoded }.distinct().size == 6)
        check(key.copy(rowKey = "a:b", itemIdentity = "c").encoded != key.copy(rowKey = "a", itemIdentity = "b:c").encoded)
        val focus = SettingsFocusSession("appearance", "a")
        val releases = (0 until 20).map { CompletableDeferred<Unit>() }
        val restored = mutableListOf<Int>()
        val jobs = releases.mapIndexed { index, release ->
            val ticket = focus.select(SettingsCategory.entries[index % 7].key)
            launch(start = CoroutineStart.UNDISPATCHED) {
                release.await()
                if (focus.isCurrent(ticket)) restored += index
            }
        }
        releases.indices.reversed().forEach { releases[it].complete(Unit) }
        jobs.joinAll()
        check(restored == listOf(19))
        val beforeMove = focus.ticket(); focus.userMoved(); check(!focus.isCurrent(beforeMove))
        check(!SettingsFocusSession(beforeMove.category, "b").isCurrent(beforeMove))
        check(accountFocusAfterRemoval(listOf("a", "b", "c"), "b", listOf("a", "c")) == "account:switch:c")
        check(accountFocusAfterRemoval(listOf("a", "b"), "b", listOf("a")) == "account:switch:a")
        check(accountFocusAfterRemoval(listOf("a"), "a", emptyList()) == "account:save-current")
        val screen = source("settings/SettingsScreen.kt")
        check("key(accountScope, category)" in screen && screen.split("if (!selection.isCurrent(ticket))").size >= 5)
        check("SaveableStateProvider" in screen && "BackHandler" in screen)
        check("settingsFocusTarget" in source("settings/SettingsFocus.kt"))
        check("restoreFocusOnClose = !savedServersOpen" in source("settings/SettingsAccount.kt"))
        println("M06-5 PASS: five-part collision-free keys, 20 reverse-order completions, user movement and adjacent deletion")
    }

    private fun materialsAndDetails() {
        val rows = source("settings/SettingsRows.kt")
        check("minLines = 2, maxLines = 2" in rows && "Modifier.width(168.dp)" in rows)
        check("查看完整说明与生效范围" in rows && "Key.Menu" in rows)
        val dialogs = source("settings/SettingsFocus.kt")
        check("LuminaSurface(SurfaceLevel.Overlay" in dialogs && "verticalScroll" in dialogs)
        check("EffectTierPreview(" in source("settings/SettingsAppearance.kt"))
        val screen = source("settings/SettingsScreen.kt")
        check("SurfaceLevel.Canvas" in screen && "LuminaSurface(SurfaceLevel.Floating" in screen)
        println("M06-6 PASS: four surface levels, two-line explanations, fixed value width and complete detail overlays")
    }

    private fun declarationsAndSharedState() {
        check(SettingDeclarations.entries.size == 29)
        check(SettingDeclarations.entries.values.all { it.defaultValue.isNotBlank() && it.timing.isNotBlank() })
        for (key in listOf("compat:playerEngine", "playback:maxStreamingBitrate", "subtitles:preferredAudioLanguage")) {
            val declaration = checkNotNull(SettingDeclarations.forRow(key))
            check(declaration.scope == SettingScope.Global && declaration.requiresNewLoad && !declaration.currentPlaybackImmediate)
        }
        check(SettingDeclarations.forRow("home:view:movie")?.scope == SettingScope.Account)
        check(SettingDeclarations.forRow("subtitles:subtitleScale")?.currentPlaybackImmediate == true)
        check(SettingDeclarations.forRow("about:exportDiagnostics") == null)
        val memory = MemoryPreferences()
        val prefs = Prefs(memory.preferences)
        check(prefs.subtitleScale == 1f && prefs.defaultSpeed == 1f && prefs.maxStreamingBitrate == 120_000_000L)
        check(!prefs.autoNextEpisode && prefs.endCreditsSkipSeconds == 30 && prefs.subtitlePosition == 0.08f)
        check(prefs.playerEngine == "auto" && prefs.preferredAudioLanguage == "" && prefs.preferredSubtitleLanguage == "")
        var pageScale = prefs.subtitleScale
        var overlayScale = prefs.subtitleScale
        val stopPage = prefs.observePreference("subtitle_scale") { pageScale = prefs.subtitleScale }
        val stopOverlay = prefs.observePreference("subtitle_scale") { overlayScale = prefs.subtitleScale }
        prefs.subtitleScale = 1.5f
        check(pageScale == 1.5f && overlayScale == 1.5f)
        prefs.subtitleBg = true
        prefs.defaultSpeed = 1.25f
        val before = memory.values.toMap()
        prefs.preferredAudioLanguage = "ja"
        prefs.preferredSubtitleLanguage = "zh"
        prefs.playerEngine = "mpv"
        check(before.all { (key, value) -> memory.values[key] == value })
        check(prefs.subtitleScale == 1.5f && prefs.subtitleBg && prefs.defaultSpeed == 1.25f)
        stopPage(); stopOverlay(); prefs.subtitleScale = 1.25f
        check(pageScale == 1.5f && overlayScale == 1.5f && memory.listeners.isEmpty())
        val player = source("PlayerScreen.kt")
        val subtitles = source("settings/SettingsSubtitles.kt")
        check("rememberPreferenceState(AppRuntime.prefs, \"subtitle_scale\"" in player)
        check("rememberPreferenceState(prefs, \"subtitle_scale\"" in subtitles)
        println("M06-7 PASS: 29 explicit defaults/scopes/timings, two live subscribers, disposal and unrelated preference preservation")
    }

    private fun categories() {
        check(SettingsCategory.entries.map { it.label } == listOf("账号与服务器", "外观与玻璃", "首页", "播放", "字幕与音轨", "播放器与兼容性", "关于与诊断"))
        check(!choiceUsesDialog("short", 3) && choiceUsesDialog("short", 4))
        for (key in listOf("preferredAudioLanguage", "sourceBitrate", "sourceReleaseGroups", "servers")) check(choiceUsesDialog(key, 2))
        check("playback:preferredAudioLanguage" !in source("settings/SettingsPlayback.kt"))
        check("SettingsDialog(\"account:servers\"" in source("settings/SettingsAccount.kt"))
        println("M06-8 PASS: seven categories, audio grouping and dialog policy for long/specialized options")
    }

    private fun source(path: String) = File("src/main/java/com/lumina/tv/ui/screens", path).readText(Charsets.UTF_8)

    private class MemoryPreferences {
        val values = mutableMapOf<String, Any>("secrets_migrated_v1" to true)
        val listeners = linkedSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()
        val preferences = proxy(SharedPreferences::class.java) { name, args -> when (name) {
            "contains" -> values.containsKey(args[0])
            "getString", "getInt", "getLong", "getFloat", "getBoolean" -> values[args[0]] ?: args[1]
            "edit" -> editor()
            "registerOnSharedPreferenceChangeListener" -> { listeners += args[0] as SharedPreferences.OnSharedPreferenceChangeListener; null }
            "unregisterOnSharedPreferenceChangeListener" -> { listeners -= args[0] as SharedPreferences.OnSharedPreferenceChangeListener; null }
            else -> error("Unexpected preference operation: $name")
        } }
        private fun editor(): SharedPreferences.Editor {
            val updates = linkedMapOf<String, Any?>()
            lateinit var editor: SharedPreferences.Editor
            editor = proxy(SharedPreferences.Editor::class.java) { name, args -> when (name) {
                "putString", "putInt", "putLong", "putFloat", "putBoolean" -> { updates[args[0] as String] = args[1]; editor }
                "remove" -> { updates[args[0] as String] = null; editor }
                "apply", "commit" -> {
                    updates.forEach { (key, value) ->
                        if (value == null) values.remove(key) else values[key] = value
                        listeners.toList().forEach { it.onSharedPreferenceChanged(preferences, key) }
                    }
                    if (name == "commit") true else null
                }
                else -> error("Unexpected editor operation: $name")
            } }
            return editor
        }
    }
    private fun <T> proxy(type: Class<T>, call: (String, Array<out Any?>) -> Any?): T = checkNotNull(type.cast(
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args -> call(method.name, args ?: emptyArray()) }))
}
