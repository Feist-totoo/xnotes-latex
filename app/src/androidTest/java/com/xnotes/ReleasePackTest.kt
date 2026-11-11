package com.xnotes

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xnotes.core.formula.FormulaPackRouter
import com.xnotes.platform.FormulaPacks
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ReleasePackTest {
    /** Explicit opt-in: ordinary offline regression runs do not need a network. */
    @Test fun downloadsVersionedGithubReleaseAndUsesIndependentLocalPack() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("networkPacks") == "true")
        val test = InstrumentationRegistry.getInstrumentation()
        val token = java.util.UUID.randomUUID().toString()
        val context = object : ContextWrapper(test.targetContext) {
            override fun getNoBackupFilesDir() = File(baseContext.cacheDir, "release-test-$token").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("$token-$name", mode)
        }
        val index = FormulaPacks.refreshCatalog(context)
        assertEquals(10, index.size)
        val quantum = index.single { it.id == "quantum" }
        assertTrue(quantum.url.contains("/releases/download/"))
        FormulaPacks.downloadAndInstall(context, quantum)
        // All subsequent calls are local reads; no recognition service is involved.
        var packs = FormulaPacks.load(context)
        assertTrue(packs.any { it.id == "quantum" && it.version == quantum.version })
        assertEquals("quantum", FormulaPackRouter.recommend("i\\hbar \\partial_t \\psi = \\hat H \\psi", packs).first().id)
        FormulaPacks.save(context, setOf("school_math"))
        assertFalse("Disabling a pack keeps it installed", "quantum" in FormulaPacks.selected(context, packs))
        assertTrue(FormulaPacks.load(context).any { it.id == "quantum" })
        FormulaPacks.uninstall(context, "quantum")
        packs = FormulaPacks.load(context)
        assertFalse(packs.any { it.id == "quantum" })
        assertTrue(packs.any { it.id == "school_math" })
        File(test.targetContext.getExternalFilesDir(null), "release-pack-test.txt")
            .writeText("PASS: ${quantum.url}\nversion=${quantum.version}\nsha256=${quantum.sha256}\nindependent install, enable/disable, routing and uninstall\n")
    }
}
