package dev.bettergit.services

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread
import dev.bettergit.core.ai.AiProvider
import java.util.concurrent.CancellationException

object AiCredentials {
    private val resolver = AiCredentialResolver(
        read = { provider -> PasswordSafe.instance.getPassword(attributes(provider)) },
        write = { provider, key ->
            val store = PasswordSafe.instance
            if (key == null) store.set(attributes(provider), null) else store.setPassword(attributes(provider), key)
        },
        environment = System::getenv,
    )

    @RequiresBackgroundThread
    fun resolve(provider: AiProvider, enteredKey: String, remember: Boolean): String? =
        resolver.resolve(provider, enteredKey, remember)

    @RequiresBackgroundThread
    fun forget(provider: AiProvider) = resolver.forget(provider)

    @RequiresBackgroundThread
    fun store(provider: AiProvider, key: String) = resolver.store(provider, key)

    private fun attributes(provider: AiProvider) = CredentialAttributes(generateServiceName("Better Git AI", provider.name))
}

internal class AiCredentialResolver(
    private val read: (AiProvider) -> String?,
    private val write: (AiProvider, String?) -> Unit,
    private val environment: (String) -> String?,
) {
    fun resolve(provider: AiProvider, enteredKey: String, remember: Boolean): String? {
        checkCancellation()
        if (provider == AiProvider.OLLAMA) return null
        val entered = checked(enteredKey)
        if (entered != null) {
            if (remember) vault("Cannot store the API key. Disable Remember key to use it for this request.") { write(provider, entered) }
            return entered
        }
        provider.environmentVariable?.let { name -> checked(environment(name))?.let { return it } }
        val stored = vault("Cannot read the saved API key. Check Better Git settings and the IDE password store.") { read(provider) }
        checkCancellation()
        return checked(stored)
    }

    fun forget(provider: AiProvider) {
        checkCancellation()
        if (provider != AiProvider.OLLAMA) vault("Cannot remove this API key from the IDE password store.") { write(provider, null) }
    }

    fun store(provider: AiProvider, key: String) {
        checkCancellation()
        require(provider != AiProvider.OLLAMA) { "Ollama does not require an API key." }
        val value = requireNotNull(checked(key)) { "Enter an API key to save." }
        vault("Cannot store the API key. Check the IDE password store and apply again.") { write(provider, value) }
    }

    private fun checked(value: String?): String? = value?.trim()?.takeIf(String::isNotEmpty)?.also {
        require(it.length <= 8192 && it.all { char -> char.code in 33..126 }) { "The API key contains invalid characters or is too long." }
    }

    private fun <T> vault(message: String, operation: () -> T): T = try {
        operation()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (cancelled: ProcessCanceledException) {
        throw cancelled
    } catch (_: Exception) {
        throw IllegalStateException(message)
    }

    private fun checkCancellation() {
        if (Thread.currentThread().isInterrupted) throw CancellationException("AI credential lookup cancelled.")
    }
}
