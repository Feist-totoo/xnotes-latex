package com.xnotes.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.xnotes.core.geometry.*
import com.xnotes.core.model.*
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolConfig
import com.xnotes.platform.*
import kotlinx.coroutines.*

private data class InkEdit(val ink: List<Stroke>, val latex: String, val manual: Boolean)

/** Draft edits remain private until one guarded, undoable replacement commits. */
@Composable
fun FormulaInkEditor(draft: FormulaDraft, anchor: Rect?, dismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val initial = remember(draft) { draft.editableInk() }
    val box = remember(draft) { initial.map { it.bounds() }.reduceOrNull(Rect::union)?.outset(16.0) ?: draft.bounds }
    var edit by remember(draft) { mutableStateOf(InkEdit(initial, draft.initialLatex, initial.isEmpty())) }
    val undo = remember(draft) { mutableStateListOf<InkEdit>() }
    val redo = remember(draft) { mutableStateListOf<InkEdit>() }
    fun change(next: InkEdit) { if (next != edit) { undo.add(edit); if (undo.size > 100) undo.removeAt(0); edit = next; redo.clear() } }
    var latexOpen by remember { mutableStateOf(initial.isEmpty()) }
    var erase by remember { mutableStateOf(false) }
    var live by remember { mutableStateOf<List<Pt>>(emptyList()) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    Popup(alignment=Alignment.TopEnd, onDismissRequest={ if (!working) dismiss() }, properties=PopupProperties(focusable=true)) {
        BoxWithConstraints(Modifier.fillMaxSize().imePadding().padding(12.dp), contentAlignment=Alignment.TopEnd) {
            val width = with(density) { ((anchor?.w ?: 300.0)*1.5).toFloat().toDp() }.coerceIn(280.dp, maxWidth.coerceAtLeast(280.dp))
            val height = with(density) { ((anchor?.h ?: 120.0)*1.5).toFloat().toDp() }.coerceIn(130.dp, (maxHeight-200.dp).coerceAtLeast(130.dp))
            Surface(Modifier.width(width).testTag("formula-ink-editor"), shape=MaterialTheme.shapes.medium, tonalElevation=8.dp, shadowElevation=8.dp) {
                Column(Modifier.padding(start=12.dp, bottom=12.dp, end=4.dp, top=4.dp)) {
                    if (latexOpen) OutlinedTextField(value=edit.latex, onValueChange={ if (it.length <= 4096) change(InkEdit(emptyList(), it, true)) },
                        enabled=!working, label={ Text("LaTeX（修改后仅保留源码）") }, modifier=Modifier.fillMaxWidth().testTag("formula-edit-latex"), maxLines=4)
                    if (!edit.manual) Canvas(Modifier.fillMaxWidth().height(height).background(Color.White).clipToBounds().testTag("formula-edit-ink").pointerInput(working, edit.manual, erase) {
                        if (working || edit.manual) return@pointerInput
                        awaitEachGesture {
                            val down=awaitFirstDown(); down.consume()
                            fun point(p: Offset) = Pt(box.x+p.x/size.width*box.w, box.y+p.y/size.height*box.h)
                            val points=ArrayList<Pt>(); points.add(point(down.position)); live=points.toList()
                            var cancelled=false
                            while (true) {
                                val event=awaitPointerEvent(); event.changes.forEach { it.consume() }
                                if (event.changes.count { it.pressed }>1) { cancelled=true; break }
                                val c=event.changes.firstOrNull { it.id==down.id } ?: break
                                if (points.size<20000) points.add(point(c.position))
                                live=points.toList()
                                if (!c.pressed) break
                            }
                            if (!cancelled) {
                                val ink = if (erase) {
                                    var remaining=edit.ink
                                    for (p in points) remaining=remaining.flatMap { it.erasedBy(p.x,p.y,box.w/size.width*14) ?: listOf(it) }
                                    remaining
                                } else edit.ink + Stroke(Tool.PEN, ToolConfig(rgba=draft.color, baseWidth=2.5), points.map { Sample(it.x,it.y,1.0) })
                                if (ink.size<=256 && ink.sumOf { it.sampleCount.toLong() }<=100000) change(edit.copy(ink=ink))
                            }
                            live=emptyList()
                        }
                    }) {
                        fun at(p: Pt)=Offset(((p.x-box.x)/box.w*size.width).toFloat(),((p.y-box.y)/box.h*size.height).toFloat())
                        fun trace(points: List<Pt>, color: Color, width: Float) {
                            if (points.isEmpty()) return
                            if (points.size == 1) { drawCircle(color,width/2,at(points.first())); return }
                            val path=Path(); points.forEachIndexed { i,p -> val q=at(p); if(i==0) path.moveTo(q.x,q.y) else path.lineTo(q.x,q.y) }
                            drawPath(path,color,style=DrawStroke(width))
                        }
                        edit.ink.forEach { s -> s.visibleRuns().forEach { trace(it,Color(s.config.rgba.r,s.config.rgba.g,s.config.rgba.b,s.config.rgba.a), (s.config.baseWidth/box.w*size.width).toFloat()) } }
                        trace(live,if(erase) Color.Gray else Color(draft.color.r,draft.color.g,draft.color.b,draft.color.a),2.dp.toPx())
                    }
                    if (edit.manual) Text("原笔迹已清除，仅编辑 LaTeX；可撤销恢复。",style=MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(modifier=Modifier.width(40.dp), contentPadding=PaddingValues(0.dp), enabled=!working && undo.isNotEmpty(),onClick={ redo.add(edit); edit=undo.removeAt(undo.lastIndex) }) { Text("↶") }
                        TextButton(modifier=Modifier.width(40.dp), contentPadding=PaddingValues(0.dp), enabled=!working && redo.isNotEmpty(),onClick={ undo.add(edit); edit=redo.removeAt(redo.lastIndex) }) { Text("↷") }
                        TextButton(modifier=Modifier.width(40.dp), contentPadding=PaddingValues(0.dp), enabled=!working && !edit.manual,onClick={ erase=!erase }) { Text(if(erase) "笔" else "擦") }
                        TextButton(modifier=Modifier.width(40.dp), contentPadding=PaddingValues(0.dp), enabled=!working,onClick={ latexOpen=!latexOpen }) { Text("TeX") }
                        TextButton(modifier=Modifier.width(40.dp), contentPadding=PaddingValues(0.dp), enabled=!working,onClick=dismiss) { Text("×") }
                        TextButton(modifier=Modifier.width(40.dp).testTag("formula-edit-apply"), contentPadding=PaddingValues(0.dp), enabled=!working && (edit.manual || edit.ink.isNotEmpty()),onClick={
                            working=true; error=false
                            scope.launch {
                                var block: ImageItem?=null
                                try {
                                    val source=if(edit.manual) edit.latex else OfflineMathRecognizer.recognize(context,strokes=edit.ink.flatMap { it.visibleRuns() },config=OfflineMathSettings.load(context)).latex
                                    val rendered=withContext(Dispatchers.Default) { draft.render(source) }
                                    try { withContext(Dispatchers.IO) { block=draft.block(rendered,false) } } finally { rendered.bitmap.recycle() }
                                    val item=block!!
                                    // Frozen source is expressed relative to the newly rendered block.
                                    val updated = ImageItem(item.image,item.rect,item.orientation,item.angle,item.flip,item.part,
                                        item.formula!!.copy(ink=if(edit.manual) null else FormulaInk.valid(item.rect, edit.ink)))
                                    updated.group=item.group; updated.sync=item.sync
                                    ensureActive(); check(draft.apply(updated,false)); block=null; dismiss()
                                } catch(c: CancellationException) { throw c }
                                catch(_: Exception) { error=true }
                                finally { block?.image?.file?.delete(); working=false }
                            }
                        }) { Text("✓") }
                    }
                    if(working) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if(error) Text("未能渲染，修改已保留，请重试。",color=MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
