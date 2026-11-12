package com.xnotes.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Functions
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.xnotes.R
import com.xnotes.ui.icons.XnotesIcons

@Composable
fun InlineMathToolbarButton(state: InlineMathState) {
    ToolbarIcon(Icons.Outlined.Functions, stringResource(R.string.math_inline), active = state.enabled) { state.toggle() }
}

@Composable
fun ImageCutToolbarButton(host: SelectionMenuHost) {
    ToolbarIcon(XnotesIcons.scissors, stringResource(R.string.image_cut_tool)) { host.startImageCut() }
}
