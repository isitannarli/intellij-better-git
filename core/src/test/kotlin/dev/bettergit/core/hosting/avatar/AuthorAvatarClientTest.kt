package dev.bettergit.core.hosting.avatar

import dev.bettergit.core.hosting.HostedRepository
import dev.bettergit.core.hosting.HostingProvider
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.CancellationException

class AuthorAvatarClientTest {
    private val github = HostedRepository(HostingProvider.GITHUB, "github.com", "team", "private-repo")
    private val gitlab = HostedRepository(HostingProvider.GITLAB, "gitlab.com", "team/platform", "private-repo")
    private val image = byteArrayOf(1, 2, 3)
    private fun json(text: String) = AvatarResponse(200, text.toByteArray())
    private fun picture() = AvatarResponse(200, image, mapOf("Content-Type" to listOf("image/png")))
    private class Recording(vararg responses: AvatarResponse) : AvatarTransport {
        val requests = mutableListOf<AvatarRequest>()
        val responses = ArrayDeque(responses.toList())
        override fun execute(request: AvatarRequest): AvatarResponse { requests += request; return responses.removeFirst() }
    }

    @Test fun `private GitHub repo needs only one public email avatar URL and no commit API or credentials`() {
        val transport = Recording(picture())
        assertArrayEquals(image, AuthorAvatarClient(transport).load(github, "dev+git@example.test"))
        val request = transport.requests.single()
        assertEquals("https://avatars.githubusercontent.com/u/e?email=dev%2Bgit%40example.test&s=72", request.uri.toString())
        assertFalse(request.uri.toString().contains("private-repo"))
        assertFalse(request.headers.keys.any { it.equals("Authorization", true) || it.equals("Private-Token", true) })
        assertFalse(request.toString().contains("dev"))
    }

    @Test fun `noreply numeric ID is a direct image URL without exposing email`() {
        val transport = Recording(picture())
        AuthorAvatarClient(transport).load(github, "12345+developer@users.noreply.github.com")
        assertEquals("https://avatars.githubusercontent.com/u/12345?s=72", transport.requests.single().uri.toString())
    }

    @Test fun `email is trimmed normalized and URL encoded without guessing a username`() {
        val transport = Recording(picture())
        AuthorAvatarClient(transport).load(github, " <Dev+Git@Example.Test> ")
        assertEquals("email=dev%2Bgit%40example.test&s=72", transport.requests.single().uri.rawQuery)
    }

    @Test fun `GitLab uses public avatar metadata and same-origin image without a CLI or token`() {
        val transport = Recording(json("""{"avatar_url":"/uploads/-/system/user/avatar/7/photo.png"}"""), picture())
        assertArrayEquals(image, AuthorAvatarClient(transport).load(gitlab, "dev+git@example.test"))
        assertEquals("https://gitlab.com/api/v4/avatar?email=dev%2Bgit%40example.test&size=72", transport.requests[0].uri.toString())
        assertEquals("https://gitlab.com/uploads/-/system/user/avatar/7/photo.png", transport.requests[1].uri.toString())
        assertTrue(transport.requests.all { "Authorization" !in it.headers && "Private-Token" !in it.headers })
    }

    @Test fun `GitLab restricted visibility does not invoke authentication or retry`() {
        val transport = Recording(AvatarResponse(403, byteArrayOf()))
        assertThrows(AvatarUnavailableException::class.java) { AuthorAvatarClient(transport).load(gitlab, "dev@example.test") }
        assertEquals(1, transport.requests.size)
        assertFalse(transport.requests.single().headers.containsKey("Authorization"))
    }

    @Test fun `GitLab server supplied Gravatar CDN image is allowed without credentials`() {
        for (host in listOf("secure.gravatar.com", "www.gravatar.com", "0.gravatar.com")) {
            val transport = Recording(json("""{"avatar_url":"https://$host/avatar/hash?s=72"}"""), picture())
            assertArrayEquals(image, AuthorAvatarClient(transport).load(gitlab, "dev@example.test"))
            assertTrue(transport.requests.all { "Authorization" !in it.headers })
        }
    }

    @Test fun `empty malformed or long email never makes a request`() {
        val transport = Recording()
        for (repo in listOf(github, gitlab)) for (email in listOf("", "\nmalformed", "a".repeat(321), "email", "two@@example.test", "dev name@example.test")) {
            assertNull(AuthorAvatarClient(transport).load(repo, email))
        }
        assertTrue(transport.requests.isEmpty())
    }

