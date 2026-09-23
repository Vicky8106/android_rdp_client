package com.freerdp.feature.telemetry.preset

import com.freerdp.feature.telemetry.network.LowLatencySocketConfig
import java.util.Locale

/**
 * Structured mapping from a [PerformancePreset] to FreeRDP connection flags/arguments
 * plus the OS-layer socket tuning applied alongside them.
 *
 * This is DATA for the `:core-rdp` integration: it is the single source of truth for
 * which FreeRDP CLI arguments a session must be launched with for a given performance
 * mode (R4 / PROJECT.md feature #24). Flag names were verified against FreeRDP 3's
 * `client/common/cmdline.h` and the `xfreerdp3(1)` manual page.
 *
 * Notable verified facts:
 * - FreeRDP has **no `/fps:` argument** (confirmed against cmdline.h). Frame-rate caps
 *   are therefore enforced client-side by [com.freerdp.feature.telemetry.pacer.FramePacer]
 *   using [PerformancePreset.frameIntervalMs]; GFX frame pacing feedback rides on the
 *   RDP8 graphics pipeline itself.
 * - `/network:` accepts `auto|modem|broadband|broadband-low|broadband-high|wan|lan`.
 * - `/gfx:[progressive|RFX|AVC420|AVC444]` selects the RDP8 GPU pipeline variant.
 * - `/compression-level:0|1|2` tunes MPPC; `/compression` (default on) toggles it.
 * - `/audio-mode:2` disables audio, `/audio-mode:0` redirects it.
 * - `/auto-reconnect` + `/auto-reconnect-max-retries` exist but are intentionally NOT
 *   emitted: the client-side [com.freerdp.feature.telemetry.reconnect.AutoReconnectManager]
 *   owns reconnection with full-jitter backoff (double-reconnect loops otherwise).
 */
data class FreeRdpConnectionProfile(
    val preset: PerformancePreset,
    /** Frame-rate cap target (client-enforced; FreeRDP has no /fps argument). */
    val frameRateFps: Int,
    /** Frame interval implied by [frameRateFps] (ms) — FramePacer VSYNC period. */
    val frameIntervalMs: Double,
    /** Where the fps cap is enforced. */
    val fpsEnforcement: String,
    /** e.g. "/bpp:32". */
    val colorDepthArg: String,
    /** Legacy/extended codec arguments, e.g. ["+rfx"] or []. */
    val codecArgs: List<String>,
    /** Human-readable codec selection note. */
    val codecDescription: String,
    /** Compression arguments, e.g. ["/compression-level:1"]. */
    val compressionArgs: List<String>,
    /** e.g. "/network:auto". */
    val networkAutoDetectArg: String,
    /** True when the preset relies on FreeRDP network auto-detection rather than a fixed class. */
    val networkAutoDetect: Boolean,
    /** e.g. "/gfx:AVC444" — RDP8 GPU pipeline variant ("" when disabled). */
    val gpuPipelineArg: String,
    val gpuPipelineEnabled: Boolean,
    /** Sound redirection arguments ("/audio-mode:2" off, "/audio-mode:0" redirect). */
    val soundArgs: List<String>,
    val soundEnabled: Boolean,
    /** OS-layer SO_RCVBUF applied by LowLatencySocketConfig (no FreeRDP CLI flag exists). */
    val socketRxBufferBytes: Int,
    val socketTxBufferBytes: Int,
    val tcpNoDelay: Boolean,
    /** Telemetry publication cadence derived from PerformancePreset.telemetryRateHz. */
    val telemetryIntervalMs: Long,
    val reconnectBaseDelayMs: Long,
    val reconnectMaxDelayMs: Long,
    val reconnectMaxAttempts: Int,
    /** Always false — see class docs; kept explicit so consumers cannot miss it. */
    val freerdpAutoReconnectEmitted: Boolean,
    /** Complete FreeRDP CLI argument list for this preset. */
    val cliArgs: List<String>
)

/**
 * OS socket option mapping table (LowLatencySocketConfig -> OS -> FreeRDP surface).
 */
data class SocketOptionMapping(
    val tuningOption: String,
    val osSocketOption: String,
    val freerdpSurface: String,
    val rationale: String
)

/**
 * Static accessor for the preset -> FreeRDP mapping tables.
 */
object FreeRdpFlagMapping {

    /** One profile per preset; order follows PerformancePreset declaration order. */
    val all: List<FreeRdpConnectionProfile> = PerformancePreset.entries.map { forPreset(it) }

