package com.freerdp.client.unit

import android.content.Context
import com.freerdp.feature.session.data.AtomicFileProfileRepository
import com.freerdp.feature.session.security.KeystoreCredentialStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import com.freerdp.client.ui.profiles.ProfilesViewModel
import com.freerdp.feature.session.RdpProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * Profile list: disk-backed loading (process-death restore), delete confirmation
 * handshake including credential removal, and error surfacing without data loss.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ProfilesViewModelTest {

    private lateinit var context: Context
    private lateinit var repo: AtomicFileProfileRepository
    private lateinit var credentials: KeystoreCredentialStore

    private fun profile(id: String, label: String): RdpProfile =
        RdpProfile(id = id, label = label, hostname = "$id.example.com")

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        repo = AtomicFileProfileRepository(
            java.io.File(context.filesDir, "profiles_list_test.json"),
            Dispatchers.Unconfined
        )
        credentials = KeystoreCredentialStore(context, prefFileName = "list_vault")
        credentials.clearAll()
    }

    @Test
    fun loadsProfilesFromDiskIntoUiState() = runTest {
        runBlocking { repo.saveProfile(profile("a", "Alpha")); repo.saveProfile(profile("b", "Beta")) }

        val vm = ProfilesViewModel(repo, credentials, backgroundScope)
        pumpAll()

        val state = vm.uiState.value
        assertFalse(state.isLoading)
        assertEquals(2, state.profiles.size)
        assertEquals(listOf("Alpha", "Beta"), state.profiles.map { it.label }.sorted())
        assertNull(state.error)
    }

    @Test
    fun profileListRestoresFromDiskSimulatingProcessDeath() = runTest {
        runBlocking { repo.saveProfile(profile("keep", "Survivor")) }

        // A brand-new repository instance over the same file == new process after death.
        val freshRepo = AtomicFileProfileRepository(
            java.io.File(context.filesDir, "profiles_list_test.json"),
            Dispatchers.Unconfined
        )
        val vm = ProfilesViewModel(freshRepo, credentials, backgroundScope)
        pumpAll()

        assertEquals(1, vm.uiState.value.profiles.size)
        assertEquals("Survivor", vm.uiState.value.profiles[0].label)
    }

    @Test
    fun emptyRepositoryShowsLoadingClearedWithNoError() = runTest {
        val vm = ProfilesViewModel(repo, credentials, backgroundScope)
        pumpAll()
        assertFalse(vm.uiState.value.isLoading)
        assertTrue(vm.uiState.value.profiles.isEmpty())
        assertNull(vm.uiState.value.error)
    }

    @Test
    fun deleteRequiresConfirmationThenRemovesProfileAndPassword() = runTest {
        runBlocking {
            repo.saveProfile(profile("doomed", "Doomed"))
        }
        credentials.saveSecret("doomed", "topsecret".toCharArray())

        val vm = ProfilesViewModel(repo, credentials, backgroundScope)
        pumpAll()
        val target = vm.uiState.value.profiles.first { it.id == "doomed" }

        // Request shows the confirmation; nothing is deleted yet.
        vm.requestDelete(target)
        assertEquals("doomed", vm.uiState.value.deleteCandidate?.id)
        pumpAll()
        assertNotNull("not deleted before confirming", repo.getProfile("doomed"))

        vm.confirmDelete()
        pumpAll()

        assertNull(vm.uiState.value.deleteCandidate)
        assertNull("removed from disk", repo.getProfile("doomed"))
        assertNull("secret removed from the Keystore vault", credentials.getSecret("doomed"))
    }

    @Test
    fun cancelDeleteKeepsEverythingIntact() = runTest {
        runBlocking { repo.saveProfile(profile("keepme", "Keep me")) }
        val vm = ProfilesViewModel(repo, credentials, backgroundScope)
        pumpAll()

        vm.requestDelete(vm.uiState.value.profiles.first())
        vm.cancelDelete()

        assertNull(vm.uiState.value.deleteCandidate)
        assertNotNull(runBlocking { repo.getProfile("keepme") })
    }

    @Test
    fun storageFailureSurfacesReadableErrorAndKeepsData() = runTest {
        runBlocking { repo.saveProfile(profile("precious", "Precious")) }
        val failing = FailingDeleteRepository(repo).apply { failDeletes = true }

        val vm = ProfilesViewModel(failing, credentials, backgroundScope)
        pumpAll()
        assertEquals(1, vm.uiState.value.profiles.size)

        vm.requestDelete(vm.uiState.value.profiles.first())
        vm.confirmDelete()
        pumpAll()

        val error = vm.uiState.value.error
        assertNotNull("failure surfaced to the UI", error)
        assertTrue(error!!.contains("Precious"))
        assertNull("confirmation cleared", vm.uiState.value.deleteCandidate)
        // Data untouched — the real repository never saw the delete.
        assertNotNull(repo.getProfile("precious"))

        vm.clearError()
        assertNull(vm.uiState.value.error)
    }

    @Test
    fun markConnectedUpdatesTimestampForOneTapConnectBookkeeping() = runTest {
        runBlocking { repo.saveProfile(profile("t", "Timed")) }
        val vm = ProfilesViewModel(repo, credentials, backgroundScope)
        pumpAll()
        assertNull(vm.uiState.value.profiles.first().lastConnectedTimestamp)

        vm.markConnected(vm.uiState.value.profiles.first())
        pumpAll()

        assertNotNull(vm.uiState.value.profiles.first().lastConnectedTimestamp)
    }
}
