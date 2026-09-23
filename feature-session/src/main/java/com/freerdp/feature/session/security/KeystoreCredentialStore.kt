package com.freerdp.feature.session.security

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM credential vault implementing the [CredentialStore] contract (4 methods,
 * unchanged) with layered key custody:
 *
 * 1. **Primary** — [EncryptedSharedPreferences] under an AndroidKeyStore [MasterKey]
 *    (AES256-SIV key-name encryption, AES256-GCM value encryption).
 * 2. **Fallback** — when EncryptedSharedPreferences init fails but the platform
 *    `AndroidKeyStore` provider is still available: a **non-exportable** AES-256 key
 *    generated in the AndroidKeyStore (alias `<masterKeyAlias>_fallback_`); only the
 *    ciphertext is written to plain SharedPreferences — the key material never leaves
 *    the hardware-backed Keystore and is never written to disk.
 * 3. **Terminal fallback (JVM/Robolectric — no AndroidKeyStore provider at all)** — a
 *    process-local, in-memory AES-256 key. The key is NEVER persisted anywhere (the
 *    former design's `vault_master_key_b64` base64-plaintext-key entry is actively
 *    PURGED on vault init), every backing store carries the unmistakable
 *    [DEGRADED_MARKER_KEY] entry, and [vaultDegraded] flips to `true` so the app can
 *    warn the user.
 *
 * Residual risk, layer 3 (the JVM/test path): secrets do not survive process death —
 * reads resolve to `null` and the user is re-prompted (fail-closed, never plaintext);
 * recovering plaintext requires process memory, not merely the prefs file; and the
 * degradation is only observable through [vaultDegraded]/[DEGRADED_MARKER_KEY] — UI
 * must surface that flag to honor "secure-by-default credentials". Residual risk,
 * layer 2: ciphertext and key *handles* sit in plain prefs (confidentiality rests on
 * the non-exportable Keystore key), and value encryption no longer hides entry names.
 *
 * The legacy plaintext-key entries written by pre-hardening installs are unusable by
 * this code by design: their secrets are treated as unreadable (null → re-prompt).
 */
