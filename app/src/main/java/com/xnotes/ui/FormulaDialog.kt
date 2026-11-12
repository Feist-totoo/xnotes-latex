package com.xnotes.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.xnotes.R
import com.xnotes.core.formula.LatexSource
import com.xnotes.core.formula.FormulaCandidate
import com.xnotes.core.formula.FormulaCandidateRanking
import com.xnotes.platform.FormulaDraft
import com.xnotes.platform.FormulaPacks
import com.xnotes.platform.LocalFormulaRecognizer
import com.xnotes.platform.PrecisionFormulaRecognizer
import com.xnotes.platform.RenderedFormula
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun FormulaDialog(draft: FormulaDraft, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var field by remember(draft) { mutableStateOf(TextFieldValue(draft.initialLatex)) }
    val latex = field.text
    var catalog by remember(context) { mutableStateOf(FormulaPacks.load(context)) }
    var selectedPacks by remember(context) { mutableStateOf(FormulaPacks.selected(context, catalog)) }
    var automaticPacks by remember(context) { mutableStateOf(FormulaPacks.automatic(context)) }
    var recognitionPacks by remember(context) { mutableStateOf(FormulaPacks.recognitionEnabled(context)) }
    var managingPacks by remember { mutableStateOf(false) }
    var pendingTemplate by remember { mutableStateOf<String?>(null) }
    var ink by remember(draft) { mutableStateOf<Bitmap?>(null) }
    var rendered by remember(draft) { mutableStateOf<RenderedFormula?>(null) }
    var recognizing by remember(draft) { mutableStateOf(draft.originalFormula == null) }
    var committing by remember { mutableStateOf(false) }
    var keepInk by remember { mutableStateOf(false) }
    var recognitionFailed by remember { mutableStateOf(false) }
    var uncertain by remember { mutableStateOf(false) }
    var renderFailed by remember { mutableStateOf(false) }
    var stale by remember { mutableStateOf(false) }
    var generation by remember { mutableIntStateOf(0) }
    var candidates by remember(draft) { mutableStateOf<List<FormulaCandidate>>(emptyList()) }
    var automaticText by remember(draft) { mutableStateOf(draft.initialLatex) }
    var userEdited by remember(draft) { mutableStateOf(false) }
    var reviewing by remember(draft) { mutableStateOf(false) }
    var reviewJob by remember(draft) { mutableStateOf<Job?>(null) }
    var preciseLatex by remember(draft) { mutableStateOf<String?>(null) }
    var reviewFailed by remember(draft) { mutableStateOf(false) }
    val ranking = remember(candidates, catalog, selectedPacks, automaticPacks, recognitionPacks) {
        FormulaCandidateRanking.rank(candidates, if (recognitionPacks) catalog.filter { it.id in selectedPacks } else emptyList(), automaticPacks)
    }
    LaunchedEffect(ranking) {
        val next = ranking.candidates.firstOrNull()?.latex ?: return@LaunchedEffect
        // Preserve edits and explicitly chosen alternatives when changing packs.
        if (!userEdited && field.text == automaticText) field = TextFieldValue(next, TextRange(next.length))
        automaticText = next
    }

    LaunchedEffect(draft, generation) {
        recognizing = draft.originalFormula == null
        recognitionFailed = false
        userEdited = false
        try {
            ink = withContext(Dispatchers.Default) { draft.inkBitmap() }
            if (draft.originalFormula != null) return@LaunchedEffect
            val source = ink ?: error("Empty selection")
            val pen = withContext(Dispatchers.Default) { draft.recognitionStrokes() }
            val result = LocalFormulaRecognizer.recognize(context, source, pen.takeIf { it.isNotEmpty() })
            if (result.candidates.isEmpty()) {
                field = TextFieldValue(result.latex, TextRange(result.latex.length))
                automaticText = result.latex
            } else {
                automaticText = field.text
                candidates = result.candidates
            }
            uncertain = result.uncertain
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            recognitionFailed = true
        } finally {
            recognizing = false
        }
    }

    val cleaned = LatexSource.clean(latex)
    val problem = LatexSource.problem(cleaned)
    LaunchedEffect(cleaned) {
        rendered = null
        renderFailed = false
        if (problem != null) return@LaunchedEffect
        delay(250)
        try {
            rendered = withContext(Dispatchers.Default) { draft.render(cleaned) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            renderFailed = true
        }
    }

    val ready = rendered?.takeIf { it.source.latex == cleaned }
    Dialog(onDismissRequest = { if (!committing) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp,
            modifier = Modifier.imePadding().widthIn(max = 820.dp).fillMaxWidth(0.95f)
                .heightIn(max = (LocalConfiguration.current.screenHeightDp - 32).coerceAtLeast(240).dp)) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(if (draft.originalFormula == null) R.string.formula_title else R.string.formula_edit), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.formula_local), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(stringResource(R.string.formula_original), style = MaterialTheme.typography.labelLarge)
                            Box(Modifier.fillMaxWidth().height(128.dp).background(Color.White, MaterialTheme.shapes.small).padding(10.dp), contentAlignment = Alignment.Center) {
                                ink?.let { Image(it.asImageBitmap(), stringResource(R.string.formula_original), Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                            }
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(stringResource(R.string.formula_preview), style = MaterialTheme.typography.labelLarge)
                            Box(Modifier.fillMaxWidth().height(128.dp).background(Color.White, MaterialTheme.shapes.small).padding(10.dp), contentAlignment = Alignment.Center) {
                                if (recognizing) CircularProgressIndicator(Modifier.size(28.dp))
                                else ready?.let { Image(it.bitmap.asImageBitmap(), stringResource(R.string.formula_preview), Modifier.fillMaxSize(), contentScale = ContentScale.Fit, colorFilter = ColorFilter.tint(Color.Black)) }
                            }
                        }
                    }
                    Text(stringResource(when {
                        recognizing -> R.string.formula_recognizing
                        recognitionFailed -> R.string.formula_recognition_failed
                        uncertain -> R.string.formula_uncertain
                        else -> R.string.formula_review
                    }), style = MaterialTheme.typography.bodyMedium)
                    if (draft.originalFormula == null && draft.hasPenInput) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(enabled = !recognizing && !reviewing && !committing && ink != null, onClick = {
                                val source = ink ?: return@OutlinedButton
                                reviewing = true; reviewFailed = false
                                reviewJob = scope.launch {
                                    try {
                                        preciseLatex = PrecisionFormulaRecognizer.recognize(context, source).latex
                                    } catch (cancelled: CancellationException) { throw cancelled }
                                    catch (_: Exception) { reviewFailed = true }
                                    finally { reviewing = false }
                                }
                            }) { Text(stringResource(R.string.formula_precision)) }
                            if (reviewing) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                TextButton(onClick = { reviewJob?.cancel() }) { Text(stringResource(R.string.cancel)) }
                            }
                        }
                        Text(stringResource(if (reviewing) R.string.formula_precision_working else R.string.formula_precision_help),
                            style = MaterialTheme.typography.bodySmall)
                        if (reviewFailed) Text(stringResource(R.string.formula_precision_failed), color = MaterialTheme.colorScheme.error)
                        preciseLatex?.let { alternative ->
                            Text(alternative, fontFamily = FontFamily.Monospace, maxLines = 5, modifier = Modifier.testTag("formula-precision-result"))
                            TextButton(enabled = !reviewing && !committing, onClick = {
                                field = TextFieldValue(alternative, TextRange(alternative.length)); userEdited = true
                                recognitionFailed = false; uncertain = true
                            }) { Text(stringResource(R.string.formula_precision_use)) }
                        }
                    }
                    if (ranking.packageIds.isNotEmpty()) {
                        val chinese = LocalConfiguration.current.locales[0].language == "zh"
                        Text((if (chinese) "候选评分：" else "Recognition scoring: ") +
                            catalog.filter { it.id in ranking.packageIds }.joinToString { it.name(chinese) },
                            style = MaterialTheme.typography.bodySmall)
                    }
                    if (!recognizing && ranking.candidates.size > 1) {
                        val chinese = LocalConfiguration.current.locales[0].language == "zh"
                        Text(if (chinese) "识别候选（点击替换，可编辑）" else "Recognition alternatives (tap to edit)", style = MaterialTheme.typography.labelLarge)
                        ranking.candidates.forEachIndexed { index, candidate ->
                            TextButton(onClick = {
                                field = TextFieldValue(candidate.latex, TextRange(candidate.latex.length))
                                userEdited = true
                            }, enabled = !committing) {
                                Text("${index+1}. ${candidate.latex}", maxLines = 2, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                    OutlinedTextField(value = field, onValueChange = { if (it.text.length <= 4096) { field = it; userEdited = true } },
                        label = { Text("LaTeX") }, enabled = !recognizing && !committing, minLines = 3, maxLines = 7,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                        isError = (problem != null && problem != "empty") || renderFailed,
                        supportingText = { if ((problem != null && problem != "empty") || renderFailed) Text(stringResource(R.string.formula_invalid)) },
                        modifier = Modifier.fillMaxWidth())
                    FormulaPackPanel(catalog, selectedPacks, cleaned, !recognizing && !committing,
                        recognition = recognitionPacks,
                        onRecognition = { recognitionPacks = it; FormulaPacks.setRecognitionEnabled(context,it) },
                        automatic = automaticPacks,
                        onAutomatic = { automaticPacks = it; FormulaPacks.setAutomatic(context, it) },
                        onManage = { managingPacks = true },
                        onSelect = { selectedPacks = it; FormulaPacks.save(context, it) },
                        onInsert = { snippet ->
                            val selection = field.selection
                            val next = latex.replaceRange(selection.min, selection.max, snippet)
                            if (next.length <= 4096) { field = TextFieldValue(next, TextRange(selection.min + snippet.length)); userEdited = true }
                        }, onTemplate = { pendingTemplate = it })
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (draft.originalFormula == null) {
                            Checkbox(checked = keepInk, onCheckedChange = { keepInk = it }, enabled = !committing)
                            Text(stringResource(R.string.formula_keep), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        } else Spacer(Modifier.weight(1f))
                        TextButton(enabled = latex.isNotBlank(), onClick = {
                            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("LaTeX", cleaned))
                        }) { Text(stringResource(R.string.formula_copy)) }
                    }
                    if (recognitionFailed) TextButton(onClick = { generation++ }, enabled = !recognizing && !reviewing) { Text(stringResource(R.string.formula_retry)) }
                    if (stale) Text(stringResource(R.string.formula_stale), color = MaterialTheme.colorScheme.error)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss, enabled = !committing) { Text(stringResource(R.string.cancel)) }
                    Spacer(Modifier.width(8.dp))
                    Button(enabled = ready != null && !recognizing && !reviewing && !committing, onClick = {
                        val result = ready ?: return@Button
                        committing = true
                        scope.launch {
                            try {
                                val block = withContext(Dispatchers.IO) { draft.block(result, keepInk) }
                                if (draft.apply(block, keepInk && draft.originalFormula == null)) onDismiss()
                                else { stale = true; withContext(Dispatchers.IO) { block.image.file.delete() } }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                stale = true
                            } finally { committing = false }
                        }
                    }) { Text(stringResource(if (draft.originalFormula == null) R.string.formula_insert else R.string.formula_update)) }
                }
            }
        }
    }
    pendingTemplate?.let { template ->
        AlertDialog(onDismissRequest = { pendingTemplate = null },
            title = { Text(stringResource(R.string.formula_template_title)) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.formula_template_description))
                Text(template, fontFamily = FontFamily.Monospace)
            } },
            confirmButton = { TextButton(onClick = {
                field = TextFieldValue(template, TextRange(template.length)); userEdited = true; pendingTemplate = null
            }) { Text(stringResource(R.string.formula_template_apply)) } },
            dismissButton = { TextButton(onClick = { pendingTemplate = null }) { Text(stringResource(R.string.cancel)) } })
    }
    if (managingPacks) FormulaPackManager(source = cleaned, onChanged = {
        catalog = FormulaPacks.load(context)
        selectedPacks = FormulaPacks.selected(context, catalog)
    }, onDismiss = { managingPacks = false })
}
