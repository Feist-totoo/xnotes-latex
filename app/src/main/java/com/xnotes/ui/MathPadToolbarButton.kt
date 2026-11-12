package com.xnotes.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Functions
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.xnotes.R

@Composable
fun MathPadToolbarButton(state: MathPadState) {
    ToolbarIcon(Icons.Outlined.Functions, stringResource(R.string.math_pad), active = state.open) { state.open = !state.open }
}
