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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.xnotes.R
import com.xnotes.core.formula.LatexSource
import com.xnotes.platform.FormulaDraft
import com.xnotes.platform.FormulaPacks
import com.xnotes.platform.RenderedFormula
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
    var managingPacks by remember { mutableStateOf(false) }
    var pendingTemplate by remember { mutableStateOf<String?>(null) }
    var ink by remember(draft) { mutableStateOf<Bitmap?>(null) }
    var rendered by remember(draft) { mutableStateOf<RenderedFormula?>(null) }
    var committing by remember { mutableStateOf(false) }
    var keepInk by remember { mutableStateOf(false) }
    var renderFailed by remember { mutableStateOf(false) }
    var stale by remember { mutableStateOf(false) }
    var preparing by remember(draft) { mutableStateOf(true) }
    var selectionFailed by remember(draft) { mutableStateOf(false) }
    LaunchedEffect(draft) {
        try { ink = withContext(Dispatchers.Default) { draft.inkBitmap() } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { selectionFailed = true }
        finally { preparing = false }
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
                                if (preparing) CircularProgressIndicator(Modifier.size(28.dp))
                                else ready?.let { Image(it.bitmap.asImageBitmap(), stringResource(R.string.formula_preview), Modifier.fillMaxSize(), contentScale = ContentScale.Fit, colorFilter = ColorFilter.tint(Color.Black)) }
                            }
                        }
                    }
                    if (draft.originalFormula == null) OfflineFormulaPanel(ink, remember(draft) { draft.recognitionStrokes() }, !preparing && !committing) { alternative ->
                        field = TextFieldValue(alternative, TextRange(alternative.length))
                    }
                    if (selectionFailed) Text(stringResource(R.string.formula_selection_failed), color = MaterialTheme.colorScheme.error)
                    Text(stringResource(R.string.formula_review), style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(value = field, onValueChange = { if (it.text.length <= 4096) { field = it } },
                        label = { Text("LaTeX") }, enabled = !committing, minLines = 3, maxLines = 7,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                        isError = (problem != null && problem != "empty") || renderFailed,
                        supportingText = { if ((problem != null && problem != "empty") || renderFailed) Text(stringResource(R.string.formula_invalid)) },
                        modifier = Modifier.fillMaxWidth())
                    FormulaPackPanel(catalog, selectedPacks, cleaned, !committing,
                        automatic = automaticPacks,
                        onAutomatic = { automaticPacks = it; FormulaPacks.setAutomatic(context, it) },
                        onManage = { managingPacks = true },
                        onSelect = { selectedPacks = it; FormulaPacks.save(context, it) },
                        onInsert = { snippet ->
                            val selection = field.selection
                            val next = latex.replaceRange(selection.min, selection.max, snippet)
                            if (next.length <= 4096) { field = TextFieldValue(next, TextRange(selection.min + snippet.length)) }
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
                    if (stale) Text(stringResource(R.string.formula_stale), color = MaterialTheme.colorScheme.error)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss, enabled = !committing) { Text(stringResource(R.string.cancel)) }
                    Spacer(Modifier.width(8.dp))
                    Button(enabled = ready != null && !committing, onClick = {
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
                field = TextFieldValue(template, TextRange(template.length)); pendingTemplate = null
            }) { Text(stringResource(R.string.formula_template_apply)) } },
            dismissButton = { TextButton(onClick = { pendingTemplate = null }) { Text(stringResource(R.string.cancel)) } })
    }
    if (managingPacks) FormulaPackManager(source = cleaned, onChanged = {
        catalog = FormulaPacks.load(context)
        selectedPacks = FormulaPacks.selected(context, catalog)
    }, onDismiss = { managingPacks = false })
}
