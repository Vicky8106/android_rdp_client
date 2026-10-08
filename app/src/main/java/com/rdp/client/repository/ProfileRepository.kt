package com.rdp.client.repository

import androidx.lifecycle.LiveData
import com.rdp.client.model.ProfileSortOrder
import com.rdp.client.model.ServerProfile
import com.rdp.client.model.ServerProfileDao
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Concrete implementation of ProfileRepository backed by Room ServerProfileDao.
 */
class ProfileRepository(
    private val dao: ServerProfileDao,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : IProfileRepository {

    override val allProfiles: Flow<List<ServerProfile>> = dao.getAllProfilesFlow()

    override val allProfilesLiveData: LiveData<List<ServerProfile>> = dao.getAllProfilesLiveData()

    override fun getProfilesSorted(sortOrder: ProfileSortOrder): Flow<List<ServerProfile>> {
        return when (sortOrder) {
            ProfileSortOrder.NAME_ASC -> dao.getAllProfilesSortedByNameAsc()
            ProfileSortOrder.NAME_DESC -> dao.getAllProfilesSortedByNameDesc()
            ProfileSortOrder.LAST_CONNECTED -> dao.getAllProfilesSortedByLastConnected()
            ProfileSortOrder.MOST_USED -> dao.getAllProfilesSortedByMostUsed()
            ProfileSortOrder.DATE_ADDED -> dao.getAllProfilesSortedByDateAdded()
        }
    }

    override fun searchProfiles(query: String): Flow<List<ServerProfile>> {
        val trimmed = query.trim()
        return if (trimmed.isBlank()) {
            dao.getAllProfilesFlow()
        } else {
            dao.getProfilesMatchingQuery(trimmed)
        }
    }

    override fun getRecentProfiles(limit: Int): Flow<List<ServerProfile>> {
        return dao.getRecentProfiles(limit)
    }

    override fun getProfileByIdLiveData(id: Long): LiveData<ServerProfile?> {
        return dao.getProfileByIdLiveData(id)
    }

    override fun getProfileByIdFlow(id: Long): Flow<ServerProfile?> {
        return dao.getProfileByIdFlow(id)
    }

    override suspend fun getProfileById(id: Long): ServerProfile? = withContext(ioDispatcher) {
        dao.getProfileById(id)
    }

    override suspend fun insertProfile(profile: ServerProfile): Long = withContext(ioDispatcher) {
        dao.insert(profile)
    }

    override suspend fun updateProfile(profile: ServerProfile): Boolean = withContext(ioDispatcher) {
        dao.update(profile) > 0
    }

    override suspend fun deleteProfile(profile: ServerProfile): Boolean = withContext(ioDispatcher) {
        dao.delete(profile) > 0
    }

    override suspend fun deleteProfileById(id: Long): Boolean = withContext(ioDispatcher) {
        dao.deleteById(id) > 0
    }

    override suspend fun duplicateProfile(profileId: Long): Long? = withContext(ioDispatcher) {
        val original = dao.getProfileById(profileId) ?: return@withContext null
        val duplicated = original.copy(
            id = 0L,
            name = if (original.name.isNotBlank()) "Copy of ${original.name}" else "Copy of ${original.host}",
            lastConnectedTimestamp = 0L,
            connectionCount = 0,
            createdTimestamp = System.currentTimeMillis()
        )
        dao.insert(duplicated)
    }

    override suspend fun recordConnection(profileId: Long): Unit = withContext(ioDispatcher) {
        dao.recordConnection(profileId)
        Unit
    }

    override suspend fun saveOrUpdateQuickConnect(
        host: String,
        port: Int,
        username: String,
        password: String,
        domain: String
    ): Long = withContext(ioDispatcher) {
        val quickProfile = ServerProfile(
            name = if (username.isNotBlank()) "$username@$host" else if (port != 3389) "$host:$port" else host,
            host = host,
            port = port,
            username = username,
            password = password,
            domain = domain,
            isQuickConnect = true,
            lastConnectedTimestamp = System.currentTimeMillis()
        )
        dao.insert(quickProfile)
    }

    override suspend fun getLatestQuickConnect(): ServerProfile? = withContext(ioDispatcher) {
        dao.getLatestQuickConnectProfile()
    }

    override suspend fun updateZoom(id: Long, zoom1: Float, zoom2: Float): Unit = withContext(ioDispatcher) {
        dao.updateZoom(id, zoom1, zoom2)
        Unit
    }
}
