package com.xnotes.core.formula

import org.json.JSONObject
import kotlin.math.ln

/** A model-compatible, data-only distribution of symbol/structure pairs. */
class FormulaRecognitionPrior private constructor(
    private val counts: IntArray,
    private val totals: LongArray,
    val strength: Double,
    val maxVisualGap: Double,
) {
    fun logLikelihood(symbols: IntArray, modifiers: IntArray): Double {
        if (symbols.isEmpty() || symbols.size != modifiers.size) return -20.0
        var sum = 0.0
        for (i in symbols.indices) {
            val s = symbols[i]; val m = modifiers[i]
            if (s !in 0 until VOCABULARY_SIZE || m !in 0 until VOCABULARY_SIZE) return -20.0
            sum += ln((counts[s * VOCABULARY_SIZE + m] + ALPHA) / (totals[s] + ALPHA * VOCABULARY_SIZE))
        }
        return sum / symbols.size
    }

    companion object {
        const val ENGINE = "gryphone-sat-q8-v1"
        const val SCORING = "masked-quarter-v1"
        const val VOCABULARY_SIZE = 287
        const val VOCABULARY_SHA256 = "bab4047952478a4ac78a8391da0bbf3ffaaec189b562dae6288d4305fbe3b07c"
        private const val ALPHA = 0.25

        fun parse(json: JSONObject): FormulaRecognitionPrior {
            require(json.getString("engine") == ENGINE && json.getString("scoring") == SCORING && json.getString("vocabularySha256") == VOCABULARY_SHA256 &&
                json.getInt("vocabularySize") == VOCABULARY_SIZE) { "Incompatible recognition package" }
            val strength = json.getDouble("strength")
            val gap = json.getDouble("maxVisualGap")
            require(strength.isFinite() && strength in 0.0..0.1 && gap.isFinite() && gap in 0.0..0.03)
            require(json.getDouble("alpha") == ALPHA)
            val rows = json.getJSONArray("pairs"); require(rows.length() in 1..4096)
            val counts = IntArray(VOCABULARY_SIZE * VOCABULARY_SIZE)
            val totals = LongArray(VOCABULARY_SIZE)
            for (i in 0 until rows.length()) {
                val row = rows.getJSONArray(i); require(row.length() == 3)
                val s = row.getInt(0); val m = row.getInt(1); val count = row.getInt(2)
                require(s in 0 until VOCABULARY_SIZE && m in 0 until VOCABULARY_SIZE && count in 1..20_000_000)
                val index = s * VOCABULARY_SIZE + m
                require(counts[index] == 0) { "Duplicate recognition statistics" }
                counts[index] = count; totals[s] += count
                require(totals[s] <= 50_000_000)
            }
            return FormulaRecognitionPrior(counts, totals, strength, gap)
        }
    }
}

data class FormulaCandidate(val latex: String, val visualScore: Double, val symbols: IntArray, val modifiers: IntArray)
data class FormulaRanking(val candidates: List<FormulaCandidate>, val packageIds: Set<String>)

/** Subject information may break close visual ties; it never invents a new expression. */
object FormulaCandidateRanking {
    fun rank(candidates: List<FormulaCandidate>, enabledPacks: List<FormulaPack>, automatic: Boolean): FormulaRanking {
        val visual = candidates.sortedByDescending { it.visualScore }.distinctBy { it.latex }
        if (visual.isEmpty()) return FormulaRanking(emptyList(), emptySet())
        val compatible = enabledPacks.filter { it.recognition != null }
        val primary = if (automatic) FormulaPackRouter.recommend(visual.first().latex, compatible).firstOrNull()?.id else null
        val active = if (automatic) compatible.filter { it.id == primary } else compatible
        if (active.isEmpty()) return FormulaRanking(visual, emptySet())
        val best = visual.first().visualScore
        val ranked = visual.sortedByDescending { candidate ->
            if (active.any { best - candidate.visualScore > it.recognition!!.maxVisualGap }) Double.NEGATIVE_INFINITY
            else candidate.visualScore + active.sumOf { pack ->
                val prior = pack.recognition!!
                prior.strength * prior.logLikelihood(candidate.symbols, candidate.modifiers)
            } / active.size
        }
        return FormulaRanking(ranked, active.map { it.id }.toSet())
    }
}
