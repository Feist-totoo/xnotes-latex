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
        val originalRegions = regions(s)
        s = Regex("\\\\([A-Za-z]+)").replace(s) { m ->
            if (originalRegions.literal[m.range.first] || originalRegions.comment[m.range.first]) m.value
            else "\\" + (aliases[m.groupValues[1]] ?: m.groupValues[1])
        }
        val regions = regions(s)
        return buildString {
            var i = 0
            while (i < s.length) {
                if (s[i].isWhitespace() && !regions.literal[i] && !regions.comment[i]) {
                    val start = i
                    while (i < s.length && s[i].isWhitespace() && !regions.literal[i] && !regions.comment[i]) i++
                    // A control word needs its terminator: \sin x must never become \sinx.
                    if (regions.commandEnd[start] && i < s.length && s[i].asciiLetter()) append(' ')
                } else append(s[i++])
            }
        }
    }

    /** True for literal text/verbatim and escaped characters; used by the native parser adapter. */
    fun literalMask(source: String): BooleanArray = regions(source).literal

    private data class Regions(val literal: BooleanArray, val comment: BooleanArray, val commandEnd: BooleanArray)
    private fun Char.asciiLetter() = this in 'a'..'z' || this in 'A'..'Z'

    /** Lexical protection only. Never infer missing symbols or rewrite mathematical operators. */
    private fun regions(source: String): Regions {
        val literal = BooleanArray(source.length)
        val comment = BooleanArray(source.length)
        val commandEnd = BooleanArray(source.length + 1)
        val textCommands = setOf("text", "textrm", "textnormal", "textbf", "textit", "texttt", "textsf",
            "textsl", "textsc", "mbox", "hbox", "operatorname", "ce", "cf")
        var depth = 0
        var pendingText = false
        var i = 0
        while (i < source.length) {
            val ch = source[i]
            if (ch == '%') {
                val start = i
                while (i < source.length && source[i] != '\n' && source[i] != '\r') i++
                // Preserve the line ending in exported source: otherwise a comment eats the next line.
                if (i < source.length && source[i] == '\r') i++
                if (i < source.length && source[i] == '\n') i++
                for (j in start until i) { comment[j] = true; literal[j] = depth > 0 }
                continue
            }
            if (ch == '\\') {
                val start = i++
                if (i < source.length && source[i].asciiLetter()) {
                    while (i < source.length && source[i].asciiLetter()) i++
                    val command = source.substring(start + 1, i)
                    commandEnd[i] = true
                    if (depth > 0) for (j in start until i) literal[j] = true
                    if (command == "verb") {
                        if (i < source.length && source[i] == '*') i++
                        if (i < source.length) {
                            val delimiter = source[i++]
                            while (i < source.length && source[i] != delimiter) i++
                            if (i < source.length) i++
                        }
                        for (j in start until i) literal[j] = true
                        pendingText = false
                    } else if (depth == 0) pendingText = command in textCommands
                } else {
                    if (i < source.length) i++
                    // Includes escaped spaces/braces/percent and explicit \\ row breaks.
                    for (j in start until i) literal[j] = true
                    if (depth == 0) pendingText = false
                }
                continue
            }
            if (depth > 0) {
                literal[i] = true
                if (ch == '{') depth++
                if (ch == '}') depth--
            } else if (pendingText && ch == '{') {
                literal[i] = true; depth = 1; pendingText = false
            } else if (!ch.isWhitespace() && !(pendingText && ch == '*')) {
                // A text macro can also take a single, unbraced token.
                if (pendingText) literal[i] = true
                pendingText = false
            }
            i++
        }
        return Regions(literal, comment, commandEnd)
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
