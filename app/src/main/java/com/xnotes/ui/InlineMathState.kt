package com.xnotes.ui

import androidx.compose.runtime.*
import com.xnotes.core.model.Stroke

/** A pen-up batch. Rendering and restoration never silently consume the source strokes. */
class InlineMathState {
    var enabled by mutableStateOf(false)
        private set
    var revision by mutableIntStateOf(0)
        private set
    var touching by mutableStateOf(false)
        private set
    var waitingForEdit by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
    var owner: Any? = null
        private set
    var strokes: List<Stroke> = emptyList()
        private set
    fun toggle() { enabled = !enabled; clear() }
    fun clear() { owner = null; strokes = emptyList(); waitingForEdit = false; busy = false; revision++ }
    /** Tool changes cancel inference but keep the editable expression available for the next pen stroke. */
    fun hold() { if (strokes.isNotEmpty()) { waitingForEdit = true; busy = false; revision++ } }
    /** Erasing a restored stroke must retain exactly its surviving fragments, never resurrect erased ink. */
    fun replaceInk(original: Stroke, fragments: List<Stroke>) {
        if (strokes.none { it === original }) return
        strokes = strokes.flatMap { if (it === original) fragments else listOf(it) }
        if (strokes.size > 256 || strokes.sumOf { it.sampleCount.toLong() } > 100_000) clear()
        else { waitingForEdit = true; revision++ }
    }
    fun touch(down: Boolean) { touching = down; revision++ }
    fun restore(key: Any, ink: List<Stroke>) { clear(); owner = key; strokes = ink; waitingForEdit = true; revision++ }
    fun offer(key: Any, stroke: Stroke) {
        if (!enabled || !stroke.tool.isStroke || stroke.tool == com.xnotes.core.tools.Tool.HIGHLIGHTER) return
        val box = strokes.map { it.bounds() }.reduceOrNull { a, b -> a.union(b) }
        val near = box == null || box.outset(maxOf(80.0, box.h * 1.5)).intersects(stroke.bounds())
        if (owner !== key || !near || (!waitingForEdit && strokes.lastOrNull()?.config?.rgba?.let { it != stroke.config.rgba } == true)) clear()
        owner = key
        if (strokes.size < 256 && strokes.sumOf { it.sampleCount.toLong() } + stroke.sampleCount <= 100_000) strokes = strokes + stroke
        else clear()
        waitingForEdit = false; revision++
    }
}
