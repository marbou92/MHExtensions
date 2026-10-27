package eu.kanade.tachiyomi.extension.ar.procomic

import java.nio.ByteBuffer

/**
 * Framework-only AVIF decoder (v108, demoted to DEBUG-ONLY in v109):
 * parses the ISOBMFF container in pure Kotlin and pushes the embedded AV1
 * OBU stream through MediaCodec's platform "video/av1" software decoder
 * (c2.android.av1 — shipped with Android 10).
 *
 * v109 FIELD VERDICT: this tier KILLED the host app on the Android 10
 * field device — configuring Android 10's brand-new software AV1 codec at
 * a still image's full size (pages 800x5000, "originals" covers to
 * 4000x6000) is a native-crash/OOM-kill surface no Java guard can catch,
 * and the app crashed on every page and cover load. The tier is REMOVED
 * from the decode ladder (see ProtectedPageServer.avifToJpeg — the WebView
 * tier replaces it) and survives only behind the debugMediaCodecTier hook
 * so the harness can keep pinning its decline behavior. The pure-Kotlin
 * parts remain LOAD-BEARING: parseAvifImage is the WebView tier's up-front
 * pixel-budget gate, and the NV21/pack pins keep guarding the container
 * logic.
 *
 * Container support (verified against live app.<host> files):
 *  - single-item AVIF ('av01' primary item): full support
 *  - grid-derived images ('grid' primary + dimg tiles): supported — tiles
 *    are decoded individually and composited row-major
 *  - 8-bit 4:2:0 output (the platform decoder's native output); exotic
 *    output formats bail gracefully into the next decode tier
 *
 * The parser and the YUV->NV21 converter are pure Kotlin (no Android
 * imports) so the JVM harness pins them with real bytes; only the MediaCodec
 * and YuvImage calls are framework surface, and every one of them is
 * guarded — any failure is reported through the log sink and returns null
 * so the caller falls through to the remaining decode tiers.
 */
internal object AvifMediaCodecDecoder {

    // ============================ data model ============================

    /** One decodable AV1 OBU sequence + the ispe dimensions of its item. */
    internal class AvifTile(val obu: ByteArray, val width: Int, val height: Int)

    /** The parsed image: one tile (simple AVIF) or the grid's tile list in
     * row-major dimg order. outputWidth/Height is the grid's declared
     * output (or the single tile's ispe). */
    internal class AvifLayout(
        val tiles: List<AvifTile>,
        val columns: Int,
        val rows: Int,
        val outputWidth: Int,
        val outputHeight: Int,
    )

    /** Decoupled plane view so the NV21 converter is JVM-testable without
     * android.media.Image. [buffer] is read with absolute indexing. */
    internal class PlaneData(val buffer: ByteBuffer?, val rowStride: Int, val pixelStride: Int)

    // ============================== entry ================================

    /**
     * Full AVIF -> JPEG pipeline. Returns the finished JPEG bytes or null
     * (every reason lands in [log]; the caller falls through to its other
     * decode tiers). Never throws.
     */
    fun decodeToJpeg(avif: ByteArray, log: (String) -> Unit, quality: Int): ByteArray? = try {
        val layout = parseAvifImage(avif) ?: run {
            log("container parse refused")
            return null
        }
        log("${layout.outputWidth}x${layout.outputHeight} tiles=${layout.tiles.size}")
        if (layout.tiles.isEmpty()) {
            log("no decodable items")
            null
        } else if (layout.tiles.size == 1) {
            val tile = layout.tiles[0]
            decodeObuToJpeg(tile.obu, tile.width, tile.height, quality, log)
        } else {
            composeGrid(layout, quality, log)
        }
    } catch (t: Throwable) {
        log("threw ${t.javaClass.simpleName}: ${t.message?.take(60)}")
        null
    }

    // ========================= ISOBMFF parsing ==========================

