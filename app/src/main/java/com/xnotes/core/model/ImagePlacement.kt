package com.xnotes.core.model

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect

/** Shared placement for imported pictures and rendered formulas. */
object ImagePlacement {
    fun place(width: Int, height: Int, orientation: Int, area: Rect, at: Pt? = null, clamp: Boolean): Rect {
        require(width > 0 && height > 0 && area.w.isFinite() && area.h.isFinite() && area.w > 0 && area.h > 0)
        val turned = Math.floorMod(orientation, 180) == 90
        val iw = if (turned) height else width
        val ih = if (turned) width else height
        val scale = minOf(1.0, area.w * .6 / iw, area.h * .6 / ih)
        val w = iw * scale; val h = ih * scale
        val center = at ?: area.center
        val x = center.x - w / 2; val y = center.y - h / 2
        return Rect(if (clamp) x.coerceIn(area.left, area.right - w) else x,
            if (clamp) y.coerceIn(area.top, area.bottom - h) else y, w, h)
    }
}
