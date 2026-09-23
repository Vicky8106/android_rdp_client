package com.freerdp.feature.session

import com.freerdp.core.engine.PerformancePreset
import com.freerdp.feature.session.data.AtomicFileProfileRepository
import com.freerdp.feature.session.model.CertValidationMode
import com.freerdp.feature.session.model.ColorDepth
import com.freerdp.feature.session.model.CredentialStorageType
import com.freerdp.feature.session.model.DisplayConfig
import com.freerdp.feature.session.model.NetworkConfig
import com.freerdp.feature.session.model.RdpProfile
import com.freerdp.feature.session.model.RedirectionConfig
import com.freerdp.feature.session.model.ResolutionMode
import com.freerdp.feature.session.model.ScalingMode
import com.freerdp.feature.session.model.SecurityConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProfileRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var storageFile: File
    private lateinit var repository: AtomicFileProfileRepository
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        storageFile = File(tempFolder.root, "profiles.json")
        repository = AtomicFileProfileRepository(storageFile, testDispatcher)
    }

    @After
    fun tearDown() {
        if (storageFile.exists()) {
            storageFile.delete()
        }
    }

    @Test
    fun testInitialProfilesListIsEmpty() = runTest(testDispatcher) {
        val profiles = repository.getAllProfiles().first()
        assertTrue("Initial profile list should be empty", profiles.isEmpty())
        assertNull("Non-existent profile ID should return null", repository.getProfile("non-existent"))
    }

    @Test
    fun testFullCrudLifecycle() = runTest(testDispatcher) {
        // 1. Create
        val profile = RdpProfile(
            label = "Primary Workstation",
            hostname = "10.0.0.42",
            port = 3389,
            username = "alice",
            domain = "CORP",
            credentialStorageType = CredentialStorageType.KEYSTORE_ENCRYPTED,
            displayConfig = DisplayConfig(
                resolutionMode = ResolutionMode.FIXED_1080P,
                colorDepth = ColorDepth.DEPTH_32,
                scalingMode = ScalingMode.FIT_SCREEN
            ),
            performancePreset = PerformancePreset.LOW_LATENCY,
            redirectionConfig = RedirectionConfig(
                soundEnabled = true,
                microphoneEnabled = false,
                clipboardEnabled = true
            ),
            networkConfig = NetworkConfig(
                autoReconnect = true,
                maxReconnectAttempts = 3,
                connectionTimeoutMs = 15000L
            ),
            securityConfig = SecurityConfig(
                nlaEnabled = true,
                tlsEnabled = true,
                certValidationMode = CertValidationMode.WARN_ON_MISMATCH
            ),
            isPinned = true
        )

        repository.saveProfile(profile)

        // 2. Read
        val retrieved = repository.getProfile(profile.id)
        assertNotNull("Retrieved profile should not be null", retrieved)
        assertEquals("Primary Workstation", retrieved?.label)
        assertEquals("10.0.0.42", retrieved?.hostname)
        assertEquals(3389, retrieved?.port)
        assertEquals("alice", retrieved?.username)
        assertEquals("CORP", retrieved?.domain)
        assertEquals(PerformancePreset.LOW_LATENCY, retrieved?.performancePreset)
        assertTrue(retrieved?.isPinned == true)

        val flowList = repository.getAllProfiles().first()
        assertEquals(1, flowList.size)
        assertEquals(profile.id, flowList[0].id)

        // 3. Update
        val updatedProfile = retrieved!!.copy(
            label = "Updated Workstation",
            port = 3390,
            hostname = "10.0.0.43"
        )
        repository.saveProfile(updatedProfile)

        val afterUpdate = repository.getProfile(profile.id)
        assertNotNull(afterUpdate)
        assertEquals("Updated Workstation", afterUpdate?.label)
        assertEquals(3390, afterUpdate?.port)
        assertEquals("10.0.0.43", afterUpdate?.hostname)

        // 4. Delete
        val deleteSuccess = repository.deleteProfile(profile.id)
        assertTrue("Deletion should succeed", deleteSuccess)

        val afterDelete = repository.getProfile(profile.id)
        assertNull("Deleted profile should return null", afterDelete)

        val emptyFlowList = repository.getAllProfiles().first()
        assertTrue("Profiles list should be empty after deletion", emptyFlowList.isEmpty())

        // 5. Delete non-existent
        val deleteNonExistent = repository.deleteProfile("unknown-id")
        assertFalse("Deleting non-existent profile should return false", deleteNonExistent)
    }

    @Test
    fun testDuplicateProfile() = runTest(testDispatcher) {
        val original = RdpProfile(
            label = "Lab Server",
            hostname = "192.168.1.100",
            port = 3389
        )
        repository.saveProfile(original)

        val duplicated = repository.duplicateProfile(original.id)
        assertNotNull("Duplicated profile should not be null", duplicated)
        assertNotEquals(original.id, duplicated?.id)
        assertEquals("Lab Server (Copy)", duplicated?.label)
        assertEquals("192.168.1.100", duplicated?.hostname)

        val allProfiles = repository.getAllProfiles().first()
        assertEquals(2, allProfiles.size)
        assertTrue(allProfiles.any { it.id == original.id })
        assertTrue(allProfiles.any { it.id == duplicated?.id })

        val duplicateNull = repository.duplicateProfile("invalid-id")
        assertNull("Duplicating non-existent ID should return null", duplicateNull)
    }

    @Test
    fun testAtomicWriteSafety() = runTest(testDispatcher) {
        val profile = RdpProfile(
            label = "Safety Test Server",
            hostname = "server.internal"
        )
        repository.saveProfile(profile)

        assertTrue("Storage file must exist after save", storageFile.exists())
        assertTrue("Storage file size must be > 0", storageFile.length() > 0)

        // Ensure no leftover temp files exist in folder
        val tempFiles = tempFolder.root.listFiles { _, name -> name.endsWith(".tmp") }
        assertTrue("No leftover temporary files should exist", tempFiles.isNullOrEmpty())
    }

    @Test
    fun testCorruptFileRecoveryReturnsEmptyGracefully() = runTest(testDispatcher) {
        // Corrupt storage file with invalid JSON
        storageFile.writeText("{ corrupted_json_syntax: true [invalid", Charsets.UTF_8)

        // Create new repository instance pointing to the corrupted file
        val corruptRepo = AtomicFileProfileRepository(storageFile, testDispatcher)
        val loadedProfiles = corruptRepo.getAllProfiles().first()
        assertTrue("Corrupted file must result in empty list without throwing", loadedProfiles.isEmpty())

        // Verify corrupted file backup was created
        val corruptBak = File(tempFolder.root, "${storageFile.name}.corrupt.bak")
        assertTrue("Corrupted backup file should be created", corruptBak.exists())

        // Saving a new profile should succeed and recover state
        val newProfile = RdpProfile(label = "Fresh Profile", hostname = "fresh.local")
        corruptRepo.saveProfile(newProfile)

        val recovered = corruptRepo.getProfile(newProfile.id)
        assertNotNull("New profile should save and load cleanly after recovery", recovered)
        assertEquals("Fresh Profile", recovered?.label)
    }

    @Test
    fun testCorruptFileRecoveryRestoresFromBackup() = runTest(testDispatcher) {
        val profile = RdpProfile(label = "Backup Profile", hostname = "backup.local")
        repository.saveProfile(profile)

        val backupFile = File(tempFolder.root, "${storageFile.name}.bak")
        assertTrue("Backup file should exist after initial save", backupFile.exists())

        // Corrupt main storage file
        storageFile.writeText("corrupt content", Charsets.UTF_8)

        val newRepo = AtomicFileProfileRepository(storageFile, testDispatcher)
        val loaded = newRepo.getAllProfiles().first()
        assertEquals(1, loaded.size)
        assertEquals("Backup Profile", loaded[0].label)
    }

    @Test
    fun testClearAllRemovesAllProfiles() = runTest(testDispatcher) {
        repository.saveProfile(RdpProfile(label = "Server 1", hostname = "1.1.1.1"))
        repository.saveProfile(RdpProfile(label = "Server 2", hostname = "2.2.2.2"))

        assertEquals(2, repository.getAllProfiles().first().size)

        repository.clearAll()
        assertEquals(0, repository.getAllProfiles().first().size)
    }

    @Test
    fun testToConnectionConfigBinding() {
        val profile = RdpProfile(
            label = "Production Server",
            hostname = "rdp.contoso.com",
            port = 3390,
            username = "admin",
            domain = "CONTOSO",
            displayConfig = DisplayConfig(
                resolutionMode = ResolutionMode.FIXED_1080P,
                colorDepth = ColorDepth.DEPTH_32
            ),
            performancePreset = PerformancePreset.ULTRA_LOW_LATENCY,
            redirectionConfig = RedirectionConfig(clipboardEnabled = true),
            securityConfig = SecurityConfig(
                nlaEnabled = true,
                tlsEnabled = true,
                certValidationMode = CertValidationMode.ACCEPT_ALL
            )
        )

        val config = profile.toConnectionConfig(password = "supersecret")
        assertEquals("rdp.contoso.com", config.serverAddress)
        assertEquals(3390, config.port)
        assertEquals("admin", config.username)
        assertEquals("CONTOSO", config.domain)
        assertEquals("supersecret", config.password)
        assertEquals(1920, config.width)
        assertEquals(1080, config.height)
        assertEquals(32, config.colorDepth)
        assertEquals(PerformancePreset.ULTRA_LOW_LATENCY, config.performancePreset)
        assertTrue(config.enableClipboard)
        assertTrue(config.enableNla)
        assertTrue(config.enableTls)
        assertTrue(config.ignoreCertificate)
    }

    // ---------------------------------------------------------------------
    // Boundary & corner cases (empty / 0-byte / truncated / garbage files,
    // huge lists, unknown fields, concurrent access, path edge cases)
    // ---------------------------------------------------------------------

    @Test
    fun testZeroByteStorageFileLoadsEmptyAndStillWorks() = runTest(testDispatcher) {
        assertTrue("Precondition: create empty file", storageFile.createNewFile())
        assertEquals("Precondition: file is 0 bytes", 0L, storageFile.length())

        val repo = AtomicFileProfileRepository(storageFile, testDispatcher)
        assertTrue("0-byte file must load as an empty list without crashing", repo.getAllProfiles().first().isEmpty())

        val profile = RdpProfile(label = "After Zero Byte", hostname = "after.local")
        repo.saveProfile(profile)
        assertNotNull("Saving after a 0-byte file must succeed", repo.getProfile(profile.id))

        val restarted = AtomicFileProfileRepository(storageFile, testDispatcher)
        assertEquals("State must persist across restart after 0-byte recovery", 1, restarted.getAllProfiles().first().size)
    }

    @Test
    fun testWhitespaceOnlyStorageFileLoadsEmpty() = runTest(testDispatcher) {
        storageFile.writeText("  \n\t\r\n   ", Charsets.UTF_8)

        val repo = AtomicFileProfileRepository(storageFile, testDispatcher)
        assertTrue("Whitespace-only file must load as empty without crashing", repo.getAllProfiles().first().isEmpty())
        assertNull(repo.getProfile("any-id"))
    }

    @Test
    fun testTruncatedJsonRecoversFromLastGoodBackup() = runTest(testDispatcher) {
        val profile = RdpProfile(label = "Truncated Victim", hostname = "trunc.local")
        repository.saveProfile(profile)

        val backupFile = File(tempFolder.root, "${storageFile.name}.bak")
        assertTrue("Backup must exist after first save", backupFile.exists())

        // Simulate a torn write: cut the live file in half
        val fullText = storageFile.readText(Charsets.UTF_8)
        assertTrue("Sanity: live file must be non-trivial JSON", fullText.length > 100)
        storageFile.writeText(fullText.substring(0, fullText.length / 2), Charsets.UTF_8)

        val recoveringRepo = AtomicFileProfileRepository(storageFile, testDispatcher)
        val recovered = recoveringRepo.getAllProfiles().first()
        assertEquals("Truncated file must restore the last good backup", 1, recovered.size)
        assertEquals("Truncated Victim", recovered[0].label)
        assertEquals(profile.id, recovered[0].id)
        assertTrue(
            "Corrupt snapshot must be preserved as .corrupt.bak",
            File(tempFolder.root, "${storageFile.name}.corrupt.bak").exists()
        )

        // The recovered store must remain writable
        recoveringRepo.saveProfile(RdpProfile(label = "Post Recovery", hostname = "post.local"))
        assertEquals(2, recoveringRepo.getAllProfiles().first().size)
    }

    @Test
    fun testGarbageBytesNeverCrashAndEnableCleanSave() = runTest(testDispatcher) {
        // Binary garbage including invalid UTF-8 and a partial JSON prefix
        storageFile.writeBytes(byteArrayOf(0x00, 0x1F, 0xFF.toByte(), 0xFE.toByte(), 0x7B, 0x5B, 0x22))

        val repo = AtomicFileProfileRepository(storageFile, testDispatcher)
        val loaded = repo.getAllProfiles().first()
        assertTrue("Garbage file must yield an empty list without throwing", loaded.isEmpty())
        assertTrue(
            "Corrupt snapshot must be preserved as .corrupt.bak",
            File(tempFolder.root, "${storageFile.name}.corrupt.bak").exists()
        )

        val profile = RdpProfile(label = "Post Garbage", hostname = "post.local")
        repo.saveProfile(profile)
        val restarted = AtomicFileProfileRepository(storageFile, testDispatcher)
        assertEquals("Store must be fully usable after garbage recovery", 1, restarted.getAllProfiles().first().size)
        assertNotNull(restarted.getProfile(profile.id))
    }

    @Test
    fun testHugeProfileListRoundTripsAcrossRestart() = runTest(testDispatcher) {
        val count = 300
        for (i in 0 until count) {
            repository.saveProfile(RdpProfile(label = "Bulk $i", hostname = "bulk$i.local", port = 3000 + i))
        }
        assertEquals("All bulk saves must be visible live", count, repository.getAllProfiles().first().size)

        // Simulated process restart: fresh instance over the same file
        val restarted = AtomicFileProfileRepository(storageFile, testDispatcher)
        val loaded = restarted.getAllProfiles().first()
        assertEquals("Restarted instance must reload every profile", count, loaded.size)
        assertEquals(
            "Every hostname must survive the round trip",
            (0 until count).map { "bulk${it}.local" }.toSet(),
            loaded.map { it.hostname }.toSet()
        )
        assertEquals(
            "Every port must survive the round trip",
            (0 until count).map { 3000 + it }.toSet(),
            loaded.map { it.port }.toSet()
        )
        assertEquals("Ids must stay unique", count, loaded.map { it.id }.toSet().size)
    }

    @Test
    fun testUnknownJsonFieldsAreIgnoredForForwardCompatibility() = runTest(testDispatcher) {
        val forwardJson = """
            [
              {
                "id": "fwd-1",
                "label": "Forward Compat",
                "hostname": "fwd.local",
                "port": 3390,
                "futureBooleanFlag": true,
                "futureNestedObject": { "alpha": 1, "beta": ["x", "y"] },
                "futureString": "hello from schema v2"
              }
            ]
        """.trimIndent()
        storageFile.writeText(forwardJson, Charsets.UTF_8)

        val repo = AtomicFileProfileRepository(storageFile, testDispatcher)
        val loaded = repo.getAllProfiles().first()
        assertEquals("Profile with unknown fields must deserialize", 1, loaded.size)
        assertEquals("fwd-1", loaded[0].id)
        assertEquals("Forward Compat", loaded[0].label)
        assertEquals("fwd.local", loaded[0].hostname)
        assertEquals(3390, loaded[0].port)
        // Fields absent from the future payload fall back to schema defaults
        assertEquals("", loaded[0].username)
        assertEquals(CredentialStorageType.KEYSTORE_ENCRYPTED, loaded[0].credentialStorageType)
    }

    @Test
    fun testConcurrentReadWriteStressKeepsStoreConsistent() = runBlocking {
        val repo = AtomicFileProfileRepository(storageFile, Dispatchers.IO)

        val writers = (0 until 24).map { i ->
            async(Dispatchers.IO) {
                repo.saveProfile(RdpProfile(label = "Concurrent $i", hostname = "c$i.local"))
            }
        }
        val readers = (0 until 8).map {
            async(Dispatchers.IO) {
                repeat(5) { repo.getAllProfiles().first() }
            }
        }
        writers.awaitAll()
        readers.awaitAll()

        val finalList = repo.getAllProfiles().first()
        assertEquals("Every concurrent save must survive", 24, finalList.size)
        assertEquals("Profile ids must stay unique under concurrency", 24, finalList.map { it.id }.toSet().size)

        // A fresh instance must observe the same consistent state on disk
        val diskList = AtomicFileProfileRepository(storageFile, Dispatchers.IO).getAllProfiles().first()
        assertEquals("On-disk store must be consistent after concurrent writes", 24, diskList.size)
    }

    @Test
    fun testMissingParentDirectoryIsCreatedOnSave() = runTest(testDispatcher) {
        val nestedFile = File(tempFolder.root, "does/not/exist/profiles.json")
        assertFalse("Precondition: nested path must not exist yet", nestedFile.exists())

        val repo = AtomicFileProfileRepository(nestedFile, testDispatcher)
        repo.saveProfile(RdpProfile(label = "Nested", hostname = "nested.local"))

        assertTrue("Missing parent directories must be created on save", nestedFile.exists())
        assertEquals(1, repo.getAllProfiles().first().size)
    }

    @Test
    fun testSavingSameIdTwiceUpdatesInsteadOfDuplicating() = runTest(testDispatcher) {
        val profile = RdpProfile(label = "v1", hostname = "v1.local")
        repository.saveProfile(profile)
        repository.saveProfile(profile.copy(label = "v2", port = 3399))

        val all = repository.getAllProfiles().first()
        assertEquals("Updating by id must not duplicate", 1, all.size)
        assertEquals("v2", all[0].label)
        assertEquals(3399, all[0].port)
        assertEquals(profile.id, all[0].id)

        // And the deduplicated state must be what lands on disk
        val restarted = AtomicFileProfileRepository(storageFile, testDispatcher)
        assertEquals(1, restarted.getAllProfiles().first().size)
        assertEquals("v2", restarted.getAllProfiles().first()[0].label)
    }
}
