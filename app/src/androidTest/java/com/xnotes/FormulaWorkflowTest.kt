package com.xnotes

import android.graphics.Bitmap
import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xnotes.core.geometry.Pt
import com.xnotes.core.model.ImageItem
import com.xnotes.core.model.Stroke
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolConfig
import com.xnotes.platform.FormulaDraft
import com.xnotes.ui.Editor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.ExternalResource
import java.io.File

/** Runs the real Android OCR, editor, lasso, dialog, renderer and undo stack; no mock recognizer. */
@RunWith(AndroidJUnit4::class)
class FormulaWorkflowTest {
    // The production splash waits for a Compose SideEffect before allowing the first draw.
    // Espresso's launch-idle wait precedes its test clock, so release only that visual gate.
    // The real activity, session restore, editor, fonts and OCR still run unchanged.
    @get:Rule(order = 0) val firstFrame = object : ExternalResource() {
        private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        private val callback = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                if (activity is EditorActivity) EditorActivity::class.java.getDeclaredField("launched").apply {
                    isAccessible = true
                    setBoolean(activity, true)
                }
            }
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        }
        override fun before() { app.registerActivityLifecycleCallbacks(callback) }
        override fun after() { app.unregisterActivityLifecycleCallbacks(callback) }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<EditorActivity>()

    @Before fun awaitRestoredSession() { editor() }

    private fun editor(): Editor {
        val field = EditorActivity::class.java.getDeclaredField("editor").apply { isAccessible = true }
        val loaded = Editor::class.java.getDeclaredField("sessionLoaded").apply { isAccessible = true }
        var value: Editor? = null
        compose.waitUntil(20_000) {
            value = field.get(compose.activity) as? Editor
            value?.let { loaded.getBoolean(it) } == true
        }
        return value!!
    }

    private fun line(vararg xy: Double): Stroke = Stroke(
        Tool.PEN, ToolConfig(baseWidth = 3.5, pressureEnabled = false),
        xy.toList().chunked(2).map { Sample(it[0], it[1], 1.0) }, straight = xy.size == 4,
    )

    /** Synthetic pen-path fixture for UI actions only; not part of the accuracy benchmark. */
    private fun handwriting() = listOf(
        line(200.0, 300.0, 207.0, 305.0, 215.0, 320.0, 229.0, 340.0, 239.0, 346.0),
        line(238.0, 299.0, 225.0, 312.0, 213.0, 329.0, 200.0, 346.0),
        line(267.0, 314.0, 306.0, 313.0), line(267.0, 332.0, 306.0, 331.0),
        line(338.0, 310.0, 348.0, 299.0, 349.0, 319.0, 350.0, 346.0),
    )

    @Test fun rendererSupportsCommonTeachingFormulasAndRejectsUnknownCommands() {
        compose.waitForIdle()
        val draft = FormulaDraft(handwriting(), compose.activity.cacheDir) { _, _ -> false }
        val templates = com.xnotes.platform.FormulaPacks.load(compose.activity).flatMap { it.templates }.map { it.latex }
        for (source in listOf("X=1", "\\frac{x^{2}+1}{2}", "\\int_0^1 x\\,dx", "\\begin{pmatrix}a&b\\\\c&d\\end{pmatrix}") + templates) {
            val rendered = runBlocking(Dispatchers.Default) { draft.render(source) }
            assertTrue(source, rendered.bitmap.width > 20 && rendered.bitmap.height > 20)
            rendered.bitmap.recycle()
        }
        assertFalse(com.xnotes.platform.LatexInternals.recognizesMath("\\thisCommandDoesNotExist{x}"))
        assertFalse(com.xnotes.platform.LatexInternals.recognizesMath("x^2^3"))
        assertFalse(com.xnotes.platform.LatexInternals.recognizesMath("x^"))
        assertFalse(com.xnotes.platform.LatexInternals.recognizesMath("x_{}"))
    }

    @Test fun spacedOcrScriptsAttachToTheirBaseAndMatchCompactRendering() {
        compose.waitForIdle()
        val draft = FormulaDraft(handwriting(), compose.activity.cacheDir) { _, _ -> false }
        for ((spaced, compact) in listOf(
            "e ^ {\\arctan(1+x)}" to "e^{\\arctan(1+x)}",
            "x _ {i} ^ {2}" to "x_{i}^{2}",
            "\\int _ {0} ^ {1} x\\,dx" to "\\int_{0}^{1} x\\,dx",
            "\\sum _ {n=1} ^ {\\infty} a _ n" to "\\sum_{n=1}^{\\infty} a_n",
        )) {
            val a = runBlocking(Dispatchers.Default) { draft.render(spaced) }
            val b = runBlocking(Dispatchers.Default) { draft.render(compact) }
            assertTrue("OCR whitespace must not change formula layout: $spaced", a.bitmap.sameAs(b.bitmap))
            a.bitmap.recycle(); b.bitmap.recycle()
        }
        val clean = com.xnotes.core.formula.LatexSource.clean("e ^ { a v c \\tan c 1 + x }")
        val script = com.xnotes.platform.LatexInternals.parse(clean).single() as com.hrm.latex.parser.model.LatexNode.Superscript
        assertEquals("e", (script.base as com.hrm.latex.parser.model.LatexNode.Text).content)
        assertTrue(script.exponent is com.hrm.latex.parser.model.LatexNode.Group)
    }

    private fun gesture(ed: Editor, localPoints: List<Pt>) {
        val offset = ed.state.pageRects.first()
        val points = localPoints.map { ed.state.contentToViewport(Pt(it.x + offset.x, it.y + offset.y)) }
        val down = SystemClock.uptimeMillis()
        val properties = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_STYLUS })
        points.forEachIndexed { index, point ->
            val coords = arrayOf(MotionEvent.PointerCoords().apply { x = point.x.toFloat(); y = point.y.toFloat(); pressure = 1f; size = 1f })
            val action = when (index) { 0 -> MotionEvent.ACTION_DOWN; points.lastIndex -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE }
            val event = MotionEvent.obtain(down, down + index * 16L, action, 1, properties, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0)
            ed.controller.onTouch(event)
            event.recycle()
        }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val test = InstrumentationRegistry.getInstrumentation()
        test.uiAutomation.waitForIdle(250, 5_000)
        val output = File(test.targetContext.getExternalFilesDir(null), name)
        test.uiAutomation.takeScreenshot().let { bitmap ->
            output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    @Test fun lassoRecognizeInsertDragEditAndUndo() {
        compose.waitForIdle()
        val ed = editor()
        val ink = handwriting()
        val neighbour = line(550.0, 550.0, 600.0, 590.0)
        compose.runOnIdle { ed.newNote() }
        compose.runOnIdle {
            ed.state.document.pages.first().items.addAll(ink + neighbour)
            ed.state.refreshAllInk()
            ed.view.requestRender()
            ed.controller.setTool(Tool.LASSO)
            gesture(ed, listOf(Pt(170.0, 270.0), Pt(385.0, 260.0), Pt(390.0, 375.0), Pt(175.0, 380.0), Pt(170.0, 270.0)))
            assertEquals(ink.toSet(), ed.controller.selectedItems().toSet())
        }
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.formula_convert)).performClick()
        val insert = compose.activity.getString(R.string.formula_insert)
        compose.waitUntil(240_000) { compose.onAllNodes(hasText(insert) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        val recognized = compose.onNode(hasSetTextAction()).fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text
        assertEquals("x=1", recognized.replace(" ", "").lowercase())
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.formula_preview)).assertIsDisplayed()
        screenshot("formula-dialog.png")
        val catalog = com.xnotes.platform.FormulaPacks.load(compose.activity)
        val beforePacks = com.xnotes.platform.FormulaPacks.selected(compose.activity, catalog)
        val packButton = compose.activity.getString(R.string.formula_packs_count, beforePacks.size)
        compose.onNodeWithText(packButton).performScrollTo().performClick()
        val quantum = catalog.first()
        val chinese = compose.activity.resources.configuration.locales[0].language == "zh"
        compose.onAllNodesWithText(quantum.name(chinese)).onFirst().performScrollTo().performClick()
        assertEquals(beforePacks.contains(quantum.id).not(), com.xnotes.platform.FormulaPacks.selected(compose.activity, catalog).contains(quantum.id))
        screenshot("formula-subject-packs.png")
        // Restore the persisted selection through the same UI before continuing.
        compose.onAllNodesWithText(quantum.name(chinese)).onFirst().performScrollTo().performClick()
        compose.onNodeWithText(if (chinese) "管理 / 下载" else "Manage / download").performScrollTo().performClick()
        compose.onNodeWithText(if (chinese) "学科包管理" else "Subject pack manager").assertIsDisplayed()
        screenshot("formula-pack-manager.png")
        compose.onNodeWithText(if (chinese) "完成" else "Done").performClick()
        compose.onNodeWithText(packButton).performScrollTo().performClick()
        compose.onNodeWithText(insert).performClick()
        compose.waitUntil(15_000) { ed.state.document.pages.first().items.filterIsInstance<ImageItem>().any { it.formula != null } }
        lateinit var block: ImageItem
        compose.runOnIdle {
            val items = ed.state.document.pages.first().items
            block = items.filterIsInstance<ImageItem>().single()
            assertEquals(recognized, block.formula!!.latex)
            assertTrue(items.contains(neighbour))
            assertFalse(items.any { it in ink })
            val original = block.rect
            val center = original.center
            gesture(ed, listOf(center, Pt(center.x + 60, center.y + 50), Pt(center.x + 120, center.y + 100), Pt(center.x + 120, center.y + 100)))
            assertTrue("A formula block must be draggable", block.rect.x > original.x + 50)
        }
        screenshot("formula-block.png")
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.formula_convert)).performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("\\frac{x^{2}+1}{2}")
        val update = compose.activity.getString(R.string.formula_update)
        compose.waitUntil(15_000) { compose.onAllNodes(hasText(update) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.formula_preview)).assertIsDisplayed()
        screenshot("formula-edit.png")
        compose.onNodeWithText(update).performClick()
        compose.waitUntil(15_000) { ed.state.document.pages.first().items.filterIsInstance<ImageItem>().singleOrNull()?.formula?.latex == "\\frac{x^{2}+1}{2}" }
        compose.runOnIdle {
            ed.undo() // edit
            assertEquals(recognized, ed.state.document.pages.first().items.filterIsInstance<ImageItem>().single().formula!!.latex)
            ed.undo() // drag
            ed.undo() // conversion
            assertEquals(ink + neighbour, ed.state.document.pages.first().items.toList())
            ed.redo()
            assertEquals(1, ed.state.document.pages.first().items.filterIsInstance<ImageItem>().count { it.formula != null })
        }
    }

    @Test fun cancelledRecognitionNeverChangesInkAndInvalidSourceCannotCommit() {
        compose.waitForIdle()
        val ed = editor()
        val ink = handwriting()
        compose.runOnIdle { ed.newNote() }
        compose.runOnIdle {
            ed.state.document.pages.first().items.addAll(ink)
            ed.state.refreshAllInk()
            ed.controller.selectPlaced(0, ink)
        }
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.formula_convert)).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.cancel)).performClick()
        compose.runOnIdle { assertEquals(ink, ed.state.document.pages.first().items.toList()) }
        // Validation is independent of OCR success and never replaces the original ink.
        val draft = FormulaDraft(ink, compose.activity.cacheDir) { _, _ -> error("Must not commit") }
        assertThrows(IllegalArgumentException::class.java) { draft.render("\\frac{x}{") }
        assertThrows(IllegalArgumentException::class.java) { draft.render("\\thisCommandDoesNotExist{x}") }
    }

    @Test fun infiniteCanvasKeepsInkSelectsNewBlockAndRejectsStaleResult() {
        compose.waitForIdle()
        val ed = editor()
        val ink = handwriting()
        lateinit var draft: FormulaDraft
        compose.runOnIdle {
            val canvas = ed.infinite
            canvas.replaceDocument(com.xnotes.core.infinite.InfiniteDocument().also { it.addAll(ink) })
            canvas.armTool(Tool.LASSO)
            canvas.selection.select(ink)
            draft = canvas.createFormulaDraft()!!
        }
        val rendered = runBlocking(Dispatchers.Default) { draft.render("\\frac{x^{2}+1}{2}") }
        val pixels = IntArray(rendered.bitmap.width * rendered.bitmap.height)
        rendered.bitmap.getPixels(pixels, 0, rendered.bitmap.width, 0, 0, rendered.bitmap.width, rendered.bitmap.height)
        assertTrue("The native renderer must paint visible math", pixels.count { android.graphics.Color.alpha(it) > 100 } > 100)
        assertEquals("The cache must have a transparent margin", 0, android.graphics.Color.alpha(pixels.first()))
        val block = draft.block(rendered, true)
        compose.runOnIdle {
            val canvas = ed.infinite
            assertTrue(draft.apply(block, true))
            assertEquals(listOf(block), canvas.selection.items)
            assertTrue(canvas.document.items.containsAll(ink))
            assertTrue(block.rect.y > draft.bounds.bottom)
            canvas.undo()
            assertEquals(ink, canvas.document.items)
            canvas.redo()
            assertTrue(canvas.document.containsRef(block))
            canvas.selection.select(ink)
            draft = canvas.createFormulaDraft()!!
            ink.first().translate(30.0, 0.0)
            assertFalse(draft.apply(block, false))
            assertTrue(canvas.document.items.containsAll(ink))
        }
    }
}
