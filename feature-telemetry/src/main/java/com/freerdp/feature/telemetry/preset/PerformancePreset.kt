package com.freerdp.feature.telemetry.preset

import com.freerdp.feature.telemetry.network.LowLatencySocketConfig

/**
 * Codec/quality negotiation level for a preset (server codec selection hint).
 */
enum class CodecQualityLevel {
    /** Maximum fidelity/refresh: raw deltas + GFX H.264 4:4:4 when available. */
    ULTRA,
    /** Full fidelity on metered mobile: GFX H.264 4:2:0. */
    HIGH,
    /** Bandwidth-optimized: progressive/legacy RLE codec. */
    MEDIUM,
    /** Minimum refresh & color: low-power codec path with dynamic throttling. */
    LOW
}

/**
 * Bandwidth- and battery-aware performance presets.
 *
 * Configures frame rate caps, color depths, compression codecs, audio redirection,
 * low-latency socket buffers, telemetry cadence, reconnect aggressiveness, and
 * FreeRDP command-line arguments to adapt dynamically between high-fidelity Wi-Fi
 * desktop sessions and low-power / metered mobile environments.
 *
 * All tunables are concrete values consumed directly by:
 * - [com.freerdp.feature.telemetry.pacer.FramePacer] (targetFps -> vsync period)
 * - [LowLatencySocketConfig] (socketRx/txBufferBytes, tcpNoDelay)
 * - [com.freerdp.feature.telemetry.reconnect.AutoReconnectManagerImpl]
 *   (reconnectBaseDelayMs / reconnectMaxDelayMs / reconnectMaxAttempts)
 * - [com.freerdp.feature.telemetry.metrics.TelemetryCollector] (telemetryRateHz)
 * - FreeRDP connection flags (see `toFreeRdpCliArgs()` and [FreeRdpFlagMapping])
 */
