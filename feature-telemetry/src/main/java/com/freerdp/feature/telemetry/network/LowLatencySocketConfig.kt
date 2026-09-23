package com.freerdp.feature.telemetry.network

import java.net.Socket
import java.nio.channels.SocketChannel

/**
 * Socket tuning configuration optimized for interactive low-latency RDP sessions.
 *
 * Configures (OS layer):
 * - TCP_NODELAY (Nagle algorithm disabled): Dispatches packets immediately without waiting for buffer fills.
 * - SO_RCVBUF (128 KB): Prevents bufferbloat while ensuring adequate throughput for frame updates.
 * - SO_SNDBUF (64 KB): Minimizes outbound queue latency for mouse and keyboard input events.
 * - IP_TOS (0x10 / IPTOS_LOWDELAY): Expedited low-delay traffic classification on intermediate routers.
 * - SO_KEEPALIVE: Early dead-connection detection.
 *
 * ## Mapping: tuning option -> OS socket option -> FreeRDP surface
 *
 * | TuningOption       | OS socket option          | FreeRDP surface                                                     | Rationale (R4 / feature #22)                          |
 * |--------------------|---------------------------|---------------------------------------------------------------------|-------------------------------------------------------|
 * | [TuningOptions.tcpNoDelay]  | `TCP_NODELAY` (1) | No CLI flag. Applied at OS layer; FreeRDP transport uses its own fd (see `core-rdp` cross-module note). MS-RDPBCGR fast-path input benefits directly. | Input PDUs leave immediately; Nagle would add up to ~40ms. |
 * | [TuningOptions.rxBufferBytes] | `SO_RCVBUF`      | No CLI flag (FreeRDP reads through its transport loop).             | 128 KB absorbs bursty GFX/frame updates without bufferbloat. |
 * | [TuningOptions.txBufferBytes] | `SO_SNDBUF`      | No CLI flag.                                                        | 64 KB bounds input+acks queueing delay.               |
 * | [TuningOptions.ipTos]        | `IP_TOS` (0x10 IPTOS_LOWDELAY) | No CLI flag; complements `/network:auto|modem|broadband`. | Best-effort DSCP hint; readback not guaranteed on all platforms (Windows JDK returns 0). |
 * | [TuningOptions.keepAlive]    | `SO_KEEPALIVE`            | FreeRDP `/heartbeat` (MS-RDPBCGR heartbeat PDUs) is complementary; OS keepalive detects dead peers below RDP layer. | Early TCP death detection feeds [com.freerdp.feature.telemetry.reconnect.AutoReconnectManager]. |
 *
 * Validation boundaries: buffer sizes must lie within [MIN_BUFFER_BYTES]..[MAX_BUFFER_BYTES],
 * IP_TOS within [MIN_IP_TOS]..[MAX_IP_TOS] (0..255). Invalid values throw [IllegalArgumentException]
 * at [TuningOptions] construction — the same class of boundary as port 0/65535 checks elsewhere.
 */
object LowLatencySocketConfig {

    const val DEFAULT_RX_BUFFER_BYTES: Int = 131072 // 128 KB
    const val DEFAULT_TX_BUFFER_BYTES: Int = 65536  // 64 KB

    /** Minimum accepted SO_RCVBUF / SO_SNDBUF (4 KB). */
    const val MIN_BUFFER_BYTES: Int = 4096

    /** Maximum accepted SO_RCVBUF / SO_SNDBUF (16 MB). */
    const val MAX_BUFFER_BYTES: Int = 16 * 1024 * 1024

    /** Minimum accepted IP_TOS value (0 = default DSCP). */
    const val MIN_IP_TOS: Int = 0

    /** Maximum accepted IP_TOS value (255 = 8-bit Traffic Class field). */
    const val MAX_IP_TOS: Int = 255

    const val IPTOS_LOWDELAY: Int = 0x10            // Low-delay TOS flag
    const val DEFAULT_TCP_NODELAY: Boolean = true
    const val DEFAULT_KEEPALIVE: Boolean = true

