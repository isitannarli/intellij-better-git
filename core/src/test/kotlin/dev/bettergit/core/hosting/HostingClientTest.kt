package dev.bettergit.core.hosting

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.http.HttpTimeoutException
import java.time.Instant
import java.util.concurrent.CancellationException

class HostingClientTest {
    private val github = HostedRepository(HostingProvider.GITHUB, "github.com", "team", "repo")
    private val gitlab = HostedRepository(HostingProvider.GITLAB, "gitlab.example:8443", "team/platform", "repo")

    @Test fun `GitHub public requests are unauthenticated and parse nullable draft`() {
        val transport = RecordingTransport(HostingHttpResponse(200, "[$GITHUB_REVIEW]"))
        val review = HostingClient(transport).reviews(github, null).single()
        assertEquals(17L, review.number)
        assertEquals("Grace", review.author)
        assertFalse(review.draft)
        assertEquals("fix/bug", review.sourceBranch)
        assertEquals("main", review.targetBranch)
        assertEquals("", review.description)
        assertEquals(Instant.parse("2026-10-17T13:14:15Z"), review.updatedAt)
        assertEquals("https://github.com/team/repo/pull/17", review.url)
        val request = transport.requests.single()
        assertEquals("https://api.github.com/repos/team/repo/pulls?state=open&sort=updated&direction=desc&per_page=100&page=1", request.uri.toString())
        assertFalse(request.headers.containsKey("Authorization"))
    }

    @Test fun `GitLab encodes the complete nested project path and uses iid`() {
        val transport = RecordingTransport(HostingHttpResponse(200, "[$GITLAB_REVIEW]", mapOf("x-next-page" to listOf("3"))))
        val page = HostingClient(transport).reviewsPage(gitlab, "test-secret", ReviewState.ALL, 2)
        val review = page.items.single()
        assertEquals(29L, review.number)
        assertTrue(review.draft)
        assertEquals("Mina", review.author)
        assertEquals("A useful description", review.description)
        assertTrue(page.hasNextPage)
        assertEquals(2, page.page)
        val request = transport.requests.single()
        assertEquals("/api/v4/projects/team%2Fplatform%2Frepo/merge_requests", request.uri.rawPath)
        assertEquals(8443, request.uri.port)
        assertTrue(request.uri.query.contains("state=all"))
        assertTrue(request.uri.query.contains("page=2"))
        assertEquals("Bearer test-secret", request.headers["Authorization"])
        assertFalse(request.toString().contains("test-secret"))
    }

    @Test fun `supports GitHub enterprise API and unified diff media type`() {
        val transport = RecordingTransport(HostingHttpResponse(200, "diff --git a/x b/x\n+hello"))
        val enterprise = github.copy(host = "code.example:8443")
        assertTrue(HostingClient(transport).diff(enterprise, 7, "test-secret").startsWith("diff --git"))
        assertEquals("https://code.example:8443/api/v3/repos/team/repo/pulls/7", transport.requests.single().uri.toString())
        assertEquals("application/vnd.github.diff", transport.requests.single().headers["Accept"])
        assertEquals("Bearer test-secret", transport.requests.single().headers["Authorization"])
    }

    @Test fun `GitLab raw diff is requested without a token for public repositories`() {
        val transport = RecordingTransport(HostingHttpResponse(200, "diff --git a/x b/x"))
        HostingClient(transport).diff(gitlab, 29, null)
        assertTrue(transport.requests.single().uri.rawPath.endsWith("/merge_requests/29/raw_diffs"))
        assertFalse(transport.requests.single().headers.containsKey("Authorization"))
    }

    @Test fun `exposes next page without trusting or following its URL`() {
        val transport = RecordingTransport(HostingHttpResponse(200, "[$GITHUB_REVIEW]", mapOf(
            "Link" to listOf("<https://attacker.example/steal>; rel=\"next\", <https://api.github.com/last>; rel=\"last\""),
        )))
        assertTrue(HostingClient(transport).reviewsPage(github, "test-secret").hasNextPage)
        assertEquals(1, transport.requests.size)
    }

    @Test fun `explicit final page header takes precedence over full page fallback`() {
        val body = List(100) { GITLAB_REVIEW }.joinToString(",", "[", "]")
        assertFalse(HostingClient(RecordingTransport(HostingHttpResponse(200, body, mapOf("X-Next-Page" to listOf("")))))
            .reviewsPage(gitlab, null).hasNextPage)
        assertTrue(HostingClient(RecordingTransport(HostingHttpResponse(200, body))).reviewsPage(gitlab, null).hasNextPage)
    }

