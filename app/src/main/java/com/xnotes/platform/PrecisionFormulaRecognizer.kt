package com.xnotes.platform

import android.content.Context
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import com.xnotes.core.formula.LatexSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Bundled compact GLM-OCR, running directly in JNI. No service, download, or HTTP client. */
object PrecisionFormulaRecognizer {
    private val mutex get() = FormulaInferenceGate.mutex
    private val worker = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "offline-formula-precision").apply { isDaemon = true } }
    private var idleRelease: ScheduledFuture<*>? = null // worker thread only
    @Volatile private var callbacksRegistered = false
    private val sequence = AtomicLong()
    private val library by lazy { System.loadLibrary("xnotesprecision") }
    private external fun nativeCreate(id: Long)
    private external fun nativeCancel(id: Long)
    private external fun nativeRelease(id: Long)
    private external fun nativeRecognize(id: Long, model: String, vision: String, rgb: ByteArray, width: Int, height: Int, threads: Int): ByteArray
    private external fun nativeUnload()
    private external fun nativeTimings(): LongArray

    /** Safe on any thread: releasing is serialized with inference. */
    fun releaseMemory() { if (callbacksRegistered) worker.execute { idleRelease?.cancel(false); nativeUnload() } }

    internal suspend fun releaseAndAwait() {
        if (!callbacksRegistered) return
        suspendCancellableCoroutine<Unit> { continuation ->
            worker.execute {
                idleRelease?.cancel(false)
                nativeUnload()
                continuation.resume(Unit)
            }
        }
    }

    @Synchronized private fun registerCallbacks(context: Context) {
        if (callbacksRegistered) return
        context.applicationContext.registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onConfigurationChanged(configuration: Configuration) {}
            override fun onLowMemory() = releaseMemory()
            override fun onTrimMemory(level: Int) {
                if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) releaseMemory()
            }
        })
        callbacksRegistered = true
    }

    suspend fun recognize(context: Context, bitmap: Bitmap): FormulaRecognition = mutex.withLock {
        val started = SystemClock.elapsedRealtime()
        LocalFormulaRecognizer.releaseAndAwait()
        val dir = withContext(Dispatchers.IO) { modelFiles(context.applicationContext) }
        val rgb = withContext(Dispatchers.Default) { rgbPixels(bitmap) }
        currentCoroutineContext().ensureActive()
        withContext(Dispatchers.Default) {
            try { library } catch (error: UnsatisfiedLinkError) {
                throw IllegalStateException("This device cannot load the offline recognition engine", error)
            }
        }
        registerCallbacks(context)
        val width = bitmap.width
        val height = bitmap.height
        val id = sequence.incrementAndGet()
        nativeCreate(id)
        val prediction = suspendCancellableCoroutine<Pair<String, LongArray>> { continuation ->
            // Cancellation never frees an in-use pointer. The native registry owns it
            // until this queued worker finishes, including cancellation before startup.
            continuation.invokeOnCancellation { nativeCancel(id) }
            worker.execute {
                idleRelease?.cancel(false)
                try {
                    val result = nativeRecognize(id, File(dir, "GLM-OCR-Q4_K_M-compact.gguf").path,
                        File(dir, "mmproj-GLM-OCR-Q8_0.gguf").path, rgb, width, height,
                        minOf(4, Runtime.getRuntime().availableProcessors()).coerceAtLeast(1))
                    continuation.resume(result.toString(Charsets.UTF_8) to nativeTimings())
                } catch (error: Exception) {
                    continuation.resumeWithException(error)
                } catch (error: LinkageError) {
                    continuation.resumeWithException(IllegalStateException("This device cannot load the offline precision engine", error))
                } finally {
                    nativeRelease(id)
                    idleRelease = worker.schedule({ nativeUnload() }, 30, TimeUnit.SECONDS)
                }
            }
        }
        currentCoroutineContext().ensureActive()
        val latex = LatexSource.clean(prediction.first)
        require(latex.isNotBlank() && latex.length <= 4096) { "Incomplete formula" }
        // Do not present uncalibrated token probabilities as recognition accuracy.
        val timing = prediction.second
        FormulaRecognition(latex, uncertain = true, elapsedMs = SystemClock.elapsedRealtime() - started,
            loadMs = timing[0], encodeMs = timing[1], decodeMs = timing[2], warm = timing[3] == 1L)
    }

    private suspend fun modelFiles(context: Context): File {
        val manifest = JSONObject(context.assets.open("formula-precision/manifest.json").bufferedReader().use { it.readText() })
        // Model copies are rebuildable assets, excluded from Android cloud backup.
        val dir = File(context.noBackupFilesDir, "formula-${manifest.getString("revision")}").apply { mkdirs() }
        val files = manifest.getJSONObject("files")
        for (name in files.keys()) {
            currentCoroutineContext().ensureActive()
            val spec = files.getJSONObject(name)
            val target = File(dir, name)
            if (target.isFile && target.length() == spec.getLong("bytes")) continue
            val temporary = File(dir, "$name.part")
            val digest = MessageDigest.getInstance("SHA-256")
            try {
                context.assets.open("formula-precision/$name").use { input ->
                    temporary.outputStream().use { output ->
                        val buffer = ByteArray(1024 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val size = input.read(buffer)
                            if (size < 0) break
                            digest.update(buffer, 0, size)
                            output.write(buffer, 0, size)
                        }
                    }
                }
                check(temporary.length() == spec.getLong("bytes") &&
                    digest.digest().joinToString("") { "%02x".format(it) } == spec.getString("sha256")) { "Offline model checksum failed" }
                check(temporary.renameTo(target)) { "Cannot unpack offline model; check free storage" }
            } finally { temporary.delete() }
        }
        return dir
    }

    internal fun rgbPixels(image: Bitmap): ByteArray {
        require(image.width in 1..4096 && image.height in 1..4096)
        val pixels = IntArray(image.width * image.height)
        image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
        var hasInk = false
        val rgb = ByteArray(pixels.size * 3)
        for (i in pixels.indices) {
            val p = pixels[i]
            val alpha = Color.alpha(p)
            val r = (Color.red(p) * alpha + 255 * (255 - alpha)) / 255
            val g = (Color.green(p) * alpha + 255 * (255 - alpha)) / 255
            val b = (Color.blue(p) * alpha + 255 * (255 - alpha)) / 255
            if (minOf(r, g, b) < 200) hasInk = true
            rgb[i * 3] = r.toByte(); rgb[i * 3 + 1] = g.toByte(); rgb[i * 3 + 2] = b.toByte()
        }
        require(hasInk) { "No ink selected" }
        return rgb
    }
}
