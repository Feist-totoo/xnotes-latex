package com.xnotes.core.formula

import com.xnotes.core.model.CanvasItem

/** Replace exactly the captured selection, preserving every unrelated item's order. */
object FormulaReplacement {
    fun items(before: List<CanvasItem>, selected: List<CanvasItem>, block: CanvasItem, keepInk: Boolean): List<CanvasItem> {
        require(selected.isNotEmpty() && selected.all { s -> before.any { it === s } })
        val at = before.indexOfFirst { it === selected.first() }
        val selectedSet = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<CanvasItem, Boolean>()).apply { addAll(selected) }
        return buildList {
            before.forEachIndexed { index, item ->
                if (index == at) add(block)
                if (keepInk || item !in selectedSet) add(item)
            }
        }
    }
}
