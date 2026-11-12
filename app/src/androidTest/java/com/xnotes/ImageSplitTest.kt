package com.xnotes

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import com.xnotes.core.geometry.*
import com.xnotes.core.model.*
import com.xnotes.platform.ImageSplitDraft
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ImageSplitTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun splitPngsReconstructEveryOriginalPixelWithoutOverlap() {
        val dir = File(context.cacheDir, "split-pixels").apply { mkdirs() }
        val width = 120; val height = 80
        val colors = IntArray(width * height) { Color.rgb((it % width) * 2, (it / width) * 3, 90) }
        val source = Bitmap.createBitmap(colors, width, height, Bitmap.Config.ARGB_8888)
        val file = File(dir, "source.png")
        file.outputStream().use { source.compress(Bitmap.CompressFormat.PNG, 100, it) }; source.recycle()
        try {
            val item = ImageItem(ImageData(file, width, height), Rect(0.0, 0.0, width.toDouble(), height.toDouble()))
            for ((a, b) in listOf(Pt(0.0, .5) to Pt(1.0, .5), Pt(.4, 0.0) to Pt(.4, 1.0), Pt(0.0, 0.0) to Pt(1.0, 1.0))) {
                val pieces = ImageSplitDraft(item, dir) { false }.split(a, b)
                val reconstructed = IntArray(colors.size)
                for (piece in pieces) {
                    val bmp = BitmapFactory.decodeFile(piece.image.file.path)
                    val pixels = IntArray(bmp.width * bmp.height)
                    bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
                    for (y in 0 until bmp.height) for (x in 0 until bmp.width) {
                        val pixel = pixels[y * bmp.width + x]
                        if (Color.alpha(pixel) == 0) continue
                        val pos = (piece.rect.y.toInt() + y) * width + piece.rect.x.toInt() + x
                        assertEquals("No pixel may belong to both pieces", 0, reconstructed[pos])
                        reconstructed[pos] = pixel
                    }
                    bmp.recycle(); piece.image.file.delete()
                }
                assertArrayEquals("A cut must not erase any image content", colors, reconstructed)
            }
        } finally { dir.deleteRecursively() }
    }

    @Test fun rotatedMirroredCroppedImageCanBeSplitRepeatedlyAndKeepsSourceUntouched() {
        val dir = File(context.cacheDir, "split-rotation").apply { mkdirs() }
        val source = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        for (y in 0 until 100) for (x in 100 until 200) source.setPixel(x, y, Color.RED)
        val file = File(dir, "source.png")
        file.outputStream().use { source.compress(Bitmap.CompressFormat.PNG, 100, it) }; source.recycle()
        val bytes = file.readBytes()
        try {
            val item = ImageItem(ImageData(file, 200, 100), Rect(30.0, 40.0, 100.0, 100.0),
                orientation = 90, angle = .35, flip = Flip.X, part = Rect(.5, 0.0, .5, 1.0))
            val draft = ImageSplitDraft(item, dir) { false }
            item.part = null // draft must keep its original image, crop and transform
            val pieces = draft.split(Pt(0.0, 0.0), Pt(1.0, 1.0))
            for (piece in pieces) {
                assertEquals(.35, piece.angle, 0.0)
                val bmp = BitmapFactory.decodeFile(piece.image.file.path)
                val pixels = IntArray(bmp.width * bmp.height)
                bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
                assertTrue(pixels.any { it == Color.RED })
                assertFalse(pixels.any { Color.blue(it) > 100 })
                bmp.recycle()
            }
            val more = ImageSplitDraft(pieces.first(), dir) { false }.split(Pt(0.0, .6), Pt(1.0, .6))
            assertEquals(2, more.size)
            assertTrue(more.all { it.cutOutline != null })
            assertArrayEquals(bytes, file.readBytes())
        } finally { dir.deleteRecursively() }
    }

    @Test fun emptyTransparentSideFailsAtomicallyAndFormulaBlocksCannotBeCut() {
        val dir = File(context.cacheDir, "split-empty").apply { mkdirs() }
        val source = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        for (y in 0 until 100) for (x in 0 until 30) source.setPixel(x, y, Color.BLACK)
        val file = File(dir, "source.png")
        file.outputStream().use { source.compress(Bitmap.CompressFormat.PNG, 100, it) }; source.recycle()
        try {
            val item = ImageItem(ImageData(file, 100, 100), Rect(0.0, 0.0, 100.0, 100.0))
            val result = runCatching { ImageSplitDraft(item, dir) { false }.split(Pt(.5, 0.0), Pt(.5, 1.0)) }
            assertTrue(result.isFailure)
            assertEquals(listOf("source.png"), dir.listFiles()!!.map { it.name })
            assertFalse(ImageSplitDraft.accepts(listOf(ImageItem(item.image, item.rect, formula = FormulaSource("x=1")))))
            item.locked = true
            assertFalse(ImageSplitDraft.accepts(listOf(item)))
        } finally { dir.deleteRecursively() }
    }
}
