package dev.bettergit.core.hosting

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit

class ExistingCredentialsResolver(
    private val runner: CredentialCommandRunner = LocalCredentialCommandRunner(),
    private val timeout: Duration = Duration.ofSeconds(4),
) {
    init {
        require(!timeout.isZero && !timeout.isNegative) { "Credential lookup timeout must be positive." }
    }

    fun token(repository: HostedRepository): String? {
        checkCancellation()
        val cliCommand = when (repository.provider) {
            HostingProvider.GITHUB -> listOf("gh", "auth", "token", "--hostname", repository.host)
            HostingProvider.GITLAB -> listOf("glab", "config", "get", "token", "--host", repository.host)
        }
        run(cliCommand)?.takeIf { it.exitCode == 0 }?.output?.let(::validToken)?.let { return it }

        // Ignore repository credential helpers: opening a review must not execute repository-controlled commands.
        val configured = run(listOf("git", "config", "--get-urlmatch", "credential.helper", "${repository.webUrl}.git"))
            ?.takeIf { it.exitCode == 0 }?.output ?: return null
        val helpers = configured.lineSequence().map(String::trim).filter { it in NONINTERACTIVE_HELPERS }.distinct().toList()
        if (helpers.isEmpty()) return null
        val command = mutableListOf("git", "-c", "credential.helper=", "-c", "core.askPass=", "-c", "credential.interactive=false")
        helpers.forEach { command += listOf("-c", "credential.helper=$it") }
        command += listOf("credential", "fill")
        val input = "protocol=https\nhost=${repository.host}\npath=${repository.fullPath}.git\n\n"
        val result = run(command, input, isolatedGitConfiguration = true)?.takeIf { it.exitCode == 0 } ?: return null
        val fields = result.output.lineSequence().filter { '=' in it }
            .associate { it.substringBefore('=') to it.substringAfter('=') }
        // Reject credentials returned for another origin.
        if (fields["protocol"] != "https" || !repository.host.equals(fields["host"], ignoreCase = true)) return null
        return fields["password"]?.let(::validToken)
    }

    private fun run(arguments: List<String>, input: String = "", isolatedGitConfiguration: Boolean = false): CredentialCommandResult? {
        checkCancellation()
        return try {
            runner.execute(CredentialCommand(arguments, input, timeout, isolatedGitConfiguration))
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw CancellationException("Credential lookup cancelled.")
        } catch (_: IOException) {
            null 
        }
    }

    private fun validToken(value: String): String? = value.trim().takeIf { token ->
        token.isNotEmpty() && token.length <= MAX_CREDENTIAL_OUTPUT && token.none { it.isWhitespace() || it.isISOControl() }
    }

    private fun checkCancellation() {
        if (Thread.currentThread().isInterrupted) throw CancellationException("Credential lookup cancelled.")
    }

    companion object {
        // Exclude helpers that may open unlock/login dialogs; GCM supports noninteractive access.
        private val NONINTERACTIVE_HELPERS = setOf("cache", "store", "manager", "manager-core", "wincred")
        internal const val MAX_CREDENTIAL_OUTPUT = 64 * 1024
    }
}

fun interface CredentialCommandRunner {
    @Throws(IOException::class, InterruptedException::class)
    fun execute(command: CredentialCommand): CredentialCommandResult
}

class CredentialCommand(
    val arguments: List<String>,
    val input: String,
    val timeout: Duration,
    val isolatedGitConfiguration: Boolean = false,
) {
    override fun toString(): String = "CredentialCommand(tool=${arguments.firstOrNull()}, input=[redacted])"
}

class CredentialCommandResult(val exitCode: Int, val output: String) {
    override fun toString(): String = "CredentialCommandResult(exitCode=$exitCode, output=[redacted])"
}

