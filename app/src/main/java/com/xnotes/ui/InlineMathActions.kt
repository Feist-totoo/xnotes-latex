package com.xnotes.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import com.xnotes.core.geometry.Pt
import com.xnotes.core.model.Page
import com.xnotes.core.history.Command
import kotlin.math.roundToInt

@Composable
fun InlineMathActions(editor: Editor) {
    val state = editor.inlineMath
    if ((!state.ready && !state.busy) || state.touching || state.strokes.isEmpty()) return
    val last = state.strokes.last()
    val p = Pt(last.xAt(last.sampleCount-1), last.yAt(last.sampleCount-1))
    val at = if (editor.canvasOpen) editor.infinite.viewport.contentToViewport(p) else {
        val index = editor.state.document.pages.indexOf(state.owner as? Page)
        if (index < 0) return
        editor.state.contentToViewport(editor.state.fromPageSpace(index, p))
    }
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val x = at.x.toFloat().coerceIn(0f, with(density) { (maxWidth-112.dp).toPx() }.coerceAtLeast(0f))
        val y = (at.y.toFloat()+12).coerceIn(0f, with(density) { (maxHeight-48.dp).toPx() }.coerceAtLeast(0f))
        Surface(Modifier.offset { IntOffset(x.roundToInt(), y.roundToInt()) }, shape=MaterialTheme.shapes.medium, tonalElevation=5.dp) {
            Row {
                TextButton(modifier=Modifier.testTag("inline-confirm"), enabled=!state.busy, onClick=state::render) { Text("✓") }
                TextButton(modifier=Modifier.testTag("inline-break"), enabled=!state.busy, onClick={
                    val owner = state.owner ?: return@TextButton
                    val ink = state.strokes.toList()
                    val command = object: Command {
                        override fun redo() { state.clear() }
                        override fun undo() { state.restore(owner, ink); state.waitForConfirmation() }
                    }
                    command.redo()
                    if (editor.canvasOpen) editor.infinite.history.push(command) else editor.history.push(command)
                }) { Text("；") }
                if (state.busy) CircularProgressIndicator(Modifier.padding(10.dp).size(24.dp))
            }
        }
    }
}
