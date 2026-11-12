package com.xnotes.core.formula

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LegacyModelCleanupTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun removesOnlyKnownModelFilesAndPreservesNotesCredentialsAndPacks() {
        val root = temporary.newFolder("no_backup")
        fun write(name: String) = File(root, name).apply { parentFile.mkdirs(); writeText("test bytes") }
        val weights = listOf(write("formula-gryph-q8-v1/encoder-q8.onnx"),
            write("formula-gryph-q8-v1/decoder-q8.onnx.part"),
            write("formula-glm-precision-v5-q4q8/GLM-OCR-Q4_K_M-compact.gguf"))
        val preserved = listOf(write("formula-gryph-q8-v1/notes.xnote"), write("formula-packs/index.json"),
            write("paddle-api.enc"), write("notes/encoder-q8.onnx"), write("unknown-model/decoder-q8.onnx"))
        assertEquals(weights.sumOf { it.length() }, LegacyModelCleanup.removeFrom(root))
        assertTrue(weights.none { it.exists() })
        assertTrue(preserved.all { it.readText() == "test bytes" })
        assertFalse(File(root, "formula-glm-precision-v5-q4q8").exists())
        assertEquals(0L, LegacyModelCleanup.removeFrom(root))
    }
    @Test fun neverDescendsIntoSubdirectories() {
        val root = temporary.newFolder("nested")
        val nested = File(root, "formula-gryph-q8-v1/encoder-q8.onnx/user-data").apply { parentFile.mkdirs(); writeText("keep") }
        assertEquals(0L, LegacyModelCleanup.removeFrom(root))
        assertEquals("keep", nested.readText())
    }
}
