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
                val vals = parseBox(box)
                // v53: the wire parser can't know which space the gate speaks
                // (it never sets normalized), so a wire box that LOOKS like a
                // fraction (all four values within ±1% of [0,1]) is treated
                // as one. A real PIXEL box can't satisfy that — text smaller
                // than 1.01px is impossible — and the renderer scales
                // fraction boxes by the downloaded bitmap, so a wrong guess
                // used to burn the text as a squashed strip at the left edge.
                val normalized = t.normalized || boxLooksFractional(vals)
                result.add(OcrTextBox(vals, extractEnglish(text), normalized))
            }
        }
        if (result.isEmpty() && singleText != null && boxList != null) {
            val vals = parseBox(boxList)
            result.add(OcrTextBox(vals, extractEnglish(singleText), boxLooksFractional(vals)))
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
    /**
     * v45: true when the coordinates are FRACTIONS of the page image
     * (0.0–1.0 relative to its width/height) instead of absolute pixels —
     * the DOM harvest emits these because the WebView's loaded image
     * variant can differ in resolution from the file the app downloads.
     * The renderer scales normalized boxes by the real bitmap dimensions.
     */
    val normalized: Boolean = false,
)

data class OcrTextBox(
    val box: FloatArray, // [x, y, w, h] — pixels, or fractions when normalized = true
    val text: String,
    val normalized: Boolean = false,
)

/**
 * v53: a box whose four values all sit in [0,1] is in FRACTION space.
 * Pixel boxes can't look like this (no real text is under ~1px wide), so
 * the false-positive risk is nil; the false-negative (fractions burned as
 * pixels) drew text as a squashed vertical strip at the left edge.
 */
fun boxLooksFractional(vals: FloatArray): Boolean = vals.size == 4 && vals.all { it >= -0.01f && it <= 1.01f }

/**
 * v54: the DOM sweep records every text element — a container div holding
 * several line spans qualifies BESIDE its children (its innerText equals its
 * textContent, so the sweep takes the whole wrapper as one box). A wrapper
 * spanning most of the page then burns its CONCATENATED text as a giant slab:
 * the font collapses, the layout clamps against the image edge ("squashed to
 * the left"), and the same lines burn a second time in their real bubbles
 * ("not in their bubble").
 *
 * This prune drops a box when its text is fully represented by tighter boxes
 * GEOMETRICALLY CONTAINED in it. The tight boxes stay; the wrapper goes. Real
 * bubbles never contain other bubbles, so the false-positive risk is nil.
 * Comparisons happen in each box's own space (a fraction box is never
 * contained in a pixel box or vice versa).
 *
 * v55: field logs showed side-by-side bubbles STILL combining. The v54 drop
 * condition demanded an EXACT text match after joining the children in
 * reading order — three real drifts defeat that: the site's text order can
 * run right-to-left (manga), adjacent inline spans fuse without spaces
 * ("Text AText B"), and sweep filters can skip one child entirely. The drop
 * condition is now CHARACTER-SET COVERAGE: a wrapper is dropped when the
 * alphanumeric characters of its contained boxes cover ~all of the
 * wrapper's (case/punctuation/spacing/order-insensitive). Content is never
 * lost — the covered characters burn in the tighter boxes at their true
 * positions. Candidates are evaluated LARGEST FIRST so a wrapper is judged
 * while all of its children are still present.
 */
