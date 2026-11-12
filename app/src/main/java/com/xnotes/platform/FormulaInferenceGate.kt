package com.xnotes.platform

import kotlinx.coroutines.sync.Mutex

/** Both offline engines share one memory budget and never infer concurrently. */
internal object FormulaInferenceGate {
    val mutex = Mutex()
}
