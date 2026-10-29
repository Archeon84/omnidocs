package com.omnidocs.app.agent

import com.omnidocs.app.ai.ModelDownloadManager
import com.omnidocs.app.ai.ModelPreferences
import com.omnidocs.app.ai.isAnyModelDownloaded
import com.omnidocs.app.security.SecurityPreferences
import javax.inject.Inject
import javax.inject.Singleton

sealed interface PolicyDecision {
    object Allowed : PolicyDecision
    data class Denied(val reason: String) : PolicyDecision
    data class RequiresUserConsent(val reason: String) : PolicyDecision
}

/**
 * Interface defining privacy and model execution policy enforcement.
 */
interface PolicyGuard {
    fun evaluate(
        privacyMode: PrivacyMode,
        modelPolicy: ModelPolicy,
        requiresCloud: Boolean = false
    ): PolicyDecision

    fun isLocalModelReady(): Boolean
}

@Singleton
class DefaultPolicyGuard @Inject constructor(
    private val modelPreferences: ModelPreferences,
    private val modelDownloadManager: ModelDownloadManager,
    private val securityPreferences: SecurityPreferences? = null
) : PolicyGuard {

    override fun evaluate(
        privacyMode: PrivacyMode,
        modelPolicy: ModelPolicy,
        requiresCloud: Boolean
    ): PolicyDecision {
        // Enforce Local-Only privacy constraint
        val localOnlyEnforced = privacyMode == PrivacyMode.LOCAL_ONLY ||
            (securityPreferences != null && securityPreferences.localOnlyProcessing.value)
        if (localOnlyEnforced && requiresCloud) {
            return PolicyDecision.Denied("Cloud processing is forbidden in LOCAL_ONLY privacy mode.")
        }

        // If cloud is required, verify provider allowance and user opt-in
        val cloudAllowedByProvider = modelPolicy.allowedProviders.any { it != "llama_cpp" }
        val cloudOptedIn = securityPreferences?.cloudOptIn?.value ?: true
        if (requiresCloud && (!cloudAllowedByProvider || !cloudOptedIn)) {
            return PolicyDecision.RequiresUserConsent("Operation requires cloud AI provider which is not enabled.")
        }

        return PolicyDecision.Allowed
    }

    override fun isLocalModelReady(): Boolean {
        return isAnyModelDownloaded(modelDownloadManager)
    }
}
