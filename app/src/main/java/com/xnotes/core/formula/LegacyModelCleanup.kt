package com.xnotes.core.formula

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/** Remove only known rebuildable OCR files left by full editions. Never walk note directories. */
object LegacyModelCleanup {
    private val models = mapOf(
        "formula-gryph-q8-v1" to listOf("encoder-q8.onnx", "decoder-q8.onnx", "vocabulary.json"),
        "formula-glm-precision-v5-q4q8" to listOf("GLM-OCR-Q4_K_M-compact.gguf", "mmproj-GLM-OCR-Q8_0.gguf"),
        "formula-glm-precision-v5-q4q5" to listOf("GLM-OCR-Q4_K_M-compact.gguf", "mmproj-GLM-OCR-Q5_0.gguf"),
        "formula-65a42de1148dbed2297e922b5dbc7d9b70c36578" to listOf("GLM-OCR-Q8_0.gguf", "GLM-OCR-Q4_K_M.gguf", "mmproj-GLM-OCR-Q8_0.gguf"),
    )
    @Synchronized fun removeFrom(noBackupDirectory: File): Long {
        val root = noBackupDirectory.canonicalFile
        var removedBytes = 0L
        for ((name, files) in models) {
            val directory = File(root, name)
            if (!directory.isDirectory || Files.isSymbolicLink(directory.toPath()) || directory.canonicalFile.parentFile != root) continue
            for (fileName in files.flatMap { listOf(it, "$it.part") }) {
                val file = File(directory, fileName)
                if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) || file.canonicalFile.parentFile != directory) continue
                val size = file.length()
                if (file.delete()) removedBytes += size
            }
            // delete() only removes an empty directory. Unknown contents are preserved.
            directory.delete()
        }
        return removedBytes
    }
}
