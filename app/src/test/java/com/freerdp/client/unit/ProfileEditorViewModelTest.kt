package com.freerdp.client.unit

import android.content.Context
import com.freerdp.client.ui.editor.ProfileEditorViewModel
import com.freerdp.client.ui.editor.ProfileEditorState
import com.freerdp.feature.session.CredentialStorageType
import com.freerdp.feature.session.RdpProfile
import com.freerdp.feature.session.data.AtomicFileProfileRepository
import com.freerdp.feature.session.security.KeystoreCredentialStore
import com.freerdp.core.engine.PerformancePreset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.runBlocking
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
import java.io.File

/**
 * Profile editor: validation gating, password flow through the REAL Keystore vault
 * (never into the JSON store), edit-keeps-password semantics and delete.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ProfileEditorViewModelTest {

    private lateinit var context: Context
    private lateinit var repo: AtomicFileProfileRepository
    private lateinit var credentials: KeystoreCredentialStore
    private lateinit var storageFile: File

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        storageFile = File(context.filesDir, "profiles_editor_test.json")
        storageFile.delete()
        repo = AtomicFileProfileRepository(storageFile, Dispatchers.Unconfined)
        credentials = KeystoreCredentialStore(context, prefFileName = "editor_vault")
        credentials.clearAll()
    }

    private fun fillValid(vm: ProfileEditorViewModel) {
        vm.onLabel("My workstation")
        vm.onHostname("pc.corp.example")
        vm.onPort("3389")
        vm.onUsername("bob")
        vm.onDomain("CORP")
    }

    @Test
    fun invalidFieldsBlockSaveWithInlineErrors() = runTest {
        val vm = ProfileEditorViewModel(repo, credentials, profileId = null, scopeOverride = backgroundScope)
        pumpAll()

        vm.onLabel("")
        vm.onHostname("http://bad")
        vm.onPort("0")
        vm.onDomain("bad domain")
        vm.save()
        pumpAll()

        val state = vm.state.value
        assertNotNull(state.errors.label)
        assertNotNull(state.errors.hostname)
        assertNotNull(state.errors.port)
        assertNotNull(state.errors.domain)
        assertFalse("still pristine — a blocked save must never mark the form saved", state.saved)
        assertEquals("nothing persisted", 0, repo.getAllProfiles().first().size)
    }

    @Test
    fun validCreateStoresProfileWithoutPasswordAndSecretInVault() = runTest {
        val vm = ProfileEditorViewModel(repo, credentials, profileId = null, scopeOverride = backgroundScope)
        pumpAll()
        fillValid(vm)
        vm.onPassword("Sup3rSecret!")
        vm.onPreset(PerformancePreset.DATA_SAVER)
        vm.save()
        pumpAll()

        val state = vm.state.value
        assertTrue(state.saved)
        assertFalse(state.saving)
        val id = state.profileId!!

        val stored = repo.getProfile(id)
        assertNotNull(stored)
        assertEquals("pc.corp.example", stored!!.hostname)
        assertEquals(3389, stored.port)
        assertEquals("bob", stored.username)
        assertEquals("CORP", stored.domain)
        assertEquals(PerformancePreset.DATA_SAVER, stored.performancePreset)

        // Password: present in the vault, ABSENT from the JSON store on disk.
        assertEquals("Sup3rSecret!", String(credentials.getSecret(id) ?: CharArray(0)))
        val rawJson = storageFile.readText()
        assertFalse("password must never touch the JSON profile store", rawJson.contains("Sup3rSecret!"))
    }

    @Test
    fun editingWithBlankPasswordKeepsExistingSecret() = runTest {
        val existing = RdpProfile(
            id = "edit-me",
            label = "Old label",
            hostname = "old.example.com",
            username = "olduser"
        )
        runBlocking { repo.saveProfile(existing) }
        credentials.saveSecret("edit-me", "original-pw".toCharArray())

        val vm = ProfileEditorViewModel(repo, credentials, profileId = "edit-me", scopeOverride = backgroundScope)
        pumpAll()

        // Form populated from the stored profile (password never prefilled).
        val initial = vm.state.value
        assertFalse(initial.isNew)
        assertEquals("Old label", initial.label)
        assertEquals("old.example.com", initial.hostname)
        assertEquals("", initial.password)

        vm.onLabel("Renamed")
        vm.onHostname("new.example.com")
        vm.save() // blank password
        pumpAll()

        assertTrue(vm.state.value.saved)
        val updated = repo.getProfile("edit-me")
        assertEquals("Renamed", updated!!.label)
        assertEquals("new.example.com", updated.hostname)
        assertEquals(
            "stored secret untouched by a blank password field",
            "original-pw",
            String(credentials.getSecret("edit-me") ?: CharArray(0))
        )
    }

    @Test
    fun storageTypeNoneDropsStoredSecretOnSave() = runTest {
        val existing = RdpProfile(
            id = "nopw",
            label = "No password",
            hostname = "np.example.com",
            credentialStorageType = CredentialStorageType.KEYSTORE_ENCRYPTED
        )
        runBlocking { repo.saveProfile(existing) }
        credentials.saveSecret("nopw", "stale".toCharArray())

        val vm = ProfileEditorViewModel(repo, credentials, profileId = "nopw", scopeOverride = backgroundScope)
        pumpAll()
        vm.onCredentialStorageChanged(CredentialStorageType.NONE)
        vm.save()
        pumpAll()

        assertTrue(vm.state.value.saved)
        assertNull("secret dropped when storage switched off", credentials.getSecret("nopw"))
    }

    @Test
    fun deleteRemovesProfileAndSecretAfterConfirmation() = runTest {
        val existing = RdpProfile(id = "gone", label = "Gone", hostname = "g.example.com")
        runBlocking { repo.saveProfile(existing) }
        credentials.saveSecret("gone", "pw".toCharArray())

        val vm = ProfileEditorViewModel(repo, credentials, profileId = "gone", scopeOverride = backgroundScope)
        pumpAll()

        vm.requestDelete()
        assertTrue(vm.state.value.deleteConfirmVisible)
        vm.cancelDelete()
        assertFalse("cancel keeps the dialog closed and the data alive", vm.state.value.deleteConfirmVisible)
        assertNotNull(repo.getProfile("gone"))

        vm.requestDelete()
        vm.confirmDelete()
        pumpAll()

        assertTrue(vm.state.value.deleted)
        assertNull(repo.getProfile("gone"))
        assertNull(credentials.getSecret("gone"))
    }

    @Test
    fun missingProfileSurfacesErrorInsteadOfEmptyFormConfusion() = runTest {
        val vm = ProfileEditorViewModel(repo, credentials, profileId = "ghost", scopeOverride = backgroundScope)
        pumpAll()

        val state = vm.state.value
        assertFalse(state.loading)
        assertNotNull(state.error)
        assertTrue("readable message", state.error!!.isNotBlank())
    }

    @Test
    fun portInputIsDigitFiltered() = runTest {
        val vm = ProfileEditorViewModel(repo, credentials, profileId = null, scopeOverride = backgroundScope)
        pumpAll()
        vm.onPort("12ab34x")
        assertEquals("1234", vm.state.value.port)
    }
}
