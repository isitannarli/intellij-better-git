package dev.bettergit.core.hosting.avatar

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

fun interface AvatarTransport {
    @Throws(IOException::class, InterruptedException::class)
    fun execute(request: AvatarRequest): AvatarResponse
}

class AvatarRequest(val uri: URI, val headers: Map<String, String>, val timeout: Duration, val limit: Int) {
    override fun toString() = "AvatarRequest(uri=[redacted], headers=[redacted])"
}

class AvatarResponse(val status: Int, val bytes: ByteArray, val headers: Map<String, List<String>> = emptyMap()) {
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, true) }?.value?.firstOrNull()
    override fun toString() = "AvatarResponse(status=$status, body=[redacted])"
}

class JdkAvatarTransport : AvatarTransport, AutoCloseable {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4))
        .followRedirects(HttpClient.Redirect.NEVER).build()

    override fun execute(request: AvatarRequest): AvatarResponse {
        require(request.uri.scheme == "https" && request.uri.host != null && request.uri.userInfo == null)
        require(request.limit in 1..AuthorAvatarClient.MAX_IMAGE_BYTES)
        require(request.timeout > Duration.ZERO && request.timeout <= Duration.ofSeconds(10))
        val builder = HttpRequest.newBuilder(request.uri).timeout(request.timeout).GET()
        request.headers.forEach { (name, value) -> builder.header(name, value) }
        val future = client.sendAsync(builder.build(), HttpResponse.BodyHandlers.limiting(
            HttpResponse.BodyHandlers.ofByteArray(), request.limit.toLong(),
        ))
        try {
            val response = future.get(request.timeout.toNanos(), TimeUnit.NANOSECONDS)
            return AvatarResponse(response.statusCode(), response.body(), response.headers().map())
        } catch (_: TimeoutException) {
            future.cancel(true)
            throw HttpTimeoutException("Avatar request timed out.")
        } catch (cancelled: InterruptedException) {
            future.cancel(true)
            throw cancelled
        } catch (_: ExecutionException) {
            throw IOException("Avatar request failed.")
        }
    }

    override fun close() { client.shutdownNow() }
}
