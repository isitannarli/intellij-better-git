package dev.bettergit.core.hosting

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import java.net.URI
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CancellationException

class HostingClient(
    private val transport: HostingTransport = JdkHostingTransport(),
    private val requestTimeout: Duration = Duration.ofSeconds(30),
) {
    init {
        require(!requestTimeout.isNegative && !requestTimeout.isZero) { "Request timeout must be positive." }
    }

    fun reviews(
        repository: HostedRepository,
        token: String?,
        state: ReviewState = ReviewState.OPEN,
        page: Int = 1,
    ): List<ReviewRequest> = reviewsPage(repository, token, state, page).items

    fun reviewsPage(
        repository: HostedRepository,
        token: String?,
        state: ReviewState = ReviewState.OPEN,
        page: Int = 1,
    ): ReviewPage {
        require(page > 0) { "Page must be greater than zero." }
        val query = when (repository.provider) {
            HostingProvider.GITHUB -> {
                val remoteState = when (state) {
                    ReviewState.OPEN -> "open"
                    ReviewState.CLOSED -> "closed"
                    ReviewState.ALL -> "all"
                }
                "state=$remoteState&sort=updated&direction=desc&per_page=$PAGE_SIZE&page=$page"
            }
            HostingProvider.GITLAB -> {
                val remoteState = when (state) {
                    ReviewState.OPEN -> "opened"
                    ReviewState.CLOSED -> "closed"
                    ReviewState.ALL -> "all"
                }
                "state=$remoteState&scope=all&order_by=updated_at&sort=desc&per_page=$PAGE_SIZE&page=$page"
            }
        }
        val response = get(repository, "${reviewsEndpoint(repository)}?$query", token)
        val items = parseResponse {
            JsonParser.parseString(response.body).asJsonArray.map { element ->
                val item = element.asJsonObject
                when (repository.provider) {
                    HostingProvider.GITHUB -> parseGitHubReview(repository, item)
                    HostingProvider.GITLAB -> parseGitLabReview(repository, item)
                }
            }
        }
        return ReviewPage(items, page, hasNextPage(response, page, items.size))
    }

    fun reviewsForCommit(repository: HostedRepository, hash: String, token: String?, page: Int = 1): ReviewPage {
        requireCommitHash(hash)
        require(page > 0) { "Page must be greater than zero." }
        val endpoint = when (repository.provider) {
            HostingProvider.GITHUB -> "/repos/${encodePath(repository.owner)}/${encodePath(repository.name)}/commits/$hash/pulls"
            HostingProvider.GITLAB -> "/projects/${encodePath(repository.fullPath)}/repository/commits/$hash/merge_requests"
        }
        val response = get(repository, "$endpoint?per_page=$PAGE_SIZE&page=$page", token)
        val items = parseResponse {
            JsonParser.parseString(response.body).asJsonArray.map { element ->
                when (repository.provider) {
                    HostingProvider.GITHUB -> parseGitHubReview(repository, element.asJsonObject, associated = true)
                    HostingProvider.GITLAB -> parseGitLabReview(repository, element.asJsonObject, associated = true)
                }
            }
        }
        return ReviewPage(items, page, hasNextPage(response, page, items.size))
    }

    private fun hasNextPage(response: HostingHttpResponse, page: Int, count: Int): Boolean {
        val nextPage = response.header("X-Next-Page")
        val links = response.header("Link")
        return when {
            nextPage != null -> nextPage.toIntOrNull()?.let { it > page } == true
            links != null -> Regex(";\\s*rel\\s*=\\s*\"?next(?:\"|[,;\\s]|$)", RegexOption.IGNORE_CASE).containsMatchIn(links)
            else -> count == PAGE_SIZE 
        }
    }

    fun diff(repository: HostedRepository, number: Long, token: String?): String {
        require(number > 0) { "Review number must be greater than zero." }
        val endpoint = "${reviewsEndpoint(repository)}/$number" +
            if (repository.provider == HostingProvider.GITLAB) "/raw_diffs" else ""
        val accept = if (repository.provider == HostingProvider.GITHUB) "application/vnd.github.diff" else "text/plain"
        return get(repository, endpoint, token, accept).body
    }

    private fun parseGitHubReview(repository: HostedRepository, item: JsonObject, associated: Boolean = false): ReviewRequest {
        val number = item.required("number").asLong.also { require(it > 0) }
        return ReviewRequest(
            number = number,
            title = item.required("title").asString,
            author = item.objectOrNull("user")?.stringOrNull("login") ?: "ghost",
            sourceBranch = item.required("head").asJsonObject.required("ref").asString,
            targetBranch = item.required("base").asJsonObject.required("ref").asString,
            url = if (associated) associatedReviewUrl(repository, item.stringOrNull("html_url"), number) else repository.reviewUrl(number),
            draft = item.booleanOrFalse("draft"),
            updatedAt = Instant.parse(item.required("updated_at").asString),
            description = item.stringOrNull("body").orEmpty(),
        )
    }

    private fun parseGitLabReview(repository: HostedRepository, item: JsonObject, associated: Boolean = false): ReviewRequest {
        val number = item.required("iid").asLong.also { require(it > 0) }
        return ReviewRequest(
            number = number,
            title = item.required("title").asString,
            author = item.objectOrNull("author")?.stringOrNull("username") ?: "ghost",
            sourceBranch = item.required("source_branch").asString,
            targetBranch = item.required("target_branch").asString,
            url = if (associated) associatedReviewUrl(repository, item.stringOrNull("web_url"), number) else repository.reviewUrl(number),
            draft = item.booleanOrFalse("draft") || item.booleanOrFalse("work_in_progress"),
            updatedAt = Instant.parse(item.required("updated_at").asString),
            description = item.stringOrNull("description").orEmpty(),
        )
    }

    private fun associatedReviewUrl(repository: HostedRepository, url: String?, number: Long): String {
        if (url == null) return repository.reviewUrl(number)
        val uri = URI(url)
        val origin = URI(repository.webUrl)
        require(uri.scheme == "https" && uri.host.equals(origin.host, ignoreCase = true) &&
            (if (uri.port == -1) 443 else uri.port) == (if (origin.port == -1) 443 else origin.port) &&
            uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null)
        val suffix = if (repository.provider == HostingProvider.GITHUB) "/pull/$number" else "/-/merge_requests/$number"
        require(uri.rawPath.endsWith(suffix))
        val target = RemoteParser.parse("https://${repository.host}${uri.rawPath.removeSuffix(suffix)}", repository.provider)
        require(target != null)
        return target.reviewUrl(number)
    }

    private fun reviewsEndpoint(repository: HostedRepository): String = when (repository.provider) {
        HostingProvider.GITHUB -> "/repos/${encodePath(repository.owner)}/${encodePath(repository.name)}/pulls"
        HostingProvider.GITLAB -> "/projects/${encodePath(repository.fullPath)}/merge_requests"
    }

    private fun apiBase(repository: HostedRepository): String = when (repository.provider) {
        HostingProvider.GITHUB -> if (repository.host.equals("github.com", ignoreCase = true) ||
            repository.host.equals("github.com:443", ignoreCase = true)) {
            "https://api.github.com"
        } else {
            "https://${repository.host}/api/v3"
        }
        HostingProvider.GITLAB -> "https://${repository.host}/api/v4"
    }

    private fun get(
        repository: HostedRepository,
        endpoint: String,
        token: String?,
        accept: String = "application/json",
    ): HostingHttpResponse {
        checkCancellation()
        require(token == null || token.none { it.isISOControl() }) { "The access token contains invalid characters." }
        val headers = mutableMapOf("Accept" to accept, "User-Agent" to "BetterGit-JetBrains")
        if (repository.provider == HostingProvider.GITHUB) {
            headers["X-GitHub-Api-Version"] = "2022-11-28"
            if (!token.isNullOrBlank()) headers["Authorization"] = "Bearer $token"
        } else if (!token.isNullOrBlank()) {
            headers["Authorization"] = "Bearer $token"
        }
        val response = try {
            transport.execute(HostingHttpRequest(URI(apiBase(repository) + endpoint), headers.toMap(), requestTimeout))
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw CancellationException("Hosting request cancelled.")
        } catch (_: HttpTimeoutException) {
            throw HostingException(HostingErrorKind.TIMEOUT, "The hosting service did not respond in time. Try again.")
        } catch (_: IOException) {
            throw HostingException(HostingErrorKind.NETWORK, "Cannot reach the hosting service. Check your connection, proxy and HTTPS certificate.")
        }
        checkCancellation()
        if (response.statusCode !in 200..299) throw responseError(response)
        return response
    }

    private fun responseError(response: HostingHttpResponse): HostingException {
        val status = response.statusCode
        val (kind, message) = when {
            status in 300..399 -> HostingErrorKind.REDIRECT to
                "The hosting service redirected this request. Check the repository host; credentials were not forwarded."
            status == 401 -> HostingErrorKind.AUTHENTICATION to
                "The hosting service could not authenticate the existing credentials. Check access through your existing Git tools."
            status == 429 || (status == 403 &&
                (response.header("X-RateLimit-Remaining") == "0" || response.header("Retry-After") != null)) ->
                HostingErrorKind.RATE_LIMITED to "The hosting service rate limit was reached. Wait before refreshing."
            status == 403 -> HostingErrorKind.FORBIDDEN to
                "Access was denied. Check this token's repository permissions and organization SSO authorization."
            status == 404 -> HostingErrorKind.NOT_FOUND to
                "The repository or review was not found. Check the remote URL and token access to private repositories."
            status in 500..599 -> HostingErrorKind.SERVER to
                "The hosting service is temporarily unavailable. Try again later."
            else -> HostingErrorKind.INVALID_RESPONSE to
                "The hosting service rejected the request (HTTP $status). Check the repository and server API compatibility."
        }
        return HostingException(kind, message, status)
    }

    private inline fun <T> parseResponse(block: () -> T): T = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: RuntimeException) {
        throw HostingException(HostingErrorKind.INVALID_RESPONSE, "The hosting service returned an unsupported response. Check server API compatibility.")
    }

    private fun checkCancellation() {
        if (Thread.currentThread().isInterrupted) throw CancellationException("Hosting request cancelled.")
    }

    companion object {
        const val PAGE_SIZE: Int = 100
    }
}

private fun JsonObject.required(name: String): JsonElement =
    get(name)?.takeUnless { it.isJsonNull } ?: error("Missing required API field.")

private fun JsonObject.objectOrNull(name: String): JsonObject? =
    get(name)?.takeUnless { it.isJsonNull }?.asJsonObject

private fun JsonObject.stringOrNull(name: String): String? =
    get(name)?.takeUnless { it.isJsonNull }?.asString

private fun JsonObject.booleanOrFalse(name: String): Boolean =
    get(name)?.takeUnless { it.isJsonNull }?.asBoolean ?: false
