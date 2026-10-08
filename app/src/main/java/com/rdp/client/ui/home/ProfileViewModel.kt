package com.rdp.client.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rdp.client.model.ProfileSortOrder
import com.rdp.client.model.ServerProfile
import com.rdp.client.repository.IProfileRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Immutable UI state model for HomeActivity.
 */
data class ProfileUiState(
    val isLoading: Boolean = true,
    val profiles: List<ServerProfile> = emptyList(),
    val searchQuery: String = "",
    val sortOrder: ProfileSortOrder = ProfileSortOrder.NAME_ASC,
    val recentlyDeletedProfile: ServerProfile? = null
)

/**
 * Reactive ViewModel managing profile bookmarks, searching, sorting, and editing operations.
 */
class ProfileViewModel(
    private val repository: IProfileRepository
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _sortOrder = MutableStateFlow(ProfileSortOrder.NAME_ASC)
    val sortOrder: StateFlow<ProfileSortOrder> = _sortOrder.asStateFlow()

    private val _recentlyDeletedProfile = MutableStateFlow<ServerProfile?>(null)

    val uiState: StateFlow<ProfileUiState> = combine(
        repository.allProfiles,
        _searchQuery,
        _sortOrder,
        _recentlyDeletedProfile
    ) { allProfiles, query, sort, deleted ->
        // 1. Search Query Filtering
        val filtered = if (query.isBlank()) {
            allProfiles
        } else {
            val q = query.trim()
            allProfiles.filter { profile ->
                profile.name.contains(q, ignoreCase = true) ||
                profile.host.contains(q, ignoreCase = true) ||
                profile.domain.contains(q, ignoreCase = true) ||
                profile.username.contains(q, ignoreCase = true)
            }
        }

        // 2. Sorting
        val sorted = when (sort) {
            ProfileSortOrder.NAME_ASC -> filtered.sortedWith(
                compareBy(String.CASE_INSENSITIVE_ORDER) { it.getDisplayName() }
            )
            ProfileSortOrder.NAME_DESC -> filtered.sortedWith(
                compareByDescending(String.CASE_INSENSITIVE_ORDER) { it.getDisplayName() }
            )
            ProfileSortOrder.LAST_CONNECTED -> filtered.sortedByDescending { it.lastConnectedTimestamp }
            ProfileSortOrder.MOST_USED -> filtered.sortedByDescending { it.connectionCount }
            ProfileSortOrder.DATE_ADDED -> filtered.sortedByDescending { it.createdTimestamp }
        }

        ProfileUiState(
            isLoading = false,
            profiles = sorted,
            searchQuery = query,
            sortOrder = sort,
            recentlyDeletedProfile = deleted
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = ProfileUiState(isLoading = true)
    )

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setSortOrder(order: ProfileSortOrder) {
        _sortOrder.value = order
    }

    suspend fun getProfileById(id: Long): ServerProfile? {
        return repository.getProfileById(id)
    }

    suspend fun insertProfile(profile: ServerProfile): Long {
        return repository.insertProfile(profile)
    }

    suspend fun updateProfile(profile: ServerProfile): Boolean {
        return repository.updateProfile(profile)
    }

    fun deleteProfile(profile: ServerProfile) {
        viewModelScope.launch {
            _recentlyDeletedProfile.value = profile
            repository.deleteProfile(profile)
        }
    }

    fun undoDelete() {
        val profileToRestore = _recentlyDeletedProfile.value ?: return
        viewModelScope.launch {
            repository.insertProfile(profileToRestore)
            _recentlyDeletedProfile.value = null
        }
    }

    fun duplicateProfile(profile: ServerProfile) {
        viewModelScope.launch {
            repository.duplicateProfile(profile.id)
        }
    }

    fun recordConnection(profileId: Long) {
        viewModelScope.launch {
            repository.recordConnection(profileId)
        }
    }
}
