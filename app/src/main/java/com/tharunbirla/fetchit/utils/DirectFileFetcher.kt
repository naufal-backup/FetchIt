package com.tharunbirla.fetchit.utils

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Fallback untuk URL yang langsung menunjuk ke file media
 * (mis. link .mp4 langsung, sebagian link TikTok/Reddit/CDN).
 * Cek via HEAD: kalau content-type video/audio → URL bisa diunduh langsung.
 */
object DirectFileFetcher {
    private val client = OkHttpClient()

    fun fetchDirectMediaUrl(pageUrl: String): String? {
        return try {
            val head = Request.Builder()
                .url(pageUrl)
                .head()
                .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 10)")
                .build()
            client.newCall(head).execute().use { resp ->
                val type = resp.header("Content-Type", "") ?: ""
                if (resp.isSuccessful && (type.startsWith("video/") || type.startsWith("audio/"))) {
                    return pageUrl
                }
            }
            // Sebagian server menolak HEAD — coba GET 1 byte
            val get = Request.Builder()
                .url(pageUrl)
                .addHeader("Range", "bytes=0-0")
                .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 10)")
                .build()
            client.newCall(get).execute().use { resp ->
                val type = resp.header("Content-Type", "") ?: ""
                if (resp.isSuccessful && (type.startsWith("video/") || type.startsWith("audio/"))) {
                    return pageUrl
                }
            }
            null
        } catch (e: Exception) {
            Log.e("DirectFile", "Error: ${e.message}", e)
            null
        }
    }
}
