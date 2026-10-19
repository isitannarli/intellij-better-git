package dev.bettergit.core.hosting

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RemoteParserTest {
    @Test fun `parses HTTPS and scp GitHub without leaking user info`() {
        val expected = HostedRepository(HostingProvider.GITHUB, "github.com", "octocat", "hello-world")
        assertEquals(expected, RemoteParser.parse("git@github.com:octocat/hello-world.git"))
        assertEquals(expected, RemoteParser.parse("https://user:secret@GITHUB.COM/octocat/hello-world.git"))
        assertEquals(expected, RemoteParser.parse("https://github.com:443/octocat/hello-world.git"))
        assertEquals("https://github.com/octocat/hello-world", expected.webUrl)
    }

    @Test fun `keeps nested GitLab groups and HTTPS ports but not SSH ports`() {
        assertEquals("engineering/platform", RemoteParser.parse("git@gitlab.com:engineering/platform/service.git")?.owner)
        val web = RemoteParser.parse("https://code.example:8443/team/subgroup/project.git", HostingProvider.GITLAB)!!
        assertEquals("code.example:8443", web.host)
        assertEquals("team/subgroup/project", web.fullPath)
        assertEquals("code.example", RemoteParser.parse("ssh://git@code.example:2222/team/project.git", HostingProvider.GITLAB)?.host)
        assertEquals("https://code.example:8443/team/subgroup/project", web.webUrl)
    }

    @Test fun `requires explicit provider for custom hosts and rejects misleading public hosts`() {
        assertNull(RemoteParser.parse("git@github.company.test:team/repo.git"))
        assertNull(RemoteParser.parse("https://github.com.evil.test/team/repo"))
        assertNull(RemoteParser.parse("https://github.com/team/repo", HostingProvider.GITLAB))
        assertEquals(HostingProvider.GITHUB, RemoteParser.parse("https://code.example/team/repo", HostingProvider.GITHUB)?.provider)
    }

    @Test fun `rejects malformed paths ports and unsupported remotes`() {
        listOf(
            "", "/home/user/repo", "file:///tmp/repo", "https://github.com/repo", "https://github.com/a/b/c",
            "https://gitlab.com/team/../repo", "https://gitlab.com/team/%2e%2e/repo", "https://gitlab.com/team/%2Frepo",
            "https://gitlab.com/team/repo?token=secret", "https://gitlab.com/team/repo#main", "https://gitlab.com:99999/team/repo",
            "https://gitlab.com/team//repo", "https://gitlab.com/team/.git", "git@github.com:team/repo\n",
        ).forEach { assertNull(RemoteParser.parse(it), it) }
    }

    @Test fun `does not treat literal plus as a form encoded space`() {
        val repo = RemoteParser.parse("https://gitlab.com/team/repo+tools.git")!!
        assertEquals("repo+tools", repo.name)
        assertEquals("https://gitlab.com/team/repo%2Btools", repo.webUrl)
    }

    @Test fun `direct repository construction cannot inject authority or path`() {
        listOf("evil.test@github.com", "github.com/path", "github.com?query", "github.com:0", "github.com\n").forEach {
            assertThrows(IllegalArgumentException::class.java) { HostedRepository(HostingProvider.GITHUB, it, "team", "repo") }
        }
        assertThrows(IllegalArgumentException::class.java) { HostedRepository(HostingProvider.GITLAB, "gitlab.com", "team/..", "repo") }
    }
}
