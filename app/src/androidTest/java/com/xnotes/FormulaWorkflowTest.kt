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

/** Runs the real editor, lasso, renderer and undo stack without requiring a cloud account. */
@RunWith(AndroidJUnit4::class)
class FormulaWorkflowTest {
    // The production splash waits for a Compose SideEffect before allowing the first draw.
    // Espresso's launch-idle wait precedes its test clock, so release only that visual gate.
    // The real activity, session restore, editor and fonts still run unchanged.
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

    private fun canvasMode(ed: Editor, value: Boolean) {
        @Suppress("UNCHECKED_CAST")
        val state = Editor::class.java.getDeclaredField("canvasOpen\$delegate").apply { isAccessible = true }.get(ed) as androidx.compose.runtime.MutableState<Boolean>
        state.value = value
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

    @Test fun toolbarImagePickerInsertsVisibleSelectedImageAndSupportsUndoInBothEditors() {
        val ed = editor()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val fixture = File(context.cacheDir, "share/picker-fixture.png").also { it.parentFile!!.mkdirs() }
        fixture.writeBytes(teachingPicture().image.file.readBytes())
        val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".fileprovider", fixture)
        val filter = android.content.IntentFilter(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(android.content.Intent.CATEGORY_OPENABLE); addDataType("image/*")
        }
        for (canvas in listOf(false, true)) {
            compose.runOnIdle {
                canvasMode(ed, false); ed.newNote()
                if (canvas) { ed.infinite.replaceDocument(com.xnotes.core.infinite.InfiniteDocument()); canvasMode(ed, true) }
            }
            compose.waitForIdle()
            val monitor = instrumentation.addMonitor(filter, android.app.Instrumentation.ActivityResult(Activity.RESULT_OK,
                android.content.Intent().setData(uri).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)), true)
            try {
                compose.onNodeWithContentDescription(context.getString(R.string.insert)).performClick()
                compose.onNodeWithText(context.getString(R.string.insert_image_ellipsis)).performClick()
                compose.waitUntil(15_000) {
                    (if (canvas) ed.infinite.document.items else ed.state.document.pages.first().items).any { it is ImageItem }
                }
                compose.runOnIdle {
                    assertTrue("Real OpenDocument launcher was used", monitor.hits > 0)
                    val items = if (canvas) ed.infinite.document.items else ed.state.document.pages.first().items
                    val image = items.filterIsInstance<ImageItem>().single()
                    assertTrue(image.image.file.isFile); assertTrue(image.rect.w > 0)
                    if (canvas) {
                        assertEquals(listOf(image), ed.infinite.selection.items)
                        assertEquals(ed.infinite.viewport.centerContent, image.rect.center)
                        ed.infinite.undo(); assertTrue(ed.infinite.document.items.isEmpty()); ed.infinite.redo()
                    } else {
                        assertEquals(listOf(image), ed.controller.selectedItems())
                        assertEquals(ed.state.document.pages.first().width / 2, image.rect.center.x, 1e-6)
                        ed.undo(); assertTrue(ed.state.document.pages.first().items.isEmpty()); ed.redo()
                    }
                }
                screenshot(if (canvas) "toolbar-image-infinite.png" else "toolbar-image-paged.png")
            } finally { instrumentation.removeMonitor(monitor) }
        }
        compose.runOnIdle { canvasMode(ed, false) }
    }

    @Test fun offlineOcrOptionsAreEditableInMainPreferences() {
        val ed = editor(); val context = compose.activity
        val original = com.xnotes.platform.OfflineMathSettings.load(context)
        try {
            compose.runOnIdle { ed.goHome() }
            compose.onNodeWithText(context.getString(R.string.preferences)).performClick()
            compose.onNodeWithTag("offline-math-preferences").assertExists()
            compose.onNodeWithText(context.getString(R.string.math_mode_balanced)).performClick()
            assertEquals(com.xnotes.platform.MathRecognitionMode.BALANCED, com.xnotes.platform.OfflineMathSettings.load(context).mode)
            screenshot("offline-ocr-preferences.png")
        } finally { com.xnotes.platform.OfflineMathSettings.save(context, original) }
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

    @Test fun mathWhitespaceIsInvisibleButTextAndExplicitSpacingSurvive() {
        compose.waitForIdle()
        val draft = FormulaDraft(handwriting(), compose.activity.cacheDir) { _, _ -> false }
        for ((spaced, compact) in listOf(
            "1 2 . 3 4 x + y = 5" to "12.34x+y=5",
            "e ^ { \\arctan ( 1 + x ) }" to "e^{\\arctan(1+x)}",
            "\\frac { 1 5 } { 4 } - 1 0 x" to "\\frac{15}{4}-10x",
            "\\begin {pmatrix} a & b \\\\ c & d \\end {pmatrix}" to "\\begin{pmatrix}a&b\\\\c&d\\end{pmatrix}",
            "\\sin   x + \\alpha   y" to "\\sin{x}+\\alpha{}y",
            "\\frac a b" to "\\frac{a}{b}",
            "a b ^ 2 + x _ i j" to "ab^2+x_ij",
            "x% comment\n + y" to "x+y",
            "\\text { for all } x > 0" to "\\text{ for all }x>0",
        )) {
            val a = runBlocking(Dispatchers.Default) { draft.render(spaced) }
            val b = runBlocking(Dispatchers.Default) { draft.render(compact) }
            assertTrue("Whitespace changed pixels: $spaced", a.bitmap.sameAs(b.bitmap))
            a.bitmap.recycle(); b.bitmap.recycle()
        }
        val compact = runBlocking(Dispatchers.Default) { draft.render("ab") }
        for (spaced in listOf("a\\,b", "a\\quad b", "a\\ b", "a~b")) {
            val explicit = runBlocking(Dispatchers.Default) { draft.render(spaced) }
            assertTrue("Explicit spacing must remain: $spaced", explicit.bitmap.width > compact.bitmap.width)
            explicit.bitmap.recycle()
        }
        compact.bitmap.recycle()
        val a = runBlocking(Dispatchers.Default) { draft.render("\\text{a b}") }
        val b = runBlocking(Dispatchers.Default) { draft.render("\\text{ab}") }
        assertTrue("Text word spaces must remain", a.bitmap.width > b.bitmap.width)
        a.bitmap.recycle(); b.bitmap.recycle()
        val nodes = com.xnotes.platform.LatexInternals.parse("ab^2")
        assertEquals(2, nodes.size)
        val power = nodes.last() as com.hrm.latex.parser.model.LatexNode.Superscript
        assertEquals("b", (power.base as com.hrm.latex.parser.model.LatexNode.Text).content)
        assertTrue(com.xnotes.platform.LatexInternals.parse("\\alpha x").none { it is com.hrm.latex.parser.model.LatexNode.Space })
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
            ed.view.input!!.invoke(event)
            event.recycle()
        }
    }

    private fun cutLine(session: com.xnotes.ui.ImageCutSession, a: Pt, b: Pt) {
        val start = session.screen(a); val end = session.screen(b)
        compose.onNodeWithTag("image-cut-canvas").performTouchInput {
            swipe(androidx.compose.ui.geometry.Offset(start.x.toFloat(), start.y.toFloat()),
                androidx.compose.ui.geometry.Offset(end.x.toFloat(), end.y.toFloat()), 500)
        }
    }

    /** Real MathWriting pen traces, replayed through the actual whiteboard input callback. */
    private fun realInk(): List<List<Pt>> {
        val folder = File(compose.activity.filesDir, "handwriting-v5")
        val entry = org.json.JSONArray(File(folder, "manifest.json").readText()).getJSONObject(1)
        val json = org.json.JSONArray(File(folder, entry.getString("strokes")).readText())
        val traces = List(json.length()) { i -> json.getJSONArray(i).let { t -> List(t.length()) { j -> t.getJSONArray(j).let { Pt(it.getDouble(0), it.getDouble(1)) } } } }
        val all = traces.flatten(); val left = all.minOf { it.x }; val top = all.minOf { it.y }
        val scale = minOf(280.0 / (all.maxOf { it.x } - left), 120.0 / (all.maxOf { it.y } - top))
        return traces.map { t -> t.map { Pt(180 + (it.x-left)*scale, 220 + (it.y-top)*scale) } }
    }

    @Test fun inlineRealInkConvertsInPlaceWithPenColorAndRestoresAfterMoving() {
        val ed = editor(); val context = compose.activity
        val ink = realInk(); val color = com.xnotes.core.model.Rgba(25, 100, 195)
        compose.runOnIdle { canvasMode(ed, false); ed.newNote(); ed.controller.setTool(Tool.PEN); ed.controller.pickInk(color) }
        compose.onNodeWithContentDescription(context.getString(R.string.math_inline)).performClick()
        compose.onNodeWithTag("math-pad-panel").assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.tool_screenshot)).assertDoesNotExist()
        compose.runOnIdle { ink.forEach { gesture(ed, it) } }
        compose.waitUntil(60_000) { ed.state.document.pages.first().items.any { it is ImageItem && it.formula != null } }
        lateinit var block: ImageItem
        compose.runOnIdle {
            val items = ed.state.document.pages.first().items
            block = items.filterIsInstance<ImageItem>().single()
            assertFalse(items.any { it is Stroke }); assertEquals(color, block.formula!!.color)
            assertNotNull(block.formula!!.ink); assertEquals(Tool.PEN, ed.controller.tool)
            assertTrue(kotlin.math.abs(block.rect.left-180.0) < 30)
            assertTrue(kotlin.math.abs(block.rect.top-220.0) < 30)
            assertTrue(block.formula!!.ink!!.strokes.all { it.config.rgba == color })
            val bitmap = android.graphics.BitmapFactory.decodeFile(block.image.file.path)
            val pixels = IntArray(bitmap.width * bitmap.height); bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            assertTrue("Rendered pixels must use the pen color", pixels.any { android.graphics.Color.alpha(it) > 240 &&
                kotlin.math.abs(android.graphics.Color.blue(it)-color.b) < 3 && kotlin.math.abs(android.graphics.Color.red(it)-color.r) < 3 })
            bitmap.recycle()
            // Exercise the real ZIP codec and PNG assets before moving and restoring the block.
            val codec = com.xnotes.format.DocumentCodec(com.xnotes.platform.AndroidImageCodec(), com.xnotes.platform.AndroidTextMeasurer())
            val bytes = java.io.ByteArrayOutputStream(); codec.write(ed.state.document, bytes)
            val reopened = codec.read(java.io.ByteArrayInputStream(bytes.toByteArray()), imageDir = File(context.cacheDir, "inline-reopened").apply { mkdirs() })
            val saved = reopened.pages.first().items.filterIsInstance<ImageItem>().single()
            assertNotNull(saved.formula!!.ink)
            items[items.indexOf(block)] = saved; block = saved
            ed.state.refreshAllInk()
            ed.controller.setTool(Tool.SELECT); ed.controller.selectPlaced(0, listOf(block))
        }
        screenshot("inline-formula-color.png")
        compose.runOnIdle {
            val center = block.rect.center
            gesture(ed, listOf(center, center + Pt(20.0,30.0), center + Pt(50.0,50.0), center + Pt(50.0,50.0)))
        }
        compose.onNodeWithContentDescription(context.getString(R.string.math_restore_ink)).performClick()
        compose.runOnIdle {
            val restored = ed.state.document.pages.first().items.filterIsInstance<Stroke>()
            assertTrue(restored.isNotEmpty()); assertTrue(ed.inlineMath.waitingForEdit)
            assertTrue(restored.all { it.config.rgba == color })
            assertFalse(ed.state.document.pages.first().items.any { it is ImageItem })
        }
        screenshot("inline-formula-restored.png")
        // A subsequent stroke extends the restored batch rather than discarding it.
        compose.runOnIdle { gesture(ed, listOf(Pt(460.0, 320.0), Pt(470.0, 320.0), Pt(480.0, 320.0))) }
        compose.waitUntil(60_000) { ed.state.document.pages.first().items.any { it is ImageItem } }
        compose.runOnIdle {
            ed.undo(); assertTrue(ed.state.document.pages.first().items.any { it is Stroke })
            assertTrue(ed.inlineMath.strokes.isEmpty())
            ed.redo(); assertTrue(ed.state.document.pages.first().items.any { it is ImageItem })
        }
        compose.onNodeWithContentDescription(context.getString(R.string.math_inline)).performClick()
        compose.runOnIdle { assertFalse(ed.inlineMath.enabled) }
    }

    @Test fun infiniteInlineFormulaAndRotatedInlineCutUseCanvasCoordinates() {
        val ed = editor(); val context = compose.activity
        compose.runOnIdle {
            ed.infinite.replaceDocument(com.xnotes.core.infinite.InfiniteDocument()); canvasMode(ed, true); ed.newNote()
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription(context.getString(R.string.math_inline)).performClick()
        val color = com.xnotes.core.model.Rgba(170, 45, 100)
        compose.runOnIdle {
            val canvas = ed.infinite
            canvas.armTool(Tool.PEN)
            val samples = realInk().map { pts -> Stroke(Tool.PEN, ToolConfig(rgba = color), pts.map { Sample(it.x,it.y,1.0) }) }
            canvas.document.addAll(samples)
            samples.forEach { ed.inlineMath.offer(canvas.document, it) }
        }
        compose.waitUntil(60_000) { ed.infinite.document.items.any { it is ImageItem && it.formula != null } }
        compose.runOnIdle {
            val block = ed.infinite.document.items.filterIsInstance<ImageItem>().single()
            assertEquals(color, block.formula!!.color)
            ed.infinite.armTool(Tool.SELECT); ed.infinite.selection.select(listOf(block)); ed.infinite.restoreFormulaInk()
            assertTrue(ed.infinite.document.items.all { it is Stroke }); assertTrue(ed.inlineMath.waitingForEdit)
            ed.inlineMath.toggle()
            ed.infinite.replaceDocument(com.xnotes.core.infinite.InfiniteDocument())
            ed.infinite.insertImage(teachingPicture().image.file.readBytes())
            val picture = ed.infinite.document.items.filterIsInstance<ImageItem>().single()
            picture.angle = .3; ed.infinite.document.itemsChanged(listOf(picture))
            ed.infinite.selection.select(listOf(picture))
        }
        compose.onNodeWithContentDescription(context.getString(R.string.image_cut_tool)).performClick()
        cutLine(ed.infinite.imageCut!!, Pt(.05,.15), Pt(.95,.85))
        compose.onNodeWithTag("image-cut-apply").assertIsEnabled()
        screenshot("inline-cut-rotated-infinite.png")
        compose.onNodeWithTag("image-cut-apply").performClick()
        compose.waitUntil(15_000) { ed.infinite.document.items.size == 2 }
        compose.runOnIdle { ed.infinite.undo(); assertEquals(1, ed.infinite.document.items.size); canvasMode(ed, false) }
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

    private fun showPreview() {
        // Editing opens the IME and shortens the dialog's scroll viewport. Dismiss it
        // as a user would before inspecting the entire preview, then scroll to it.
        val imeVisible = androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
            ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
        if (imeVisible) InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.waitUntil(5000) {
            androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) != true
        }
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.formula_preview))
            .performScrollTo().assertIsDisplayed()
    }

    @Test fun lassoManualFormulaInsertDragEditAndUndo() {
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
        compose.onNodeWithTag("offline-settings").assertExists()
        compose.onNodeWithText(insert).assertIsNotEnabled()
        assertEquals("Opening the dialog must not submit handwriting", "", compose.onNode(hasSetTextAction()).fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text)
        val recognized = "x=1"
        compose.onNode(hasSetTextAction()).performTextReplacement(recognized)
        compose.waitUntil(15_000) { compose.onAllNodes(hasText(insert) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        showPreview()
        screenshot("formula-dialog.png")
        val catalog = com.xnotes.platform.FormulaPacks.load(compose.activity)
        val beforePacks = com.xnotes.platform.FormulaPacks.selected(compose.activity, catalog)
        val packButton = compose.activity.getString(R.string.formula_packs_count, beforePacks.size)
        compose.onNodeWithText(packButton).performScrollTo().performClick()
        val quantum = catalog.first()
        val chinese = compose.activity.resources.configuration.locales[0].language == "zh"
        compose.onNode(hasSetTextAction()).performTextReplacement("x=2")
        compose.onAllNodesWithText(quantum.name(chinese)).onFirst().performScrollTo().performClick()
        assertEquals("Switching packages must preserve manual edits", "x=2", compose.onNode(hasSetTextAction()).fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text)
        assertEquals(beforePacks.contains(quantum.id).not(), com.xnotes.platform.FormulaPacks.selected(compose.activity, catalog).contains(quantum.id))
        screenshot("formula-subject-packs.png")
        // Restore the persisted selection through the same UI before continuing.
        compose.onAllNodesWithText(quantum.name(chinese)).onFirst().performScrollTo().performClick()
        compose.onNodeWithText(if (chinese) "管理 / 下载" else "Manage / download").performScrollTo().performClick()
        compose.onNodeWithText(if (chinese) "学科包管理" else "Subject pack manager").assertIsDisplayed()
        screenshot("formula-pack-manager.png")
        compose.onNodeWithText(if (chinese) "完成" else "Done").performClick()
        compose.onNodeWithText(packButton).performScrollTo().performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement(recognized)
        compose.waitUntil(15_000) { compose.onAllNodes(hasText(insert) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
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
        showPreview()
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

    @Test fun closingDialogNeverChangesInkAndInvalidSourceCannotCommit() {
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

    private fun teachingPicture(): ImageItem {
        val bitmap = Bitmap.createBitmap(800, 500, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.WHITE)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.BLACK; textSize = 38f }
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawText("1. Find the derivative", 35f, 65f, paint)
        canvas.drawText("y = x^3 + 2x", 65f, 140f, paint)
        canvas.drawText("2. Solve the system", 35f, 320f, paint)
        canvas.drawText("x + y = 5     x - y = 1", 65f, 400f, paint)
        val file = File(compose.activity.cacheDir, "split-teaching.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        return ImageItem(com.xnotes.core.model.ImageData(file, 800, 500), com.xnotes.core.geometry.Rect(110.0, 220.0, 440.0, 275.0))
    }

    @Test fun splitImagePreviewCancelCutPickMoveRecutUndoAndRedo() {
        val ed = editor()
        val fixture = teachingPicture()
        lateinit var original: ImageItem
        compose.runOnIdle { ed.newNote() }
        compose.runOnIdle {
            ed.insertImage(fixture.image.file.readBytes())
            original = ed.state.document.pages.first().items.filterIsInstance<ImageItem>().single()
            ed.state.refreshAllInk(); ed.view.requestRender()
            ed.controller.setTool(Tool.SELECT); ed.controller.selectPlaced(0, listOf(original))
        }
        val split = compose.activity.getString(R.string.image_cut_tool)
        compose.onNodeWithContentDescription(split).performClick()
        compose.onNodeWithTag("image-cut-overlay").assertExists()
        compose.onNodeWithTag("image-cut-apply").assertIsNotEnabled()
        compose.onNodeWithText(compose.activity.getString(R.string.cancel)).performClick()
        compose.runOnIdle { assertEquals(listOf(original), ed.state.document.pages.first().items.toList()) }
        compose.onNodeWithContentDescription(split).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("image-cut-canvas").fetchSemanticsNodes().isNotEmpty() }
        cutLine(ed.imageCut!!, Pt(.03, .5), Pt(.97, .5))
        compose.onNodeWithTag("image-cut-apply").assertIsEnabled()
        screenshot("image-cut-canvas.png")
        compose.onNodeWithTag("image-cut-apply").performClick()
        compose.waitUntil(15_000) { ed.state.document.pages.first().items.size == 2 }
        lateinit var first: ImageItem
        compose.runOnIdle {
            val pieces = ed.state.document.pages.first().items.filterIsInstance<ImageItem>()
            assertEquals(2, pieces.size); assertTrue(pieces.all { it.group == null })
            first = pieces.minBy { it.rect.y }
            val center = first.rect.center
            gesture(ed, listOf(center, center)) // select just this piece
            assertEquals(listOf(first), ed.controller.selectedItems())
            gesture(ed, listOf(center, Pt(center.x + 35, center.y - 45), Pt(center.x + 90, center.y - 90), Pt(center.x + 90, center.y - 90)))
            assertTrue(first.rect.x > original.rect.x + 50)
            assertEquals(original.rect.x, pieces.single { it !== first }.rect.x, 1e-8)
        }
        screenshot("image-split-pieces.png")
        compose.onNodeWithContentDescription(split).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("image-cut-canvas").fetchSemanticsNodes().isNotEmpty() }
        cutLine(ed.imageCut!!, Pt(.5, .25), Pt(.5, .85))
        compose.onNodeWithTag("image-cut-apply").assertIsEnabled()
        compose.onNodeWithTag("image-cut-apply").performClick()
        compose.waitUntil(15_000) { ed.state.document.pages.first().items.size == 3 }
        compose.runOnIdle {
            ed.undo(); assertEquals(2, ed.state.document.pages.first().items.size)
            ed.undo() // move
            ed.undo(); assertEquals(listOf(original), ed.state.document.pages.first().items.toList())
            ed.redo(); assertEquals(2, ed.state.document.pages.first().items.size)
        }
    }

    @Test fun infiniteImageSplitPreservesNeighboursAndRejectsStaleDraft() {
        val ed = editor()
        val original = teachingPicture()
        val neighbour = line(550.0, 550.0, 600.0, 590.0)
        lateinit var draft: com.xnotes.platform.ImageSplitDraft
        compose.runOnIdle {
            val canvas = ed.infinite
            canvas.replaceDocument(com.xnotes.core.infinite.InfiniteDocument().also { it.addAll(listOf(original, neighbour)) })
            canvas.selection.select(listOf(original))
            draft = canvas.createImageSplitDraft()!!
        }
        val pieces = runBlocking(Dispatchers.IO) { draft.split(Pt(0.0, 0.0), Pt(1.0, 1.0)) }
        compose.runOnIdle {
            val canvas = ed.infinite
            assertTrue(draft.apply(pieces))
            assertEquals(pieces + neighbour, canvas.document.items)
            assertEquals(1, pieces.count { it.contains(Pt(original.rect.x + 30, original.rect.y + 170)) })
            canvas.undo(); assertEquals(listOf(original, neighbour), canvas.document.items)
            canvas.redo(); assertEquals(3, canvas.document.items.size)
            canvas.selection.select(listOf(pieces.first()))
            draft = canvas.createImageSplitDraft()!!
            pieces.first().translate(40.0, 0.0)
            assertFalse(draft.apply(pieces))
            assertTrue(canvas.document.containsRef(neighbour))
        }
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