fun pruneContainerBoxes(boxes: List<OcrTextBox>): List<OcrTextBox> {
    if (boxes.size <= 1) return boxes

    fun rect(b: OcrTextBox): FloatArray = floatArrayOf(
        b.box.getOrElse(0) { 0f },
        b.box.getOrElse(1) { 0f },
        b.box.getOrElse(2) { 0f },
        b.box.getOrElse(3) { 0f },
    )

    fun area(r: FloatArray): Float = (r[2] - r[0]).coerceAtLeast(0f) * (r[3] - r[1]).coerceAtLeast(0f)

    fun contains(outer: FloatArray, inner: FloatArray): Boolean {
        val cx = (inner[0] + inner[2]) / 2f
        val cy = (inner[1] + inner[3]) / 2f
        return cx >= outer[0] && cx <= outer[2] && cy >= outer[1] && cy <= outer[3]
    }

    /** Alphanumeric character counts — case-, punctuation-, spacing-free. */
    fun charCounts(text: String): HashMap<Char, Int> {
        val counts = HashMap<Char, Int>()
        for (c in text.lowercase()) {
            if (c.isLetterOrDigit()) counts.merge(c, 1, Int::plus)
        }
        return counts
    }

    /** True when [pool] covers all but a sliver of the container's characters. */
    fun coveredBy(container: HashMap<Char, Int>, pool: HashMap<Char, Int>): Boolean {
        var need = 0
        var missing = 0
        for ((c, n) in container) {
            val have = pool[c] ?: 0
            if (have < n) missing += n - have
            need += n
        }
        if (need == 0) return pool.isNotEmpty()
        return missing <= need * (1f - MIN_COVER)
    }

    data class Cand(val index: Int, val rect: FloatArray, val area: Float)

    val cands = boxes.withIndex().map { (i, b) ->
        val r = rect(b)
        Cand(i, r, area(r))
    }
    val dropped = BooleanArray(boxes.size)
    var changed = false

    // Largest first: wrappers are judged while all of their children are intact.
    val ordered = cands.sortedByDescending { it.area }
    for (a in ordered) {
        if (dropped[a.index]) continue
        val boxA = boxes[a.index]
        // Tighter boxes of the SAME coordinate space whose center sits inside A.
        val inner = cands.filter { other ->
            other.index != a.index &&
                !dropped[other.index] &&
                other.area < a.area &&
                boxes[other.index].normalized == boxA.normalized &&
                contains(a.rect, other.rect)
        }
        if (inner.isEmpty()) continue
        val textA = charCounts(boxA.text)
        if (textA.isEmpty()) continue
        // Fast path: one child carrying the identical text → padded duplicate.
        if (inner.any { charCounts(boxes[it.index].text) == textA }) {
            dropped[a.index] = true
            changed = true
            continue
        }
        // Coverage path: the union of the children's characters represents A.
        val pool = HashMap<Char, Int>()
        for (cand in inner) {
            for ((c, n) in charCounts(boxes[cand.index].text)) {
                pool.merge(c, n, Int::plus)
            }
        }
        if (coveredBy(textA, pool)) {
            dropped[a.index] = true
            changed = true
        }
    }

    return if (changed) boxes.filterIndexed { i, _ -> !dropped[i] } else boxes
}

/** v55: fraction of a wrapper's characters its contained boxes must cover. */
private const val MIN_COVER = 0.95f

// ============================== v56: site-chrome / ad-box filter ==============================
//
// The DOM sweep reads EVERY text element near the page images — including the
// site's ad banners and app promos that float over the reader ("Ascent Browser
// FREE", "Read manga the clean way Android", "Opens the Google Play Store").
// Those boxes were stored like dialogue and burned onto the pages — English
// ad text even on foreign-language chapters, plus wasted translation quota.
//
// The filter is deliberately conservative (dialogue false-positives cost real
// bubbles): unambiguous phrases, literal UI labels, 3+ weak ad tokens, or the
// one safe token pair. The repeated-banner sweep in storeOcrBoxes catches the
// rest (the same text on 3+ different pages is chrome by definition).

/** Word tokens of a normalized string (letters/digits/plus kept). */
private val CHROME_TOKEN = Regex("""[a-z0-9+]+""")

/** Unambiguous ad/promo phrases — dialogue never contains these. */
private val CHROME_STRONG_PHRASES = listOf(
    "google play", "play store", "app store", "download now", "install now",
    "install the app", "open in app", "read manga", "read manhwa", "read free",
    "read comics", "sponsored", "advertisement", "click here", "sign up",
    "sign in", "log in", "login now", "register now", "join now", "join us",
    "follow us", "subscribe now", "turn on notifications", "enable notifications",
    "bet now", "free spins", "free coins", "jackpot", "casino bonus",
    "welcome bonus", "try your luck", "play now", "watch now", "stream free",
    "vpn", "telegram", "discord", "whatsapp", "18+", "21+", "adult content",
    "chapter notifications", "recommended for you", "for you page",
)

