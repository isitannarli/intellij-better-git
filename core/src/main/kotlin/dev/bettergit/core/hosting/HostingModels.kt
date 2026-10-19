package dev.bettergit.core.hosting

import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant

enum class HostingProvider { GITHUB, GITLAB }

data class HostedRepository(
    val provider: HostingProvider,
    val host: String,
    val owner: String,
    val name: String,
) {
    init {
        require(isValidHost(host)) { "Enter a hosting hostname with an optional HTTPS port." }
        require(owner.split('/').all(::isValidPathSegment) && isValidPathSegment(name)) {
            "Enter a valid repository owner and name."
        }
        require(provider != HostingProvider.GITHUB || '/' !in owner) {
            "GitHub repositories have exactly one owner."
        }
    }

    val fullPath: String get() = "$owner/$name"
    val webUrl: String get() = "https://$host/${fullPath.split('/').joinToString("/") { encodePath(it) }}"

    fun commitUrl(hash: String): String {
        requireCommitHash(hash)
        return "$webUrl/${if (provider == HostingProvider.GITHUB) "commit" else "-/commit"}/$hash"
    }

    fun reviewUrl(number: Long): String {
        require(number > 0) { "Review number must be greater than zero." }
        return "$webUrl/${if (provider == HostingProvider.GITHUB) "pull" else "-/merge_requests"}/$number"
    }
}

internal fun requireCommitHash(hash: String) {
    require(Regex("(?:[0-9a-fA-F]{40}|[0-9a-fA-F]{64})").matches(hash)) { "A full commit hash is required." }
}

data class ReviewRequest(
    val number: Long,
    val title: String,
    val author: String,
    val sourceBranch: String,
    val targetBranch: String,
    val url: String,
    val draft: Boolean,
    val updatedAt: Instant,
    val description: String = "",
)

enum class ReviewState { OPEN, CLOSED, ALL }

data class ReviewPage(val items: List<ReviewRequest>, val page: Int, val hasNextPage: Boolean)

enum class HostingErrorKind {
    AUTHENTICATION, FORBIDDEN, NOT_FOUND, RATE_LIMITED, REDIRECT, TIMEOUT, NETWORK, INVALID_RESPONSE, SERVER,
}

class HostingException(
    val kind: HostingErrorKind,
    message: String,
    val statusCode: Int? = null,
) : RuntimeException(message)

internal fun encodePath(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

internal fun isValidPathSegment(value: String): Boolean =
    value.isNotBlank() && value != "." && value != ".." &&
        value.none { it.isISOControl() || it in "/\\?#:" }

internal fun isValidHost(host: String): Boolean = runCatching {
    val uri = URI("https://$host")
    host.isNotBlank() && host.none { it.isWhitespace() || it.isISOControl() } &&
        uri.host != null && uri.rawAuthority == host && uri.rawUserInfo == null &&
        uri.rawPath.isNullOrEmpty() && uri.rawQuery == null && uri.rawFragment == null &&
        (uri.port == -1 || uri.port in 1..65535)
}.getOrDefault(false)
