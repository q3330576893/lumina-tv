package com.lumina.tv.data

import android.content.SharedPreferences
import org.json.JSONObject

/** Each operation captures one account, including both halves of hidden-resume state. */
internal class AccountContentState(private val sp: SharedPreferences, private val scope: String) {
    private fun key(name: String) = "${name}_account_v1_$scope"

    init { synchronized(sp) { migrateLegacy() } }

    private fun migrateLegacy() {
        val marker = key("content_migrated")
        if (sp.getBoolean(marker, false)) return
        val edit = sp.edit()
        // The old global format has NO owner metadata (saved_servers also loses account history).
        // Only an explicitly recorded owner is proof; never infer it from the current login or
        // a single saved server. Unattributed globals remain untouched and are never read as defaults.
        if (sp.getString("legacy_content_owner_scope", null) == scope) {
            if (!sp.contains(key("intro_map"))) {
                sp.getString("intro_map", null)?.let { edit.putString(key("intro_map"), it) }
            }
            if (!sp.contains(key("hidden_view_ids"))) {
                sp.getStringSet("hidden_view_ids", null)?.let { edit.putStringSet(key("hidden_view_ids"), it.toSet()) }
            }
            if (!sp.contains(key("hidden_resume_ids")) && !sp.contains(key("hidden_resume_positions"))) {
                val ids = readMap("hidden_resume_ids")
                val positions = readMap("hidden_resume_positions")
                // Incomplete legacy pairs must not create permanently hidden entries.
                val completeIds = ids.filterKeys { it in positions }
                edit.putString(key("hidden_resume_ids"), JSONObject(completeIds).toString())
                    .putString(key("hidden_resume_positions"), JSONObject(positions.filterKeys { it in completeIds }).toString())
            }
            listOf("intro_map", "hidden_view_ids", "hidden_resume_ids", "hidden_resume_positions", "legacy_content_owner_scope")
                .forEach(edit::remove)
        }
        // Import, removal of the legacy pair, and the marker form a single atomic preferences edit.
        edit.putBoolean(marker, true).apply()
    }

    var introMapJson: String
        get() = sp.getString(key("intro_map"), "{}") ?: "{}"
        set(value) { synchronized(sp) { sp.edit().putString(key("intro_map"), value).apply() } }

    fun setIntroSeconds(itemId: String, seconds: Int) = synchronized(sp) {
        val json = runCatching { JSONObject(introMapJson) }.getOrDefault(JSONObject())
        json.put(itemId, seconds)
        introMapJson = json.toString()
    }

    var hiddenViewIds: Set<String>
        get() = sp.getStringSet(key("hidden_view_ids"), emptySet()).orEmpty().toSet()
        set(value) { synchronized(sp) { sp.edit().putStringSet(key("hidden_view_ids"), value.toSet()).apply() } }

    fun toggleViewHidden(viewId: String) = synchronized(sp) {
        hiddenViewIds = hiddenViewIds.toMutableSet().apply { if (!add(viewId)) remove(viewId) }
    }

    private fun readMap(key: String): Map<String, Long> = runCatching {
        val json = JSONObject(sp.getString(key, "{}") ?: "{}")
        buildMap { json.keys().forEach { id -> put(id, json.getLong(id)) } }
    }.getOrDefault(emptyMap())

    val hiddenResumeIds: Map<String, Long> get() = readMap(key("hidden_resume_ids"))
    private val hiddenResumePositions: Map<String, Long> get() = readMap(key("hidden_resume_positions"))

    private fun writeResume(ids: Map<String, Long>, positions: Map<String, Long>) {
        require(ids.keys == positions.keys)
        sp.edit().putString(key("hidden_resume_ids"), JSONObject(ids).toString())
            .putString(key("hidden_resume_positions"), JSONObject(positions).toString()).apply()
    }

    fun hideResumeItem(item: MediaItem, hiddenAt: Long) = synchronized(sp) {
        val positions = hiddenResumePositions
        val ids = hiddenResumeIds.filterKeys { it in positions }
        writeResume(ids + (item.id to hiddenAt), positions.filterKeys { it in ids } + (item.id to item.positionTicks))
    }

    fun visibleResumeItems(items: List<MediaItem>): List<MediaItem> = synchronized(sp) {
        val originalIds = hiddenResumeIds
        val originalPositions = hiddenResumePositions
        val ids = originalIds.filterKeys { it in originalPositions }.toMutableMap()
        val positions = originalPositions.filterKeys { it in ids }.toMutableMap()
        val visible = items.filter { item ->
            val hiddenAt = ids[item.id] ?: return@filter true
            val playedAgain = item.lastPlayedDate?.let { it > hiddenAt } == true || item.positionTicks != positions[item.id]
            if (playedAgain) { ids.remove(item.id); positions.remove(item.id) }
            playedAgain
        }
        if (ids != originalIds || positions != originalPositions) writeResume(ids, positions)
        visible
    }
}
