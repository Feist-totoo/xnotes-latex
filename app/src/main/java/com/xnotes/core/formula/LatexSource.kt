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
        return Regex("\\\\([A-Za-z]+)").replace(s) { m -> "\\" + (aliases[m.groupValues[1]] ?: m.groupValues[1]) }
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
