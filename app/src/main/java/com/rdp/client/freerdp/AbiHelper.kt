package com.rdp.client.freerdp

import android.os.Build

/**
 * Diagnostic status representing the result of native library dynamic loading.
 */
enum class NativeLoadStatus {
    /** Native library loaded and operational */
    LOADED,

    /** Host CPU architecture is unsupported (e.g. 32-bit armeabi-v7a or x86) */
    UNSUPPORTED_ABI,

    /** Native .so library missing from APK package */
    MISSING_LIBRARY,

    /** Linkage error encountered (e.g. missing transitive library) */
    LINKAGE_ERROR,

    /** Executing inside host JVM unit testing environment (e.g. JUnit on desktop) */
    JVM_TEST_ENVIRONMENT,

    /** Generic load failure */
    LOAD_FAILED
}

/**
 * Utility helper inspecting hardware CPU architectures and ABI compatibility.
 */
object AbiHelper {

    /** Primary supported ABIs for the Android RDP Client */
    val SUPPORTED_ABIS = listOf("arm64-v8a", "x86_64")

    /**
     * Returns true if device architecture supports 64-bit binaries.
     */
    fun is64BitDevice(): Boolean {
        return try {
            Build.SUPPORTED_64_BIT_ABIS != null && Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()
        } catch (e: Throwable) {
            false
        }
    }

    /**
     * Returns the primary ABI of the current hardware platform.
     */
    fun getPrimaryAbi(): String {
        return try {
            Build.SUPPORTED_ABIS?.firstOrNull() ?: "unknown"
        } catch (e: Throwable) {
            "unknown"
        }
    }

    /**
     * Checks if current platform supports one of the project's compiled ABIs.
     */
    fun isSupportedPlatform(): Boolean {
        val primary = getPrimaryAbi()
        return SUPPORTED_ABIS.contains(primary)
    }

    /**
     * Returns diagnostic description of platform architecture.
     */
    fun getArchitectureReport(): String {
        val primary = getPrimaryAbi()
        val allAbis = try {
            Build.SUPPORTED_ABIS?.joinToString(", ") ?: "none"
        } catch (e: Throwable) {
            "none"
        }
        val is64 = is64BitDevice()
        return "Primary ABI: $primary | Supported ABIs: [$allAbis] | 64-bit: $is64 | Compatible: ${isSupportedPlatform()}"
    }
}
