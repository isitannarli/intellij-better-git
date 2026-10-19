package dev.bettergit.core.hosting

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

object RemoteParser {
    /** Unknown hosts need an explicit provider hint; SSH ports are not HTTPS API ports. */
    fun parse(url: String, providerHint: HostingProvider? = null): HostedRepository? = runCatching {
        if (url.any(Char::isISOControl)) return null
        val remote = url.trim()
        val uri = if ("://" in remote) {
            URI(remote)
        } else {
            val scp = Regex("^(?:[^@/:\\s]+@)?(\\[[^]]+]|[^/:\\s]+):(.+)$").matchEntire(remote) ?: return null
            URI("ssh://${scp.groupValues[1]}/${scp.groupValues[2]}")
        }
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (scheme !in setOf("https", "http", "ssh", "git+ssh", "git")) return null
        if (uri.rawQuery != null || uri.rawFragment != null || uri.host == null) return null
        if (uri.port != -1 && uri.port !in 1..65535) return null
        val hostname = uri.host.lowercase(Locale.ROOT)
        val provider = when (hostname) {
            "github.com" -> HostingProvider.GITHUB
            "gitlab.com" -> HostingProvider.GITLAB
            else -> providerHint ?: return null
        }
        if (providerHint != null && provider != providerHint) return null
        val defaultPort = (scheme == "https" && uri.port == 443) || (scheme == "http" && uri.port == 80)
        val port = if (scheme in setOf("https", "http") && uri.port != -1 && !defaultPort) ":${uri.port}" else ""
        val host = "$hostname$port"
        val rawSegments = uri.rawPath.orEmpty().trim('/').split('/')
        if (rawSegments.size < 2) return null
        val segments = rawSegments.map {
            URLDecoder.decode(it.replace("+", "%2B"), StandardCharsets.UTF_8)
        }.toMutableList()
        segments[segments.lastIndex] = segments.last().removeSuffix(".git")
        if (!segments.all(::isValidPathSegment)) return null
        HostedRepository(provider, host, segments.dropLast(1).joinToString("/"), segments.last())
    }.getOrNull()
}
