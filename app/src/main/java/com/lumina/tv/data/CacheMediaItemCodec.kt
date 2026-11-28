package com.lumina.tv.data

import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference

/** Disk-only schema. Network parsing remains owned by EmbyClient. */
internal object CacheMediaItemCodec {
    const val SCHEMA_VERSION = 1

    // Every constructor field is required, even nullable fields and empty collections.
    val fieldNames = setOf(
        "id", "name", "type", "imageTag", "overview", "year", "endYear", "rating", "runtimeTicks",
        "seriesId", "seasonId", "indexNumber", "parentIndexNumber", "seriesName",
        "positionTicks", "played", "playedPercentage", "primaryImageAspectRatio", "backdropTag",
        "backdropTags", "parentBackdropItemId", "parentBackdropTags", "logoTag", "sourceCount",
        "communityRating", "genres", "isFavorite", "resolution", "isHdr", "isDolbyVision",
        "audioFormat", "people", "providerIds", "productionLocations", "originalLanguage",
        "lastPlayedDate", "isMissing", "contentVersion",
    )

    data class Fields(val schemaVersion: Int?, val present: Set<String>, val invalid: Set<String>) {
        val missing: Set<String> get() = fieldNames - present
        val requiresRefresh: Boolean get() = schemaVersion != SCHEMA_VERSION || missing.isNotEmpty() || invalid.isNotEmpty()
    }

    data class Envelope(val item: MediaItem, val fields: Fields)

    // Keep provenance alongside the exact decoded object, without changing MediaItem or retaining
    // dead home snapshots. Value equality must not let a fresh item promote an incomplete disk item.
    private class ItemReference(item: MediaItem, queue: ReferenceQueue<MediaItem>? = null) :
        WeakReference<MediaItem>(item, queue) {
        private val identity = System.identityHashCode(item)
        override fun hashCode() = identity
        override fun equals(other: Any?): Boolean = this === other ||
            (other is ItemReference && get()?.let { it === other.get() } == true)
    }
    private val collected = ReferenceQueue<MediaItem>()
    private val provenance = HashMap<ItemReference, Fields>()

    private fun discardCollected() {
        while (true) provenance.remove(collected.poll() ?: break)
    }

    fun fieldsOf(item: MediaItem): Fields? = synchronized(provenance) {
        discardCollected()
        provenance[ItemReference(item)]
    }

    fun requiresRefresh(item: MediaItem) = fieldsOf(item)?.requiresRefresh == true

    fun encode(item: MediaItem): JSONObject = with(item) {
        val json = JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put("id", id).put("name", name).put("type", type)
            .putNullable("imageTag", imageTag).putNullable("overview", overview).putNullable("year", year).putNullable("endYear", endYear)
            .putNullable("rating", rating).putNullable("runtimeTicks", runtimeTicks).putNullable("seriesId", seriesId)
            .putNullable("seasonId", seasonId).putNullable("indexNumber", indexNumber).putNullable("parentIndexNumber", parentIndexNumber)
            .putNullable("seriesName", seriesName).put("positionTicks", positionTicks).put("played", played)
            .put("playedPercentage", playedPercentage).putNullable("primaryImageAspectRatio", primaryImageAspectRatio)
            .putNullable("backdropTag", backdropTag).put("backdropTags", indexedTags(backdropTags))
            .putNullable("parentBackdropItemId", parentBackdropItemId).put("parentBackdropTags", indexedTags(parentBackdropTags))
            .putNullable("logoTag", logoTag).put("sourceCount", sourceCount)
            .putNullable("communityRating", communityRating).put("genres", JSONArray(genres)).put("isFavorite", isFavorite)
            .putNullable("resolution", resolution).put("isHdr", isHdr).put("isDolbyVision", isDolbyVision)
            .putNullable("audioFormat", audioFormat)
            .put("people", JSONArray().also { array -> people.forEach { person -> array.put(JSONObject()
                .putNullable("id", person.id).put("name", person.name).putNullable("role", person.role)
                .putNullable("type", person.type).putNullable("imageTag", person.imageTag)) } })
            .put("providerIds", JSONObject(providerIds)).put("productionLocations", JSONArray(productionLocations))
            .putNullable("originalLanguage", originalLanguage).putNullable("lastPlayedDate", lastPlayedDate)
            .put("isMissing", isMissing).put("contentVersion", contentVersion)
        // A partial home refresh may reuse old rows. Never write their display defaults as facts.
        fieldsOf(item)?.let { fields ->
            (fields.missing + fields.invalid).forEach(json::remove)
            if (fields.schemaVersion == null) json.remove("schemaVersion")
            else json.put("schemaVersion", fields.schemaVersion)
        }
        json
    }