    @Test fun `rejects redirects and never includes server body in errors`() {
        val transport = RecordingTransport(HostingHttpResponse(302, "test-secret", mapOf("Location" to listOf("https://attacker.example"))))
        val error = assertThrows(HostingException::class.java) { HostingClient(transport).reviews(github, "test-secret") }
        assertEquals(HostingErrorKind.REDIRECT, error.kind)
        assertEquals(302, error.statusCode)
        assertFalse(error.toString().contains("test-secret"))
        assertEquals(1, transport.requests.size)
    }

    @Test fun `authentication permissions rate limits and server errors are actionable`() {
        mapOf(401 to HostingErrorKind.AUTHENTICATION, 403 to HostingErrorKind.FORBIDDEN,
            404 to HostingErrorKind.NOT_FOUND, 429 to HostingErrorKind.RATE_LIMITED, 503 to HostingErrorKind.SERVER).forEach { (code, kind) ->
            val error = assertThrows(HostingException::class.java) {
                HostingClient(RecordingTransport(HostingHttpResponse(code, "secret body"))).reviews(github, null)
            }
            assertEquals(kind, error.kind)
            assertFalse(error.message!!.contains("secret body"))
        }
        val error = assertThrows(HostingException::class.java) {
            HostingClient(RecordingTransport(HostingHttpResponse(403, "", mapOf("x-ratelimit-remaining" to listOf("0")))))
                .reviews(github, null)
        }
        assertEquals(HostingErrorKind.RATE_LIMITED, error.kind)
    }

    @Test fun `malformed JSON and timestamps fail with a safe provider error`() {
        listOf("<html>private body</html>", "{}", "[{}]", "[${GITHUB_REVIEW.replace("2026-10-17T13:14:15Z", "secret-invalid-date")}]").forEach { body ->
            val error = assertThrows(HostingException::class.java) {
                HostingClient(RecordingTransport(HostingHttpResponse(200, body))).reviews(github, null)
            }
            assertEquals(HostingErrorKind.INVALID_RESPONSE, error.kind)
            assertNull(error.cause)
        }
    }

    @Test fun `timeouts and network failures hide transport exception data`() {
        listOf(HttpTimeoutException("test-secret") to HostingErrorKind.TIMEOUT, IOException("test-secret") to HostingErrorKind.NETWORK).forEach { (failure, expected) ->
            val error = assertThrows(HostingException::class.java) {
                HostingClient(HostingTransport { throw failure }).reviews(github, null)
            }
            assertEquals(expected, error.kind)
            assertFalse(error.toString().contains("test-secret"))
            assertNull(error.cause)
        }
    }

    @Test fun `interruption remains cancellation rather than a user error`() {
        try {
            assertThrows(CancellationException::class.java) {
                HostingClient(HostingTransport { throw InterruptedException() }).reviews(github, null)
            }
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
    }

    @Test fun `rejects invalid identifiers and header injection before network access`() {
        val transport = RecordingTransport(HostingHttpResponse(200, "[]"))
        val client = HostingClient(transport)
        assertThrows(IllegalArgumentException::class.java) { client.reviews(github, null, page = 0) }
        assertThrows(IllegalArgumentException::class.java) { client.diff(github, -1, null) }
        assertThrows(IllegalArgumentException::class.java) { client.reviews(github, "token\nInjected: value") }
        assertTrue(transport.requests.isEmpty())
    }

    private class RecordingTransport(private val response: HostingHttpResponse) : HostingTransport {
        val requests = mutableListOf<HostingHttpRequest>()
        override fun execute(request: HostingHttpRequest): HostingHttpResponse { requests += request; return response }
    }

    companion object {
        private const val GITHUB_REVIEW = """{"number":17,"title":"Fix the bug","user":{"login":"Grace"},"head":{"ref":"fix/bug"},"base":{"ref":"main"},"draft":null,"updated_at":"2026-10-17T13:14:15Z","body":null}"""
        private const val GITLAB_REVIEW = """{"id":100029,"iid":29,"title":"Draft: Improve API","author":{"username":"Mina"},"source_branch":"feature/api","target_branch":"main","draft":null,"work_in_progress":true,"updated_at":"2026-10-17T13:14:15.000Z","description":"A useful description"}"""
    }
}
