package eu.kanade.tachiyomi.extension.ar.procomic

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonString
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Serves ProComic's "protected" reader pages.
 *
 * The reader SSR exposes the first few pages as plain CDN URLs; the rest
 * arrive as encrypted reconstruction "maps". The site's own client decrypts
 * each map into a plan `{dim, mode, pieces, order, rects}` where every piece
 * is a short-lived `/i/{token}` image on the CDN, and draws the pieces onto a
 * canvas at their rects. The server decrypts on our behalf through
 * `POST /chapter-map-proxy-plan/{chapterId}` (the client-side session-key
 * path answers `use_proxy_plan`), so the flow here is:
 *
 *   getPageList -> deferred-media (map tokens, cached in memory)
 *               -> synthetic `/.protected-page/{chapterId}/{index}` URLs
 *   interceptor -> proxy-plan POST (server-decrypted plan)
 *               -> download pieces -> stitch at rects -> JPEG response
 *
 * Piece images are AVIF: decoding requires Android 12 (API 31)+, matching
 * the site's own format choice.
 */
class ProtectedPageInterceptor(
    private val baseUrl: String,
    private val mapCache: MapCache,
    private val callFactoryProvider: () -> Call.Factory,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url
        if (url.host != baseUrl.toHttpUrl().host ||
            !url.encodedPath.startsWith(PROTECTED_PATH_PREFIX)
        ) {
            return chain.proceed(request)
        }

        val segments = url.pathSegments
        // /.protected-page/{chapterId}/{pageIndex}
        val chapterId = segments.getOrNull(1) ?: throw IOException("ProComic: malformed page url")
        val pageIndex = segments.getOrNull(2)?.toIntOrNull() ?: throw IOException("ProComic: malformed page url")
        val entry = mapCache.get(chapterId, pageIndex)
            ?: throw IOException("ProComic: page plan expired, reopen the chapter")

        val client = callFactoryProvider().let { it as? OkHttpClient }
            ?: throw IOException("ProComic: client unavailable")

        val plan = fetchPlan(client, chapterId, entry)
        val body = stitchToJpeg(client, plan)
            ?: throw IOException("ProComic: could not assemble page (Android 12+ required for AVIF pages)")

        return Response.Builder()
            .request(request)
            .protocol(okhttp3.Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(body)
            .build()
    }

    private fun fetchPlan(
        client: OkHttpClient,
        chapterId: String,
        entry: MapCache.Entry,
    ): PlanDto {
        val payload = PlanRequestDto(
            token = entry.token,
            method = entry.method,
            cdnPath = entry.cdnPath,
            pageIndex = entry.pageIndex,
        ).toJsonString().toRequestBody(JSON_MEDIA_TYPE)

        val request = Request.Builder()
            .url("$baseUrl/chapter-map-proxy-plan/$chapterId")
            .post(payload)
            .header("Accept", "application/json")
            .header("Referer", "$baseUrl/")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("ProComic: page plan failed with HTTP ${response.code}")
            }
            val body = response.body.string()
            return body.parseAs<PlanResponseDto>().data?.map
                ?: throw IOException("ProComic: empty page plan")
        }
    }

    private fun stitchToJpeg(client: OkHttpClient, plan: PlanDto): okhttp3.ResponseBody? {
        val dimW = plan.dim.getOrNull(0)?.takeIf { it > 0 } ?: return null
        val dimH = plan.dim.getOrNull(1)?.takeIf { it > 0 } ?: return null

        val rects = plan.rects
            ?.takeIf { it.size == plan.order.size }
            ?: computeGridRects(plan.mode, dimW, dimH, plan.order.size)
            ?: return null

        val result = Bitmap.createBitmap(dimW, dimH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawColor(Color.WHITE)

        val pieceUrls = plan.order.mapNotNull { plan.pieces.getOrNull(it) }
        if (pieceUrls.isEmpty()) return null

        try {
            pieceUrls.forEachIndexed { index, pieceUrl ->
                val rect = rects.getOrNull(index) ?: return@forEachIndexed
                val piece = decodePiece(client, pieceUrl)
                    ?: throw IOException("ProComic: piece $index failed to decode")
                piece.density = Bitmap.DENSITY_NONE
                canvas.drawBitmap(
                    piece,
                    null,
                    android.graphics.RectF(
                        rect.left.toFloat(),
                        rect.top.toFloat(),
                        (rect.left + rect.width).toFloat(),
                        (rect.top + rect.height).toFloat(),
                    ),
                    null,
                )
                if (piece !== result) piece.recycle()
            }

            val output = ByteArrayOutputStream()
            result.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
            val bytes = output.toByteArray()
            return bytes.toResponseBody("image/jpeg".toMediaType())
        } finally {
            if (!result.isRecycled) result.recycle()
        }
    }

    private fun decodePiece(client: OkHttpClient, pieceUrl: String): Bitmap? {
        val request = Request.Builder()
            .url(pieceUrl)
            .header("Accept", "image/avif,image/webp,image/*,*/*;q=0.8")
            .header("Referer", "$baseUrl/")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("ProComic: piece download failed with HTTP ${response.code}")
            }
            val bytes = response.body.bytes()
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
    }

    /**
     * mode "grid_{cols}x{rows}" with the site's floor-and-spread remainder
     * distribution (matches the reader's own computation).
     */
    private fun computeGridRects(mode: String, dimW: Int, dimH: Int, count: Int): List<PlanRectDto>? {
        val parts = mode.split("_").getOrNull(1)?.split("x") ?: return null
        val cols = parts.getOrNull(0)?.toIntOrNull()?.takeIf { it > 0 } ?: return null
        val rows = parts.getOrNull(1)?.toIntOrNull()?.takeIf { it > 0 } ?: return null

        val cellW = dimW / cols
        val cellH = dimH / rows
        val remW = dimW - cellW * cols
        val remH = dimH - cellH * rows
        val widths = List(cols) { i -> cellW + if (i < remW) 1 else 0 }
        val heights = List(rows) { i -> cellH + if (i < remH) 1 else 0 }

        val rects = mutableListOf<PlanRectDto>()
        var y = 0
        for (r in 0 until rows) {
            var x = 0
            for (c in 0 until cols) {
                rects.add(PlanRectDto(left = x, top = y, width = widths[c], height = heights[r]))
                x += widths[c]
            }
            y += heights[r]
        }
        return if (count in 1..rects.size) rects.take(count) else rects
    }

    @kotlinx.serialization.Serializable
    private class PlanRequestDto(
        val token: String,
        val method: String?,
        val cdnPath: String?,
        val pageIndex: Int,
    )

    companion object {
        const val PROTECTED_PATH_PREFIX = "/.protected-page/"
        const val JPEG_QUALITY = 88

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

/**
 * In-memory store for the deferred-media map tokens fetched while building
 * the page list. Keys are chapter ids; entries preserve the deferred order so
 * `pageIndex` indexes directly into the list. App process death clears the
 * cache — Mihon re-runs getPageList and repopulates it.
 */
class MapCache {
    class Entry(
        val token: String,
        val method: String?,
        val cdnPath: String?,
        val pageIndex: Int,
    )

    private val cache = HashMap<String, List<Entry>>()
    private val lock = Any()

    fun putAll(chapterId: String, maps: List<Entry>) {
        synchronized(lock) {
            if (cache.size >= MAX_CHAPTERS) {
                val iter = cache.entries.iterator()
                repeat(MAX_CHAPTERS / 4) {
                    if (iter.hasNext()) {
                        iter.next()
                        iter.remove()
                    }
                }
            }
            cache[chapterId] = maps
        }
    }

    fun get(chapterId: String, pageIndex: Int): Entry? = synchronized(lock) {
        cache[chapterId]?.getOrNull(pageIndex)
    }

    private companion object {
        const val MAX_CHAPTERS = 24
    }
}
