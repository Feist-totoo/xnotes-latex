package com.xnotes.core.formula

import com.xnotes.core.geometry.Pt
import kotlin.math.abs

/** Canonical, pressure-independent pen centerlines used by the handwriting model. */
object FormulaInkRaster {
    const val SIZE = 224
    fun pixels(strokes: List<List<Pt>>): FloatArray {
        var count = 0L
        var left = Double.POSITIVE_INFINITY; var top = Double.POSITIVE_INFINITY
        var right = Double.NEGATIVE_INFINITY; var bottom = Double.NEGATIVE_INFINITY
        for (stroke in strokes) for (p in stroke) {
            require(++count <= 2_000_000 && p.x.isFinite() && p.y.isFinite())
            left=minOf(left,p.x);top=minOf(top,p.y);right=maxOf(right,p.x);bottom=maxOf(bottom,p.y)
        }
        require(count > 0)
        val width = right-left; val height = bottom-top
        require(width.isFinite() && height.isFinite())
        val scale = minOf(SIZE / width.coerceAtLeast(1e-9), SIZE / height.coerceAtLeast(1e-9))
        val pixels = FloatArray(3 * SIZE * SIZE)
        fun mark(x: Int, y: Int) { if (x in 0 until SIZE && y in 0 until SIZE) pixels[y * SIZE + x] = 1f }
        fun line(a: Pt, b: Pt) {
            var x = ((a.x - left) * scale).toInt(); var y = ((a.y - top) * scale).toInt()
            val endX = ((b.x - left) * scale).toInt(); val endY = ((b.y - top) * scale).toInt()
            val dx = abs(endX - x); val dy = abs(endY - y)
            val sx = if (x < endX) 1 else -1; val sy = if (y < endY) 1 else -1
            var error = dx - dy
            while (true) {
                mark(x, y)
                if (x == endX && y == endY) break
                val twice = 2 * error
                if (twice >= -dy) { error -= dy; x += sx }
                if (twice <= dx) { error += dx; y += sy }
            }
        }
        for (stroke in strokes) {
            if (stroke.size == 1) line(stroke[0], stroke[0])
            else for (i in 1 until stroke.size) line(stroke[i - 1], stroke[i])
        }
        System.arraycopy(pixels, 0, pixels, SIZE * SIZE, SIZE * SIZE)
        System.arraycopy(pixels, 0, pixels, 2 * SIZE * SIZE, SIZE * SIZE)
        require(pixels.any { it > 0f }) { "No visible pen strokes" }
        return pixels
    }
}

/** Reproducible across desktop and Android; no platform-dependent random seed. */
class FormulaMaskRandom(private var state: Int) {
    fun next(): Double {
        var x = state
        x = x xor (x shl 13); x = x xor (x ushr 17); x = x xor (x shl 5)
        state = x
        return (x.toLong() and 0xffffffffL) / 4294967296.0
    }
}
