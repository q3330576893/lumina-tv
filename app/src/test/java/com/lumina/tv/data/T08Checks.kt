package com.lumina.tv.data

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composition
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateObserver
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.lumina.tv.AppRuntime
import com.lumina.tv.ui.components.activateFocusSurface
import com.lumina.tv.ui.components.relocateDisabledSurfaceFocus
import com.lumina.tv.ui.components.requestSurfaceInitialFocus
import com.lumina.tv.ui.screens.EmptySearchPageEffect
import com.lumina.tv.ui.screens.SEARCH_EMPTY_AUTO_PAGE_LIMIT
import com.lumina.tv.ui.screens.SearchRequestGeneration
import com.lumina.tv.ui.screens.accountFocusAfterRemoval
import com.lumina.tv.ui.screens.accountFocusTargets
import com.lumina.tv.ui.screens.accountRequesterKeys
import com.lumina.tv.ui.screens.focusNeighborAfterRemoval
import com.lumina.tv.ui.screens.incompleteEmptySearch
import com.lumina.tv.ui.screens.rankSearchItems
import com.lumina.tv.ui.screens.runSearchPageRequest
import com.lumina.tv.ui.screens.searchErrorPresentation
import com.lumina.tv.ui.screens.searchHistoryItemKey
import java.io.File
import java.io.IOException
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield

/** Offline policies and real Compose effects; native D-pad/layout/TalkBack still require a device. */
object T08Checks {
    @JvmStatic fun main(args: Array<String>) = runBlocking { run() }

    suspend fun run() {
        accountAndHistoryFocus()
        adaptiveHistoryWidths()
        staleSearchCallbacks()
        fifthPageAndAppendRetry()
        disabledSurfaces()
        renameMetadata()
        sourceWiring()
        println("V6-T08 PASS: three-server focus/deletion; stable history keys/neighbors; fifth-page manual continuation and same-cursor retry; stale query callbacks; disabled activation/initial focus/relocation; observable rename and Session/client/sender identity; failed save and identity-switch races")
    }

    private fun accountAndHistoryFocus() {
        val urls = listOf("https://one/Emby", "https://two/emby", "https://three")
        for (remaining in listOf(emptyList(), urls.take(1), urls.take(2), urls)) {
            val graph = accountFocusTargets(remaining)
            val keys = accountRequesterKeys(remaining).toSet()
            val visited = linkedSetOf<String>()
            var current: String? = "account:current-edit"
            while (current != null) {
                check(visited.add(current)) { "Account focus cycle at $current" }
                val targets = graph.getValue(current)
                check(targets.up == null || targets.up in keys)
                check(targets.down == null || targets.down in keys)
                current = targets.down
            }
            check(visited == keys)
        }
        check(accountFocusTargets(urls).getValue("account:delete:${urls[0]}").down == "account:switch:${urls[1]}")
        check(accountFocusTargets(urls).getValue("account:delete:${urls[1]}").down == "account:switch:${urls[2]}")
        urls.forEachIndexed { index, removed ->
            val remaining = urls - removed
            val expected = urls.getOrNull(index + 1) ?: urls[index - 1]
            check(accountFocusAfterRemoval(urls, removed, remaining) == "account:switch:$expected")
            check(accountFocusAfterRemoval(urls, removed, remaining) in accountRequesterKeys(remaining))
        }
        check(accountFocusAfterRemoval(urls.take(1), urls.first(), emptyList()) == "account:save-current")

        val history = listOf("label", "一个很长的搜索词，含空格和符号 : /", "latest:label", "four", "five", "six", "seven", "eight")
        val keys = history.associateWith(::searchHistoryItemKey)
        check(keys.values.toSet().size == history.size)
        check(keys.values.none { it in setOf("search-history-heading", "search-latest-heading", "search-latest-empty") })
        history.forEachIndexed { index, removed ->
            val remaining = history - removed
            check(remaining.associateWith(::searchHistoryItemKey) == keys - removed)
            check(focusNeighborAfterRemoval(history, removed, remaining) == (history.getOrNull(index + 1) ?: history[index - 1]))
        }
        check(focusNeighborAfterRemoval(listOf("only"), "only", emptyList()) == null)
        check(focusNeighborAfterRemoval(history, history.first(), listOf(history.last())) == history.last())
        println("T08 focus PASS: down traversal reaches all 3 servers; first/middle/last/only deletion; 8 long history entries and reserved-key collisions")
    }