enum class PerformancePreset(
    val targetFps: Int,
    val colorDepth: Int,
    val compressionCodec: String,
    val soundEnabled: Boolean,
    val wallpaperEnabled: Boolean,
    val fontSmoothingEnabled: Boolean,
    val fullWindowDragEnabled: Boolean,
    val menuAnimationsEnabled: Boolean,
    val telemetryRateHz: Double,
    val dynamicThrottling: Boolean,
    val socketRxBufferBytes: Int,
    val socketTxBufferBytes: Int,
    val tcpNoDelay: Boolean,
    val compressionLevel: Int,
    val networkAutoDetect: Boolean,
    val gpuPipelineEnabled: Boolean,
    val gpuPipelineArg: String,
    val reconnectBaseDelayMs: Long,
    val reconnectMaxDelayMs: Long,
    val reconnectMaxAttempts: Int,
    val qualityLevel: CodecQualityLevel
) {
    /**
     * 60 FPS, 32-bit color, raw deltas, sound disabled, maximum responsiveness.
     * Ideal for low-latency Wi-Fi / LAN networks where rendering speed is paramount.
     */
    ULTRA_LOW_LATENCY(
        targetFps = 60,
        colorDepth = 32,
        compressionCodec = "NONE",
        soundEnabled = false,
        wallpaperEnabled = false,
        fontSmoothingEnabled = true,
        fullWindowDragEnabled = false,
        menuAnimationsEnabled = false,
        telemetryRateHz = 10.0,
        dynamicThrottling = false,
        socketRxBufferBytes = 131072,
        socketTxBufferBytes = 65536,
        tcpNoDelay = true,
        compressionLevel = 1,
        networkAutoDetect = true,
        gpuPipelineEnabled = true,
        gpuPipelineArg = "/gfx:AVC444",
        reconnectBaseDelayMs = 500L,
        reconnectMaxDelayMs = 8_000L,
        reconnectMaxAttempts = 8,
        qualityLevel = CodecQualityLevel.ULTRA
    ),

    /**
     * 30 FPS, 16-bit color, FastPath RFX with GFX H.2644:2:0, sound enabled.
     * Optimized for unmetered 4G/5G mobile connections.
     */
    BALANCED_MOBILE(
        targetFps = 30,
        colorDepth = 16,
        compressionCodec = "RFX",
        soundEnabled = true,
        wallpaperEnabled = false,
        fontSmoothingEnabled = true,
        fullWindowDragEnabled = false,
        menuAnimationsEnabled = false,
        telemetryRateHz = 2.0,
        dynamicThrottling = false,
        socketRxBufferBytes = 131072,
        socketTxBufferBytes = 65536,
        tcpNoDelay = true,
        compressionLevel = 1,
        networkAutoDetect = true,
        gpuPipelineEnabled = true,
        gpuPipelineArg = "/gfx:AVC420",
        reconnectBaseDelayMs = 1_000L,
        reconnectMaxDelayMs = 30_000L,
        reconnectMaxAttempts = 5,
        qualityLevel = CodecQualityLevel.HIGH
    ),

    /**
     * 15 FPS, 8-bit color, high compression, wallpaper/themes disabled.
     * Minimizes network bandwidth usage on metered or high-latency connections.
     */
    DATA_SAVER(
        targetFps = 15,
        colorDepth = 8,
        compressionCodec = "RLE",
        soundEnabled = false,
        wallpaperEnabled = false,
        fontSmoothingEnabled = false,
        fullWindowDragEnabled = false,
        menuAnimationsEnabled = false,
        telemetryRateHz = 1.0,
        dynamicThrottling = true,
        socketRxBufferBytes = 65536,
        socketTxBufferBytes = 32768,
        tcpNoDelay = true,
        compressionLevel = 2,
        networkAutoDetect = false,
        gpuPipelineEnabled = true,
        gpuPipelineArg = "/gfx:progressive",
        reconnectBaseDelayMs = 2_000L,
        reconnectMaxDelayMs = 60_000L,
        reconnectMaxAttempts = 5,
        qualityLevel = CodecQualityLevel.MEDIUM
    ),

    /**
     * 15 FPS, 16-bit color, dynamic frame throttling when idle.
     * Minimizes GPU/CPU wakeups when battery is low or device is in power-saving mode.
     */
    BATTERY_SAVER(
        targetFps = 15,
        colorDepth = 16,
        compressionCodec = "RLE",
        soundEnabled = false,
        wallpaperEnabled = false,
        fontSmoothingEnabled = false,
        fullWindowDragEnabled = false,
        menuAnimationsEnabled = false,
        telemetryRateHz = 0.5,
        dynamicThrottling = true,
        socketRxBufferBytes = 65536,
        socketTxBufferBytes = 32768,
        tcpNoDelay = true,
        compressionLevel = 2,
        networkAutoDetect = false,
        gpuPipelineEnabled = true,
        gpuPipelineArg = "/gfx:AVC420",
        reconnectBaseDelayMs = 3_000L,
        reconnectMaxDelayMs = 60_000L,
        reconnectMaxAttempts = 3,
        qualityLevel = CodecQualityLevel.LOW
    );

    init {
        require(targetFps in 1..240) { "targetFps must be in 1..240, got $targetFps" }
        require(colorDepth in intArrayOf(4, 8, 15, 16, 24, 32)) { "Unsupported colorDepth $colorDepth" }
        require(telemetryRateHz > 0.0) { "telemetryRateHz must be > 0, got $telemetryRateHz" }
        require(socketRxBufferBytes in LowLatencySocketConfig.MIN_BUFFER_BYTES..LowLatencySocketConfig.MAX_BUFFER_BYTES)
        require(socketTxBufferBytes in LowLatencySocketConfig.MIN_BUFFER_BYTES..LowLatencySocketConfig.MAX_BUFFER_BYTES)
        require(compressionLevel in 0..2) { "compressionLevel must be in 0..2, got $compressionLevel" }
        require(reconnectBaseDelayMs >= 0L) { "reconnectBaseDelayMs must be >= 0" }
        require(reconnectMaxDelayMs >= reconnectBaseDelayMs) {
            "reconnectMaxDelayMs ($reconnectMaxDelayMs) must be >= reconnectBaseDelayMs ($reconnectBaseDelayMs)"
        }
        require(reconnectMaxAttempts >= 1) { "reconnectMaxAttempts must be >= 1, got $reconnectMaxAttempts" }
    }

    /** Frame interval implied by [targetFps] in milliseconds (e.g. 60 FPS -> 16.67 ms). */
    val frameIntervalMs: Double get() = 1000.0 / targetFps

    /** Telemetry publication cadence in milliseconds (e.g. 10 Hz -> 100 ms). */
    val telemetryIntervalMs: Long get() = (1000.0 / telemetryRateHz).toLong()

    /**
     * Materializes this preset's socket tuning for
     * [LowLatencySocketConfig.configureSocket] / [LowLatencySocketConfig.configureChannel].
     * Construction re-validates buffer ranges against the LowLatencySocketConfig bounds.
     */
    fun toSocketOptions(): LowLatencySocketConfig.TuningOptions =
        LowLatencySocketConfig.TuningOptions(
            tcpNoDelay = tcpNoDelay,
            rxBufferBytes = socketRxBufferBytes,
            txBufferBytes = socketTxBufferBytes,
            ipTos = LowLatencySocketConfig.IPTOS_LOWDELAY,
            keepAlive = LowLatencySocketConfig.DEFAULT_KEEPALIVE
        )

    /**
     * Generates standard FreeRDP command-line arguments corresponding to this preset.
     *
     * Covers: color depth (/bpp), network auto-detect (/network), codec (GFX pipeline
     * `/gfx:` + legacy `+rfx`), compression level, sound (/audio-mode), asynchronous
     * channels/update, and desktop-effects performance flags. See [FreeRdpFlagMapping]
     * for the structured table consumed by :core-rdp.
     */
    fun toFreeRdpCliArgs(): List<String> {
        val args = mutableListOf<String>()
        args.add("/bpp:$colorDepth")

        when (this) {
            ULTRA_LOW_LATENCY -> {
                args.addAll(
                    listOf(
                        "/network:auto",
                        "+async-channels",
                        "+async-update",
                        "-wallpaper",
                        "-themes",
                        "-menu-anims",
                        "-window-drag",
                        "-sound",
                        "/compression-level:1",
                        "/gfx:AVC444",
                        "/audio-mode:2"
                    )
                )
            }
            BALANCED_MOBILE -> {
                args.addAll(
                    listOf(
                        "/network:auto",
                        "+rfx",
                        "+async-channels",
                        "+async-update",
                        "+sound",
                        "/compression-level:1",
                        "/gfx:AVC420",
                        "/audio-mode:0"
                    )
                )
            }
            DATA_SAVER -> {
                args.addAll(
                    listOf(
                        "/network:modem",
                        "+compression",
                        "-wallpaper",
                        "-themes",
                        "-fonts",
                        "-sound",
                        "/compression-level:2",
                        "/gfx:progressive",
                        "/audio-mode:2"
                    )
                )
            }
            BATTERY_SAVER -> {
                args.addAll(
                    listOf(
                        "/network:broadband",
                        "+compression",
                        "-wallpaper",
                        "-themes",
                        "-sound",
                        "+async-update",
                        "/compression-level:2",
                        "/gfx:AVC420",
                        "/audio-mode:2"
                    )
                )
            }
        }
        return args
    }

    /**
     * Maps this telemetry performance preset to the core engine preset.
     */
    fun toCorePreset(): com.freerdp.core.engine.PerformancePreset {
        return when (this) {
            ULTRA_LOW_LATENCY -> com.freerdp.core.engine.PerformancePreset.ULTRA_LOW_LATENCY
            BALANCED_MOBILE -> com.freerdp.core.engine.PerformancePreset.BALANCED
            DATA_SAVER -> com.freerdp.core.engine.PerformancePreset.DATA_SAVER
            BATTERY_SAVER -> com.freerdp.core.engine.PerformancePreset.BATTERY_SAVER
        }
    }

    companion object {
        fun fromCorePreset(corePreset: com.freerdp.core.engine.PerformancePreset): PerformancePreset {
            return when (corePreset) {
                com.freerdp.core.engine.PerformancePreset.ULTRA_LOW_LATENCY -> ULTRA_LOW_LATENCY
                com.freerdp.core.engine.PerformancePreset.LOW_LATENCY -> ULTRA_LOW_LATENCY
                com.freerdp.core.engine.PerformancePreset.BALANCED -> BALANCED_MOBILE
                com.freerdp.core.engine.PerformancePreset.DATA_SAVER -> DATA_SAVER
                com.freerdp.core.engine.PerformancePreset.BATTERY_SAVER -> BATTERY_SAVER
            }
        }
    }
}
