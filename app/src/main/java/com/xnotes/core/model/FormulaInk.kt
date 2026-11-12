package com.xnotes.core.model

import com.xnotes.core.geometry.Affine
import com.xnotes.core.geometry.Rect

/** Frozen editable strokes plus the formula's initial placement, independent of its PNG cache. */
class FormulaInk(val reference: Rect, strokes: List<Stroke>) {
    val strokes = strokes.map { Stroke(it).also { copy -> copy.group = it.group; copy.sync = it.sync } }
    fun restore(item: ImageItem): List<Stroke> {
        val sx = item.rect.w / reference.w; val sy = item.rect.h / reference.h
        val fit = Affine(sx, 0.0, 0.0, sy, item.rect.x - reference.x * sx, item.rect.y - reference.y * sy)
        val transform = Affine.rotateAbout(item.rect.center, item.angle).compose(fit)
        return strokes.map { Stroke(it).also { copy -> copy.applyTransform(transform); copy.group = it.group; copy.sync = it.sync } }
    }
    companion object {
        fun valid(reference: Rect?, strokes: List<Stroke>): FormulaInk? = reference?.takeIf {
            listOf(it.x, it.y, it.w, it.h).all(Double::isFinite) && it.w > 0 && it.h > 0
        }?.takeIf { strokes.size in 1..256 && strokes.sumOf { s -> s.sampleCount.toLong() } <= 100_000 &&
            strokes.all { s -> s.sampleCount > 0 && s.samples.all { p -> p.x.isFinite() && p.y.isFinite() && p.pressure.isFinite() } } }
            ?.let { FormulaInk(it, strokes) }
    }
}
