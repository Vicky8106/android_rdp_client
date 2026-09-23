package com.freerdp.client.unit

import android.content.Context
import com.freerdp.client.di.AppContainer
import com.freerdp.client.session.ReconnectTuning
import com.freerdp.client.session.SessionPhase
import com.freerdp.core.engine.MockRdpEngine
import com.freerdp.core.engine.NativeFreeRdpEngine
import com.freerdp.feature.session.CredentialStorageType
import com.freerdp.feature.session.data.AtomicFileProfileRepository
import com.freerdp.feature.session.security.KeystoreCredentialStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * AppContainer manual-DI wiring: engine selection (demo Mock vs real Native),
 * dependency availability and the rotation-surviving session ViewModel cache.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AppContainerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    private fun newContainer(
        engineOverride: ((Boolean) -> com.freerdp.core.engine.IRdpEngine)? = null
    ): AppContainer = AppContainer(
        appContext = context,
        engineOverride = engineOverride,
        ioDispatcher = Dispatchers.Unconfined
    )

    @Test
    fun engineSelectionPicksMockForDemoAndNativeForRealSessions() {
        val container = newContainer()
        assertTrue("demo mode uses the deterministic test double", container.engineFor(true) is MockRdpEngine)
        assertTrue("real sessions use the FreeRDP wrapper", container.engineFor(false) is NativeFreeRdpEngine)
    }

    @Test
    fun engineOverrideIsRespectedForTests() {
        val mock = MockRdpEngine()
        val container = newContainer(engineOverride = { mock })
        assertSame(mock, container.engineFor(true))
        assertSame(mock, container.engineFor(false))
    }

    @Test
    fun allWiredDependenciesArePresent() {
        val container = newContainer()
        assertNotNull(container.settings)
        assertNotNull(container.profileRepository)
        assertNotNull(container.credentialStore)
        assertNotNull(container.telemetry)
        assertNotNull(container.framePacer)
        assertNotNull(container.presetAdapter)

        // Factories produce working instances.
        val transformer = com.freerdp.feature.mouse.CoordinateTransformer(1920, 1080, 1080, 2400)
        assertNotNull(container.createMouseController(MockRdpEngine(), transformer, null))
        assertNotNull(
            container.createGestureEngine(object : com.freerdp.feature.mouse.GestureEventListener {})
        )
    }

    @Test
    fun credentialStoreAndRepositoryAreRealProductionTypes() {
        val container = newContainer()
        assertTrue(container.credentialStore is KeystoreCredentialStore)
        assertTrue(container.profileRepository is AtomicFileProfileRepository)

        // Repository defaults to app-private storage.
        val defaultFile = File(context.filesDir, "profiles.json")
        val wired = AtomicFileProfileRepository(defaultFile, Dispatchers.Unconfined)
        assertNotNull(wired.getAllProfiles())
    }

    @Test
    fun sessionViewModelIsCachedAcrossRotationAndReleasedOnExit() {
        val mock = MockRdpEngine()
        val container = newContainer(engineOverride = { mock })

        val first = container.sessionViewModel("p1")
        val sameInstance = container.sessionViewModel("p1")
        assertSame("survives Activity recreation", first, sameInstance)

        // Asking for a DIFFERENT profile without releasing starts clean.
        val other = container.sessionViewModel("p2")
        assertNotSame(first, other)

        container.releaseSessionViewModel()
        val fresh = container.sessionViewModel("p1")
        assertNotSame(other, fresh)
    }

    @Test
    fun sessionViewModelWiresRealDependenciesAndStartsIdle() {
        val mock = MockRdpEngine()
        val container = AppContainer(
            appContext = context,
            engineOverride = { mock },
            ioDispatcher = Dispatchers.Unconfined,
            reconnectTuning = ReconnectTuning(maxAttempts = 2, baseDelayMs = 10, maxDelayMs = 10, jitter = { m, _ -> m })
        )
        val vm = container.sessionViewModel("profile-x")
        assertTrue(vm.phase.value is com.freerdp.client.session.SessionPhase.Idle)
        assertNotNull(vm.telemetry)
        assertNotNull(vm.framePacer)
        assertNotNull(vm.networkMonitor)
        assertNotNull(vm.reconnectManager)
        container.releaseSessionViewModel()
    }

    @Test
    fun settingsRepositoryDefaultsAreProductSane() {
        val container = newContainer()
        val s = container.settings.current
        assertEquals(com.freerdp.client.settings.ThemeMode.SYSTEM, s.themeMode)
        assertTrue(s.dynamicColor)
        assertTrue(s.hudEnabled)
        // Demo OFF by default: real sessions target the native engine.
        assertTrue(!s.demoEngine)
        assertTrue(s.trustedCertificates.isEmpty())
        assertEquals(com.freerdp.core.engine.PerformancePreset.BALANCED, s.defaultPerformancePreset)
    }

    /**
     * Failure-UI one-tap action for error 1001 (challenger product gap 1): flipping the
     * demo switch must PERSIST the setting, tear down the native-engine session VM, and
     * make the next session build run on the demo engine — after which the retried
     * connect actually reaches Connected(demo=true) instead of dead-ending at 1001.
     */
    @Test
    fun enableDemoEngineAndRestartPersistsSettingRebuildsSessionAndRetriesConnect() = runTest {
        val demosRequested = mutableListOf<Boolean>()
        val settingsPrefs = context
            .getSharedPreferences("demo_restart_settings_test", Context.MODE_PRIVATE)
        settingsPrefs.edit().clear().commit()
        val profileFile = File(context.filesDir, "demo_restart_profiles_test.json")
        profileFile.delete()

        val container = AppContainer(
            appContext = context,
            engineOverride = { demo -> demosRequested.add(demo); MockRdpEngine() },
            ioDispatcher = Dispatchers.Unconfined,
            sessionScopeOverride = backgroundScope,
            settingsPrefs = settingsPrefs,
            profileStorageFile = profileFile
        )
        val profile = testProfile(id = "demo-restart-profile", storage = CredentialStorageType.NONE)
        runBlocking { container.profileRepository.saveProfile(profile) }

        // --- Before: native (demo OFF) session cached ---
        val first = container.sessionViewModel(profile.id)
        assertFalse(container.settings.current.demoEngine)
        assertEquals(listOf(false), demosRequested)
        assertFalse(first.isDemo)

        // --- The one-tap action ---
        container.enableDemoEngineAndRestart()

        // (1) AppSettingsRepository.demoEngine flipped AND persisted to disk.
        assertTrue("setting must flip in-memory", container.settings.current.demoEngine)
        assertTrue(
            "setting must survive a settings reload from disk",
            com.freerdp.client.settings.AppSettingsRepository(settingsPrefs).current.demoEngine
        )
        // (2) The old native-engine VM was fully torn down.
        assertTrue("released session VM must be shut down", first.reconnectManager.isShutDown)

        // (3) The next session builds on the DEMO engine.
        val second = container.sessionViewModel(profile.id)
        assertNotSame("fresh VM on the demo engine", first, second)
        assertTrue("rebuilt VM runs in demo mode", second.isDemo)
        assertEquals(listOf(false, true), demosRequested)

        // (4) The retried connect (what the session screen's start effect invokes)
        //     now succeeds on the demo engine and reports demo=true.
        second.ensureStarted(profile.id)
        pumpAll()
        val phase = second.phase.value
        assertTrue("retried connect must reach Connected, was $phase", phase is SessionPhase.Connected)
        assertTrue((phase as SessionPhase.Connected).demo)

        container.releaseSessionViewModel()
    }
}
