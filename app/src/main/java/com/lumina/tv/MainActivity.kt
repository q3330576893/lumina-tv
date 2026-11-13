package com.lumina.tv

import android.media.AudioManager
import android.os.Bundle
import android.content.ComponentCallbacks2
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import coil.imageLoader
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.delay
import com.lumina.tv.data.MediaItem
import com.lumina.tv.ui.components.AuroraBackground
import com.lumina.tv.ui.screens.DetailScreen
import com.lumina.tv.ui.screens.HomeScreen
import com.lumina.tv.ui.screens.LoginScreen
import com.lumina.tv.ui.screens.PlayerScreen
import com.lumina.tv.ui.screens.SearchScreen
import com.lumina.tv.ui.screens.SplashScreen
import com.lumina.tv.ui.theme.LuminaTheme

sealed interface Screen {
    data object Login : Screen
    data object Home : Screen
    data class Detail(val id: String, val previousId: String? = null) : Screen
    data class Play(val item: MediaItem, val sourceId: String? = null) : Screen
    data class Search(val keyword: String = "") : Screen
    data object Settings : Screen
}

private data class BackStackEntry(val screen: Screen, val returnTo: Screen? = null)

/** 页面层级深度，用于决定导航切换动画方向。 */
private fun Screen.depth(): Int = when (this) {
    Screen.Login -> 0
    Screen.Home -> 1
    is Screen.Detail -> 2
    is Screen.Play -> 3
    is Screen.Search -> 2
    Screen.Settings -> 2
}

class MainActivity : ComponentActivity() {
    private var phoneSearch by mutableStateOf<String?>(null)
    private var startupCrashNotice by mutableStateOf<String?>(null)

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW || level == ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
            imageLoader.memoryCache?.clear()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Lumina phone and TV clients intentionally share the 16:9 landscape layout.
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        initializeAppLog()
        CrashHandler.install(this)
        startupCrashNotice = CrashHandler.takeStartupNotice(this)
        AppRuntime.init(this)
        AppRuntime.restore()
        // 应用个性化主题与玻璃质感
        AppRuntime.prefs?.let {
            com.lumina.tv.ui.theme.ThemeRuntime.applyTheme(it.themeColor)
            com.lumina.tv.ui.theme.ThemeRuntime.applyGlass(it.glassStyle)
            com.lumina.tv.ui.theme.ThemeRuntime.applyBase(it.baseTheme)
        }

        AppRuntime.onRemoteKey = { action -> runOnUiThread { dispatchRemote(action) } }
        AppRuntime.onSearch = { kw -> runOnUiThread { if (AppRuntime.session != null) phoneSearch = kw } }

