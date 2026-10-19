package dev.bettergit.core.hosting.avatar

import com.google.gson.JsonParser
import dev.bettergit.core.hosting.HostedRepository
import dev.bettergit.core.hosting.HostingProvider
import dev.bettergit.core.hosting.encodePath
import java.io.IOException
import java.net.URI
import java.time.Duration
import java.util.Locale
import java.util.concurrent.CancellationException

class AvatarUnavailableException(val rateLimited: Boolean = false) : RuntimeException("Author avatar is unavailable.")

class AuthorAvatarClient(
    private val transport: AvatarTransport = JdkAvatarTransport(),
    private val timeout: Duration = Duration.ofSeconds(5),
) : AutoCloseable {
    init { require(timeout > Duration.ZERO && timeout <= Duration.ofSeconds(10)) }

    fun load(repository: HostedRepository, authorEmail: String): ByteArray? {
        val email = authorEmail.trim().removeSurrounding("<", ">").trim().lowercase(Locale.ROOT)
        if (email.length !in 3..320 || email.count { it == '@' } != 1 || email.startsWith('@') || email.endsWith('@') ||
            email.any { it.isISOControl() || it.isWhitespace() }) return null
        val url = when (repository.provider) {
            HostingProvider.GITHUB -> {
                if (URI(repository.webUrl).host.equals("github.com", true)) {
                    val id = Regex("^([1-9][0-9]{0,19})\\+[^@]+@users\\.noreply\\.github\\.com$").matchEntire(email)?.groupValues?.get(1)
                    if (id != null) "https://avatars.githubusercontent.com/u/$id?s=72"
                    else "https://avatars.githubusercontent.com/u/e?email=${encodePath(email)}&s=72"
                } else "https://${repository.host}/api/v3/enterprise/avatars/u/e?email=${encodePath(email)}&s=72"
            }
            HostingProvider.GITLAB -> {
                val response = get(URI("https://${repository.host}/api/v4/avatar?email=${encodePath(email)}&size=72"), JSON_LIMIT, json = true)
                requireSuccess(response)
                try {
                    JsonParser.parseString(response.bytes.toString(Charsets.UTF_8)).asJsonObject
                        .get("avatar_url")?.takeUnless { it.isJsonNull }?.asString ?: return null
                } catch (_: RuntimeException) { return null }
            }
        }
        var uri = allowedImage(repository, url) ?: return null
        repeat(3) { attempt ->
            val image = get(uri, MAX_IMAGE_BYTES, json = false)
            if (image.status in setOf(301, 302, 303, 307, 308)) {
                if (attempt == 2) return null
                val location = image.header("Location") ?: return null
                val resolved = try { uri.resolve(location).toString() } catch (_: IllegalArgumentException) { return null }
                uri = allowedImage(repository, resolved) ?: return null
            } else {
                requireSuccess(image)
                val type = image.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
                if (type != null && !type.startsWith("image/") && type != "application/octet-stream") return null
                return image.bytes.takeIf { it.isNotEmpty() }
            }
        }
        return null
    }

    private fun get(uri: URI, limit: Int, json: Boolean): AvatarResponse {
        checkCancellation()
        val headers = mapOf("Accept" to if (json) "application/json" else "image/png,image/jpeg,image/gif", "User-Agent" to "BetterGit-JetBrains")
        val response = try { transport.execute(AvatarRequest(uri, headers, timeout, limit)) }
        catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw CancellationException("Avatar request cancelled.")
        } catch (_: IOException) { throw AvatarUnavailableException() }
        checkCancellation()
        if (response.bytes.size > limit) throw AvatarUnavailableException()
        return response
    }

    private fun requireSuccess(response: AvatarResponse) {
        if (response.status !in 200..299) throw AvatarUnavailableException(response.status == 429 ||
            (response.status == 403 && (response.header("X-RateLimit-Remaining") == "0" || response.header("Retry-After") != null)))
    }

    private fun checkCancellation() {
        if (Thread.currentThread().isInterrupted) throw CancellationException("Avatar request cancelled.")
    }

    override fun close() { (transport as? AutoCloseable)?.close() }

    companion object {
        const val MAX_IMAGE_BYTES = 1024 * 1024
        private const val JSON_LIMIT = 64 * 1024

        internal fun allowedImage(repository: HostedRepository, value: String): URI? = runCatching {
            if (value.length > 4096 || value.any(Char::isISOControl)) return null
            val uri = URI(repository.webUrl).resolve(value)
            if (uri.scheme != "https" || uri.host == null || uri.rawUserInfo != null || uri.rawFragment != null) return null
            val cdn = when (repository.provider) {
                HostingProvider.GITHUB -> uri.host.equals("avatars.githubusercontent.com", true)
                HostingProvider.GITLAB -> uri.host.lowercase(Locale.ROOT) in setOf("gravatar.com", "www.gravatar.com", "secure.gravatar.com", "0.gravatar.com", "1.gravatar.com", "2.gravatar.com")
            }
            uri.takeIf { sameOrigin(it, URI(repository.webUrl)) || (cdn && it.port in setOf(-1, 443)) }
        }.getOrNull()

        private fun sameOrigin(a: URI, b: URI) = a.scheme == b.scheme && a.host.equals(b.host, true) &&
            (if (a.port == -1) 443 else a.port) == (if (b.port == -1) 443 else b.port)
    }
}
