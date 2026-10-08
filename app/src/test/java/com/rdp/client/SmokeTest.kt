package com.rdp.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Minimal smoke test verifying JVM unit testing pipeline.
 */
class SmokeTest {

    @Test
    fun testApplicationPackageConstant() {
        val packageName = "com.rdp.client"
        assertEquals("com.rdp.client", packageName)
    }

    @Test
    fun testLibFreeRDPDeclaration() {
        val facade = com.rdp.client.freerdp.LibFreeRDP
        assertNotNull(facade)
    }
}
