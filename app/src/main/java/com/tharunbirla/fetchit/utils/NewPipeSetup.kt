package com.tharunbirla.fetchit.utils

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.localization.Localization
import java.util.concurrent.TimeUnit

/** Init NewPipeExtractor sekali + OkHttp downloader. */
object NewPipeSetup {
    @Volatile private var ready = false

    fun ensure() {
        if (ready) return
        synchronized(this) {
            if (ready) return
            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
            NewPipe.init(object : Downloader() {
                override fun execute(request: Request): Response {
                    val body = request.dataToSend()?.let {
                        it.toRequestBody("application/octet-stream".toMediaTypeOrNull())
                    }
                    val builder = okhttp3.Request.Builder()
                        .url(request.url())
                        .method(request.httpMethod(), body)
                        .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 10)")
                    request.headers().forEach { (k, vs) ->
                        vs.forEach { builder.addHeader(k, it) }
                    }
                    client.newCall(builder.build()).execute().use { resp ->
                        return Response(
                            resp.code,
                            resp.message,
                            resp.headers.toMultimap(),
                            resp.body?.string(),
                            resp.request.url.toString()
                        )
                    }
                }
            }, Localization.DEFAULT)
            ready = true
        }
    }
}