/** Weak ad tokens — three or more together is an ad headline, not dialogue. */
private val CHROME_WEAK_TOKENS = setOf(
    "free", "download", "install", "android", "browser", "app", "update",
    "premium", "official", "notify", "notifications", "bonus", "coins",
    "unlock", "unlimited", "offline", "apk", "ios", "chapters", "hd",
)

/** Whole-text matches — the box is literally site UI. */
private val CHROME_EXACT = setOf("ad", "ads", "close", "skip ad", "18+", "21+")

/** v56: true when [text] is site chrome (ad banner / app promo / UI label). */
fun isChromeBoxText(text: String): Boolean {
    val norm = text.lowercase().replace(Regex("\\s+"), " ").trim()
    if (norm.isEmpty()) return false
    if (norm in CHROME_EXACT) return true
    for (phrase in CHROME_STRONG_PHRASES) {
        if (norm.contains(phrase)) return true
    }
    val tokens = CHROME_TOKEN.findAll(norm).map { it.value }.toSet()
    if (tokens.isEmpty()) return false
    var weak = 0
    for (token in tokens) if (token in CHROME_WEAK_TOKENS) weak++
    if (weak >= 3) return true
    // "Ascent Browser FREE" — 'browser' is near-impossible in dialogue and
    // pairs with 'free' only in app promos.
    return "browser" in tokens && "free" in tokens
}

/** v56: order/punctuation-free key for the repeated-banner sweep. */
fun chromeRepetitionKey(text: String): String {
    val sb = StringBuilder(text.length)
    for (c in text.lowercase()) {
        if (c.isLetterOrDigit() || c.isWhitespace()) sb.append(c)
    }
    return sb.toString().trim().replace(Regex("\\s+"), " ")
}

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
    var tallest: Float = first.box.getOrElse(3) { 0f }

    // v53: the merged box lives in the SAME space as its lines. Dropping
    // this flag burned every paragraph as PIXELS — fraction coordinates
    // like x=0.35 became x=0.35px, and the text rendered as a squashed
    // vertical strip pinned to the left edge of the page.
    val normalized: Boolean = first.normalized
    private val texts = mutableListOf(first.text)

    val width: Float get() = right - left
    val height: Float get() = bottom - top

    fun canAbsorb(box: OcrTextBox): Boolean {
        // v53: never merge across coordinate spaces — a fraction line inside
        // a pixel paragraph (or vice versa) would poison the union box.
        if (box.normalized != normalized) return false
        val bx = box.box.getOrElse(0) { 0f }
        val by = box.box.getOrElse(1) { 0f }
        val bw = box.box.getOrElse(2) { 0f }
        val bh = box.box.getOrElse(3) { 0f }

        // Vertical proximity: the new line must start at/near the block's
        // bottom (within 70% of the shorter of the two line heights).
        // v54: the +2f slack was a PIXEL-space constant; in fraction space it
        // meant +200% OF THE PAGE, so every stacked bubble chain-merged into
        // page-sized slabs. The slack is now space-aware: 2px in pixel space,
        // 0.4% of the page in fraction space.
        val gap = by - bottom
        val slack = if (normalized) 0.004f else 2f
        val tolerance = 0.7f * minOf(bh, lastHeight) + slack
        if (gap > tolerance || gap < -bh) return false // below block, or way above (out of order)

        // v54 runaway cap: a real paragraph's height stays proportional to its
        // line count. If the union would grow past (lines absorbed + 1.5) ×
        // the tallest absorbed line (+slack), this is a different block.
        val tallestSoFar = maxOf(tallest, bh)
        val proposed = maxOf(bottom, by + bh) - minOf(top, by)
        val heightCap = (texts.size + 1.5f) * tallestSoFar + (if (normalized) 0.01f else 10f)
        if (proposed > heightCap) return false

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
        tallest = maxOf(tallest, bh)
        texts.add(box.text)
    }

    fun toTextBox(): OcrTextBox = OcrTextBox(
        floatArrayOf(left, top, width, height),
        texts.joinToString(" ") { it.trim() }.trim(),
        normalized,
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
