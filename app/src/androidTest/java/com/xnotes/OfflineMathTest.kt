package com.xnotes

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xnotes.core.geometry.Pt
import com.xnotes.platform.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** External real MathWriting fixtures are pushed to app files; never shipped in an APK. */
@RunWith(AndroidJUnit4::class)
class OfflineMathTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun downloadAndVerifyModelFromPublicGitHubRelease() = runBlocking {
        OfflineMathModel.download(context)
        withTimeout(240_000) { while (OfflineMathModel.status.value.busy) delay(100) }
        assertFalse("Download failed", OfflineMathModel.status.value.error)
        assertTrue(OfflineMathModel.installed(context))
        OfflineMathModel.validatedDirectory(context)
        Unit
    }
    @Test fun benchmarkRealHandwritingOffline() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val mode = MathRecognitionMode.valueOf(args.getString("mathMode") ?: "ACCURATE")
        val count = args.getString("mathCount")?.toInt() ?: 20
        val folder = File(context.filesDir, "handwriting-v5")
        val manifest = JSONArray(File(folder, "manifest.json").readText())
        // Fixed first N from each subject, without selecting by prediction quality.
        val cases = (0 until manifest.length()).map { manifest.getJSONObject(it) }
            .groupBy { it.getString("subject") }.values.flatMap { it.take(count / 2) }
        assertEquals(count, cases.size)
        val rows = JSONArray()
        val output = File(context.getExternalFilesDir(null), "offline-${mode.name.lowercase()}.json")
        val config = OfflineMathConfig(mode = mode, threads = 2, useSubjectPacks = true)
        for (sample in cases) {
            val ink = JSONArray(File(folder, sample.getString("strokes")).readText())
            val strokes = List(ink.length()) { i -> ink.getJSONArray(i).let { s -> List(s.length()) { j -> s.getJSONArray(j).let { Pt(it.getDouble(0), it.getDouble(1)) } } } }
            val row = JSONObject().put("id", sample.getString("id")).put("truth", sample.getString("latex"))
                .put("subject", sample.getString("subject")).put("mode", mode.name)
            val start = SystemClock.elapsedRealtime()
            try {
                val result = OfflineMathRecognizer.recognize(context, strokes = strokes, config = config)
                row.put("latex", result.latex).put("elapsed_ms", result.elapsedMs).put("load_ms", result.loadMs)
                    .put("encode_ms", result.encodeMs).put("decode_ms", result.decodeMs).put("warm", result.warm)
            } catch (e: Exception) { row.put("latex", "").put("error", e.javaClass.simpleName).put("elapsed_ms", SystemClock.elapsedRealtime() - start) }
            rows.put(row); output.writeText(rows.toString(2))
        }
        assertEquals(count, rows.length())
        assertTrue((0 until rows.length()).any { rows.getJSONObject(it).optString("latex").isNotBlank() })
    }
    @Test fun settingsRoundtripAndNativeCancellationLeaveEngineUsable() = runBlocking {
        val original = OfflineMathSettings.load(context)
        try {
            OfflineMathSettings.save(context, OfflineMathConfig(false, MathRecognitionMode.FAST, 900, 4, false))
            assertEquals(OfflineMathConfig(false, MathRecognitionMode.FAST, 900, 4, false), OfflineMathSettings.load(context))
        } finally { OfflineMathSettings.save(context, original) }
        val strokes = listOf(listOf(Pt(0.0, 0.0), Pt(100.0, 100.0)), listOf(Pt(100.0, 0.0), Pt(0.0, 100.0)))
        val job = launch { OfflineMathRecognizer.recognize(context, strokes = strokes) }
        delay(40); job.cancelAndJoin()
        val result = OfflineMathRecognizer.recognize(context, strokes = strokes, config = OfflineMathConfig(mode = MathRecognitionMode.FAST))
        assertTrue(result.latex.isNotBlank())
        OfflineMathRecognizer.releaseAndAwait()
    }
}
