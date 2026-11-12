package com.xnotes.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.xnotes.core.formula.FormulaPack
import com.xnotes.core.formula.FormulaPackRouter
import com.xnotes.platform.FormulaPacks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun FormulaPackManager(source: String, onChanged: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val chinese = LocalConfiguration.current.locales[0].language == "zh"
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf(FormulaPacks.catalog(context)) }
    var installed by remember { mutableStateOf(FormulaPacks.load(context)) }
    var enabled by remember { mutableStateOf(FormulaPacks.selected(context, installed)) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val suggestions = remember(source, entries) { FormulaPackRouter.recommend(source, entries.map { it.routingInfo() }) }
    fun operation(block: () -> Unit) {
        if (busy) return
        busy = true; message = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) { block() }
                installed = FormulaPacks.load(context)
                enabled = FormulaPacks.selected(context, installed)
                onChanged()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                message = if (chinese) "操作未完成。请检查网络、包格式或存储空间；已安装的包仍可离线使用。" else "Could not complete the operation. Check connection, package format and storage. Installed packs still work offline."
            } finally { busy = false }
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) operation {
            val bytes = context.contentResolver.openInputStream(uri)!!.use { input ->
                val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) {
                    val n = input.read(buffer); if (n < 0) break
                    require(output.size() + n <= FormulaPack.MAX_BYTES)
                    output.write(buffer, 0, n)
                }
                output.toByteArray()
            }
            FormulaPacks.install(context, bytes)
        }
    }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (chinese) "学科包管理" else "Subject pack manager") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (chinese) "包从 GitHub Releases 下载，安装后独立在本机调用。基础识别始终离线可用；下载不会上传笔迹。" else "Packages come from GitHub Releases and run independently on this device. Core recognition always works offline; downloads never upload ink.")
                Text(if (chinese) "高数、线代 v2 包含识别统计约束，其余包提供编辑辅助。统计数据源于 Google MathWriting，遵循 CC BY-NC-SA 4.0（非商业、署名、相同方式共享）。" else "Calculus and linear algebra v2 include recognition statistics; other packs assist editing. Statistics derived from Google MathWriting: CC BY-NC-SA 4.0 (noncommercial, attribution, share-alike).", style = MaterialTheme.typography.bodySmall)
                Row {
                    TextButton(enabled = !busy, onClick = { operation { entries = FormulaPacks.refreshCatalog(context) } }) {
                        Text(if (chinese) "刷新 Release 目录" else "Refresh release catalog")
                    }
                    TextButton(enabled = !busy, onClick = { importer.launch(arrayOf("*/*")) }) {
                        Text(if (chinese) "本地导入" else "Import file")
                    }
                }
                if (suggestions.isNotEmpty()) Text((if (chinese) "本机识图建议：" else "Local recognition suggests: ") +
                    suggestions.joinToString { suggestion -> entries.first { it.id == suggestion.id }.name(chinese) + " (" + suggestion.evidence.joinToString() + ")" },
                    style = MaterialTheme.typography.bodySmall)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                // Imported packages also remain manageable if absent from the release catalog.
                val ids = (entries.map { it.id } + installed.map { it.id }).distinct()
                ids.forEach { id ->
                    val entry = entries.firstOrNull { it.id == id }
                    val pack = installed.firstOrNull { it.id == id }
                    HorizontalDivider()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(pack?.name(chinese) ?: entry!!.name(chinese), style = MaterialTheme.typography.titleSmall)
                            if (pack?.recognition != null) Text(if (chinese) "含实验候选评分（默认关闭）" else "Experimental recognition scoring (off by default)", style = MaterialTheme.typography.bodySmall)
                            Text(if (pack != null) "v${pack.version} · " + if (chinese) "已安装" else "Installed"
                                else if (chinese) "未安装 · ${entry!!.bytes} B" else "Not installed · ${entry!!.bytes} B",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        if (pack != null) Switch(checked = id in enabled, enabled = !busy, onCheckedChange = {
                            enabled = if (it) enabled + id else enabled - id
                            FormulaPacks.save(context, enabled); onChanged()
                        })
                    }
                    Row {
                        if (entry != null && (pack == null || entry.version > pack.version)) {
                            TextButton(enabled = !busy, onClick = { operation { FormulaPacks.downloadAndInstall(context, entry) } }) {
                                Text(if (pack == null) { if (chinese) "下载" else "Download" } else { if (chinese) "更新" else "Update" })
                            }
                        }
                        if (pack != null) TextButton(enabled = !busy, onClick = { operation { FormulaPacks.uninstall(context, id) } }) {
                            Text(if (chinese) "卸载" else "Uninstall")
                        }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(if (chinese) "完成" else "Done") } })
}
