package dev.bettergit.core.hosting

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

class CredentialEnvironmentTest {
    @TempDir lateinit var directory: Path

    private fun executable(name: String, content: String = "#!/bin/sh\nexit 0\n"): Path {
        val file = directory.resolve(name)
        Files.writeString(file, content)
        check(file.toFile().setExecutable(true))
        return file
    }

    @Test fun `existing CLI is resolved from supplied shell PATH and inherits sanitized noninteractive environment`() {
        assumeFalse(System.getProperty("os.name").startsWith("Windows", true))
        executable("gh", """
            #!/bin/sh
            [ "${'$'}BETTER_GIT_TEST_MARKER" = "loaded-shell" ] || exit 11
            [ -z "${'$'}GIT_DIR" ] || exit 12
            [ -z "${'$'}GH_DEBUG" ] || exit 13
            [ "${'$'}GH_PROMPT_DISABLED" = "1" ] || exit 14
            [ "${'$'}GIT_TERMINAL_PROMPT" = "0" ] || exit 15
            [ "${'$'}1" = "auth" ] && [ "${'$'}2" = "token" ] || exit 16
            printf 'fixture-token\n'
        """.trimIndent())
        val runner = LocalCredentialCommandRunner { mapOf("PATH" to directory.toString(), "BETTER_GIT_TEST_MARKER" to "loaded-shell", "GIT_DIR" to "/must-be-removed", "GH_DEBUG" to "api") }
        assertEquals("fixture-token", ExistingCredentialsResolver(runner).token(HostedRepository(HostingProvider.GITHUB, "github.com", "team", "repo")))
    }

    @Test fun `supplied environment is read only when a command runs`() {
        assumeFalse(System.getProperty("os.name").startsWith("Windows", true))
        var reads = 0
        executable("gh")
        val runner = LocalCredentialCommandRunner { reads++; mapOf("PATH" to directory.toString()) }
        assertEquals(0, reads)
        assertEquals(0, runner.execute(CredentialCommand(listOf("gh"), "", Duration.ofSeconds(2))).exitCode)
        assertEquals(1, reads)
    }

    @Test fun `lookup respects PATH order and skips relative and empty entries`() {
        assumeFalse(System.getProperty("os.name").startsWith("Windows", true))
        val file = executable("gh")
        val later = Files.createDirectory(directory.resolve("later"))
        Files.copy(file, later.resolve("gh")); later.resolve("gh").toFile().setExecutable(true)
        assertEquals(file, credentialExecutable("gh", mapOf("PATH" to ":.:relative:${directory}:${later}"), false))
        assertNull(credentialExecutable("gh", mapOf("PATH" to ":.:relative"), false))
    }

    @Test fun `lookup requires an executable file and never a directory`() {
        assumeFalse(System.getProperty("os.name").startsWith("Windows", true))
        Files.createDirectory(directory.resolve("gh"))
        assertNull(credentialExecutable("gh", mapOf("PATH" to directory.toString()), false))
        Files.writeString(directory.resolve("glab"), "not executable")
        directory.resolve("glab").toFile().setExecutable(false)
        assertNull(credentialExecutable("glab", mapOf("PATH" to directory.toString()), false))
    }

    @Test fun `Windows lookup accepts Path casing and resolves exe without shell scripts`() {
        val file = executable("gh.exe")
        assertEquals(file, credentialExecutable("gh", mapOf("Path" to ";${directory};"), true))
        assertNull(credentialExecutable("glab", mapOf("Path" to directory.toString()), true))
    }

    @Test fun `unknown commands path injection and missing PATH are refused`() {
        executable("gh")
        for (name in listOf("../gh", "/bin/sh", "gh --help", "other-tool")) {
            assertNull(credentialExecutable(name, mapOf("PATH" to directory.toString()), false))
        }
        assertNull(credentialExecutable("gh", emptyMap(), false))
        assertThrows(IOException::class.java) {
            LocalCredentialCommandRunner { emptyMap() }.execute(CredentialCommand(listOf("gh"), "", Duration.ofSeconds(1)))
        }
    }
}
