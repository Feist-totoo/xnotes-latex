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
}
