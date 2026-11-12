package com.xnotes.platform

import android.content.Context
import android.net.Uri
import com.xnotes.core.geometry.Pt
import com.xnotes.core.model.ImageData
import com.xnotes.ui.Editor
import com.xnotes.ui.Pane
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.io.File

/** Persistable picker destination; never holds an Activity or an Editor across recreation. */
data class ImageInsertTarget(val pane: String, val canvas: Boolean, val path: String?, val created: Long?, val at: Pt?) {
    fun encode(): String = JSONObject().put("pane", pane).put("canvas", canvas).put("path", path)
        .put("created", created).put("x", at?.x).put("y", at?.y).toString()
    fun matches(editor: Editor): Boolean = editor.canvasOpen == canvas &&
        (if (canvas) editor.infinite.document.path else editor.state.document.path) == path &&
        (if (canvas) editor.infinite.document.created else editor.state.document.created) == created
    companion object {
        fun capture(editor: Editor, canvas: Boolean, at: Pt?) = ImageInsertTarget(editor.pane.name, canvas,
            if (canvas) editor.infinite.document.path else editor.state.document.path,
            if (canvas) editor.infinite.document.created else editor.state.document.created, at)
        fun decode(text: String): ImageInsertTarget = JSONObject(text).let {
            val pane = it.getString("pane"); require(pane in Pane.entries.map { p -> p.name })
            ImageInsertTarget(pane, it.getBoolean("canvas"), if (it.has("path")) it.getString("path") else null,
                if (it.has("created")) it.getLong("created") else null,
                if (it.has("x") && it.has("y")) Pt(it.getDouble("x"), it.getDouble("y")) else null)
        }
    }
}

object ImageImport {
    data class Prepared(val image: ImageData, val orientation: Int)
    /** Copy SAF data as a stream off-thread; avoid holding a second copy of a large photo in heap. */
    suspend fun read(context: Context, uri: Uri, directory: File): Prepared {
        directory.mkdirs()
        val file = File.createTempFile("import-image-", ".img", directory)
        try {
            context.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024); var total = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer); if (count < 0) break
                    total += count; require(total <= 128L * 1024 * 1024) { "Image too large" }
                    output.write(buffer, 0, count)
                }
            } } ?: error("Image provider returned no data")
            val size = ImageDecoder.probeFile(file.path) ?: error("Unreadable image")
            require(size.width > 0 && size.height > 0)
            return Prepared(ImageData(file, size.width, size.height), ImageDecoder.exifTurn(file.path))
        } catch (e: Throwable) { file.delete(); throw e }
    }
}
