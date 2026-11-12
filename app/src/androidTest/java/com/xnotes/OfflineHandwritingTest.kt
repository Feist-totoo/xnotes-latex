package com.xnotes

import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xnotes.platform.FormulaPacks
import com.xnotes.platform.LocalFormulaRecognizer
import com.xnotes.platform.LatexInternals
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Public human pen traces, plus the user's private failing image (test assets only).
 * Reports every prediction; a parseable formula is NOT counted as correct handwriting.
 */
@RunWith(AndroidJUnit4::class)
class OfflineHandwritingTest {
    @Test fun modelReuseKeepsOutputsAndRecordsStageTimings() = runBlocking {
        val test = InstrumentationRegistry.getInstrumentation()
        val assets = test.context.assets
        val source = if (assets.list("handwriting").orEmpty().contains("user-formula.png")) "user-formula.png"
            else JSONArray(assets.open("handwriting/manifest.json").bufferedReader().use { it.readText() }).getJSONObject(0).getString("image")
        val bitmap = assets.open("handwriting/$source").use { BitmapFactory.decodeStream(it) }
        val rows = JSONArray()
        try {
            LocalFormulaRecognizer.releaseMemory()
            var reference: String? = null
            for (index in 0..2) {
                if (index == 2) LocalFormulaRecognizer.releaseMemory()
                val result = LocalFormulaRecognizer.recognize(test.targetContext, bitmap)
                if (reference == null) reference = result.latex else assertEquals("Cache must not change output", reference, result.latex)
                rows.put(JSONObject().put("run", index).put("latex", result.latex).put("elapsed_ms", result.elapsedMs)
                    .put("load_ms", result.loadMs).put("encode_ms", result.encodeMs).put("decode_ms", result.decodeMs).put("warm", result.warm))
            }
            File(test.targetContext.getExternalFilesDir(null), "android-formula-speed.json").writeText(rows.toString(2))
        } finally { bitmap.recycle(); LocalFormulaRecognizer.releaseMemory() }
    }

    @Test fun realHandwritingRunsInNativeAndroid() = runBlocking {
        val test = InstrumentationRegistry.getInstrumentation()
        val context = test.targetContext
        val permissions = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        assertTrue("Optional Release downloads require Internet permission", "android.permission.INTERNET" in permissions)
        val assets = test.context.assets
        val rows = JSONArray(assets.open("handwriting/manifest.json").bufferedReader().use { it.readText() })
        val privateRegression = assets.list("handwriting").orEmpty().contains("user-formula.png")
        val results = JSONArray()
        for (i in 0 until rows.length() + if (privateRegression) 1 else 0) {
            val row = if (i == rows.length()) JSONObject().put("id", "user-regression").put("image", "user-formula.png")
                .put("latex", "e^{\\arctan(1+x)}") else rows.getJSONObject(i)
            val image = assets.open("handwriting/${row.getString("image")}").use { BitmapFactory.decodeStream(it) }
            val result = try { LocalFormulaRecognizer.recognize(context, image) } finally { image.recycle() }
            results.put(JSONObject().put("id", row.getString("id")).put("truth", row.getString("latex"))
                .put("latex", result.latex).put("elapsed_ms", result.elapsedMs)
                .put("renderable", LatexInternals.recognizesMath(result.latex))
                .put("load_ms", result.loadMs).put("encode_ms", result.encodeMs).put("decode_ms", result.decodeMs).put("warm", result.warm))
            File(context.getExternalFilesDir(null), "android-handwriting.json").writeText(results.toString(2))
            // Report image-only regressions separately from the production pen-coordinate path.
            // Formula correctness is scored against the reference, never inferred from parsing.
        }
    }

    @Test fun allSubjectTemplatesRenderAndSelectionsPersist() {
        val test = InstrumentationRegistry.getInstrumentation()
        val token = java.util.UUID.randomUUID().toString()
        val context = object : android.content.ContextWrapper(test.targetContext) {
            override fun getNoBackupFilesDir() = File(baseContext.cacheDir, "pack-test-$token").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("$token-$name", mode)
        }
        val packs = FormulaPacks.load(context)
        assertEquals(10, packs.size)
        val previous = FormulaPacks.selected(context, packs)
        assertEquals(setOf("school_math", "calculus", "linear_algebra"), previous)
        assertEquals(3, packs.single { it.id == "calculus" }.version)
        try {
            val choice = setOf("calculus", "electromagnetism", "quantum")
            FormulaPacks.save(context, choice)
            assertEquals(choice, FormulaPacks.selected(context, FormulaPacks.load(context)))
            for (pack in packs) for (template in pack.templates) {
                assertTrue("Unsupported ${pack.id} template: ${template.latex}", LatexInternals.recognizesMath(template.latex))
            }
            FormulaPacks.uninstall(context, "quantum")
            assertFalse(FormulaPacks.load(context).any { it.id == "quantum" })
            FormulaPacks.installBundled(context, "quantum")
            assertTrue("Restoring a bundled pack must work without networking", FormulaPacks.load(context).any { it.id == "quantum" })
            assertFalse("calculus must remain independent", FormulaPacks.load(context).none { it.id == "calculus" })
            FormulaPacks.save(context, setOf("school_math"))
            val calculus = test.targetContext.assets.open("formula-packs/calculus.xformula").use { it.readBytes() }
            FormulaPacks.install(context, calculus)
            assertFalse("Updating must preserve disabled state", "calculus" in FormulaPacks.selected(context, FormulaPacks.load(context)))
            FormulaPacks.uninstall(context,"calculus")
            assertFalse("Bundled-pack uninstall must survive load",FormulaPacks.load(context).any { it.id=="calculus" })
        } finally { FormulaPacks.save(context, previous) }
    }

    @Test fun fullBundleUpgradePreservesDisabledAndRemovedCorePacks() {
        val test = InstrumentationRegistry.getInstrumentation()
        val token = java.util.UUID.randomUUID().toString()
        val context = object : android.content.ContextWrapper(test.targetContext) {
            override fun getNoBackupFilesDir() = File(baseContext.cacheDir, "upgrade-test-$token").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("$token-$name", mode)
        }
        val older = JSONObject(context.assets.open("formula-packs/calculus.xformula").bufferedReader().use { it.readText() })
            .put("version", 2).toString().toByteArray()
        FormulaPacks.install(context, older)
        FormulaPacks.save(context, setOf("school_math"))
        context.getSharedPreferences("formula-packs", 0).edit().putBoolean("recognition-v2", true).apply()
        val upgraded = FormulaPacks.load(context)
        assertEquals(3, upgraded.single { it.id == "calculus" }.version)
        assertEquals(setOf("school_math"), FormulaPacks.selected(context, upgraded))
        assertFalse("A v4 core-pack uninstall must survive the full-bundle upgrade", upgraded.any { it.id == "linear_algebra" })
        FormulaPacks.installBundled(context, "linear_algebra")
        assertTrue(FormulaPacks.load(context).any { it.id == "linear_algebra" })
    }
}
