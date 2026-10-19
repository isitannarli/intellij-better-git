package dev.bettergit.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import dev.bettergit.core.git.GitCommit
import dev.bettergit.core.hosting.HostedRemoteChoices
import dev.bettergit.core.hosting.HostedRepository
import dev.bettergit.core.hosting.avatar.AuthorAvatarClient
import dev.bettergit.core.hosting.avatar.AvatarUnavailableException
import dev.bettergit.settings.BetterGitSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import java.awt.image.BufferedImage
import java.nio.file.Path
import java.time.Duration
import java.util.Locale

/** Cancelling one waiter must not cancel a shared avatar request. */
@Service(Service.Level.PROJECT)
class AuthorAvatarService(private val project: Project, private val scope: CoroutineScope) : Disposable {
    private data class Key(val root: Path, val author: String)
    private data class Entry(val image: BufferedImage?, val expires: Long)
    private data class RemoteEntry(val repository: HostedRepository?, val expires: Long)
    private val lock = Any()
    private val cache = LinkedHashMap<Key, Entry>(128, 0.75f, true)
    private val pending = mutableMapOf<Key, Deferred<BufferedImage?>>()
    private val remotes = LinkedHashMap<Path, RemoteEntry>(32, 0.75f, true)
    private val cooldown = LinkedHashMap<String, Long>(32, 0.75f, true)
    private val dispatcher = Dispatchers.IO.limitedParallelism(2)
    private val clientDelegate = lazy { AuthorAvatarClient() }
    private val client by clientDelegate
    @Volatile private var disposed = false

    suspend fun avatar(root: Path, commit: GitCommit): BufferedImage? {
        if (disposed || project.isDisposed || !BetterGitSettings.getInstance().state.authorAvatars) return null
        val author = commit.email.trim().takeIf { it.length in 1..320 }?.lowercase(Locale.ROOT) ?: commit.hash
        val key = Key(root.toAbsolutePath().normalize(), author)
        val request = synchronized(lock) {
            cache[key]?.let { entry ->
                if (System.nanoTime() < entry.expires) return entry.image
                cache.remove(key)
            }
            if (disposed) return null
            pending[key] ?: run {
                if (pending.size >= 16) return null
                scope.async(dispatcher, start = CoroutineStart.LAZY) {
                    try {
                        val image = try { withTimeoutOrNull(12_000) { runInterruptible { fetch(key.root, commit) } } }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { null } 
                        synchronized(lock) {
                            if (!disposed) {
                                cache[key] = Entry(image, System.nanoTime() + Duration.ofMinutes(if (image == null) 2 else 10).toNanos())
                                trim(cache, 128)
                            }
                        }
                        image
                    } finally { synchronized(lock) { pending.remove(key) } }
                }.also { pending[key] = it }
            }
        }
        request.start()
        return request.await()
    }

    private fun fetch(root: Path, commit: GitCommit): BufferedImage? {
        if (disposed || !BetterGitSettings.getInstance().state.authorAvatars) return null
        val cachedRemote = synchronized(lock) { remotes[root]?.takeIf { System.nanoTime() < it.expires } }
        val repository = (if (cachedRemote != null) cachedRemote.repository else run {
            val selected = HostedRemoteChoices.from(WorkbenchService.getInstance(project).git.remotes(root))
                .firstOrNull { !it.confirmProvider }?.repository
            synchronized(lock) {
                remotes[root] = RemoteEntry(selected, System.nanoTime() + Duration.ofMinutes(1).toNanos())
                trim(remotes, 32)
            }
            selected
        }) ?: return null
        if (synchronized(lock) { cooldown[repository.host]?.let { System.nanoTime() < it } == true }) return null
        val providerClient = client
        if (disposed) { providerClient.close(); return null }
        val bytes = try {
            providerClient.load(repository, commit.email)
        } catch (unavailable: AvatarUnavailableException) {
            if (unavailable.rateLimited) synchronized(lock) {
                cooldown[repository.host] = System.nanoTime() + Duration.ofMinutes(2).toNanos()
                trim(cooldown, 32)
            }
            return null
        } ?: return null
        return AvatarImages.decode(bytes)
    }

    override fun dispose() {
        disposed = true
        synchronized(lock) {
            pending.values.toList().forEach { it.cancel() }
            pending.clear(); cache.clear(); remotes.clear(); cooldown.clear()
        }
        if (clientDelegate.isInitialized()) client.close()
    }

    private fun <K, V> trim(map: LinkedHashMap<K, V>, limit: Int) {
        while (map.size > limit) map.remove(map.keys.first())
    }
}
