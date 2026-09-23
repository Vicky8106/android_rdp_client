package com.freerdp.feature.session.data

import com.freerdp.feature.session.model.RdpProfile
import com.freerdp.feature.session.repository.ProfileRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

class AtomicFileProfileRepository(
    private val storageFile: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ProfileRepository {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val mutex = Mutex()
    private val _profiles = MutableStateFlow<List<RdpProfile>>(emptyList())

    // Written only under `synchronized(this)`; volatile so that the fast-path
    // double-checked read in ensureLoaded() is visible across threads.
    @Volatile
    private var isInitialized = false

    override fun getAllProfiles(): Flow<List<RdpProfile>> {
        ensureLoaded()
        return _profiles.asStateFlow()
    }

    override suspend fun getProfile(id: String): RdpProfile? = withContext(ioDispatcher) {
        mutex.withLock {
            ensureLoaded()
            _profiles.value.find { it.id == id }
        }
    }

    override suspend fun saveProfile(profile: RdpProfile) = withContext(ioDispatcher) {
        mutex.withLock {
            ensureLoaded()
            val currentList = _profiles.value.toMutableList()
            val existingIndex = currentList.indexOfFirst { it.id == profile.id }
            val profileToSave = profile.copy(updatedAt = System.currentTimeMillis())

            if (existingIndex >= 0) {
                currentList[existingIndex] = profileToSave
            } else {
                currentList.add(profileToSave)
            }

            writeProfilesAtomically(currentList)
            _profiles.value = currentList
        }
    }

    override suspend fun deleteProfile(id: String): Boolean = withContext(ioDispatcher) {
        mutex.withLock {
            ensureLoaded()
            val currentList = _profiles.value.toMutableList()
            val removed = currentList.removeAll { it.id == id }
            if (removed) {
                writeProfilesAtomically(currentList)
                _profiles.value = currentList
            }
            removed
        }
    }

    override suspend fun duplicateProfile(id: String): RdpProfile? = withContext(ioDispatcher) {
        mutex.withLock {
            ensureLoaded()
            val original = _profiles.value.find { it.id == id } ?: return@withContext null
            val now = System.currentTimeMillis()
            val duplicate = original.copy(
                id = UUID.randomUUID().toString(),
                label = "${original.label} (Copy)",
                createdAt = now,
                updatedAt = now
            )
            val currentList = _profiles.value.toMutableList()
            currentList.add(duplicate)
            writeProfilesAtomically(currentList)
            _profiles.value = currentList
            duplicate
        }
    }

    override suspend fun clearAll() = withContext(ioDispatcher) {
        mutex.withLock {
            writeProfilesAtomically(emptyList())
            _profiles.value = emptyList()
        }
    }

    /**
     * Loads the store exactly once. Safe to call concurrently from the
     * non-suspend [getAllProfiles] path and from suspend operations that already
     * hold [mutex]: the file load itself is serialized by a double-checked
     * monitor so the on-disk file is never read while a recovery/write is in
     * progress on another thread.
     */
    private fun ensureLoaded() {
        if (!isInitialized) {
            synchronized(this) {
                if (!isInitialized) {
                    val loaded = loadFromFile()
                    _profiles.value = loaded
                    isInitialized = true
                }
            }
        }
    }

    private fun loadFromFile(): List<RdpProfile> {
        if (!storageFile.exists()) {
            return emptyList()
        }

        val rawText = try {
            storageFile.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            return recoverCorruptFile()
        }

        if (rawText.isBlank()) {
            return emptyList()
        }

        return try {
            json.decodeFromString<List<RdpProfile>>(rawText)
        } catch (e: Exception) {
            recoverCorruptFile()
        }
    }

    private fun recoverCorruptFile(): List<RdpProfile> {
        val parent = storageFile.parentFile ?: File(".")
        try {
            val corruptBak = File(parent, "${storageFile.name}.corrupt.bak")
            if (storageFile.exists()) {
                storageFile.copyTo(corruptBak, overwrite = true)
            }
        } catch (_: Exception) {
        }

        val backupFile = File(parent, "${storageFile.name}.bak")
        if (backupFile.exists()) {
            try {
                val backupText = backupFile.readText(Charsets.UTF_8)
                if (backupText.isNotBlank()) {
                    val restored = json.decodeFromString<List<RdpProfile>>(backupText)
                    writeProfilesDirect(backupText)
                    return restored
                }
            } catch (_: Exception) {
            }
        }

        return emptyList()
    }

    private fun writeProfilesAtomically(profiles: List<RdpProfile>) {
        val parent = storageFile.parentFile ?: File(".")
        if (!parent.exists()) {
            parent.mkdirs()
        }

        val jsonString = json.encodeToString(profiles)
        val jsonBytes = jsonString.toByteArray(Charsets.UTF_8)

        // Maintain a backup of last good state
        try {
            val backupFile = File(parent, "${storageFile.name}.bak")
            if (storageFile.exists() && storageFile.length() > 0) {
                storageFile.copyTo(backupFile, overwrite = true)
            } else {
                backupFile.writeBytes(jsonBytes)
            }
        } catch (_: Exception) {
        }

        val tempFile = File.createTempFile("profiles_", ".tmp", parent)
        try {
            FileOutputStream(tempFile).use { fos ->
                fos.write(jsonBytes)
                fos.flush()
                fos.fd.sync()
            }

            try {
                Files.move(
                    tempFile.toPath(),
                    storageFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: Exception) {
                if (!tempFile.renameTo(storageFile)) {
                    tempFile.copyTo(storageFile, overwrite = true)
                    tempFile.delete()
                }
            }
        } finally {
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }

    private fun writeProfilesDirect(jsonText: String) {
        val parent = storageFile.parentFile ?: File(".")
        if (!parent.exists()) {
            parent.mkdirs()
        }
        val tempFile = File.createTempFile("profiles_restore_", ".tmp", parent)
        try {
            tempFile.writeText(jsonText, Charsets.UTF_8)
            if (!tempFile.renameTo(storageFile)) {
                tempFile.copyTo(storageFile, overwrite = true)
                tempFile.delete()
            }
        } finally {
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }
}