        setContent {
            LuminaTheme {
                AuroraBackground {
                    var bootDone by remember { mutableStateOf(AppRuntime.session == null) }
                    val serverName = AppRuntime.prefs?.serverName
                        ?: AppRuntime.session?.serverName
                        ?: "Emby"
                    LaunchedEffect(Unit) {
                        if (!bootDone) {
                            val startedAt = System.currentTimeMillis()
                            val elapsed = System.currentTimeMillis() - startedAt
                            if (elapsed < 600) delay(600 - elapsed)
                            bootDone = true
                        }
                    }
                    Crossfade(
                        targetState = bootDone,
                        animationSpec = tween(260),
                        label = "boot-transition",
                    ) { done ->
                        if (done) {
                            AppRoot(
                                phoneSearch = phoneSearch,
                                onConsumePhoneSearch = { phoneSearch = null },
                                crashNotice = startupCrashNotice,
                                onConsumeCrashNotice = { startupCrashNotice = null },
                            )
                        } else {
                            SplashScreen(serverName = serverName, modifier = Modifier.fillMaxSize())
                        }
                    }
                }
            }
        }
    }

    private fun initializeAppLog() {
        val current = java.io.File(filesDir, "app.log")
        val previous = java.io.File(filesDir, "app-prev.log")
        if (current.isFile && current.length() > 0L) {
            previous.delete()
            current.renameTo(previous)
        }
        current.writeText("")
    }

    override fun onDestroy() {
        AppRuntime.onRemoteKey = null
        AppRuntime.onSearch = null
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        AppRuntime.client?.requestProgressFlush()
    }

    /** 手机遥控 -> 系统按键事件，直接喂给当前 Compose 焦点系统。 */
    private fun dispatchRemote(action: String) {
        val code = when (action) {
            "up" -> KeyEvent.KEYCODE_DPAD_UP
            "down" -> KeyEvent.KEYCODE_DPAD_DOWN
            "left" -> KeyEvent.KEYCODE_DPAD_LEFT
            "right" -> KeyEvent.KEYCODE_DPAD_RIGHT
            "ok" -> KeyEvent.KEYCODE_DPAD_CENTER
            "back" -> KeyEvent.KEYCODE_BACK
            "home" -> KeyEvent.KEYCODE_HOME
            "menu" -> KeyEvent.KEYCODE_MENU
            "playpause" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "rewind" -> KeyEvent.KEYCODE_MEDIA_REWIND
            "forward" -> KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
            else -> -1
        }
        if (code > 0) {
            dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
            return
        }
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        when (action) {
            "volup" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            "voldown" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            "mute" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI)
        }
    }
}

