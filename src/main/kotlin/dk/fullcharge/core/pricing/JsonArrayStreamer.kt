package dk.fullcharge.core.pricing

import java.io.Reader

/**
 * Streams a large JSON document and hands over the raw text of each element of every array
 * stored under [arrayKey] (at any depth), one element at a time. AFIR feeds are tens of MB per
 * operator (15-min prices for every charge point), too big to hold as one String or tree on a
 * phone; one site element is small. Nested arrays with the same key inside a captured element
 * are part of that element (not emitted separately). Assumes valid JSON.
 */
object JsonArrayStreamer {
    fun forEachElement(reader: Reader, arrayKey: String, action: (String) -> Unit) {
        val buf = CharArray(64 * 1024)
        var inString = false
        var escape = false
        val lastString = StringBuilder()
        var lastKey: String? = null // the most recent string token followed by ':'
        var expectValueOf: String? = null
        var depth = 0
        // Capture state: depth of the matched array (its elements sit at arrayDepth + 1).
        var arrayDepth = -1
        var element: StringBuilder? = null
        var elementDepth = 0
        var pendingString: String? = null

        while (true) {
            val n = reader.read(buf)
            if (n < 0) break
            for (i in 0 until n) {
                val ch = buf[i]
                element?.append(ch)
                if (inString) {
                    if (escape) { escape = false; if (element == null) lastString.append(ch); continue }
                    when (ch) {
                        '\\' -> escape = true
                        '"' -> { inString = false; pendingString = lastString.toString() }
                        else -> if (element == null) lastString.append(ch)
                    }
                    continue
                }
                when (ch) {
                    '"' -> { inString = true; lastString.setLength(0) }
                    ':' -> { lastKey = pendingString; expectValueOf = lastKey; pendingString = null }
                    '{', '[' -> {
                        depth++
                        if (element == null && arrayDepth < 0 && ch == '[' && expectValueOf == arrayKey) {
                            arrayDepth = depth
                        } else if (element == null && arrayDepth >= 0 && depth == arrayDepth + 1) {
                            element = StringBuilder().append(ch)
                            elementDepth = depth
                        }
                        expectValueOf = null
                    }
                    '}', ']' -> {
                        if (element != null && depth == elementDepth) {
                            action(element.toString())
                            element = null
                        }
                        if (depth == arrayDepth && ch == ']') arrayDepth = -1
                        depth--
                        expectValueOf = null
                    }
                    ',' -> expectValueOf = null
                    else -> Unit
                }
            }
        }
    }
}