    /**
     * Parses a single- or grid-image AVIF file. Returns null when anything
     * needed for a MediaCodec decode is missing or unsupported:
     * non-AVIF brand, no primary item, no usable iloc, idat/item-relative
     * construction, a primary item without ispe, or empty extents.
     */
    internal fun parseAvifImage(data: ByteArray): AvifLayout? {
        if (data.size < 12) return null
        if (data[4] != 'f'.code.toByte() || data[5] != 't'.code.toByte() ||
            data[6] != 'y'.code.toByte() || data[7] != 'p'.code.toByte()
        ) {
            return null
        }
        val brand = String(data, 8, 4, Charsets.US_ASCII)
        if (!(brand.startsWith("av") || brand == "mif1" || brand == "miaf")) return null

        var primaryId = -1L
        val itemTypes = HashMap<Long, String>()
        val itemExtents = HashMap<Long, List<Pair<Long, Long>>>()
        val itemProps = HashMap<Long, List<Int>>()
        val dimgRefs = HashMap<Long, MutableList<Long>>()
        val properties = ArrayList<Triple<String, Int, Int>>() // (type, bodyStart, bodyEnd)

        // Top level: plain boxes; meta is itself a full box, so its children
        // begin 4 bytes into the meta body.
        walkBoxes(data, 0, data.size) { type, bodyStart, bodyEnd ->
            when (type) {
                "meta" -> walkBoxes(data, bodyStart + 4, bodyEnd) { child, start, end ->
                    when (child) {
                        "pitm" -> primaryId = parsePitm(data, start, end)
                        "iloc" -> parseIloc(data, start, end, itemExtents)
                        "iinf" -> parseIinf(data, start, end, itemTypes)
                        "iref" -> parseIref(data, start, end, dimgRefs)
                        // iprp sits INSIDE meta: ipco (the property pool)
                        // and ipma (item <-> property association).
                        "iprp" -> walkBoxes(data, start, end) { propContainer, pcStart, pcEnd ->
                            when (propContainer) {
                                "ipco" -> walkBoxes(data, pcStart, pcEnd) { prop, propStart, propEnd ->
                                    properties.add(Triple(prop, propStart, propEnd))
                                }
                                "ipma" -> parseIpma(data, pcStart, pcEnd, itemProps)
                            }
                        }
                    }
                }
            }
        }

        if (primaryId < 0) return null

        fun ispeOf(itemId: Long): Pair<Int, Int>? {
            val props = itemProps[itemId] ?: return null
            for (index in props) {
                val prop = properties.getOrNull(index - 1) ?: continue
                if (prop.first == "ispe" && prop.second + 8 <= prop.third) {
                    val width = readU32(data, prop.second + 4).toInt()
                    val height = readU32(data, prop.second + 8).toInt()
                    if (width > 0 && height > 0) return width to height
                }
            }
            return null
        }

        // iloc construction_method 0 = offsets are ABSOLUTE file offsets.
        fun itemBytes(itemId: Long): ByteArray? {
            val extents = itemExtents[itemId] ?: return null
            var total = 0L
            for ((_, length) in extents) total += length
            if (total <= 0L || total > MAX_ITEM_BYTES) return null
            val out = ByteArray(total.toInt())
            var filled = 0
            for ((offset, length) in extents) {
                if (length <= 0L) continue
                if (offset < 0 || offset + length > data.size) return null
                System.arraycopy(data, offset.toInt(), out, filled, length.toInt())
                filled += length.toInt()
            }
            if (filled == 0) return null
            return out
        }

        val primaryType = itemTypes[primaryId]
        val primaryDims = ispeOf(primaryId)
        val dimgChildren = dimgRefs[primaryId].orEmpty().filter { it != primaryId }

        return if (primaryType == "grid" || (primaryDims == null && dimgChildren.isNotEmpty())) {
            parseGridImage(data, primaryId, dimgChildren, ::itemBytes, ::ispeOf, properties)
        } else {
            // Lenient fallback: quirky writers associate properties through
            // non-standard channels — if the whole file carries exactly one
            // ispe, that is the image's size.
            val dims = primaryDims
                ?: (
                    properties.singleOrNull()?.takeIf { it.first == "ispe" }?.let {
                        val width = readU32(data, it.second + 4).toInt()
                        val height = readU32(data, it.second + 8).toInt()
                        if (width > 0 && height > 0) width to height else null
                    }
                    )
                ?: return null
            val payload = itemBytes(primaryId) ?: return null
            if (dimgChildren.isNotEmpty()) return null // av01 with children is not a shape we decode
            AvifLayout(listOf(AvifTile(payload, dims.first, dims.second)), 1, 1, dims.first, dims.second)
        }
    }

