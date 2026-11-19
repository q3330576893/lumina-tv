package com.lumina.tv

import com.lumina.tv.data.MediaItem
import com.lumina.tv.data.UpdateCheckHttp
import com.lumina.tv.data.remainingBudgetMillis
import com.lumina.tv.player.OPTIONAL_VTT_MAX_BYTES
import com.lumina.tv.ui.screens.rankSearchItems
import com.lumina.tv.ui.screens.searchErrorPresentation
import java.io.File

object B8Checks {
    @JvmStatic
    fun main(args: Array<String>) {
        loginDeadline()
        appendErrorPresentation()
        updateTimeouts()
        searchRanking()
        check(OPTIONAL_VTT_MAX_BYTES == 16 * 1024 * 1024)
        println("B8 PASS: login remaining deadline propagation, visible search results on append failure, bounded update requests, one pinyin rank per item, and unchanged VTT size cap")
    }

    private fun loginDeadline() {
        check(remainingBudgetMillis(20_000_000L, 7_000_000L) == 13L)
        check(remainingBudgetMillis(20_000_000L, 20_000_000L) == 0L)
        check(remainingBudgetMillis(20_000_000L, 20_000_001L) == 0L)

        val root = sourceRoot()
        val login = root.resolve("src/main/java/com/lumina/tv/ui/screens/LoginScreen.kt").readText()
        val client = root.resolve("src/main/java/com/lumina/tv/data/EmbyClient.kt").readText()
        check(login.contains("System.nanoTime() + 26_000_000_000L"))
        check(login.contains("remainingBudgetMillis(deadlineNanos)"))
        check(login.contains("testConnection(candidate, timeoutMs = remainingMs)"))
        check(login.contains("currentCoroutineContext().ensureActive()"))
        val afterProbe = login.substringAfter(".testConnection(candidate, timeoutMs = remainingMs)")
        check(afterProbe.indexOf("remainingBudgetMillis(deadlineNanos) <= 0L") in 0 until afterProbe.indexOf("found = candidate"))
        check(client.contains("testConnection(server: String, timeoutMs: Long = 20_000L): String = withMetadataContext(timeoutMs)"))
    }

    private fun appendErrorPresentation() {
        val withResults = searchErrorPresentation(3, "first-page error", "append error")
        check(withResults.primary == null && withResults.append == "append error")
        val withoutResults = searchErrorPresentation(0, null, "append error")
        check(withoutResults.primary == "append error" && withoutResults.append == null)
        val firstPageFailure = searchErrorPresentation(0, "first-page error", null)
        check(firstPageFailure.primary == "first-page error")

        val root = sourceRoot()
        val search = root.resolve("src/main/java/com/lumina/tv/ui/screens/SearchScreen.kt").readText()
        val appendFlow = search.substringAfter("LaunchedEffect(gridState,").substringBefore("val folded =")
        check(appendFlow.contains("appendError = searchFailureMessage(e)"))
        check(!appendFlow.contains("searchError ="))
        check(search.contains("onClick = { retryAppend() }"))
        check(search.contains("onClick = { submitSearch() }"))
    }

    private fun updateTimeouts() {
        val client = UpdateCheckHttp.client
        check(client.connectTimeoutMillis == 4_000)
        check(client.readTimeoutMillis == 5_000)
        check(client.callTimeoutMillis == 8_000)

        val root = sourceRoot()
        val about = root.resolve("src/main/java/com/lumina/tv/ui/screens/settings/SettingsAbout.kt").readText()
        check(about.contains("UpdateCheckHttp.client.readCancellable(request)"))
    }

    private fun searchRanking() {
        val items = listOf(
            MediaItem("contains", "An x story", rating = 2.0),
            MediaItem("prefix", "Xenon", rating = 1.0),
            MediaItem("exact", "x", rating = 2.0),
            MediaItem("pinyin-top", "拼音优先", rating = 9.0),
            MediaItem("pinyin-next", "拼音次序", rating = 1.0),
            MediaItem("excluded", "完全不匹配", rating = 10.0),
        )
        val pinyinCalls = mutableMapOf<String, Int>()
        val ranks = mapOf("拼音优先" to 0, "拼音次序" to 1, "完全不匹配" to 3)
        val ranked = rankSearchItems(items, "x") { name, needle ->
            check(needle == "x")
            pinyinCalls[name] = (pinyinCalls[name] ?: 0) + 1
            ranks.getValue(name)
        }
        check(ranked.map { it.id } == listOf("pinyin-top", "exact", "prefix", "pinyin-next", "contains"))
        check(pinyinCalls == mapOf("拼音优先" to 1, "拼音次序" to 1, "完全不匹配" to 1))
    }

    private fun sourceRoot(): File = if (File("src/main").isDirectory) File(".") else File("app")
}
