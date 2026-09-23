package com.tharunbirla.fetchit.utils

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class HistoryEntry(
    val name: String,
    val platform: String,
    val date: String,
    val success: Boolean
)

/** Riwayat unduhan ringan berbasis SharedPreferences (max 20). */
object DownloadHistory {
    private const val PREFS = "fetchit_history"
    private const val KEY = "entries"
    private const val MAX = 20

    fun add(context: Context, entry: HistoryEntry) {
        val list = list(context).toMutableList()
        list.add(0, entry)
        val arr = JSONArray()
        list.take(MAX).forEach {
            arr.put(JSONObject()
                .put("name", it.name)
                .put("platform", it.platform)
                .put("date", it.date)
                .put("success", it.success))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }

    fun list(context: Context): List<HistoryEntry> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                HistoryEntry(
                    o.optString("name"),
                    o.optString("platform", "-"),
                    o.optString("date", ""),
                    o.optBoolean("success", false)
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY).apply()
    }

    fun now(): String =
        SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()).format(Date())
}
