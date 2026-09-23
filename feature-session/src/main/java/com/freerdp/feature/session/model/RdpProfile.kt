package com.freerdp.feature.session.model

import com.freerdp.core.engine.PerformancePreset
import com.freerdp.core.engine.RdpConnectionConfig
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class CredentialStorageType {
    NONE,
    KEYSTORE_ENCRYPTED,
    PROMPT_ON_CONNECT
}

@Serializable
enum class ResolutionMode {
    NATIVE_DISPLAY,
    FIXED_1080P,
    FIXED_720P,
    CUSTOM
}

@Serializable
enum class ColorDepth(val bpp: Int) {
    DEPTH_16(16),
    DEPTH_24(24),
    DEPTH_32(32)
}

@Serializable
enum class ScalingMode {
    FIT_SCREEN,
    ONE_TO_ONE,
    STRETCH
}

@Serializable
enum class CertValidationMode {
    STRICT,
    WARN_ON_MISMATCH,
    ACCEPT_ALL
}

@Serializable
data class DisplayConfig(
    val resolutionMode: ResolutionMode = ResolutionMode.NATIVE_DISPLAY,
    val customWidth: Int = 1920,
    val customHeight: Int = 1080,
    val colorDepth: ColorDepth = ColorDepth.DEPTH_32,
    val scalingMode: ScalingMode = ScalingMode.FIT_SCREEN
) {
    companion object {
        val DEFAULT = DisplayConfig()
    }
}

@Serializable
data class RedirectionConfig(
    val soundEnabled: Boolean = false,
    val microphoneEnabled: Boolean = false,
    val clipboardEnabled: Boolean = true
) {
    companion object {
        val DEFAULT = RedirectionConfig()
    }
}

@Serializable
data class NetworkConfig(
    val autoReconnect: Boolean = true,
    val maxReconnectAttempts: Int = 5,
    val connectionTimeoutMs: Long = 10000L
) {
    companion object {
        val DEFAULT = NetworkConfig()
    }
}

@Serializable
data class SecurityConfig(
    val nlaEnabled: Boolean = true,
    val tlsEnabled: Boolean = true,
    val certValidationMode: CertValidationMode = CertValidationMode.WARN_ON_MISMATCH
) {
    companion object {
        val DEFAULT = SecurityConfig()
    }
}

@Serializable
data class RdpProfile(
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    val hostname: String,
    val port: Int = 3389,
    val username: String = "",
    val domain: String = "",
    val credentialStorageType: CredentialStorageType = CredentialStorageType.KEYSTORE_ENCRYPTED,
    val displayConfig: DisplayConfig = DisplayConfig.DEFAULT,
    val performancePreset: PerformancePreset = PerformancePreset.BALANCED,
    val redirectionConfig: RedirectionConfig = RedirectionConfig.DEFAULT,
    val networkConfig: NetworkConfig = NetworkConfig.DEFAULT,
    val securityConfig: SecurityConfig = SecurityConfig.DEFAULT,
    val lastConnectedTimestamp: Long? = null,
    val isPinned: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun toConnectionConfig(password: String = ""): RdpConnectionConfig {
        val (w, h) = when (displayConfig.resolutionMode) {
            ResolutionMode.FIXED_1080P -> 1920 to 1080
            ResolutionMode.FIXED_720P -> 1280 to 720
            ResolutionMode.CUSTOM -> displayConfig.customWidth to displayConfig.customHeight
            ResolutionMode.NATIVE_DISPLAY -> displayConfig.customWidth to displayConfig.customHeight
        }
        return RdpConnectionConfig(
            serverAddress = hostname,
            port = port,
            username = username,
            domain = domain,
            password = password,
            width = w,
            height = h,
            colorDepth = displayConfig.colorDepth.bpp,
            enableNla = securityConfig.nlaEnabled,
            enableTls = securityConfig.tlsEnabled,
            ignoreCertificate = securityConfig.certValidationMode == CertValidationMode.ACCEPT_ALL,
            enableClipboard = redirectionConfig.clipboardEnabled,
            enableDynamicResolution = true,
            performancePreset = performancePreset
        )
    }
}
