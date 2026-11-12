// The LaTeX library keeps its layout and drawing Kotlin-internal; this file alone reaches past that.
@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package com.xnotes.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.Density
import com.hrm.latex.parser.ParseResult
import com.hrm.latex.parser.ParseDiagnostic
import com.hrm.latex.parser.model.LatexNode
import com.hrm.latex.parser.model.SourceRange
import com.hrm.latex.parser.tokenizer.LatexToken
import com.hrm.latex.parser.tokenizer.LatexTokenizer
import com.xnotes.core.formula.LatexSource
import com.hrm.latex.renderer.font.MathFontProvider
import com.hrm.latex.renderer.font.MathFontProviderFactory
import com.hrm.latex.renderer.layout.LatexRenderResult
import com.hrm.latex.renderer.layout.LatexRenderer
import com.hrm.latex.renderer.model.LatexConfig
import com.hrm.latex.renderer.model.LatexFontFamilies
import com.hrm.latex.renderer.model.defaultLatexFontFamilies
import com.hrm.latex.renderer.model.toContext

/**
 * The few calls that set a formula once and then both measure and draw it from that one layout,
 * rather than through the library's public measurer and exporter, which each lay it out again. The
 * library marks them internal, so they are kept to this one file, where a library update that moves
 * them fails the build here rather than somewhere far away.
 */
object LatexInternals {

    /** The library's default fonts, read through the composition exactly as its own composables do. */
    @Composable
    fun defaultFonts(): LatexFontFamilies = defaultLatexFontFamilies()

    /** [latex] parsed; empty when nothing in it sets. */
    fun parse(latex: String): List<LatexNode> = parseMath(latex).document.children

    /** TeX math ignores ordinary whitespace; the library treats it as visible Space nodes.
     * Adapt tokens, retaining source offsets, text groups and explicit spacing commands.
     * Split math text into atoms so compact `ab^2` scripts b, not the entire string ab.
     */
    private fun parseMath(latex: String): ParseResult {
        val literal = LatexSource.literalMask(latex)
        val tokens = LatexTokenizer(latex).tokenize().flatMap { token ->
            when {
                token is LatexToken.Whitespace && !literal.getOrElse(token.range.start) { false } &&
                    latex.substring(token.range.start, token.range.end).all { it.isWhitespace() } -> emptyList()
                token is LatexToken.Text && !(token.range.start until token.range.end).all { literal[it] } -> buildList {
                    var offset = 0
                    while (offset < token.content.length) {
                        var end = offset + Character.charCount(token.content.codePointAt(offset))
                        if (literal[token.range.start + offset]) {
                            while (end < token.content.length && literal[token.range.start + end])
                                end += Character.charCount(token.content.codePointAt(end))
                        }
                        add(LatexToken.Text(token.content.substring(offset, end),
                            SourceRange(token.range.start + offset, token.range.start + end)))
                        offset = end
                    }
                }
                else -> listOf(token)
            }
        }
        return LatexParserBridge.parse(tokens, latex.length)
    }

    /** Unknown commands are shown as red source by the renderer; never accept those as formulas. */
    fun recognizesMath(latex: String): Boolean {
        val result = parseMath("\\displaystyle $latex")
        val nodes = result.document.children
        if (result.diagnostics.any { it.severity == ParseDiagnostic.Severity.ERROR }) return false
        return nodes.isNotEmpty() && nodes.none { root -> root.fold(false) { found, n ->
            found || n is LatexNode.Command ||
                (n is LatexNode.Superscript && n.exponent.emptyScript()) ||
                (n is LatexNode.Subscript && n.index.emptyScript())
        } }
    }

    private fun LatexNode.emptyScript(): Boolean = when (this) {
        is LatexNode.Text -> content.isBlank()
        is LatexNode.Space -> true
        is LatexNode.Group -> children.all { it.emptyScript() }
        else -> false
    }

    /** The font metrics every [layout] shares; the library builds a fresh one per formula otherwise. */
    internal fun metrics(fonts: LatexFontFamilies): Any = MathFontProviderFactory.create(fonts)

    /** [nodes] laid out, as a handle for the calls below; null when they set nothing. */
    internal fun layout(
        nodes: List<LatexNode>,
        config: LatexConfig,
        fonts: LatexFontFamilies,
        metrics: Any?,
        text: TextMeasurer,
        density: Density,
    ): Any? {
        if (nodes.isEmpty()) return null
        val r = LatexRenderer.measure(nodes, config.toContext(false, fonts, metrics as MathFontProvider?), text, density)
        return r.takeIf { it.layout.width > 0f && it.layout.height > 0f }
    }

    internal fun width(layout: Any): Float = (layout as LatexRenderResult).canvasWidth

    internal fun height(layout: Any): Float = (layout as LatexRenderResult).canvasHeight

    /** How far below the top of the box [width] and [height] describe the formula's baseline sits. */
    internal fun baseline(layout: Any): Float = (layout as LatexRenderResult).let { it.layout.baseline + it.verticalPadding }

    /** Draw [layout] into [scope] as the library's renderer does over a transparent background. */
    internal fun draw(scope: DrawScope, layout: Any) {
        val r = layout as LatexRenderResult
        r.layout.draw(scope, r.horizontalPadding, r.verticalPadding)
    }

    /**
     * Whether [nodes] paint in colours of their own rather than only the text colour: `\color` and
     * its kin, links, and commands the library does not know, which it marks in red.
     */
    fun ownColors(nodes: List<LatexNode>): Boolean =
        nodes.any { root -> root.fold(false) { found, n -> found || n.ownsColor() } }

    private fun LatexNode.ownsColor(): Boolean = when (this) {
        is LatexNode.Color, is LatexNode.ColorBox, is LatexNode.Hyperlink, is LatexNode.Command -> true
        is LatexNode.Enclose -> "mathcolor" in attributes || "mathbackground" in attributes
        else -> false
    }
}
