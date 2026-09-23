package com.tharunbirla.fetchit.utils

import android.util.Log
import okhttp3.Headers.Companion.toHeaders
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import org.json.JSONObject

object FacebookUrlFetcher {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val headers = mapOf(
        "sec-fetch-user" to "?1",
        "sec-ch-ua-mobile" to "?0",
        "sec-fetch-site" to "none",
        "sec-fetch-dest" to "document",
        "sec-fetch-mode" to "navigate",
        "cache-control" to "max-age=0",
        "upgrade-insecure-requests" to "1",
        "accept-language" to "en-US,en;q=0.9",
        "sec-ch-ua" to "\"Chromium\";v=\"120\", \"Not_A Brand\";v=\"24\"",
        "user-agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36",
        "accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
    ).toHeaders()

    fun fetchFacebookVideoUrl(videoUrl: String): String? {
        // Coba beberapa varian host: www (asli), m, dan mbasic (sering tanpa login)
        val candidates = linkedSetOf(videoUrl)
        try {
            val withHost = { host: String ->
                videoUrl.replaceFirst(
                    Regex("https?://(www|m|mbasic)\\.facebook\\.com"),
                    "https://$host.facebook.com"
                )
            }
            candidates.add(withHost("m"))
            candidates.add(withHost("mbasic"))
        } catch (_: Exception) { }

        var lastError: String? = null
        for (url in candidates) {
            try {
                fetchFromPage(url)?.let { return it }
            } catch (e: Exception) {
                lastError = e.message
                Log.d("Facebook", "varian gagal ($url): ${e.message}")
            }
        }
        Log.e("Facebook", "Semua varian gagal. Terakhir: $lastError")
        return null
    }

    private fun fetchFromPage(pageUrl: String): String? {
        val request = Request.Builder()
            .url(pageUrl)
            .headers(headers)
            .build()

        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                val body = response.body?.string()
                if (body.isNullOrEmpty()) {
                    Log.e("Facebook", "Empty response body")
                    return null
                }
                return parseVideoDetailsFromHtml(body)
            } else {
                Log.e("Facebook", "Error: HTTP ${response.code}, Message: ${response.message}")
                return null
            }
        }
    }

    private fun parseVideoDetailsFromHtml(html: String): String? {
        // Urutan: HD native → playable HD → og:video → SD native → playable → redirect
        getHDLink(html)?.let { return it }
        getPlayableHdLink(html)?.let { return it }
        getOgVideo(html)?.let { return it }
        getSDLink(html)?.let { return it }
        getPlayableLink(html)?.let { return it }
        return getRedirectLink(html)
    }

    private fun extractTitle(html: String): String {
        val titleRegex = """<title>(.*?)</title>""".toRegex()
        return titleRegex.find(html)?.groups?.get(1)?.value?.trim() ?: "Unknown Title"
    }

    private fun getSDLink(html: String): String? {
        val regex = """"browser_native_sd_url":"([^"]+)"""".toRegex()
        return regex.find(html)?.groups?.get(1)?.value?.let { cleanStr(it) }
    }

    private fun getHDLink(html: String): String? {
        val regex = """"browser_native_hd_url":"([^"]+)"""".toRegex()
        return regex.find(html)?.groups?.get(1)?.value?.let { cleanStr(it) }
    }

    private fun getPlayableHdLink(html: String): String? {
        val regex = """"playable_url_quality_hd":"([^"]+)"""".toRegex()
        return regex.find(html)?.groups?.get(1)?.value?.let { cleanStr(it) }
    }

    private fun getPlayableLink(html: String): String? {
        val regex = """"playable_url":"([^"]+)"""".toRegex()
        return regex.find(html)?.groups?.get(1)?.value?.let { cleanStr(it) }
    }

    private fun getOgVideo(html: String): String? {
        val regex = """<meta[^>]+property="og:video(?::url)?"[^>]+content="([^"]+)"""".toRegex()
        return regex.find(html)?.groups?.get(1)?.value
            ?: """<meta[^>]+content="([^"]+)"[^>]+property="og:video(?::url)?"""".toRegex()
                .find(html)?.groups?.get(1)?.value
    }

    /** Link /video_redirect/?src=... ala mbasic — decode URL-nya. */
    private fun getRedirectLink(html: String): String? {
        val regex = """/video_redirect/\?src=([^"&]+)""".toRegex()
        val raw = regex.find(html)?.groups?.get(1)?.value ?: return null
        return try {
            java.net.URLDecoder.decode(raw, "UTF-8")
        } catch (_: Exception) {
            raw
        }
    }

    private fun cleanStr(str: String): String {
        return "{\"text\": \"$str\"}".let { json ->
            val jsonObject = JSONObject(json)
            jsonObject.getString("text")
        }
    }
}
