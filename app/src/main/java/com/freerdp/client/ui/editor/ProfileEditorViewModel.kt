package com.freerdp.client.ui.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.freerdp.core.engine.PerformancePreset
import com.freerdp.feature.session.CredentialStorageType
import com.freerdp.feature.session.CredentialStore
import com.freerdp.feature.session.KeystoreCredentialStore
import com.freerdp.feature.session.ProfileRepository
import com.freerdp.feature.session.RdpProfile
import com.freerdp.feature.session.SecurityConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Field-level validation messages (null = valid). Pure and directly unit-tested. */
data class FieldErrors(
    val label: String? = null,
    val hostname: String? = null,
    val port: String? = null,
    val domain: String? = null
) {
    val hasErrors: Boolean get() = label != null || hostname != null || port != null || domain != null
}

object ProfileValidation {

    private val HOST_REGEX = Regex("^[A-Za-z0-9._-]+$")
    private val DOMAIN_REGEX = Regex("^[A-Za-z0-9.-]+$")

    fun validateLabel(label: String): String? = when {
        label.isBlank() -> "A profile name is required"
        label.length > 64 -> "Keep the name under 64 characters"
        else -> null
    }

    fun validateHostname(host: String): String? = when {
        host.isBlank() -> "A host name or IP address is required"
        host.length > 253 -> "Host name is too long (max 253 characters)"
        "://" in host -> "Enter a bare host or IP — leave out http:// and the like"
        !HOST_REGEX.matches(host) -> "Only letters, digits, '.', '-' and '_' are allowed"
        else -> null
    }

    fun validatePort(port: String): String? {
        val value = port.toIntOrNull() ?: return "Port must be a number"
        return if (value in 1..65535) null else "Port must be between 1 and 65535"
    }

    /** Domain is optional; when present it must be a plausible AD/DNS domain. */
    fun validateDomain(domain: String): String? {
        if (domain.isBlank()) return null
        return when {
            domain.length > 253 -> "Domain is too long"
            !DOMAIN_REGEX.matches(domain) -> "Only letters, digits, '.' and '-' are allowed"
            else -> null
        }
    }

    fun validateAll(
        label: String,
        hostname: String,
        port: String,
        domain: String
    ): FieldErrors = FieldErrors(
        label = validateLabel(label),
        hostname = validateHostname(hostname),
        port = validatePort(port),
        domain = validateDomain(domain)
    )
}

/** Editor screen state (create/edit/delete of a connection profile). */
data class ProfileEditorState(
    val isNew: Boolean = true,
    val profileId: String? = null,
    val loading: Boolean = true,
    val saving: Boolean = false,
    val label: String = "",
    val hostname: String = "",
    val port: String = "3389",
    val username: String = "",
    val domain: String = "",
    val password: String = "",
    val performancePreset: PerformancePreset = PerformancePreset.BALANCED,
    val nlaEnabled: Boolean = true,
    val credentialStorageType: CredentialStorageType = CredentialStorageType.KEYSTORE_ENCRYPTED,
    val errors: FieldErrors = FieldErrors(),
    val error: String? = null,
    val saved: Boolean = false,
    val deleteConfirmVisible: Boolean = false,
    val deleted: Boolean = false
)

/**
 * Create/edit/delete logic for connection profiles.
 *
 * Passwords enter the Keystore through a [CharArray] (zeroed after use) and never touch
 * the JSON profile store; a blank password field on edit keeps the stored secret.
 */
