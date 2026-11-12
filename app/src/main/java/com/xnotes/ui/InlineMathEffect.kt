package com.xnotes.ui

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.xnotes.R
import com.xnotes.core.model.ImageItem
import com.xnotes.platform.*
import kotlinx.coroutines.*

/** No separate input surface: observes only committed ink from the active whiteboard. */
@Composable
fun InlineMathEffect(editor: Editor) {
    val state = editor.inlineMath
    val context = LocalContext.current
    LaunchedEffect(state.enabled, state.revision, editor.noteOpen, editor.canvasOpen) {
        state.busy = false
        if (!state.enabled || state.touching || state.waitingForEdit || state.strokes.isEmpty() || !editor.noteOpen) return@LaunchedEffect
        val version = state.revision
        val config = OfflineMathSettings.load(context)
        delay(config.pauseMs.toLong())
        if (!OfflineMathModel.installed(context)) { editor.message = context.getString(R.string.math_pack_needed); return@LaunchedEffect }
        val draft = editor.inlineFormulaDraft() ?: return@LaunchedEffect
        var block: ImageItem? = null
        state.busy = true
        try {
            val result = OfflineMathRecognizer.recognize(context, strokes = draft.recognitionStrokes(), config = config)
            val rendered = withContext(Dispatchers.Default) { draft.render(result.latex) }
            try { withContext(Dispatchers.IO) { block = draft.block(rendered, false) } }
            finally { rendered.bitmap.recycle() }
            ensureActive()
            if (version == state.revision && state.enabled && draft.apply(block!!, false)) {
                block = null; state.clear()
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { editor.message = context.getString(R.string.math_inline_failed) }
        finally { block?.image?.file?.delete(); state.busy = false }
    }
}
