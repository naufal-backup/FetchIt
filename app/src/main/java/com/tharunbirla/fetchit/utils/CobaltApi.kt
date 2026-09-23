package com.tharunbirla.fetchit.utils

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Cobalt API ( instances komunitas, format baru ).
 * Tiap instance dicoba singkat; yang gagal dilewati.
 * Response sukses: {"status":"tunnel"|"redirect","url":"..."}.
 */
object CobaltApi {
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val instances = listOf(
        "https://cobalt.canine.tools/",
        "https://co.eepy.today/",
        "https://cobalt-api.meower.xyz/"
    )

    fun resolve(videoUrl: String): String? {
        val body = JSONObject()
            .put("url", videoUrl)
            .put("videoQuality", "720")
            .toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        for (base in instances) {
            try {
                val req = Request.Builder()
                    .url(base)
                    .post(body)
                    .addHeader("Accept", "application/json")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 10)")
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val json = JSONObject(resp.body?.string().orEmpty())
                        val status = json.optString("status")
                        if (status == "tunnel" || status == "redirect") {
                            val direct = json.optString("url")
                            if (direct.isNotEmpty()) return direct
                        } else if (status.isNotEmpty()) {
                            Log.d("Cobalt", "$base -> $status")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d("Cobalt", "$base gagal: ${e.message}")
            }
        }
        return null
    }
}