    fun decode(json: JSONObject): Envelope? {
        val reader = Reader(json)
        val item = with(reader) {
            MediaItem(
                id = string("id"), name = string("name"), type = string("type"),
                imageTag = nullableString("imageTag"), overview = nullableString("overview"),
                year = nullableInt("year"), endYear = nullableInt("endYear"), rating = nullableDouble("rating"), runtimeTicks = nullableLong("runtimeTicks"),
                seriesId = nullableString("seriesId"), seasonId = nullableString("seasonId"),
                indexNumber = nullableInt("indexNumber"), parentIndexNumber = nullableInt("parentIndexNumber"),
                seriesName = nullableString("seriesName"), positionTicks = long("positionTicks"), played = bool("played"),
                playedPercentage = double("playedPercentage"), primaryImageAspectRatio = nullableDouble("primaryImageAspectRatio"),
                backdropTag = nullableString("backdropTag"), backdropTags = tags("backdropTags"),
                parentBackdropItemId = nullableString("parentBackdropItemId"), parentBackdropTags = tags("parentBackdropTags"),
                logoTag = nullableString("logoTag"), sourceCount = int("sourceCount"), communityRating = nullableDouble("communityRating"),
                genres = strings("genres"), isFavorite = bool("isFavorite"), resolution = nullableString("resolution"),
                isHdr = bool("isHdr"), isDolbyVision = bool("isDolbyVision"), audioFormat = nullableString("audioFormat"),
                people = people(), providerIds = stringMap("providerIds"), productionLocations = strings("productionLocations"),
                originalLanguage = nullableString("originalLanguage"), lastPlayedDate = nullableLong("lastPlayedDate"),
                isMissing = bool("isMissing"), contentVersion = string("contentVersion"),
            )
        }
        if (item.id.isBlank()) return null
        val version = if (!json.has("schemaVersion")) null else runCatching { integer(json.get("schemaVersion")) }.getOrDefault(-1)
        val fields = Fields(version, fieldNames.filterTo(mutableSetOf()) { json.has(it) }, reader.invalid.toSet())
        synchronized(provenance) {
            discardCollected()
            provenance[ItemReference(item, collected)] = fields
        }
        return Envelope(item, fields)
    }

    private fun indexedTags(tags: Map<Int, String>) = JSONObject().also { json ->
        tags.forEach { (index, tag) -> json.put(index.toString(), tag) }
    }

    private fun JSONObject.putNullable(key: String, value: Any?) = put(key, value ?: JSONObject.NULL)
    private fun text(value: Any): String = (value as? String) ?: error("Expected string")
    private fun integral(value: Any): Long = when (value) {
        is Int -> value.toLong()
        is Long -> value
        else -> error("Expected integer")
    }
    private fun integer(value: Any): Int = integral(value).let {
        require(it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
        it.toInt()
    }
    private fun decimal(value: Any): Double = (value as? Number)?.toDouble()?.also {
        require(it.isFinite())
    } ?: error("Expected number")
    private fun boolean(value: Any): Boolean = (value as? Boolean) ?: error("Expected boolean")
    private fun stringEntries(value: Any): Map<String, String> {
        val json = value as? JSONObject ?: error("Expected object")
        return json.keys().asSequence().associateWith { text(json.get(it)) }
    }

    private class Reader(private val json: JSONObject) {
        val invalid = mutableSetOf<String>()
        private fun <T> read(key: String, fallback: T, parse: (Any) -> T): T {
            if (!json.has(key)) return fallback
            return try { parse(json.get(key)) } catch (_: Exception) {
                invalid += key
                fallback
            }
        }
        private fun <T> nullable(key: String, parse: (Any) -> T): T? =
            read(key, null as T?) { if (it === JSONObject.NULL) null else parse(it) }

        fun string(key: String) = read(key, "", ::text)
        fun nullableString(key: String) = nullable(key, ::text)
        fun int(key: String) = read(key, 0, ::integer)
        fun nullableInt(key: String) = nullable(key, ::integer)
        fun long(key: String) = read(key, 0L, ::integral)
        fun nullableLong(key: String) = nullable(key, ::integral)
        fun double(key: String) = read(key, 0.0, ::decimal)
        fun nullableDouble(key: String) = nullable(key, ::decimal)
        fun bool(key: String) = read(key, false, ::boolean)
        fun strings(key: String): List<String> = read(key, emptyList()) { value ->
            val array = value as? JSONArray ?: error("Expected array")
            (0 until array.length()).map { text(array.get(it)) }
        }
        fun stringMap(key: String) = read(key, emptyMap(), ::stringEntries)
        fun tags(key: String): Map<Int, String> = read(key, emptyMap()) { value ->
            stringEntries(value).mapKeys { (rawIndex, _) ->
                val index = rawIndex.toInt()
                require(index.toString() == rawIndex)
                index
            }
        }
        fun people(): List<MediaPerson> = read("people", emptyList()) { value ->
            val array = value as? JSONArray ?: error("Expected array")
            (0 until array.length()).map { index ->
                val person = array.getJSONObject(index)
                fun optional(key: String): String? = person.get(key).let { if (it === JSONObject.NULL) null else text(it) }
                MediaPerson(optional("id"), text(person.get("name")), optional("role"), optional("type"), optional("imageTag"))
            }
        }
    }
}
