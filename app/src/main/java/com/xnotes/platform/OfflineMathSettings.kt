package com.xnotes.platform

import android.content.Context

enum class MathRecognitionMode(val beams: Int, val steps: Int) { FAST(1, 10), BALANCED(2, 20), ACCURATE(4, 20) }
data class OfflineMathConfig(val automatic: Boolean = true, val mode: MathRecognitionMode = MathRecognitionMode.ACCURATE,
    val pauseMs: Int = 550, val threads: Int = 2, val useSubjectPacks: Boolean = true)

object OfflineMathSettings {
    private fun prefs(context: Context) = context.getSharedPreferences("offline-math", Context.MODE_PRIVATE)
    fun load(context: Context): OfflineMathConfig = prefs(context).let {
        OfflineMathConfig(it.getBoolean("automatic", true), runCatching { MathRecognitionMode.valueOf(it.getString("mode", "ACCURATE")!!) }.getOrDefault(MathRecognitionMode.ACCURATE),
            it.getInt("pause", 550).coerceIn(250, 1500), it.getInt("threads", 2).coerceIn(1, 4),
            it.getBoolean("subjects", FormulaPacks.recognitionEnabled(context)))
    }
    fun save(context: Context, config: OfflineMathConfig) {
        prefs(context).edit().putBoolean("automatic", config.automatic).putString("mode", config.mode.name)
            .putInt("pause", config.pauseMs.coerceIn(250,1500)).putInt("threads", config.threads.coerceIn(1,4))
            .putBoolean("subjects", config.useSubjectPacks).apply()
    }
}
