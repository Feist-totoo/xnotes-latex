package com.xnotes.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.xnotes.R
import com.xnotes.platform.*
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Downloadable offline engine settings; the whiteboard toolbar controls automatic conversion. */
@Composable
fun OfflineMathPreferences() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by OfflineMathModel.status.collectAsState()
    var config by remember { mutableStateOf(OfflineMathSettings.load(context)) }
    val installed = remember(status) { OfflineMathModel.installed(context) }
    fun save(next: OfflineMathConfig) { config = next; OfflineMathSettings.save(context, next) }
    Column(Modifier.fillMaxWidth().testTag("offline-math-preferences"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.math_settings), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(if (installed) R.string.math_pack_installed else R.string.math_pack_needed), style = MaterialTheme.typography.bodySmall)
        if (status.busy) {
            LinearProgressIndicator(progress = { status.progress }, modifier = Modifier.fillMaxWidth())
            Text("${(status.progress * 100).roundToInt()}%")
            TextButton(onClick = OfflineMathModel::cancel) { Text(stringResource(R.string.cancel)) }
        } else Row {
            Button(modifier = Modifier.testTag("math-download"), onClick = { OfflineMathModel.download(context) }) {
                Text(stringResource(if (installed) R.string.math_pack_verify else R.string.math_pack_download))
            }
            if (installed) TextButton(onClick = { scope.launch { OfflineMathModel.uninstall(context) } }) { Text(stringResource(R.string.math_pack_remove)) }
        }
        if (status.error) Text(stringResource(R.string.math_download_failed), color = MaterialTheme.colorScheme.error)
        Text(stringResource(R.string.math_inline_settings_hint), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.math_mode))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MathRecognitionMode.entries.forEach { mode ->
                FilterChip(selected = config.mode == mode, onClick = { save(config.copy(mode = mode)) }, label = {
                    Text(stringResource(when (mode) { MathRecognitionMode.FAST -> R.string.math_mode_fast; MathRecognitionMode.BALANCED -> R.string.math_mode_balanced; else -> R.string.math_mode_accurate }))
                })
            }
        }
        Text(stringResource(R.string.math_pause, config.pauseMs))
        Slider(value = config.pauseMs.toFloat(), onValueChange = { save(config.copy(pauseMs = (it / 50).roundToInt() * 50)) }, valueRange = 250f..1500f, steps = 24)
        Text(stringResource(R.string.math_threads, config.threads))
        Slider(value = config.threads.toFloat(), onValueChange = { save(config.copy(threads = it.roundToInt())) }, valueRange = 1f..4f, steps = 2)
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Checkbox(checked = config.useSubjectPacks, onCheckedChange = { save(config.copy(useSubjectPacks = it)) })
            Text(stringResource(R.string.math_subjects))
        }
        Text(stringResource(R.string.math_accuracy_note), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun OfflineMathSettingsDialog(onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.math_settings)) },
        text = { androidx.compose.foundation.rememberScrollState().let { scroll ->
            Column(Modifier.heightIn(max = 540.dp).verticalScroll(scroll)) { OfflineMathPreferences() }
        } }, confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) } })
}