    /** 'grid' construction: the primary item carries a small grid header
     * (rows/columns/output size) and dimg-references the tile items in
     * row-major order. Tiles are located from their OWN iloc extents and
     * sized from their own ispe properties. */
    private fun parseGridImage(
        data: ByteArray,
        gridId: Long,
        tileIds: List<Long>,
        itemBytes: (Long) -> ByteArray?,
        ispeOf: (Long) -> Pair<Int, Int>?,
        properties: List<Triple<String, Int, Int>>,
    ): AvifLayout? {
        val gridPayload = itemBytes(gridId) ?: return null
        if (gridPayload.size < 8) return null
        val version = gridPayload[0].toInt() and 0xFF
        val flags = gridPayload[1].toInt() and 0xFF
        val rows = (gridPayload[2].toInt() and 0xFF) + 1
        val columns = (gridPayload[3].toInt() and 0xFF) + 1
        val wide = version == 0 && (flags and 1) == 0
        var cursor = 4
        fun dimension(): Int = if (wide) {
            val v = readU32(gridPayload, cursor).toInt()
            cursor += 4
            v
        } else {
            val v = readU16(gridPayload, cursor)
            cursor += 2
            v
        }
        val outputWidth = dimension()
        val outputHeight = dimension()
        if (rows <= 0 || columns <= 0 || outputWidth <= 0 || outputHeight <= 0) return null
        if (tileIds.size < rows * columns) return null

        val tiles = ArrayList<AvifTile>(rows * columns)
        for (tileId in tileIds.take(rows * columns)) {
            val dims = ispeOf(tileId)
                ?: (
                    properties.singleOrNull()?.takeIf { it.first == "ispe" }?.let {
                        val width = readU32(data, it.second + 4).toInt()
                        val height = readU32(data, it.second + 8).toInt()
                        if (width > 0 && height > 0) width to height else null
                    }
                    )
                ?: return null
            val payload = itemBytes(tileId) ?: return null
            tiles.add(AvifTile(payload, dims.first, dims.second))
        }
        return AvifLayout(tiles, columns, rows, outputWidth, outputHeight)
    }

    /** Plain-box walker: [block] receives the box type plus the body bounds
     * (right after the size+type header — full-box version/flags are NOT
     * stripped; parsers read them where they need them). */
    private inline fun walkBoxes(
        data: ByteArray,
        start: Int,
        end: Int,
        block: (type: String, bodyStart: Int, bodyEnd: Int) -> Unit,
    ) {
        var pos = start
        while (pos + 8 <= end) {
            var size = readU32(data, pos).toInt()
            var header = 8
            if (size == 1) {
                if (pos + 16 > end) return
                size = readU64(data, pos + 8)
                header = 16
            } else if (size == 0) {
                size = end - pos
            }
            if (size < header || pos + size > end) return
            val type = readType(data, pos + 4)
            val bodyStart = pos + header
            block(type, bodyStart.coerceAtMost(end), pos + size)
            pos += size
        }
    }

    private fun parsePitm(data: ByteArray, start: Int, end: Int): Long {
        if (start >= end) return -1L
        val version = data[start].toInt() and 0xFF
        return if (version == 0) readU16(data, start + 4).toLong() else readU32(data, start + 4)
    }

