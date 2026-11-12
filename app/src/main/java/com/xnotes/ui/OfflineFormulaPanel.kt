package com.xnotes.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.xnotes.R
import com.xnotes.core.geometry.Pt
import com.xnotes.platform.*
import kotlinx.coroutines.*

@Composable
fun OfflineFormulaPanel(ink: Bitmap?, strokes: List<List<Pt>>, enabled: Boolean, onApply: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by OfflineMathModel.status.collectAsState()
    var settings by remember { mutableStateOf(false) }
    var result by remember(ink) { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    val installed = remember(status) { OfflineMathModel.installed(context) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.math_offline), style = MaterialTheme.typography.titleSmall)
        Row {
            OutlinedButton(enabled = enabled && ink != null && installed && !working, onClick = {
                working = true; failed = false
                job = scope.launch {
                    try { result = OfflineMathRecognizer.recognize(context, ink, strokes.takeIf { it.isNotEmpty() }).latex }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { failed = true }
                    finally { working = false }
                }
            }) { Text(stringResource(R.string.math_recognize)) }
            TextButton(modifier = Modifier.testTag("offline-settings"), onClick = { settings = true }) { Text(stringResource(R.string.math_settings)) }
            if (working) TextButton(onClick = { job?.cancel() }) { Text(stringResource(R.string.cancel)) }
        }
        if (working) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (!installed) Text(stringResource(R.string.math_pack_needed), style = MaterialTheme.typography.bodySmall)
        if (failed) Text(stringResource(R.string.math_recognition_failed), color = MaterialTheme.colorScheme.error)
        result?.let { value ->
            Text(value, maxLines = 4)
            TextButton(enabled = enabled && !working, onClick = { onApply(value) }) { Text(stringResource(R.string.math_use_result)) }
        }
    }
    if (settings) OfflineMathSettingsDialog { settings = false }
}
