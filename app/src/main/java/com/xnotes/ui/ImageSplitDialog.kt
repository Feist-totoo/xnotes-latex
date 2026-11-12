package com.xnotes.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.xnotes.R
import com.xnotes.core.geometry.Pt
import com.xnotes.core.model.ImageSliceGeometry
import com.xnotes.platform.ImageSplitDraft
import kotlinx.coroutines.*
import kotlin.math.roundToInt

@Composable
fun ImageSplitDialog(draft: ImageSplitDraft, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var bitmap by remember(draft) { mutableStateOf<Bitmap?>(null) }
    var a by remember(draft) { mutableStateOf<Pt?>(null) }
    var b by remember(draft) { mutableStateOf<Pt?>(null) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(draft) {
        try { bitmap = withContext(Dispatchers.IO) { draft.preview() } }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { error = R.string.image_split_failed }
        finally { loading = false }
    }
    val pieces = if (a != null && b != null) ImageSliceGeometry.split(draft.outline, a!!, b!!) else null
    val primary = MaterialTheme.colorScheme.primary
    Dialog(onDismissRequest = { if (!working) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.widthIn(max = 900.dp).fillMaxWidth(.93f).fillMaxHeight(.88f), shape = MaterialTheme.shapes.extraLarge) {
            Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.image_split), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.image_split_help), style = MaterialTheme.typography.bodyMedium)
                Box(Modifier.fillMaxWidth().weight(1f).clipToBounds().background(Color(0xffe4e4e4))) {
                    bitmap?.let { source ->
                        Canvas(Modifier.fillMaxSize().testTag("image-split-preview").pointerInput(source, working) {
                            if (working) return@pointerInput
                            fun point(p: Offset): Pt {
                                val scale = minOf(size.width.toFloat() / source.width, size.height.toFloat() / source.height)
                                val w = source.width * scale; val h = source.height * scale
                                return Pt(((p.x - (size.width - w) / 2) / w).toDouble(), ((p.y - (size.height - h) / 2) / h).toDouble())
                            }
                            detectDragGestures(onDragStart = { a = point(it); b = a; error = 0 },
                                onDragCancel = { a = null; b = null }, onDrag = { change, _ ->
                                    change.consume(); b = point(change.position)
                                })
                        }) {
                            val scale = minOf(size.width / source.width, size.height / source.height)
                            val w = source.width * scale; val h = source.height * scale
                            val left = (size.width - w) / 2; val top = (size.height - h) / 2
                            drawImage(source.asImageBitmap(), dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                                dstSize = IntSize(w.roundToInt(), h.roundToInt()))
                            fun position(p: Pt) = Offset(left + p.x.toFloat() * w, top + p.y.toFloat() * h)
                            pieces?.forEachIndexed { index, polygon ->
                                val path = Path().apply {
                                    polygon.forEachIndexed { i, pt -> val p = position(pt); if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }; close()
                                }
                                drawPath(path, (if (index == 0) Color(0xff007ba7) else Color(0xffd46b00)).copy(alpha = .16f))
                            }
                            if (a != null && b != null && a!!.distanceTo(b!!) > 1e-6) {
                                // Extend the guide to the image edges, matching the actual infinite-line cut.
                                val crossings = ImageSliceGeometry.split(ImageSliceGeometry.square, a!!, b!!)?.firstOrNull()
                                    ?.filter { kotlin.math.abs(ImageSliceGeometry.side(a!!, b!!, it)) < 1e-6 }
                                if (crossings != null && crossings.size >= 2) drawLine(primary, position(crossings.first()), position(crossings.last()), 3.dp.toPx())
                            }
                        }
                    }
                    if (loading || working) CircularProgressIndicator(Modifier.align(androidx.compose.ui.Alignment.Center))
                }
                Text(stringResource(if (pieces != null) R.string.image_split_ready else R.string.image_split_draw), style = MaterialTheme.typography.bodySmall)
                if (error != 0) Text(stringResource(error), color = MaterialTheme.colorScheme.error)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    TextButton(enabled = !working && a != null, onClick = { a = null; b = null; error = 0 }) { Text(stringResource(R.string.image_split_reset)) }
                    Spacer(Modifier.weight(1f))
                    TextButton(enabled = !working, onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                    Button(modifier = Modifier.testTag("image-split-apply"), enabled = !working && bitmap != null && pieces != null, onClick = {
                        val from = a ?: return@Button; val to = b ?: return@Button
                        working = true; error = 0
                        scope.launch {
                            var result: List<com.xnotes.core.model.ImageItem>? = null
                            try {
                                // Finish a started file operation so cancellation cannot leak generated pieces.
                                withContext(NonCancellable + Dispatchers.IO) { result = draft.split(from, to) }
                                ensureActive()
                                if (draft.apply(result!!)) { result = null; onDismiss() }
                                else error = R.string.image_split_stale
                            } catch (e: CancellationException) { throw e }
                            catch (_: Exception) { error = R.string.image_split_failed }
                            finally { result?.forEach { it.image.file.delete() }; working = false }
                        }
                    }) { Text(stringResource(R.string.image_split_apply)) }
                }
            }
        }
    }
}
