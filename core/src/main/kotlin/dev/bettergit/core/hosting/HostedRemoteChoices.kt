package dev.bettergit.core.hosting

import dev.bettergit.core.git.GitRemote

data class HostedRemoteChoice(val remoteName: String, val repository: HostedRepository, val confirmProvider: Boolean) {
    override fun toString(): String = "${remoteName.replace(Regex("[\\p{Cntrl}\\p{Cf}]"), " ")} · " +
        "${if (repository.provider == HostingProvider.GITHUB) "GitHub" else "GitLab"} · ${repository.host}/${repository.fullPath}"
}

object HostedRemoteChoices {
    fun from(remotes: List<GitRemote>): List<HostedRemoteChoice> = remotes.sortedBy { if (it.name == "origin") 0 else 1 }
        .flatMap { remote ->
            val detected = RemoteParser.parse(remote.url)
            if (detected != null) listOf(HostedRemoteChoice(remote.name, detected, false))
            else HostingProvider.entries.mapNotNull { hint ->
                RemoteParser.parse(remote.url, hint)?.let { HostedRemoteChoice(remote.name, it, true) }
            }
        }.distinctBy { it.repository }
}
