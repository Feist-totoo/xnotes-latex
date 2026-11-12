package com.xnotes

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xnotes.core.formula.*
import com.xnotes.core.geometry.Pt
import com.xnotes.core.model.Stroke
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolConfig
import com.xnotes.platform.FormulaDraft
import com.xnotes.platform.FormulaPacks
import com.xnotes.platform.LatexInternals
import com.xnotes.platform.LocalFormulaRecognizer
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/** Only real, verified human pen traces. Fixtures live in the test APK, never the app. */
@RunWith(AndroidJUnit4::class)
class OfflineStrokeBenchmarkTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val dataset get() = InstrumentationRegistry.getArguments().getString("dataset", "handwriting-v4")!!
    private val roundTrip get() = InstrumentationRegistry.getArguments().getString("roundtrip") == "true"
    private fun strokes(name: String): List<List<Pt>> {
        val rows=JSONArray(instrumentation.context.assets.open("$dataset/$name").bufferedReader().use { it.readText() })
        val pen=List(rows.length()) { i -> rows.getJSONArray(i).let { a -> List(a.length()) { j ->
            a.getJSONArray(j).let { Pt(it.getDouble(0),it.getDouble(1)) }
        } } }
        if (!roundTrip) return pen
        val items=pen.map { trace -> Stroke(Tool.PEN,ToolConfig(),trace.map { Sample(it.x,it.y,1.0) }) }
        return FormulaDraft(items,instrumentation.targetContext.cacheDir) { _,_ -> false }.recognitionStrokes()
    }

    @Test fun fullHeldOutSubjectSetAndOfflinePackAblation() = runBlocking {
        val context=instrumentation.targetContext;val assets=instrumentation.context.assets
        val rows=JSONArray(assets.open("$dataset/manifest.json").bufferedReader().use { it.readText() })
        for (id in listOf("calculus","linear_algebra")) FormulaPacks.install(context,context.assets.open("formula-packs/$id.xformula").use { it.readBytes() })
        val packs=FormulaPacks.load(context).filter { it.id in setOf("calculus","linear_algebra") }
        assertEquals(2,packs.count { it.recognition!=null })
        val results=JSONArray();val suffix=if (roundTrip) "-roundtrip" else ""
        val output=File(context.getExternalFilesDir(null),"android-v4-$dataset$suffix.json")
        var expected=0
        for (i in 0 until rows.length()) {
            val row=rows.getJSONObject(i)
            if (roundTrip && row.getString("raster_sha256")==row.getString("raster_roundtrip_sha256")) continue
            expected++
            val pen=strokes(row.getString("strokes"))
            val raster=FormulaInkRaster.pixels(pen).map { it.toInt().toByte() }.toByteArray()
            val hash=MessageDigest.getInstance("SHA-256").digest(raster).joinToString("") { "%02x".format(it) }
            assertEquals("Desktop/Android raster parity: ${row.getString("id")}",row.getString(if (roundTrip) "raster_roundtrip_sha256" else "raster_sha256"),hash)
            val bitmap=assets.open("$dataset/${row.getString("image")}").use { BitmapFactory.decodeStream(it) }
            val record=JSONObject().put("id",row.getString("id")).put("truth",row.getString("latex"))
            try {
                val result=LocalFormulaRecognizer.recognize(context,bitmap,pen)
                val ranking=FormulaCandidateRanking.rank(result.candidates,packs,true)
                val candidates=JSONArray()
                result.candidates.forEach { c -> candidates.put(JSONObject().put("latex",c.latex).put("visual_score",c.visualScore)
                    .put("symbols",JSONArray(c.symbols.toList())).put("modifiers",JSONArray(c.modifiers.toList()))) }
                record.put("latex",result.latex).put("ranked",ranking.candidates.first().latex).put("candidates",candidates)
                    .put("routed",JSONArray(ranking.packageIds.toList())).put("elapsed_ms",result.elapsedMs)
                    .put("load_ms",result.loadMs).put("encode_ms",result.encodeMs).put("decode_ms",result.decodeMs)
                    .put("warm",result.warm).put("renderable",LatexInternals.recognizesMath(ranking.candidates.first().latex))
            } catch (error: Exception) {
                record.put("latex","").put("candidates",JSONArray()).put("error",error.javaClass.simpleName+": "+error.message)
            } finally { bitmap.recycle() }
            results.put(record);output.writeText(results.toString(2))
            android.util.Log.i("StrokeBenchmark","${i+1}/${rows.length()}")
        }
        assertEquals(expected,results.length())
        assertTrue(expected>0)
        assertEquals("All samples must complete without engine errors",0,(0 until results.length()).count { results.getJSONObject(it).has("error") })
        LocalFormulaRecognizer.releaseMemory()
    }

    @Test fun coldWarmReloadUsesRealStrokes() = runBlocking {
        val context=instrumentation.targetContext;val assets=instrumentation.context.assets
        val row=JSONArray(assets.open("$dataset/manifest.json").bufferedReader().use { it.readText() }).getJSONObject(0)
        val pen=strokes(row.getString("strokes"));val bitmap=assets.open("$dataset/${row.getString("image")}").use { BitmapFactory.decodeStream(it) }
        val results=JSONArray();var reference: String?=null
        try {
            for (i in 0..2) {
                if (i!=1) LocalFormulaRecognizer.releaseMemory()
                val result=LocalFormulaRecognizer.recognize(context,bitmap,pen)
                if (reference==null) reference=result.latex else assertEquals(reference,result.latex)
                assertEquals(i==1,result.warm)
                results.put(JSONObject().put("run",i).put("latex",result.latex).put("elapsed_ms",result.elapsedMs)
                    .put("load_ms",result.loadMs).put("encode_ms",result.encodeMs).put("decode_ms",result.decodeMs).put("warm",result.warm))
            }
            File(context.getExternalFilesDir(null),"android-v4-speed.json").writeText(results.toString(2))
        } finally { bitmap.recycle();LocalFormulaRecognizer.releaseMemory() }
    }
}
