package com.xnotes.ui

import androidx.compose.runtime.*
import com.xnotes.core.formula.LatexSource
import com.xnotes.core.geometry.Pt

class MathPadLine {
    var strokes by mutableStateOf<List<List<Pt>>>(emptyList())
        private set
    var revision by mutableIntStateOf(0)
        private set
    var latex by mutableStateOf("")
        private set
    var resultRevision by mutableIntStateOf(-1)
        private set
    var manual by mutableStateOf(false)
        private set
    val empty get() = strokes.isEmpty() && latex.isBlank()
    val ready get() = resultRevision == revision && LatexSource.problem(LatexSource.clean(latex)) == null
    fun ink(value: List<List<Pt>>) { strokes = value; revision++; manual = false; if (value.isEmpty()) latex = "" }
    fun edit(value: String) { revision++; manual = true; accept(value) }
    fun accept(value: String, expected: Int = revision): Boolean {
        if (revision != expected) return false
        latex = value.take(4096); resultRevision = revision; return true
    }
}

class MathPadBlock {
    val lines = mutableStateListOf(MathPadLine())
    var activeLine by mutableIntStateOf(0)
    val current get() = lines[activeLine]
    fun newLine(): Boolean {
        if (!current.ready || lines.size >= 16) return false
        lines.add(MathPadLine()); activeLine = lines.lastIndex; return true
    }
    fun source(): String? {
        val used = lines.filterNot { it.empty }
        if (used.isEmpty() || used.any { !it.ready }) return null
        val sources = used.map { LatexSource.clean(it.latex) }
        return (if (sources.size == 1) sources.single() else "\\begin{aligned}" + sources.joinToString("\\\\") { "&$it" } + "\\end{aligned}")
            .takeIf { it.length <= 4096 && LatexSource.problem(it) == null }
    }
}

class MathPadState {
    var open by mutableStateOf(false)
    val blocks = mutableStateListOf(MathPadBlock())
    var activeBlock by mutableIntStateOf(0)
    val current get() = blocks[activeBlock]
    fun newBlock() {
        if (blocks.size >= 32) return
        blocks.add(MathPadBlock()); activeBlock = blocks.lastIndex
    }
    fun inserted(block: MathPadBlock) {
        val index = blocks.indexOf(block)
        if (index < 0) return
        blocks.removeAt(index)
        if (blocks.isEmpty()) blocks.add(MathPadBlock())
        activeBlock = activeBlock.coerceAtMost(blocks.lastIndex)
    }
}
