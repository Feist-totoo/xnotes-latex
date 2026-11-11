package com.xnotes.core.formula

/** Bounded math input, not a TeX document. A successful check does not certify OCR correctness. */
object LatexSource {
    fun clean(raw: String): String {
        var s = raw.trim()
        for ((left, right) in listOf("\$\$" to "\$\$", "\\[" to "\\]", "\\(" to "\\)", "\$" to "\$")) {
            if (s.startsWith(left) && s.endsWith(right) && s.length > left.length + right.length) {
                s = s.substring(left.length, s.length - right.length).trim()
                break
            }
        }
        val aliases = mapOf("infin" to "infty", "rarr" to "rightarrow", "larr" to "leftarrow", "harr" to "leftrightarrow", "Rarr" to "Rightarrow", "Larr" to "Leftarrow", "Harr" to "Leftrightarrow", "darr" to "downarrow", "uarr" to "uparrow")
        s = Regex("\\\\([A-Za-z]+)").replace(s) { m -> "\\" + (aliases[m.groupValues[1]] ?: m.groupValues[1]) }
        return normalizeScripts(s)
    }

    /**
     * OCR separates every token with spaces. TeX ignores them around ^ and _, but the native
     * parser attaches scripts to Space nodes and consumes another Space as their argument.
     * Only remove those separators: never join control words (\\sin x), rewrite symbols, or
     * change literal text. Copy escape sequences atomically so \\_ and \\^ remain literal.
     */
    private fun normalizeScripts(source: String): String {
        val textCommands = setOf("text", "textrm", "textnormal", "textbf", "textit", "texttt", "textsf", "textsl", "textsc", "mbox", "hbox", "verb")
        val out = StringBuilder()
        var i = 0
        var literalDepth = 0
        var pendingText = false
        var afterScript = false
        var whitespaceStart = -1
        while (i < source.length) {
            val ch = source[i]
            if (ch == '%') {
                while (i < source.length && source[i] != '\n') out.append(source[i++])
                if (i < source.length) out.append(source[i++])
                whitespaceStart = -1
                continue
            }
            if (ch == '\\') {
                val start = i++
                if (i < source.length && source[i].isLetter()) {
                    while (i < source.length && source[i].isLetter()) i++
                } else if (i < source.length) i++
                val command = source.substring(start + 1, i)
                out.append(source, start, i)
                if (command == "verb" && i < source.length) {
                    if (source[i] == '*') out.append(source[i++])
                    if (i < source.length) {
                        val delimiter = source[i]
                        out.append(source[i++])
                        while (i < source.length && source[i] != delimiter) out.append(source[i++])
                        if (i < source.length) out.append(source[i++])
                    }
                }
                pendingText = command in textCommands
                afterScript = false
                whitespaceStart = -1
                continue
            }
            if (literalDepth > 0) {
                if (ch == '{') literalDepth++
                if (ch == '}') literalDepth--
                out.append(ch)
                whitespaceStart = -1
            } else if (pendingText && ch == '{') {
                literalDepth = 1
                pendingText = false
                out.append(ch)
            } else if (ch == '^' || ch == '_') {
                if (whitespaceStart >= 0) out.setLength(whitespaceStart)
                out.append(ch)
                afterScript = true
                pendingText = false
                whitespaceStart = -1
            } else if (!ch.isWhitespace() || !afterScript) {
                if (ch.isWhitespace() && whitespaceStart < 0) whitespaceStart = out.length
                out.append(ch)
                if (!ch.isWhitespace()) { afterScript = false; pendingText = false; whitespaceStart = -1 }
            }
            i++
        }
        return out.toString()
    }

    fun problem(source: String): String? {
        if (source.isBlank()) return "empty"
        if (source.length > 4096) return "length"
        if (Regex("\\\\(input|include|write|openout|read|usepackage|documentclass|def|newcommand)\\b").containsMatchIn(source)) return "document"
        var depth = 0
        var i = 0
        while (i < source.length) {
            if (source[i] == '\\') { i += 2; continue }
            when (source[i]) {
                '{' -> depth++
                '}' -> if (--depth < 0) return "braces"
            }
            i++
        }
        if (depth != 0) return "braces"
        val environments = ArrayDeque<String>()
        for (m in Regex("\\\\(begin|end)\\s*\\{([^}]+)\\}").findAll(source)) {
            val name = m.groupValues[2]
            if (m.groupValues[1] == "begin") environments.addLast(name)
            else if (environments.removeLastOrNull() != name) return "environment"
        }
        return if (environments.isEmpty()) null else "environment"
    }
}