    fun forPreset(preset: PerformancePreset): FreeRdpConnectionProfile {
        val cliArgs = preset.toFreeRdpCliArgs()
        return FreeRdpConnectionProfile(
            preset = preset,
            frameRateFps = preset.targetFps,
            frameIntervalMs = preset.frameIntervalMs,
            fpsEnforcement =
                "Client-side FramePacer VSYNC period " +
                    String.format(Locale.US, "%.2f", preset.frameIntervalMs) + " ms " +
                    "(FreeRDP exposes no /fps argument; GFX pipeline paces server updates)",
            colorDepthArg = "/bpp:${preset.colorDepth}",
            codecArgs = if (preset.compressionCodec == "RFX") listOf("+rfx") else emptyList(),
            codecDescription = when (preset.compressionCodec) {
                "NONE" -> "Raw desktop updates; GFX ${preset.gpuPipelineArg.substringAfter(":")} " +
                    "negotiated by RDP8 pipeline for bounded bandwidth"
                "RFX" -> "RemoteFX (+rfx) legacy codec with GFX H.264 fallback"
                else -> "Legacy RLE/planar bitmap codec with ${preset.gpuPipelineArg} GFX variant"
            },
            compressionArgs = listOf("/compression-level:${preset.compressionLevel}"),
            networkAutoDetectArg = "/network:${if (preset.networkAutoDetect) "auto" else networkClassOf(preset)}",
            networkAutoDetect = preset.networkAutoDetect,
            gpuPipelineArg = if (preset.gpuPipelineEnabled) preset.gpuPipelineArg else "",
            gpuPipelineEnabled = preset.gpuPipelineEnabled,
            soundArgs = if (preset.soundEnabled) listOf("/audio-mode:0") else listOf("/audio-mode:2"),
            soundEnabled = preset.soundEnabled,
            socketRxBufferBytes = preset.socketRxBufferBytes,
            socketTxBufferBytes = preset.socketTxBufferBytes,
            tcpNoDelay = preset.tcpNoDelay,
            telemetryIntervalMs = preset.telemetryIntervalMs,
            reconnectBaseDelayMs = preset.reconnectBaseDelayMs,
            reconnectMaxDelayMs = preset.reconnectMaxDelayMs,
            reconnectMaxAttempts = preset.reconnectMaxAttempts,
            freerdpAutoReconnectEmitted = false,
            cliArgs = cliArgs
        )
    }

    private fun networkClassOf(preset: PerformancePreset): String = when (preset) {
        PerformancePreset.DATA_SAVER -> "modem"
        PerformancePreset.BATTERY_SAVER -> "broadband"
        else -> "auto"
    }

    /**
     * OS socket option mapping consumed by :core-rdp when the native FreeRDP file
     * descriptor becomes reachable (see handoff cross-module request: FreeRDP owns its
     * transport socket, so these options must either be applied post-`freerdp_connect`
     * on the native fd or negotiated through FreeRDP's own transport layer).
     */
    val socketOptionMappings: List<SocketOptionMapping> = listOf(
        SocketOptionMapping(
            tuningOption = "tcpNoDelay=true",
            osSocketOption = "TCP_NODELAY",
            freerdpSurface = "No CLI flag — OS layer on FreeRDP native fd",
            rationale = "Disables Nagle so fastpath input PDUs dispatch immediately (max input lag win)"
        ),
        SocketOptionMapping(
            tuningOption = "rxBufferBytes=131072 (128 KB)",
            osSocketOption = "SO_RCVBUF",
            freerdpSurface = "No CLI flag — OS layer on FreeRDP native fd",
            rationale = "Absorbs GFX/frame bursts without bufferbloat (queueing-delay bound)"
        ),
        SocketOptionMapping(
            tuningOption = "txBufferBytes=65536 (64 KB)",
            osSocketOption = "SO_SNDBUF",
            freerdpSurface = "No CLI flag — OS layer on FreeRDP native fd",
            rationale = "Bounds input+ack queue depth to keep outbound latency low"
        ),
        SocketOptionMapping(
            tuningOption = "ipTos=0x10 (IPTOS_LOWDELAY)",
            osSocketOption = "IP_TOS / IPV6_TCLASS",
            freerdpSurface = "Complements /network:auto|modem|broadband (RDP perf flags)",
            rationale = "Best-effort low-delay DSCP hint to the network; readback not guaranteed on Windows"
        ),
        SocketOptionMapping(
            tuningOption = "keepAlive=true",
            osSocketOption = "SO_KEEPALIVE",
            freerdpSurface = "/heartbeat (MS-RDPBCGR heartbeat PDUs, complementary)",
            rationale = "Dead-peer detection below the RDP layer feeds AutoReconnectManager sooner"
        ),
        SocketOptionMapping(
            tuningOption = "(not emitted)",
            osSocketOption = "n/a",
            freerdpSurface = "/auto-reconnect, /auto-reconnect-max-retries",
            rationale = "FreeRDP auto-reconnect intentionally disabled — client AutoReconnectManager " +
                "owns reconnection with full-jitter backoff to avoid dual reconnect loops"
        )
    )

    /** Renders the mapping as pipe-table rows for documentation/handoff output. */
    fun renderMarkdownTable(): String = buildString {
        appendLine("| Preset | FPS (client) | Codec | Compression | Network auto-detect | GPU pipeline | Sound | Rx/Tx buf | Telemetry | Reconnect (base/max/attempts) |")
        appendLine("|---|---|---|---|---|---|---|---|---|---|")
        all.forEach { p ->
            appendLine(
                "| ${p.preset.name} " +
                    "| ${p.frameRateFps} fps (FramePacer, ${String.format(Locale.US, "%.2f", p.frameIntervalMs)} ms) " +
                    "| ${p.preset.compressionCodec} ${p.codecArgs.joinToString(" ").ifBlank { "(native)" }} → `${p.gpuPipelineArg}` " +
                    "| ${p.compressionArgs.joinToString(" ")} " +
                    "| `${p.networkAutoDetectArg}` ${if (p.networkAutoDetect) "(auto)" else "(fixed class)"} " +
                    "| `${p.gpuPipelineArg}` " +
                    "| ${p.soundArgs.joinToString(" ")} " +
                    "| rx ${p.socketRxBufferBytes / 1024} KB / tx ${p.socketTxBufferBytes / 1024} KB, TCP_NODELAY=${p.tcpNoDelay} " +
                    "| ${p.telemetryIntervalMs} ms " +
                    "| ${p.reconnectBaseDelayMs}/${p.reconnectMaxDelayMs}/${p.reconnectMaxAttempts} |"
            )
        }
    }
}
