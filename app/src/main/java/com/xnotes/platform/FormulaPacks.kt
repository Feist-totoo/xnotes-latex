package com.xnotes.platform

import android.content.Context
import com.xnotes.core.formula.FormulaPack
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

data class FormulaPackEntry(val id: String, val version: Int, val nameZh: String, val nameEn: String,
                            val bytes: Int, val sha256: String, val url: String, val signals: Map<String, Int>) {
    fun name(chinese: Boolean) = if (chinese) nameZh else nameEn
    fun routingInfo() = FormulaPack(id, version, nameZh, nameEn, "", "", signals, emptyList(), emptyList())
}

/** Package storage only. The recognizer has no dependency on this downloader. */
object FormulaPacks {
    // Preview catalog must not replace the stable channel used by v3 installations.
    const val INDEX_URL = "https://github.com/Feist-totoo/xnotes-latex/releases/download/v0.9.8-latex.4-preview/formula-packs-index.json"
    private fun directory(context: Context) = File(context.noBackupFilesDir, "formula-packs").apply { mkdirs() }
    private fun preferences(context: Context) = context.getSharedPreferences("formula-packs", Context.MODE_PRIVATE)

    @Synchronized fun load(context: Context): List<FormulaPack> {
        val folder = directory(context)
        if (!preferences(context).getBoolean("bootstrapped", false)) {
            install(context, context.assets.open("formula-packs/school_math.xformula").use { it.readBytes() })
            preferences(context).edit().putBoolean("bootstrapped", true).apply()
        }
        // Run once on upgrade. Uninstalling a bundled pack remains persistent.
        if (!preferences(context).getBoolean("recognition-v2", false)) {
            for (id in listOf("calculus", "linear_algebra")) {
                val existing = File(folder, "$id.xformula")
                val version = runCatching { FormulaPack.parse(existing.readBytes()).version }.getOrDefault(0)
                if (version < 2) install(context, context.assets.open("formula-packs/$id.xformula").use { it.readBytes() })
            }
            preferences(context).edit().putBoolean("recognition-v2", true).apply()
        }
        return folder.listFiles().orEmpty().filter { it.extension == "xformula" }.mapNotNull {
            runCatching { require(it.length() <= FormulaPack.MAX_BYTES); FormulaPack.parse(it.readBytes()) }.getOrNull()
        }.sortedBy { it.id }
    }

    fun catalog(context: Context): List<FormulaPackEntry> {
        val saved = File(directory(context), "index.json")
        val bytes = if (saved.isFile && saved.length() <= FormulaPack.MAX_BYTES) saved.readBytes()
            else context.assets.open("formula-packs/index.json").use { it.readBytes() }
        return runCatching { parseCatalog(bytes) }.getOrElse {
            parseCatalog(context.assets.open("formula-packs/index.json").use { it.readBytes() })
        }
    }

    internal fun parseCatalog(bytes: ByteArray): List<FormulaPackEntry> {
        require(bytes.size <= FormulaPack.MAX_BYTES)
        val root = JSONObject(bytes.toString(Charsets.UTF_8)); require(root.getInt("schema") == 1)
        val rows = root.getJSONArray("packs"); require(rows.length() <= 100)
        return List(rows.length()) { i -> rows.getJSONObject(i).let {
            val id = it.getString("id"); require(Regex("[a-z][a-z0-9_]{0,47}").matches(id))
            val url = it.getString("url")
            require(url.startsWith("https://github.com/Feist-totoo/xnotes-latex/releases/download/"))
            val hash = it.getString("sha256"); require(Regex("[a-f0-9]{64}").matches(hash))
            val length = it.getInt("bytes"); require(length in 1..FormulaPack.MAX_BYTES)
            val version = it.getInt("version"); require(version in 1..1_000_000)
            val signals = it.getJSONObject("signals"); require(signals.length() <= 64)
            val weights = signals.keys().asSequence().associateWith { key ->
                require(key.length in 1..64); signals.getInt(key).also { score -> require(score in 1..8) }
            }
            FormulaPackEntry(id, version, it.getString("nameZh").take(80), it.getString("nameEn").take(80), length, hash, url, weights)
        } }.also { require(it.distinctBy { p -> p.id }.size == it.size) }
    }

