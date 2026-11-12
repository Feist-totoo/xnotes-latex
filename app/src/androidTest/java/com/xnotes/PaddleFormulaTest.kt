package com.xnotes

import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.xnotes.core.formula.PaddleFormulaApi
import com.xnotes.platform.PaddleFormulaSettings
import com.xnotes.ui.PaddleFormulaPanel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class PaddleFormulaTest {
    @get:Rule val compose = createComposeRule()
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val isolated = object : ContextWrapper(base) {
        private val directory = File(base.cacheDir, "paddle-test-${UUID.randomUUID()}").apply { mkdirs() }
        override fun getNoBackupFilesDir() = directory
    }

    @Test fun credentialsAreEncryptedPersistAndCanBeRemoved() {
        val config = PaddleFormulaApi.Config(token = "TEST_ONLY_token_not_a_real_credential")
        assertNull(PaddleFormulaSettings.load(isolated))
        try {
            PaddleFormulaSettings.save(isolated, config)
            val encrypted = File(isolated.noBackupFilesDir, "paddle-api.enc")
            assertFalse(String(encrypted.readBytes()).contains(config.token))
            assertEquals(config.token, PaddleFormulaSettings.load(isolated)!!.token)
            assertEquals(config.endpoint, PaddleFormulaSettings.load(isolated)!!.endpoint)
            // Corrupt storage safely disables API configuration rather than exposing plaintext.
            encrypted.writeBytes(byteArrayOf(1, 2, 3))
            assertNull(PaddleFormulaSettings.load(isolated))
            PaddleFormulaSettings.save(isolated, config)
            PaddleFormulaSettings.clear(isolated)
            assertNull(PaddleFormulaSettings.load(isolated))
            assertFalse(encrypted.exists())
        } finally { PaddleFormulaSettings.clear(isolated) }
    }

    @Test fun configuringDoesNotUploadAndResultsRequireExplicitApply() {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val response = CompletableDeferred<String>()
        var uploads = 0
        var applied = "original edit"
        try {
            compose.setContent {
                CompositionLocalProvider(LocalContext provides isolated) {
                    MaterialTheme {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            PaddleFormulaPanel(bitmap, true, recognize = { actual, config ->
                                assertSame(bitmap, actual)
                                assertEquals("TEST_ONLY_token", config.token)
                                uploads++
                                response.await()
                            }) { applied = it }
                        }
                    }
                }
            }
            compose.onNodeWithTag("paddle-recognize").assertIsNotEnabled()
            compose.onNodeWithTag("paddle-settings").performClick()
            compose.onNodeWithTag("paddle-token").performTextReplacement("TEST_ONLY_token")
            compose.onNodeWithTag("paddle-save").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("paddle-token").fetchSemanticsNodes().isEmpty() }
            assertEquals(0, uploads)
            compose.onNodeWithTag("paddle-recognize").assertIsEnabled().performClick()
            compose.waitUntil(5000) { uploads == 1 }
            assertEquals("original edit", applied)
            response.complete("e^{\\arctan(1+x)}")
            compose.waitUntil(5000) { compose.onAllNodesWithTag("paddle-result").fetchSemanticsNodes().isNotEmpty() }
            assertEquals("original edit", applied)
            compose.onNodeWithText(base.getString(R.string.paddle_apply)).performScrollTo().performClick()
            assertEquals("e^{\\arctan(1+x)}", applied)
        } finally { PaddleFormulaSettings.clear(isolated); bitmap.recycle() }
    }

    @Test fun authenticationFailureAndCancellationPreserveEditsAndAllowRetry() {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        var calls = 0
        var applied = "x=original"
        try {
            PaddleFormulaSettings.save(isolated, PaddleFormulaApi.Config(token = "TEST_ONLY_token"))
            compose.setContent {
                CompositionLocalProvider(LocalContext provides isolated) {
                    MaterialTheme {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            PaddleFormulaPanel(bitmap, true, recognize = { _, _ ->
                                when (++calls) {
                                    1 -> throw PaddleFormulaApi.Error(PaddleFormulaApi.Failure.AUTH)
                                    2 -> awaitCancellation()
                                    else -> "x=3"
                                }
                            }) { applied = it }
                        }
                    }
                }
            }
            compose.waitUntil(5000) { compose.onAllNodes(hasTestTag("paddle-recognize") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            assertEquals(0, calls)
            compose.onNodeWithTag("paddle-recognize").performClick()
            compose.onNodeWithText(base.getString(R.string.paddle_auth_error)).assertExists()
            assertEquals("x=original", applied)
            compose.onNodeWithTag("paddle-recognize").performClick()
            compose.onNodeWithText(base.getString(R.string.paddle_cancel)).performScrollTo().performClick()
            compose.onNodeWithTag("paddle-result").assertDoesNotExist()
            assertEquals("x=original", applied)
            compose.onNodeWithTag("paddle-recognize").performScrollTo().performClick()
            compose.onNodeWithTag("paddle-result").assertTextEquals("x=3")
            assertEquals(3, calls)
            assertEquals("x=original", applied)
        } finally { PaddleFormulaSettings.clear(isolated); bitmap.recycle() }
    }

    @Test fun liteHasNoModelAssetsAndCleanupDoesNotFollowLinks() {
        val assets = base.assets.list("").orEmpty()
        assertFalse(assets.any { it in setOf("formula-gryph", "formula-precision", "formula-texo") })
        val outside = File(base.cacheDir, "model-cleanup-outside-${UUID.randomUUID()}").apply { mkdirs() }
        val preserved = File(outside, "encoder-q8.onnx").apply { writeText("outside scope") }
        val link = File(isolated.noBackupFilesDir, "formula-gryph-q8-v1")
        try {
            java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath())
            assertEquals(0L, com.xnotes.core.formula.LegacyModelCleanup.removeFrom(isolated.noBackupFilesDir))
            assertEquals("outside scope", preserved.readText())
        } finally { link.delete(); preserved.delete(); outside.delete() }
    }
}
