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
