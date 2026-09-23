package com.freerdp.core.engine

data class RdpConnectionConfig(
    val serverAddress: String,
    val port: Int = 3389,
    val username: String = "",
    val domain: String = "",
    val password: String = "",
    val width: Int = 1920,
    val height: Int = 1080,
    val colorDepth: Int = 32,
    val enableNla: Boolean = true,
    val enableTls: Boolean = true,
    val ignoreCertificate: Boolean = false,
    val enableClipboard: Boolean = true,
    val enableDynamicResolution: Boolean = true,
    val performancePreset: PerformancePreset = PerformancePreset.LOW_LATENCY
) {
    val host: String get() = serverAddress

    constructor(
        host: String,
        port: Int = 3389,
        username: String = "",
        domain: String = "",
        password: String = "",
        width: Int = 1920,
        height: Int = 1080,
        colorDepth: Int = 32,
        performancePreset: PerformancePreset = PerformancePreset.LOW_LATENCY,
        enableClipboard: Boolean = true,
        enableDynamicResize: Boolean = true
    ) : this(
        serverAddress = host,
        port = port,
        username = username,
        domain = domain,
        password = password,
        width = width,
        height = height,
        colorDepth = colorDepth,
        enableNla = true,
        enableTls = true,
        ignoreCertificate = false,
        enableClipboard = enableClipboard,
        enableDynamicResolution = enableDynamicResize,
        performancePreset = performancePreset
    )
}

enum class PerformancePreset {
    ULTRA_LOW_LATENCY,
    LOW_LATENCY,
    BALANCED,
    DATA_SAVER,
    BATTERY_SAVER
}
