package eu.kanade.tachiyomi.network.interceptor

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import kotlin.random.Random

/**
 * Retries idempotent requests that the server throttles (HTTP 429/503) with exponential backoff,
 * honouring a numeric Retry-After header. Some image hosts rate-limit at the account level, so a
 * single failed image is expected under load and succeeds shortly after.
 */
class RetryOnThrottleInterceptor(
    private val maxRetries: Int = 6,
    private val maxDelayMs: Long = 8_000,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        var response = chain.proceed(request)
        if (request.method != "GET" && request.method != "HEAD") return response

        var delayMs = 1_000L
        var attempt = 0
        while (response.code in THROTTLE_CODES && attempt < maxRetries) {
            val retryAfterMs = response.header("Retry-After")?.trim()?.toLongOrNull()?.times(1_000)
            val wait = (retryAfterMs ?: delayMs).coerceAtMost(maxDelayMs) + Random.nextLong(0, 400)
            response.close()
            try {
                Thread.sleep(wait)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("Interrupted while waiting to retry", e)
            }
            if (chain.call().isCanceled()) throw IOException("Canceled")
            response = chain.proceed(request)
            delayMs = (delayMs * 2).coerceAtMost(maxDelayMs)
            attempt++
        }
        return response
    }

    private companion object {
        val THROTTLE_CODES = setOf(429, 503)
    }
}
