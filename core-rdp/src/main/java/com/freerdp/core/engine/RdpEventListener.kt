package com.freerdp.core.engine

import android.graphics.Bitmap

interface RdpEventListener {
    fun onConnectionSuccess()
    fun onConnectionFailure(errorCode: Int, message: String)
    fun onDisconnected()
    fun onGraphicsUpdate(bitmap: Bitmap, x: Int, y: Int, width: Int, height: Int)
    fun onResolutionChanged(width: Int, height: Int)
    fun onClipboardDataReceived(format: Int, data: ByteArray)
    fun onCertificateVerification(fingerprint: String, host: String): Boolean
}
