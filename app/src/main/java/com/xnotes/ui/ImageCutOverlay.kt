package com.xnotes.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.xnotes.R
import com.xnotes.core.geometry.*
import com.xnotes.core.model.ImageSliceGeometry
import kotlinx.coroutines.*

@Composable
fun ImageCutOverlay(session: ImageCutSession?, dismiss: () -> Unit) {
    if (session == null) return
    if (!session.valid()) {
        LaunchedEffect(session) { dismiss() }
        return
    }
    val scope = rememberCoroutineScope()
    var a by remember(session) { mutableStateOf<Pt?>(null) }
    var b by remember(session) { mutableStateOf<Pt?>(null) }
    var working by remember(session) { mutableStateOf(false) }
    var dragging by remember(session) { mutableStateOf(false) }
    var failed by remember(session) { mutableStateOf(false) }
    val parts = if (a != null && b != null) ImageSliceGeometry.split(session.draft.outline, a!!, b!!) else null
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.tertiary
    BackHandler { if (!working) dismiss() }
    Box(Modifier.fillMaxSize().clipToBounds().testTag("image-cut-overlay")) {
        Canvas(Modifier.fillMaxSize().testTag("image-cut-canvas").pointerInput(session, working) {
            if (working) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown(); down.consume()
                val point = session.local(Pt(down.position.x.toDouble(), down.position.y.toDouble()))
                if (!session.valid()) { dismiss(); return@awaitEachGesture }
                if (!Geometry.pointInPolygon(session.draft.outline, point)) return@awaitEachGesture
                a = point; b = point; dragging = true; failed = false
                try {
                    while (true) {
                        val event = awaitPointerEvent()
                        event.changes.forEach { it.consume() }
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        b = session.local(Pt(change.position.x.toDouble(), change.position.y.toDouble()))
                        if (event.changes.count { it.pressed } > 1) { a = null; b = null; break }
                        if (!change.pressed) break
                    }
                } finally { dragging = false }
            }
        }) {
            fun pos(p: Pt) = session.screen(p).let { Offset(it.x.toFloat(), it.y.toFloat()) }
            parts?.forEachIndexed { i, polygon ->
                val path = Path().apply { polygon.forEachIndexed { j, point ->
                    val p = pos(point); if (j == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
                }; close() }
                drawPath(path, (if (i == 0) primary else secondary).copy(alpha = .30f))
            }
            if (a != null && b != null) {
                val edge = parts?.first()?.filter { kotlin.math.abs(ImageSliceGeometry.side(a!!, b!!, it)) < 1e-6 }
                val start = edge?.takeIf { it.size >= 2 }?.first() ?: a!!
                val end = edge?.takeIf { it.size >= 2 }?.last() ?: b!!
                drawLine(primary, pos(start), pos(end), 2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 7.dp.toPx())))
                drawCircle(primary, 3.dp.toPx(), pos(a!!)); drawCircle(primary, 3.dp.toPx(), pos(b!!))
            }
        }
        Surface(Modifier.align(Alignment.BottomCenter).padding(16.dp), shape = MaterialTheme.shapes.large, tonalElevation = 6.dp) {
            Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(if (failed) R.string.image_split_failed else R.string.image_cut_inline_help), style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(enabled = !working, onClick = dismiss) { Text(stringResource(R.string.cancel)) }
                    TextButton(enabled = !working && a != null, onClick = { a = null; b = null }) { Text(stringResource(R.string.image_split_reset)) }
                    Button(modifier = Modifier.testTag("image-cut-apply"), enabled = !working && !dragging && parts != null, onClick = {
                        working = true
                        scope.launch {
                            var created: List<com.xnotes.core.model.ImageItem>? = null
                            try {
                                check(session.valid())
                                withContext(Dispatchers.IO) { created = session.draft.split(a!!, b!!) }
                                ensureActive()
                                check(session.valid() && session.draft.apply(created!!))
                                created = null; dismiss()
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { failed = true }
                            finally { created?.forEach { it.image.file.delete() }; working = false }
                        }
                    }) { Text(stringResource(R.string.image_cut_apply)) }
                    if (working) CircularProgressIndicator(Modifier.padding(start = 8.dp).size(22.dp))
                }
            }
        }
    }
}
