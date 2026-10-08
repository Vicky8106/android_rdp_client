package com.rdp.client.freerdp

import com.rdp.client.model.AudioMode
import com.rdp.client.model.ColorDepth
import com.rdp.client.model.ResolutionMode
import com.rdp.client.model.SecurityType
import com.rdp.client.model.ServerProfile

/**
 * Validation result for RdpConnectionParameters.
 */
sealed class ConnectionValidationResult {
    object Success : ConnectionValidationResult()
    data class Error(val message: String) : ConnectionValidationResult()
    val isSuccess: Boolean get() = this is Success
}

/**
 * Immutable data class encapsulating parameters required to configure a native FreeRDP session.
 * Bridges Room DB ServerProfile entities into native arguments and C structures.
 */
data class RdpConnectionParameters(
    // Server Endpoint
    val host: String,
    val port: Int = 3389,

    // Credentials & Domain
    val domain: String = "",
    val username: String = "",
    val password: String = "",
    val securityType: SecurityType = SecurityType.AUTO,

    // Display & Graphics
    val width: Int = 1920,
    val height: Int = 1080,
    val desktopScale: Int = 100,
    val colorDepth: ColorDepth = ColorDepth.DEPTH_32,
    val resolutionMode: ResolutionMode = ResolutionMode.FIT_TO_SCREEN,

    // Remote Desktop Gateway
    val enableGateway: Boolean = false,
    val gatewayHost: String = "",
    val gatewayPort: Int = 443,
    val gatewayDomain: String = "",
    val gatewayUsername: String = "",
    val gatewayPassword: String = "",

    // Audio & Microphone
    val audioMode: AudioMode = AudioMode.LOCAL,
    val microphoneEnabled: Boolean = false,

    // Codecs & Performance
    val useRemoteFX: Boolean = true,
    val useGFX: Boolean = true,
    val useH264: Boolean = true,
    val clipboardSync: Boolean = true,
    val ignoreCertificate: Boolean = false,
    val performanceFlags: Int = 0
) {
    // Direct fields accessed by native JNI reflection in android_freerdp.c
    @JvmField val colorDepthBpp: Int = colorDepth.bpp
    @JvmField val securityTypeName: String = securityType.name
    @JvmField val audioModeCode: Int = audioMode.ordinal

    /**
     * Serializes parameters into FreeRDP 3.x command-line arguments.
     */
    fun toNativeArgs(): Array<String> {
        val args = mutableListOf<String>()

        // Synthetic argv[0] required by FreeRDP argument parser
        args.add("freerdp-android")

        // Target server endpoint
        val coercedPort = if (port in 1..65535) port else 3389
        args.add("/v:$host:$coercedPort")

        // Credentials
        if (username.isNotBlank()) args.add("/u:$username")
        if (password.isNotBlank()) args.add("/p:$password")
        if (domain.isNotBlank()) args.add("/d:$domain")

        // Security Negotiation
        when (securityType) {
            SecurityType.AUTO -> args.add("/sec:auto")
            SecurityType.NLA -> args.add("/sec:nla")
            SecurityType.TLS -> args.add("/sec:tls")
            SecurityType.RDP -> args.add("/sec:rdp")
        }

        // RD Gateway
        if (enableGateway && gatewayHost.isNotBlank()) {
            val coercedGwPort = if (gatewayPort in 1..65535) gatewayPort else 443
            args.add("/g:$gatewayHost:$coercedGwPort")
            val gwUser = if (gatewayUsername.isNotBlank()) gatewayUsername else username
            val gwPass = if (gatewayPassword.isNotBlank()) gatewayPassword else password
            val gwDom = if (gatewayDomain.isNotBlank()) gatewayDomain else domain
            if (gwUser.isNotBlank()) args.add("/gu:$gwUser")
            if (gwPass.isNotBlank()) args.add("/gp:$gwPass")
            if (gwDom.isNotBlank()) args.add("/gd:$gwDom")
        }

        // Display Resolution
        when (resolutionMode) {
            ResolutionMode.CUSTOM -> {
                args.add("/size:${width}x${height}")
            }
            ResolutionMode.DYNAMIC, ResolutionMode.FIT_TO_SCREEN -> {
                args.add("/size:${width}x${height}")
                args.add("/disp")
            }
            ResolutionMode.NATIVE -> {
                args.add("/size:${width}x${height}")
            }
        }

        // Scaling percentage
        if (desktopScale != 100) {
            args.add("/scale:$desktopScale")
        }

        // Color Depth
        args.add("/bpp:${colorDepth.bpp}")

        // Audio Redirection
        when (audioMode) {
            AudioMode.LOCAL -> args.add("/sound:sys:opensles")
            AudioMode.REMOTE -> args.add("/audio-mode:1")
            AudioMode.MUTE -> args.add("/audio-mode:2")
        }

        // Microphone Redirection
        if (microphoneEnabled) {
            args.add("/microphone:sys:opensles")
        }

        // Graphics Codecs
        if (useRemoteFX) args.add("+rfx")
        if (useGFX) args.add("+gfx")
        if (useH264) args.add("+gfx:AVC444")

        // Clipboard Virtual Channel (CLIPRDR)
        if (clipboardSync) args.add("+clipboard") else args.add("-clipboard")

        // Certificate verification
        if (ignoreCertificate) args.add("/cert:ignore")

        // Software GDI rasterizer
        args.add("/gdi:sw")

        // Input
        args.add("/kbd:unicode:on")

        return args.toTypedArray()
    }

    /**
     * Validates connection parameters prior to invoking native connect.
     */
    fun validate(): ConnectionValidationResult {
        if (host.isBlank()) {
            return ConnectionValidationResult.Error("Host address cannot be empty")
        }
        if (host.length > 255) {
            return ConnectionValidationResult.Error("Host address cannot exceed 255 characters")
        }
        if (username.length > 127) {
            return ConnectionValidationResult.Error("Username cannot exceed 127 characters")
        }
        if (password.length > 127) {
            return ConnectionValidationResult.Error("Password cannot exceed 127 characters")
        }
        if (domain.length > 127) {
            return ConnectionValidationResult.Error("Domain cannot exceed 127 characters")
        }
        if (port !in 1..65535) {
            return ConnectionValidationResult.Error("Port must be between 1 and 65535")
        }
        if (width <= 0 || height <= 0) {
            return ConnectionValidationResult.Error("Resolution width and height must be positive numbers")
        }
        if (enableGateway) {
            if (gatewayHost.isBlank()) {
                return ConnectionValidationResult.Error("Gateway host cannot be empty when gateway is enabled")
            }
            if (gatewayHost.length > 255) {
                return ConnectionValidationResult.Error("Gateway host cannot exceed 255 characters")
            }
            if (gatewayPort !in 1..65535) {
                return ConnectionValidationResult.Error("Gateway port must be between 1 and 65535")
            }
        }
        return ConnectionValidationResult.Success
    }

    /**
     * Produces a sanitized string representation with credentials masked for safe logging.
     */
    fun toSafeString(): String {
        val maskedPass = if (password.isNotBlank()) "******" else "(none)"
        val maskedGwPass = if (gatewayPassword.isNotBlank()) "******" else "(none)"
        val safeHost = host.take(64)
        val safeUser = username.take(64)
        val safeDomain = domain.take(64)
        val safeGwHost = gatewayHost.take(64)
        return "RdpConnectionParameters(host='$safeHost', port=$port, user='$safeUser', domain='$safeDomain', " +
                "pass='$maskedPass', sec=$securityType, res=${width}x${height}, bpp=${colorDepth.bpp}, " +
                "gwEnabled=$enableGateway, gwHost='$safeGwHost', gwPass='$maskedGwPass', audio=$audioMode)"
    }

    companion object {
        /**
         * Factory function creating parameters from a Room ServerProfile entity,
         * with optional display dimension overrides from the active Viewport.
         */
        fun fromServerProfile(
            profile: ServerProfile,
            targetWidth: Int? = null,
            targetHeight: Int? = null
        ): RdpConnectionParameters {
            val resolvedWidth = when (profile.resolutionMode) {
                ResolutionMode.CUSTOM -> profile.customWidth
                ResolutionMode.FIT_TO_SCREEN, ResolutionMode.NATIVE, ResolutionMode.DYNAMIC -> {
                    targetWidth ?: profile.customWidth
                }
            }
            val resolvedHeight = when (profile.resolutionMode) {
                ResolutionMode.CUSTOM -> profile.customHeight
                ResolutionMode.FIT_TO_SCREEN, ResolutionMode.NATIVE, ResolutionMode.DYNAMIC -> {
                    targetHeight ?: profile.customHeight
                }
            }

            return RdpConnectionParameters(
                host = profile.host,
                port = profile.port,
                domain = profile.domain,
                username = profile.username,
                password = profile.password,
                securityType = profile.securityType,
                width = resolvedWidth,
                height = resolvedHeight,
                desktopScale = profile.desktopScale,
                colorDepth = profile.colorDepth,
                resolutionMode = profile.resolutionMode,
                enableGateway = profile.enableGateway,
                gatewayHost = profile.gatewayHost,
                gatewayPort = profile.gatewayPort,
                gatewayDomain = profile.gatewayDomain,
                gatewayUsername = profile.gatewayUsername,
                gatewayPassword = profile.gatewayPassword,
                audioMode = profile.audioMode,
                microphoneEnabled = profile.microphoneEnabled,
                useRemoteFX = profile.useRemoteFX,
                useGFX = profile.useGFX,
                useH264 = profile.useH264,
                clipboardSync = profile.clipboardSync,
                ignoreCertificate = profile.ignoreCertificate,
                performanceFlags = profile.performanceFlags
            )
        }
        fun fromProfile(profile: ServerProfile): RdpConnectionParameters = fromServerProfile(profile)
    }
}
