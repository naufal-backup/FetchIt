package com.tharunbirla.fetchit.utils

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

object YouTubeUrlFetcher {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun fetchYouTubeVideoUrl(videoUrl: String): String? {
        // 1) Cobalt (multi-instance)
        try {
            CobaltApi.resolve(videoUrl)?.let { return it }
        } catch (e: Exception) {
            Log.d("YouTube", "Cobalt gagal: ${e.message}")
        }
        // 2) Piped
        try {
            extractVideoId(videoUrl)?.let { id ->
                PipedApi.resolveStream(id)?.let { return it }
            }
        } catch (e: Exception) {
            Log.d("YouTube", "Piped gagal: ${e.message}")
        }
        // 3) Scrape player_response langsung
        return try {
            scrapePlayerResponse(videoUrl)
        } catch (e: Exception) {
            Log.e("YouTube", "Error: ${e.message}", e)
            null
        }
    }

    fun extractVideoId(url: String): String? {
        return try {
            val patterns = listOf(
                """[?&]v=([\w-]{11})""".toRegex(),
                """youtu\.be/([\w-]{11})""".toRegex(),
                """/shorts/([\w-]{11})""".toRegex(),
                """/embed/([\w-]{11})""".toRegex(),
                """/live/([\w-]{11})""".toRegex()
            )
            patterns.firstNotNullOfOrNull { it.find(url)?.groups?.get(1)?.value }
        } catch (_: Exception) {
            null
        }
    }

    /** Ambil progressive mp4 (itag 22/18) dari ytInitialPlayerResponse. */
    private fun scrapePlayerResponse(videoUrl: String): String? {
        val id = extractVideoId(videoUrl) ?: return null
        val req = Request.Builder()
            .url("https://www.youtube.com/watch?v=$id")
            .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36")
            .addHeader("Accept-Language", "en-US,en;q=0.9")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val html = resp.body?.string() ?: return null
            val marker = "ytInitialPlayerResponse = "
            val start = html.indexOf(marker)
            if (start < 0) return null
            var depth = 0
            var end = -1
            for (i in (start + marker.length) until html.length) {
                when (html[i]) {
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) { end = i + 1; break }
                    }
                }
                if (i - start > 2_000_000) break
            }
            if (end < 0) return null
            val player = JSONObject(html.substring(start + marker.length, end))
            val formats = player.optJSONObject("streamingData")?.optJSONArray("formats")
                ?: return null
            var fallback: String? = null
            for (i in 0 until formats.length()) {
                val f = formats.getJSONObject(i)
                val url = f.optString("url")
                if (url.isEmpty()) continue // butuh decipher signature → lewati
                val mime = f.optString("mimeType", "")
                if (!mime.contains("mp4")) continue
                val itag = f.optInt("itag", 0)
                if (itag == 22) return URLDecoder.decode(url, "UTF-8")
                if (fallback == null) fallback = URLDecoder.decode(url, "UTF-8")
            }
            return fallback
        }
    }
}
