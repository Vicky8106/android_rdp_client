package com.freerdp.feature.session

import android.content.Context
import org.robolectric.RuntimeEnvironment
import com.freerdp.feature.session.security.KeystoreCredentialStore
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class KeystoreCredentialStoreTest {

    private lateinit var context: Context
    private lateinit var credentialStore: KeystoreCredentialStore

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        credentialStore = KeystoreCredentialStore(context, prefFileName = "test_secure_rdp_vault")
        credentialStore.clearAll()
    }

    @Test
    fun testSaveAndRetrieveSecret() {
        val profileId = "profile-uuid-101"
        val secret = "SecureEnterprisePass#2026!".toCharArray()

        credentialStore.saveSecret(profileId, secret)

        val retrieved = credentialStore.getSecret(profileId)
        assertNotNull("Retrieved secret should not be null", retrieved)
        assertArrayEquals("Retrieved secret must match original", secret, retrieved)
    }

    @Test
    fun testDeleteSecretRemovesStoredValue() {
        val profileId = "profile-uuid-delete"
        val secret = "PasswordToDelete".toCharArray()

        credentialStore.saveSecret(profileId, secret)
        assertNotNull(credentialStore.getSecret(profileId))

        credentialStore.deleteSecret(profileId)
        assertNull("Deleted secret should return null", credentialStore.getSecret(profileId))
    }

    @Test
    fun testOverwriteSecretUpdatesValue() {
        val profileId = "profile-uuid-update"
        val initialSecret = "InitialPassword123".toCharArray()
        val updatedSecret = "UpdatedSecret456".toCharArray()

        credentialStore.saveSecret(profileId, initialSecret)
        assertArrayEquals(initialSecret, credentialStore.getSecret(profileId))

        credentialStore.saveSecret(profileId, updatedSecret)
        assertArrayEquals(updatedSecret, credentialStore.getSecret(profileId))
    }

    @Test
    fun testClearAllRemovesAllStoredSecrets() {
        val profile1 = "profile-1"
        val profile2 = "profile-2"

        credentialStore.saveSecret(profile1, "SecretOne".toCharArray())
        credentialStore.saveSecret(profile2, "SecretTwo".toCharArray())

        assertNotNull(credentialStore.getSecret(profile1))
        assertNotNull(credentialStore.getSecret(profile2))

        credentialStore.clearAll()

        assertNull("All secrets should be wiped after clearAll", credentialStore.getSecret(profile1))
        assertNull("All secrets should be wiped after clearAll", credentialStore.getSecret(profile2))
    }

    @Test
    fun testNonExistentProfileReturnsNull() {
        assertNull(credentialStore.getSecret("non-existent-profile-id"))
    }

    @Test
    fun testCharArrayMemoryWipeZeroesMemory() {
        val secret = "SensitivePassword123".toCharArray()
        assertFalse(secret.all { it == '\u0000' })

        KeystoreCredentialStore.wipeSecret(secret)

        assertTrue("All characters in array must be zeroed to null character", secret.all { it == '\u0000' })
    }

    @Test
    fun testSpecialCharactersAndUnicodeSecrets() {
        val profileId = "profile-unicode"
        val unicodeSecret = "密码 🔑 Ünïcödë_Пароль_123!@#$%^&*()_+".toCharArray()

        credentialStore.saveSecret(profileId, unicodeSecret)

        val retrieved = credentialStore.getSecret(profileId)
        assertNotNull(retrieved)
        assertArrayEquals(unicodeSecret, retrieved)
    }

    @Test
    fun testResultBasedCompatibilityMethods() {
        val profileId = "profile-compat"
        val secret = "CompatSecret".toCharArray()

        val saveRes = credentialStore.saveCredential(profileId, secret)
        assertTrue(saveRes.isSuccess)

        val getRes = credentialStore.getCredential(profileId)
        assertTrue(getRes.isSuccess)
        assertArrayEquals(secret, getRes.getOrNull())

        val delRes = credentialStore.deleteCredential(profileId)
        assertTrue(delRes.isSuccess)
        assertNull(credentialStore.getCredential(profileId).getOrNull())
    }

    @Test
    fun testLongSecretRoundTrip() {
        val profileId = "profile-long"
        val longString = "A".repeat(1024)
        val longSecret = longString.toCharArray()

        credentialStore.saveSecret(profileId, longSecret)

        val retrieved = credentialStore.getSecret(profileId)
        assertNotNull(retrieved)
        assertEquals(1024, retrieved?.size)
        assertArrayEquals(longSecret, retrieved)
    }

    // ---------------------------------------------------------------------
    // Boundary & corner cases (profile isolation, mid-session deletion,
    // empty/single-char secrets, tampered entries, plaintext exposure)
    // ---------------------------------------------------------------------

    @Test
    fun testDeletingOneProfileNeverTouchesAnother() {
        credentialStore.saveSecret("profile-alpha", "AlphaPassword".toCharArray())
        credentialStore.saveSecret("profile-beta", "BetaPassword".toCharArray())

        // Mid-session deletion of one profile's credential
        credentialStore.deleteSecret("profile-alpha")

        assertNull("Deleted profile secret must be gone", credentialStore.getSecret("profile-alpha"))
        assertArrayEquals(
            "Sibling profile must be untouched by the delete",
            "BetaPassword".toCharArray(),
            credentialStore.getSecret("profile-beta")
        )

        // Re-creating the deleted profile must not disturb the sibling either
        credentialStore.saveSecret("profile-alpha", "AlphaPasswordRenewed".toCharArray())
        assertArrayEquals("AlphaPasswordRenewed".toCharArray(), credentialStore.getSecret("profile-alpha"))
        assertArrayEquals("BetaPassword".toCharArray(), credentialStore.getSecret("profile-beta"))
    }

    @Test
    fun testDeleteNonExistentProfileNeverThrows() {
        credentialStore.deleteSecret("never-existed")
        credentialStore.deleteSecret("")

        assertNull(credentialStore.getSecret("never-existed"))

        // Store must remain fully usable afterwards
        credentialStore.saveSecret("still-works", "Value".toCharArray())
        assertArrayEquals("Value".toCharArray(), credentialStore.getSecret("still-works"))
    }

    @Test
    fun testEmptySecretIsDistinctFromMissingSecret() {
        credentialStore.saveSecret("profile-empty", CharArray(0))

        val retrieved = credentialStore.getSecret("profile-empty")
        assertNotNull("An empty secret is stored state, not a missing key", retrieved)
        assertEquals(0, retrieved!!.size)

        // A profile that was never saved is still null
        assertNull(credentialStore.getSecret("profile-empty-never-saved"))
    }

    @Test
    fun testSingleCharacterSecretRoundTrip() {
        credentialStore.saveSecret("profile-single", charArrayOf('z'))
        assertArrayEquals(charArrayOf('z'), credentialStore.getSecret("profile-single"))
    }

    @Test
    fun testTamperedStoredValueReturnsNullInsteadOfThrowing() {
        credentialStore.saveSecret("profile-tampered", "OriginalSecret".toCharArray())

        // Corrupt the backing store directly with an undecodable payload
        context.getSharedPreferences("test_secure_rdp_vault", Context.MODE_PRIVATE)
            .edit()
            .putString("secret_profile-tampered", "!!!not-valid-base64-or-gcm!!!")
            .commit()

        // Must resolve to null rather than propagating a Base64/GCM exception
        assertNull(credentialStore.getSecret("profile-tampered"))
    }

    @Test
    fun testSecretNeverStoredInPlaintext() {
        val plaintext = "PlaintextProbe!2026-Secret"
        credentialStore.saveSecret("profile-plaintext", plaintext.toCharArray())

        // Round trip still works...
        assertArrayEquals(plaintext.toCharArray(), credentialStore.getSecret("profile-plaintext"))

        // ...while no raw preference entry contains the plaintext password
        val rawEntries = context.getSharedPreferences("test_secure_rdp_vault", Context.MODE_PRIVATE).all
        assertTrue("Vault backing store must contain entries", rawEntries.isNotEmpty())
        for ((key, value) in rawEntries) {
            val asString = value?.toString() ?: continue
            assertFalse(
                "Entry '$key' must not contain the plaintext secret",
                asString.contains(plaintext)
            )
        }
    }

    @Test
    fun testClearAllThenReuseStoreWorks() {
        credentialStore.saveSecret("profile-c1", "One".toCharArray())
        credentialStore.saveSecret("profile-c2", "Two".toCharArray())

        credentialStore.clearAll()

        assertNull(credentialStore.getSecret("profile-c1"))
        assertNull(credentialStore.getSecret("profile-c2"))

        // Re-using the vault after clearAll must work
        credentialStore.saveSecret("profile-c1", "Reborn".toCharArray())
        assertArrayEquals("Reborn".toCharArray(), credentialStore.getSecret("profile-c1"))
        assertNull(credentialStore.getSecret("profile-c2"))
    }

    @Test
    fun testRetrievedSecretIsCallerOwnedIndependentCopy() {
        credentialStore.saveSecret("profile-copy", "Independent".toCharArray())

        val retrieved = credentialStore.getSecret("profile-copy")
        assertNotNull(retrieved)

        // Wiping the caller's copy must not damage the stored ciphertext
        KeystoreCredentialStore.wipeSecret(retrieved!!)
        assertEquals("Wipe preserves the array length", "Independent".length, retrieved.size)
        assertTrue("Every character must be zeroed to NUL", retrieved.all { it.code == 0 })
        assertArrayEquals("Independent".toCharArray(), credentialStore.getSecret("profile-copy"))
    }

    // ---------------------------------------------------------------------
    // Hardening additions (reviewer_w2 MAJOR-2 + challenger_w2 S1/M3):
    // degraded-mode flag, no plaintext master key at rest, IV/nonce uniqueness.
    // ---------------------------------------------------------------------

    @Test
    fun testFallbackVaultRaisesDegradedFlagInsteadOfDowngradingSilently() {
        // setUp() forced the delegate via clearAll(): on JVM/Robolectric the
        // EncryptedSharedPreferences primary path cannot initialize, so the fallback
        // vault engages — it MUST surface that as vaultDegraded for the app to warn on.
        assertTrue(
            "fallback vault engagement must raise the vaultDegraded flag",
            credentialStore.vaultDegraded.value
        )
        // And the vault stays fully functional while flagged.
        credentialStore.saveSecret("profile-degraded-flag", "StillWorks".toCharArray())
        assertArrayEquals(
            "StillWorks".toCharArray(),
            credentialStore.getSecret("profile-degraded-flag")
        )
        assertTrue(credentialStore.vaultDegraded.value)
    }

    @Test
    fun testFallbackVaultNeverPersistsPlaintextMasterKeyAndMarksDegradation() {
        val prefs = context.getSharedPreferences("test_secure_rdp_vault", Context.MODE_PRIVATE)

        credentialStore.saveSecret("profile-custody", "CustodySecret".toCharArray())
        val entries = prefs.all

        // (a) The legacy base64-plaintext AES key entry must be ABSENT — no key at rest.
        assertFalse(
            "plaintext master key entry must never exist in the new design",
            entries.containsKey("vault_master_key_b64")
        )
        // (b) The degradation marker entry must be present and unmistakable.
        val marker = entries[KeystoreCredentialStore.DEGRADED_MARKER_KEY]
        assertNotNull("degraded-mode marker entry must be stamped into the vault", marker)
        assertTrue(
            "marker must carry the unmistakable DEGRADED_VAULT token",
            marker.toString().contains("DEGRADED_VAULT_V1")
        )
        // Round trip still works with the never-persisted key.
        assertArrayEquals("CustodySecret".toCharArray(), credentialStore.getSecret("profile-custody"))

        // (c) A legacy plaintext key planted by an older install is PURGED on vault init,
        //     and secrets encrypted under it are treated as unreadable (fail-closed null).
        prefs.edit()
            .putString("vault_master_key_b64", android.util.Base64.encodeToString(ByteArray(32) { 1 }, android.util.Base64.NO_WRAP))
            .commit()
        val secondStore = KeystoreCredentialStore(context, prefFileName = "test_secure_rdp_vault")
        secondStore.saveSecret("profile-after-upgrade", "PostUpgrade".toCharArray())
        assertFalse(
            "pre-existing plaintext master key must be purged on vault initialization",
            prefs.contains("vault_master_key_b64")
        )
        assertArrayEquals(
            "PostUpgrade".toCharArray(),
            secondStore.getSecret("profile-after-upgrade")
        )
        // Vault never throws on legacy ciphertext either — reads either decrypt (when key
        // custody is shared) or fail closed to null, but never surface an exception.
        val legacyRead = runCatching { secondStore.getSecret("profile-custody") }
        assertTrue("legacy-ciphertext read must never throw", legacyRead.isSuccess)
    }

    @Test
    fun testNonceIvIsUniqueAcrossOneHundredEncryptions() {
        // challenger_w2 survivor M3: a reused AES-GCM nonce under one key is a textbook
        // critical bug — this must fail if the IV ever stops being randomized.
        val prefs = context.getSharedPreferences("test_secure_rdp_vault", Context.MODE_PRIVATE)
        val seenIvs = mutableSetOf<String>()

        repeat(100) { i ->
            credentialStore.saveSecret("profile-nonce", "value-$i".toCharArray())
            val raw = prefs.getString("secret_profile-nonce", null)
            assertNotNull("stored entry must exist after save #$i", raw)
            val bytes = android.util.Base64.decode(raw, android.util.Base64.NO_WRAP)
            assertTrue("value must be iv(12) + ciphertext", bytes.size > 12)
            seenIvs.add(bytes.take(12).joinToString(","))
        }

        assertEquals(
            "all 100 AES-GCM IVs (leading 12 bytes) must be distinct — nonce reuse is forbidden",
            100,
            seenIvs.size
        )
        // And the ciphertexts remain decryptable under the same key.
        assertArrayEquals("value-99".toCharArray(), credentialStore.getSecret("profile-nonce"))
    }
}