    fun refreshCatalog(context: Context): List<FormulaPackEntry> {
        val bytes = download(INDEX_URL)
        val entries = parseCatalog(bytes)
        atomicWrite(File(directory(context), "index.json"), bytes)
        return entries
    }

    fun downloadAndInstall(context: Context, entry: FormulaPackEntry) {
        val bytes = download(entry.url)
        require(bytes.size == entry.bytes && sha256(bytes) == entry.sha256) { "Package checksum mismatch" }
        val pack = FormulaPack.parse(bytes)
        require(pack.id == entry.id && pack.version == entry.version) { "Wrong package identity" }
        install(context, bytes)
    }

    @Synchronized fun install(context: Context, bytes: ByteArray): FormulaPack {
        val pack = FormulaPack.parse(bytes)
        val target = File(directory(context), "${pack.id}.xformula")
        val previousVersion = if (target.isFile && target.length() <= FormulaPack.MAX_BYTES)
            runCatching { FormulaPack.parse(target.readBytes()).version }.getOrNull() else null
        if (previousVersion != null) require(previousVersion <= pack.version) { "Package downgrade rejected" }
        atomicWrite(target, bytes)
        val previous = preferences(context).getStringSet("selected", setOf("school_math")).orEmpty()
        if (previousVersion == null) save(context, previous + pack.id)
        return pack
    }

    @Synchronized fun uninstall(context: Context, id: String) {
        require(Regex("[a-z][a-z0-9_]{0,47}").matches(id))
        val target = File(directory(context), "$id.xformula")
        check(!target.exists() || target.delete())
        val previous = preferences(context).getStringSet("selected", emptySet()).orEmpty()
        save(context, previous - id)
    }

    fun selected(context: Context, catalog: List<FormulaPack>): Set<String> = preferences(context)
        .getStringSet("selected", setOf("school_math")).orEmpty().intersect(catalog.map { it.id }.toSet())
    fun save(context: Context, ids: Set<String>) { preferences(context).edit().putStringSet("selected", ids.toSet()).apply() }
    fun automatic(context: Context) = preferences(context).getBoolean("automatic", true)
    fun setAutomatic(context: Context, value: Boolean) { preferences(context).edit().putBoolean("automatic", value).apply() }
    // Held-out evaluation did not establish a net gain; opt in explicitly for experiments.
    fun recognitionEnabled(context: Context) = preferences(context).getBoolean("recognition-scoring", false)
    fun setRecognitionEnabled(context: Context, value: Boolean) { preferences(context).edit().putBoolean("recognition-scoring", value).apply() }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    @Synchronized private fun atomicWrite(target: File, bytes: ByteArray) {
        val temp = File(target.parentFile, "${target.name}.part")
        try {
            temp.outputStream().use { it.write(bytes); it.fd.sync() }
            check(temp.renameTo(target)) { "Cannot save package" }
        } finally { temp.delete() }
    }

    private fun download(source: String): ByteArray {
        var url = URL(source)
        val hosts = setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")
        repeat(6) {
            require(url.protocol == "https" && url.host in hosts && url.userInfo == null) { "Unsupported package host" }
            val connection = url.openConnection() as HttpsURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000; connection.readTimeout = 30_000
            connection.setRequestProperty("User-Agent", "xnotes-formula-packs/1")
            try {
                val status = connection.responseCode
                if (status in listOf(301, 302, 303, 307, 308)) {
                    url = URL(url, connection.getHeaderField("Location") ?: error("Missing download location"))
                } else {
                    require(status == 200) { "GitHub download failed ($status)" }
                    require(connection.contentLengthLong <= FormulaPack.MAX_BYTES)
                    return connection.inputStream.use { input ->
                        val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                        while (true) {
                            val n = input.read(buffer); if (n < 0) break
                            require(output.size() + n <= FormulaPack.MAX_BYTES) { "Package too large" }
                            output.write(buffer, 0, n)
                        }
                        output.toByteArray()
                    }
                }
            } finally { connection.disconnect() }
        }
        error("Too many download redirects")
    }
}
