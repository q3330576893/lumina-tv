package com.lumina.tv

import android.media.AudioManager
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.lumina.tv.data.MediaItem
import com.lumina.tv.ui.components.AuroraBackground
import com.lumina.tv.ui.screens.DetailScreen
import com.lumina.tv.ui.screens.HomeScreen
import com.lumina.tv.ui.screens.LoginScreen
import com.lumina.tv.ui.screens.PlayerScreen
import com.lumina.tv.ui.screens.SearchScreen
import com.lumina.tv.ui.screens.SettingsScreen
import com.lumina.tv.ui.theme.LuminaTheme

sealed interface Screen {
    data object Login : Screen
    data object Home : Screen
    data class Detail(val id: String) : Screen
    data class Play(val item: MediaItem, val sourceId: String? = null) : Screen
    data class Search(val keyword: String = "") : Screen
    data object Settings : Screen
}

/** 濡炪倗鏁诲鎵沪閸屾粓鐛撴繛锝呭船鐎规娊鏁嶅畝鈧弫銈嗙鎼粹€虫瀫閻庤鑹鹃閬嶆嚋椤忓嫬鐎奸柟骞垮灩婵晠鎮界紒妯荤厵闁告碍鍨埀?*/
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashHandler.install(this)
        AppRuntime.init(this)
        AppRuntime.restore()
        AppRuntime.prefs?.let {
            com.lumina.tv.ui.theme.ThemeRuntime.applyTheme(it.themeColor)
            com.lumina.tv.ui.theme.ThemeRuntime.applyGlass(it.glassStyle)
        }

        AppRuntime.onRemoteKey = { action -> runOnUiThread { dispatchRemote(action) } }
        AppRuntime.onSearch = { kw -> runOnUiThread { phoneSearch = kw } }

        setContent {
            LuminaTheme {
                AuroraBackground {
                    AppRoot(
                        phoneSearch = phoneSearch,
                        onConsumePhoneSearch = { phoneSearch = null },
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        AppRuntime.onRemoteKey = null
        AppRuntime.onSearch = null
        super.onDestroy()
    }

    /** 闁归潧顑嗗┃鈧梺顑惧劜鐢?-> 缂侇垵宕电划娲箰婢舵劖鏆涘ù婊冾儎濞嗐垽鏁嶅畝鈧ú鍧楀箳閵夈儲婢撶紓浣圭懃缂嶅宕?Compose 闁绘帪濡囬崑锝囧寲閼姐倗鍩犻柕?*/
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
private fun AppRoot(phoneSearch: String?, onConsumePhoneSearch: () -> Unit) {
    val context = LocalContext.current
    var screen by remember {
        mutableStateOf<Screen>(if (AppRuntime.session != null) Screen.Home else Screen.Login)
    }
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
            screen = Screen.Search(kw)
            onConsumePhoneSearch()
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
        AnimatedContent(
            targetState = screen,
            transitionSpec = {
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
        when (target) {
            is Screen.Login -> LoginScreen(
                onLoggedIn = { screen = Screen.Home },
                onNavigate = { destination ->
                    when (destination) {
                        "search" -> screen = Screen.Search()
                        "home" -> if (AppRuntime.session != null) screen = Screen.Home
                        "play" -> if (AppRuntime.session != null) screen = Screen.Home
                        "settings" -> if (AppRuntime.session != null) screen = Screen.Settings
                    }
                },
            )

            is Screen.Home -> {
                HomeScreen(
                    onOpenDetail = { id -> screen = Screen.Detail(id) },
                    onPlay = { item -> screen = Screen.Play(item) },
                    onSearch = { screen = Screen.Search() },
                    onSettings = { screen = Screen.Settings },
                )
            }

            is Screen.Detail -> {
                BackHandler { screen = Screen.Home }
                DetailScreen(
                    id = target.id,
                    onPlay = { item -> screen = Screen.Play(item) },
                    onPlayWithSource = { item, sid -> screen = Screen.Play(item, sid) },
                    onBack = { screen = Screen.Home },
                )
            }

            is Screen.Play -> {
                PlayerScreen(
                    item = target.item,
                    initialSourceId = target.sourceId,
                    onBack = { screen = Screen.Detail(target.item.seriesId ?: target.item.id) },
                    onPlayNext = { next -> screen = Screen.Play(next) },
                )
            }

            is Screen.Search -> {
                BackHandler { screen = Screen.Home }
                SearchScreen(
                    initialKeyword = target.keyword,
                    onOpenDetail = { id -> screen = Screen.Detail(id) },
                    onBack = { screen = Screen.Home },
                )
            }

            is Screen.Settings -> {
                BackHandler { screen = Screen.Home }
                SettingsScreen(
                    onBack = { screen = Screen.Home },
                    onLogout = { screen = Screen.Login },
                )
            }
        }
        }
    }
}
