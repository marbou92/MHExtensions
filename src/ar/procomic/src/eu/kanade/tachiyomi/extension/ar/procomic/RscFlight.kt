package eu.kanade.tachiyomi.extension.ar.procomic

/**
 * RscFlight — the Next.js SSR flight extractor (reconstructed for
 * v1.6.56, behavior-faithful to the shipped v1.6.55 build).
 *
 * Next.js App Router pages ship their server payload as a chain of
 * `self.__next_f.push([1,"…"])` script chunks — each chunk is a
 * JSON-escaped string fragment of ONE concatenated RSC payload.
 * [extract] walks every marker, unescapes each fragment, and returns
 * the joined flight string; [balancedObject] / [balancedArray] then
 * carve a balanced {…} / […] out of it at a caller-found index
 * (string-aware: braces inside string literals never count).
 */
internal object RscFlight {

    private const val FLIGHT_MARKER = "self.__next_f.push([1,\""

    /** One balanced `[…]` starting exactly at [start]. */
    fun balancedArray(s: String, start: Int): String? {
        if (start < 0 || start >= s.length || s[start] != '[') return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until s.length) {
            val c = s[i]
            when {
                escaped -> escaped = false
                c == '\\' -> escaped = true
                c == '"' -> inString = !inString
                !inString && c == '[' -> depth++
                !inString && c == ']' -> {
                    depth--
                    if (depth == 0) return s.substring(start, i + 1)
                }
            }
        }
        return null
    }

    /** One balanced `{…}` starting exactly at [start]. */
    fun balancedObject(s: String, start: Int): String? {
        if (start < 0 || start >= s.length || s[start] != '{') return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until s.length) {
            val c = s[i]
            when {
                escaped -> escaped = false
                c == '\\' -> escaped = true
                c == '"' -> inString = !inString
                !inString && c == '{' -> depth++
                !inString && c == '}' -> {
                    depth--
                    if (depth == 0) return s.substring(start, i + 1)
                }
            }
        }
        return null
    }

    /** Join every `self.__next_f.push([1,"…")` string fragment into the
     * one flight payload (JSON escapes resolved). */
    fun extract(html: String): String {
        val sb = StringBuilder(html.length / 2)
        var cursor = 0
        while (true) {
            val markerAt = html.indexOf(FLIGHT_MARKER, cursor)
            if (markerAt < 0) return sb.toString()
            var i = markerAt + FLIGHT_MARKER.length
            while (i < html.length) {
                val c = html[i]
                if (c == '"') {
                    // A quote only terminates the fragment when no
                    // backslash run precedes it (JSON string grammar).
                    var backslashes = 0
                    var j = i - 1
                    while (j >= 0 && html[j] == '\\') {
                        backslashes++
                        j--
                    }
                    if (backslashes % 2 == 0) break
                }
                if (c != '\\' || i + 1 >= html.length) {
                    sb.append(c)
                    i++
                } else {
                    val next = html[i + 1]
                    when (next) {
                        '"' -> sb.append('"')
                        '/' -> sb.append('/')
                        '\\' -> sb.append('\\')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            val end = i + 6
                            val code = if (end > html.length) null else html.substring(i + 2, end).toIntOrNull(16)
                            if (code == null) {
                                sb.append(html, i, i + 2)
                            } else {
                                sb.append(code.toChar())
                            }
                        }
                        else -> {
                            sb.append('\\')
                            sb.append(next)
                        }
                    }
                    i += 2
                }
            }
            cursor = i + 1
        }
    }
}
