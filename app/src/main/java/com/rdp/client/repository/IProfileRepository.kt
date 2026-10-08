package com.rdp.client.repository

import androidx.lifecycle.LiveData
import com.rdp.client.model.ProfileSortOrder
import com.rdp.client.model.ServerProfile
import kotlinx.coroutines.flow.Flow

/**
 * Clean architectural contract for server profile persistence and queries.
 */
interface IProfileRepository {
    val allProfiles: Flow<List<ServerProfile>>
    val allProfilesLiveData: LiveData<List<ServerProfile>>

    fun getProfilesSorted(sortOrder: ProfileSortOrder): Flow<List<ServerProfile>>
    fun searchProfiles(query: String): Flow<List<ServerProfile>>
    fun getRecentProfiles(limit: Int = 5): Flow<List<ServerProfile>>

    fun getProfileByIdLiveData(id: Long): LiveData<ServerProfile?>
    fun getProfileByIdFlow(id: Long): Flow<ServerProfile?>
    suspend fun getProfileById(id: Long): ServerProfile?

    suspend fun insertProfile(profile: ServerProfile): Long
    suspend fun updateProfile(profile: ServerProfile): Boolean
    suspend fun deleteProfile(profile: ServerProfile): Boolean
    suspend fun deleteProfileById(id: Long): Boolean
    suspend fun duplicateProfile(profileId: Long): Long?

    suspend fun recordConnection(profileId: Long)
    suspend fun saveOrUpdateQuickConnect(
        host: String,
        port: Int = 3389,
        username: String = "",
        password: String = "",
        domain: String = ""
    ): Long
    suspend fun getLatestQuickConnect(): ServerProfile?
    suspend fun updateZoom(id: Long, zoom1: Float, zoom2: Float)
}
