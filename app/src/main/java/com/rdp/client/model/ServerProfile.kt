package com.rdp.client.model

import android.os.Parcelable
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.parcelize.Parcelize

/**
 * Room Entity representing an RDP server connection profile.
 * Provides complete parity with AVNC bookmarks adapted for Microsoft RDP connections.
 */
@Parcelize
@Entity(
    tableName = "profiles",
    indices = [
        Index(value = ["name"]),
        Index(value = ["lastConnectedTimestamp"]),
        Index(value = ["isQuickConnect"]),
        Index(value = ["sortOrder"])
    ]
)
data class ServerProfile(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,

    // General & Connection Information
    val name: String = "",
    val host: String = "",
    val port: Int = 3389,
    val domain: String = "",
    val username: String = "",
    val password: String = "",
    val securityType: SecurityType = SecurityType.AUTO,

    // Remote Desktop Gateway (RDGW)
    val enableGateway: Boolean = false,
    val gatewayHost: String = "",
    val gatewayPort: Int = 443,
    val gatewayDomain: String = "",
    val gatewayUsername: String = "",
    val gatewayPassword: String = "",

    // Display & Graphics
    val resolutionMode: ResolutionMode = ResolutionMode.FIT_TO_SCREEN,
    val customWidth: Int = 1920,
    val customHeight: Int = 1080,
    val desktopScale: Int = 100, // Scaling percentage (100, 125, 150, 200)
    val colorDepth: ColorDepth = ColorDepth.DEPTH_32,
    val useRemoteFX: Boolean = true,
    val useGFX: Boolean = true,
    val useH264: Boolean = true,

    // Audio & Microphone
    val audioMode: AudioMode = AudioMode.LOCAL,
    val microphoneEnabled: Boolean = false,

    // Wake-on-LAN (WoL)
    val enableWol: Boolean = false,
    val wolMacAddress: String = "",
    val wolBroadcastIp: String = "255.255.255.255",
    val wolPort: Int = 9,

    // Ergonomics & Viewport Settings (AVNC Parity)
    val zoom1: Float = 1.0f, // Saved portrait zoom scale
    val zoom2: Float = 1.0f, // Saved landscape zoom scale
    val viewMode: ViewMode = ViewMode.NORMAL,
    val gestureStyle: GestureStyle = GestureStyle.AUTO,
    val screenOrientation: ScreenOrientation = ScreenOrientation.AUTO,

    // Session Flags
    val ignoreCertificate: Boolean = false,
    val clipboardSync: Boolean = true,
    val connectOnAppStart: Boolean = false,
    val buttonUpDelay: Boolean = false,
    val performanceFlags: Int = 0,

    // Metadata & Organization
    val lastConnectedTimestamp: Long = 0L,
    val connectionCount: Int = 0,
    val isQuickConnect: Boolean = false,
    val sortOrder: Int = 0,
    val createdTimestamp: Long = System.currentTimeMillis()
) : Parcelable {

    /**
     * Returns user-visible display title: `name` if set, else fallback to `host`.
     */
    fun getDisplayName(): String = name.ifBlank { host.ifBlank { "Untitled Connection" } }

    /**
     * Returns formatted host endpoint (e.g. "192.168.1.100" or "192.168.1.100:3390").
     */
    fun getDisplayAddress(): String = if (port == 3389) host else "$host:$port"

    /**
     * Returns a concise textual summary of connection parameters for UI badges/labels.
     */
    fun toDisplaySummary(): String {
        val addr = getDisplayAddress()
        val sec = securityType.displayName
        val res = when (resolutionMode) {
            ResolutionMode.FIT_TO_SCREEN -> "Fit"
            ResolutionMode.NATIVE -> "Native"
            ResolutionMode.CUSTOM -> "${customWidth}x${customHeight}"
            ResolutionMode.DYNAMIC -> "Dynamic"
        }
        val depth = "${colorDepth.bpp} bpp"
        return "$addr • $sec • $res • $depth"
    }

    /**
     * Returns true if credentials are saved in profile.
     */
    fun hasCredentials(): Boolean = username.isNotBlank() || password.isNotBlank()

    /**
     * Validates configuration integrity.
     */
    fun validate(): ValidationResult {
        if (host.isBlank()) {
            return ValidationResult.Error("Host address cannot be empty")
        }
        if (host.length > 255) {
            return ValidationResult.Error("Host address cannot exceed 255 characters")
        }
        if (username.length > 127) {
            return ValidationResult.Error("Username cannot exceed 127 characters")
        }
        if (password.length > 127) {
            return ValidationResult.Error("Password cannot exceed 127 characters")
        }
        if (domain.length > 127) {
            return ValidationResult.Error("Domain cannot exceed 127 characters")
        }
        if (port !in 1..65535) {
            return ValidationResult.Error("Port must be between 1 and 65535")
        }
        if (enableGateway) {
            if (gatewayHost.isBlank()) {
                return ValidationResult.Error("Gateway host cannot be empty when gateway is enabled")
            }
            if (gatewayHost.length > 255) {
                return ValidationResult.Error("Gateway host cannot exceed 255 characters")
            }
            if (gatewayPort !in 1..65535) {
                return ValidationResult.Error("Gateway port must be between 1 and 65535")
            }
        }
        if (enableWol) {
            if (wolMacAddress.isBlank()) {
                return ValidationResult.Error("Wake-on-LAN MAC address cannot be empty")
            }
            val macRegex = "^([0-9A-Fa-f]{2}[:-]){5}([0-9A-Fa-f]{2})$".toRegex()
            if (!wolMacAddress.matches(macRegex)) {
                return ValidationResult.Error("Invalid MAC address format (expected XX:XX:XX:XX:XX:XX)")
            }
            if (wolPort !in 1..65535) {
                return ValidationResult.Error("Wake-on-LAN port must be between 1 and 65535")
            }
        }
        if (resolutionMode == ResolutionMode.CUSTOM) {
            if (customWidth <= 0 || customHeight <= 0) {
                return ValidationResult.Error("Custom resolution width and height must be positive numbers")
            }
        }
        return ValidationResult.Success
    }

    /**
     * Converts this profile into command line arguments for FreeRDP / aFreeRDP engine.
     */
    fun toFreeRdpArguments(): List<String> {
        val args = mutableListOf<String>()

        // Target server endpoint
        val coercedPort = if (port in 1..65535) port else 3389
        args.add("/v:$host:$coercedPort")

        // Credentials & Domain
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

        // Display & Resolution
        when (resolutionMode) {
            ResolutionMode.CUSTOM -> {
                args.add("/w:$customWidth")
                args.add("/h:$customHeight")
            }
            ResolutionMode.DYNAMIC -> {
                args.add("/disp")
            }
            ResolutionMode.FIT_TO_SCREEN -> {
                args.add("/disp")
            }
            ResolutionMode.NATIVE -> {
                // Resolution populated dynamically by SessionActivity
            }
        }

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

        // Codecs
        if (useRemoteFX) args.add("+rfx")
        if (useGFX) args.add("+gfx")
        if (useH264) args.add("+gfx:AVC444")

        // Clipboard
        if (clipboardSync) args.add("+clipboard") else args.add("-clipboard")

        // Certificate verification
        if (ignoreCertificate) args.add("/cert:ignore")

        return args
    }
}

/**
 * Result model for profile validation.
 */
sealed class ValidationResult {
    data object Success : ValidationResult()
    data class Error(val message: String) : ValidationResult()

    val isSuccess: Boolean get() = this is Success
}
