package eu.kanade.tachiyomi.extension.all.manhuarmtl

import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Ported from keiyoushi's own `manhuarm` source (same site, manhuarmtl.com):
 * when a request comes back with an HTTP error status and we haven't warmed
 * up yet, make one GET to the base URL and then retry the original request.
 *
 * The warm-up request gives the app-level `CloudflareInterceptor` (which
 * KeiSource runs AFTER source interceptors) a chance to mint `cf_clearance`
 * through the shared WebView cookie jar; the retry then rides the clearance
 * like any other request. No extension-level WebView solving, no custom
 * fingerprint headers — exactly the network behaviour of the keiyoushi
 * extension users report as "instant".
 */
class CloudflareWarmupInterceptor(
    private val baseUrl: String,
    private val headers: Headers,
) : Interceptor {

    private val isWarmedUp = AtomicBoolean(false)

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)

        if (!response.isSuccessful && !isWarmedUp.get()) {
            response.close()

            try {
                val warmupRequest = GET(baseUrl, headers)
                val warmupResponse = chain.proceed(warmupRequest)
                warmupResponse.close()
                isWarmedUp.set(true)
            } catch (_: Exception) {
            }

            return chain.proceed(request)
        }

        return response
    }

    fun reset() {
        isWarmedUp.set(false)
    }
}
