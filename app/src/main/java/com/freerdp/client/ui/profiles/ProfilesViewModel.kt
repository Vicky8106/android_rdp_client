package com.freerdp.client.ui.profiles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.freerdp.feature.session.CredentialStore
import com.freerdp.feature.session.ProfileRepository
import com.freerdp.feature.session.RdpProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** UI state for the profile list screen (loading/empty/error/delete-confirm covered). */
data class ProfilesUiState(
    val isLoading: Boolean = true,
    val profiles: List<RdpProfile> = emptyList(),
    val error: String? = null,
    val deleteCandidate: RdpProfile? = null
)

/**
 * Drives the ProfileList screen: reacts to the repository's persisted flow (so the
 * list restores after process death straight from disk), runs the delete-confirmation
 * handshake and surfaces storage failures as user-readable errors instead of crashing.
 */
class ProfilesViewModel(
    private val profileRepository: ProfileRepository,
    private val credentialStore: CredentialStore,
    scopeOverride: CoroutineScope? = null
) : ViewModel() {

    private val scope: CoroutineScope = scopeOverride ?: viewModelScope

    private val _uiState = MutableStateFlow(ProfilesUiState())
    val uiState: StateFlow<ProfilesUiState> = _uiState.asStateFlow()

    init {
        scope.launch {
            try {
                profileRepository.getAllProfiles().collect { profiles ->
                    _uiState.update {
                        it.copy(isLoading = false, profiles = profiles, error = null)
                    }
                }
            } catch (t: Throwable) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = "Could not load saved profiles: ${t.message ?: "unknown error"}"
                    )
                }
            }
        }
    }

    fun requestDelete(profile: RdpProfile) {
        _uiState.update { it.copy(deleteCandidate = profile) }
    }

    fun cancelDelete() {
        _uiState.update { it.copy(deleteCandidate = null) }
    }

    fun confirmDelete() {
        val target = _uiState.value.deleteCandidate ?: return
        scope.launch {
            try {
                profileRepository.deleteProfile(target.id)
                credentialStore.deleteSecret(target.id)
                _uiState.update { it.copy(deleteCandidate = null) }
            } catch (t: Throwable) {
                _uiState.update {
                    it.copy(
                        deleteCandidate = null,
                        error = "Couldn't delete \"${target.label}\": ${t.message ?: "storage error"}"
                    )
                }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    /** One-tap connect bookkeeping: refreshes the profile's last-connected timestamp. */
    fun markConnected(profile: RdpProfile) {
        scope.launch {
            runCatching {
                profileRepository.saveProfile(profile.copy(lastConnectedTimestamp = System.currentTimeMillis()))
            }
        }
    }
}
