package com.xnotes

import com.xnotes.core.FakeImageCodec
import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.geometry.*
import com.xnotes.core.model.*
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.*
import com.xnotes.format.*
import com.xnotes.ui.InlineMathState
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.nio.file.Files

class InlineFormulaTest {
    private fun stroke(x: Double = 20.0, color: Rgba = Rgba(190, 40, 80, 180)) = Stroke(Tool.PEN,
        ToolConfig(baseWidth = 3.5, rgba = color), listOf(Sample(x, 50.0, .3), Sample(x + 25, 65.0, .8)))
    @Test fun restoredInkTracksFormulaMoveResizeAndRotationAndKeepsPressureColor() {
        val original = stroke(); val reference = Rect(10.0, 40.0, 100.0, 40.0)
        val ink = FormulaInk(reference, listOf(original))
        original.translate(999.0, 0.0)
        val item = ImageItem(ImageData(File("unused.png"), 100, 40), Rect(60.0, 140.0, 200.0, 80.0), angle = .7)
        val restored = ink.restore(item).single()
        val fit = Affine(2.0, 0.0, 0.0, 2.0, 40.0, 60.0)
        val expected = Affine.rotateAbout(item.rect.center, .7).apply(fit.apply(Pt(20.0, 50.0)))
        assertEquals(expected.x, restored.xAt(0), 1e-4); assertEquals(expected.y, restored.yAt(0), 1e-4)
        assertEquals(.3, restored.pAt(0), 1e-6); assertEquals(original.config.rgba, restored.config.rgba)
        assertEquals(7.0, restored.config.baseWidth, 1e-8)
    }
    @Test fun bothNoteFormatsKeepRestorableInkAcrossSaveAndReopen() {
        val dir = Files.createTempDirectory("inline-formula").toFile()
        try {
            val file = File(dir, "image.png").apply { writeBytes(byteArrayOf(1,2,3)) }
            val rect = Rect(10.0, 40.0, 100.0, 40.0)
            val original = stroke()
            val item = ImageItem(ImageData(file, 100, 40), rect, formula = FormulaSource("x^2", original.config.rgba,
                ink = FormulaInk(rect, listOf(original))))
            val pageCodec = DocumentCodec(FakeImageCodec(), FakeTextMeasurer())
            val canvasCodec = CanvasCodec(FakeImageCodec(), FakeTextMeasurer())
            val p = ByteArrayOutputStream().also { pageCodec.write(Document(pages = mutableListOf(Page(1000.0, 1400.0, mutableListOf(item)))), it) }
            val c = ByteArrayOutputStream().also { canvasCodec.write(com.xnotes.core.infinite.InfiniteDocument().also { d -> d.add(item) }, it) }
            val read = listOf(pageCodec.read(ByteArrayInputStream(p.toByteArray()), imageDir = dir).pages.single().items.single(),
                canvasCodec.read(ByteArrayInputStream(c.toByteArray()), imageDir = dir).items.single())
            read.forEach {
                val block = it as ImageItem
                val ink = block.formula!!.ink!!
                assertEquals(original.samples, ink.restore(block).single().samples)
                assertEquals(original.config.rgba, ink.strokes.single().config.rgba)
                assertEquals(original.config.baseWidth, ink.strokes.single().config.baseWidth, 0.0)
            }
        } finally { dir.deleteRecursively() }
    }
    @Test fun batchesStaySeparateAndRestorationWaitsForAnEdit() {
        val state = InlineMathState(); val page = Any(); val first = stroke()
        state.offer(page, first); assertTrue(state.strokes.isEmpty())
        state.toggle(); state.offer(page, first); assertEquals(listOf(first), state.strokes)
        val version = state.revision; state.touch(true); assertTrue(state.revision > version); assertTrue(state.touching)
        state.restore(page, listOf(first)); assertTrue(state.waitingForEdit)
        val next = stroke(48.0, Rgba(0, 0, 200)); state.offer(page, next)
        assertEquals(listOf(first, next), state.strokes); assertFalse(state.waitingForEdit)
        val far = stroke(900.0); state.offer(page, far); assertEquals(listOf(far), state.strokes)
        state.offer(Any(), first); assertEquals(listOf(first), state.strokes)
        state.toggle(); assertTrue(state.strokes.isEmpty())
    }
    @Test fun erasingRestoredInkRetainsOnlySurvivingFragmentsForNextRecognition() {
        val state = InlineMathState(); state.toggle()
        val doc = com.xnotes.core.infinite.InfiniteDocument()
        val original = Stroke(Tool.PEN, ToolConfig(), (0..20).map { Sample(it * 5.0, 50.0, 1.0) })
        doc.add(original); state.restore(doc, listOf(original))
        state.hold()
        val eraser = com.xnotes.core.infinite.EraseSession(doc) { old, fragments -> state.replaceInk(old, fragments) }
        eraser.erase(50.0, 50.0, 8.0, true)
        assertFalse(doc.containsRef(original)); assertFalse(state.strokes.contains(original))
        assertEquals(doc.items.toList(), state.strokes); assertTrue(state.waitingForEdit)
        val extra = stroke(48.0); doc.add(extra); state.offer(doc, extra)
        assertEquals(doc.items.toList(), state.strokes); assertFalse(state.waitingForEdit)
    }
    @Test fun screenshotIsAbsentFromNormalToolbarCatalog() {
        assertFalse(ToolbarItem.SCREENSHOT in ToolbarLayout.NOTE_ITEMS)
        assertTrue(ToolbarLayout.DEFAULT.visibleSections.flatMap { it.visibleEntries }.none { it.item == ToolbarItem.SCREENSHOT })
    }
}
