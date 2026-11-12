package com.xnotes.core.formula

import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** AI Studio hosted API. No caller in the offline recognition path. */
object PaddleFormulaApi {
    const val DEFAULT_ENDPOINT = "https://paddleocr.aistudio-app.com/api/v2/ocr/jobs"
    val models = listOf("PaddleOCR-VL-1.6", "PaddleOCR-VL-1.5")
    class Config(val endpoint: String = DEFAULT_ENDPOINT, val token: String, val model: String = models.first()) {
        override fun toString() = "Paddle API configuration (credentials hidden)"
    }
    enum class Failure { CONFIG, AUTH, QUOTA, NETWORK, RESPONSE, MULTIPLE, EMPTY, SERVICE }
    class Error(val reason: Failure) : Exception(reason.name)
    class Request(val url: String, val method: String = "GET", val headers: Map<String, String> = emptyMap(), val body: ByteArray? = null)
    class Response(val status: Int, val body: String)
    fun interface Transport { suspend fun execute(request: Request): Response }

    fun valid(config: Config): Boolean = runCatching {
        val u = URI(config.endpoint)
        val host = u.host.orEmpty().lowercase()
        u.scheme == "https" && u.rawUserInfo == null && u.rawQuery == null && u.rawFragment == null &&
            u.port == -1 && (host.endsWith(".aistudio-app.com") || host.endsWith(".aistudio-hub.baidu.com")) &&
            u.path in listOf("/api/v2/ocr/jobs", "/layout-parsing") &&
            config.token.length in 1..4096 && config.token.all { it.code in 33..126 } && config.model in models
    }.getOrDefault(false)

