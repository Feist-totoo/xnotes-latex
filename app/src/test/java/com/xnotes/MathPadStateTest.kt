package com.xnotes

import com.xnotes.ui.*
import com.xnotes.core.geometry.*
import com.xnotes.core.model.ImagePlacement
import com.xnotes.platform.ImageInsertTarget
import org.junit.Assert.*
import org.junit.Test

class MathPadStateTest {
    private val ink = listOf(listOf(Pt(2.0, 3.0), Pt(8.0, 9.0)))
    @Test fun lateRecognitionCannotReplaceNewInkOrManualCorrection() {
        val line = MathPadLine(); line.ink(ink); val first = line.revision
        line.ink(ink + ink)
        assertFalse(line.accept("x", first)); assertFalse(line.ready)
        val next = line.revision; line.edit("x^{2}")
        assertFalse(line.accept("x", next)); assertEquals("x^{2}", line.latex); assertTrue(line.ready)
        line.ink(emptyList()); assertTrue(line.empty); assertFalse(line.ready)
    }
    @Test fun linesComposeOnlyWhenEveryNonemptyLineHasCurrentResult() {
        val block = MathPadBlock(); assertFalse(block.newLine())
        block.current.edit("x=1"); assertTrue(block.newLine())
        assertEquals("x=1", block.source())
        block.current.ink(ink); assertNull(block.source())
        block.current.accept("y=2")
        assertEquals("\\begin{aligned}&x=1\\\\&y=2\\end{aligned}", block.source())
        block.activeLine = 0; block.current.ink(ink); assertNull(block.source())
    }
    @Test fun switchingAndInsertingBlocksRetainsOtherDrafts() {
        val state = MathPadState(); val first = state.current
        first.current.edit("x"); state.newBlock(); val second = state.current
        second.current.edit("y"); state.activeBlock = 0
        assertEquals("x", state.current.source())
        state.inserted(first); assertSame(second, state.current); assertEquals("y", state.current.source())
        state.inserted(second); assertEquals(1, state.blocks.size); assertTrue(state.current.current.empty)
    }
    @Test fun picturePlacementHonorsRotationViewportAndExplicitPoint() {
        val area = Rect(100.0, 200.0, 1000.0, 600.0)
        val rect = ImagePlacement.place(1600, 900, 90, area, clamp = false)
        assertEquals(area.center, rect.center); assertEquals(360.0, rect.h, 1e-8)
        assertEquals(202.5, rect.w, 1e-8)
        val clipped = ImagePlacement.place(1600, 900, 0, area, Pt(-20.0, -30.0), true)
        assertEquals(area.left, clipped.left, 1e-8); assertEquals(area.top, clipped.top, 1e-8)
    }
    @Test fun pickerTargetSurvivesSavedStateWithoutEditorReferences() {
        for (target in listOf(ImageInsertTarget("PRIMARY", false, null, 123L, null),
            ImageInsertTarget("SECONDARY", true, "content://notes/one", 987L, Pt(12.0, 34.0)))) {
            assertEquals(target, ImageInsertTarget.decode(target.encode()))
        }
    }
}
