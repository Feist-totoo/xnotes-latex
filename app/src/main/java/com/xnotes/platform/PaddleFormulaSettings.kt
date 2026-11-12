package com.xnotes.platform

import android.content.Context
import android.graphics.Bitmap
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.xnotes.core.formula.PaddleFormulaApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Credentials never enter note files, settings exports, logs, or Android backups. */
object PaddleFormulaSettings {
    private const val ALIAS = "xnotes.paddle.api.v1"
    private fun file(context: Context) = AtomicFile(File(context.noBackupFilesDir, "paddle-api.enc"))
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized fun load(context: Context): PaddleFormulaApi.Config? = runCatching {
        val bytes = file(context).readFully()
        require(bytes.size in 29..16384)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        val json = JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
        PaddleFormulaApi.Config(json.getString("endpoint"), json.getString("token"), json.getString("model"))
            .takeIf(PaddleFormulaApi::valid)
    }.getOrNull()

    @Synchronized fun save(context: Context, config: PaddleFormulaApi.Config) {
        require(PaddleFormulaApi.valid(config))
        val plaintext = JSONObject().put("endpoint", config.endpoint).put("token", config.token).put("model", config.model).toString().toByteArray()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.iv + cipher.doFinal(plaintext)
        val file = file(context)
        val output = file.startWrite()
        try { output.write(encrypted); file.finishWrite(output) }
        catch (e: Exception) { file.failWrite(output); throw e }
        finally { plaintext.fill(0) }
    }
    @Synchronized fun clear(context: Context) { file(context).delete() }

    suspend fun recognize(bitmap: Bitmap, config: PaddleFormulaApi.Config): String {
        val png = withContext(Dispatchers.Default) {
            // Selection only. Bound upload dimensions without enlarging small handwriting.
            val scale = minOf(1.0, 2048.0 / maxOf(bitmap.width, bitmap.height))
            val image = if (scale < 1.0) Bitmap.createScaledBitmap(bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true) else bitmap
            try { ByteArrayOutputStream().use { out -> image.compress(Bitmap.CompressFormat.PNG, 100, out); out.toByteArray() } }
            finally { if (image !== bitmap) image.recycle() }
        }
        return PaddleFormulaApi.recognize(png, config)
    }
}
