package com.xnotes.core.formula

import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.geometry.Rect
import com.xnotes.core.history.MoveItems
import com.xnotes.core.history.ReplacePageItems
import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.infinite.ReplaceCanvasItems
import com.xnotes.core.model.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class FormulaTest {
    @Test fun insertingSpaceCannotSliceAnEditableFormulaIntoImages() {
        val block = item("x=1")
        val page = Page(1000.0, 1400.0, mutableListOf(block))
        val command = com.xnotes.core.room.RoomCut.open(page, 50.0, 100.0)!!
        assertSame(block, page.items.single())
        assertNull(block.part)
        assertEquals("x=1", block.formula!!.latex)
        command.undo()
        assertEquals(Rect(10.0, 20.0, 200.0, 80.0), block.rect)
    }

    @Test fun strokeSnapshotAcceptsUnchangedInkAndRejectsEdits() {
        val stroke = Stroke(com.xnotes.core.tools.Tool.PEN, com.xnotes.core.tools.ToolConfig(), listOf(
            com.xnotes.core.stroke.Sample(10.0, 20.0, 1.0), com.xnotes.core.stroke.Sample(30.0, 40.0, 1.0),
        ))
        val captured = FormulaSelectionState(listOf(stroke))
        assertTrue(captured.unchanged())
        stroke.translate(15.0, 10.0)
        assertFalse(captured.unchanged())
        val moved = FormulaSelectionState(listOf(stroke))
        stroke.config = stroke.config.copy(baseWidth = 10.0)
        assertFalse(moved.unchanged())
    }

    private fun item(source: String? = null) = ImageItem(
        ImageData(File("test.png"), 200, 80), Rect(10.0, 20.0, 200.0, 80.0),
        formula = source?.let { FormulaSource(it) },
    )

    @Test fun wrappersAndAliasesNormalizeWithoutChangingLongerCommands() {
        assertEquals("x^{2}", LatexSource.clean("  \$\$x^{2}\$\$  "))
        assertEquals("\\int_0^{\\infty}", LatexSource.clean("\\[\\int_0^{\\infin}\\]"))
        assertEquals("\\rightarrow \\infinite", LatexSource.clean("\\rarr \\infinite"))
    }

    @Test fun escapedBracesAndNestedEnvironmentsAreValid() {
        assertNull(LatexSource.problem("\\left\\{\\frac{a}{b}\\right\\}"))
        assertNull(LatexSource.problem("\\begin{aligned}x &= \\begin{matrix}a & b\\end{matrix}\\end{aligned}"))
    }

    @Test fun damagedOrUnboundedMathCannotBeInserted() {
        assertEquals("braces", LatexSource.problem("\\frac{x}{"))
        assertEquals("braces", LatexSource.problem("}x{"))
        assertEquals("environment", LatexSource.problem("\\begin{matrix}x\\end{cases}"))
        assertEquals("environment", LatexSource.problem("\\begin{matrix}x"))
        assertEquals("document", LatexSource.problem("\\input{file}"))
        assertEquals("empty", LatexSource.problem("  "))
        assertEquals("length", LatexSource.problem("x".repeat(4097)))
    }

    @Test fun replacingDisjointSelectionKeepsNeighbouringItemsAndExactUndoOrder() {
        val a = item(); val b = item(); val c = item(); val d = item(); val formula = item("x=1")
        val before = listOf(a, b, c, d)
        val after = FormulaReplacement.items(before, listOf(b, d), formula, false)
        assertEquals(listOf(a, formula, c), after)
        val page = Page(1000.0, 1400.0, before.toMutableList())
        val command = ReplacePageItems(page, before, after)
        command.redo(); assertEquals(after, page.items.toList())
        command.undo(); assertEquals(before, page.items.toList())
        command.redo(); assertEquals(after, page.items.toList())
    }

    @Test fun keepingInkAndUndoAlsoWorksOnInfiniteCanvas() {
        val a = item(); val b = item(); val formula = item("x=1")
        val before = listOf(a, b)
        val after = FormulaReplacement.items(before, listOf(a), formula, true)
        assertEquals(listOf(formula, a, b), after)
        val doc = InfiniteDocument().also { it.addAll(before) }
        val command = ReplaceCanvasItems(doc, before, after)
        command.redo(); assertTrue(doc.containsRef(formula)); assertEquals(after, doc.items.toList())
        command.undo(); assertFalse(doc.containsRef(formula)); assertEquals(before, doc.items.toList())
        command.redo(); assertEquals(after, doc.items.toList())
    }

    @Test fun staleAndEmptySelectionCannotDropOtherContent() {
        val original = item(); val foreign = item(); val formula = item("x")
        assertThrows(IllegalArgumentException::class.java) { FormulaReplacement.items(listOf(original), listOf(foreign), formula, false) }
        assertThrows(IllegalArgumentException::class.java) { FormulaReplacement.items(listOf(original), emptyList(), formula, false) }
    }

    @Test fun copiedAndMovedFormulaKeepsEditableSourceWithoutMovingOriginal() {
        val original = item("\\frac{1}{2}")
        val copy = original.deepCopy(FakeTextMeasurer()) as ImageItem
        val move = MoveItems(listOf(copy), 125.0, -20.0)
        move.redo()
        assertEquals(Rect(135.0, 0.0, 200.0, 80.0), copy.rect)
        assertEquals(Rect(10.0, 20.0, 200.0, 80.0), original.rect)
        assertEquals(original.formula, copy.formula)
        assertFalse(copy.mirrorable)
        move.undo(); assertEquals(original.rect, copy.rect)
    }
}
