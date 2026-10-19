package dev.bettergit.services

import com.intellij.util.EnvironmentUtil
import dev.bettergit.core.hosting.ExistingCredentialsResolver
import dev.bettergit.core.hosting.LocalCredentialCommandRunner

internal object HostingCredentials {
    fun resolver() = ExistingCredentialsResolver(LocalCredentialCommandRunner { EnvironmentUtil.getEnvironmentMap() })
}
