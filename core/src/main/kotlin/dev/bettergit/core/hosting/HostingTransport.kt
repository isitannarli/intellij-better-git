package dev.bettergit.core.hosting

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

fun interface HostingTransport {
    @Throws(java.io.IOException::class, InterruptedException::class)
    fun execute(request: HostingHttpRequest): HostingHttpResponse
}

class HostingHttpRequest(
    val uri: URI,
    val headers: Map<String, String>,
    val timeout: Duration,
) {
    override fun toString(): String = "HostingHttpRequest(method=GET, host=${uri.host}, headers=[redacted])"
}

class HostingHttpResponse(
    val statusCode: Int,
    val body: String,
    val headers: Map<String, List<String>> = emptyMap(),
) {
    fun header(name: String): String? = headers.entries
        .firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.joinToString(",")

    override fun toString(): String = "HostingHttpResponse(statusCode=$statusCode, body=[redacted])"
}

class JdkHostingTransport(connectTimeout: Duration = Duration.ofSeconds(10)) : HostingTransport {
    private val client = HttpClient.newBuilder()
        .connectTimeout(connectTimeout)
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    override fun execute(request: HostingHttpRequest): HostingHttpResponse {
        require(request.uri.scheme == "https" && request.uri.userInfo == null) {
            "Hosting requests require HTTPS without URL credentials."
        }
        val builder = HttpRequest.newBuilder(request.uri).timeout(request.timeout).GET()
        request.headers.forEach { (name, value) -> builder.header(name, value) }
        val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        return HostingHttpResponse(response.statusCode(), response.body(), response.headers().map())
    }
}
