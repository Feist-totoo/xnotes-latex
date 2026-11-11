package com.xnotes.core.formula

import org.json.JSONObject

data class FormulaSnippet(val latex: String, val labelZh: String, val labelEn: String) {
    fun label(chinese: Boolean) = if (chinese) labelZh else labelEn
}
data class FormulaPack(
    val id: String, val version: Int, val nameZh: String, val nameEn: String,
    val reviewZh: String, val reviewEn: String, val signals: Map<String, Int>,
    val symbols: List<FormulaSnippet>, val templates: List<FormulaSnippet>,
) {
    fun name(chinese: Boolean) = if (chinese) nameZh else nameEn
    fun review(chinese: Boolean) = if (chinese) reviewZh else reviewEn
    companion object {
        const val MAX_BYTES = 128 * 1024
        fun parse(bytes: ByteArray): FormulaPack {
            require(bytes.size in 1..MAX_BYTES) { "Invalid pack size" }
            val p = JSONObject(bytes.toString(Charsets.UTF_8))
            require(p.getString("format") == "xnotes-formula-pack" && p.getInt("schema") == 1)
            require(p.getString("type") == "vocabulary" && p.getInt("minAppVersionCode") <= 71) { "Unsupported pack type/version" }
            val id = p.getString("id")
            require(Regex("[a-z][a-z0-9_]{0,47}").matches(id)) { "Invalid pack ID" }
            val version = p.getInt("version"); require(version in 1..1_000_000)
            fun text(key: String, max: Int = 160) = p.getString(key).also { require(it.length in 1..max) }
            fun snippets(key: String): List<FormulaSnippet> {
                val values = p.getJSONArray(key); require(values.length() <= 64)
                return List(values.length()) { i -> values.getJSONObject(i).let {
                    val latex = it.getString("latex")
                    require(latex.length <= 1024 && LatexSource.problem(latex) == null) { "Invalid pack formula" }
                    val zh = it.getString("labelZh"); val en = it.getString("labelEn")
                    require(zh.length in 1..80 && en.length in 1..80)
                    FormulaSnippet(latex, zh, en)
                } }
            }
            val signals = p.getJSONObject("signals")
            require(signals.length() <= 64)
            val weights = signals.keys().asSequence().associateWith { key ->
                require(key.length in 1..64)
                signals.getInt(key).also { require(it in 1..8) }
            }
            return FormulaPack(id, version, text("nameZh", 80), text("nameEn", 80),
                text("reviewZh"), text("reviewEn"), weights, snippets("symbols"), snippets("templates"))
        }
    }
}

data class FormulaPackRecommendation(val id: String, val score: Int, val evidence: List<String>)

/** Reuses local OCR. No second VLM, expression rewrite, downloaded regex or code. */
object FormulaPackRouter {
    private val tokenPattern = Regex("\\\\[A-Za-z]+|\\\\.|[^\\s]")
    private fun tokens(source: String): List<String> {
        val raw = tokenPattern.findAll(source).map { it.value }.toList()
        val out = ArrayList<String>(); var i = 0
        while (i < raw.size) {
            if (i + 2 < raw.size && raw[i] == "{" && raw[i + 2] == "}") {
                out.add(raw[i + 1]); i += 3
            } else { out.add(raw[i]); i++ }
        }
        return out
    }
    private fun contains(source: List<String>, signal: List<String>): Boolean {
        if (signal.isEmpty() || signal.size > source.size) return false
        for (i in 0..source.size - signal.size) {
            var matches = true
            for (j in signal.indices) if (source[i+j] != signal[j]) { matches = false; break }
            if (matches) return true
        }
        return false
    }
    fun recommend(source: String, packs: List<FormulaPack>): List<FormulaPackRecommendation> {
        val normalized = tokens(LatexSource.clean(source.take(4096)))
        return packs.map { pack ->
            val found = pack.signals.filter { (signal, _) ->
                contains(normalized, tokens(signal))
            }
            FormulaPackRecommendation(pack.id, found.values.sum(), found.keys.toList())
        }.filter { it.score >= 4 }.sortedWith(compareByDescending<FormulaPackRecommendation> { it.score }.thenBy { it.id }).take(3)
    }
}
