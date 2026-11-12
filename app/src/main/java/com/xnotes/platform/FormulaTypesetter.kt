package com.xnotes.platform

import com.xnotes.core.formula.LatexSource
import com.xnotes.core.model.FormulaSource
import com.xnotes.core.model.Rgba
import kotlin.math.ceil

object FormulaTypesetter {
    fun render(raw: String, color: Rgba = Rgba(24, 24, 24), pointSize: Double = 24.0): RenderedFormula {
        val latex = LatexSource.clean(raw)
        require(LatexSource.problem(latex) == null) { "Invalid math source" }
        check(MathRendering.Blocking.ready()) { "Formula fonts are not ready" }
        require(LatexInternals.recognizesMath(latex)) { "Unsupported math command" }
        val math = MathRendering.Blocking.measure(latex, pointSize, true)
        require(math != null && math.width.isFinite() && math.height.isFinite() && math.width > 0 && math.height > 0) { "Formula cannot be rendered" }
        val padding = 6.0
        val width = math.width + padding * 2
        val height = math.height + padding * 2
        val scale = minOf(4.0, 3072.0 / maxOf(width, height), kotlin.math.sqrt(4_000_000.0 / (width * height)))
        val surface = AndroidRasterSurface.create(ceil(width * scale).toInt(), ceil(height * scale).toInt())
        surface.fill(Rgba(0, 0, 0, 0))
        val renderer = surface.renderer()
        renderer.scale(scale, scale)
        check(renderer.drawMath(latex, padding, padding + math.ascent, pointSize, color, true))
        return RenderedFormula(FormulaSource(latex, color, pointSize), surface.bitmap)
    }

}
