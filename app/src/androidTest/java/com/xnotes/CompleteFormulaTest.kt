package com.xnotes
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.*
import com.xnotes.platform.FormulaDraft
import com.xnotes.platform.RenderedFormula
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
class CompleteFormulaTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
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
