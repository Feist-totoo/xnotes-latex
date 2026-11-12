package com.xnotes.core.formula

import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.ImageItem
import com.xnotes.core.model.Stroke

/** Value snapshots: Stroke's undo snapshots deliberately do not implement value equality. */
class FormulaSelectionState(items: List<CanvasItem>) {
    private val captured = items.map { it to stamp(it) }

    fun unchanged(): Boolean = captured.all { (item, before) -> stamp(item) == before }

    private fun stamp(item: CanvasItem): List<Any?> = when (item) {
        is Stroke -> listOf(item.samples.toList(), item.config, item.cuts.toList(), item.bounds(), item.speedScale, item.smoothScale, item.locked, item.group)
        is ImageItem -> listOf(item.snapshotGeometry(), item.orientation, item.image, item.formula, item.cutOutline, item.locked, item.group, item.sync)
        else -> listOf(item.snapshotGeometry(), item.locked)
    }
}
