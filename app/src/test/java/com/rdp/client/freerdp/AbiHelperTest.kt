package com.rdp.client.freerdp

import org.junit.Assert.*
import org.junit.Test

class AbiHelperTest {

    @Test
    fun testSupportedAbisConfiguration() {
        assertEquals(2, AbiHelper.SUPPORTED_ABIS.size)
        assertTrue(AbiHelper.SUPPORTED_ABIS.contains("arm64-v8a"))
        assertTrue(AbiHelper.SUPPORTED_ABIS.contains("x86_64"))
    }

    @Test
    fun testArchitectureReportGeneration() {
        val report = AbiHelper.getArchitectureReport()
        assertNotNull(report)
        assertTrue(report.contains("Primary ABI"))
        assertTrue(report.contains("Supported ABIs"))
        assertTrue(report.contains("64-bit"))
    }

    @Test
    fun testPrimaryAbiNotNull() {
        val primary = AbiHelper.getPrimaryAbi()
        assertNotNull(primary)
        assertTrue(primary.isNotEmpty())
    }
}
