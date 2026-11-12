package com.xnotes

import android.graphics.BitmapFactory
import android.os.Debug
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xnotes.core.geometry.Pt
import com.xnotes.platform.FormulaRecognition
import com.xnotes.platform.LocalFormulaRecognizer
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/** Same fixed public cases, same AVD. Reflection bridges the v3/v4 suspend signature. */
@RunWith(AndroidJUnit4::class)
class CompatFormulaSpeedTest {
    @Test fun sameHumanCasesColdWarmReload() = runBlocking {
        val test=InstrumentationRegistry.getInstrumentation();val context=test.targetContext;val assets=test.context.assets
        val manifest=JSONArray(assets.open("handwriting-v4/manifest.json").bufferedReader().use { it.readText() })
        val results=JSONArray()
        val method=LocalFormulaRecognizer.javaClass.methods.single { it.name=="recognize" }
        val version=context.packageManager.getPackageInfo(context.packageName,0).versionName
        for (index in listOf(0,100,199)) {
            val row=manifest.getJSONObject(index)
            val pen=JSONArray(assets.open("handwriting-v4/${row.getString("strokes")}").bufferedReader().use { it.readText() }).let { traces ->
                List(traces.length()) { i -> traces.getJSONArray(i).let { points -> List(points.length()) { j ->
                    points.getJSONArray(j).let { Pt(it.getDouble(0),it.getDouble(1)) }
                } } }
            }
            val image=assets.open("handwriting-v4/${row.getString("image")}").use { BitmapFactory.decodeStream(it) }
            try {
                for (run in 0..2) {
                    if (run!=1) LocalFormulaRecognizer.releaseMemory()
                    val result=suspendCoroutine<FormulaRecognition> { continuation ->
                        try {
                            val returned=if (method.parameterCount==4) method.invoke(LocalFormulaRecognizer,context,image,pen,continuation)
                                else method.invoke(LocalFormulaRecognizer,context,image,continuation)
                            if (returned!==COROUTINE_SUSPENDED) continuation.resume(returned as FormulaRecognition)
                        } catch (error: Exception) { continuation.resumeWithException(error.cause ?: error) }
                    }
                    val memory=Debug.MemoryInfo();Debug.getMemoryInfo(memory)
                    results.put(JSONObject().put("version",version).put("id",row.getString("id")).put("run",run)
                        .put("truth",row.getString("latex")).put("latex",result.latex).put("elapsed_ms",result.elapsedMs)
                        .put("load_ms",result.loadMs).put("encode_ms",result.encodeMs).put("decode_ms",result.decodeMs)
                        .put("warm",result.warm).put("pss_kib",memory.totalPss))
                    File(context.getExternalFilesDir(null),"compat-speed-$version.json").writeText(results.toString(2))
                }
            } finally { image.recycle();LocalFormulaRecognizer.releaseMemory() }
        }
    }
}
