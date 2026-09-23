package com.freerdp.feature.session.repository

import com.freerdp.feature.session.model.RdpProfile
import kotlinx.coroutines.flow.Flow

interface ProfileRepository {
    fun getAllProfiles(): Flow<List<RdpProfile>>
    suspend fun getProfile(id: String): RdpProfile?
    suspend fun saveProfile(profile: RdpProfile)
    suspend fun deleteProfile(id: String): Boolean
    suspend fun duplicateProfile(id: String): RdpProfile?
    suspend fun clearAll()
}
