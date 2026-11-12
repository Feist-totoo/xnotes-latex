package com.xnotes

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Debug
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.ImageData
import com.xnotes.core.model.ImageItem
import com.xnotes.core.model.FormulaSource
import com.xnotes.platform.FormulaDraft
import com.xnotes.platform.LocalFormulaRecognizer
import com.xnotes.platform.PrecisionFormulaRecognizer
import com.xnotes.platform.RenderedFormula
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/** Real images are test assets only. Accuracy is scored externally, including failures. */
@RunWith(AndroidJUnit4::class)
class CompleteFormulaTest {
    private val test get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = test.targetContext
    private fun pen(row: JSONObject, dataset: String): List<List<Pt>> {
        val data = JSONArray(test.context.assets.open("$dataset/${row.getString("strokes")}").bufferedReader().use { it.readText() })
        return List(data.length()) { i -> data.getJSONArray(i).let { stroke -> List(stroke.length()) { j ->
            stroke.getJSONArray(j).let { Pt(it.getDouble(0), it.getDouble(1)) }
        } } }
    }

    @Test fun twentyFixedRealImagesAndEngineSwitchingWorkOffline() = runBlocking {
        val assets = test.context.assets
        val rows = JSONArray(assets.open("handwriting-v5-images/manifest.json").bufferedReader().use { it.readText() })
        assertEquals(20, rows.length())
        val results = JSONArray()
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            val bytes = assets.open("handwriting-v5-images/${row.getString("image")}").use { it.readBytes() }
            assertEquals(row.getString("image_sha256"), MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            val record = JSONObject().put("id", row.getString("id")).put("truth", row.getString("latex"))
                .put("desktop", row.getString("desktop"))
            try {
                // Image-only calls must route to the complete local precision engine.
                val result = LocalFormulaRecognizer.recognize(context, bitmap)
                record.put("latex", result.latex).put("elapsed_ms", result.elapsedMs).put("warm", result.warm)
            } catch (error: Exception) { record.put("latex", "").put("error", error.toString()) }
            finally { bitmap.recycle() }
            results.put(record)
            File(context.getExternalFilesDir(null), "android-v5-images.json").writeText(results.toString(2))
        }
        assertEquals(0, (0 until results.length()).count { results.getJSONObject(it).has("error") })
        val row = JSONArray(assets.open("handwriting-v5/manifest.json").bufferedReader().use { it.readText() }).getJSONObject(0)
        val image = assets.open("handwriting-v5/${row.getString("image")}").use { BitmapFactory.decodeStream(it) }
        try {
            val strokes = pen(row, "handwriting-v5")
            val before = LocalFormulaRecognizer.recognize(context, image, strokes)
            PrecisionFormulaRecognizer.recognize(context, image)
            assertEquals(before.latex, LocalFormulaRecognizer.recognize(context, image, strokes).latex)
            val review = launch(Dispatchers.Default) { PrecisionFormulaRecognizer.recognize(context, image) }
            delay(1000)
            review.cancelAndJoin()
            assertEquals("Cancelling detailed review must leave the fast engine usable", before.latex,
                withTimeout(60_000) { LocalFormulaRecognizer.recognize(context, image, strokes) }.latex)
        } finally { image.recycle(); LocalFormulaRecognizer.releaseMemory(); PrecisionFormulaRecognizer.releaseMemory() }
    }

    @Test fun detailedModelReuseAndReloadOnSameThreeRealCases() = runBlocking {
        val assets = test.context.assets
        val rows = JSONArray(assets.open("handwriting-v4/manifest.json").bufferedReader().use { it.readText() })
        val results = JSONArray()
        for (index in listOf(0, 100, 199)) {
            val row = rows.getJSONObject(index)
            val image = assets.open("handwriting-v4/${row.getString("image")}").use { BitmapFactory.decodeStream(it) }
            var expected: String? = null
            try {
                for (run in 0..2) {
                    if (run != 1) PrecisionFormulaRecognizer.releaseMemory()
                    val result = PrecisionFormulaRecognizer.recognize(context, image)
                    if (expected == null) expected = result.latex else assertEquals(expected, result.latex)
                    val memory = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
                    results.put(JSONObject().put("id", row.getString("id")).put("index", index).put("run", run)
                        .put("latex", result.latex).put("elapsed_ms", result.elapsedMs).put("load_ms", result.loadMs)
                        .put("encode_ms", result.encodeMs).put("decode_ms", result.decodeMs).put("warm", result.warm)
                        .put("pss_kib", memory.totalPss))
                }
            } finally { image.recycle() }
            File(context.getExternalFilesDir(null), "android-v5-precision-speed.json").writeText(results.toString(2))
        }
        PrecisionFormulaRecognizer.releaseMemory()
    }

    @Test fun selectedImageCropIsFrozenAndCanKeepOriginal() = runBlocking(Dispatchers.Default) {
        val source = Bitmap.createBitmap(100, 50, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.WHITE)
        for (y in 10..39) for (x in 10..39) source.setPixel(x,y,Color.BLACK)
        for (y in 10..39) for (x in 60..89) source.setPixel(x,y,Color.RED)
        val file = File(context.cacheDir, "formula-crop-test.png")
        file.outputStream().use { source.compress(Bitmap.CompressFormat.PNG,100,it) };source.recycle()
        val original = ImageItem(ImageData(file,100,50),Rect(30.0,40.0,200.0,200.0),part=Rect(.5,0.0,.5,1.0))
        assertTrue(FormulaDraft.accepts(listOf(original)))
        val draft = FormulaDraft(listOf(original),context.cacheDir) { _,_ -> false }
        val before = draft.inkBitmap()!!
        original.part=null;original.rect=Rect(500.0,600.0,200.0,100.0)
        val after = draft.inkBitmap()!!
        assertTrue("The original image and crop must be frozen",before.sameAs(after))
        val pixels=IntArray(before.width*before.height);before.getPixels(pixels,0,before.width,0,0,before.width,before.height)
        assertTrue(pixels.any { Color.red(it)>200 && Color.green(it)<50 })
        assertFalse("The cropped-away black symbol must not enter OCR",pixels.any { Color.red(it)<50 && Color.green(it)<50 && Color.blue(it)<50 })
        // This is a crop/placement test; real typesetting is exercised by FormulaWorkflowTest.
        val rendered=RenderedFormula(FormulaSource("x=1"),Bitmap.createBitmap(100,50,Bitmap.Config.ARGB_8888))
        val block=draft.block(rendered,true)
        assertTrue(block.rect.top>draft.bounds.bottom)
        assertEquals(0.0,block.angle,0.0)
        before.recycle();after.recycle();rendered.bitmap.recycle()
    }
}
