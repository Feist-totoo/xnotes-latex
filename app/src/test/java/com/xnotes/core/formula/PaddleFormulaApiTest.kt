package com.xnotes.core.formula

import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PaddleFormulaApiTest {
    private val config = PaddleFormulaApi.Config(token = "test-token")
    private val png = byteArrayOf(1, 2, 3, 4)
    private fun response(text: String) = JSONObject().put("result", JSONObject().put("layoutParsingResults",
        JSONArray().put(JSONObject().put("markdown", JSONObject().put("text", text))))).toString()

    @Test fun asyncUploadsOncePollsAndDownloadsWithoutLeakingToken() = runBlocking {
        val requests = mutableListOf<PaddleFormulaApi.Request>()
        val replies = ArrayDeque(listOf(
            """{"code":0,"data":{"jobId":"job-123"}}""",
            """{"data":{"state":"running"}}""",
            """{"data":{"state":"done","resultUrl":{"jsonUrl":"https://results.bj.bcebos.com/formula.jsonl?signature=example"}}}""",
            response("\$\$e ^ { \\arctan ( 1 + x ) }\$\$")
        ))
        val result = PaddleFormulaApi.recognize(png, config) { request ->
            requests += request
            PaddleFormulaApi.Response(200, replies.removeFirst())
        }
        assertEquals("e^{\\arctan(1+x)}", result)
        assertEquals(4, requests.size)
        assertEquals(1, requests.count { it.method == "POST" })
        assertEquals("Bearer test-token", requests.first().headers["Authorization"])
        assertTrue(requests.last().headers.isEmpty())
        val upload = String(requests.first().body!!)
        assertTrue(upload.contains("PaddleOCR-VL-1.6"))
        assertTrue(upload.contains("\"promptLabel\":\"formula\""))
        assertTrue(upload.contains("\"useLayoutDetection\":false"))
        assertTrue(upload.contains("filename=\"selection.png\""))
        assertFalse(upload.contains("test-token"))
    }

    @Test fun dedicatedOfficialEndpointUsesTokenAndBase64Contract() = runBlocking {
        val dedicated = PaddleFormulaApi.Config("https://sample.aistudio-app.com/layout-parsing", "test-token")
        val result = PaddleFormulaApi.recognize(png, dedicated) { request ->
            assertEquals("token test-token", request.headers["Authorization"])
            val payload = JSONObject(String(request.body!!))
            assertEquals("AQIDBA==", payload.getString("file"))
            assertEquals(1, payload.getInt("fileType"))
            assertEquals("formula", payload.getString("promptLabel"))
            assertFalse(payload.getBoolean("visualize"))
            PaddleFormulaApi.Response(200, response("\\[\\frac { 1 2 } { x }\\]"))
        }
        assertEquals("\\frac{12}{x}", result)
    }

    @Test fun invalidConfigurationCannotMakeAnyRequest() = runBlocking {
        for (url in listOf("http://sample.aistudio-app.com/layout-parsing", "https://sample.aistudio-app.com.evil.com/layout-parsing",
            "https://test-token@sample.aistudio-app.com/layout-parsing", "https://sample.aistudio-app.com/layout-parsing?token=x",
            "https://sample.aistudio-app.com/ocr", "https://127.0.0.1/layout-parsing")) {
            try {
                PaddleFormulaApi.recognize(png, PaddleFormulaApi.Config(url, "test-token")) { error("Must not connect") }
                fail(url)
            } catch (e: PaddleFormulaApi.Error) { assertEquals(PaddleFormulaApi.Failure.CONFIG, e.reason) }
        }
        assertFalse(PaddleFormulaApi.valid(PaddleFormulaApi.Config(token = "bad\r\nheader")))
        assertFalse(config.toString().contains("test-token"))
        assertFalse(PaddleFormulaApi.validResultUrl("https://results.bcebos.com.evil.com/file"))
        assertFalse(PaddleFormulaApi.validResultUrl("http://results.bcebos.com/file"))
    }

    @Test fun errorsAreSafeAndNeverResubmit() = runBlocking {
        for ((status, expected) in listOf(401 to PaddleFormulaApi.Failure.AUTH, 403 to PaddleFormulaApi.Failure.AUTH,
            429 to PaddleFormulaApi.Failure.QUOTA, 500 to PaddleFormulaApi.Failure.SERVICE, 302 to PaddleFormulaApi.Failure.SERVICE)) {
            var count = 0
            try {
                PaddleFormulaApi.recognize(png, config) { count++; PaddleFormulaApi.Response(status, "secret echoed by server") }
                fail("Must fail")
            } catch (e: PaddleFormulaApi.Error) { assertEquals(expected, e.reason); assertFalse(e.message!!.contains("secret")) }
            assertEquals(1, count)
        }
    }

    @Test fun multipleOrMissingFormulasAreNotSilentlyApplied() {
        for (raw in listOf("\$\$x\$\$\n\$\$y\$\$", "Explanation: \$x\$", "", "\\frac{x}{", "a".repeat(9000))) {
            assertThrows(PaddleFormulaApi.Error::class.java) { PaddleFormulaApi.extract(JSONObject(response(raw))) }
        }
        assertThrows(PaddleFormulaApi.Error::class.java) { PaddleFormulaApi.extract(JSONObject("""{"result":{"layoutParsingResults":[]}}""")) }
        assertThrows(PaddleFormulaApi.Error::class.java) { PaddleFormulaApi.extract(JSONObject("""{"errorCode":500,"result":{}}""")) }
        assertEquals("\\text{for all }x>0", PaddleFormulaApi.extract(JSONObject(response("\\text {for all } x > 0"))))
    }

    @Test fun cancellationStopsPollingAndDoesNotApplyLateResults() = runBlocking {
        val started = CompletableDeferred<Unit>()
        var requests = 0
        var applied = false
        val task = launch {
            PaddleFormulaApi.recognize(png, config) { requests++; started.complete(Unit); awaitCancellation() }
            applied = true
        }
        started.await()
        task.cancelAndJoin()
        assertEquals(1, requests)
        assertFalse(applied)
    }
}
