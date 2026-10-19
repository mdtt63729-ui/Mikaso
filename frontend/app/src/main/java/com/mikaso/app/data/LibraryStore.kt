package com.mikaso.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** The "Downloaded" list: finished files, newest first. */
class LibraryStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<LibraryItem>> = _items.asStateFlow()

    fun add(item: LibraryItem) {
        _items.value = listOf(item) + _items.value
        save()
    }

    fun remove(id: String) {
        _items.value = _items.value.filterNot { it.id == id }
        save()
    }

    fun removeAll(ids: Collection<String>) {
        val set = ids.toSet()
        _items.value = _items.value.filterNot { it.id in set }
        save()
    }

    fun clear() {
        _items.value = emptyList()
        save()
    }

    private fun save() {
        val array = JSONArray()
        _items.value.forEach { array.put(toJson(it)) }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    private fun load(): List<LibraryItem> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i -> array.optJSONObject(i)?.let { fromJson(it) } }
        }.getOrDefault(emptyList())
    }

    private fun toJson(i: LibraryItem): JSONObject = JSONObject()
        .put("id", i.id)
        .put("kind", i.kind.name)
        .put("platform", i.platform.name)
        .put("title", i.title)
        .put("filename", i.filename)
        .put("uri", i.uri)
        .put("mime", i.mime)
        .put("size", i.size)
        .put("createdAt", i.createdAt)
        .put("uploader", i.uploader ?: JSONObject.NULL)
        .put("thumbnail", i.thumbnail ?: JSONObject.NULL)
        .put("label", i.label)

    private fun fromJson(o: JSONObject): LibraryItem = LibraryItem(
        id = o.optString("id"),
        kind = runCatching { MediaKind.valueOf(o.optString("kind", MediaKind.Video.name)) }.getOrDefault(MediaKind.Video),
        platform = Platform.fromId(o.optString("platform")) ?: Platform.YouTube,
        title = o.optString("title", "Untitled"),
        filename = o.optString("filename", ""),
        uri = o.optString("uri", ""),
        mime = o.optString("mime", "application/octet-stream"),
        size = o.optLong("size", 0),
        createdAt = o.optLong("createdAt", 0),
        uploader = o.optString("uploader", "").takeIf { it.isNotBlank() && it != "null" },
        thumbnail = o.optString("thumbnail", "").takeIf { it.isNotBlank() && it != "null" },
        label = o.optString("label", ""),
    )

    private companion object {
        const val PREFS = "mikaso_library"
        const val KEY = "items"
    }
}