@Composable
private fun AppRoot(
    phoneSearch: String?,
    onConsumePhoneSearch: () -> Unit,
    crashNotice: String?,
    onConsumeCrashNotice: () -> Unit,
) {
    val context = LocalContext.current
    var showCrashLog by remember { mutableStateOf(false) }
    val backStack = remember {
        androidx.compose.runtime.mutableStateListOf(
            BackStackEntry(if (AppRuntime.session != null) Screen.Home else Screen.Login)
        )
    }
    val screen = backStack.last().screen
    val stateHolder = rememberSaveableStateHolder()
    fun navigate(destination: Screen, returnTo: Screen? = screen) {
        backStack.add(BackStackEntry(destination, returnTo))
    }
    fun replaceRoot(destination: Screen) {
        backStack.clear()
        backStack.add(BackStackEntry(destination))
    }
    fun replaceCurrent(destination: Screen) {
        val returnTo = backStack.lastOrNull()?.returnTo
        backStack[backStack.lastIndex] = BackStackEntry(destination, returnTo)
    }
    fun goBack(fallback: Screen = Screen.Home) {
        if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
        else replaceRoot(fallback)
    }

    // 启动渐入：整体从轻微缩小 + 透明淡入，避免进 App 生硬
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { started = true }
    val appAlpha by animateFloatAsState(if (started) 1f else 0f, tween(520), label = "appAlpha")
    val appScale by animateFloatAsState(if (started) 1f else 0.985f, tween(520), label = "appScale")

    LaunchedEffect(Unit) {
        if (AppRuntime.pairing == null) AppRuntime.startPairing(context)
    }

    LaunchedEffect(phoneSearch) {
        val kw = phoneSearch
        if (!kw.isNullOrBlank()) {
            navigate(Screen.Search(kw))
            onConsumePhoneSearch()
        }
    }

    LaunchedEffect(crashNotice) {
        if (!crashNotice.isNullOrBlank()) {
            kotlinx.coroutines.delay(7000)
            onConsumeCrashNotice()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                alpha = appAlpha
                scaleX = appScale
                scaleY = appScale
            }
    ) {
        if (!crashNotice.isNullOrBlank()) {
            Snackbar(
                modifier = Modifier.align(androidx.compose.ui.Alignment.TopCenter).padding(top = 16.dp),
                action = {
                    TextButton(onClick = { showCrashLog = true; onConsumeCrashNotice() }) { Text("查看") }
                },
                dismissAction = { TextButton(onClick = onConsumeCrashNotice) { Text("关闭") } },
            ) { Text("检测到上次异常，日志已保存在本地") }
        }
        if (showCrashLog) {
            AlertDialog(
                onDismissRequest = { showCrashLog = false },
                title = { Text("上次崩溃日志") },
                text = {
                    Text(
                        runCatching { CrashHandler.crashFile(context).readText().take(8000) }
                            .getOrDefault("无法读取崩溃日志"),
                        maxLines = 24,
                    )
                },
                confirmButton = { TextButton(onClick = { showCrashLog = false }) { Text("关闭") } },
            )
        }
        AnimatedContent(
            targetState = screen,
            transitionSpec = {
                // 进入更深页面：从下方轻微上滑淡入；返回：向上滑出
                if (targetState.depth() >= initialState.depth()) {
                    (fadeIn(tween(300)) + slideInVertically(tween(300)) { it / 14 }) togetherWith
                        fadeOut(tween(200))
                } else {
                    fadeIn(tween(300)) togetherWith
                        (fadeOut(tween(240)) + slideOutVertically(tween(240)) { it / 14 })
                }
            },
            label = "nav",
        ) { target ->
        stateHolder.SaveableStateProvider(target.stateKey()) {
        when (target) {
            is Screen.Login -> LoginScreen(
                onLoggedIn = { replaceRoot(Screen.Home) },
                onCancel = { goBack() },
                onNavigate = { destination ->
                    when (destination) {
                        "search" -> navigate(Screen.Search())
                        "home" -> if (AppRuntime.session != null) replaceRoot(Screen.Home)
                        "play" -> if (AppRuntime.session != null) replaceRoot(Screen.Home)
                        "settings" -> if (AppRuntime.session != null) navigate(Screen.Settings)
                    }
                },
            )

            is Screen.Home -> {
                HomeScreen(
                    onOpenDetail = { id -> navigate(Screen.Detail(id), Screen.Home) },
                    onPlay = { item -> navigate(Screen.Play(item), Screen.Home) },
                    onSearch = { navigate(Screen.Search()) },
                    onSettings = { navigate(Screen.Settings) },
                    onSwitchServer = {
                        navigate(Screen.Login, Screen.Home)
                    },
                    onLogout = {
                        AppRuntime.logout()
                        com.lumina.tv.data.LibraryCache.clear()
                        replaceRoot(Screen.Login)
                    },
                )
            }

            is Screen.Detail -> {
                BackHandler { goBack() }
                DetailScreen(
                    id = target.id,
                    onPlay = { item -> navigate(Screen.Play(item), target) },
                    onPlayWithSource = { item, sid -> navigate(Screen.Play(item, sid), target) },
                    onOpenDetail = { nextId -> navigate(Screen.Detail(nextId), target) },
                    onBack = { goBack() },
                )
            }

            is Screen.Play -> {
                PlayerScreen(
                    item = target.item,
                    initialSourceId = target.sourceId,
                    onBack = { goBack() },
                    onPlayNext = { next -> replaceCurrent(Screen.Play(next)) },
                )
            }

            is Screen.Search -> {
                BackHandler { goBack() }
                SearchScreen(
                    initialKeyword = target.keyword,
                    onOpenDetail = { id -> navigate(Screen.Detail(id), target) },
                    onBack = { goBack() },
                )
            }

            is Screen.Settings -> {
                BackHandler { goBack() }
                com.lumina.tv.ui.screens.settings.SettingsScreen(
                    onBack = { goBack() },
                    onLogout = { AppRuntime.logout(); replaceRoot(Screen.Login) },
                )
            }
        }
        }
        }
    }
}

private fun Screen.stateKey(): String = when (this) {
    Screen.Login -> "login"
    Screen.Home -> "home"
    is Screen.Detail -> "detail:$id"
    is Screen.Play -> "play:${item.id}"
    is Screen.Search -> "search:$keyword"
    Screen.Settings -> "settings"
}
