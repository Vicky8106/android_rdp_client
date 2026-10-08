package com.rdp.client.freerdp

/**
 * Encapsulates an IBM PC AT 8042 scancode and extended prefix flag.
 */
data class RdpScancode(
    val code: Int,
    val isExtended: Boolean = false
) {
    /**
     * Packs the scancode and state into FreeRDP / MS-RDPBCGR keyboard flags.
     */
    fun toRdpFlags(isDown: Boolean): Int {
        var flags = if (isDown) KBD_FLAGS_DOWN else KBD_FLAGS_RELEASE
        if (isExtended) {
            flags = flags or KBD_FLAGS_EXTENDED
        }
        return flags
    }

    companion object {
        const val KBD_FLAGS_DOWN = 0x0000
        const val KBD_FLAGS_RELEASE = 0x8000
        const val KBD_FLAGS_EXTENDED = 0x0100
        const val EXTENDED_PREFIX_E0 = 0xE0
    }
}
