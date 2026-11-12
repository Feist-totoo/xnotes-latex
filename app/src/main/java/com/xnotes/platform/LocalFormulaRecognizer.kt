package com.xnotes.platform

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.xnotes.core.formula.*
import com.xnotes.core.geometry.Pt
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.exp
import kotlin.math.ln

data class FormulaRecognition(val latex: String, val uncertain: Boolean, val elapsedMs: Long,
    val loadMs: Long = 0, val encodeMs: Long = 0, val decodeMs: Long = 0, val warm: Boolean = false,
    val candidates: List<FormulaCandidate> = emptyList())

/** Offline stroke recognition. Both ONNX graphs and vocabulary are bundled with the app. */
object LocalFormulaRecognizer {
    private const val LENGTH = 150
    private const val BEAMS = 4
    private const val STEPS = 20
    private const val VOCAB = 287
    private val mutex get() = FormulaInferenceGate.mutex
    private val worker = Executors.newSingleThreadScheduledExecutor { Thread(it, "offline-formula").apply { isDaemon = true } }
    private val environment by lazy { OrtEnvironment.getEnvironment() }
    private data class Engine(val encoder: OrtSession, val decoder: OrtSession, val vocabulary: List<String>)
    private var engine: Engine? = null // accessed on worker only
    private var idleRelease: ScheduledFuture<*>? = null
    @Volatile private var registered = false

