package dev.bettergit.core.git

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Path
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal class GitOutput(val stdoutBytes: ByteArray, val stderr: String, val exitCode: Int) {
    val stdout: String get() = stdoutBytes.toString(UTF_8)
    override fun toString() = "GitOutput(${stdoutBytes.size} bytes, exitCode=$exitCode)"
}

internal class GitProcessRunner(private val executable: String) {
    fun run(
        root: Path,
        arguments: List<String>,
        acceptedExitCodes: Set<Int> = setOf(0),
        timeout: Duration = Duration.ofSeconds(30),
        literalPathspecs: Boolean = true,
        enableHooks: Boolean = false,
    ): GitOutput {
        if (Thread.currentThread().isInterrupted) throw CancellationException("Git operation cancelled")
        val operation = arguments.firstOrNull() ?: "command"
        val process = try {
            ProcessBuilder(
                listOf(
                    executable,
                    "--no-pager",
                    "--no-optional-locks",
                ) + (if (literalPathspecs) listOf("--literal-pathspecs") else emptyList()) + listOf(
                    "-c", "color.ui=false",
                    "-c", "core.quotePath=false",
                    "-c", "core.fsmonitor=false",
                    "-c", "core.askPass=",
                    "-c", "diff.suppressBlankEmpty=false",
                    "-c", "protocol.ext.allow=never",
                ) + (if (enableHooks) emptyList() else listOf("-c", "core.hooksPath=$disabledHooksDirectory")) + listOf(
                    "-C", root.toAbsolutePath().normalize().toString(),
                ) + arguments,
            ).apply {
                environment()["GIT_TERMINAL_PROMPT"] = "0"
                environment()["GIT_ASKPASS"] = ""
                environment()["SSH_ASKPASS"] = ""
                environment()["GCM_INTERACTIVE"] = "never"
                environment()["SSH_ASKPASS_REQUIRE"] = "never"
                environment()["LC_ALL"] = "C"
                // GIT_DIFF_OPTS overrides --unified=0 and corrupts snapshot line mapping.
                environment().remove("GIT_DIFF_OPTS")
                if (!literalPathspecs) environment().remove("GIT_LITERAL_PATHSPECS")
            }.start()
        } catch (error: java.io.IOException) {
            throw GitCommandException(operation, null, "Cannot start Git. Check the Git executable and repository directory.", error)
        }

        process.outputStream.close()
        val readers = Executors.newVirtualThreadPerTaskExecutor()
        val stdout = readers.submit<ByteArray> { readBounded(process.inputStream, process, operation) }
        val stderr = readers.submit<ByteArray> { readBounded(process.errorStream, process, operation) }
        val deadline = System.nanoTime() + timeout.toNanos()
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                terminate(process)
                throw GitCommandException(operation, null, "Git $operation timed out after ${timeout.seconds} seconds.")
            }
            // Child helpers can retain pipes after Git exits; collection must share the process deadline.
            val output = GitOutput(
                stdout.get((deadline - System.nanoTime()).coerceAtLeast(1), TimeUnit.NANOSECONDS),
                stderr.get((deadline - System.nanoTime()).coerceAtLeast(1), TimeUnit.NANOSECONDS).toString(UTF_8),
                process.exitValue(),
            )
            if (output.exitCode !in acceptedExitCodes) {
                val detail = redactGitError(output.stderr).ifBlank { "Process exited with code ${output.exitCode}." }
                throw GitCommandException(operation, output.exitCode, "Git $operation failed: $detail")
            }
            return output
        } catch (error: InterruptedException) {
            terminate(process)
            Thread.currentThread().interrupt()
            throw CancellationException("Git operation cancelled").apply { initCause(error) }
        } catch (_: TimeoutException) {
            terminate(process)
            throw GitCommandException(operation, null, "Git $operation timed out after ${timeout.seconds} seconds.")
        } catch (error: ExecutionException) {
            terminate(process)
            val cause = error.cause
            if (cause is GitCommandException) throw cause
            throw GitCommandException(operation, null, "Cannot read Git $operation output.", cause)
        } finally {
            if (process.isAlive) terminate(process)
            stdout.cancel(true)
            stderr.cancel(true)
            readers.shutdownNow()
            process.inputStream.close()
            process.errorStream.close()
        }
    }

    private fun readBounded(input: InputStream, process: Process, operation: String): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count == -1) break
            if (output.size() + count > MAX_OUTPUT_BYTES) {
                terminate(process)
                throw GitCommandException(operation, null, "Git $operation output exceeds the 16 MiB safety limit. Narrow the selection.")
            }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun terminate(process: Process) {
        process.descendants().use { descendants -> descendants.forEach { it.destroyForcibly() } }
        process.destroyForcibly()
    }

    private companion object {
        const val MAX_OUTPUT_BYTES = 16 * 1024 * 1024
        val disabledHooksDirectory: Path by lazy {
            Files.createTempDirectory("better-git-hooks-").also { it.toFile().deleteOnExit() }
        }
    }
}

internal fun redactGitError(message: String): String = message
    .replace(Regex("(?i)((?:https?|ssh|git)://)[^\\s/@]+@"), "$1[redacted]@")
    .replace(Regex("(?i)([?&](?:access_token|private_token|token|password|secret)=)[^\\s&]+"), "$1[redacted]")
    .replace(Regex("(?i)(?:gh[pousr]_[A-Za-z0-9_]+|github_pat_[A-Za-z0-9_]+|glpat-[A-Za-z0-9_-]+)"), "[redacted]")
    .replace(Regex("[\\p{Cntrl}&&[^\\n\\t]]"), "")
    .trim()
    .take(2000)
