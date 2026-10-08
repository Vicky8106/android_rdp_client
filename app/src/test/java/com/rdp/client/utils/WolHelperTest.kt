package com.rdp.client.utils

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WolHelperTest {

    @Test
    fun testIsValidMac_colonSeparated() {
        assertThat(WolHelper.isValidMac("00:11:22:33:44:55")).isTrue()
        assertThat(WolHelper.isValidMac("AA:BB:CC:DD:EE:FF")).isTrue()
        assertThat(WolHelper.isValidMac("a1:b2:c3:d4:e5:f6")).isTrue()
    }

    @Test
    fun testIsValidMac_dashSeparated() {
        assertThat(WolHelper.isValidMac("00-11-22-33-44-55")).isTrue()
        assertThat(WolHelper.isValidMac("AA-BB-CC-DD-EE-FF")).isTrue()
    }

    @Test
    fun testIsValidMac_invalidFormats() {
        assertThat(WolHelper.isValidMac("00:11:22:33:44")).isFalse()
        assertThat(WolHelper.isValidMac("00:11:22:33:44:55:66")).isFalse()
        assertThat(WolHelper.isValidMac("ZZ:11:22:33:44:55")).isFalse()
        assertThat(WolHelper.isValidMac("")).isFalse()
    }

    @Test
    fun testParseMacBytes() {
        val bytes = WolHelper.parseMacBytes("00:11:22:AA:BB:CC")
        assertThat(bytes).hasLength(6)
        assertThat(bytes[0]).isEqualTo(0x00.toByte())
        assertThat(bytes[1]).isEqualTo(0x11.toByte())
        assertThat(bytes[2]).isEqualTo(0x22.toByte())
        assertThat(bytes[3]).isEqualTo(0xAA.toByte())
        assertThat(bytes[4]).isEqualTo(0xBB.toByte())
        assertThat(bytes[5]).isEqualTo(0xCC.toByte())
    }
}
