package com.freerdp.client.unit

import com.freerdp.client.ui.editor.ProfileValidation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Host/port/domain/label validation matrix for the profile editor. */
class ProfileValidationTest {

    // ---------------------------------------------------------------- labels

    @Test
    fun labelIsRequired() {
        assertNotNull(ProfileValidation.validateLabel(""))
        assertNotNull(ProfileValidation.validateLabel("   "))
    }

    @Test
    fun labelAcceptsNormalNamesAndRejectsOverlong() {
        assertNull(ProfileValidation.validateLabel("Work desktop"))
        assertNull(ProfileValidation.validateLabel("сервер-01"))
        assertNotNull(ProfileValidation.validateLabel("x".repeat(65)))
    }

    // --------------------------------------------------------------- hostnames

    @Test
    fun hostnameRejectsBlankSpacesAndSchemes() {
        assertNotNull(ProfileValidation.validateHostname(""))
        assertNotNull(ProfileValidation.validateHostname("my host"))
        assertNotNull(ProfileValidation.validateHostname("http://example.com"))
        assertNotNull(ProfileValidation.validateHostname("exa/mple"))
        assertNotNull(ProfileValidation.validateHostname("host!"))
    }

    @Test
    fun hostnameAcceptsDnsNamesIpv4AndIpv6Like() {
        assertNull(ProfileValidation.validateHostname("workstation.corp.example"))
        assertNull(ProfileValidation.validateHostname("10.0.0.5"))
        assertNull(ProfileValidation.validateHostname("rdp-gw_1.internal"))
        assertNull(ProfileValidation.validateHostname("x".repeat(253)))
        assertNotNull(ProfileValidation.validateHostname("x".repeat(254)))
    }

    // -------------------------------------------------------------------- ports

    @Test
    fun portMustBeNumericWithinRange() {
        assertNotNull(ProfileValidation.validatePort(""))
        assertNotNull(ProfileValidation.validatePort("abc"))
        assertNotNull(ProfileValidation.validatePort("0"))
        assertNotNull(ProfileValidation.validatePort("65536"))
        assertNotNull(ProfileValidation.validatePort("-1"))
        assertNull(ProfileValidation.validatePort("1"))
        assertNull(ProfileValidation.validatePort("3389"))
        assertNull(ProfileValidation.validatePort("65535"))
    }

    // ------------------------------------------------------------------ domains

    @Test
    fun domainIsOptionalButStrictWhenPresent() {
        assertNull(ProfileValidation.validateDomain(""))
        assertNull(ProfileValidation.validateDomain("   "))
        assertNull(ProfileValidation.validateDomain("corp.example"))
        assertNotNull(ProfileValidation.validateDomain("bad domain"))
        assertNotNull(ProfileValidation.validateDomain("dom@in"))
        assertNotNull(ProfileValidation.validateDomain("d".repeat(254)))
    }

    // -------------------------------------------------------------- aggregation

    @Test
    fun validateAllReportsOnlyInvalidFields() {
        val clean = ProfileValidation.validateAll("PC", "host.example", "3389", "corp")
        assertNull(clean.label)
        assertNull(clean.hostname)
        assertNull(clean.port)
        assertNull(clean.domain)
        assertEquals(false, clean.hasErrors)

        val dirty = ProfileValidation.validateAll("", "http://x", "0", "a b")
        assertNotNull(dirty.label)
        assertNotNull(dirty.hostname)
        assertNotNull(dirty.port)
        assertNotNull(dirty.domain)
        assertEquals(true, dirty.hasErrors)
    }
}
