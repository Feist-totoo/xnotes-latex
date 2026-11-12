package com.xnotes.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.xnotes.R
import com.xnotes.core.formula.PaddleFormulaApi
import com.xnotes.platform.PaddleFormulaSettings
import kotlinx.coroutines.*

@Composable
fun PaddleFormulaPanel(ink: Bitmap?, enabled: Boolean, onStart: () -> Unit = {},
    recognize: suspend (Bitmap, PaddleFormulaApi.Config) -> String = PaddleFormulaSettings::recognize,
    onApply: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var config by remember { mutableStateOf<PaddleFormulaApi.Config?>(null) }
    var settings by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    var result by remember(ink) { mutableStateOf<String?>(null) }
    var error by remember { mutableIntStateOf(0) }
    LaunchedEffect(context) { config = withContext(Dispatchers.IO) { PaddleFormulaSettings.load(context) } }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Paddle AI Studio", style = MaterialTheme.typography.labelLarge)
        Text(stringResource(R.string.paddle_privacy), style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(modifier = Modifier.testTag("paddle-recognize"), enabled = enabled && !working && ink != null && config != null, onClick = {
                val bitmap = ink ?: return@OutlinedButton
                val selected = config ?: return@OutlinedButton
                onStart()
                working = true; error = 0; result = null
                job = scope.launch {
                    try { result = recognize(bitmap, selected) }
                    catch (_: TimeoutCancellationException) { error = R.string.paddle_timeout }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: PaddleFormulaApi.Error) {
                        error = when (failure.reason) {
                            PaddleFormulaApi.Failure.AUTH -> R.string.paddle_auth_error
                            PaddleFormulaApi.Failure.QUOTA -> R.string.paddle_quota_error
                            PaddleFormulaApi.Failure.MULTIPLE -> R.string.paddle_multiple_error
                            PaddleFormulaApi.Failure.EMPTY -> R.string.paddle_empty_error
                            PaddleFormulaApi.Failure.NETWORK -> R.string.paddle_network_error
                            else -> R.string.paddle_response_error
                        }
                    } catch (_: Exception) { error = R.string.paddle_response_error }
                    finally { working = false }
                }
            }) { Text(stringResource(R.string.paddle_recognize)) }
            TextButton(enabled = !working && enabled, onClick = { settings = true }, modifier = Modifier.testTag("paddle-settings")) {
                Text(stringResource(R.string.paddle_settings))
            }
        }
        if (working) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(stringResource(R.string.paddle_working), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { job?.cancel() }) { Text(stringResource(R.string.paddle_cancel)) }
        }
        if (error != 0) Text(stringResource(error), color = MaterialTheme.colorScheme.error)
        result?.let { text ->
            Text(text, fontFamily = FontFamily.Monospace, maxLines = 6, modifier = Modifier.testTag("paddle-result"))
            TextButton(enabled = enabled && !working, onClick = { onApply(text) }) { Text(stringResource(R.string.paddle_apply)) }
        }
    }
    if (settings) PaddleSettingsDialog(config, onSaved = { config = it; settings = false; error = 0 }, onDismiss = { settings = false })
}

@Composable
private fun PaddleSettingsDialog(initial: PaddleFormulaApi.Config?, onSaved: (PaddleFormulaApi.Config?) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val links = LocalUriHandler.current
    var endpoint by remember { mutableStateOf(initial?.endpoint ?: PaddleFormulaApi.DEFAULT_ENDPOINT) }
    var token by remember { mutableStateOf(initial?.token.orEmpty()) }
    var model by remember { mutableStateOf(initial?.model ?: PaddleFormulaApi.models.first()) }
    var saving by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val config = PaddleFormulaApi.Config(endpoint.trim(), token.trim(), model)
    AlertDialog(onDismissRequest = { if (!saving) onDismiss() }, title = { Text(stringResource(R.string.paddle_settings)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.paddle_setup_help))
            TextButton(onClick = { links.openUri("https://aistudio.baidu.com/account/accessToken") }) { Text(stringResource(R.string.paddle_get_token)) }
            OutlinedTextField(token, { if (it.length <= 4096) token = it }, singleLine = true, enabled = !saving,
                label = { Text("AI Studio Access Token") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.testTag("paddle-token"))
            PaddleFormulaApi.models.forEach { name ->
                Row {
                    RadioButton(selected = model == name, enabled = !saving, onClick = { model = name })
                    TextButton(enabled = !saving, onClick = { model = name }) { Text(name) }
                }
            }
            OutlinedTextField(endpoint, { if (it.length <= 1024) endpoint = it }, enabled = !saving, label = { Text("API URL") },
                supportingText = { Text(stringResource(R.string.paddle_endpoint_help)) }, modifier = Modifier.testTag("paddle-endpoint"))
            Text(stringResource(R.string.paddle_storage_help), style = MaterialTheme.typography.bodySmall)
            if (failed) Text(stringResource(R.string.paddle_save_error), color = MaterialTheme.colorScheme.error)
            if (initial != null) TextButton(enabled = !saving, onClick = {
                saving = true
                scope.launch {
                    try { withContext(Dispatchers.IO) { PaddleFormulaSettings.clear(context) }; onSaved(null) }
                    catch (_: Exception) { failed = true } finally { saving = false }
                }
            }) { Text(stringResource(R.string.paddle_clear)) }
        }
    }, confirmButton = {
        TextButton(enabled = !saving && PaddleFormulaApi.valid(config), onClick = {
            saving = true
            scope.launch {
                try { withContext(Dispatchers.IO) { PaddleFormulaSettings.save(context, config) }; onSaved(config) }
                catch (_: Exception) { failed = true } finally { saving = false }
            }
        }, modifier = Modifier.testTag("paddle-save")) { Text(stringResource(R.string.paddle_save)) }
    }, dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}
