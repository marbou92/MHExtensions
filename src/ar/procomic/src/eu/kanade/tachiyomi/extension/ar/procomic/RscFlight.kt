package eu.kanade.tachiyomi.extension.ar.procomic

/**
 * Next.js ships page data as "flight" chunks inside inline scripts:
 * `self.__next_f.push([1,"<escaped payload>"])`. The payloads concatenate
 * into the full RSC stream. This extractor unescapes them with a linear
 * scan — a regex-based approach catastrophically backtracks on large
 * pages (hundreds of KB with Arabic \uXXXX escapes), so it must stay
 * index-based.
 */
object RscFlight {

    private const val MARKER = "self.__next_f.push([1,\""

    fun extract(html: String): String {
        val sb = StringBuilder(html.length / 2)
        var i = 0
        while (true) {
            i = html.indexOf(MARKER, i)
            if (i < 0) break
            i += MARKER.length
            while (i < html.length) {
                val c = html[i]
                if (c == '"') {
                    // an even run of backslashes before the quote means the
                    // string ends here; an odd run means the quote is escaped
                    var back = 0
                    var j = i - 1
                    while (j >= 0 && html[j] == '\\') {
                        back++
                        j--
                    }
                    if (back % 2 == 0) break
                }
                if (c == '\\' && i + 1 < html.length) {
                    appendEscape(sb, html, i)
                    i += 2
                    continue
                }
                sb.append(c)
                i++
            }
            i++
        }
        return sb.toString()
    }

    private fun appendEscape(sb: StringBuilder, html: String, i: Int) {
        when (val c = html[i + 1]) {
            '\\' -> sb.append('\\')

            '"' -> sb.append('"')

            '/' -> sb.append('/')

            'n' -> sb.append('\n')

            'r' -> sb.append('\r')

            't' -> sb.append('\t')

            'b' -> sb.append('\b')

            'f' -> sb.append('\u000C')

            'u' -> {
                val end = i + 6
                if (end <= html.length) {
                    val hex = html.substring(i + 2, end)
                    val code = hex.toIntOrNull(16)
                    if (code != null) {
                        sb.append(code.toChar())
                        return
                    }
                }
                sb.append(html, i, i + 2)
            }

            else -> {
                sb.append('\\')
                sb.append(c)
            }
        }
    }

    /**
     * Returns the balanced `{...}` object that starts at [start] (which must
     * point at the opening brace), or null when the braces never balance.
     */
    fun balancedObject(data: String, start: Int): String? {
        if (start < 0 || start >= data.length || data[start] != '{') return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until data.length) {
            val c = data[i]
            when {
                escaped -> escaped = false

                c == '\\' -> escaped = true

                c == '"' -> inString = !inString

                !inString && c == '{' -> depth++

                !inString && c == '}' -> {
                    depth--
                    if (depth == 0) return data.substring(start, i + 1)
                }
            }
        }
        return null
    }

    /** Same for a balanced `[...]` array starting at [start]. */
    fun balancedArray(data: String, start: Int): String? {
        if (start < 0 || start >= data.length || data[start] != '[') return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until data.length) {
            val c = data[i]
            when {
                escaped -> escaped = false

                c == '\\' -> escaped = true

                c == '"' -> inString = !inString

                !inString && c == '[' -> depth++

                !inString && c == ']' -> {
                    depth--
                    if (depth == 0) return data.substring(start, i + 1)
                }
            }
        }
        return null
    }
}
