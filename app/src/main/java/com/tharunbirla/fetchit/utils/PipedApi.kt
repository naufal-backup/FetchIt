package com.tharunbirla.fetchit.utils

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Piped API (frontend YouTube alternatif) sebagai fallback.
 * GET {instance}/streams/{videoId} → videoStreams[] berisi URL progresif.
 */
object PipedApi {
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val instances = listOf(
        "https://pipedapi.kavin.rocks",
        "https://pipedapi.reallyaweso.me",
        "https://pipedapi.leptons.xyz"
    )

    fun resolveStream(videoId: String): String? {
        for (base in instances) {
            try {
                val req = Request.Builder()
                    .url("$base/streams/$videoId")
                    .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 10)")
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val json = JSONObject(resp.body?.string().orEmpty())
                    val streams = json.optJSONArray("videoStreams") ?: return@use
                    var fallback: String? = null
                    for (i in 0 until streams.length()) {
                        val s = streams.getJSONObject(i)
                        val url = s.optString("url")
                        if (url.isEmpty() || !url.startsWith("http")) continue
                        val mime = s.optString("mimeType", "")
                        val videoOnly = s.optBoolean("videoOnly", false)
                        // Prioritas: mp4 progresif (ada audio)
                        if (!videoOnly && mime.contains("mp4")) return url
                        if (fallback == null && !videoOnly) fallback = url
                    }
                    if (fallback != null) return fallback
                }
            } catch (e: Exception) {
                Log.d("Piped", "$base gagal: ${e.message}")
            }
        }
        return null
    }
}
