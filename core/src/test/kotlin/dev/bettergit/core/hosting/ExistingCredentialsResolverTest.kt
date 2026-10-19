package dev.bettergit.core.hosting

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.CancellationException

class ExistingCredentialsResolverTest {
    private val github = HostedRepository(HostingProvider.GITHUB, "github.com", "team", "repo")

    @Test fun `uses the existing GitHub CLI active account without login or persistence`() {
        val runner = ScriptedRunner(CredentialCommandResult(0, "existing-secret\n"))
        assertEquals("existing-secret", ExistingCredentialsResolver(runner).token(github))
        assertEquals(listOf("gh", "auth", "token", "--hostname", "github.com"), runner.commands.single().arguments)
        assertFalse(runner.commands.single().toString().contains("existing-secret"))
    }

    @Test fun `GitLab reads the explicitly scoped host configuration including HTTPS port`() {
        val runner = ScriptedRunner(CredentialCommandResult(0, "gitlab-secret"))
        val repository = HostedRepository(HostingProvider.GITLAB, "gitlab.example:8443", "group/nested", "repo")
        assertEquals("gitlab-secret", ExistingCredentialsResolver(runner).token(repository))
        assertEquals(listOf("glab", "config", "get", "token", "--host", "gitlab.example:8443"), runner.commands.single().arguments)
    }

    @Test fun `falls back to existing noninteractive Git helper with exact HTTPS context`() {
        val runner = ScriptedRunner(
            CredentialCommandResult(1, ""), CredentialCommandResult(0, "manager\n"),
            CredentialCommandResult(0, "protocol=https\nhost=github.com\nusername=person\npassword=helper-secret\n\n"),
        )
        assertEquals("helper-secret", ExistingCredentialsResolver(runner).token(github))
        assertEquals(listOf("git", "config", "--get-urlmatch", "credential.helper", "https://github.com/team/repo.git"), runner.commands[1].arguments)
        val fill = runner.commands[2]
        assertTrue(fill.isolatedGitConfiguration)
        assertTrue(fill.arguments.contains("credential.helper="))
        assertTrue(fill.arguments.contains("credential.helper=manager"))
        assertEquals(listOf("credential", "fill"), fill.arguments.takeLast(2))
        assertEquals("protocol=https\nhost=github.com\npath=team/repo.git\n\n", fill.input)
    }

    @Test fun `never executes custom shell helpers or helpers that may open an unlock dialog`() {
        listOf("!some-custom-command", "osxkeychain", "libsecret", "manager; echo secret", "/tmp/credential-helper", "store --file=/tmp/other").forEach { helper ->
            val runner = ScriptedRunner(CredentialCommandResult(1, ""), CredentialCommandResult(0, helper))
            assertNull(ExistingCredentialsResolver(runner).token(github))
            assertEquals(2, runner.commands.size)
        }
    }

    @Test fun `rejects credentials returned for a different origin`() {
        listOf("protocol=https\nhost=other.example\npassword=secret", "protocol=http\nhost=github.com\npassword=secret",
            "password=secret", "protocol=https\nhost=github.com:8443\npassword=secret").forEach { output ->
            val runner = ScriptedRunner(CredentialCommandResult(1, ""), CredentialCommandResult(0, "cache"), CredentialCommandResult(0, output))
            assertNull(ExistingCredentialsResolver(runner).token(github))
        }
    }

    @Test fun `missing tools and missing credentials fall back to public access`() {
        assertNull(ExistingCredentialsResolver(CredentialCommandRunner { throw IOException("private error") }).token(github))
        val runner = ScriptedRunner(CredentialCommandResult(0, "multiple lines\nnot-a-token"), CredentialCommandResult(1, ""))
        assertNull(ExistingCredentialsResolver(runner).token(github))
    }

    @Test fun `credential command results redact secrets from diagnostics`() {
        assertFalse(CredentialCommandResult(0, "secret").toString().contains("secret"))
    }

    @Test fun `interrupted credential lookup preserves cancellation`() {
        try {
            assertThrows(CancellationException::class.java) {
                ExistingCredentialsResolver(CredentialCommandRunner { throw InterruptedException() }).token(github)
            }
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
    }

    private class ScriptedRunner(vararg responses: CredentialCommandResult) : CredentialCommandRunner {
        private val queue = ArrayDeque(responses.toList())
        val commands = mutableListOf<CredentialCommand>()
        override fun execute(command: CredentialCommand): CredentialCommandResult { commands += command; return queue.removeFirst() }
    }
}
