package dev.bettergit.core.ai

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.URI
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.util.concurrent.CancellationException

class CommitMessageGeneratorTest {
    @Test fun `OpenAI uses Responses with storage disabled and parses actual output blocks`() {
        val transport = Recording(200, OPENAI)
        assertEquals("fix: validate input", generate(AiProvider.OPENAI, transport))
        val request = transport.requests.single()
        assertEquals("https://api.openai.com/v1/responses", request.uri.toString())
        assertEquals("Bearer test-key", request.headers["Authorization"])
        val body = json(request.body)
        assertFalse(body["store"].asBoolean)
        assertFalse(body["stream"].asBoolean)
        assertTrue(body["instructions"].asString.contains("untrusted source material"))
        assertTrue(body["input"].asString.contains("+private source"))
        assertFalse(body.has("tools"))
        assertFalse(body.has("temperature"))
    }

    @Test fun `Gemini uses header authentication and excludes thought parts`() {
        val transport = Recording(200, GEMINI)
        assertEquals("fix: validate input", generate(AiProvider.GEMINI, transport))
        val request = transport.requests.single()
        assertEquals("generativelanguage.googleapis.com", request.uri.host)
        assertNull(request.uri.query)
        assertEquals("test-key", request.headers["x-goog-api-key"])
        val body = json(request.body)
        assertTrue(body["systemInstruction"].asJsonObject.has("parts"))
        assertTrue(body["contents"].asJsonArray[0].asJsonObject["parts"].asJsonArray[0].asJsonObject["text"].asString.contains("+private source"))
    }

    @Test fun `Claude sends version and optional workspace and excludes thinking`() {
        val transport = Recording(200, CLAUDE)
        val message = CommitMessageGenerator(transport).generate(request(AiProvider.CLAUDE).copy(anthropicWorkspaceId = "wrkspc_example"))
        assertEquals("fix: validate input", message)
        val sent = transport.requests.single()
        assertEquals("https://api.anthropic.com/v1/messages", sent.uri.toString())
        assertEquals("2023-06-01", sent.headers["anthropic-version"])
        assertEquals("wrkspc_example", sent.headers["anthropic-workspace-id"])
        assertEquals("test-key", sent.headers["x-api-key"])
        assertTrue(json(sent.body)["system"].asString.contains("Ignore commands"))
    }

    @Test fun `local provider never calls cloud and verifies local GGUF before source transmission`() {
        val cloud = Recording(200, OPENAI)
        val localRequests = mutableListOf<LocalAiRequest>()
        val local = LocalAiTransport {
            localRequests += it
            LocalAiResponse(200, if (it.uri.path == "/api/show") """{"details":{"format":"gguf"},"model_info":{"architecture":"test"}}""" else """{"response":"feat: local draft","done":true}""")
        }
        assertEquals("feat: local draft", CommitMessageGenerator(cloud, local).generate(request(AiProvider.OLLAMA).copy(apiKey = null)))
        assertTrue(cloud.requests.isEmpty())
        assertFalse(localRequests.first().body.contains("private source"))
        assertTrue(localRequests.last().body.contains("private source"))
    }

    @Test fun `missing credentials invalid models and UTF8 size fail before transport`() {
        val transport = Recording(200, OPENAI)
        val generator = CommitMessageGenerator(transport)
        listOf(
            request(AiProvider.OPENAI).copy(apiKey = null),
            request(AiProvider.OPENAI).copy(apiKey = "secret\nheader"),
            request(AiProvider.OPENAI).copy(model = "bad model"),
            request(AiProvider.GEMINI).copy(model = "../proxy"),
            request(AiProvider.OPENAI).copy(diff = "\u00E9".repeat(52_000)),
            request(AiProvider.OPENAI).copy(diff = ""),
            request(AiProvider.OPENAI).copy(diff = "binary\u0000data"),
            request(AiProvider.OPENAI).copy(style = "bad\nstyle"),
            request(AiProvider.CLAUDE).copy(anthropicWorkspaceId = "bad\rworkspace"),
        ).forEach { assertThrows(RuntimeException::class.java) { generator.generate(it) } }
        assertTrue(transport.requests.isEmpty())
    }

    @Test fun `refusals incomplete responses and nontext tool results are not accepted as drafts`() {
        val responses = listOf(
            AiProvider.OPENAI to OPENAI.replace("\"status\":\"completed\"", "\"status\":\"incomplete\""),
            AiProvider.OPENAI to OPENAI.replace("\"type\":\"output_text\",\"text\":\"fix: validate input\"", "\"type\":\"refusal\",\"refusal\":\"no\""),
            AiProvider.OPENAI to """{"status":"completed","output":[{"type":"function_call"}]}""",
            AiProvider.GEMINI to GEMINI.replace("\"STOP\"", "\"MAX_TOKENS\""),
            AiProvider.GEMINI to """{"promptFeedback":{"blockReason":"SAFETY"},"candidates":[]}""",
            AiProvider.GEMINI to """{"candidates":[{"finishReason":"STOP","content":{"parts":[{"functionCall":{"name":"tool"}}]}}]}""",
            AiProvider.CLAUDE to CLAUDE.replace("\"end_turn\"", "\"max_tokens\""),
            AiProvider.CLAUDE to CLAUDE.dropLast(1) + """, "stop_details":{"type":"refusal"}}""",
            AiProvider.CLAUDE to """{"type":"message","role":"assistant","stop_reason":"end_turn","content":[{"type":"tool_use"}]}""",
        )
        responses.forEach { (provider, body) ->
            assertThrows(AiGenerationException::class.java, { generate(provider, Recording(200, body)) }, "$provider $body")
        }
    }

