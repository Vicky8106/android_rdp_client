package com.rdp.client.ui.home

import com.google.common.truth.Truth.assertThat
import com.rdp.client.model.AudioMode
import com.rdp.client.model.ColorDepth
import com.rdp.client.model.ProfileSortOrder
import com.rdp.client.model.ResolutionMode
import com.rdp.client.model.SecurityType
import com.rdp.client.model.ServerProfile
import com.rdp.client.repository.IProfileRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val repository: IProfileRepository = mockk(relaxed = true)
    private val profilesFlow = MutableStateFlow<List<ServerProfile>>(emptyList())

    private lateinit var viewModel: ProfileViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        coEvery { repository.allProfiles } returns profilesFlow
        viewModel = ProfileViewModel(repository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testInitialState_emptyProfiles() = runTest(testDispatcher) {
        testDispatcher.scheduler.advanceUntilIdle()
        val state = viewModel.uiState.value
        assertThat(state.isLoading).isFalse()
        assertThat(state.profiles).isEmpty()
    }

    @Test
    fun testSearchFiltering_matchesNameAndHost() = runTest(testDispatcher) {
        val p1 = createProfile(id = 1, name = "Accounting PC", host = "10.0.0.1")
        val p2 = createProfile(id = 2, name = "Workstation", host = "192.168.1.50")
        profilesFlow.value = listOf(p1, p2)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.setSearchQuery("192.168")
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.profiles).containsExactly(p2)
    }

    @Test
    fun testSorting_byNameAlphabetical() = runTest(testDispatcher) {
        val pA = createProfile(id = 1, name = "Bravo Server", host = "10.0.0.1")
        val pB = createProfile(id = 2, name = "Alpha Desktop", host = "10.0.0.2")
        profilesFlow.value = listOf(pA, pB)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.setSortOrder(ProfileSortOrder.NAME_ASC)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.profiles.map { it.name }).containsExactly("Alpha Desktop", "Bravo Server").inOrder()
    }

    @Test
    fun testSorting_byLastConnected() = runTest(testDispatcher) {
        val pOld = createProfile(id = 1, name = "Old", host = "1.1.1.1", lastConnected = 1000L)
        val pNew = createProfile(id = 2, name = "New", host = "1.1.1.2", lastConnected = 5000L)
        profilesFlow.value = listOf(pOld, pNew)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.setSortOrder(ProfileSortOrder.LAST_CONNECTED)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.profiles.map { it.name }).containsExactly("New", "Old").inOrder()
    }

    @Test
    fun testSorting_byMostUsed() = runTest(testDispatcher) {
        val pFew = createProfile(id = 1, name = "Few", host = "1.1.1.1", connectionCount = 2)
        val pMany = createProfile(id = 2, name = "Many", host = "1.1.1.2", connectionCount = 10)
        profilesFlow.value = listOf(pFew, pMany)
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.setSortOrder(ProfileSortOrder.MOST_USED)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.profiles.map { it.name }).containsExactly("Many", "Few").inOrder()
    }

    @Test
    fun testDuplicateProfile_callsRepository() = runTest(testDispatcher) {
        val source = createProfile(id = 42, name = "Production Host", host = "prod.local")
        viewModel.duplicateProfile(source)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify {
            repository.duplicateProfile(42L)
        }
    }

    @Test
    fun testDeleteAndUndo() = runTest(testDispatcher) {
        val target = createProfile(id = 10, name = "To Delete", host = "10.0.0.1")
        viewModel.deleteProfile(target)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { repository.deleteProfile(target) }

        viewModel.undoDelete()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { repository.insertProfile(target) }
    }

    @Test
    fun testRecordConnection() = runTest(testDispatcher) {
        viewModel.recordConnection(5L)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { repository.recordConnection(5L) }
    }

    private fun createProfile(
        id: Long,
        name: String,
        host: String,
        lastConnected: Long = 0L,
        connectionCount: Int = 0
    ) = ServerProfile(
        id = id,
        name = name,
        host = host,
        port = 3389,
        securityType = SecurityType.AUTO,
        resolutionMode = ResolutionMode.FIT_TO_SCREEN,
        colorDepth = ColorDepth.DEPTH_32,
        audioMode = AudioMode.LOCAL,
        lastConnectedTimestamp = lastConnected,
        connectionCount = connectionCount
    )
}
