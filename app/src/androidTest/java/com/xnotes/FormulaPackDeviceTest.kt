package com.xnotes
import androidx.test.platform.app.InstrumentationRegistry
import com.xnotes.platform.FormulaPacks
import com.xnotes.platform.LatexInternals
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
class FormulaPackDeviceTest {
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
