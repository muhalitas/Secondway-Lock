package com.secondwaybrowser.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class HistoryEntry(
    val id: String,
    val url: String,
    val title: String,
    val timestampMs: Long
)

object HistoryStore {
    private const val PREFS_NAME = "history_prefs"
    private const val KEY_HISTORY = "history_items"
    private const val MAX_ITEMS = 1000

    fun addEntry(context: Context, url: String, title: String) {
        val trimmedUrl = url.trim()
        if (trimmedUrl.isEmpty()) return
        val now = System.currentTimeMillis()
        val list = getEntries(context).toMutableList()
        val last = list.firstOrNull()
        if (last != null && last.url == trimmedUrl && (now - last.timestampMs) < 3000) {
            return
        }
        val entry = HistoryEntry(
            id = UUID.randomUUID().toString(),
            url = trimmedUrl,
            title = title.trim(),
            timestampMs = now
        )
        list.add(0, entry)
        if (list.size > MAX_ITEMS) list.subList(MAX_ITEMS, list.size).clear()
        save(context, list)
    }

    fun getEntries(context: Context): List<HistoryEntry> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_HISTORY, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            val out = ArrayList<HistoryEntry>(arr.length())
            for (i in 0 until arr.length()) {
                val ob = arr.optJSONObject(i) ?: continue
                val id = ob.optString("id")
                val url = ob.optString("url")
                val title = ob.optString("title")
                val ts = ob.optLong("ts", 0L)
                if (id.isNotBlank() && url.isNotBlank() && ts > 0) {
                    out.add(HistoryEntry(id, url, title, ts))
                }
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun removeEntry(context: Context, id: String) {
        val list = getEntries(context).filterNot { it.id == id }
        save(context, list)
    }

    fun clearAll(context: Context) {
        save(context, emptyList())
    }

    fun clearSince(context: Context, cutoffMs: Long) {
        val list = getEntries(context).filter { it.timestampMs < cutoffMs }
        save(context, list)
    }

    private fun save(context: Context, list: List<HistoryEntry>) {
        val arr = JSONArray()
        for (item in list) {
            val ob = JSONObject()
            ob.put("id", item.id)
            ob.put("url", item.url)
            ob.put("title", item.title)
            ob.put("ts", item.timestampMs)
            arr.put(ob)
        }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_HISTORY, arr.toString()).apply()
    }
}
