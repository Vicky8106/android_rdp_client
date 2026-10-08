package com.rdp.client.model

/**
 * Security negotiation protocols supported by Microsoft RDP / FreeRDP.
 */
enum class SecurityType(val code: Int, val freeRdpArg: String, val displayName: String) {
    AUTO(0, "auto", "Auto (Negotiate)"),
    NLA(1, "nla", "NLA (CredSSP)"),
    TLS(2, "tls", "TLS / SSL"),
    RDP(3, "rdp", "Standard RDP");

    companion object {
        fun fromInt(code: Int): SecurityType =
            entries.firstOrNull { it.code == code } ?: AUTO

        fun fromString(name: String?): SecurityType =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: AUTO
    }
}
