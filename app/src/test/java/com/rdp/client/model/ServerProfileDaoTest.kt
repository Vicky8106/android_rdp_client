package com.rdp.client.model

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.rdp.client.repository.ProfileRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ServerProfileDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: ServerProfileDao
    private lateinit var repository: ProfileRepository

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = AppDatabase.createInMemory(context)
        dao = database.serverProfileDao()
        repository = ProfileRepository(dao)
    }

    @After
    fun teardown() {
        database.close()
    }

    @Test
    fun testInsertAndRetrieveProfileById() = runBlocking {
        val profile = ServerProfile(
            name = "Test Work PC",
            host = "192.168.1.100",
            port = 3389,
            username = "admin",
            domain = "CORP",
            securityType = SecurityType.NLA
        )

        val id = dao.insert(profile)
        assertThat(id).isGreaterThan(0L)

        val retrieved = dao.getProfileById(id)
        assertThat(retrieved).isNotNull()
        assertThat(retrieved?.name).isEqualTo("Test Work PC")
        assertThat(retrieved?.host).isEqualTo("192.168.1.100")
        assertThat(retrieved?.securityType).isEqualTo(SecurityType.NLA)
    }

    @Test
    fun testUpdateProfile() = runBlocking {
        val id = dao.insert(ServerProfile(name = "Initial", host = "10.0.0.1"))
        val existing = dao.getProfileById(id)!!

        val updated = existing.copy(name = "Updated Name", port = 3390)
        val affected = dao.update(updated)
        assertThat(affected).isEqualTo(1)

        val reloaded = dao.getProfileById(id)!!
        assertThat(reloaded.name).isEqualTo("Updated Name")
        assertThat(reloaded.port).isEqualTo(3390)
    }

    @Test
    fun testDeleteProfile() = runBlocking {
        val id = dao.insert(ServerProfile(name = "To Delete", host = "10.0.0.2"))
        assertThat(dao.getProfileById(id)).isNotNull()

        val affected = dao.deleteById(id)
        assertThat(affected).isEqualTo(1)
        assertThat(dao.getProfileById(id)).isNull()
    }

    @Test
    fun testSearchProfilesFiltering() = runBlocking {
        dao.insert(ServerProfile(name = "Office Workstation", host = "192.168.1.10"))
        dao.insert(ServerProfile(name = "Home Media Server", host = "192.168.1.20"))
        dao.insert(ServerProfile(name = "Cloud VPS", host = "vps.example.com"))

        val searchOffice = dao.getProfilesMatchingQuery("office").first()
        assertThat(searchOffice).hasSize(1)
        assertThat(searchOffice[0].name).isEqualTo("Office Workstation")

        val searchIp = dao.getProfilesMatchingQuery("192.168").first()
        assertThat(searchIp).hasSize(2)
    }

    @Test
    fun testSortingByNameAscending() = runBlocking {
        dao.insert(ServerProfile(name = "Zebra Server", host = "1.1.1.1"))
        dao.insert(ServerProfile(name = "Alpha Host", host = "2.2.2.2"))
        dao.insert(ServerProfile(name = "Beta Machine", host = "3.3.3.3"))

        val sorted = dao.getAllProfilesSortedByNameAsc().first()
        assertThat(sorted).hasSize(3)
        assertThat(sorted[0].name).isEqualTo("Alpha Host")
        assertThat(sorted[1].name).isEqualTo("Beta Machine")
        assertThat(sorted[2].name).isEqualTo("Zebra Server")
    }

    @Test
    fun testSortingByNameDescending() = runBlocking {
        dao.insert(ServerProfile(name = "Alpha Host", host = "1.1.1.1"))
        dao.insert(ServerProfile(name = "Zebra Server", host = "2.2.2.2"))

        val sorted = dao.getAllProfilesSortedByNameDesc().first()
        assertThat(sorted).hasSize(2)
        assertThat(sorted[0].name).isEqualTo("Zebra Server")
        assertThat(sorted[1].name).isEqualTo("Alpha Host")
    }

    @Test
    fun testRecordConnectionIncrementsCount() = runBlocking {
        val id = dao.insert(ServerProfile(name = "Connection Test", host = "1.2.3.4"))
        assertThat(dao.getProfileById(id)?.connectionCount).isEqualTo(0)

        dao.recordConnection(id, 123456789L)
        val afterFirst = dao.getProfileById(id)!!
        assertThat(afterFirst.connectionCount).isEqualTo(1)
        assertThat(afterFirst.lastConnectedTimestamp).isEqualTo(123456789L)

        dao.recordConnection(id, 987654321L)
        val afterSecond = dao.getProfileById(id)!!
        assertThat(afterSecond.connectionCount).isEqualTo(2)
        assertThat(afterSecond.lastConnectedTimestamp).isEqualTo(987654321L)
    }

    @Test
    fun testDuplicateProfile() = runBlocking {
        val id = repository.insertProfile(ServerProfile(name = "Original Server", host = "1.2.3.4"))
        val clonedId = repository.duplicateProfile(id)

        assertThat(clonedId).isNotNull()
        assertThat(clonedId).isNotEqualTo(id)

        val cloned = repository.getProfileById(clonedId!!)!!
        assertThat(cloned.name).isEqualTo("Copy of Original Server")
        assertThat(cloned.host).isEqualTo("1.2.3.4")
        assertThat(cloned.connectionCount).isEqualTo(0)
    }

    @Test
    fun testValidationRules() {
        val invalidHost = ServerProfile(host = "")
        assertThat(invalidHost.validate().isSuccess).isFalse()

        val invalidPort = ServerProfile(host = "1.2.3.4", port = 70000)
        assertThat(invalidPort.validate().isSuccess).isFalse()

        val validProfile = ServerProfile(host = "1.2.3.4", port = 3389)
        assertThat(validProfile.validate().isSuccess).isTrue()
    }

    @Test
    fun testFreeRdpArgumentsGeneration() {
        val profile = ServerProfile(
            name = "Production DC",
            host = "dc01.corp.internal",
            port = 3389,
            username = "admin",
            password = "secretPassword",
            domain = "CORP",
            securityType = SecurityType.NLA,
            colorDepth = ColorDepth.DEPTH_32,
            audioMode = AudioMode.LOCAL,
            ignoreCertificate = true,
            clipboardSync = true
        )

        val args = profile.toFreeRdpArguments()
        assertThat(args).contains("/v:dc01.corp.internal:3389")
        assertThat(args).contains("/u:admin")
        assertThat(args).contains("/p:secretPassword")
        assertThat(args).contains("/d:CORP")
        assertThat(args).contains("/sec:nla")
        assertThat(args).contains("/bpp:32")
        assertThat(args).contains("/sound:sys:opensles")
        assertThat(args).contains("/cert:ignore")
        assertThat(args).contains("+clipboard")
    }

    @Test
    fun testDisplaySummary() {
        val profile = ServerProfile(
            name = "Workstation",
            host = "192.168.1.50",
            port = 3389,
            securityType = SecurityType.NLA,
            colorDepth = ColorDepth.DEPTH_32,
            resolutionMode = ResolutionMode.FIT_TO_SCREEN
        )
        val summary = profile.toDisplaySummary()
        assertThat(summary).contains("192.168.1.50")
        assertThat(summary).contains("NLA")
        assertThat(summary).contains("32 bpp")
    }
}
