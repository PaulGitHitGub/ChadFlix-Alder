package dev.alder

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

internal data class WatchTitle(val id: String, val type: String, val name: String, val poster: String?, val background: String?) {
    fun matches(other: WatchTitle) = id == other.id && type == other.type

    fun json(): JSONObject = JSONObject().put("id", id).put("type", type).put("name", name)
        .put("poster", poster ?: JSONObject.NULL).put("background", background ?: JSONObject.NULL)
}

internal data class Watchlist(val name: String, val items: List<WatchTitle>)

internal class Watchlists(private val prefs: SharedPreferences, private val key: String) {
    fun all(): List<Watchlist> = runCatching {
        JSONArray(prefs.getString(key, "[]")).objects().map { list ->
            Watchlist(list.getString("name"), list.getJSONArray("items").objects().map {
                WatchTitle(it.getString("id"), it.getString("type"), it.getString("name"), it.text("poster"), it.text("background"))
            })
        }
    }.getOrDefault(emptyList())

    private fun save(lists: List<Watchlist>) {
        val json = JSONArray(lists.map {
            JSONObject().put("name", it.name).put("items", JSONArray(it.items.map(WatchTitle::json)))
        })
        prefs.edit().putString(key, json.toString()).apply()
    }

    fun create(input: String, first: WatchTitle? = null): Boolean {
        val name = input.trim()
        val lists = all()
        if (name.isEmpty() || lists.any { it.name.equals(name, true) }) return false
        save(lists + Watchlist(name, listOfNotNull(first)))
        return true
    }

    fun rename(old: String, input: String): Boolean {
        val name = input.trim()
        val lists = all()
        if (name.isEmpty() || lists.none { it.name == old } || lists.any { it.name != old && it.name.equals(name, true) }) return false
        save(lists.map { if (it.name == old) it.copy(name = name) else it })
        return true
    }

    fun delete(name: String) = save(all().filterNot { it.name == name })

    fun toggle(name: String, title: WatchTitle) = save(all().map { list ->
        if (list.name != name) list
        else list.copy(items = if (list.items.any(title::matches)) list.items.filterNot(title::matches) else list.items + title)
    })
}