    @Test fun `avatar URLs reject arbitrary origins HTTP credentials and alternate CDN ports`() {
        for (url in listOf("http://gitlab.com/a", "https://evil.example/photo.png", "https://gitlab.com@evil.example/a",
            "https://user:secret@gitlab.com/a", "https://secure.gravatar.com:8443/a", "file:///tmp/image", "https://gitlab.com/image#fragment")) {
            val transport = Recording(json("""{"avatar_url":"$url"}"""))
            assertNull(AuthorAvatarClient(transport).load(gitlab, "dev@example.test"), url)
            assertEquals(1, transport.requests.size)
        }
    }

    @Test fun `metadata redirect is never followed and server text is not exposed`() {
        val transport = Recording(AvatarResponse(302, "sensitive body".toByteArray(), mapOf("Location" to listOf("https://evil.example"))))
        val failure = assertThrows(AvatarUnavailableException::class.java) { AuthorAvatarClient(transport).load(gitlab, "dev@example.test") }
        assertFalse(failure.toString().contains("sensitive")); assertEquals(1, transport.requests.size)
    }

    @Test fun `image redirect to arbitrary origin is refused`() {
        val transport = Recording(AvatarResponse(302, byteArrayOf(), mapOf("Location" to listOf("https://evil.example/pixel"))))
        assertNull(AuthorAvatarClient(transport).load(github, "dev@example.test"))
        assertEquals(1, transport.requests.size)
    }

    @Test fun `safe image redirects remain anonymous and loops have finite budget`() {
        val redirect = AvatarResponse(302, byteArrayOf(), mapOf("Location" to listOf("https://avatars.githubusercontent.com/u/42")))
        val transport = Recording(redirect, redirect, redirect)
        assertNull(AuthorAvatarClient(transport).load(github, "dev@example.test"))
        assertEquals(3, transport.requests.size)
        assertTrue(transport.requests.all { "Authorization" !in it.headers })
    }

    @Test fun `rate limit is signalled for cross-author backoff`() {
        val transport = Recording(AvatarResponse(429, byteArrayOf()))
        assertTrue(assertThrows(AvatarUnavailableException::class.java) { AuthorAvatarClient(transport).load(github, "dev@example.test") }.rateLimited)
    }

    @Test fun `malformed GitLab metadata and nonimage content keep initials`() {
        for (body in listOf("not JSON", "{}", """{"avatar_url":null}""")) {
            assertNull(AuthorAvatarClient(Recording(json(body))).load(gitlab, "dev@example.test"))
        }
        assertNull(AuthorAvatarClient(Recording(AvatarResponse(200, "html".toByteArray(), mapOf("Content-Type" to listOf("text/html")))))
            .load(github, "dev@example.test"))
    }

    @Test fun `metadata and image byte caps also apply to injected transports`() {
        assertThrows(AvatarUnavailableException::class.java) { AuthorAvatarClient(Recording(AvatarResponse(200, ByteArray(64 * 1024 + 1)))).load(gitlab, "dev@example.test") }
        assertThrows(AvatarUnavailableException::class.java) { AuthorAvatarClient(Recording(AvatarResponse(200, ByteArray(AuthorAvatarClient.MAX_IMAGE_BYTES + 1)))).load(github, "dev@example.test") }
    }

    @Test fun `network failure is redacted and interruption propagates cancellation`() {
        val client = AuthorAvatarClient(AvatarTransport { throw IOException("email and URL details") })
        assertFalse(assertThrows(AvatarUnavailableException::class.java) { client.load(github, "dev@example.test") }.toString().contains("email"))
        try {
            assertThrows(CancellationException::class.java) { AuthorAvatarClient(AvatarTransport { throw InterruptedException() }).load(github, "dev@example.test") }
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
    }

    @Test fun `explicit enterprise source uses public email avatar endpoint on same origin`() {
        val repo = github.copy(host = "code.example:8443")
        val transport = Recording(picture())
        AuthorAvatarClient(transport).load(repo, "dev@example.test")
        assertEquals("https://code.example:8443/api/v3/enterprise/avatars/u/e?email=dev%40example.test&s=72", transport.requests.single().uri.toString())
        assertNull(AuthorAvatarClient.allowedImage(repo, "https://code.example/avatars/42.png"))
    }
}
