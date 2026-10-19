package dev.bettergit.core.ai

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.URI
import java.net.http.HttpTimeoutException
import java.util.concurrent.CancellationException

class LocalCommitMessageGeneratorTest {
    @Test fun `verifies local model before sending staged diff and requests a nonstreaming message`() {
        val transport = RecordingTransport(LocalAiResponse(200, LOCAL_METADATA), LocalAiResponse(200, """{"response":"fix: handle empty input\n\nAvoid a null result.","done":true}"""))
        val message = LocalCommitMessageGenerator(URI("http://localhost:11434"), transport).generate("+private source code")
        assertEquals("fix: handle empty input\n\nAvoid a null result.", message)
        val show = transport.requests[0]
        assertEquals("http://127.0.0.1:11434/api/show", show.uri.toString())
        assertFalse(show.body.contains("private source code"))
        val generate = transport.requests[1]
        assertEquals("/api/generate", generate.uri.path)
        val body = JsonParser.parseString(generate.body).asJsonObject
        assertFalse(body["stream"].asBoolean)
        assertEquals("qwen2.5-coder:7b", body["model"].asString)
        assertTrue(body["prompt"].asString.contains("+private source code"))
        assertTrue(body["system"].asString.contains("untrusted source material"))
        assertFalse(generate.toString().contains("private source code"))
    }

    @Test fun `only loopback origins without credentials paths or queries are accepted`() {
        listOf("https://api.example.com", "http://127.0.0.1.evil.test", "http://localhost@evil.test", "http://user@localhost:11434",
            "http://localhost:11434/proxy", "http://localhost:11434?destination=cloud", "http://0.0.0.0:11434",
            "http://192.168.1.1:11434", "http://127.0.0.999:11434", "ftp://localhost:11434").forEach { endpoint ->
            assertThrows(IllegalArgumentException::class.java) { LocalCommitMessageGenerator(URI(endpoint), RecordingTransport()) }
        }
        assertDoesNotThrow { LocalCommitMessageGenerator(URI("http://[::1]:11434"), RecordingTransport()) }
        assertDoesNotThrow { LocalCommitMessageGenerator(URI("http://127.0.0.2:11434"), RecordingTransport()) }
    }

    @Test fun `refuses cloud tags without sending source code`() {
        val transport = RecordingTransport()
        val generator = LocalCommitMessageGenerator(transport = transport)
        assertThrows(IllegalArgumentException::class.java) { generator.generate("+source", "gemma:cloud") }
        assertThrows(IllegalArgumentException::class.java) { generator.generate("+source", "model-cloud") }
        assertTrue(transport.requests.isEmpty())
    }

    @Test fun `refuses cloud aliases and unknown model metadata before sending code`() {
        listOf("{}", """{"details":{"format":"gguf"}}""",
            """{"remote_host":"https://ollama.com","remote_model":"large-model","details":{"format":"gguf"},"model_info":{"architecture":"x"}}""").forEach { metadata ->
            val transport = RecordingTransport(LocalAiResponse(200, metadata))
            assertThrows(LocalAiException::class.java) { LocalCommitMessageGenerator(transport = transport).generate("+private", "local-looking-alias") }
            assertEquals(1, transport.requests.size)
            assertFalse(transport.requests.single().body.contains("+private"))
        }
    }

    @Test fun `enforces UTF8 diff byte limit and requires staged changes`() {
        val transport = RecordingTransport()
        val generator = LocalCommitMessageGenerator(transport = transport)
        assertThrows(IllegalArgumentException::class.java) { generator.generate("   ") }
        assertThrows(IllegalArgumentException::class.java) { generator.generate("x".repeat(100 * 1024 + 1)) }
        assertThrows(IllegalArgumentException::class.java) { generator.generate("\u00E9".repeat(60 * 1024)) }
        assertTrue(transport.requests.isEmpty())
    }

    @Test fun `missing model error is actionable and never installs a model`() {
        val transport = RecordingTransport(LocalAiResponse(404, "private server error"))
        val error = assertThrows(LocalAiException::class.java) { LocalCommitMessageGenerator(transport = transport).generate("+source") }
        assertTrue(error.message!!.contains("not installed"))
        assertFalse(error.toString().contains("private server error"))
        assertEquals(listOf("/api/show"), transport.requests.map { it.uri.path })
    }

    @Test fun `redirects are refused before source code reaches a different origin`() {
        val transport = RecordingTransport(LocalAiResponse(307, "redirect to private endpoint"))
        val error = assertThrows(LocalAiException::class.java) { LocalCommitMessageGenerator(transport = transport).generate("+source") }
        assertTrue(error.message!!.contains("Redirects are disabled"))
        assertEquals(1, transport.requests.size)
    }

    @Test fun `network errors and malformed responses never expose code or raw error text`() {
        listOf(IOException("private code"), HttpTimeoutException("private code")).forEach { failure ->
            val error = assertThrows(LocalAiException::class.java) {
                LocalCommitMessageGenerator(transport = LocalAiTransport { throw failure }).generate("+source")
            }
            assertFalse(error.toString().contains("private code"))
            assertNull(error.cause)
        }
        listOf("not-json", "{}", """{"response":""}""", """{"response":null}""").forEach { response ->
            val transport = RecordingTransport(LocalAiResponse(200, LOCAL_METADATA), LocalAiResponse(200, response))
            assertThrows(LocalAiException::class.java) { LocalCommitMessageGenerator(transport = transport).generate("+source") }
        }
    }

    @Test fun `cancellation is preserved`() {
        try {
            assertThrows(CancellationException::class.java) {
                LocalCommitMessageGenerator(transport = LocalAiTransport { throw InterruptedException() }).generate("+source")
            }
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
    }

    private class RecordingTransport(vararg responses: LocalAiResponse) : LocalAiTransport {
        private val queue = ArrayDeque(responses.toList())
        val requests = mutableListOf<LocalAiRequest>()
        override fun execute(request: LocalAiRequest): LocalAiResponse { requests += request; return queue.removeFirst() }
    }

    companion object {
        private const val LOCAL_METADATA = """{"details":{"format":"gguf","family":"qwen2"},"model_info":{"general.architecture":"qwen2"}}"""
    }
}
