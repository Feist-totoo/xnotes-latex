package com.xnotes.ui

import com.xnotes.core.geometry.Pt
import com.xnotes.core.model.ImageItem
import com.xnotes.platform.ImageSplitDraft
import kotlin.math.cos
import kotlin.math.sin

/** Geometry mapping stays on the board; cutting uses the exact same normalized plane as its PNG. */
class ImageCutSession(val item: ImageItem, val draft: ImageSplitDraft,
    val toViewport: (Pt) -> Pt, val fromViewport: (Pt) -> Pt, val valid: () -> Boolean) {
    fun screen(p: Pt): Pt {
        val dx = (p.x - .5) * item.rect.w; val dy = (p.y - .5) * item.rect.h
        return toViewport(Pt(item.rect.centerX + dx * cos(item.angle) - dy * sin(item.angle),
            item.rect.centerY + dx * sin(item.angle) + dy * cos(item.angle)))
    }
    fun local(p: Pt): Pt {
        val q = fromViewport(p) - item.rect.center
        return Pt(.5 + (q.x * cos(item.angle) + q.y * sin(item.angle)) / item.rect.w,
            .5 + (-q.x * sin(item.angle) + q.y * cos(item.angle)) / item.rect.h)
    }
}
