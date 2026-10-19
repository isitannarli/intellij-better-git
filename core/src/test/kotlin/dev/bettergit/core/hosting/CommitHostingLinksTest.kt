package dev.bettergit.core.hosting

import dev.bettergit.core.git.GitRemote
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CommitHostingLinksTest {
    private val hash = "a".repeat(40)
    private val github = HostedRepository(HostingProvider.GITHUB, "github.com", "team", "repo")
    private val gitlab = HostedRepository(HostingProvider.GITLAB, "gitlab.example:8443", "group/subgroup", "repo+tools")

    @Test fun `builds canonical provider commit and review links without credentials`() {
        assertEquals("https://github.com/team/repo/commit/$hash", github.commitUrl(hash))
        assertEquals("https://gitlab.example:8443/group/subgroup/repo%2Btools/-/commit/$hash", gitlab.commitUrl(hash))
        assertEquals("https://github.com/team/repo/pull/17", github.reviewUrl(17))
        assertEquals("https://gitlab.example:8443/group/subgroup/repo%2Btools/-/merge_requests/29", gitlab.reviewUrl(29))
        assertTrue(github.commitUrl("B".repeat(64)).endsWith("B".repeat(64)))
    }

    @Test fun `invalid commit hashes and review numbers cannot alter links or trigger requests`() {
        val transport = Recording("[]")
        val client = HostingClient(transport)
        listOf("abc123", "HEAD", "$hash?token=secret", "../pull/1", "g".repeat(40), "$hash\n").forEach {
            assertThrows(IllegalArgumentException::class.java) { github.commitUrl(it) }
            assertThrows(IllegalArgumentException::class.java) { client.reviewsForCommit(github, it, null) }
        }
        assertThrows(IllegalArgumentException::class.java) { github.reviewUrl(0) }
        assertThrows(IllegalArgumentException::class.java) { client.reviewsForCommit(github, hash, null, 0) }
        assertTrue(transport.requests.isEmpty())
    }

    @Test fun `remote choices deduplicate transport aliases and keep origin first`() {
        val choices = HostedRemoteChoices.from(listOf(
            GitRemote("upstream", "https://github.com/upstream/repo.git"),
            GitRemote("origin", "git@github.com:team/repo.git"),
            GitRemote("duplicate", "https://user:secret@github.com/team/repo.git"),
        ))
        assertEquals(2, choices.size)
        assertEquals("origin", choices.first().remoteName)
        assertFalse(choices.first().confirmProvider)
        assertFalse(choices.joinToString().contains("secret"))
        assertFalse(choices.joinToString().contains("user:"))
    }

    @Test fun `custom hosts always require provider confirmation while local remotes are excluded`() {
        val choices = HostedRemoteChoices.from(listOf(GitRemote("origin", "ssh://git@code.example:2222/team/repo.git")))
        assertEquals(setOf(HostingProvider.GITHUB, HostingProvider.GITLAB), choices.map { it.repository.provider }.toSet())
        assertTrue(choices.all { it.confirmProvider })
        assertTrue(choices.all { it.repository.host == "code.example" })
        assertTrue(HostedRemoteChoices.from(listOf(GitRemote("local", "/tmp/repo"))).isEmpty())
    }

    @Test fun `GitHub commit association uses the exact SHA endpoint with pagination`() {
        val transport = Recording("[$GITHUB]", mapOf("Link" to listOf("<https://attacker.example>; rel=\"next\"")))
        val result = HostingClient(transport).reviewsForCommit(github, hash, null, 2)
        assertEquals("https://api.github.com/repos/team/repo/commits/$hash/pulls?per_page=100&page=2", transport.requests.single().uri.toString())
        assertFalse(transport.requests.single().headers.containsKey("Authorization"))
        assertEquals(2, result.page)
        assertTrue(result.hasNextPage)
        assertEquals(17L, result.items.single().number)
        assertEquals(github.reviewUrl(17), result.items.single().url)
        assertEquals(1, transport.requests.size)
    }

    @Test fun `GitLab association encodes nested projects and includes merged reviews without a state filter`() {
        val transport = Recording("[$GITLAB]", mapOf("X-Next-Page" to listOf("")))
        val result = HostingClient(transport).reviewsForCommit(gitlab, hash, "test-token")
        val request = transport.requests.single()
        assertEquals("/api/v4/projects/group%2Fsubgroup%2Frepo%2Btools/repository/commits/$hash/merge_requests", request.uri.rawPath)
        assertFalse(request.uri.query.contains("state="))
        assertEquals("Bearer test-token", request.headers["Authorization"])
        assertEquals(29L, result.items.single().number)
        assertFalse(result.hasNextPage)
    }

    @Test fun `fork associations use the actual review target on the same hosting origin`() {
        val gh = GITHUB.dropLast(1) + ",\"html_url\":\"https://github.com/upstream/project/pull/17\"}"
        assertEquals("https://github.com/upstream/project/pull/17", HostingClient(Recording("[$gh]")).reviewsForCommit(github, hash, null).items.single().url)
        val gl = GITLAB.dropLast(1) + ",\"web_url\":\"https://gitlab.example:8443/upstream/project/-/merge_requests/29\"}"
        assertEquals("https://gitlab.example:8443/upstream/project/-/merge_requests/29", HostingClient(Recording("[$gl]")).reviewsForCommit(gitlab, hash, null).items.single().url)
    }

    @Test fun `association URLs cannot navigate to another origin or a non review target`() {
        listOf("https://attacker.example/team/repo/pull/17", "javascript:alert(1)", "https://user:secret@github.com/team/repo/pull/17", "https://github.com/team/repo/issues/17", "https://github.com/team/repo/pull/17?token=secret", "https://github.com/team/%2e%2e/pull/17").forEach { url ->
            val response = GITHUB.dropLast(1) + ",\"html_url\":\"$url\"}"
            val error = assertThrows(HostingException::class.java) { HostingClient(Recording("[$response]")).reviewsForCommit(github, hash, null) }
            assertEquals(HostingErrorKind.INVALID_RESPONSE, error.kind)
            assertFalse(error.toString().contains("secret"))
        }
    }

    @Test fun `empty associations remain empty and hosting errors keep existing safe semantics`() {
        assertTrue(HostingClient(Recording("[]")).reviewsForCommit(github, hash, null).items.isEmpty())
        val error = assertThrows(HostingException::class.java) {
            HostingClient(HostingTransport { HostingHttpResponse(404, "private server details") }).reviewsForCommit(github, hash, null)
        }
        assertEquals(HostingErrorKind.NOT_FOUND, error.kind)
        assertFalse(error.toString().contains("private server"))
    }

    private class Recording(private val body: String, private val headers: Map<String,List<String>> = emptyMap()) : HostingTransport {
        val requests = mutableListOf<HostingHttpRequest>()
        override fun execute(request: HostingHttpRequest): HostingHttpResponse { requests += request; return HostingHttpResponse(200,body,headers) }
    }
    companion object {
        private const val GITHUB = """{"number":17,"title":"Merge change","user":{"login":"Developer"},"head":{"ref":"feature"},"base":{"ref":"main"},"updated_at":"2026-10-18T10:00:00Z","state":"closed","merged_at":"2026-10-18T10:00:00Z"}"""
        private const val GITLAB = """{"id":100029,"iid":29,"title":"Merge change","author":{"username":"Developer"},"source_branch":"feature","target_branch":"main","updated_at":"2026-10-18T10:00:00Z","state":"merged"}"""
    }
}
