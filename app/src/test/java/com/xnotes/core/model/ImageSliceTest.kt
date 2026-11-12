package com.xnotes.core.model

import com.xnotes.core.FakeImageCodec
import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.geometry.*
import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.format.CanvasCodec
import com.xnotes.format.DocumentCodec
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.nio.file.Files

class ImageSliceTest {
    private val square = ImageSliceGeometry.square
    private val triangle = listOf(Pt(0.0, 0.0), Pt(1.0, 1.0), Pt(0.0, 1.0))
    private fun picture() = ImageItem(ImageData(File("unused"), 200, 100), Rect(30.0, 40.0, 200.0, 100.0), cutOutline = triangle)

    @Test fun horizontalVerticalAndDiagonalCutsConserveArea() {
        for ((a, b) in listOf(Pt(-.1, .3) to Pt(1.1, .3), Pt(.2, -1.0) to Pt(.2, 2.0), Pt(0.0, 0.0) to Pt(1.0, 1.0))) {
            val pieces = ImageSliceGeometry.split(square, a, b)!!
            assertEquals(1.0, pieces.sumOf(ImageSliceGeometry::area), 1e-10)
            assertTrue(pieces.all { ImageSliceGeometry.area(it) > 0 })
        }
    }

    @Test fun repeatedCutsPreserveExactlyTheOriginalShape() {
        var pieces = listOf(square)
        val random = java.util.Random(20261113)
        repeat(60) {
            val x = random.nextDouble(); val y = random.nextDouble()
            pieces = pieces.flatMap { ImageSliceGeometry.split(it, Pt(x, -.2), Pt(y, 1.2)) ?: listOf(it) }
            assertEquals(1.0, pieces.sumOf(ImageSliceGeometry::area), 1e-9)
        }
    }

    @Test fun noOpAndCutsThroughTransparentCornersAreRejected() {
        assertNull(ImageSliceGeometry.split(square, Pt(0.0, 0.0), Pt(0.0, 0.0)))
        assertNull(ImageSliceGeometry.split(square, Pt(-1.0, -1.0), Pt(1.0, -1.0)))
        assertNull(ImageSliceGeometry.split(square, Pt(0.0, 0.0), Pt(1.0, 0.0)))
        assertNull(ImageSliceGeometry.split(triangle, Pt(.8, 0.0), Pt(1.0, .2)))
        assertNull(ImageSliceGeometry.split(square, Pt(Double.NaN, 0.0), Pt(1.0, 1.0)))
    }

    @Test fun hitTestingTracksMirrorQuarterTurnCropAndFreeRotation() {
        for (turn in listOf(0, 90, 180, 270)) for (flip in listOf(Flip.NONE, Flip.X, Flip.Y)) {
            val item = picture().apply { orientation = turn; this.flip = flip; angle = .72; part = Rect(.1, .1, .8, .8) }
            for (p in listOf(Pt(.25, .75), Pt(.75, .25))) {
                val display = ImageSliceGeometry.displayPoint(item, p)
                val back = ImageSliceGeometry.sourcePoint(item, display)
                assertEquals(p.x, back.x, 1e-10); assertEquals(p.y, back.y, 1e-10)
                val dx = (display.x - .5) * item.rect.w; val dy = (display.y - .5) * item.rect.h
                val world = Pt(item.rect.centerX + dx * kotlin.math.cos(item.angle) - dy * kotlin.math.sin(item.angle),
                    item.rect.centerY + dx * kotlin.math.sin(item.angle) + dy * kotlin.math.cos(item.angle))
                assertEquals(p.y > p.x, item.contains(world))
            }
        }
    }

    @Test fun duplicateAndCropUndoKeepThePickingOutline() {
        val original = picture()
        assertEquals(triangle, (original.deepCopy(FakeTextMeasurer()) as ImageItem).cutOutline)
        val cropped = ImageSliceGeometry.cropOutline(triangle, Rect(0.0, .5, 1.0, .5))!!
        val command = com.xnotes.core.history.ReplaceImage(original, original.image to original.rect,
            ImageData(File("crop"), 200, 50) to Rect(30.0, 90.0, 200.0, 50.0), cropped)
        command.redo(); assertEquals(cropped, original.cutOutline)
        command.undo(); assertEquals(triangle, original.cutOutline)
        command.redo(); assertEquals(cropped, original.cutOutline)
    }

    @Test fun cutMetadataRoundTripsThroughBothNoteFormats() {
        val dir = Files.createTempDirectory("slice-codec").toFile()
        try {
            val item = picture().apply { image = ImageData(File(dir, "source.png").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }, 200, 100) }
            val document = Document(pages = mutableListOf(Page(1000.0, 1400.0, mutableListOf(item))))
            val dc = DocumentCodec(FakeImageCodec(), FakeTextMeasurer())
            val data = ByteArrayOutputStream().also { dc.write(document, it) }.toByteArray()
            val pageDir = File(dir, "paged").apply { mkdir() }
            val reopened = dc.read(ByteArrayInputStream(data), imageDir = pageDir).pages.single().items.single() as ImageItem
            assertEquals(triangle, reopened.cutOutline)
            assertFalse(reopened.contains(Pt(180.0, 60.0)))
            val canvas = InfiniteDocument().apply { add(item) }
            val cc = CanvasCodec(FakeImageCodec(), FakeTextMeasurer())
            val bytes = ByteArrayOutputStream().also { cc.write(canvas, it) }.toByteArray()
            val canvasDir = File(dir, "canvas").apply { mkdir() }
            val open = cc.read(ByteArrayInputStream(bytes), imageDir = canvasDir).items.single() as ImageItem
            assertEquals(triangle, open.cutOutline)
            assertArrayEquals(item.image.file.readBytes(), open.image.file.readBytes())
        } finally { dir.deleteRecursively() }
    }
}