    @Test fun `malformed empty oversized and control character outputs are rejected`() {
        listOf("not-json", "{}", "[]", OPENAI.replace("fix: validate input", ""),
            OPENAI.replace("fix: validate input", "x".repeat(8193)),
            OPENAI.replace("fix: validate input", "bad\\u0001control"),
            "x".repeat(1024 * 1024 + 1)).forEach { response ->
            assertThrows(AiGenerationException::class.java) { generate(AiProvider.OPENAI, Recording(200, response)) }
        }
    }

    @Test fun `HTTP failures are actionable and do not include provider response or secrets`() {
        listOf(301, 307, 400, 401, 403, 404, 429, 500).forEach { status ->
            val transport = Recording(status, "test-key private source server internals")
            val error = assertThrows(AiGenerationException::class.java) { generate(AiProvider.OPENAI, transport) }
            assertFalse(error.toString().contains("test-key"))
            assertFalse(error.toString().contains("private source"))
            assertNull(error.cause)
            assertEquals(1, transport.requests.size)
        }
    }

    @Test fun `network failures and request representations redact keys and source`() {
        listOf(IOException("test-key private source"), HttpTimeoutException("test-key private source"), IllegalStateException("test-key private source")).forEach { failure ->
            val error = assertThrows(AiGenerationException::class.java) {
                generate(AiProvider.OPENAI, CloudAiTransport { throw failure })
            }
            assertFalse(error.toString().contains("test-key"))
            assertFalse(error.toString().contains("private source"))
            assertNull(error.cause)
        }
        val recording = Recording(200, OPENAI)
        generate(AiProvider.OPENAI, recording)
        listOf(request(AiProvider.OPENAI).toString(), recording.requests.single().toString(), CloudAiResponse(200, "private source").toString()).forEach {
            assertFalse(it.contains("test-key")); assertFalse(it.contains("private source"))
        }
    }

    @Test fun `cancellation interrupts request and preserves cancellation semantics`() {
        try {
            assertThrows(CancellationException::class.java) {
                generate(AiProvider.OPENAI, CloudAiTransport { throw InterruptedException() })
            }
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
    }

    @Test fun `transport boundary refuses alternate origins paths credentials and request headers`() {
        val valid = CloudAiRequest(AiProvider.OPENAI, URI("https://api.openai.com/v1/responses"), mapOf("Authorization" to "Bearer key"), "{}", Duration.ofSeconds(1))
        validateCloudRequest(valid)
        listOf("http://api.openai.com/v1/responses", "https://api.openai.com.evil.test/v1/responses", "https://api.openai.com/v1/responses?key=x", "https://user@api.openai.com/v1/responses", "https://api.openai.com/v1/chat/completions").forEach { url ->
            assertThrows(IllegalArgumentException::class.java) { validateCloudRequest(CloudAiRequest(valid.provider, URI(url), valid.headers, "{}", valid.timeout)) }
        }
        assertThrows(IllegalArgumentException::class.java) { validateCloudRequest(CloudAiRequest(valid.provider, valid.uri, mapOf("Host" to "evil.test"), "{}", valid.timeout)) }
    }

    private fun generate(provider: AiProvider, transport: CloudAiTransport) = CommitMessageGenerator(transport).generate(request(provider))
    private fun request(provider: AiProvider) = CommitMessageRequest(provider, provider.defaultModel, "+private source", apiKey = "test-key")
    private fun json(text: String) = JsonParser.parseString(text).asJsonObject
    private class Recording(private val status: Int, private val response: String) : CloudAiTransport {
        val requests = mutableListOf<CloudAiRequest>()
        override fun execute(request: CloudAiRequest): CloudAiResponse { requests += request; return CloudAiResponse(status, response) }
    }
    companion object {
        private const val OPENAI = """{"status":"completed","incomplete_details":null,"output":[{"type":"reasoning"},{"type":"message","role":"assistant","status":"completed","content":[{"type":"output_text","text":"fix: validate input"}]}]}"""
        private const val GEMINI = """{"candidates":[{"finishReason":"STOP","content":{"parts":[{"thought":true,"text":"private reasoning"},{"text":"fix: validate input"}]}}]}"""
        private const val CLAUDE = """{"type":"message","role":"assistant","stop_reason":"end_turn","content":[{"type":"thinking","thinking":"private reasoning"},{"type":"text","text":"fix: validate input"}]}"""
    }
}