    private fun parseIloc(
        data: ByteArray,
        start: Int,
        end: Int,
        out: HashMap<Long, List<Pair<Long, Long>>>,
    ) {
        if (start + 6 > end) return
        val version = data[start].toInt() and 0xFF
        val offsetSize = (data[start + 4].toInt() shr 4) and 0xF
        val lengthSize = data[start + 4].toInt() and 0xF
        val baseOffsetSize = (data[start + 5].toInt() shr 4) and 0xF
        val indexSize = if (version == 1 || version == 2) data[start + 5].toInt() and 0xF else 0
        var pos = start + 6
        val count = if (version < 2) {
            val v = readU16(data, pos)
            pos += 2
            v
        } else {
            val v = readU32(data, pos)
            pos += 4
            v.toInt()
        }
        for (i in 0 until count) {
            if (pos >= end) return
            val itemId: Long
            if (version < 2) {
                itemId = readU16(data, pos).toLong()
                pos += 2
            } else {
                itemId = readU32(data, pos)
                pos += 4
            }
            var construction = 0
            if (version >= 1) {
                construction = readU16(data, pos) and 0xF
                pos += 2
            }
            pos += 2 // data_reference_index
            val baseOffset = readIntSized(data, pos, baseOffsetSize)
            pos += baseOffsetSize
            if (pos + 2 > end) return
            val extentCount = readU16(data, pos)
            pos += 2
            val extents = ArrayList<Pair<Long, Long>>(extentCount.coerceAtMost(8))
            for (e in 0 until extentCount) {
                if (indexSize > 0) pos += indexSize
                if (pos + offsetSize + lengthSize > end) return
                val offset = readIntSized(data, pos, offsetSize)
                pos += offsetSize
                val length = readIntSized(data, pos, lengthSize)
                pos += lengthSize
                extents.add((baseOffset + offset) to length)
            }
            if (construction == 0) out[itemId] = extents
        }
    }

    private fun parseIinf(
        data: ByteArray,
        start: Int,
        end: Int,
        out: HashMap<Long, String>,
    ) {
        if (start + 2 > end) return
        val version = data[start].toInt() and 0xFF
        val count: Int
        var pos = start + 4
        if (version == 0) {
            count = readU16(data, pos)
            pos += 2
        } else {
            count = readU32(data, pos).toInt()
            pos += 4
        }
        var seen = 0
        walkBoxes(data, pos, end) { _, boxStart, boxEnd ->
            // infe full box: version decides the item id width; item_type
            // follows the 2-byte protection index (infe v2+).
            if (boxStart + 6 <= boxEnd && seen < count) {
                val infeVersion = data[boxStart].toInt() and 0xFF
                val id: Long
                val typeOffset: Int
                if (infeVersion >= 3) {
                    id = readU32(data, boxStart + 4)
                    typeOffset = boxStart + 4 + 4 + 2
                } else if (infeVersion == 2) {
                    id = readU16(data, boxStart + 4).toLong()
                    typeOffset = boxStart + 4 + 2 + 2
                } else {
                    return@walkBoxes
                }
                if (typeOffset + 4 <= boxEnd) {
                    out[id] = readType(data, typeOffset)
                }
                seen++
            }
        }
    }

    private fun parseIref(
        data: ByteArray,
        start: Int,
        end: Int,
        out: HashMap<Long, MutableList<Long>>,
    ) {
        if (start + 6 > end) return
        val version = data[start].toInt() and 0xFF
        var pos = start + 4
        val count = readU16(data, pos)
        pos += 2
        val wide = version >= 1
        for (r in 0 until count) {
            if (pos >= end) return
            val from = if (wide) readU32(data, pos) else readU16(data, pos).toLong()
            pos += if (wide) 4 else 2
            if (pos + 2 > end) return
            val refCount = readU16(data, pos)
            pos += 2
            val targets = out.getOrPut(from) { ArrayList() }
            repeat(refCount) {
                if (pos >= end) return
                val to = if (wide) readU32(data, pos) else readU16(data, pos).toLong()
                pos += if (wide) 4 else 2
                targets.add(to)
            }
        }
    }

    private fun parseIpma(
        data: ByteArray,
        start: Int,
        end: Int,
        out: HashMap<Long, List<Int>>,
    ) {
        if (start + 8 > end) return
        val version = data[start].toInt() and 0xFF
        val flags = ((data[start + 1].toInt() and 0xFF) shl 16) or ((data[start + 2].toInt() and 0xFF) shl 8) or (data[start + 3].toInt() and 0xFF)
        val wideIds = version >= 1
        val wideAssoc = (flags and 1) != 0
        var pos = start + 4
        val count = readU32(data, pos).toInt()
        pos += 4
        for (i in 0 until count) {
            if (pos >= end) return
            val itemId = if (wideIds) {
                val v = readU32(data, pos)
                pos += 4
                v
            } else {
                val v = readU16(data, pos).toLong()
                pos += 2
                v
            }
            if (pos >= end) return
            val assocCount: Int
            if (wideAssoc) {
                assocCount = readU16(data, pos)
                pos += 2
            } else {
                assocCount = data[pos].toInt() and 0xFF
                pos += 1
            }
            val indexes = ArrayList<Int>(assocCount.coerceAtMost(64))
            for (a in 0 until assocCount) {
                if (pos >= end) return
                if (wideAssoc) {
                    indexes.add(readU16(data, pos) and 0x7FFF)
                    pos += 2
                } else {
                    indexes.add((data[pos].toInt() and 0xFF) and 0x7F)
                    pos += 1
                }
            }
            out[itemId] = indexes
        }
    }

