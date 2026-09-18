package eu.kanade.tachiyomi.extension.all.manhuarmtl

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class OcrResponse(
    val success: Boolean? = null,
    val data: List<OcrPage>? = null,
) {
    /**
     * The OCR endpoint may return either a bare array `[...]` or
     * an envelope `{"success":true,"data":[...]}`. This helper
     * normalises both shapes to a flat list of pages.
     */
    fun pages(): List<OcrPage> = data ?: emptyList()
}

@Serializable
data class OcrPage(
    val image: String? = null,
    val texts: List<OcrText>? = null,
    @SerialName("text") val singleText: String? = null,
    @SerialName("box") val boxList: List<JsonElement>? = null,
) {
    /**
     * Normalise the various shapes the OCR response can take:
     *  - `{"image":"split_000.webp","texts":[{"box":[x,y,w,h],"clean_box":[x,y,w,h],"text":"..."}]}`
     *  - `{"image":"split_000.webp","text":"...","box":[x,y,w,h]}`
     *
     * `clean_box` (tighter text region) is preferred when present,
     * falling back to the raw `box`.
     */
    fun normalisedTexts(): List<OcrTextBox> {
        val result = mutableListOf<OcrTextBox>()
        texts?.forEach { t ->
            val box = t.cleanBox ?: t.boxList
            val text = t.text ?: ""
            if (box != null && text.isNotBlank()) {
                result.add(OcrTextBox(parseBox(box), extractEnglish(text)))
            }
        }
        if (result.isEmpty() && singleText != null && boxList != null) {
            result.add(OcrTextBox(parseBox(boxList), extractEnglish(singleText)))
        }
        return result.filter { it.text.isNotBlank() }
    }

    private fun parseBox(box: List<JsonElement>): FloatArray {
        val vals = box.mapNotNull { it.toString().trim('"', ' ').toFloatOrNull() }
        return when (vals.size) {
            4 -> floatArrayOf(vals[0], vals[1], vals[2], vals[3])
            2 -> floatArrayOf(0f, 0f, vals[0], vals[1]) // w,h only
            else -> floatArrayOf(0f, 0f, 0f, 0f)
        }
    }
}

@Serializable
data class OcrText(
    val text: String? = null,
    @SerialName("box") val boxList: List<JsonElement>? = null,
    @SerialName("clean_box") val cleanBox: List<JsonElement>? = null,
)

data class OcrTextBox(
    val box: FloatArray, // [x, y, w, h]
    val text: String,
)

/**
 * Groups per-line OCR boxes into paragraph blocks the way the site's overlay
 * renders them: consecutive lines that sit close together and overlap
 * horizontally become ONE overlay block (union box, texts joined), so the
 * burned text wraps naturally like a paragraph instead of appearing as
 * fragmented single lines.
 */
fun groupIntoParagraphs(boxes: List<OcrTextBox>): List<OcrTextBox> {
    if (boxes.size <= 1) return boxes

    // Reading order: top-to-bottom, then left-to-right.
    val sorted = boxes.sortedWith(compareBy({ it.box.getOrElse(1) { 0f } }, { it.box.getOrElse(0) { 0f } }))

    val paragraphs = mutableListOf<ParagraphAccumulator>()
    for (box in sorted) {
        val target = paragraphs.lastOrNull { it.canAbsorb(box) }
        if (target != null) {
            target.absorb(box)
        } else {
            paragraphs.add(ParagraphAccumulator(box))
        }
    }
    return paragraphs.map { it.toTextBox() }
}

/**
 * Mutable paragraph builder. Lines are absorbed while they are vertically
 * adjacent (gap smaller than ~70% of the shorter line height) and horizontally
 * overlapping (at least 30% of the narrower box) — the geometric footprint of
 * lines belonging to the same text block. Different columns/balloons don't
 * overlap and therefore stay separate blocks.
 */
private class ParagraphAccumulator(first: OcrTextBox) {
    var left: Float = first.box.getOrElse(0) { 0f }
    var top: Float = first.box.getOrElse(1) { 0f }
    var right: Float = left + first.box.getOrElse(2) { 0f }
    var bottom: Float = top + first.box.getOrElse(3) { 0f }
    var lastHeight: Float = first.box.getOrElse(3) { 0f }
    private val texts = mutableListOf(first.text)

    val width: Float get() = right - left
    val height: Float get() = bottom - top

    fun canAbsorb(box: OcrTextBox): Boolean {
        val bx = box.box.getOrElse(0) { 0f }
        val by = box.box.getOrElse(1) { 0f }
        val bw = box.box.getOrElse(2) { 0f }
        val bh = box.box.getOrElse(3) { 0f }

        // Vertical proximity: the new line must start at/near the block's
        // bottom (within 70% of the shorter of the two line heights).
        val gap = by - bottom
        val tolerance = 0.7f * minOf(bh, lastHeight) + 2f
        if (gap > tolerance || gap < -bh) return false // below block, or way above (out of order)

        // Horizontal overlap of at least 30% of the narrower box.
        val overlap = minOf(right, bx + bw) - maxOf(left, bx)
        return overlap >= 0.3f * minOf(width, bw)
    }

    fun absorb(box: OcrTextBox) {
        val bx = box.box.getOrElse(0) { 0f }
        val by = box.box.getOrElse(1) { 0f }
        val bw = box.box.getOrElse(2) { 0f }
        val bh = box.box.getOrElse(3) { 0f }

        left = minOf(left, bx)
        top = minOf(top, by)
        right = maxOf(right, bx + bw)
        bottom = maxOf(bottom, by + bh)
        lastHeight = bh
        texts.add(box.text)
    }

    fun toTextBox(): OcrTextBox = OcrTextBox(
        floatArrayOf(left, top, width, height),
        texts.joinToString(" ") { it.trim() }.trim(),
    )
}

data class OcrCredentials(
    val cid: String,
    val token: String,
    val timestamp: Long,
    val nonce: String,
    val gateUrl: String,
    val ref: String,
)

/**
 * Legacy cleanup: older chapters embedded the translation inline as
 * "<original text> [ENGLISH]: <english text>". Strip the marker and
 * known watermark strings so the pure English text remains.
 */
fun extractEnglish(raw: String): String {
    val markers = listOf("[ENGLISH]:", "[DRAFT_ENGLISH]:", "[ENGLISH]", "[DRAFT_ENGLISH]")
    var result = raw
    for (marker in markers) {
        val idx = result.indexOf(marker)
        if (idx >= 0) {
            result = result.substring(idx + marker.length).trim()
            break
        }
    }

    // Strip watermarks
    val watermarks = listOf("jiyun data", "sumanku", "tencent", "jiyun", "suman")
    for (wm in watermarks) {
        result = result.replace(wm, "", ignoreCase = true)
    }

    return result.trim()
}