class ProfileEditorViewModel(
    private val profileRepository: ProfileRepository,
    private val credentialStore: CredentialStore,
    private val profileId: String? = null,
    scopeOverride: CoroutineScope? = null
) : ViewModel() {

    private val scope: CoroutineScope = scopeOverride ?: viewModelScope

    private val _state = MutableStateFlow(ProfileEditorState(profileId = profileId, isNew = profileId == null))
    val state: StateFlow<ProfileEditorState> = _state.asStateFlow()

    init {
        if (profileId != null) {
            scope.launch {
                val existing = runCatching { profileRepository.getProfile(profileId) }.getOrNull()
                if (existing == null) {
                    _state.update {
                        it.copy(
                            loading = false,
                            error = "This profile no longer exists. It may have been deleted."
                        )
                    }
                } else {
                    _state.update {
                        it.copy(
                            loading = false,
                            isNew = false,
                            profileId = existing.id,
                            label = existing.label,
                            hostname = existing.hostname,
                            port = existing.port.toString(),
                            username = existing.username,
                            domain = existing.domain,
                            performancePreset = existing.performancePreset,
                            nlaEnabled = existing.securityConfig.nlaEnabled,
                            credentialStorageType = existing.credentialStorageType,
                            password = "" // never prefill a stored secret into the form
                        )
                    }
                }
            }
        } else {
            _state.update { it.copy(loading = false) }
        }
    }

    // ------------------------------------------------------------------ fields

    fun onLabel(value: String) = _state.update { it.copy(label = value, errors = it.errors.copy(label = null)) }

    fun onHostname(value: String) =
        _state.update { it.copy(hostname = value, errors = it.errors.copy(hostname = null)) }

    fun onPort(value: String) = _state.update {
        it.copy(port = value.filter(Char::isDigit).take(5), errors = it.errors.copy(port = null))
    }

    fun onUsername(value: String) = _state.update { it.copy(username = value) }

    fun onDomain(value: String) = _state.update {
        it.copy(domain = value, errors = it.errors.copy(domain = null))
    }

    fun onPassword(value: String) = _state.update { it.copy(password = value) }

    fun onPreset(value: PerformancePreset) = _state.update { it.copy(performancePreset = value) }

    fun onNlaChanged(enabled: Boolean) = _state.update { it.copy(nlaEnabled = enabled) }

    fun onCredentialStorageChanged(type: CredentialStorageType) = _state.update { it.copy(credentialStorageType = type) }

    fun clearError() = _state.update { it.copy(error = null) }

    // -------------------------------------------------------------------- save

    fun save() {
        val s = _state.value
        val errors = ProfileValidation.validateAll(s.label, s.hostname, s.port, s.domain)
        if (errors.hasErrors) {
            _state.update { it.copy(errors = errors) }
            return
        }
        _state.update { it.copy(saving = true, error = null) }
        scope.launch {
            try {
                val existing = s.profileId?.let { profileRepository.getProfile(it) }
                val base = existing ?: RdpProfile(label = s.label, hostname = s.hostname)
                val updated = base.copy(
                    label = s.label.trim(),
                    hostname = s.hostname.trim(),
                    port = s.port.toIntOrNull() ?: 3389,
                    username = s.username.trim(),
                    domain = s.domain.trim(),
                    credentialStorageType = s.credentialStorageType,
                    performancePreset = s.performancePreset,
                    securityConfig = base.securityConfig.copy(nlaEnabled = s.nlaEnabled)
                )
                profileRepository.saveProfile(updated)

                when {
                    s.password.isNotEmpty() -> {
                        val chars = s.password.toCharArray()
                        runCatching { credentialStore.saveSecret(updated.id, chars) }
                        KeystoreCredentialStore.wipeSecret(chars)
                    }
                    s.credentialStorageType == CredentialStorageType.NONE ->
                        runCatching { credentialStore.deleteSecret(updated.id) }
                    // blank password on edit => keep the previously stored secret untouched
                }

                _state.update { it.copy(saving = false, saved = true, profileId = updated.id) }
            } catch (t: Throwable) {
                _state.update {
                    it.copy(
                        saving = false,
                        error = "Couldn't save the profile: ${t.message ?: "storage error"}"
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------------ delete

    fun requestDelete() = _state.update { it.copy(deleteConfirmVisible = true) }

    fun cancelDelete() = _state.update { it.copy(deleteConfirmVisible = false) }

    fun confirmDelete() {
        val id = _state.value.profileId ?: run {
            _state.update { it.copy(deleted = true) }
            return
        }
        _state.update { it.copy(deleteConfirmVisible = false) }
        scope.launch {
            try {
                profileRepository.deleteProfile(id)
                credentialStore.deleteSecret(id)
                _state.update { it.copy(deleted = true, deleteConfirmVisible = false) }
            } catch (t: Throwable) {
                _state.update {
                    it.copy(
                        deleteConfirmVisible = false,
                        error = "Couldn't delete the profile: ${t.message ?: "storage error"}"
                    )
                }
            }
        }
    }
}
