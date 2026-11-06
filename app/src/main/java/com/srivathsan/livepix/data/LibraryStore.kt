package com.srivathsan.livepix.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** A user-made album. Android has no albums of its own, only folders, so these live in the app. */
data class UserAlbum(val id: String, val name: String, val created: Long, val keys: List<String>)

/**
 * Albums, hidden items, and adjusted dates and locations, saved as JSON in app storage. Adjustments
 * live here rather than in the files, so originals stay untouched and can be reverted to.
 */
class LibraryStore(context: Context) {
    private val file = File(context.filesDir, "library.json")

    var albums: List<UserAlbum> = emptyList(); private set
    var hidden: Set<String> = emptySet(); private set
    var dates: Map<String, Long> = emptyMap(); private set
    /** A null point means the user removed the location. */
    var locations: Map<String, GeoPoint?> = emptyMap(); private set

    init { load() }

    fun createAlbum(name: String, keys: List<String> = emptyList()): UserAlbum {
        val album = UserAlbum(UUID.randomUUID().toString(), name.trim(), System.currentTimeMillis(), keys.distinct())
        albums = albums + album
        save()
        return album
    }

    fun addToAlbum(id: String, keys: List<String>) = updateAlbum(id) { it.copy(keys = (it.keys + keys).distinct()) }
    fun removeFromAlbum(id: String, keys: Collection<String>) = updateAlbum(id) { it.copy(keys = it.keys - keys.toSet()) }
    fun renameAlbum(id: String, name: String) = updateAlbum(id) { it.copy(name = name.trim()) }

    fun deleteAlbum(id: String) {
        albums = albums.filterNot { it.id == id }
        save()
    }

    fun setHidden(keys: Collection<String>, hide: Boolean) {
        hidden = if (hide) hidden + keys else hidden - keys.toSet()
        save()
    }

    fun setDates(next: Map<String, Long>) { dates = dates + next; save() }
    fun clearDates(keys: Collection<String>) { dates = dates - keys.toSet(); save() }
    fun setLocation(keys: Collection<String>, point: GeoPoint?) { locations = locations + keys.associateWith { point }; save() }
    fun clearLocation(keys: Collection<String>) { locations = locations - keys.toSet(); save() }

    private fun updateAlbum(id: String, f: (UserAlbum) -> UserAlbum) {
        albums = albums.map { if (it.id == id) f(it) else it }
        save()
    }

    private fun save() {
        val json = JSONObject().apply {
            put("albums", JSONArray().apply {
                albums.forEach { a ->
                    put(JSONObject().apply {
                        put("id", a.id); put("name", a.name); put("created", a.created)
                        put("keys", JSONArray(a.keys))
                    })
                }
            })
            put("hidden", JSONArray(hidden.toList()))
            put("dates", JSONObject().apply { dates.forEach { (k, v) -> put(k, v) } })
            put("locations", JSONObject().apply {
                locations.forEach { (k, p) -> put(k, JSONArray().apply { if (p != null) { put(p.lat); put(p.lng) } }) }
            })
        }
        runCatching { file.writeText(json.toString()) }
    }

    private fun load() {
        val json = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return
        albums = json.optJSONArray("albums")?.let { arr ->
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val k = o.optJSONArray("keys") ?: JSONArray()
                UserAlbum(o.getString("id"), o.getString("name"), o.optLong("created"), (0 until k.length()).map { k.getString(it) })
            }
        }.orEmpty()
        hidden = json.optJSONArray("hidden")?.let { arr -> (0 until arr.length()).map { arr.getString(it) }.toSet() }.orEmpty()
        dates = json.optJSONObject("dates")?.let { o -> o.keys().asSequence().associateWith { o.getLong(it) } }.orEmpty()
        locations = json.optJSONObject("locations")?.let { o ->
            o.keys().asSequence().associateWith { k ->
                o.getJSONArray(k).takeIf { it.length() == 2 }?.let { GeoPoint(it.getDouble(0), it.getDouble(1)) }
            }
        }.orEmpty()
    }
}