    /** A single cropped PNG, submitted once. Polls never resubmit or upload the full note. */
    suspend fun recognize(png: ByteArray, config: Config, transport: Transport = Network): String = withTimeout(180_000) {
        if (!valid(config) || png.isEmpty() || png.size > 8 * 1024 * 1024) throw Error(Failure.CONFIG)
        val options = JSONObject().put("useDocOrientationClassify", false).put("useDocUnwarping", false)
            .put("useLayoutDetection", false).put("promptLabel", "formula").put("visualize", false)
        val async = URI(config.endpoint).path == "/api/v2/ocr/jobs"
        val auth = mapOf("Authorization" to "${if (async) "Bearer" else "token"} ${config.token}")
        if (!async) {
            // AI Studio's per-service URL copied from its API panel uses the synchronous contract.
            val body = options.put("file", java.util.Base64.getEncoder().encodeToString(png)).put("fileType", 1)
            val result = checked(transport.execute(Request(config.endpoint, "POST", auth + ("Content-Type" to "application/json"), body.toString().toByteArray())))
            return@withTimeout extract(JSONObject(result))
        }
        val boundary = "xnotes-${UUID.randomUUID()}"
        val body = ByteArrayOutputStream().apply {
            fun part(name: String, value: String) {
                write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
            }
            part("model", config.model)
            part("optionalPayload", options.toString())
            write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"selection.png\"\r\nContent-Type: image/png\r\n\r\n".toByteArray())
            write(png)
            write("\r\n--$boundary--\r\n".toByteArray())
        }.toByteArray()
        val submitted = data(checked(transport.execute(Request(config.endpoint, "POST",
            auth + ("Content-Type" to "multipart/form-data; boundary=$boundary"), body))))
        val id = submitted.optString("jobId")
        if (!id.matches(Regex("[A-Za-z0-9_-]{1,200}"))) throw Error(Failure.RESPONSE)
        var interval = 1000L
        while (true) {
            val status = data(checked(transport.execute(Request("${config.endpoint}/$id", headers = auth))))
            when (status.optString("state")) {
                "failed" -> throw Error(Failure.SERVICE)
                "done" -> {
                    val url = status.optJSONObject("resultUrl")?.optString("jsonUrl").orEmpty()
                    if (!validResultUrl(url)) throw Error(Failure.RESPONSE)
                    // Pre-signed result downloads MUST NOT carry the AI Studio token.
                    val lines = checked(transport.execute(Request(url))).lineSequence().filter { it.isNotBlank() }.toList()
                    if (lines.size != 1) throw Error(Failure.MULTIPLE)
                    return@withTimeout extract(JSONObject(lines.single()))
                }
                "pending", "running" -> { delay(interval); interval = (interval * 3 / 2).coerceAtMost(5000) }
                else -> throw Error(Failure.RESPONSE)
            }
        }
        @Suppress("UNREACHABLE_CODE") ""
    }

    internal fun validResultUrl(url: String): Boolean = runCatching {
        val u = URI(url)
        val h = u.host.orEmpty().lowercase()
        u.scheme == "https" && u.rawUserInfo == null && u.port == -1 && u.rawFragment == null &&
            (h.endsWith(".bcebos.com") || h.endsWith(".aistudio-app.com") || h.endsWith(".aistudio-hub.baidu.com"))
    }.getOrDefault(false)

    private fun checked(response: Response): String {
        if (response.body.length > 2 * 1024 * 1024) throw Error(Failure.RESPONSE)
        when (response.status) {
            401, 403 -> throw Error(Failure.AUTH)
            429 -> throw Error(Failure.QUOTA)
            in 200..299 -> return response.body
            else -> throw Error(Failure.SERVICE)
        }
    }
    private fun data(raw: String): JSONObject {
        val root = JSONObject(raw)
        if (root.optInt("code", 0) != 0) throw Error(Failure.SERVICE)
        return root.optJSONObject("data") ?: throw Error(Failure.RESPONSE)
    }
    internal fun extract(root: JSONObject): String {
        if (root.optInt("errorCode", 0) != 0) throw Error(Failure.SERVICE)
        val result = root.optJSONObject("result") ?: throw Error(Failure.RESPONSE)
        val pages = result.optJSONArray("layoutParsingResults") ?: throw Error(Failure.RESPONSE)
        if (pages.length() != 1) throw Error(if (pages.length() == 0) Failure.EMPTY else Failure.MULTIPLE)
        val raw = pages.getJSONObject(0).optJSONObject("markdown")?.optString("text").orEmpty().trim()
        if (raw.length > 8192) throw Error(Failure.RESPONSE)
        // Do not silently join several detected formulas or discard surrounding prose.
        val cleaned = LatexSource.clean(raw)
        if (cleaned.isBlank()) throw Error(Failure.EMPTY)
        if (Regex("(?<!\\\\)\\$").containsMatchIn(cleaned) || cleaned.contains("\\[") || cleaned.contains("\\]")) throw Error(Failure.MULTIPLE)
        if (LatexSource.problem(cleaned) != null) throw Error(Failure.RESPONSE)
        return cleaned
    }

    private object Network : Transport {
        private val workers = Executors.newFixedThreadPool(2) { Thread(it, "paddle-api").apply { isDaemon = true } }
        override suspend fun execute(request: Request): Response = suspendCancellableCoroutine { continuation ->
            val connection = URI(request.url).toURL().openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.requestMethod = request.method
            request.headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
            val future = workers.submit {
                try {
                    if (!continuation.isActive) return@submit
                    request.body?.let { body ->
                        connection.doOutput = true
                        connection.setFixedLengthStreamingMode(body.size)
                        connection.outputStream.use { it.write(body) }
                    }
                    val code = connection.responseCode
                    val content = (if (code in 200..299) connection.inputStream else connection.errorStream)?.use { input ->
                        val out = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            if (!continuation.isActive) return@submit
                            val size = input.read(buffer)
                            if (size < 0) break
                            if (out.size() + size > 2 * 1024 * 1024) throw Error(Failure.RESPONSE)
                            out.write(buffer, 0, size)
                        }
                        out.toString("UTF-8")
                    }.orEmpty()
                    continuation.resume(Response(code, content))
                } catch (e: Exception) {
                    continuation.resumeWithException(if (e is Error) e else Error(Failure.NETWORK))
                } finally { connection.disconnect() }
            }
            continuation.invokeOnCancellation { connection.disconnect(); future.cancel(true) }
        }
    }
}
