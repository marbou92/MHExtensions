package eu.kanade.tachiyomi.extension.all.manhuarmtl

import eu.kanade.tachiyomi.multisrc.madara.GenreRoute
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonPrimitive

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

/**
 * v39 lenient OCR payload parser.
 *
 * The gate response has been seen as a bare array of page objects and as a
 * `{data|pages: [...]}` envelope; text entries as `{box|clean_box, text}`,
 * as `[[x,y,w,h], text-or-map]` arrays, and as coordinate objects
 * `{x, y, width, height, angle, ...}`. Field names drift between the site
 * owner's obfuscation rounds, so the parser matches by SHAPE: image-ish
 * string keys, box-ish arrays/objects, text-ish strings — not one fixed
 * schema. This is what the WebView harvester (OcrHarvest) and the direct
 * gate path both feed into.
 *
 * Returns null when [body] is not an OCR payload at all (challenge page,
 * random JSON) and an empty list for a legitimate "nothing here" payload.
 */
fun parseOcrPayload(body: String): List<OcrPage>? {
    val trimmed = body.trim()
    if (trimmed.length < 10) return null
    if (trimmed[0] != '[' && trimmed[0] != '{') return null

    val root = try {
        PAYLOAD_JSON.parseToJsonElement(trimmed)
    } catch (_: Exception) {
        return null
    }

    val pagesArray: JsonArray? = when (root) {
        is JsonArray -> root
        is JsonObject -> root["data"] as? JsonArray ?: root["pages"] as? JsonArray
        else -> null
    }
    if (pagesArray == null) return null

    return pagesArray.mapNotNull { el -> (el as? JsonObject)?.toOcrPage() }
}

private val PAYLOAD_JSON = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

private fun JsonObject.toOcrPage(): OcrPage? {
    val image = firstStringOf("image", "img", "file", "filename", "image_url", "imageurl", "url", "src")
    val textsEl = this["texts"] ?: this["dialogues"] ?: this["dialogs"] ?: this["boxes"] ?: this["lines"]
    val boxes = mutableListOf<OcrText>()

    if (textsEl is JsonArray) {
        for (t in textsEl) {
            when (t) {
                is JsonObject -> {
                    val box = coordinateQuad(t) ?: (t["clean_box"] as? JsonArray) ?: (t["box"] as? JsonArray)
                    val text = objectText(t)
                    if (box != null && !text.isNullOrBlank()) {
                        boxes += OcrText(text = text, boxList = box)
                    }
                }

                is JsonArray -> {
                    // Legacy shape: [[x, y, w, h], "text"] (or a map in slot 1)
                    val box = t.firstOrNull() as? JsonArray
                    val second = t.getOrNull(1)
                    val text = (second as? JsonPrimitive)?.takeIf { it.isString }?.content
                        ?: second?.let { s -> (s as? JsonObject)?.let(::objectText) }
                    if (box != null && !text.isNullOrBlank()) {
                        boxes += OcrText(text = text, boxList = box)
                    }
                }

                else -> {}
            }
        }
    }

    if (image == null && boxes.isEmpty()) return null
    return OcrPage(image = image, texts = boxes)
}

/** Text of an object-shaped entry: explicit "text" key first, then any string value. */
private fun objectText(obj: JsonObject): String? {
    (obj["text"] as? JsonPrimitive)?.takeIf { it.isString }?.let { return it.content }
    return obj.entries
        .asSequence()
        .filter { it.value is JsonPrimitive && (it.value as JsonPrimitive).isString }
        .map { (it.value as JsonPrimitive).content }
        .firstOrNull { it.isNotBlank() }
}

/** `{x, y, width|w, height|h}` numeric object → box array, else null. */
private fun coordinateQuad(obj: JsonObject): JsonArray? {
    val x = obj["x"]?.jsonPrimitive?.floatOrNull ?: return null
    val y = obj["y"]?.jsonPrimitive?.floatOrNull ?: return null
    val w = (obj["width"] ?: obj["w"])?.jsonPrimitive?.floatOrNull ?: return null
    val h = (obj["height"] ?: obj["h"])?.jsonPrimitive?.floatOrNull ?: return null
    return JsonArray(listOf(JsonPrimitive(x), JsonPrimitive(y), JsonPrimitive(w), JsonPrimitive(h)))
}

private fun JsonObject.firstStringOf(vararg keys: String): String? {
    for (key in keys) {
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.let { return it.content }
    }
    return null
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

/**
 * Filter taxonomy payload cached by KeiSource between [ManhuaRMTL.getFilterList]
 * invocations. Genres and tags are separate taxonomies on the site; keeping
 * them apart is what keeps the Genres dialog small and fast.
 */
@Serializable
internal class FilterTaxonomyDto(
    val genres: List<GenreRoute> = emptyList(),
    val tags: List<GenreRoute> = emptyList(),
)
