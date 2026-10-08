package com.rdp.client.utils

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.regex.Pattern

/**
 * Utility for constructing and transmitting Wake-on-LAN (WoL) UDP magic packets.
 */
object WolHelper {

    private const val TAG = "WolHelper"
    private val MAC_REGEX = Pattern.compile("^([0-9A-Fa-f]{2}[:-]){5}([0-9A-Fa-f]{2})$")

    /**
     * Sends a Wake-on-LAN magic packet over UDP broadcast.
     * Returns true if packet was successfully transmitted.
     */
    suspend fun sendMagicPacket(
        macAddress: String,
        broadcastIp: String = "255.255.255.255",
        port: Int = 9
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val cleanMac = macAddress.trim()
            if (!isValidMac(cleanMac)) {
                Log.e(TAG, "Invalid MAC address: $cleanMac")
                return@withContext false
            }

            val macBytes = parseMacBytes(cleanMac)
            val packetData = ByteArray(6 + 16 * macBytes.size)

            // Header: 6 bytes of 0xFF
            for (i in 0 until 6) {
                packetData[i] = 0xFF.toByte()
            }

            // Payload: MAC address repeated 16 times
            for (i in 0 until 16) {
                System.arraycopy(macBytes, 0, packetData, 6 + i * macBytes.size, macBytes.size)
            }

            val address = InetAddress.getByName(if (broadcastIp.isBlank()) "255.255.255.255" else broadcastIp.trim())
            val targetPort = if (port in 1..65535) port else 9
            val packet = DatagramPacket(packetData, packetData.size, address, targetPort)

            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.send(packet)
            }

            Log.i(TAG, "Successfully transmitted WoL magic packet to $cleanMac via $address:$targetPort")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to transmit WoL magic packet", e)
            false
        }
    }

    fun isValidMac(macAddress: String): Boolean {
        return MAC_REGEX.matcher(macAddress.trim()).matches()
    }

    fun parseMacBytes(macStr: String): ByteArray {
        val parts = macStr.split(":", "-")
        val bytes = ByteArray(6)
        for (i in 0 until 6) {
            bytes[i] = parts[i].toInt(16).toByte()
        }
        return bytes
    }
}
