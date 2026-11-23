package com.lumina.tv.ui.screens.settings

import com.lumina.tv.ui.theme.TypeScale

import androidx.compose.foundation.background
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumina.tv.AppRuntime
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
    var renameServerText by remember { mutableStateOf("") }
    var showLogoutConfirmation by remember { mutableStateOf(false) }
    val session = AppRuntime.session
    val savedServerUrls = savedServers.map { it.second }
    val focusTargets = accountFocusTargets(savedServerUrls)
    val rowKeys = accountRequesterKeys(savedServerUrls)
    val requesters = remember(rowKeys) {
        rowKeys.mapIndexed { index, key -> key to if (index == 0) firstFocusRequester else FocusRequester() }.toMap()
    }
    val groupShape = RoundedCornerShape(SettingsTokens.GroupCorner)

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
                    prefs?.renameSavedServer(url, renameServerText)
                    savedServers = prefs?.savedServers().orEmpty()
                    renameServerUrl = null
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

    LazyColumn(state = settingsListState(SettingsCategory.Account), modifier = modifier) {
        item(key = "account:current-label") {
            Text("当前服务器", color = TextSub, fontSize = SettingsTokens.GroupLabelSize, modifier = Modifier.padding(start = 4.dp, bottom = 10.dp))
        }
        item(key = "account:current") {
            Column(Modifier.fillMaxWidth().background(ThemeRuntime.frostTint, groupShape).glassBorder(groupShape).padding(SettingsTokens.RowPaddingH)) {
                Text(session?.serverName ?: prefs?.serverName ?: "-", color = TextMain, fontSize = TypeScale.sp18)
                Spacer(Modifier.height(4.dp))
                Text(session?.baseUrl ?: prefs?.baseUrl ?: "-", color = TextSub, fontSize = TypeScale.sp12)
                Spacer(Modifier.height(10.dp))
                ActionRow(
                    focusKey = "account:current-edit",
                    title = "编辑当前服务器名称",
                    description = "修改当前服务器的显示名称",
                    actionLabel = "编辑",
                    onClick = {
                        renameServerUrl = session?.baseUrl ?: prefs?.baseUrl
                        renameServerText = session?.serverName ?: prefs?.serverName.orEmpty()
                    },
                    focusRequester = requireNotNull(requesters["account:current-edit"]),
                    leftRequester = railRequester,
                    upRequester = focusTargets.getValue("account:current-edit").up?.let(requesters::getValue),
                    onFocused = onFocused,
                    showDivider = false,
                )
            }
        }
        item(key = "account:saved-label") {
            Text("已保存服务器", color = TextSub, fontSize = SettingsTokens.GroupLabelSize, modifier = Modifier.padding(start = 4.dp, top = SettingsTokens.GroupGap, bottom = 10.dp))
        }
        if (savedServers.isEmpty()) {
            item(key = "account:empty") {
                Text("暂无已保存服务器", color = TextSub, fontSize = TypeScale.sp14, modifier = Modifier.fillMaxWidth().background(ThemeRuntime.frostTint, groupShape).padding(SettingsTokens.RowPaddingH))
            }
        } else {
            items(savedServers.size, key = { "account:server:${savedServers[it].second}" }) { index ->
                val (name, url) = savedServers[index]
                Column(Modifier.fillMaxWidth().background(ThemeRuntime.frostTint, groupShape).padding(horizontal = 10.dp, vertical = 4.dp)) {
                    Text(name, color = TextMain, fontSize = TypeScale.sp14, modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp))
                    Text(url, color = TextSub, fontSize = TypeScale.sp11, modifier = Modifier.padding(horizontal = 8.dp))
                    NavigationRow(
                        focusKey = "account:switch:$url",
                        title = "切换服务器",
                        description = "使用此服务器的已保存账号",
                        currentValue = "切换",
                        onClick = {
                            if (AppRuntime.switchToServer(url)) onServerSwitched()
                        },
                        focusRequester = requireNotNull(requesters["account:switch:$url"]),
                        leftRequester = railRequester,
                        upRequester = focusTargets.getValue("account:switch:$url").up?.let(requesters::getValue),
                        downRequester = focusTargets.getValue("account:switch:$url").down?.let(requesters::getValue),
                        onFocused = onFocused,
                    )
                    ActionRow(
                        focusKey = "account:edit:$url",
                        title = "编辑服务器名称",
                        description = "$name · $url",
                        actionLabel = "编辑",
                        onClick = { renameServerUrl = url; renameServerText = name },
                        focusRequester = requireNotNull(requesters["account:edit:$url"]),
                        leftRequester = railRequester,
                        upRequester = focusTargets.getValue("account:edit:$url").up?.let(requesters::getValue),
                        downRequester = focusTargets.getValue("account:edit:$url").down?.let(requesters::getValue),
                        onFocused = onFocused,
                    )
                    ActionRow(
                        focusKey = "account:delete:$url",
                        title = "删除已保存服务器",
                        description = "$name · $url",
                        actionLabel = "删除",
                        onClick = {
                            prefs?.removeSavedServer(url)
                            savedServers = prefs?.savedServers().orEmpty()
                        },
                        focusRequester = requireNotNull(requesters["account:delete:$url"]),
                        leftRequester = railRequester,
                        upRequester = focusTargets.getValue("account:delete:$url").up?.let(requesters::getValue),
                        downRequester = focusTargets.getValue("account:delete:$url").down?.let(requesters::getValue),
                        onFocused = onFocused,
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
                    onClick = { prefs?.saveCurrentServer(); savedServers = prefs?.savedServers().orEmpty() },
                    focusRequester = requireNotNull(requesters["account:save-current"]),
                    leftRequester = railRequester,
                    upRequester = focusTargets.getValue("account:save-current").up?.let(requesters::getValue),
                    downRequester = focusTargets.getValue("account:save-current").down?.let(requesters::getValue),
                    onFocused = onFocused,
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
                    onFocused = onFocused,
                    danger = true,
                    showDivider = false,
                )
            }
        }
        item(key = "account:bottom-space") { Spacer(Modifier.height(SettingsTokens.GroupGap)) }
    }
}
