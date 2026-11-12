package com.xnotes.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.xnotes.R
import com.xnotes.core.geometry.Pt
import com.xnotes.core.model.ImageData
import com.xnotes.platform.*
import kotlinx.coroutines.*
import java.io.File

@Composable
fun MathPadPanel(editor: Editor, modifier: Modifier = Modifier) {
    val state = editor.mathPad
    val block = state.current
    val line = block.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by OfflineMathModel.status.collectAsState()
    var config by remember { mutableStateOf(OfflineMathSettings.load(context)) }
    var settings by remember { mutableStateOf(false) }
    var writing by remember(line) { mutableStateOf(false) }
    var live by remember(line) { mutableStateOf<List<Pt>>(emptyList()) }
    var recognizing by remember { mutableStateOf(false) }
    var committing by remember { mutableStateOf(false) }
    var error by remember { mutableIntStateOf(0) }
    var request by remember { mutableStateOf<Pair<MathPadLine, Int>?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var elapsed by remember { mutableLongStateOf(0) }
    val installed = remember(status) { OfflineMathModel.installed(context) }
    LaunchedEffect(line, line.revision, writing, config, status.revision, attempt) {
        recognizing = false
        val explicit = request == (line to line.revision)
        if (writing || line.strokes.isEmpty() || (line.manual && !explicit) || !installed ||
            (line.ready && !explicit) || (!config.automatic && !explicit)) return@LaunchedEffect
        val expected = line.revision
        if (explicit) request = null
        if (!explicit) delay(config.pauseMs.toLong())
        recognizing = true; error = 0
        try {
            val result = OfflineMathRecognizer.recognize(context, strokes = line.strokes, config = config)
            if (line.accept(result.latex, expected)) elapsed = result.elapsedMs
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { if (line.revision == expected) error = R.string.math_recognition_failed }
        finally { recognizing = false }
    }
    val source = block.source()
    var rendered by remember(block) { mutableStateOf<RenderedFormula?>(null) }
    LaunchedEffect(source) {
        if (source == null) return@LaunchedEffect
        delay(120)
        try { rendered = withContext(Dispatchers.Default) { FormulaTypesetter.render(source) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = R.string.formula_invalid }
    }
    val ready = rendered?.takeIf { source != null && it.source.latex == source }
    Surface(modifier.fillMaxHeight().testTag("math-pad-panel"), tonalElevation = 3.dp) {
        Column(Modifier.padding(12.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.math_pad), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { settings = true }, enabled = !committing) { Text(stringResource(R.string.math_settings_short)) }
                TextButton(onClick = { state.open = false }, enabled = !committing) { Text("×") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(enabled = state.activeBlock > 0 && !committing, onClick = { state.activeBlock-- }) { Text("‹") }
                Text(stringResource(R.string.math_block_number, state.activeBlock + 1, state.blocks.size), Modifier.weight(1f))
                TextButton(enabled = state.activeBlock < state.blocks.lastIndex && !committing, onClick = { state.activeBlock++ }) { Text("›") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(enabled = block.activeLine > 0 && !committing, onClick = { block.activeLine-- }) { Text("‹") }
                Text(stringResource(R.string.math_line_number, block.activeLine + 1, block.lines.size), Modifier.weight(1f))
                TextButton(enabled = block.activeLine < block.lines.lastIndex && !committing, onClick = { block.activeLine++ }) { Text("›") }
            }
            Canvas(Modifier.fillMaxWidth().aspectRatio(3.2f).heightIn(min = 100.dp).clipToBounds().background(Color.White)
                .testTag("math-pad-ink").pointerInput(line, committing) {
                    if (committing) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(); down.consume()
                        fun normalize(p: Offset) = Pt((p.x / size.width * 1000).toDouble().coerceIn(0.0, 1000.0), (p.y / size.height * 312.5).coerceIn(0.0, 312.5))
                        val points = ArrayList<Pt>(); points.add(normalize(down.position))
                        writing = true; live = points.toList(); error = 0
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                event.changes.forEach { it.consume() }
                                val next = normalize(change.position)
                                if (points.last().distanceTo(next) > .2 && points.size < 20_000) points.add(next)
                                live = points.toList()
                                if (!change.pressed) {
                                    if (line.strokes.sumOf { it.size } + points.size <= 100_000) line.ink(line.strokes + listOf(points.toList()))
                                    break
                                }
                            }
                        } finally { live = emptyList(); writing = false }
                    }
                }) {
                val paths = line.strokes + listOf(live)
                for (trace in paths) {
                    if (trace.isEmpty()) continue
                    fun at(p: Pt) = Offset((p.x / 1000 * size.width).toFloat(), (p.y / 312.5 * size.height).toFloat())
                    if (trace.size == 1) drawCircle(Color.Black, 1.5.dp.toPx(), at(trace.first()))
                    else drawPath(Path().apply { trace.forEachIndexed { i, p -> val q = at(p); if (i == 0) moveTo(q.x, q.y) else lineTo(q.x, q.y) } }, Color.Black, style = Stroke(2.dp.toPx()))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(enabled = line.ready && !writing && !committing, onClick = { block.newLine() }) { Text(stringResource(R.string.math_new_line)) }
                OutlinedButton(enabled = !writing && !committing && state.blocks.size < 32, onClick = state::newBlock) { Text(stringResource(R.string.math_new_block)) }
                Button(modifier = Modifier.testTag("math-pad-insert"), enabled = ready != null && !writing && !committing, onClick = {
                    val result = ready ?: return@Button
                    val targetCanvas = editor.canvasOpen
                    val targetDocument = if (targetCanvas) editor.infinite.document else editor.state.document
                    committing = true; error = 0
                    scope.launch {
                        var file: File? = null
                        try {
                            withContext(NonCancellable + Dispatchers.IO) {
                                file = File.createTempFile("formula-pad-", ".png", editor.imageAssetDirectory)
                                file!!.outputStream().use { check(result.bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                            }
                            ensureActive()
                            check(editor.canvasOpen == targetCanvas && targetDocument === (if (targetCanvas) editor.infinite.document else editor.state.document))
                            check(block.source() == source)
                            val image = ImageData(file!!, result.bitmap.width, result.bitmap.height)
                            val item = if (targetCanvas) editor.infinite.insertImageData(image, formula = result.source)
                                else editor.insertImageData(image, formula = result.source)
                            check(item != null)
                            file = null; state.inserted(block)
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { error = R.string.math_insert_failed }
                        finally { file?.delete(); committing = false }
                    }
                }) { Text(stringResource(R.string.insert)) }
            }
            Row {
                TextButton(enabled = line.strokes.isNotEmpty() && !writing && !committing, onClick = { line.ink(line.strokes.dropLast(1)) }) { Text(stringResource(R.string.undo)) }
                TextButton(enabled = !writing && !committing, onClick = { line.ink(emptyList()) }) { Text(stringResource(R.string.math_clear_line)) }
                TextButton(enabled = installed && line.strokes.isNotEmpty() && !writing && !committing, onClick = {
                    request = line to line.revision; attempt++
                }) { Text(stringResource(R.string.math_recognize)) }
            }
            if (recognizing) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (!installed) TextButton(onClick = { settings = true }) { Text(stringResource(R.string.math_pack_needed)) }
            Box(Modifier.fillMaxWidth().height(160.dp).background(Color.White).padding(8.dp), contentAlignment = Alignment.Center) {
                rendered?.let { Image(it.bitmap.asImageBitmap(), stringResource(R.string.formula_preview), Modifier.fillMaxSize(), contentScale = ContentScale.Fit,
                    alpha = if (ready != null && !writing) 1f else .35f) }
            }
            if (elapsed > 0) Text(stringResource(R.string.math_elapsed, elapsed), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(value = line.latex, onValueChange = { if (it.length <= 4096) line.edit(it) }, enabled = !committing && !writing,
                label = { Text(stringResource(R.string.math_line_latex)) }, modifier = Modifier.fillMaxWidth().testTag("math-pad-source"), minLines = 2, maxLines = 4)
            if (error != 0) Text(stringResource(error), color = MaterialTheme.colorScheme.error)
            Text(stringResource(R.string.math_review_note), style = MaterialTheme.typography.bodySmall)
        }
    }
    if (settings) OfflineMathSettingsDialog { settings = false; config = OfflineMathSettings.load(context) }
}
