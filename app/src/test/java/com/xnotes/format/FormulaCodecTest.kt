package com.xnotes.format

import com.xnotes.core.FakeImageCodec
import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.geometry.Rect
import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.model.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

class FormulaCodecTest {
    private fun formula() = ImageItem(
        ImageData(File.createTempFile("formula", ".png").apply { writeBytes(byteArrayOf(1, 2, 3, 4)); deleteOnExit() }, 400, 180),
        Rect(-100.0, 50.0, 200.0, 90.0), angle = 0.3,
        formula = FormulaSource("\\begin{aligned}x&=\\frac{a}{b}\\\\y&=2\\end{aligned}", Rgba(20, 40, 120), 28.0),
    )

    private fun assertPreserved(a: ImageItem, b: ImageItem) {
        assertEquals(a.formula, b.formula)
        assertEquals(a.rect, b.rect)
        assertEquals(a.angle, b.angle, 0.0)
        assertArrayEquals(a.image.file.readBytes(), b.image.file.readBytes())
    }

    @Test fun pagedNotePreservesSourceAndDisplayCacheAfterReopen() {
        val original = formula()
        val doc = Document(pages = mutableListOf(Page(1000.0, 1400.0, mutableListOf(original))))
        val codec = DocumentCodec(FakeImageCodec(), FakeTextMeasurer())
        val bytes = ByteArrayOutputStream().also { codec.write(doc, it) }.toByteArray()
        val dir = Files.createTempDirectory("formula-test").toFile()
        try {
            val reopened = codec.read(ByteArrayInputStream(bytes), imageDir = dir)
            assertPreserved(original, reopened.pages.single().items.single() as ImageItem)
        } finally { dir.deleteRecursively() }
    }

    @Test fun infiniteCanvasPreservesSourceAndDisplayCacheAfterReopen() {
        val original = formula()
        val doc = InfiniteDocument().also { it.add(original) }
        val codec = CanvasCodec(FakeImageCodec(), FakeTextMeasurer())
        val bytes = ByteArrayOutputStream().also { codec.write(doc, it) }.toByteArray()
        val dir = Files.createTempDirectory("formula-test").toFile()
        try {
            val reopened = codec.read(ByteArrayInputStream(bytes), imageDir = dir)
            assertPreserved(original, reopened.items.single() as ImageItem)
        } finally { dir.deleteRecursively() }
    }

    @Test fun ordinaryImagesRemainOrdinaryImages() {
        val original = formula().let { ImageItem(it.image, it.rect) }
        val doc = Document(pages = mutableListOf(Page(1000.0, 1400.0, mutableListOf(original))))
        val codec = DocumentCodec(FakeImageCodec(), FakeTextMeasurer())
        val bytes = ByteArrayOutputStream().also { codec.write(doc, it) }.toByteArray()
        val dir = Files.createTempDirectory("formula-test").toFile()
        try {
            val reopened = codec.read(ByteArrayInputStream(bytes), imageDir = dir)
            assertNull((reopened.pages.single().items.single() as ImageItem).formula)
        } finally { dir.deleteRecursively() }
    }
}
