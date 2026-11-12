package com.xnotes.platform

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.xnotes.core.formula.LatexSource
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.FormulaSource
import com.xnotes.core.model.ImageData
import com.xnotes.core.model.ImageItem
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.Stroke
import java.io.File
import java.util.UUID
import kotlin.math.ceil

/** A frozen selection and a guarded commit back to its original document. */
class FormulaDraft(
    items: List<CanvasItem>,
    val imageDir: File,
    val apply: (ImageItem, Boolean) -> Boolean,
) {
    private val previous = (items.singleOrNull() as? ImageItem)?.let {
        ImageItem(it.image, it.rect, it.orientation, it.angle, it.flip, it.part, it.formula).apply {
            group = it.group; sync = it.sync
        }
    }
    val originalFormula = previous?.formula
    val bounds: Rect = items.map { it.bounds() }.reduce(Rect::union)
    private val strokes = items.filterIsInstance<Stroke>().map { Stroke(it).also { copy -> copy.group = it.group; copy.sync = it.sync } }
    val hasPenInput: Boolean get() = strokes.isNotEmpty()
    val color = originalFormula?.color ?: strokes.lastOrNull()?.config?.rgba ?: Rgba(24, 24, 24)
    val initialLatex = originalFormula?.latex?.let(LatexSource::clean).orEmpty()

    /** Frozen visible centerlines, including clipping from room/area erasers. */
    fun recognitionStrokes() = strokes.flatMap { it.visibleRuns() }

    /** Only selected ink, on white; no paper lines, PDF, annotations or neighbouring ink. */
    fun inkBitmap(): Bitmap? {
        if (originalFormula != null) return BitmapFactory.decodeFile(previous?.image?.file?.path)
        if (strokes.isEmpty() && previous == null) return null
        val box = bounds.outset(8.0)
        require(box.w.isFinite() && box.h.isFinite() && box.w > 0 && box.h > 0)
        val scale = minOf(3.0, 1536.0 / maxOf(box.w, box.h))
        val surface = AndroidRasterSurface.create(ceil(box.w * scale).toInt(), ceil(box.h * scale).toInt())
        surface.fill(Rgba(255, 255, 255))
        val renderer = surface.renderer()
        renderer.scale(scale, scale)
        renderer.translate(-box.x, -box.y)
        previous?.paint(renderer)
        for (originalStroke in strokes) {
            val stroke = Stroke(originalStroke)
            stroke.config = stroke.config.copy(rgba = Rgba(0, 0, 0), neon = false, colorOverride = null, highlighterAlpha = 1.0)
            stroke.paint(renderer)
        }
        return surface.bitmap
    }

    fun render(raw: String): RenderedFormula = FormulaTypesetter.render(raw, color, originalFormula?.pointSize ?: 24.0)

    fun block(rendered: RenderedFormula, keepInk: Boolean): ImageItem {
        imageDir.mkdirs()
        val file = File(imageDir, "formula-${UUID.randomUUID()}.png")
        file.outputStream().use { check(rendered.bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        val editing = originalFormula != null
        val width = if (editing) previous!!.rect.w else bounds.w.coerceIn(80.0, 900.0)
        val height = width * rendered.bitmap.height / rendered.bitmap.width
        val position = if (editing) previous!!.rect else bounds
        val rect = Rect(position.x, if (keepInk && !editing) bounds.bottom + 16.0 else position.y, width, height)
        return ImageItem(ImageData(file, rendered.bitmap.width, rendered.bitmap.height), rect,
            orientation = if (editing) previous!!.orientation else 0, angle = if (editing) previous!!.angle else 0.0,
            formula = rendered.source.copy(ink = originalFormula?.ink ?: if (!keepInk) com.xnotes.core.model.FormulaInk.valid(rect, strokes) else null))
            .also { it.group = previous?.group; it.sync = previous?.sync }
    }

    companion object {
        fun accepts(items: List<CanvasItem>): Boolean =
            (items.singleOrNull() as? ImageItem)?.let { !it.locked } == true ||
                (items.isNotEmpty() && items.size <= 1000 && items.all { it is Stroke && it.sampleCount > 0 && !it.locked })
    }
}

data class RenderedFormula(val source: FormulaSource, val bitmap: Bitmap)
