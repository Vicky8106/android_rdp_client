package com.rdp.client.model

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for ServerProfile persistence and queries.
 */
@Dao
interface ServerProfileDao {

    // -------------------------------------------------------------
    // Reactive Streams: Flow & LiveData
    // -------------------------------------------------------------

    /**
     * Observes all saved server bookmarks (excludes quick connect).
     * Ordered by user sortOrder ascending, then ID descending.
     */
    @Query("SELECT * FROM profiles WHERE isQuickConnect = 0 ORDER BY sortOrder ASC, id DESC")
    fun getAllProfilesFlow(): Flow<List<ServerProfile>>

    /**
     * LiveData stream of all bookmarks for Android Architecture Component observation.
     */
    @Query("SELECT * FROM profiles WHERE isQuickConnect = 0 ORDER BY sortOrder ASC, id DESC")
    fun getAllProfilesLiveData(): LiveData<List<ServerProfile>>

    // -------------------------------------------------------------
    // Sorting Queries (AVNC Parity)
    // -------------------------------------------------------------

    /**
     * Sorts bookmarks alphabetically by profile name (case-insensitive).
     */
    @Query("SELECT * FROM profiles WHERE isQuickConnect = 0 ORDER BY CASE WHEN name = '' THEN host ELSE name END COLLATE NOCASE ASC, id ASC")
    fun getAllProfilesSortedByNameAsc(): Flow<List<ServerProfile>>

    /**
     * Sorts bookmarks alphabetically descending.
     */
    @Query("SELECT * FROM profiles WHERE isQuickConnect = 0 ORDER BY CASE WHEN name = '' THEN host ELSE name END COLLATE NOCASE DESC, id DESC")
    fun getAllProfilesSortedByNameDesc(): Flow<List<ServerProfile>>

    /**
     * Sorts bookmarks by most recently connected first.
     */
    @Query("SELECT * FROM profiles WHERE isQuickConnect = 0 ORDER BY lastConnectedTimestamp DESC, id DESC")
    fun getAllProfilesSortedByLastConnected(): Flow<List<ServerProfile>>

    /**
     * Sorts bookmarks by total connection frequency (most used).
     */
    @Query("SELECT * FROM profiles WHERE isQuickConnect = 0 ORDER BY connectionCount DESC, id DESC")
    fun getAllProfilesSortedByMostUsed(): Flow<List<ServerProfile>>

    /**
     * Sorts bookmarks by date created / added.
     */
    @Query("SELECT * FROM profiles WHERE isQuickConnect = 0 ORDER BY createdTimestamp DESC, id DESC")
    fun getAllProfilesSortedByDateAdded(): Flow<List<ServerProfile>>

    // -------------------------------------------------------------
    // Search & Filter
    // -------------------------------------------------------------

    /**
     * Searches profiles matching query across name, host, domain, and username.
     */
    @Query("""
        SELECT * FROM profiles 
        WHERE isQuickConnect = 0 
          AND (name LIKE '%' || :query || '%' 
               OR host LIKE '%' || :query || '%' 
               OR domain LIKE '%' || :query || '%' 
               OR username LIKE '%' || :query || '%')
        ORDER BY CASE WHEN name = '' THEN host ELSE name END COLLATE NOCASE ASC
    """)
    fun getProfilesMatchingQuery(query: String): Flow<List<ServerProfile>>

    /**
     * Returns top N recently connected profiles.
     */
    @Query("SELECT * FROM profiles WHERE isQuickConnect = 0 AND lastConnectedTimestamp > 0 ORDER BY lastConnectedTimestamp DESC LIMIT :limit")
    fun getRecentProfiles(limit: Int = 5): Flow<List<ServerProfile>>

    // -------------------------------------------------------------
    // Single Profile Lookups
    // -------------------------------------------------------------

    /**
     * Retrieves profile by ID as LiveData.
     */
    @Query("SELECT * FROM profiles WHERE id = :id LIMIT 1")
    fun getProfileByIdLiveData(id: Long): LiveData<ServerProfile?>

    /**
     * Retrieves profile by ID as reactive Flow.
     */
    @Query("SELECT * FROM profiles WHERE id = :id LIMIT 1")
    fun getProfileByIdFlow(id: Long): Flow<ServerProfile?>

    /**
     * Direct suspend lookup by ID.
     */
    @Query("SELECT * FROM profiles WHERE id = :id LIMIT 1")
    suspend fun getProfileById(id: Long): ServerProfile?

    /**
     * Retrieves latest Quick Connect session configuration.
     */
    @Query("SELECT * FROM profiles WHERE isQuickConnect = 1 ORDER BY lastConnectedTimestamp DESC LIMIT 1")
    suspend fun getLatestQuickConnectProfile(): ServerProfile?

    /**
     * Returns total number of saved bookmark profiles.
     */
    @Query("SELECT COUNT(*) FROM profiles WHERE isQuickConnect = 0")
    fun getProfileCountFlow(): Flow<Int>

    @Query("SELECT COUNT(*) FROM profiles WHERE isQuickConnect = 0")
    suspend fun getProfileCount(): Int

    // -------------------------------------------------------------
    // CRUD Operations (Suspend)
    // -------------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(profile: ServerProfile): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(profiles: List<ServerProfile>): List<Long>

    @Update
    suspend fun update(profile: ServerProfile): Int

    @Delete
    suspend fun delete(profile: ServerProfile): Int

    @Query("DELETE FROM profiles WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    @Query("DELETE FROM profiles WHERE isQuickConnect = 0")
    suspend fun deleteAllProfiles(): Int

    @Query("DELETE FROM profiles WHERE isQuickConnect = 1")
    suspend fun deleteQuickConnectProfiles(): Int

    // -------------------------------------------------------------
    // In-Place State Updates
    // -------------------------------------------------------------

    /**
     * Records a successful connection: updates timestamp and increments connectionCount.
     */
    @Query("""
        UPDATE profiles 
        SET lastConnectedTimestamp = :timestamp, 
            connectionCount = connectionCount + 1 
        WHERE id = :id
    """)
    suspend fun recordConnection(id: Long, timestamp: Long = System.currentTimeMillis()): Int

    /**
     * Updates manual sort order index.
     */
    @Query("UPDATE profiles SET sortOrder = :sortOrder WHERE id = :id")
    suspend fun updateSortOrder(id: Long, sortOrder: Int): Int

    /**
     * Updates per-orientation zoom levels.
     */
    @Query("UPDATE profiles SET zoom1 = :zoom1, zoom2 = :zoom2 WHERE id = :id")
    suspend fun updateZoom(id: Long, zoom1: Float, zoom2: Float): Int
}