class LocalCredentialCommandRunner(
    private val environment: () -> Map<String, String> = { System.getenv() },
) : CredentialCommandRunner {
    override fun execute(command: CredentialCommand): CredentialCommandResult {
        val directory = Files.createTempDirectory("better-git-auth-")
        var process: Process? = null
        try {
            val inherited = environment()
            val executable = credentialExecutable(command.arguments.first(), inherited)
                ?: throw IOException("The existing credential tool is not available in the configured environment.")
            // ProcessBuilder searches the parent PATH; resolve against the IDE's loaded shell PATH explicitly.
            val builder = ProcessBuilder(listOf(executable.toString()) + command.arguments.drop(1)).directory(directory.toFile())
                .redirectError(ProcessBuilder.Redirect.DISCARD)
            val env = builder.environment()
            env.clear()
            env.putAll(inherited)
            // Remove inherited Git config injections and secret-bearing traces.
            env.keys.filter { key ->
                key.startsWith("GIT_TRACE") || key.startsWith("GIT_CONFIG_") || key in setOf(
                    "GIT_DIR", "GIT_WORK_TREE", "GIT_COMMON_DIR", "GH_DEBUG", "DEBUG", "GLAB_DEBUG",
                    "GCM_TRACE", "GCM_TRACE_SECRETS", "GCM_TRACE_MSAUTH", "GCM_DEBUG",
                )
            }.toList().forEach(env::remove)
            env.putAll(mapOf(
                "GIT_TERMINAL_PROMPT" to "0", "GIT_ASKPASS" to "", "SSH_ASKPASS" to "",
                "GCM_INTERACTIVE" to "Never", "GCM_GUI_PROMPT" to "0", "GCM_TRACE" to "0",
                "GCM_TRACE_SECRETS" to "0", "GCM_AUTODETECT_TIMEOUT" to "-1",
                "GH_PROMPT_DISABLED" to "1", "GH_NO_UPDATE_NOTIFIER" to "1", "GLAB_CHECK_UPDATE" to "false",
                "GIT_CEILING_DIRECTORIES" to directory.parent.toString(),
            ))
            if (command.isolatedGitConfiguration) {
                val emptyConfig = directory.resolve("empty-config")
                Files.createFile(emptyConfig)
                env["GIT_CONFIG_GLOBAL"] = emptyConfig.toString()
                env["GIT_CONFIG_SYSTEM"] = emptyConfig.toString()
            }
            process = builder.start()
            process.outputStream.use { it.write(command.input.toByteArray(Charsets.UTF_8)) }
            val running = process
            var output = ""
            var tooLarge = false
            val reader = Thread.ofVirtual().start {
                try {
                    val bytes = running.inputStream.readNBytes(ExistingCredentialsResolver.MAX_CREDENTIAL_OUTPUT + 1)
                    tooLarge = bytes.size > ExistingCredentialsResolver.MAX_CREDENTIAL_OUTPUT
                    if (!tooLarge) output = bytes.toString(Charsets.UTF_8)
                    else running.destroyForcibly()
                } catch (_: IOException) {
                }
            }
            if (!process.waitFor(command.timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                stop(process)
                reader.join(500)
                return CredentialCommandResult(-1, "")
            }
            reader.join(500)
            if (reader.isAlive || tooLarge) return CredentialCommandResult(-1, "")
            return CredentialCommandResult(process.exitValue(), output)
        } finally {
            process?.let(::stop)
            Files.deleteIfExists(directory.resolve("empty-config"))
            Files.deleteIfExists(directory)
        }
    }

    private fun stop(process: Process) {
        process.descendants().forEach { it.destroyForcibly() }
        if (process.isAlive) process.destroyForcibly()
    }
}

/** Search only absolute PATH entries, never the repository or implicit current directory. */
internal fun credentialExecutable(
    name: String,
    environment: Map<String, String>,
    windows: Boolean = System.getProperty("os.name").startsWith("Windows", true),
): Path? {
    if (name !in setOf("git", "gh", "glab")) return null
    val path = environment["PATH"] ?: environment.entries.firstOrNull { it.key.equals("PATH", true) }?.value ?: return null
    val separator = if (windows) ';' else ':'
    val filename = if (windows) "$name.exe" else name
    return path.split(separator).asSequence().filter(String::isNotBlank).mapNotNull { entry ->
        runCatching { Path.of(entry).takeIf { it.isAbsolute }?.resolve(filename) }.getOrNull()
    }.firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }
}
