package com.xnotes.core.model

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.vector.PolygonClip
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Straight cuts in the image's displayed, unrotated unit box. Pixels remain in ordinary PNGs. */
object ImageSliceGeometry {
    val square = listOf(Pt(0.0, 0.0), Pt(1.0, 0.0), Pt(1.0, 1.0), Pt(0.0, 1.0))

    fun sourcePoint(item: ImageItem, p: Pt): Pt {
        val x = (p.x - .5) * if (item.flip.x) -1 else 1
        val y = (p.y - .5) * if (item.flip.y) -1 else 1
        val q = when (Math.floorMod(item.orientation, 360)) {
            90 -> Pt(y, -x); 180 -> Pt(-x, -y); 270 -> Pt(-y, x); else -> Pt(x, y)
        }
        val part = item.part ?: ImageCrop.WHOLE
        return Pt(part.x + (q.x + .5) * part.w, part.y + (q.y + .5) * part.h)
    }

    fun displayPoint(item: ImageItem, p: Pt): Pt {
        val part = item.part ?: ImageCrop.WHOLE
        val x = (p.x - part.x) / part.w - .5
        val y = (p.y - part.y) / part.h - .5
        val q = when (Math.floorMod(item.orientation, 360)) {
            90 -> Pt(-y, x); 180 -> Pt(-x, -y); 270 -> Pt(y, -x); else -> Pt(x, y)
        }
        return Pt(.5 + q.x * if (item.flip.x) -1 else 1, .5 + q.y * if (item.flip.y) -1 else 1)
    }

    fun outline(item: ImageItem): List<Pt> = item.cutOutline?.let {
        PolygonClip.polygon(PolygonClip.wound(it.map { p -> displayPoint(item, p) }), square)
    } ?: square

    fun worldOutline(item: ImageItem): List<Pt> = outline(item).map {
        val dx = (it.x - .5) * item.rect.w
        val dy = (it.y - .5) * item.rect.h
        Pt(item.rect.centerX + dx * cos(item.angle) - dy * sin(item.angle),
            item.rect.centerY + dx * sin(item.angle) + dy * cos(item.angle))
    }

    fun side(a: Pt, b: Pt, p: Pt) = (b.x - a.x) * (p.y - a.y) - (b.y - a.y) * (p.x - a.x)

    fun area(p: List<Pt>): Double = abs(p.indices.sumOf { i ->
        val q = p[(i + 1) % p.size]; p[i].x * q.y - q.x * p[i].y
    }) / 2

    /** An infinite straight line, not a freehand path; no cut if it only grazes the visible piece. */
    fun split(outline: List<Pt>, a: Pt, b: Pt): List<List<Pt>>? {
        if (listOf(a.x, a.y, b.x, b.y).any { !it.isFinite() } || a.distanceTo(b) < 1e-6) return null
        val pieces = listOf(half(outline, a, b), half(outline, b, a))
        return pieces.takeIf { it.all { p -> p.size >= 3 && area(p) > 1e-5 } }
    }

    private fun half(polygon: List<Pt>, a: Pt, b: Pt): List<Pt> {
        val out = ArrayList<Pt>()
        for (i in polygon.indices) {
            val p = polygon[i]; val q = polygon[(i + 1) % polygon.size]
            val fp = side(a, b, p); val fq = side(a, b, q)
            if (fp >= 0) out.add(p)
            if ((fp >= 0) != (fq >= 0)) out.add(p + (q - p) * (fp / (fp - fq)))
        }
        return out
    }

    fun cropOutline(outline: List<Pt>?, source: Rect): List<Pt>? = outline?.let {
        val normalized = it.map { p -> Pt((p.x - source.x) / source.w, (p.y - source.y) / source.h) }
        PolygonClip.polygon(PolygonClip.wound(normalized), square)
    }

    /** Metadata is only a hit outline. Old readers still display the same transparent PNG. */
    fun validOutline(points: List<Pt>?): List<Pt>? = points?.takeIf { p ->
        p.size in 3..512 && p.all { it.x.isFinite() && it.y.isFinite() && it.x in -1e-8..1.00000001 && it.y in -1e-8..1.00000001 } && area(p) > 1e-10
    }
}