    private fun unload() { engine?.let { it.decoder.close(); it.encoder.close() }; engine = null }
    fun releaseMemory() { if (registered) worker.execute { idleRelease?.cancel(false); unload() } }
    internal suspend fun releaseAndAwait() {
        if (!registered) return
        suspendCancellableCoroutine<Unit> { continuation ->
            worker.execute {
                idleRelease?.cancel(false)
                unload()
                continuation.resume(Unit)
            }
        }
    }
    @Synchronized private fun register(context: Context) {
        if (registered) return
        context.applicationContext.registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onConfigurationChanged(configuration: Configuration) {}
            override fun onLowMemory() = releaseMemory()
            override fun onTrimMemory(level: Int) { if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) releaseMemory() }
        })
        registered = true
    }

    suspend fun recognize(context: Context, bitmap: Bitmap, strokes: List<List<Pt>>? = null): FormulaRecognition =
        if (strokes == null) PrecisionFormulaRecognizer.recognize(context, bitmap) else mutex.withLock {
        val started = SystemClock.elapsedRealtime()
        PrecisionFormulaRecognizer.releaseAndAwait()
        val folder = withContext(Dispatchers.IO) { modelFiles(context.applicationContext) }
        val pixels = withContext(Dispatchers.Default) { FormulaInkRaster.pixels(strokes) }
        currentCoroutineContext().ensureActive()
        register(context)
        val cancelled = AtomicBoolean(false)
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { cancelled.set(true) }
            worker.execute {
                idleRelease?.cancel(false)
                try {
                    val prediction = infer(folder, pixels, started, cancelled)
                    // Remove only the obsolete, reproducible v3 model cache after successful inference.
                    runCatching {
                        val old = File(context.noBackupFilesDir, "formula-65a42de1148dbed2297e922b5dbc7d9b70c36578")
                        if (old.canonicalFile.parentFile == context.noBackupFilesDir.canonicalFile) old.deleteRecursively()
                    }
                    continuation.resume(prediction)
                } catch (error: Exception) {
                    unload()
                    continuation.resumeWithException(error)
                } catch (error: LinkageError) {
                    // A native loader failure must finish the coroutine, not leave a spinner.
                    runCatching { unload() }
                    continuation.resumeWithException(IllegalStateException("This device cannot load the offline recognition engine",error))
                } finally { idleRelease = worker.schedule({ unload() }, 30, TimeUnit.SECONDS) }
            }
        }
    }

    private fun infer(folder: File, pixels: FloatArray, started: Long, cancelled: AtomicBoolean): FormulaRecognition {
        fun checkpoint() {
            if (cancelled.get()) throw CancellationException("Recognition cancelled")
            check(SystemClock.elapsedRealtime() - started < 60_000) { "Recognition timed out" }
        }
        checkpoint()
        val warm = engine != null
        val loading = SystemClock.elapsedRealtime()
        if (engine == null) {
            val vocabulary = JSONArray(File(folder, "vocabulary.json").readText()).let { a -> List(a.length()) { a.getString(it) } }
            check(vocabulary.size == VOCAB && sha256((vocabulary.joinToString("\n") + "\n").toByteArray()) == FormulaRecognitionPrior.VOCABULARY_SHA256)
            OrtSession.SessionOptions().use { options ->
                options.setIntraOpNumThreads(minOf(4, Runtime.getRuntime().availableProcessors()).coerceAtLeast(1))
                options.setInterOpNumThreads(1)
                val encoder = environment.createSession(File(folder, "encoder-q8.onnx").path, options)
                try { engine = Engine(encoder, environment.createSession(File(folder, "decoder-q8.onnx").path, options), vocabulary) }
                catch (error: Exception) { encoder.close(); throw error }
            }
        }
        val loaded = SystemClock.elapsedRealtime()
        val model = engine!!
        val symbols = LongArray(BEAMS * LENGTH) { 1 }
        val modifiers = symbols.copyOf()
        val random = intArrayOf(1, 17, 37, 101).map { FormulaMaskRandom(it) }
        var symbolLogits = FloatArray(0)
        var modifierLogits = FloatArray(0)
        val visualScores = DoubleArray(BEAMS)
        fun decoder(s: LongArray, m: LongArray, keys: OnnxTensor, values: OnnxTensor): Pair<FloatArray, FloatArray> {
            OnnxTensor.createTensor(environment, LongBuffer.wrap(s), longArrayOf(BEAMS.toLong(), LENGTH.toLong())).use { st ->
                OnnxTensor.createTensor(environment, LongBuffer.wrap(m), longArrayOf(BEAMS.toLong(), LENGTH.toLong())).use { mt ->
                    model.decoder.run(mapOf("symbols" to st,"modifiers" to mt,"keys" to keys,"values" to values)).use { out ->
                        return FloatArray(BEAMS*LENGTH*VOCAB).also { (out[0] as OnnxTensor).floatBuffer.get(it) } to
                            FloatArray(BEAMS*LENGTH*VOCAB).also { (out[1] as OnnxTensor).floatBuffer.get(it) }
                    }
                }
            }
        }
        fun logProbability(logits: FloatArray, base: Int, id: Int): Double {
            var max = Double.NEGATIVE_INFINITY
            for (j in 0 until VOCAB) max = maxOf(max, logits[base+j].toDouble())
            var sum = 0.0
            for (j in 0 until VOCAB) sum += exp(logits[base+j] - max)
            return logits[base+id] - max - ln(sum)
        }
        var encoded = loaded
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(pixels), longArrayOf(1, 3, 224, 224)).use { input ->
            model.encoder.run(mapOf("pixels" to input)).use { memory ->
                encoded = SystemClock.elapsedRealtime()
                val keys = memory[0] as OnnxTensor
                val values = memory[1] as OnnxTensor
                repeat(STEPS) { step ->
                    checkpoint()
                    val logits = decoder(symbols,modifiers,keys,values)
                    symbolLogits=logits.first;modifierLogits=logits.second
                    for ((ids, output) in listOf(symbols to symbolLogits, modifiers to modifierLogits)) {
                        for (i in ids.indices) if (ids[i] == 1L) {
                            val offset = i * VOCAB
                            var best = 0
                            for (j in 1 until VOCAB) if (output[offset+j] > output[offset+best]) best = j
                            ids[i] = best.toLong()
                        }
                    }
                    val probability = 1.0 - (step + 1).toDouble() / STEPS
                    // Head-major RNG order is part of the frozen decoding contract.
                    for (ids in listOf(symbols, modifiers)) for (beam in 0 until BEAMS) for (position in 0 until LENGTH) {
                        if (random[beam].next() < probability) ids[beam * LENGTH + position] = 1
                    }
                }
                val ends=IntArray(BEAMS) { beam -> (0 until LENGTH).firstOrNull {
                    symbols[beam*LENGTH+it]==0L || modifiers[beam*LENGTH+it]==0L
                } ?: LENGTH }
                val diverse=(0 until BEAMS).map { beam ->
                    (0 until ends[beam]).map { symbols[beam*LENGTH+it] } to
                        (0 until ends[beam]).map { modifiers[beam*LENGTH+it] }
                }.distinct().size>1
                if (diverse) {
                    // Rescore without allowing a token to see its own predicted identity.
                    // Reuse the same encoded image; identical candidates need no extra passes.
                    repeat(4) { phase ->
                        checkpoint()
                        val hiddenS=symbols.copyOf();val hiddenM=modifiers.copyOf()
                        for (i in hiddenS.indices) if ((i%LENGTH)%4==phase) { hiddenS[i]=1;hiddenM[i]=1 }
                        val logits=decoder(hiddenS,hiddenM,keys,values)
                        for (beam in 0 until BEAMS) {
                            val end=minOf(ends[beam],LENGTH-1)
                            for (position in phase..end step 4) {
                                val i=beam*LENGTH+position
                                visualScores[beam]+=logProbability(logits.first,i*VOCAB,symbols[i].toInt()) +
                                    logProbability(logits.second,i*VOCAB,modifiers[i].toInt())
                            }
                        }
                    }
                    for (beam in 0 until BEAMS) visualScores[beam]/=2*minOf(ends[beam]+1,LENGTH)
                }
            }
        }
        checkpoint()
        val candidates = (0 until BEAMS).mapNotNull { beam ->
            val offset = beam * LENGTH
            val end = (0 until LENGTH).firstOrNull { symbols[offset+it] == 0L || modifiers[offset+it] == 0L } ?: return@mapNotNull null
            if (end == 0) return@mapNotNull null
            val s = IntArray(end) { symbols[offset+it].toInt() }
            val m = IntArray(end) { modifiers[offset+it].toInt() }
            if (s.any { it == 1 } || m.any { it == 1 }) return@mapNotNull null
            val tokens = ArrayList<String>()
            for (i in s.indices) {
                val modifier = model.vocabulary[m[i]]
                tokens.addAll(modifier.filter { it in "{^_" }.map { it.toString() })
                tokens.add(model.vocabulary[s[i]])
                tokens.addAll(modifier.filter { it == '}' }.map { it.toString() })
            }
            val latex = LatexSource.clean(tokens.joinToString(" "))
            if (latex.isBlank() || latex.length > 4096) return@mapNotNull null
            FormulaCandidate(latex, visualScores[beam], s, m)
        }.sortedByDescending { it.visualScore }.distinctBy { it.latex }
        require(candidates.isNotEmpty()) { "Incomplete formula" }
        val finished = SystemClock.elapsedRealtime()
        return FormulaRecognition(candidates.first().latex, true, finished-started,
            loaded-loading, encoded-loaded, finished-encoded, warm, candidates)
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private suspend fun modelFiles(context: Context): File {
        val manifest = JSONObject(context.assets.open("formula-gryph/manifest.json").bufferedReader().use { it.readText() })
        check(manifest.getString("engine") == FormulaRecognitionPrior.ENGINE)
        check(manifest.getString("scoring") == FormulaRecognitionPrior.SCORING)
        val dir = File(context.noBackupFilesDir, "formula-gryph-q8-v1").apply { mkdirs() }
        val files = manifest.getJSONObject("files")
        for (name in listOf("encoder-q8.onnx", "decoder-q8.onnx", "vocabulary.json")) {
            currentCoroutineContext().ensureActive()
            val spec = files.getJSONObject(name)
            val target = File(dir, name)
            if (target.isFile && target.length() == spec.getLong("bytes")) continue
            val temporary = File(dir, "$name.part")
            val digest = MessageDigest.getInstance("SHA-256")
            try {
                context.assets.open("formula-gryph/$name").use { input -> temporary.outputStream().use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val size = input.read(buffer); if (size < 0) break
                        digest.update(buffer, 0, size); output.write(buffer, 0, size)
                    }
                } }
                check(temporary.length() == spec.getLong("bytes") && digest.digest().joinToString("") { "%02x".format(it) } == spec.getString("sha256")) { "Offline model checksum failed" }
                check(temporary.renameTo(target)) { "Cannot unpack offline model; check free storage" }
            } finally { temporary.delete() }
        }
        return dir
    }

    /** Bitmap fallback; the lasso path uses the original pen coordinates instead. */
    internal fun rasterPixels(image: Bitmap): FloatArray {
        require(image.width in 1..4096 && image.height in 1..4096)
        val colors = IntArray(image.width * image.height)
        image.getPixels(colors, 0, image.width, 0, 0, image.width, image.height)
        val gray = IntArray(colors.size)
        var left = image.width; var top = image.height; var right = -1; var bottom = -1
        colors.forEachIndexed { i, p ->
            val a = Color.alpha(p)
            val r = (Color.red(p)*a+255*(255-a))/255; val g = (Color.green(p)*a+255*(255-a))/255; val b = (Color.blue(p)*a+255*(255-a))/255
            gray[i] = (299*r+587*g+114*b)/1000
            if (gray[i] < 180) { val x = i%image.width; val y = i/image.width; left=minOf(left,x); top=minOf(top,y); right=maxOf(right,x); bottom=maxOf(bottom,y) }
        }
        require(right >= left) { "No ink selected" }
        val w = right-left+1; val h = bottom-top+1
        val scale = 224.0/maxOf(w,h)
        val rw = (w*scale).toInt().coerceIn(1,224); val rh=(h*scale).toInt().coerceIn(1,224)
        val out = FloatArray(3*224*224)
        for (y in 0 until rh) for (x in 0 until rw) {
            val source = (top+(y*h/rh).coerceAtMost(h-1))*image.width+left+(x*w/rw).coerceAtMost(w-1)
            val v=1f-gray[source]/255f
            for (c in 0..2) out[c*224*224+y*224+x]=v
        }
        return out
    }
}