    data class TuningOptions(
        val tcpNoDelay: Boolean = DEFAULT_TCP_NODELAY,
        val rxBufferBytes: Int = DEFAULT_RX_BUFFER_BYTES,
        val txBufferBytes: Int = DEFAULT_TX_BUFFER_BYTES,
        val ipTos: Int = IPTOS_LOWDELAY,
        val keepAlive: Boolean = DEFAULT_KEEPALIVE
    ) {
        init {
            require(rxBufferBytes in MIN_BUFFER_BYTES..MAX_BUFFER_BYTES) {
                "rxBufferBytes must be in $MIN_BUFFER_BYTES..$MAX_BUFFER_BYTES, got $rxBufferBytes"
            }
            require(txBufferBytes in MIN_BUFFER_BYTES..MAX_BUFFER_BYTES) {
                "txBufferBytes must be in $MIN_BUFFER_BYTES..$MAX_BUFFER_BYTES, got $txBufferBytes"
            }
            require(ipTos in MIN_IP_TOS..MAX_IP_TOS) {
                "ipTos must be in $MIN_IP_TOS..$MAX_IP_TOS, got $ipTos"
            }
        }
    }

    /**
     * Validates options without applying them (range checks mirror [TuningOptions] init).
     * Returns the validated instance for convenience.
     */
    fun validate(options: TuningOptions): TuningOptions {
        // Construction already validated; re-check defensively so callers can validate
        // options deserialized from storage without trusting the source.
        require(options.rxBufferBytes in MIN_BUFFER_BYTES..MAX_BUFFER_BYTES)
        require(options.txBufferBytes in MIN_BUFFER_BYTES..MAX_BUFFER_BYTES)
        require(options.ipTos in MIN_IP_TOS..MAX_IP_TOS)
        return options
    }

    /**
     * Applies low-latency socket options to a standard Java [Socket].
     *
     * Fails with [Result.failure] when the socket is already closed or the platform
     * rejects an option (instead of silently no-op'ing), so callers can surface
     * misconfiguration rather than run with default Nagle/buffer settings.
     */
    fun configureSocket(socket: Socket, options: TuningOptions = TuningOptions()): Result<Unit> = runCatching {
        validate(options)
        check(!socket.isClosed) { "Cannot configure a closed socket" }
        socket.tcpNoDelay = options.tcpNoDelay
        socket.receiveBufferSize = options.rxBufferBytes
        socket.sendBufferSize = options.txBufferBytes
        socket.trafficClass = options.ipTos
        socket.keepAlive = options.keepAlive
    }

    /**
     * Applies low-latency socket options to a NIO [SocketChannel].
     * Fails when the channel is closed.
     */
    fun configureChannel(channel: SocketChannel, options: TuningOptions = TuningOptions()): Result<Unit> = runCatching {
        validate(options)
        check(channel.isOpen) { "Cannot configure a closed SocketChannel" }
        channel.setOption(java.net.StandardSocketOptions.TCP_NODELAY, options.tcpNoDelay)
        channel.setOption(java.net.StandardSocketOptions.SO_RCVBUF, options.rxBufferBytes)
        channel.setOption(java.net.StandardSocketOptions.SO_SNDBUF, options.txBufferBytes)
        channel.setOption(java.net.StandardSocketOptions.IP_TOS, options.ipTos)
        channel.setOption(java.net.StandardSocketOptions.SO_KEEPALIVE, options.keepAlive)
    }

    /**
     * Reads back current socket options for verification and diagnostics.
     *
     * Note: on Windows JDKs `trafficClass` readback is always 0 (IP_TOS is applied
     * best-effort); other options round-trip on all supported platforms.
     */
    fun inspectSocket(socket: Socket): Map<String, Any> {
        return mapOf(
            "tcpNoDelay" to socket.tcpNoDelay,
            "receiveBufferSize" to socket.receiveBufferSize,
            "sendBufferSize" to socket.sendBufferSize,
            "trafficClass" to socket.trafficClass,
            "keepAlive" to socket.keepAlive
        )
    }
}
