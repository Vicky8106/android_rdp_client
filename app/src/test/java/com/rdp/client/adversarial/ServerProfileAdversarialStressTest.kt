package com.rdp.client.adversarial

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.rdp.client.model.AppDatabase
import com.rdp.client.model.AudioMode
import com.rdp.client.model.ColorDepth
import com.rdp.client.model.Converters
import com.rdp.client.model.GestureStyle
import com.rdp.client.model.ProfileSortOrder
import com.rdp.client.model.ResolutionMode
import com.rdp.client.model.ScreenOrientation
import com.rdp.client.model.SecurityType
import com.rdp.client.model.ServerProfile
import com.rdp.client.model.ServerProfileDao
import com.rdp.client.model.ViewMode
import com.rdp.client.repository.ProfileRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ServerProfileAdversarialStressTest {

    private lateinit var context: Context
    private lateinit var inMemoryDb: AppDatabase
    private lateinit var inMemoryDao: ServerProfileDao
    private lateinit var inMemoryRepo: ProfileRepository

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext<Context>()
        inMemoryDb = AppDatabase.createInMemory(context)
        inMemoryDao = inMemoryDb.serverProfileDao()
        inMemoryRepo = ProfileRepository(inMemoryDao)
    }

    @After
    fun teardown() {
        inMemoryDb.close()
    }

    // =========================================================================
    // 1. CONCURRENCY & STRESS TESTING
    // =========================================================================

    @Test
    fun testConcurrentMassInsertionsAndCountIntegrity() = runBlocking {
        val totalProfiles = 100
        val concurrency = 10

        val deferreds = (0 until concurrency).map { workerIdx ->
            async(Dispatchers.IO) {
                for (i in 0 until (totalProfiles / concurrency)) {
                    val profileId = (workerIdx * (totalProfiles / concurrency)) + i
                    inMemoryDao.insert(
                        ServerProfile(
                            name = "Worker_${workerIdx}_Profile_$profileId",
                            host = "10.0.$workerIdx.$i",
                            port = 3389 + i,
                            username = "user_$profileId"
                        )
                    )
                }
            }
        }
        deferreds.awaitAll()

        val count = inMemoryDao.getProfileCount()
        assertThat(count).isEqualTo(totalProfiles)

        val allProfiles = inMemoryDao.getAllProfilesFlow().first()
        assertThat(allProfiles).hasSize(totalProfiles)
    }

    @Test
    fun testConcurrentRecordConnectionRaceCondition() = runBlocking {
        val profileId = inMemoryDao.insert(
            ServerProfile(name = "ContentionTarget", host = "192.168.1.1")
        )

        val iterations = 50
        val updates = (0 until iterations).map { idx ->
            async(Dispatchers.IO) {
                inMemoryDao.recordConnection(profileId, 1000L + idx)
            }
        }
        updates.awaitAll()

        val finalProfile = inMemoryDao.getProfileById(profileId)!!
        // In SQLite, "SET connectionCount = connectionCount + 1" under serialized Room queries
        // should increment exactly 50 times without lost updates
        assertThat(finalProfile.connectionCount).isEqualTo(iterations)
        assertThat(finalProfile.lastConnectedTimestamp).isAtLeast(1000L)
    }

    @Test
    fun testConcurrentMixedCrudStress() = runBlocking {
        // Pre-populate with 30 profiles
        val baseProfiles = (1..30).map { i ->
            ServerProfile(name = "Initial_$i", host = "10.0.0.$i")
        }
        inMemoryDao.insertAll(baseProfiles)

        val completedOps = AtomicInteger(0)

        // Launch 20 concurrent tasks doing interleaved insert, update, query, delete
        val tasks = (1..20).map { threadId ->
            async(Dispatchers.IO) {
                // Task 1: Insert new profile
                val newId = inMemoryDao.insert(
                    ServerProfile(name = "Stress_$threadId", host = "192.168.99.$threadId")
                )
                // Task 2: Update existing profile
                inMemoryDao.update(
                    ServerProfile(id = newId, name = "Updated_$threadId", host = "192.168.99.$threadId", port = 3390)
                )
                // Task 3: Query profile
                val retrieved = inMemoryDao.getProfileById(newId)
                assertThat(retrieved?.name).isEqualTo("Updated_$threadId")

                // Task 4: Search query during active mutations
                val searchResult = inMemoryDao.getProfilesMatchingQuery("Stress").first()
                assertThat(searchResult).isNotNull()

                // Task 5: Selective delete
                if (threadId % 2 == 0) {
                    inMemoryDao.deleteById(newId)
                    assertThat(inMemoryDao.getProfileById(newId)).isNull()
                }

                completedOps.incrementAndGet()
            }
        }
        tasks.awaitAll()
        assertThat(completedOps.get()).isEqualTo(20)

        // Confirm DB state is intact (30 initial + 10 odd-numbered retained = 40)
        val finalCount = inMemoryDao.getProfileCount()
        assertThat(finalCount).isEqualTo(40)
    }

    // =========================================================================
    // 2. SORTING PERMUTATIONS & EDGE CASES
    // =========================================================================

    @Test
    fun testSortingPermutationsIdenticalTimestampsDeterministicTieBreaking() = runBlocking {
        val fixedTimestamp = 1600000000000L
        val fixedCreated = 1500000000000L

        // Insert 5 profiles with identical lastConnectedTimestamp and createdTimestamp
        val id1 = inMemoryDao.insert(ServerProfile(name = "S1", host = "1.1.1.1", lastConnectedTimestamp = fixedTimestamp, createdTimestamp = fixedCreated))
        val id2 = inMemoryDao.insert(ServerProfile(name = "S2", host = "1.1.1.2", lastConnectedTimestamp = fixedTimestamp, createdTimestamp = fixedCreated))
        val id3 = inMemoryDao.insert(ServerProfile(name = "S3", host = "1.1.1.3", lastConnectedTimestamp = fixedTimestamp, createdTimestamp = fixedCreated))

        // Last connected sorts by "lastConnectedTimestamp DESC, id DESC"
        val byLastConn = inMemoryDao.getAllProfilesSortedByLastConnected().first()
        assertThat(byLastConn.map { it.id }).containsExactly(id3, id2, id1).inOrder()

        // Date added sorts by "createdTimestamp DESC, id DESC"
        val byDateAdded = inMemoryDao.getAllProfilesSortedByDateAdded().first()
        assertThat(byDateAdded.map { it.id }).containsExactly(id3, id2, id1).inOrder()

        // Most used with connectionCount = 0 sorts by "connectionCount DESC, id DESC"
        val byMostUsed = inMemoryDao.getAllProfilesSortedByMostUsed().first()
        assertThat(byMostUsed.map { it.id }).containsExactly(id3, id2, id1).inOrder()
    }

    @Test
    fun testSortingWithEmptyAndWhitespaceNames() = runBlocking {
        // Query logic: CASE WHEN name = '' THEN host ELSE name END COLLATE NOCASE ASC, id ASC
        val p1 = ServerProfile(name = "", host = "Zulu.com")
        val p2 = ServerProfile(name = "Alpha", host = "1.1.1.1")
        val p3 = ServerProfile(name = "   ", host = "Bravo.com") // Whitespace only
        val p4 = ServerProfile(name = "", host = "Charlie.com")

        inMemoryDao.insert(p1)
        inMemoryDao.insert(p2)
        inMemoryDao.insert(p3)
        inMemoryDao.insert(p4)

        val sortedAsc = inMemoryDao.getAllProfilesSortedByNameAsc().first()
        // Notice: p3 has name = "   ", which is NOT '' in SQLite.
        // Spaces sort before ASCII letters in standard ASCII/Unicode collation.
        // Charlie falls back to host "Charlie.com" because name = ''
        // Zulu falls back to host "Zulu.com" because name = ''
        // Order expected: "   " (space), "Alpha", "Charlie.com", "Zulu.com"
        val namesOrHosts = sortedAsc.map { if (it.name.isEmpty()) it.host else it.name }
        assertThat(namesOrHosts).containsExactly("   ", "Alpha", "Charlie.com", "Zulu.com").inOrder()
    }

    @Test
    fun testSortingNonAsciiUnicodeAndEmojiServerNames() = runBlocking {
        val p1 = ServerProfile(name = "🚀 Rocket", host = "1.1.1.1")
        val p2 = ServerProfile(name = "北京服务器", host = "1.1.1.2")
        val p3 = ServerProfile(name = "東京サーバー", host = "1.1.1.3")
        val p4 = ServerProfile(name = "Москва Сервер", host = "1.1.1.4")
        val p5 = ServerProfile(name = "Alpha", host = "1.1.1.5")
        val p6 = ServerProfile(name = "alpha_lower", host = "1.1.1.6")

        inMemoryDao.insert(p1)
        inMemoryDao.insert(p2)
        inMemoryDao.insert(p3)
        inMemoryDao.insert(p4)
        inMemoryDao.insert(p5)
        inMemoryDao.insert(p6)

        val sortedAsc = inMemoryDao.getAllProfilesSortedByNameAsc().first()
        assertThat(sortedAsc).hasSize(6)

        val sortedDesc = inMemoryDao.getAllProfilesSortedByNameDesc().first()
        assertThat(sortedDesc).hasSize(6)
        // Ensure reverse ordering is exact inverse of ascending
        assertThat(sortedDesc.map { it.id }).containsExactlyElementsIn(sortedAsc.map { it.id }.reversed()).inOrder()
    }

    @Test
    fun testSortingSpecialPunctuationAndCharacters() = runBlocking {
        val p1 = ServerProfile(name = "-HyphenHost", host = "1.1.1.1")
        val p2 = ServerProfile(name = "!ExclamationHost", host = "1.1.1.2")
        val p3 = ServerProfile(name = "@AtHost", host = "1.1.1.3")
        val p4 = ServerProfile(name = "_UnderscoreHost", host = "1.1.1.4")
        val p5 = ServerProfile(name = "[BracketHost]", host = "1.1.1.5")

        inMemoryDao.insert(p1)
        inMemoryDao.insert(p2)
        inMemoryDao.insert(p3)
        inMemoryDao.insert(p4)
        inMemoryDao.insert(p5)

        val sortedAsc = inMemoryDao.getAllProfilesSortedByNameAsc().first()
        assertThat(sortedAsc).hasSize(5)
        // All special character profiles must be retrieved without SQLite collation corruption
        val names = sortedAsc.map { it.name }
        assertThat(names).containsExactly(
            "!ExclamationHost",
            "-HyphenHost",
            "@AtHost",
            "[BracketHost]",
            "_UnderscoreHost"
        ).inOrder()
    }

    // =========================================================================
    // 3. SEARCH FILTERING, SQL INJECTION, WILDCARDS & REGEX
    // =========================================================================

    @Test
    fun testSearchSqlInjectionAttacksAreNeutralized() = runBlocking {
        inMemoryDao.insert(ServerProfile(name = "Legitimate Server", host = "192.168.1.100", domain = "CORP"))
        inMemoryDao.insert(ServerProfile(name = "Secret Admin Box", host = "10.0.0.1", domain = "CONFIDENTIAL"))

        val injectionPayloads = listOf(
            "' OR '1'='1",
            "' OR 1=1 --",
            "'; DROP TABLE profiles; --",
            "' UNION SELECT * FROM profiles --",
            "\" OR \"\"=\"",
            "admin'--",
            "test'; DELETE FROM profiles WHERE '1'='1"
        )

        for (payload in injectionPayloads) {
            // Room uses SQLite parameterized statements (:query bound as '?')
            // None of these should execute SQL injection or crash
            val results = inMemoryDao.getProfilesMatchingQuery(payload).first()
            // Should return 0 since none of the names/hosts match these injection strings
            assertThat(results).isEmpty()
        }

        // Verify the database tables were NOT dropped or modified by injection attempts
        val profileCount = inMemoryDao.getProfileCount()
        assertThat(profileCount).isEqualTo(2)
    }

    @Test
    fun testSearchSpecialRegexAndWildcardCharactersEmpiricalBehavior() = runBlocking {
        inMemoryDao.insert(ServerProfile(name = "server_with_underscore", host = "192.168.1.1"))
        inMemoryDao.insert(ServerProfile(name = "server%with%percent", host = "192.168.1.2"))
        inMemoryDao.insert(ServerProfile(name = "plainserver", host = "192.168.1.3"))

        // Empirical check for '%' in LIKE query:
        // In SQL LIKE: '%' || query || '%'
        // If query is '%', the pattern becomes '%%%' which in SQLite LIKE matches ANY string!
        val searchPercent = inMemoryDao.getProfilesMatchingQuery("%").first()
        // EMPIRICAL DISCOVERY: Without LIKE ESCAPE, '%' acts as SQL wildcard matching ALL profiles
        assertThat(searchPercent).hasSize(3)

        // Empirical check for '_' in LIKE query:
        // In SQL LIKE: '_' matches ANY single character. So '%_%' matches any string with length >= 1.
        val searchUnderscore = inMemoryDao.getProfilesMatchingQuery("_").first()
        // EMPIRICAL DISCOVERY: Without LIKE ESCAPE, '_' acts as single char wildcard matching ALL profiles
        assertThat(searchUnderscore).hasSize(3)

        // Now test searching literal text around special characters
        val searchLiteralUnderscore = inMemoryDao.getProfilesMatchingQuery("with_underscore").first()
        assertThat(searchLiteralUnderscore).hasSize(1)
        assertThat(searchLiteralUnderscore[0].name).isEqualTo("server_with_underscore")

        val searchLiteralPercent = inMemoryDao.getProfilesMatchingQuery("with%percent").first()
        assertThat(searchLiteralPercent).hasSize(1)
        assertThat(searchLiteralPercent[0].name).isEqualTo("server%with%percent")
    }

    @Test
    fun testSearchSpecialCharactersQuotesSemicolonsBackslashes() = runBlocking {
        inMemoryDao.insert(ServerProfile(name = """O'Connor "Server" \DC;01""", host = "dc01.corp.com"))
        inMemoryDao.insert(ServerProfile(name = "NormalHost", host = "normal.com"))

        val singleQuoteMatch = inMemoryDao.getProfilesMatchingQuery("O'Connor").first()
        assertThat(singleQuoteMatch).hasSize(1)
        assertThat(singleQuoteMatch[0].name).contains("O'Connor")

        val doubleQuoteMatch = inMemoryDao.getProfilesMatchingQuery("\"Server\"").first()
        assertThat(doubleQuoteMatch).hasSize(1)

        val backslashMatch = inMemoryDao.getProfilesMatchingQuery("""\DC""").first()
        assertThat(backslashMatch).hasSize(1)

        val semicolonMatch = inMemoryDao.getProfilesMatchingQuery(";01").first()
        assertThat(semicolonMatch).hasSize(1)
    }

    @Test
    fun testSearchExtremelyLongQueryString() = runBlocking {
        inMemoryDao.insert(ServerProfile(name = "TargetServer", host = "10.0.0.1"))

        // 10,000 character search string
        val longString = "A".repeat(10_000)
        val result = inMemoryDao.getProfilesMatchingQuery(longString).first()
        assertThat(result).isEmpty()

        // 10,000 character profile name
        val hugeName = "Mega_" + "X".repeat(10_000)
        inMemoryDao.insert(ServerProfile(name = hugeName, host = "huge.internal"))

        val hugeMatch = inMemoryDao.getProfilesMatchingQuery("Mega_").first()
        assertThat(hugeMatch).hasSize(1)
        assertThat(hugeMatch[0].name).isEqualTo(hugeName)
    }

    // =========================================================================
    // 4. IN-MEMORY VS DISK DATABASE BEHAVIORAL VERIFICATION
    // =========================================================================

    @Test
    fun testInMemoryVsDiskDatabaseBehaveIdentically() = runBlocking {
        val diskDbFile = File(context.cacheDir, "adversarial_test_disk.db")
        if (diskDbFile.exists()) diskDbFile.delete()

        val diskDb = Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            diskDbFile.absolutePath
        ).allowMainThreadQueries().build()

        try {
            val diskDao = diskDb.serverProfileDao()

            val testData = listOf(
                ServerProfile(name = "Bravo", host = "10.0.0.2", port = 3389, lastConnectedTimestamp = 200L),
                ServerProfile(name = "Alpha", host = "10.0.0.1", port = 3390, lastConnectedTimestamp = 100L),
                ServerProfile(name = "Charlie", host = "10.0.0.3", port = 3391, lastConnectedTimestamp = 300L)
            )

            // Insert into both
            inMemoryDao.insertAll(testData)
            diskDao.insertAll(testData)

            // 1. Compare total counts
            assertThat(diskDao.getProfileCount()).isEqualTo(inMemoryDao.getProfileCount())

            // 2. Compare Name Ascending Sort
            val memSorted = inMemoryDao.getAllProfilesSortedByNameAsc().first()
            val diskSorted = diskDao.getAllProfilesSortedByNameAsc().first()
            assertThat(diskSorted.map { it.name }).isEqualTo(memSorted.map { it.name })

            // 3. Compare Last Connected Sort
            val memLastConn = inMemoryDao.getAllProfilesSortedByLastConnected().first()
            val diskLastConn = diskDao.getAllProfilesSortedByLastConnected().first()
            assertThat(diskLastConn.map { it.name }).isEqualTo(memLastConn.map { it.name })

            // 4. Compare Search query results
            val memSearch = inMemoryDao.getProfilesMatchingQuery("Alpha").first()
            val diskSearch = diskDao.getProfilesMatchingQuery("Alpha").first()
            assertThat(diskSearch.map { it.name }).isEqualTo(memSearch.map { it.name })

            // 5. In-place recordConnection updates
            val targetProfile = memSorted.first()
            inMemoryDao.recordConnection(targetProfile.id, 9999L)
            diskDao.recordConnection(targetProfile.id, 9999L)

            val updatedMem = inMemoryDao.getProfileById(targetProfile.id)!!
            val updatedDisk = diskDao.getProfileById(targetProfile.id)!!
            assertThat(updatedDisk.connectionCount).isEqualTo(updatedMem.connectionCount)
            assertThat(updatedDisk.lastConnectedTimestamp).isEqualTo(updatedMem.lastConnectedTimestamp)

        } finally {
            diskDb.close()
            if (diskDbFile.exists()) diskDbFile.delete()
        }
    }

    @Test
    fun testDiskDatabasePersistenceAcrossReopening() = runBlocking {
        val diskDbFile = File(context.cacheDir, "reopen_test_disk.db")
        if (diskDbFile.exists()) diskDbFile.delete()

        // Phase 1: Open DB, write data, close DB
        var diskDb = Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            diskDbFile.absolutePath
        ).allowMainThreadQueries().build()

        val profileId = diskDb.serverProfileDao().insert(
            ServerProfile(name = "Persistent Server", host = "192.168.1.250", port = 3389)
        )
        diskDb.close()

        // Phase 2: Reopen DB from same file and verify persistence
        diskDb = Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            diskDbFile.absolutePath
        ).allowMainThreadQueries().build()

        try {
            val loaded = diskDb.serverProfileDao().getProfileById(profileId)
            assertThat(loaded).isNotNull()
            assertThat(loaded?.name).isEqualTo("Persistent Server")
            assertThat(loaded?.host).isEqualTo("192.168.1.250")
        } finally {
            diskDb.close()
            if (diskDbFile.exists()) diskDbFile.delete()
        }
    }

    // =========================================================================
    // 5. DATA LAYER BOUNDARIES, CONVERTERS & REPOSITORY EDGE CASES
    // =========================================================================

    @Test
    fun testEnumConvertersUnknownOrCorruptedValuesFallback() {
        val converters = Converters()

        // Negative or out of bounds integer codes
        assertThat(converters.toSecurityType(-1)).isEqualTo(SecurityType.AUTO)
        assertThat(converters.toSecurityType(999)).isEqualTo(SecurityType.AUTO)

        assertThat(converters.toResolutionMode(-1)).isEqualTo(ResolutionMode.FIT_TO_SCREEN)
        assertThat(converters.toResolutionMode(999)).isEqualTo(ResolutionMode.FIT_TO_SCREEN)

        assertThat(converters.toColorDepth(-1)).isEqualTo(ColorDepth.DEPTH_32)
        assertThat(converters.toColorDepth(999)).isEqualTo(ColorDepth.DEPTH_32)

        assertThat(converters.toAudioMode(-1)).isEqualTo(AudioMode.LOCAL)
        assertThat(converters.toAudioMode(999)).isEqualTo(AudioMode.LOCAL)

        assertThat(converters.toViewMode(-1)).isEqualTo(ViewMode.NORMAL)
        assertThat(converters.toViewMode(999)).isEqualTo(ViewMode.NORMAL)

        // Corrupted strings
        assertThat(converters.toGestureStyle("corrupted_value")).isEqualTo(GestureStyle.AUTO)
        assertThat(converters.toGestureStyle(null)).isEqualTo(GestureStyle.AUTO)

        assertThat(converters.toScreenOrientation("corrupted_value")).isEqualTo(ScreenOrientation.AUTO)
        assertThat(converters.toScreenOrientation(null)).isEqualTo(ScreenOrientation.AUTO)
    }

    @Test
    fun testRepositoryDuplicateProfileEdgeCases() = runBlocking {
        // 1. Duplicating non-existent ID
        val missingResult = inMemoryRepo.duplicateProfile(999999L)
        assertThat(missingResult).isNull()

        // 2. Duplicating profile with blank name (should use host)
        val blankNameId = inMemoryDao.insert(ServerProfile(name = "", host = "10.0.0.99"))
        val dupId = inMemoryRepo.duplicateProfile(blankNameId)
        assertThat(dupId).isNotNull()
        val dupProfile = inMemoryDao.getProfileById(dupId!!)!!
        assertThat(dupProfile.name).isEqualTo("Copy of 10.0.0.99")
        assertThat(dupProfile.connectionCount).isEqualTo(0)
        assertThat(dupProfile.lastConnectedTimestamp).isEqualTo(0L)

        // 3. Duplicating profile with blank name AND blank host
        val emptyBothId = inMemoryDao.insert(ServerProfile(name = "", host = ""))
        val dupEmptyId = inMemoryRepo.duplicateProfile(emptyBothId)
        assertThat(dupEmptyId).isNotNull()
        val dupEmpty = inMemoryDao.getProfileById(dupEmptyId!!)!!
        assertThat(dupEmpty.name).isEqualTo("Copy of ")
    }

    @Test
    fun testExtremeNumericValuesInProfile() = runBlocking {
        val extremeProfile = ServerProfile(
            name = "Extreme Values",
            host = "extreme.test",
            port = 65535,
            gatewayPort = 65535,
            lastConnectedTimestamp = Long.MAX_VALUE,
            connectionCount = Int.MAX_VALUE,
            zoom1 = Float.MAX_VALUE,
            zoom2 = Float.MIN_VALUE,
            sortOrder = Int.MIN_VALUE
        )

        val id = inMemoryDao.insert(extremeProfile)
        val retrieved = inMemoryDao.getProfileById(id)!!

        assertThat(retrieved.lastConnectedTimestamp).isEqualTo(Long.MAX_VALUE)
        assertThat(retrieved.connectionCount).isEqualTo(Int.MAX_VALUE)
        assertThat(retrieved.zoom1).isEqualTo(Float.MAX_VALUE)
        assertThat(retrieved.zoom2).isEqualTo(Float.MIN_VALUE)
        assertThat(retrieved.sortOrder).isEqualTo(Int.MIN_VALUE)
    }
}