    private suspend fun staleSearchCallbacks() = coroutineScope {
        for (fails in listOf(false, true)) {
            val generation = SearchRequestGeneration()
            val old = generation.update(listOf("old", 0))
            check(generation.update(listOf("old", 0)) == old)
            var focused = "old"
            var result = "old"
            var failures = 0
            var finishes = 0
            val response = CompletableDeferred<Unit>()
            val request = launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    runSearchPageRequest(isCurrent = { generation.accepts(old) },
                        onFailure = { failures++ }, onFinished = { finishes++ }) {
                        response.await()
                        if (fails) throw IOException("late old-query error")
                        generation.ensureCurrent(old)
                        result = "late old result"
                    }
                } catch (_: CancellationException) { }
            }
            generation.invalidate() // Synchronous event admission, before the next composition.
            generation.accept(old) { focused = "late old focus" }
            check(focused == "old")
            val fresh = generation.update(listOf("new", 1))
            generation.accept(fresh) { focused = "new" }
            result = "new"
            response.complete(Unit)
            request.join()
            generation.accept(old) { focused = "late old focus" }
            check(focused == "new" && result == "new" && failures == 0 && finishes == 0)
            runSearchPageRequest(isCurrent = { generation.accepts(fresh) },
                onFailure = { failures++ }, onFinished = { finishes++ }) { throw IOException("current failure") }
            check(failures == 1 && finishes == 1)
            generation.invalidate()
            val repeatQuery = generation.update(listOf("new", 1))
            check(repeatQuery != fresh && !generation.accepts(fresh))
        }
        println("T08 search generation PASS: stale focus, success, error and finally cannot overwrite a new or repeated query")
    }

    private fun adaptiveHistoryWidths() {
        var cases = 0
        for (widthDp in listOf(360, 640, 960, 1280, 1920)) {
            for (densityScale in listOf(1f, 1.5f, 2f)) {
                var previousSizes: List<Int>? = null
                for (fontScale in listOf(0.85f, 1f, 1.3f, 1.5f, 2f)) {
                    val density = Density(densityScale, fontScale)
                    val available = with(density) { (widthDp - 80).dp.roundToPx() }
                    val spacing = with(density) { 14.dp.roundToPx() }
                    val sizes = with(GridCells.Adaptive(150.dp)) { density.calculateCrossAxisCellSizes(available, spacing) }
                    check(sizes.isNotEmpty() && sizes.all { it > 0 })
                    check(sizes.sum() + spacing * (sizes.size - 1) == available)
                    check(sizes.max() - sizes.min() <= 1)
                    if (previousSizes != null) check(sizes == previousSizes)
                    previousSizes = sizes
                    // Use the actual Compose cell sizes to place every one of the eight history items.
                    for (index in 0 until 8) {
                        val column = index % sizes.size
                        val left = sizes.take(column).sum() + column * spacing
                        check(left >= 0 && left + sizes[column] <= available)
                    }
                    cases++
                }
            }
        }
        check(cases == 75)
        println("T08 adaptive grid PASS: Compose GridCells native sizing, 360/640/960/1280/1920 dp, density 1/1.5/2, fontScale 0.85/1/1.3/1.5/2; 8 entries stay within cell bounds (75 cases; rendered text still device_pending)")
    }

    private suspend fun fifthPageAndAppendRetry() = coroutineScope {
        val clock = object : MonotonicFrameClock {
            override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R = onFrame(System.nanoTime())
        }
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val applier = object : AbstractApplier<Unit>(Unit) {
            override fun insertTopDown(index: Int, instance: Unit) = Unit
            override fun insertBottomUp(index: Int, instance: Unit) = Unit
            override fun remove(index: Int, count: Int) = Unit
            override fun move(from: Int, to: Int, count: Int) = Unit
            override fun onClear() = Unit
        }
        val composition = Composition(applier, recomposer)
        fun page(offset: Int) = (offset until offset + 40).map {
            MediaItem("id-$it", if (it == 160) "needle" else "unrelated-$it", "Movie")
        }
        var rows by mutableStateOf(page(0))
        var pageStart by mutableIntStateOf(40)
        var autoPages by mutableIntStateOf(0)
        var continuation by mutableIntStateOf(0)
        var loading by mutableStateOf(false)
        var hasMore by mutableStateOf(true)
        var appendError by mutableStateOf<String?>(null)
        var failNext = false
        val requested = mutableListOf<Int>()
        suspend fun pumpUntil(condition: () -> Boolean) = withTimeout(10_000) {
            while (!condition()) {
                Snapshot.sendApplyNotifications()
                yield()
                recomposer.awaitIdle()
                delay(5)
            }
        }
        fun visible() = rankSearchItems(rows, "needle") { _, _ -> 3 }.size
        fun continueSearch() {
            appendError = null
            autoPages = 0
            continuation++
            hasMore = true
        }
        try {
            composition.setContent {
                EmptySearchPageEffect(
                    searchKey = listOf("needle"), loadedItemCount = pageStart,
                    visibleCount = visible(), hasMore = { hasMore }, loading = { loading },
                    automaticallyLoadedPages = { autoPages }, continuationRequest = { continuation },
                ) {
                    val offset = pageStart
                    requested += offset
                    autoPages++
                    loading = true
                    runSearchPageRequest(onFailure = { appendError = it.message; hasMore = false },
                        onFinished = { loading = false }) {
                        delay(10)
                        if (failNext) { failNext = false; throw IOException("append failed") }
                        rows = rows + page(offset)
                        pageStart += 40
                    }
                }
            }
            pumpUntil { autoPages == SEARCH_EMPTY_AUTO_PAGE_LIMIT && !loading }
            check(requested == listOf(40, 80, 120) && pageStart == 160 && rows.size == 160 && visible() == 0) {
                "Empty-page cap: requested=$requested cursor=$pageStart rows=${rows.size} visible=${visible()}"
            }
            check(incompleteEmptySearch(visible(), hasMore, loading, autoPages))
            check(!incompleteEmptySearch(0, false, false, autoPages))
            check(!incompleteEmptySearch(0, true, true, autoPages))

            failNext = true
            continueSearch()
            pumpUntil { appendError != null && !loading }
            check(pageStart == 160 && rows.size == 160 && !hasMore)
            check(searchErrorPresentation(0, null, appendError).primary == "append failed")
            continueSearch()
            pumpUntil { visible() == 1 && !loading }
            check(requested == listOf(40, 80, 120, 160, 160) && pageStart == 200) {
                "Manual fifth page: requested=$requested cursor=$pageStart visible=${visible()}"
            }
            check(rows.size == 200 && appendError == null)
            val successfulRows = rows
            runSearchPageRequest(onFailure = { appendError = it.message }, onFinished = {}) { throw IOException("later append failure") }
            check(rows == successfulRows && pageStart == 200)
            check(searchErrorPresentation(visible(), null, appendError).append == "later append failure")
            println("T08 Compose search PASS: cap after page 4; manual page 5; failed append preserves 160 rows/cursor; retry offset 160 finds needle; later failure keeps visible results")
        } finally {
            composition.dispose()
            recomposer.close()
            runner.join()
        }
    }

    private suspend fun disabledSurfaces() = coroutineScope {
        var activations = 0
        activateFocusSurface { activations++ }
        repeat(3) { activateFocusSurface(false) { activations++ } }
        check(activations == 1)
        var enabled = false
        var requests = 0
        requestSurfaceInitialFocus(true, { enabled }) { requests++ }
        enabled = true
        val delayed = launch(start = CoroutineStart.UNDISPATCHED) {
            requestSurfaceInitialFocus(true, { enabled }) { requests++ }
        }
        enabled = false
        delayed.join()
        check(requests == 0)
        enabled = true
        requestSurfaceInitialFocus(true, { enabled }) { requests++ }
        requestSurfaceInitialFocus(false, { enabled }) { requests++ }
        check(requests == 1)
        val cancelled = launch(start = CoroutineStart.UNDISPATCHED) {
            requestSurfaceInitialFocus(true, { enabled }) { requests++ }
        }
        cancelled.cancelAndJoin()
        check(requests == 1)
        for (nextSucceeds in listOf(false, true)) for (previousSucceeds in listOf(false, true)) {
            val movements = mutableListOf<String>()
            check(relocateDisabledSurfaceFocus(false, true,
                next = { movements += "next"; nextSucceeds },
                previous = { movements += "previous"; previousSucceeds },
                clear = { movements += "clear" }))
            check(movements == if (nextSucceeds) listOf("next") else if (previousSucceeds) listOf("next", "previous") else listOf("next", "previous", "clear"))
        }
        check(!relocateDisabledSurfaceFocus(true, true, { error("enabled item moved") }, { false }, {}))
        check(!relocateDisabledSurfaceFocus(false, false, { error("unfocused item moved") }, { false }, {}))
        println("T08 disabled PASS: default enabled; no disabled activation; late disable/cancel rejects initial focus; next/previous/clear relocation")
    }

    private fun renameMetadata() {
        val oldPrefs = AppRuntime.prefs
        val oldSession = AppRuntime.session
        val oldClient = AppRuntime.client
        val a = Session("https://host/Emby", "t08-token-a", "user-a", "User A", "Server A")
        val b = Session("https://other/emby", "t08-token-b", "user-b", "User B", "Server B")
        val dialogOwner = mutableStateOf<Session?>(a, referentialEqualityPolicy())
        val relogged = a.copy()
        dialogOwner.value = relogged
        check(dialogOwner.value === relogged && dialogOwner.value !== a)
        val memory = B2MemoryPrefs()
        val pauseSave = AtomicBoolean(false)
        val saveEntered = CountDownLatch(1)
        val releaseSave = CountDownLatch(1)
        val seal: (String) -> String? = { value ->
            if (value.startsWith("[") && pauseSave.compareAndSet(true, false)) {
                saveEntered.countDown()
                check(releaseSave.await(5, TimeUnit.SECONDS)) { "Rename persistence was not released" }
            }
            Base64.getEncoder().encodeToString(value.toByteArray())
        }
        val p = Prefs(memory.sp, seal, { value -> runCatching { String(Base64.getDecoder().decode(value)) }.getOrNull() })
        fun saved(s: Session) = SavedServer(s.serverName, s.baseUrl, s.userName, s.userId, s.token, 1)
        fun sender(client: EmbyClient) = EmbyClient::class.java.getDeclaredField("progressSender").apply { isAccessible = true }.get(client)
        var changes = 0
        val observer = SnapshotStateObserver { it() }
        observer.start()
        try {
            AppRuntime.prefs = p
            AppRuntime.client = null
            AppRuntime.session = null
            check(p.upsertServer(saved(a)) && p.upsertServer(saved(b)))
            AppRuntime.applySession(a)
            val client = checkNotNull(AppRuntime.client)
            val progressSender = sender(client)
            Snapshot.sendApplyNotifications()
            observer.observeReads("server display", { changes++ }) { check(AppRuntime.serverDisplayName == "Server A") }
            check(AppRuntime.renameSavedServer("HTTPS://HOST:443/Emby/", "  Renamed A  ", a))
            Snapshot.sendApplyNotifications()
            check(changes == 1 && AppRuntime.serverDisplayName == "Renamed A")
            check(p.readSession() == a.copy(serverName = "Renamed A"))
            check(AppRuntime.session === a && AppRuntime.client === client && sender(client) === progressSender)
            check(org.json.JSONObject(AppRuntime.stateJson()).getString("server") == "Renamed A")

            val changeCount = changes
            check(AppRuntime.renameSavedServer(b.baseUrl, "Renamed B", a))
            Snapshot.sendApplyNotifications()
            check(AppRuntime.serverDisplayName == "Renamed A" && changes == changeCount)
            check(p.savedServerList().single { it.url == b.baseUrl }.name == "Renamed B")
            check(AppRuntime.session === a && AppRuntime.client === client && sender(client) === progressSender)

            memory.failCommit = true
            check(!AppRuntime.renameSavedServer(a.baseUrl, "Rejected", a))
            memory.failCommit = false
            check(AppRuntime.serverDisplayName == "Renamed A" && p.readSession()?.serverName == "Renamed A")
            // Two saved-list commits succeed; fail the following current-session commit.
            memory.failAtCommit = memory.commitAttempts + 3
            check(!AppRuntime.renameSavedServer(a.baseUrl, "Session commit rejected", a))
            memory.failAtCommit = null
            check(AppRuntime.serverDisplayName == "Renamed A" && p.readSession()?.serverName == "Renamed A")
            check(AppRuntime.session === a && AppRuntime.client === client && sender(client) === progressSender)
            check(AppRuntime.renameSavedServer(a.baseUrl, "Retry A", a))

            val otherAccount = a.copy(token = "t08-token-other", userId = "other-user", userName = "Other", serverName = "Other account")
            AppRuntime.applySession(otherAccount)
            val otherClient = AppRuntime.client
            val beforeStale = memory.values.toMap()
            check(!AppRuntime.renameSavedServer(a.baseUrl, "Stale A dialog", a))
            check(!p.renameSavedServer(a.baseUrl, "Stale A persistence", a))
            check(memory.values == beforeStale && p.readSession() == otherAccount)
            check(AppRuntime.session === otherAccount && AppRuntime.client === otherClient && AppRuntime.serverDisplayName == "Other account")
            AppRuntime.applySession(a.copy()) // Equal credentials, new login/session object.
            val beforeRelogin = memory.values.toMap()
            check(!AppRuntime.renameSavedServer(a.baseUrl, "Old login dialog", a))
            check(memory.values == beforeRelogin)

            AppRuntime.applySession(a)
            pauseSave.set(true)
            val error = AtomicReference<Throwable?>(null)
            val renamed = AtomicBoolean(false)
            val rename = thread(isDaemon = true, name = "t08-rename") {
                runCatching { renamed.set(AppRuntime.renameSavedServer(a.baseUrl, "Concurrent A", a)) }.onFailure { error.set(it) }
            }
            check(saveEntered.await(5, TimeUnit.SECONDS))
            val switchAttempted = CountDownLatch(1)
            val switchDone = CountDownLatch(1)
            val switch = thread(isDaemon = true, name = "t08-switch") {
                switchAttempted.countDown()
                runCatching { AppRuntime.applySession(b) }.onFailure { error.set(it) }
                switchDone.countDown()
            }
            try {
                check(switchAttempted.await(5, TimeUnit.SECONDS))
                check(!switchDone.await(50, TimeUnit.MILLISECONDS)) { "Identity transition bypassed rename serialization" }
            } finally { releaseSave.countDown() }
            rename.join(5_000)
            switch.join(5_000)
            check(!rename.isAlive && !switch.isAlive && error.get() == null && renamed.get()) { "Rename/switch race failed: ${error.get()}" }
            check(AppRuntime.session === b && p.readSession() == b && AppRuntime.serverDisplayName == b.serverName)
            check(p.savedServerList().single { it.url == b.baseUrl }.name == "Renamed B")
            check(AppRuntime.logout() && AppRuntime.serverDisplayName == null)
            println("T08 rename PASS: observable metadata only; Session === / client === / progressSender ===; current/noncurrent; list/session commit failures and retry; same-URL account switch; same-credentials relogin; switch during persistence")
        } finally {
            releaseSave.countDown()
            observer.stop()
            observer.clear()
            AppRuntime.client?.close()
            AppRuntime.prefs = oldPrefs
            AppRuntime.session = oldSession
            AppRuntime.client = oldClient
        }
    }

    private fun sourceWiring() {
        val root = if (File("src/main").isDirectory) File(".") else File("app")
        fun source(path: String) = root.resolve("src/main/java/com/lumina/tv/$path").readText()
        val focus = source("ui/components/Components.kt").substringAfter("fun FocusSurface(").substringBefore("internal fun ResumeRemovalPill")
        check(focus.contains("enabled: Boolean = true") && focus.contains(".clickable(enabled = enabled,"))
        check(focus.contains(".focusProperties { canFocus = enabled }") && focus.contains("LaunchedEffect(requestFocus, enabled)"))
        val detail = source("ui/screens/DetailScreen.kt").substringAfter("private fun SettingRow(").substringBefore("private enum class MediaBadge")
        check(detail.contains("enabled = enabled, onClick = onClick"))
        val search = source("ui/screens/SearchScreen.kt")
        check(search.contains("items(history, key = { searchHistoryItemKey(it) })"))
        check(search.contains("latestGridState.scrollToItem") && search.contains("historyRequesters.getValue(entry)"))
        check(search.contains("GridCells.Adaptive(150.dp)") && search.contains("heightIn(min = 40.dp)"))
        check(search.contains("尚未搜完，可手动继续搜索") && search.contains("继续搜索"))
        check(!search.contains("EmptyView(errorPresentation.primary!!)") && search.contains("searchGeneration.accept(searchTicket) { focusedItemId = item.id }"))
        val account = source("ui/screens/settings/SettingsAccount.kt")
        check(account.contains("requesterCache.getOrPut(key)") && account.contains("listState.scrollToItem(itemIndex)"))
        check(account.contains("AppRuntime.renameSavedServer(url, renameServerText, renameSession)"))
        check(account.contains("mutableStateOf<Session?>(null, referentialEqualityPolicy())"))
        check(source("ui/screens/HomeScreen.kt").contains("AppRuntime.serverDisplayName"))
        println("T08 wiring PASS: native enabled/canFocus, dialog fallback, stable lazy keys, mounted deletion targets, adaptive history, visible manual continuation and observable display consumers")
    }
}
