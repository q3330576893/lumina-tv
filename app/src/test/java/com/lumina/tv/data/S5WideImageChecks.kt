package com.lumina.tv.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/** Regression checks for server metadata, old home caches and the shared image fallback order. */
object S5WideImageChecks {
    @JvmStatic
    fun main(args: Array<String>) {
        val client = EmbyClient("https://example.invalid")
        try {
            val parse = EmbyClient::class.java.getDeclaredMethod("parseItem", JSONObject::class.java)
                .apply { isAccessible = true }
            fun parseItem(json: JSONObject) = parse.invoke(client, json) as MediaItem

            val item = parseItem(JSONObject()
                .put("Id", "episode").put("Name", "Episode")
                .put("ImageTags", JSONObject().put("Primary", "poster-tag"))
                .put("BackdropImageTags", JSONArray().put("own-0").put("").put("own-2").put(JSONObject.NULL))
                .put("ParentBackdropItemId", "series")
                .put("ParentBackdropImageTags", JSONArray().put("parent-0").put("").put("parent-2")))
            check(item.backdropTags == mapOf(0 to "own-0", 2 to "own-2"))
            check(item.backdropTag == "own-0" && item.imageTag == "poster-tag")
            check(item.parentBackdropItemId == "series")
            check(item.parentBackdropTags == mapOf(0 to "parent-0", 2 to "parent-2"))

            val compatible = parseItem(JSONObject()
                .put("BackdropImageTags", JSONObject().put("2", "own-2").put("0", "own-0")
                    .put("1", "").put("invalid", "ignored").put("-1", "ignored"))
                .put("ParentBackdropImageTags", JSONObject().put("0", "parent-0").put("1", JSONObject.NULL)))
            check(compatible.backdropTags == item.backdropTags)
            check(compatible.parentBackdropTags == mapOf(0 to "parent-0"))
            val missing = parseItem(JSONObject().put("ParentBackdropItemId", JSONObject.NULL))
            check(missing.backdropTags.isEmpty() && missing.backdropTag == null)
            check(missing.parentBackdropTags.isEmpty() && missing.parentBackdropItemId == null)

            val write = LibraryCache::class.java.getDeclaredMethod("toJson", MediaItem::class.java)
                .apply { isAccessible = true }
            val read = LibraryCache::class.java.getDeclaredMethod("readItems", JSONObject::class.java, String::class.java)
                .apply { isAccessible = true }
            fun readItem(json: JSONObject): MediaItem {
                val wrapper = JSONObject().put("items", JSONArray().put(json))
                return (read.invoke(LibraryCache, wrapper, "items") as List<*>).single() as MediaItem
            }
            val saved = write.invoke(LibraryCache, item) as JSONObject
            check(saved.getJSONObject("backdropTags").getString("2") == "own-2")
            check(saved.getJSONObject("parentBackdropTags").getString("0") == "parent-0")
            val restored = readItem(JSONObject(saved.toString()))
            check(restored.backdropTag == item.backdropTag && restored.backdropTags == item.backdropTags)
            check(restored.parentBackdropItemId == item.parentBackdropItemId && restored.parentBackdropTags == item.parentBackdropTags)
            val old = readItem(JSONObject().put("id", "legacy").put("name", "Legacy").put("backdropTag", "legacy-0"))
            check(old.backdropTags.isEmpty() && old.parentBackdropTags.isEmpty() && old.parentBackdropItemId == null)

            val tmdb = "https://images.example.invalid/backdrop.jpg"
            val candidates = client.wideImageCandidates(restored, 1280, tmdbUrl = tmdb)
            check(candidates.map { it.source } == listOf("emby", "emby", "emby", "parent", "parent", "parent", "tmdb", "poster"))
            check(candidates.map { URI(it.url).path } == listOf(
                "/Items/episode/Images/Backdrop/0", "/Items/episode/Images/Backdrop/2",
                "/Items/episode/Images/Backdrop/0", "/Items/series/Images/Backdrop/0",
                "/Items/series/Images/Backdrop/2", "/Items/series/Images/Backdrop/0", "/backdrop.jpg", "/Items/episode/Images/Primary"))
            check(query(candidates[0].url)["tag"] == "own-0" && query(candidates[2].url)["tag"] == null)
            check(query(candidates[3].url)["tag"] == "parent-0" && query(candidates[4].url)["tag"] == "parent-2" && query(candidates[5].url)["tag"] == null)
            check(candidates.take(6).all { query(it.url)["maxWidth"] == "1280" && query(it.url)["quality"] == "80" })
            check(client.wideImageCandidates(restored, 480, quality = 67).dropLast(1)
                .all { query(it.url)["maxWidth"] == "480" && query(it.url)["quality"] == "67" })

            val bare = MediaItem("bare", "Bare")
            val bareCandidates = client.wideImageCandidates(bare, 1280)
            check(bareCandidates.size == 1 && URI(bareCandidates.single().url).path == "/Items/bare/Images/Backdrop/0")
            check(query(bareCandidates.single().url)["tag"] == null)
            check(client.wideImageCandidates(old, 1280).size == 2)
            check(query(client.wideImageCandidates(old, 1280).first().url)["tag"] == "legacy-0")
            val parentOnly = restored.copy(backdropTags = emptyMap(), backdropTag = null)
            check(client.wideImageCandidates(parentOnly, 1280, tmdbUrl = tmdb).map { it.source } ==
                listOf("emby", "parent", "parent", "parent", "tmdb", "poster"))
            val duplicated = restored.copy(parentBackdropItemId = restored.id, parentBackdropTags = mapOf(0 to "own-0"))
            check(client.wideImageCandidates(duplicated, 1280, tmdbUrl = candidates[2].url).map { it.source } ==
                listOf("emby", "emby", "emby", "poster"))
            check(query(client.backdropUrl(restored)!!)["quality"] == "80")
            check(query(client.backdropUrl(restored, index = 2, maxWidth = 480)!!)["quality"] == "80")
            check(query(client.backdropUrl("series", "parent-0")!!)["quality"] == "80")
            check(query(client.heroStillUrl(restored.id, 1))["quality"] == "80")
            println("S5 checks passed: array/object parsing, parent metadata, cache round-trip/legacy cache, index 0, order/dedup, poster last, sizes/quality.")
        } finally {
            client.close()
        }
    }

    private fun query(url: String): Map<String, String> = URI(url).rawQuery.orEmpty()
        .split('&').filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
}
