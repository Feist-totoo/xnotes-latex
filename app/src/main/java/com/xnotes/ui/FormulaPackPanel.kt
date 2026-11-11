package com.xnotes.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.xnotes.R
import com.xnotes.core.formula.FormulaPack
import com.xnotes.core.formula.FormulaPackRouter

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FormulaPackPanel(catalog: List<FormulaPack>, selected: Set<String>, source: String, enabled: Boolean,
                     automatic: Boolean, onAutomatic: (Boolean) -> Unit, onManage: () -> Unit,
                     onSelect: (Set<String>) -> Unit, onInsert: (String) -> Unit, onTemplate: (String) -> Unit) {
    val chinese = LocalConfiguration.current.locales[0].language == "zh"
    var expanded by remember { mutableStateOf(false) }
    var active by remember { mutableStateOf<String?>(null) }
    val recommendations = remember(source, catalog, selected) { FormulaPackRouter.recommend(source, catalog.filter { it.id in selected }) }
    val routed = recommendations.map { it.id }.toSet()
    val chosen = catalog.filter { it.id in selected && (!automatic || routed.isEmpty() || it.id in routed) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TextButton(onClick = { expanded = !expanded }) {
            Text(stringResource(R.string.formula_packs_count, selected.size))
        }
        if (expanded) {
            Text(stringResource(R.string.formula_packs_explanation), style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Switch(checked = automatic, onCheckedChange = onAutomatic)
                Text(if (chinese) "按本机识别结果自动选包" else "Route using local recognition", Modifier.weight(1f))
                TextButton(onClick = onManage) { Text(if (chinese) "管理 / 下载" else "Manage / download") }
            }
            if (automatic && recommendations.isNotEmpty()) {
                Text((if (chinese) "本次调用：" else "Active for this expression: ") + chosen.joinToString { it.name(chinese) }, style = MaterialTheme.typography.bodySmall)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                catalog.forEach { pack ->
                    FilterChip(selected = pack.id in selected, onClick = {
                        onSelect(if (pack.id in selected) selected - pack.id else selected + pack.id)
                        active = pack.id
                    }, label = { Text(pack.name(chinese)) })
                }
            }
            if (chosen.isNotEmpty()) {
                Text(stringResource(R.string.formula_packs_edit), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    chosen.forEach { pack ->
                        FilterChip(selected = (active ?: chosen.first().id) == pack.id,
                            onClick = { active = pack.id }, label = { Text(pack.name(chinese)) })
                    }
                }
                val current = chosen.firstOrNull { it.id == active } ?: chosen.first()
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    current.symbols.forEach { symbol ->
                        AssistChip(onClick = { onInsert(symbol.latex) }, enabled = enabled,
                            label = { Text(symbol.label(chinese)) })
                    }
                }
                Text(stringResource(R.string.formula_packs_templates), style = MaterialTheme.typography.bodySmall)
                current.templates.forEach { template ->
                    TextButton(onClick = { onTemplate(template.latex) }, enabled = enabled) {
                        Text(template.label(chinese))
                    }
                }
            }
        }
        val notes = chosen.filter { it.id in routed }.map { it.review(chinese) }
        if (source.isNotBlank() && notes.isNotEmpty()) {
            Text(stringResource(R.string.formula_packs_review, notes.joinToString(if (chinese) "；" else "; ")),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
