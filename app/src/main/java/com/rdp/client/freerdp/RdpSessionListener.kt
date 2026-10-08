package com.rdp.client.freerdp

/**
 * Listener interface for native RDP protocol events dispatched from LibFreeRDP.
 */
interface RdpSessionListener {
    /** Called when RDP capabilities negotiation finishes and desktop session is active */
    fun onConnectionSuccess(instance: Long)

    /** Called when connection attempt fails with an error code and description */
    fun onConnectionFailure(instance: Long, errorCode: Int, message: String)

    /** Called when session terminates cleanly or remote host disconnects */
    fun onDisconnected(instance: Long)

    /** Called when dirty rectangle union is ready for blitting onto the framebuffer */
    fun onGraphicsUpdate(instance: Long, x: Int, y: Int, width: Int, height: Int)

    /** Called when desktop resolution or color depth changes (e.g. MS-RDPEDISP resize) */
    fun onGraphicsResize(instance: Long, width: Int, height: Int, bpp: Int) {}

    /** Called when server requires NLA/CredSSP credentials (returns true if supplied) */
    fun onAuthenticate(instance: Long): Boolean = false

    /** Called when server TLS certificate requires verification (returns true to accept) */
    fun onVerifyCertificate(instance: Long, host: String, port: Int, fingerprint: String, flags: Int): Boolean = false

    /** Called when remote server updates clipboard content */
    fun onRemoteClipboardChanged(instance: Long, text: String) {}

    /** Called when remote server updates cursor position */
    fun onCursorMoved(instance: Long, x: Int, y: Int) {}

    /** Called when remote server updates cursor shape */
    fun onCursorShapeChanged(instance: Long, width: Int, height: Int, hotX: Int, hotY: Int, pixels: IntArray?) {}
}
