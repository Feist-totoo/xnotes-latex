package com.xnotes.platform

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.xnotes.core.formula.LatexSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.security.MessageDigest
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

data class FormulaRecognition(val latex: String, val uncertain: Boolean, val elapsedMs: Long)

/** Native, deterministic image-to-LaTeX. No HTTP client or network permission is needed. */
object LocalFormulaRecognizer {
    private val mutex = Mutex()
    private const val SIDE = 384
    private const val MAX_TOKENS = 512
    private const val TIMEOUT_MS = 60_000L

    suspend fun recognize(context: Context, bitmap: Bitmap): FormulaRecognition = withContext(Dispatchers.Default) {
        mutex.withLock {
            currentCoroutineContext().ensureActive()
            val started = SystemClock.elapsedRealtime()
            val dir = modelFiles(context.applicationContext)
            val vocab = JSONObject(File(dir, "tokenizer.json").readText()).getJSONObject("model").getJSONObject("vocab")
            val words = vocab.keys().asSequence().associate { vocab.getInt(it) to it }
            val environment = OrtEnvironment.getEnvironment()
            OrtSession.SessionOptions().use { options ->
                options.setIntraOpNumThreads(minOf(4, Runtime.getRuntime().availableProcessors()).coerceAtLeast(1))
                options.setInterOpNumThreads(1)
                environment.createSession(File(dir, "encoder_model.onnx").path, options).use { encoder ->
                    environment.createSession(File(dir, "decoder_model_merged.onnx").path, options).use { decoder ->
                        OnnxTensor.createTensor(environment, FloatBuffer.wrap(preprocess(bitmap)), longArrayOf(1, 3, SIDE.toLong(), SIDE.toLong())).use { image ->
                            encoder.run(mapOf("pixel_values" to image)).use { encoded ->
                                val hidden = encoded[0] as OnnxTensor
                                OnnxTensor.createTensor(environment, FloatBuffer.allocate(0), longArrayOf(1, 16, 0, 24)).use { empty ->
                                    var first: OrtSession.Result? = null
                                    var previous: OrtSession.Result? = null
                                    val tokens = ArrayList<String>()
                                    var token = 0L
                                    var logProbability = 0.0
                                    var weak = false
                                    var finished = false
                                    try {
                                        for (step in 0 until MAX_TOKENS) {
                                            currentCoroutineContext().ensureActive()
                                            check(SystemClock.elapsedRealtime() - started < TIMEOUT_MS) { "Formula recognition timed out" }
                                            OnnxTensor.createTensor(environment, LongBuffer.wrap(longArrayOf(token)), longArrayOf(1, 1)).use { ids ->
                                                OnnxTensor.createTensor(environment, booleanArrayOf(step > 0)).use { useCache ->
                                                    val inputs = linkedMapOf("input_ids" to ids, "encoder_hidden_states" to hidden, "use_cache_branch" to useCache)
                                                    for (layer in 0..1) for (side in listOf("decoder", "encoder")) for (part in listOf("key", "value")) {
                                                        val name = "$layer.$side.$part"
                                                        val cached = if (side == "encoder") first else previous
                                                        inputs["past_key_values.$name"] = cached?.get("present.$name")?.orElse(null) as? OnnxTensor ?: empty
                                                    }
                                                    val next = decoder.run(inputs)
                                                    if (previous !== first) previous?.close()
                                                    if (first == null) first = next
                                                    previous = next
                                                    val scores = (next.get("logits").get() as OnnxTensor).floatBuffer
                                                    var best = 0
                                                    var bestScore = Float.NEGATIVE_INFINITY
                                                    for (i in 0 until scores.remaining()) if (scores.get(i) > bestScore) { best = i; bestScore = scores.get(i) }
                                                    check(bestScore.isFinite()) { "Invalid recognizer output" }
                                                    var denominator = 0.0
                                                    for (i in 0 until scores.remaining()) denominator += exp((scores.get(i) - bestScore).toDouble())
                                                    val probability = 1.0 / denominator
                                                    logProbability += ln(probability)
                                                    weak = weak || probability < 0.35
                                                    token = best.toLong()
                                                    finished = best == 2
                                                    if (!finished) {
                                                        check(best > 3 && words.containsKey(best)) { "Unrecognized math symbol" }
                                                        tokens.add(words.getValue(best))
                                                    }
                                                }
                                            }
                                            if (finished) break
                                        }
                                        check(finished && tokens.isNotEmpty()) { "Formula is too long or could not be recognized" }
                                        FormulaRecognition(LatexSource.clean(tokens.joinToString(" ")), weak || exp(logProbability / (tokens.size + 1)) < 0.85, SystemClock.elapsedRealtime() - started)
                                    } finally {
                                        if (previous !== first) previous?.close()
                                        first?.close()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun modelFiles(context: Context): File {
        val manifest = JSONObject(context.assets.open("formula/manifest.json").bufferedReader().use { it.readText() })
        val dir = File(context.filesDir, "formula-${manifest.getString("revision")}").apply { mkdirs() }
        val hashes = manifest.getJSONObject("sha256")
        for (name in listOf("encoder_model.onnx", "decoder_model_merged.onnx", "tokenizer.json")) {
            val target = File(dir, name)
            if (target.isFile && target.length() > 0) continue
            val temporary = File(dir, "$name.part")
            val digest = MessageDigest.getInstance("SHA-256")
            context.assets.open("formula/$name").use { input ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        val size = input.read(buffer)
                        if (size < 0) break
                        digest.update(buffer, 0, size)
                        output.write(buffer, 0, size)
                    }
                }
            }
            check(digest.digest().joinToString("") { "%02x".format(it) } == hashes.getString(name)) { "Formula model checksum failed" }
            check(temporary.renameTo(target)) { "Cannot install the offline formula model" }
        }
        return dir
    }

    /** Texo transfer ONNX preprocessing: crop ink, aspect fit, BLACK pad, repeat grayscale channels. */
    internal fun preprocess(image: Bitmap): FloatArray {
        val pixels = IntArray(image.width * image.height)
        image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
        var left = image.width; var top = image.height; var right = -1; var bottom = -1
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val pixel = pixels[y * image.width + x]
            val grey = (Color.red(pixel) * 0.299 + Color.green(pixel) * 0.587 + Color.blue(pixel) * 0.114)
            if (grey < 200 && Color.alpha(pixel) > 128) {
                left = minOf(left, x); top = minOf(top, y); right = maxOf(right, x); bottom = maxOf(bottom, y)
            }
        }
        require(right >= left && bottom >= top) { "No ink selected" }
        val width = right - left + 1; val height = bottom - top + 1
        val scale = SIDE.toDouble() / maxOf(width, height)
        val w = (width * scale).roundToInt().coerceIn(1, SIDE)
        val h = (height * scale).roundToInt().coerceIn(1, SIDE)
        val normalized = Bitmap.createBitmap(SIDE, SIDE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(normalized)
        canvas.drawColor(Color.BLACK)
        val dx = ((SIDE - w) / 2).toFloat(); val dy = ((SIDE - h) / 2).toFloat()
        canvas.drawBitmap(image, android.graphics.Rect(left, top, right + 1, bottom + 1), RectF(dx, dy, dx + w, dy + h), Paint(Paint.FILTER_BITMAP_FLAG))
        val output = IntArray(SIDE * SIDE)
        normalized.getPixels(output, 0, SIDE, 0, 0, SIDE, SIDE)
        normalized.recycle()
        val channel = FloatArray(output.size) { i ->
            val p = output[i]
            (((Color.red(p) * 0.299 + Color.green(p) * 0.587 + Color.blue(p) * 0.114) / 255.0 - 0.7931) / 0.1738).toFloat()
        }
        return FloatArray(channel.size * 3).also { for (c in 0..2) channel.copyInto(it, c * channel.size) }
    }
}
