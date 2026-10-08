package com.rdp.client.validator

import com.google.common.truth.Truth.assertThat
import com.rdp.client.model.ResolutionMode
import org.junit.Test

class ProfileValidatorTest {

    @Test
    fun validateHost_validHost_returnsValid() {
        val res = ProfileValidator.validateHost("192.168.1.50")
        assertThat(res.isValid).isTrue()

        val fqdnRes = ProfileValidator.validateHost("rdp.corp.company.com")
        assertThat(fqdnRes.isValid).isTrue()
    }

    @Test
    fun validateHost_emptyHost_returnsError() {
        val res = ProfileValidator.validateHost("  ")
        assertThat(res.isValid).isFalse()
        assertThat(res.errorMessage).contains("Host address is required")
    }

    @Test
    fun validateHost_containsWhitespace_returnsError() {
        val res = ProfileValidator.validateHost("192.168.1. 50")
        assertThat(res.isValid).isFalse()
        assertThat(res.errorMessage).contains("whitespace")
    }

    @Test
    fun validatePort_validPort_returnsValid() {
        assertThat(ProfileValidator.validatePort(3389).isValid).isTrue()
        assertThat(ProfileValidator.validatePort(1).isValid).isTrue()
        assertThat(ProfileValidator.validatePort(65535).isValid).isTrue()
    }

    @Test
    fun validatePort_outOfRange_returnsError() {
        assertThat(ProfileValidator.validatePort(0).isValid).isFalse()
        assertThat(ProfileValidator.validatePort(65536).isValid).isFalse()
        assertThat(ProfileValidator.validatePort(-1).isValid).isFalse()
    }

    @Test
    fun validateCustomResolution_withinBounds_returnsValid() {
        val (w, h) = ProfileValidator.validateCustomResolution("1920", "1080")
        assertThat(w.isValid).isTrue()
        assertThat(h.isValid).isTrue()
    }

    @Test
    fun validateCustomResolution_belowMinimum_returnsError() {
        val (w, h) = ProfileValidator.validateCustomResolution("320", "240")
        assertThat(w.isValid).isFalse()
        assertThat(h.isValid).isFalse()
    }

    @Test
    fun validateCustomResolution_aboveMaximum_returnsError() {
        val (w, h) = ProfileValidator.validateCustomResolution("9000", "9000")
        assertThat(w.isValid).isFalse()
        assertThat(h.isValid).isFalse()
    }

    @Test
    fun validateMacAddress_validFormats_returnsValid() {
        assertThat(ProfileValidator.validateMacAddress("00:11:22:33:44:55").isValid).isTrue()
        assertThat(ProfileValidator.validateMacAddress("AA-BB-CC-DD-EE-FF").isValid).isTrue()
        assertThat(ProfileValidator.validateMacAddress("a1:b2:c3:d4:e5:f6").isValid).isTrue()
    }

    @Test
    fun validateMacAddress_invalidFormat_returnsError() {
        assertThat(ProfileValidator.validateMacAddress("00:11:22:33:44").isValid).isFalse()
        assertThat(ProfileValidator.validateMacAddress("invalid-mac").isValid).isFalse()
        assertThat(ProfileValidator.validateMacAddress("").isValid).isFalse()
    }

    @Test
    fun validateBroadcastIp_validIp_returnsValid() {
        assertThat(ProfileValidator.validateBroadcastIp("255.255.255.255").isValid).isTrue()
        assertThat(ProfileValidator.validateBroadcastIp("192.168.1.255").isValid).isTrue()
    }

    @Test
    fun validateBroadcastIp_invalidIp_returnsError() {
        assertThat(ProfileValidator.validateBroadcastIp("999.999.999.999").isValid).isFalse()
        assertThat(ProfileValidator.validateBroadcastIp("invalid-ip").isValid).isFalse()
        assertThat(ProfileValidator.validateBroadcastIp("").isValid).isFalse()
    }

    @Test
    fun validateProfile_fullValidForm_returnsValid() {
        val form = ProfileValidator.ProfileFormState(
            name = "Office PC",
            host = "10.0.0.15",
            portStr = "3389",
            enableGateway = true,
            gatewayHost = "gw.corp.com",
            gatewayPortStr = "443",
            enableWol = true,
            wolMac = "00:11:22:33:44:55",
            wolBroadcast = "10.0.0.255",
            wolPortStr = "9"
        )
        val res = ProfileValidator.validateProfile(form)
        assertThat(res.isValid).isTrue()
        assertThat(res.errors).isEmpty()
    }

    @Test
    fun validateProfile_invalidFields_populatesErrorsMap() {
        val form = ProfileValidator.ProfileFormState(
            name = "Broken PC",
            host = "",
            portStr = "99999",
            resolutionMode = ResolutionMode.CUSTOM,
            customWidthStr = "100",
            customHeightStr = "100",
            enableGateway = true,
            gatewayHost = "",
            gatewayPortStr = "0",
            enableWol = true,
            wolMac = "bad",
            wolBroadcast = "bad",
            wolPortStr = "0"
        )
        val res = ProfileValidator.validateProfile(form)
        assertThat(res.isValid).isFalse()
        assertThat(res.errors).containsKey(FormField.HOST)
        assertThat(res.errors).containsKey(FormField.PORT)
        assertThat(res.errors).containsKey(FormField.CUSTOM_WIDTH)
        assertThat(res.errors).containsKey(FormField.CUSTOM_HEIGHT)
        assertThat(res.errors).containsKey(FormField.GATEWAY_HOST)
        assertThat(res.errors).containsKey(FormField.GATEWAY_PORT)
        assertThat(res.errors).containsKey(FormField.WOL_MAC)
        assertThat(res.errors).containsKey(FormField.WOL_BROADCAST)
        assertThat(res.errors).containsKey(FormField.WOL_PORT)
    }
}
