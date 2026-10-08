package com.rdp.client.validator

import com.rdp.client.model.ResolutionMode
import java.util.regex.Pattern

enum class FormField {
    HOST,
    PORT,
    CUSTOM_WIDTH,
    CUSTOM_HEIGHT,
    GATEWAY_HOST,
    GATEWAY_PORT,
    WOL_MAC,
    WOL_BROADCAST,
    WOL_PORT
}

data class ValidationResult(
    val isValid: Boolean,
    val errors: Map<FormField, String> = emptyMap()
)

data class SingleValidationResult(
    val isValid: Boolean,
    val errorMessage: String? = null
)

/**
 * Pure Kotlin validator for RDP connection parameters.
 * Completely decoupled from Android framework dependencies for fast, headless JUnit testing.
 */
object ProfileValidator {

    private val MAC_PATTERN = Pattern.compile("^([0-9A-Fa-f]{2}[:-]){5}([0-9A-Fa-f]{2})$")
    private val IPV4_PATTERN = Pattern.compile(
        "^((25[0-5]|(2[0-4]|1\\d|[1-9]|)\\d)\\.){3}(25[0-5]|(2[0-4]|1\\d|[1-9]|)\\d)$"
    )

    data class ProfileFormState(
        val name: String = "",
        val host: String = "",
        val portStr: String = "3389",
        val domain: String = "",
        val username: String = "",
        val password: String = "",
        val resolutionMode: ResolutionMode = ResolutionMode.FIT_TO_SCREEN,
        val customWidthStr: String = "1920",
        val customHeightStr: String = "1080",
        val enableGateway: Boolean = false,
        val gatewayHost: String = "",
        val gatewayPortStr: String = "443",
        val enableWol: Boolean = false,
        val wolMac: String = "",
        val wolBroadcast: String = "255.255.255.255",
        val wolPortStr: String = "9"
    )

    fun validateHost(host: String): SingleValidationResult {
        val trimmed = host.trim()
        if (trimmed.isEmpty()) {
            return SingleValidationResult(false, "Host address is required")
        }
        if (trimmed.contains(" ")) {
            return SingleValidationResult(false, "Host address cannot contain whitespace")
        }
        return SingleValidationResult(true)
    }

    fun validatePort(port: Int): SingleValidationResult {
        if (port !in 1..65535) {
            return SingleValidationResult(false, "Port must be between 1 and 65535")
        }
        return SingleValidationResult(true)
    }

    fun validateCustomResolution(widthStr: String, heightStr: String): Pair<SingleValidationResult, SingleValidationResult> {
        val width = widthStr.toIntOrNull()
        val height = heightStr.toIntOrNull()

        val widthResult = when {
            width == null -> SingleValidationResult(false, "Width is required")
            width !in 640..8192 -> SingleValidationResult(false, "Width must be between 640 and 8192")
            else -> SingleValidationResult(true)
        }

        val heightResult = when {
            height == null -> SingleValidationResult(false, "Height is required")
            height !in 480..8192 -> SingleValidationResult(false, "Height must be between 480 and 8192")
            else -> SingleValidationResult(true)
        }

        return Pair(widthResult, heightResult)
    }

    fun validateMacAddress(mac: String): SingleValidationResult {
        val trimmed = mac.trim()
        if (trimmed.isEmpty()) {
            return SingleValidationResult(false, "MAC address is required for Wake-on-LAN")
        }
        if (!MAC_PATTERN.matcher(trimmed).matches()) {
            return SingleValidationResult(false, "Invalid MAC address (e.g. 00:11:22:33:44:55)")
        }
        return SingleValidationResult(true)
    }

    fun validateBroadcastIp(ip: String): SingleValidationResult {
        val trimmed = ip.trim()
        if (trimmed.isEmpty()) {
            return SingleValidationResult(false, "Broadcast IP is required")
        }
        if (!IPV4_PATTERN.matcher(trimmed).matches()) {
            return SingleValidationResult(false, "Invalid IPv4 broadcast address")
        }
        return SingleValidationResult(true)
    }

    fun validateProfile(form: ProfileFormState): ValidationResult {
        val errors = mutableMapOf<FormField, String>()

        // Host
        val hostRes = validateHost(form.host)
        if (!hostRes.isValid) errors[FormField.HOST] = hostRes.errorMessage!!

        // Port
        val port = form.portStr.toIntOrNull() ?: -1
        val portRes = validatePort(port)
        if (!portRes.isValid) errors[FormField.PORT] = portRes.errorMessage!!

        // Custom Resolution
        if (form.resolutionMode == ResolutionMode.CUSTOM) {
            val (wRes, hRes) = validateCustomResolution(form.customWidthStr, form.customHeightStr)
            if (!wRes.isValid) errors[FormField.CUSTOM_WIDTH] = wRes.errorMessage!!
            if (!hRes.isValid) errors[FormField.CUSTOM_HEIGHT] = hRes.errorMessage!!
        }

        // RD Gateway
        if (form.enableGateway) {
            val gwHostRes = validateHost(form.gatewayHost)
            if (!gwHostRes.isValid) errors[FormField.GATEWAY_HOST] = "Gateway host is required"

            val gwPort = form.gatewayPortStr.toIntOrNull() ?: -1
            val gwPortRes = validatePort(gwPort)
            if (!gwPortRes.isValid) errors[FormField.GATEWAY_PORT] = "Gateway port must be between 1 and 65535"
        }

        // Wake-on-LAN
        if (form.enableWol) {
            val macRes = validateMacAddress(form.wolMac)
            if (!macRes.isValid) errors[FormField.WOL_MAC] = macRes.errorMessage!!

            val bcastRes = validateBroadcastIp(form.wolBroadcast)
            if (!bcastRes.isValid) errors[FormField.WOL_BROADCAST] = bcastRes.errorMessage!!

            val wolPort = form.wolPortStr.toIntOrNull() ?: -1
            val wolPortRes = validatePort(wolPort)
            if (!wolPortRes.isValid) errors[FormField.WOL_PORT] = "WoL port must be between 1 and 65535"
        }

        return ValidationResult(isValid = errors.isEmpty(), errors = errors)
    }
}