    // ======================== byte primitives ===========================

    private fun readU16(data: ByteArray, pos: Int): Int = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)

    private fun readU32(data: ByteArray, pos: Int): Long = ((data[pos].toLong() and 0xFF) shl 24) or ((data[pos + 1].toLong() and 0xFF) shl 16) or
        ((data[pos + 2].toLong() and 0xFF) shl 8) or (data[pos + 3].toLong() and 0xFF)

    private fun readU64(data: ByteArray, pos: Int): Int {
        val high = readU32(data, pos)
        if (high != 0L) return Int.MAX_VALUE
        return readU32(data, pos + 4).toInt()
    }

    /** Big-endian integer of [size] bytes (0/1/2/4/8 — the iloc field widths). */
    private fun readIntSized(data: ByteArray, pos: Int, size: Int): Long = when (size) {
        0 -> 0L
        1 -> (data[pos].toLong() and 0xFF)
        2 -> readU16(data, pos).toLong()
        4 -> readU32(data, pos)
        8 -> {
            val high = readU32(data, pos)
            if (high != 0L) Long.MAX_VALUE else readU32(data, pos + 4)
        }
        else -> 0L
    }

    private fun readType(data: ByteArray, pos: Int): String = String(data, pos, 4, Charsets.US_ASCII)

    // ====================== MediaCodec decode tier ======================

    /** Decodes ONE AV1 OBU sequence to JPEG via the platform codec. */
    private fun decodeObuToJpeg(obu: ByteArray, width: Int, height: Int, quality: Int, log: (String) -> Unit): ByteArray? {
        val codec = try {
            android.media.MediaCodec.createDecoderByType(AV1_MIME)
        } catch (t: Throwable) {
            log("no av1 codec (${t.javaClass.simpleName})")
            return null
        }
        try {
            try {
                codec.configure(android.media.MediaFormat.createVideoFormat(AV1_MIME, width, height), null, null, 0)
            } catch (t: Throwable) {
                log("configure refused (${t.javaClass.simpleName})")
                return null
            }
            codec.start()
            val deadline = System.currentTimeMillis() + DECODE_DEADLINE_MS

            var queued = false
            while (!queued && System.currentTimeMillis() < deadline) {
                val inputIndex = codec.dequeueInputBuffer(QUEUE_TIMEOUT_MS)
                if (inputIndex < 0) continue
                val buffer = codec.getInputBuffer(inputIndex) ?: break
                buffer.clear()
                if (buffer.capacity() < obu.size) {
                    log("input buffer too small (${buffer.capacity()} < ${obu.size})")
                    return null
                }
                buffer.put(obu)
                codec.queueInputBuffer(inputIndex, 0, obu.size, 0L, KEY_FRAME_FLAG)
                queued = true
            }
            if (!queued) {
                log("input queue timeout")
                return null
            }

            val info = android.media.MediaCodec.BufferInfo()
            while (System.currentTimeMillis() < deadline) {
                val outputIndex = codec.dequeueOutputBuffer(info, QUEUE_TIMEOUT_MS)
                when {
                    outputIndex == INFO_TRY_AGAIN -> continue
                    outputIndex == INFO_FORMAT_CHANGED -> continue
                    outputIndex == INFO_BUFFERS_CHANGED -> continue
                    outputIndex < 0 -> {
                        log("unexpected dequeue $outputIndex")
                        return null
                    }
                    else -> {
                        val image = try {
                            codec.getOutputImage(outputIndex)
                        } catch (t: Throwable) {
                            log("output image refused (${t.javaClass.simpleName})")
                            null
                        } ?: run {
                            codec.releaseOutputBuffer(outputIndex, false)
                            continue
                        }
                        try {
                            if (image.format != YUV_420_888) {
                                log("unexpected image format ${image.format}")
                                return null
                            }
                            val w = image.width
                            val h = image.height
                            if (w <= 0 || h <= 0 || (w and 1) != 0 || (h and 1) != 0) {
                                log("invalid image size ${w}x$h")
                                return null
                            }
                            val planes = image.planes
                            val nv21 = nv21FromPlanes(
                                PlaneData(planes[0].buffer, planes[0].rowStride, planes[0].pixelStride),
                                PlaneData(planes[1].buffer, planes[1].rowStride, planes[1].pixelStride),
                                PlaneData(planes[2].buffer, planes[2].rowStride, planes[2].pixelStride),
                                w,
                                h,
                            ) ?: run {
                                log("nv21 conversion failed")
                                return null
                            }
                            return nv21ToJpeg(nv21, w, h, quality, log)
                        } finally {
                            try {
                                image.close()
                            } catch (_: Throwable) {}
                            codec.releaseOutputBuffer(outputIndex, false)
                        }
                    }
                }
            }
            log("decode deadline exceeded")
            return null
        } catch (t: Throwable) {
            log("codec threw ${t.javaClass.simpleName}")
            return null
        } finally {
            try {
                codec.stop()
            } catch (_: Throwable) {}
            try {
                codec.release()
            } catch (_: Throwable) {}
        }
    }

    /** Grid decode: every tile through [decodeObuToJpeg], composited
     * row-major into the declared output size, encoded once. */
    private fun composeGrid(layout: AvifLayout, quality: Int, log: (String) -> Unit): ByteArray? {
        if (layout.outputWidth.toLong() * layout.outputHeight > MAX_COMPOSE_PIXELS) {
            log("grid ${layout.outputWidth}x${layout.outputHeight} exceeds the compose budget")
            return null
        }
        val canvasBitmap = try {
            android.graphics.Bitmap.createBitmap(layout.outputWidth, layout.outputHeight, android.graphics.Bitmap.Config.ARGB_8888)
        } catch (t: Throwable) {
            log("compose alloc failed (${t.javaClass.simpleName})")
            return null
        }
        try {
            val canvas = android.graphics.Canvas(canvasBitmap)
            for (row in 0 until layout.rows) {
                for (column in 0 until layout.columns) {
                    val tile = layout.tiles.getOrNull(row * layout.columns + column) ?: continue
                    val tileJpeg = decodeObuToJpeg(tile.obu, tile.width, tile.height, TILE_JPEG_QUALITY, log) ?: return null
                    val tileBitmap = android.graphics.BitmapFactory.decodeByteArray(tileJpeg, 0, tileJpeg.size)
                        ?: run {
                            log("tile jpeg decode null")
                            return null
                        }
                    canvas.drawBitmap(tileBitmap, (column * tile.width).toFloat(), (row * tile.height).toFloat(), null)
                    tileBitmap.recycle()
                }
            }
            val output = java.io.ByteArrayOutputStream(layout.outputWidth * layout.outputHeight / 4)
            if (!canvasBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, output)) {
                log("compose encode false")
                return null
            }
            return output.toByteArray()
        } finally {
            canvasBitmap.recycle()
        }
    }

    // ========================= YUV -> NV21 ==============================

    /**
     * Packs a YUV_420_888 frame into the tightly-packed NV21 layout
     * YuvImage requires. Handles both planar (pixelStride 1) and
     * semi-planar (pixelStride 2) decoder outputs; bulk-copies whole rows
     * whenever the strides allow it (Image plane buffers are DIRECT — no
     * array() access). Returns null on shape mismatch.
     */
    internal fun nv21FromPlanes(y: PlaneData, u: PlaneData, v: PlaneData, width: Int, height: Int): ByteArray? {
        if (width <= 0 || height <= 0 || (width and 1) != 0 || (height and 1) != 0) return null
        val chromaWidth = width / 2
        val chromaHeight = height / 2

        // Exact minimum capacity each plane must cover (the last sample is
        // at (rows-1)*rowStride + (cols-1)*pixelStride) — never reject a
        // plane for being tighter than rows*rowStride.
        fun PlaneData.minCapacity(rows: Int, cols: Int): Long = (rows - 1).toLong() * rowStride + (cols - 1).toLong() * pixelStride + 1
        val yBuffer = y.buffer?.takeIf { it.capacity() >= y.minCapacity(height, width) }?.apply { position(0) } ?: return null
        val uBuffer = u.buffer?.takeIf { it.capacity() >= u.minCapacity(chromaHeight, chromaWidth) }?.apply { position(0) } ?: return null
        val vBuffer = v.buffer?.takeIf { it.capacity() >= v.minCapacity(chromaHeight, chromaWidth) }?.apply { position(0) } ?: return null

        val ySize = width * height
        val nv21 = ByteArray(ySize + 2 * chromaWidth * chromaHeight)

        // Y plane — bulk per-row get when every row fits contiguously.
        val yRowsContiguous = y.pixelStride == 1 &&
            yBuffer.capacity() >= (height - 1).toLong() * y.rowStride + width
        val rowCopy = ByteArray(width)
        for (row in 0 until height) {
            val target = row * width
            if (yRowsContiguous) {
                yBuffer.position(row * y.rowStride)
                yBuffer.get(rowCopy, 0, width)
                System.arraycopy(rowCopy, 0, nv21, target, width)
            } else {
                for (column in 0 until width) {
                    nv21[target + column] = yBuffer.get(row * y.rowStride + column * y.pixelStride)
                }
            }
        }

        // Chroma planes, interleaved as V,U per NV21.
        for (row in 0 until chromaHeight) {
            val target = ySize + row * chromaWidth * 2
            for (column in 0 until chromaWidth) {
                nv21[target + column * 2] = vBuffer.get(row * v.rowStride + column * v.pixelStride)
                nv21[target + column * 2 + 1] = uBuffer.get(row * u.rowStride + column * u.pixelStride)
            }
        }
        return nv21
    }

    /** NV21 -> JPEG through YuvImage (the framework's own encoder). */
    private fun nv21ToJpeg(nv21: ByteArray, width: Int, height: Int, quality: Int, log: (String) -> Unit): ByteArray? = try {
        val yuv = android.graphics.YuvImage(nv21, NV21_FORMAT, width, height, null)
        val output = java.io.ByteArrayOutputStream(nv21.size / 2)
        if (!yuv.compressToJpeg(android.graphics.Rect(0, 0, width, height), quality, output)) {
            log("yuv jpeg encode false")
            null
        } else {
            output.toByteArray()
        }
    } catch (t: Throwable) {
        log("yuv encode ${t.javaClass.simpleName}")
        null
    }

    // ========================= constants ===============================

    /** The platform AV1 codec mime (MediaFormat.MIMETYPE_VIDEO_AV1). */
    private const val AV1_MIME = "video/av1"

    /** ImageFormat.NV21. */
    private const val NV21_FORMAT = 17

    /** ImageFormat.YUV_420_888 — the only output layout this tier converts. */
    private const val YUV_420_888 = 35

    /** MediaCodec.BUFFER_FLAG_KEY_FRAME. */
    private const val KEY_FRAME_FLAG = 1

    private const val INFO_TRY_AGAIN = -1
    private const val INFO_FORMAT_CHANGED = -2
    private const val INFO_BUFFERS_CHANGED = -3

    private const val QUEUE_TIMEOUT_MS = 10_000L

    /** Whole-image decode deadline: a tall page is a heavy software AV1
     * decode, but if the codec has not emitted anything in a minute the
     * device is wedged — fall through to the next tier instead. */
    private const val DECODE_DEADLINE_MS = 60_000L

    /** Grid tiles are re-encoded into the composed bitmap — encode them
     * lossless-ish so the final JPEG quality governs the bytes. */
    private const val TILE_JPEG_QUALITY = 100

    /** Guard against absurd single items (a hostile/malformed mdat). */
    private const val MAX_ITEM_BYTES = 64L * 1024 * 1024

    /** Composited grid ceiling (ARGB_8888): 50 MP ~ 200 MB — above this
     * the tier refuses rather than OOM-ing the host app. */
    private const val MAX_COMPOSE_PIXELS = 50_000_000L
}
