package dev.bettergit.core.ai

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutionException
import java.util.concurrent.Flow
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

fun interface CloudAiTransport {
    @Throws(IOException::class, InterruptedException::class)
    fun execute(request: CloudAiRequest): CloudAiResponse
}

class CloudAiRequest(
    val provider: AiProvider,
    val uri: URI,
    val headers: Map<String, String>,
    val body: String,
    val timeout: Duration,
) {
    override fun toString(): String = "CloudAiRequest(provider=$provider, uri=[redacted], headers=[redacted], body=[redacted])"
}

class CloudAiResponse(val statusCode: Int, val body: String) {
    override fun toString(): String = "CloudAiResponse(statusCode=$statusCode, body=[redacted])"
}

class JdkCloudAiTransport : CloudAiTransport {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER).build()

    override fun execute(request: CloudAiRequest): CloudAiResponse {
        validateCloudRequest(request)
        val builder = HttpRequest.newBuilder(request.uri).timeout(request.timeout)
            .POST(HttpRequest.BodyPublishers.ofString(request.body))
        request.headers.forEach { (name, value) -> builder.header(name, value) }
        val future = client.sendAsync(builder.build(), HttpResponse.BodyHandler { BoundedAiBodySubscriber(MAX_RESPONSE_BYTES) })
        try {
            val response = future.get(request.timeout.toNanos(), TimeUnit.NANOSECONDS)
            return CloudAiResponse(response.statusCode(), response.body())
        } catch (_: TimeoutException) {
            future.cancel(true)
            throw HttpTimeoutException("AI generation timed out.")
        } catch (error: InterruptedException) {
            future.cancel(true)
            throw error
        } catch (error: ExecutionException) {
            when (val cause = error.cause) {
                is AiGenerationException -> throw cause
                is IOException -> throw cause
                else -> throw IOException("AI provider request failed.")
            }
        }
    }

    companion object {
        const val MAX_RESPONSE_BYTES = 1024 * 1024
        const val MAX_REQUEST_BYTES = 1024 * 1024
    }
}

internal fun validateCloudRequest(request: CloudAiRequest) {
    val uri = request.uri
    val pathMatches = when (request.provider) {
        AiProvider.OPENAI -> uri.host == "api.openai.com" && uri.rawPath == "/v1/responses"
        AiProvider.GEMINI -> uri.host == "generativelanguage.googleapis.com" &&
            Regex("/v1beta/models/[A-Za-z0-9][A-Za-z0-9._-]{0,127}:generateContent").matches(uri.rawPath.orEmpty())
        AiProvider.CLAUDE -> uri.host == "api.anthropic.com" && uri.rawPath == "/v1/messages"
        AiProvider.OLLAMA -> false
    }
    require(uri.scheme == "https" && uri.port in setOf(-1, 443) && uri.rawUserInfo == null &&
        uri.rawQuery == null && uri.rawFragment == null && pathMatches) { "Unsupported AI provider endpoint." }
    val allowedHeaders = when (request.provider) {
        AiProvider.OPENAI -> setOf("content-type", "authorization")
        AiProvider.GEMINI -> setOf("content-type", "x-goog-api-key")
        AiProvider.CLAUDE -> setOf("content-type", "x-api-key", "anthropic-version", "anthropic-workspace-id")
        AiProvider.OLLAMA -> emptySet()
    }
    require(request.headers.all { (name, value) -> name.lowercase(java.util.Locale.ROOT) in allowedHeaders &&
        value.isNotEmpty() && value.length <= 8192 && value.all { it.code in 32..126 } }) { "Invalid AI request headers." }
    require(!request.timeout.isNegative && !request.timeout.isZero && request.timeout <= Duration.ofMinutes(5)) {
        "AI request timeout must be positive and no longer than five minutes."
    }
    require(request.body.toByteArray(Charsets.UTF_8).size <= JdkCloudAiTransport.MAX_REQUEST_BYTES) { "AI request exceeds its size limit." }
}

internal class BoundedAiBodySubscriber(private val limit: Int) : HttpResponse.BodySubscriber<String> {
    private val body = CompletableFuture<String>()
    private val output = ByteArrayOutputStream()
    private var subscription: Flow.Subscription? = null

    override fun getBody(): CompletionStage<String> = body
    override fun onSubscribe(value: Flow.Subscription) {
        if (subscription != null) { value.cancel(); return }
        subscription = value
        value.request(1)
    }
    override fun onNext(buffers: List<ByteBuffer>) {
        if (body.isDone) return
        for (buffer in buffers) {
            if (buffer.remaining() > limit - output.size()) {
                subscription?.cancel()
                body.completeExceptionally(AiGenerationException("The AI provider response exceeded the 1 MiB safety limit."))
                return
            }
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            output.write(bytes)
        }
        subscription?.request(1)
    }
    override fun onError(error: Throwable) { body.completeExceptionally(error) }
    override fun onComplete() { body.complete(output.toString(Charsets.UTF_8)) }
}
