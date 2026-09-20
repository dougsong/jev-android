package io.github.jevandroid

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Shared transport: no redirects, retries, response-body errors, or request logging. */
internal class ProviderTransport(
    private val calls: Call.Factory = client(),
    private val maxResponseBytes: Long = 1_048_576,
) {
    init { require(maxResponseBytes in 1..1_048_576) }

    suspend fun execute(request: Request, provider: String): String = suspendCancellableCoroutine { continuation ->
        val call = calls.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                // Do not propagate the original exception: it can contain request details.
                if (!continuation.isCancelled)
                    continuation.resumeWithException(IOException("$provider request failed"))
            }

            override fun onResponse(call: Call, response: Response) {
                var rejection = "$provider response rejected"
                try {
                    val value = response.use {
                        if (!it.isSuccessful) {
                            rejection = "$provider HTTP ${it.code}"
                            throw IOException()
                        }
                        val body = it.body ?: throw IOException()
                        if (body.contentLength() > maxResponseBytes) {
                            rejection = "$provider response too large"
                            throw IOException()
                        }
                        val source = body.source()
                        source.request(maxResponseBytes + 1)
                        if (source.buffer.size > maxResponseBytes) {
                            rejection = "$provider response too large"
                            throw IOException()
                        }
                        source.readUtf8()
                    }
                    if (!continuation.isCancelled) continuation.resume(value)
                } catch (_: Exception) {
                    // Keep even decompression/read/decoding errors free of response content.
                    if (!continuation.isCancelled)
                        continuation.resumeWithException(IOException(rejection))
                }
            }
        })
    }

    companion object {
        internal fun client(): OkHttpClient = OkHttpClient.Builder()
            .callTimeout(25, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build()
    }
}
