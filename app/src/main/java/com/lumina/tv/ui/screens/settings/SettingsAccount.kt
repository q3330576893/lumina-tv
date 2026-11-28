package com.lumina.tv.ui.screens.settings

import com.lumina.tv.ui.theme.*
import com.lumina.tv.ui.theme.TypeScale

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumina.tv.AppRuntime
import com.lumina.tv.data.Session
import com.lumina.tv.ui.screens.accountFocusAfterRemoval
import com.lumina.tv.ui.screens.accountFocusTargets
import com.lumina.tv.ui.screens.accountRequesterKeys
import com.lumina.tv.ui.theme.TextMain
import com.lumina.tv.ui.theme.TextSub
import com.lumina.tv.ui.theme.ThemeRuntime

@Composable
fun SettingsAccount(
    firstFocusRequester: FocusRequester,
    railRequester: FocusRequester,
    onFocused: (String) -> Unit,
    onLogout: () -> Unit,
    onServerSwitched: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val prefs = AppRuntime.prefs
    var savedServers by remember { mutableStateOf(prefs?.savedServers().orEmpty()) }
    var renameServerUrl by remember { mutableStateOf<String?>(null) }
    var renameSession by remember { mutableStateOf<Session?>(null, referentialEqualityPolicy()) }
    var renameServerText by remember { mutableStateOf("") }
    var showLogoutConfirmation by remember { mutableStateOf(false) }
    var accountError by remember { mutableStateOf(prefs?.credentialStorageError) }
    val session = AppRuntime.session
    val displayServerName = AppRuntime.serverDisplayName ?: prefs?.serverName
    val savedServerUrls = savedServers.map { it.second }
    val focusTargets = accountFocusTargets(savedServerUrls)
    val rowKeys = accountRequesterKeys(savedServerUrls)
    val requesterCache = remember(firstFocusRequester) {
        mutableMapOf("account:current-edit" to firstFocusRequester)
    }
    val requesters = rowKeys.associateWith { key -> requesterCache.getOrPut(key) { FocusRequester() } }
    val listState = settingsListState(SettingsCategory.Account)
    var pendingFocusKey by remember { mutableStateOf<String?>(null) }
    var focusedAccountKey by remember { mutableStateOf<String?>(null) }
    val accountOnFocused: (String) -> Unit = { focusedAccountKey = it; onFocused(it) }
    val groupShape = RoundedCornerShape(SettingsTokens.GroupCorner)

    LaunchedEffect(pendingFocusKey, savedServerUrls) {
        val target = pendingFocusKey ?: return@LaunchedEffect
        val serverIndex = savedServerUrls.indexOfFirst { target == "account:switch:$it" }
        val itemIndex = if (serverIndex >= 0) 3 + serverIndex else 3 + savedServerUrls.size.coerceAtLeast(1)
        listState.scrollToItem(itemIndex)
        var restored = false
        for (attempt in 0 until 3) {
            withFrameNanos { }
            runCatching { requesters[target]?.requestFocus() }
            withFrameNanos { }
            restored = focusedAccountKey == target
            if (restored) break
        }
        if (!restored) runCatching { firstFocusRequester.requestFocus() }
        pendingFocusKey = null
    }

    renameServerUrl?.let { url ->
        AlertDialog(
            onDismissRequest = { renameServerUrl = null },
            title = { Text("编辑服务器名称") },
            text = {
                OutlinedTextField(
                    value = renameServerText,
                    onValueChange = { renameServerText = it.take(32) },
                    singleLine = true,
                    placeholder = { Text("例如：客厅 NAS") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val saved = AppRuntime.renameSavedServer(url, renameServerText, renameSession)
                    savedServers = prefs?.savedServers().orEmpty()
                    if (saved) renameServerUrl = null
                    else accountError = prefs?.credentialStorageError ?: "保存服务器名称失败，请重试。"
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { renameServerUrl = null }) { Text("取消") } },
        )
    }
    if (showLogoutConfirmation) {
        AlertDialog(
            onDismissRequest = { showLogoutConfirmation = false },
            title = { Text("退出 / 更换账号") },
            text = { Text("确定退出当前账号并返回登录页面吗？") },
            confirmButton = {
                TextButton(onClick = {
                    showLogoutConfirmation = false
                    onLogout()
                }) { Text("退出") }
            },
            dismissButton = { TextButton(onClick = { showLogoutConfirmation = false }) { Text("取消") } },
        )
    }

    if (accountError != null) AlertDialog(
        onDismissRequest = { accountError = null },
        title = { Text("操作未完成") },
        text = { Text(accountError.orEmpty()) },
        confirmButton = { TextButton(onClick = { accountError = null }) { Text("确定") } },
    )

    LazyColumn(state = listState, modifier = modifier) {
        item(key = "account:current-label") {
            Text("当前服务器", color = TextSub, fontSize = SettingsTokens.GroupLabelSize, modifier = Modifier.padding(start = 4.dp, bottom = 10.dp))
        }
        item(key = "account:current") {
            Column(Modifier.fillMaxWidth().luminaSurface(SurfaceLevel.Content, shape = groupShape).glassBorder(groupShape).padding(SettingsTokens.RowPaddingH)) {
                Text(displayServerName ?: "-", color = TextMain, fontSize = TypeScale.sp18)
                Spacer(Modifier.height(4.dp))
                Text(session?.baseUrl ?: prefs?.baseUrl ?: "-", color = TextSub, fontSize = TypeScale.sp12)
                Spacer(Modifier.height(10.dp))
                ActionRow(
                    focusKey = "account:current-edit",
                    title = "编辑当前服务器名称",
                    description = "修改当前服务器的显示名称",
                    actionLabel = "编辑",
                    onClick = {
                        renameSession = session
                        renameServerUrl = session?.baseUrl ?: prefs?.baseUrl
                        renameServerText = displayServerName.orEmpty()
                    },
                    focusRequester = requireNotNull(requesters["account:current-edit"]),
                    leftRequester = railRequester,
                    upRequester = focusTargets.getValue("account:current-edit").up?.let(requesters::getValue),
                    onFocused = accountOnFocused,
                    showDivider = false,
                )
            }
        }
        item(key = "account:saved-label") {
            Text("已保存服务器", color = TextSub, fontSize = SettingsTokens.GroupLabelSize, modifier = Modifier.padding(start = 4.dp, top = SettingsTokens.GroupGap, bottom = 10.dp))
        }
        if (savedServers.isEmpty()) {
            item(key = "account:empty") {
                Text("暂无已保存服务器", color = TextSub, fontSize = TypeScale.sp14, modifier = Modifier.fillMaxWidth().luminaSurface(SurfaceLevel.Content, shape = groupShape).padding(SettingsTokens.RowPaddingH))
            }
        } else {
            items(savedServers.size, key = { "account:server:${savedServers[it].second}" }) { index ->
                val (name, url) = savedServers[index]
                Column(Modifier.fillMaxWidth().luminaSurface(SurfaceLevel.Content, shape = groupShape).padding(horizontal = 10.dp, vertical = 4.dp)) {
                    Text(name, color = TextMain, fontSize = TypeScale.sp14, modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp))
                    Text(url, color = TextSub, fontSize = TypeScale.sp11, modifier = Modifier.padding(horizontal = 8.dp))
                    NavigationRow(
                        focusKey = "account:switch:$url",
                        title = "切换服务器",
                        description = "使用此服务器的已保存账号",
                        currentValue = "切换",
                        onClick = {
                            if (AppRuntime.switchToServer(url)) onServerSwitched()
                            else accountError = prefs?.credentialStorageError ?: "切换服务器失败，请重新登录后重试。"
                        },
                        focusRequester = requireNotNull(requesters["account:switch:$url"]),
                        leftRequester = railRequester,
                        upRequester = focusTargets.getValue("account:switch:$url").up?.let(requesters::getValue),
                        downRequester = focusTargets.getValue("account:switch:$url").down?.let(requesters::getValue),
                        onFocused = accountOnFocused,
                    )
                    ActionRow(
                        focusKey = "account:edit:$url",
                        title = "编辑服务器名称",
                        description = "$name · $url",
                        actionLabel = "编辑",
                        onClick = { renameSession = session; renameServerUrl = url; renameServerText = name },
                        focusRequester = requireNotNull(requesters["account:edit:$url"]),
                        leftRequester = railRequester,
                        upRequester = focusTargets.getValue("account:edit:$url").up?.let(requesters::getValue),
                        downRequester = focusTargets.getValue("account:edit:$url").down?.let(requesters::getValue),
                        onFocused = accountOnFocused,
                    )
                    ActionRow(
                        focusKey = "account:delete:$url",
                        title = "删除已保存服务器",
                        description = "$name · $url",
                        actionLabel = "删除",
                        onClick = {
                            if (prefs?.removeSavedServer(url) != true) {
                                accountError = prefs?.credentialStorageError ?: "删除已保存服务器失败，请重试。"
                            } else {
                                val updated = prefs.savedServers()
                                pendingFocusKey = accountFocusAfterRemoval(savedServerUrls, url, updated.map { it.second })
                                savedServers = updated
                            }
                        },
                        focusRequester = requireNotNull(requesters["account:delete:$url"]),
                        leftRequester = railRequester,
                        upRequester = focusTargets.getValue("account:delete:$url").up?.let(requesters::getValue),
                        downRequester = focusTargets.getValue("account:delete:$url").down?.let(requesters::getValue),
                        onFocused = accountOnFocused,
                        danger = true,
                        showDivider = false,
                    )
                }
            }
        }
        item(key = "account:save") {
            Column(Modifier.fillMaxWidth().padding(top = SettingsTokens.GroupGap)) {
                ActionRow(
                    focusKey = "account:save-current",
                    title = "保存当前服务器",
                    description = "将当前服务器和账号加入已保存列表",
                    actionLabel = "保存",
                    onClick = {
                        if (prefs?.saveCurrentServer() != true) {
                            accountError = prefs?.credentialStorageError ?: "保存服务器凭据失败，请重试。"
                        }
                        savedServers = prefs?.savedServers().orEmpty()
                    },
                    focusRequester = requireNotNull(requesters["account:save-current"]),
                    leftRequester = railRequester,
                    upRequester = focusTargets.getValue("account:save-current").up?.let(requesters::getValue),
                    downRequester = focusTargets.getValue("account:save-current").down?.let(requesters::getValue),
                    onFocused = accountOnFocused,
                )
                ActionRow(
                    focusKey = "account:logout",
                    title = "退出 / 更换账号",
                    description = "退出当前账号并返回登录页面",
                    actionLabel = "退出",
                    onClick = { showLogoutConfirmation = true },
                    focusRequester = requireNotNull(requesters["account:logout"]),
                    leftRequester = railRequester,
                    upRequester = focusTargets.getValue("account:logout").up?.let(requesters::getValue),
                    onFocused = accountOnFocused,
                    danger = true,
                    showDivider = false,
                )
            }
        }
        item(key = "account:bottom-space") { Spacer(Modifier.height(SettingsTokens.GroupGap)) }
    }
}
