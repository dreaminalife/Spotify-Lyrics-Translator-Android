package com.dreaminalife.lyricstranslator.data.net

import com.dreaminalife.lyricstranslator.log.AppLog
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

const val USER_AGENT = "LyricsTranslator-Android/1.0"

/** Shared JSON configuration for network responses and cache files. */
val AppJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

fun createHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .addInterceptor { chain ->
        val request = chain.request()
        if (request.header("User-Agent") != null) {
            chain.proceed(request)
        } else {
            chain.proceed(request.newBuilder().header("User-Agent", USER_AGENT).build())
        }
    }
    // Logs every request's URL, status and timing. Headers (including Authorization) are never logged.
    .addInterceptor { chain ->
        val request = chain.request()
        val started = System.nanoTime()
        try {
            chain.proceed(request).also { response ->
                AppLog.d("HTTP", "${request.method} ${request.url} -> ${response.code} in ${elapsedMs(started)} ms")
            }
        } catch (e: IOException) {
            AppLog.w("HTTP", "${request.method} ${request.url} failed after ${elapsedMs(started)} ms: $e")
            throw e
        }
    }
    .build()

private fun elapsedMs(startedNanos: Long) = (System.nanoTime() - startedNanos) / 1_000_000

data class HttpResponse(val code: Int, val body: String) {
    val isSuccessful: Boolean get() = code in 200..299
}

class HttpException(val code: Int, message: String) : IOException(message)

/** Executes [request] without blocking the caller; cancelling the coroutine cancels the call. */
suspend fun OkHttpClient.send(request: Request): HttpResponse {
    val call = newCall(request)
    return suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use { HttpResponse(it.code, it.body.string()) }
                    continuation.resume(result)
                } catch (e: IOException) {
                    continuation.resumeWithException(e)
                }
            }
        })
    }
}
