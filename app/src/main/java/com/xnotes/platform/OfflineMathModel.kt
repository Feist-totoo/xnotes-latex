package com.xnotes.platform

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Fixed, verified data files. Download only on an explicit user action, never during inference. */
object OfflineMathModel {
    data class Asset(val name: String, val bytes: Long, val sha256: String)
    val assets = listOf(
        Asset("encoder-q8.onnx", 25056585, "7ed2dbc6a9248c8fa57a33af1c833d34f48b5855f512928b32773dc68034e456"),
        Asset("decoder-q8.onnx", 7170036, "e8b06ed1f3337872e2f85f6f10e3478a49d3f0eeb1ff912b592594d410a4c0c8"),
    )
    private const val BASE = "https://github.com/Feist-totoo/xnotes-latex/releases/download/v0.9.8-latex.5-full/"
    data class Status(val busy: Boolean = false, val progress: Float = 0f, val error: Boolean = false, val revision: Int = 0)
    val status = MutableStateFlow(Status())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    fun directory(context: Context) = File(context.noBackupFilesDir, "formula-live-q8-v1")
    fun installed(context: Context): Boolean = assets.all { File(directory(context), it.name).let { f -> f.isFile && f.length() == it.bytes } }
    fun digest(file: File): String {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(128 * 1024); while (true) {
            val count = input.read(buffer); if (count < 0) break; hash.update(buffer, 0, count)
        } }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }
    fun validatedDirectory(context: Context): File {
        val dir = directory(context)
        check(assets.all { File(dir, it.name).let { f -> f.isFile && f.length() == it.bytes && digest(f) == it.sha256 } }) { "Install the offline math pack in Preferences" }
        return dir
    }
    @Synchronized fun download(context: Context) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        status.value = Status(busy = true, revision = status.value.revision)
        job = scope.launch {
            val dir = directory(app).apply { mkdirs() }
            var done = 0L
            try {
                for (asset in assets) {
                    val target = File(dir, asset.name)
                    if (target.isFile && target.length() == asset.bytes && digest(target) == asset.sha256) { done += asset.bytes; continue }
                    val part = File(dir, asset.name + ".part")
                    var connection: HttpURLConnection? = null
                    try {
                        var url = URL(BASE + asset.name)
                        repeat(6) {
                            if (connection != null) return@repeat
                            require(url.protocol == "https")
                            val next = (url.openConnection() as HttpURLConnection).apply {
                                connectTimeout = 20_000; readTimeout = 20_000; instanceFollowRedirects = false
                                setRequestProperty("User-Agent", "xnotes-offline-math")
                            }
                            if (next.responseCode in listOf(301, 302, 303, 307, 308)) {
                                url = URL(url, next.getHeaderField("Location") ?: error("Missing redirect")); next.disconnect()
                            } else { check(next.responseCode == 200); connection = next }
                        }
                        val active = connection ?: error("Too many redirects")
                        var count = 0L
                        active.inputStream.use { input -> part.outputStream().use { output ->
                            val buffer = ByteArray(128 * 1024)
                            while (true) {
                                ensureActive()
                                val n = input.read(buffer); if (n < 0) break
                                count += n; check(count <= asset.bytes)
                                output.write(buffer, 0, n)
                                status.value = Status(true, (done + count).toFloat() / assets.sumOf { it.bytes }, revision = status.value.revision)
                            }
                        } }
                        check(part.length() == asset.bytes && digest(part) == asset.sha256) { "Model checksum mismatch" }
                        ensureActive()
                        check(part.renameTo(target)) { "Cannot install model" }
                        done += count
                    } finally { connection?.disconnect(); part.delete() }
                }
                status.value = Status(progress = 1f, revision = status.value.revision + 1)
            } catch (cancelled: CancellationException) { status.value = Status(revision = status.value.revision + 1); throw cancelled }
            catch (_: Exception) { status.value = Status(error = true, revision = status.value.revision + 1) }
        }
    }
    @Synchronized fun cancel() { job?.cancel() }
    suspend fun uninstall(context: Context) {
        job?.cancelAndJoin()
        OfflineMathRecognizer.releaseAndAwait()
        for (asset in assets) for (suffix in listOf("", ".part")) File(directory(context), asset.name + suffix).delete()
        directory(context).delete()
        status.value = Status(revision = status.value.revision + 1)
    }
}