class KeystoreCredentialStore(
    private val context: Context,
    private val prefFileName: String = "secure_rdp_vault",
    private val customMasterKeyAlias: String = "_rdp_master_key_",
    sharedPreferences: SharedPreferences? = null
) : CredentialStore {

    private interface VaultDelegate {
        fun saveSecret(profileId: String, secret: CharArray)
        fun getSecret(profileId: String): CharArray?
        fun deleteSecret(profileId: String)
        fun clearAll()
    }

    private val _vaultDegraded = MutableStateFlow(false)

    /**
     * `true` once the primary [EncryptedSharedPreferences] path has failed and the
     * fallback vault is in use (layers 2/3 above). The app should observe this flow
     * and warn the user that credential protection is reduced. Log-free: no `Log.*`
     * call is ever made from this class.
     */
    val vaultDegraded: StateFlow<Boolean> = _vaultDegraded.asStateFlow()

    private val delegate: VaultDelegate by lazy {
        try {
            val masterKey = MasterKey.Builder(context, customMasterKeyAlias)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val encPrefs = sharedPreferences ?: EncryptedSharedPreferences.create(
                context,
                prefFileName,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            EncryptedSharedPrefsVault(encPrefs)
        } catch (e: Exception) {
            // AndroidKeyStore hardware provider is absent in local JVM/Robolectric test
            // environments (and can fail on locked-down devices). Fall back to a genuine
            // AES-256-GCM vault: AndroidKeyStore non-exportable key when the provider
            // exists anyway, otherwise a never-persisted in-memory key — and surface the
            // degradation through [vaultDegraded] instead of downgrading silently.
            _vaultDegraded.value = true
            Aes256GcmFallbackVault(context, prefFileName, sharedPreferences, customMasterKeyAlias)
        }
    }

    override fun saveSecret(profileId: String, secret: CharArray) {
        delegate.saveSecret(profileId, secret)
    }

    override fun getSecret(profileId: String): CharArray? {
        return delegate.getSecret(profileId)
    }

    override fun deleteSecret(profileId: String) {
        delegate.deleteSecret(profileId)
    }

    override fun clearAll() {
        delegate.clearAll()
    }

    private class EncryptedSharedPrefsVault(
        private val encryptedPrefs: SharedPreferences
    ) : VaultDelegate {

        override fun saveSecret(profileId: String, secret: CharArray) {
            val bytes = charArrayToByteArray(secret)
            try {
                val encodedSecret = Base64.encodeToString(bytes, Base64.NO_WRAP)
                encryptedPrefs.edit().putString(vaultKey(profileId), encodedSecret).commit()
            } finally {
                Arrays.fill(bytes, 0.toByte())
            }
        }

        override fun getSecret(profileId: String): CharArray? {
            return try {
                val encoded = encryptedPrefs.getString(vaultKey(profileId), null) ?: return null
                val bytes = Base64.decode(encoded, Base64.NO_WRAP)
                try {
                    byteArrayToCharArray(bytes)
                } finally {
                    Arrays.fill(bytes, 0.toByte())
                }
            } catch (e: Exception) {
                // Missing, tampered or unreadable entries resolve to null; the vault never throws.
                null
            }
        }

        override fun deleteSecret(profileId: String) {
            encryptedPrefs.edit().remove(vaultKey(profileId)).commit()
        }

        override fun clearAll() {
            encryptedPrefs.edit().clear().commit()
        }

        private fun vaultKey(profileId: String): String = "secret_$profileId"
    }

    /**
     * Fallback vault (layers 2 & 3 of the class KDoc). Key custody, in order:
     *
     * 1. A **non-exportable AndroidKeyStore AES-256 key** whenever the platform
     *    `AndroidKeyStore` provider is available — even though
     *    [EncryptedSharedPreferences] itself just failed — so only ciphertext is ever
     *    written to the prefs file.
     * 2. Otherwise a **process-local in-memory AES-256 key that is never persisted**
     *    (no plaintext key at rest, ever). Legacy `vault_master_key_b64` entries are
     *    purged on init, and the backing store is stamped with [DEGRADED_MARKER_KEY]
     *    so the degradation is unmistakable to anything reading the file.
     *
     * Secret values stay pure base64(iv‖ciphertext) — the marker is a separate prefs
     * ENTRY, so raw-entry parsing (e2e tamper tests included) is unaffected.
     * [KeystoreCredentialStore.vaultDegraded] flags this vault for the app.
     */
    private class Aes256GcmFallbackVault(
        context: Context,
        prefFileName: String,
        customPrefs: SharedPreferences?,
        masterKeyAlias: String
    ) : VaultDelegate {

        private val prefs: SharedPreferences = customPrefs ?: context.getSharedPreferences(prefFileName, Context.MODE_PRIVATE)
        private val secureRandom = SecureRandom()

        /** Non-exportable platform-Keystore key, or null when the provider is absent (JVM). */
        private val keyStoreKey: SecretKey? = obtainAndroidKeyStoreKey(masterKeyAlias + "_fallback_")

        /** Terminal-fallback key: exists only in this process's memory, never on disk. */
        private val ephemeralKey: SecretKey? =
            if (keyStoreKey == null) {
                val keyGen = KeyGenerator.getInstance("AES")
                keyGen.init(256, secureRandom)
                keyGen.generateKey()
            } else {
                null
            }

        private val secretKey: SecretKey
            get() = keyStoreKey ?: ephemeralKey
                ?: throw IllegalStateException("fallback vault key unavailable")

        init {
            // Purge any legacy base64-plaintext master key and stamp the degradation
            // marker — both in one atomic commit, before the first read/write.
            prefs.edit()
                .remove(LEGACY_PLAINTEXT_KEY_PREF)
                .putString(DEGRADED_MARKER_KEY, DEGRADED_MARKER_VALUE)
                .commit()
        }

        private companion object {
            // Standard AES-GCM IV length; stored as the leading bytes of each value.
            const val IV_SIZE_BYTES = 12

            /** Pref key of the pre-hardening plaintext master-key entry (purged, never read). */
            const val LEGACY_PLAINTEXT_KEY_PREF = "vault_master_key_b64"
        }

        /**
         * Generates/reuses an AES-256 GCM key inside the AndroidKeyStore.
         * Returns null (→ terminal in-memory fallback) when the provider does not exist,
         * which is the case on plain JVM/Robolectric test environments.
         */
        private fun obtainAndroidKeyStoreKey(alias: String): SecretKey? = try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (!keyStore.containsAlias(alias)) {
                val generator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES,
                    "AndroidKeyStore"
                )
                generator.init(
                    KeyGenParameterSpec.Builder(
                        alias,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build()
                )
                generator.generateKey()
            }
            keyStore.getKey(alias, null) as? SecretKey
        } catch (t: Throwable) {
            null
        }

        override fun saveSecret(profileId: String, secret: CharArray) {
            val plaintextBytes = charArrayToByteArray(secret)
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                val iv: ByteArray
                if (keyStoreKey != null) {
                    // Keystore-owned keys generate the IV themselves; it is not exportable
                    // in advance but is available via cipher.iv after ENCRYPT init.
                    cipher.init(Cipher.ENCRYPT_MODE, secretKey)
                    iv = cipher.iv
                } else {
                    // Fresh random 96-bit nonce for EVERY encryption (AES-GCM uniqueness).
                    iv = ByteArray(IV_SIZE_BYTES)
                    secureRandom.nextBytes(iv)
                    cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
                }
                val ciphertext = cipher.doFinal(plaintextBytes)

                val combined = ByteArray(iv.size + ciphertext.size)
                System.arraycopy(iv, 0, combined, 0, iv.size)
                System.arraycopy(ciphertext, 0, combined, iv.size, ciphertext.size)

                val encoded = Base64.encodeToString(combined, Base64.NO_WRAP)
                prefs.edit().putString(vaultKey(profileId), encoded).commit()
            } finally {
                Arrays.fill(plaintextBytes, 0.toByte())
            }
        }

        override fun getSecret(profileId: String): CharArray? {
            return try {
                val encoded = prefs.getString(vaultKey(profileId), null) ?: return null
                val combined = Base64.decode(encoded, Base64.NO_WRAP)
                if (combined.size < IV_SIZE_BYTES) {
                    null
                } else {
                    val iv = ByteArray(IV_SIZE_BYTES)
                    val ciphertext = ByteArray(combined.size - IV_SIZE_BYTES)
                    System.arraycopy(combined, 0, iv, 0, IV_SIZE_BYTES)
                    System.arraycopy(combined, IV_SIZE_BYTES, ciphertext, 0, ciphertext.size)

                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
                    val decryptedBytes = cipher.doFinal(ciphertext)
                    try {
                        byteArrayToCharArray(decryptedBytes)
                    } finally {
                        Arrays.fill(decryptedBytes, 0.toByte())
                    }
                }
            } catch (e: Exception) {
                // Missing, tampered or unreadable entries resolve to null; the vault never throws.
                null
            }
        }

        override fun deleteSecret(profileId: String) {
            prefs.edit().remove(vaultKey(profileId)).commit()
        }

        override fun clearAll() {
            val editor = prefs.edit()
            prefs.all.keys.forEach { key ->
                if (key.startsWith("secret_")) {
                    editor.remove(key)
                }
            }
            // Note: the degradation marker and (purged) legacy key are vault STATE, not
            // secrets — the marker intentionally survives clearAll.
            editor.commit()
        }

        private fun vaultKey(profileId: String): String = "secret_$profileId"
    }

    companion object {
        /**
         * Unmistakable degradation marker ENTRY written into the backing prefs file by
         * the fallback vault (its value names the layer and points at the class KDoc).
         * Absent from the primary [EncryptedSharedPreferences] path.
         */
        internal const val DEGRADED_MARKER_KEY = "vault_degraded_mode"
        internal const val DEGRADED_MARKER_VALUE =
            "DEGRADED_VAULT_V1: primary EncryptedSharedPreferences path unavailable — " +
                "see KeystoreCredentialStore KDoc for residual risk"

        fun charArrayToByteArray(chars: CharArray): ByteArray {
            val bytes = ByteArray(chars.size * 2)
            for (i in chars.indices) {
                val code = chars[i].code
                bytes[i * 2] = (code ushr 8).toByte()
                bytes[i * 2 + 1] = (code and 0xFF).toByte()
            }
            return bytes
        }

        fun byteArrayToCharArray(bytes: ByteArray): CharArray {
            val chars = CharArray(bytes.size / 2)
            for (i in chars.indices) {
                val hi = (bytes[i * 2].toInt() and 0xFF) shl 8
                val lo = bytes[i * 2 + 1].toInt() and 0xFF
                chars[i] = (hi or lo).toChar()
            }
            return chars
        }

        fun wipeSecret(secret: CharArray) {
            Arrays.fill(secret, '\u0000')
        }
    }
}
