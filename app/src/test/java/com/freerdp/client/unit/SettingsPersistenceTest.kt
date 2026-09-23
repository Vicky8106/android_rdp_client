package com.freerdp.client.unit

import android.content.Context
import com.freerdp.client.settings.AppSettingsRepository
import com.freerdp.client.settings.ThemeMode
import com.freerdp.client.settings.TrustedCertificate
import com.freerdp.client.ui.settings.SettingsViewModel
import com.freerdp.core.engine.PerformancePreset
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Settings persistence (survives process death via SharedPreferences), the
 * trust-on-first-use certificate list lifecycle and the live performance-preset
 * recommendation from the real PerformancePresetAdapter (network + battery).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SettingsPersistenceTest {

    private lateinit var context: Context
    private lateinit var prefsName: String

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        prefsName = "settings_test_prefs"
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun allSettingsRoundTripThroughDisk() {
        val repo = AppSettingsRepository(
            context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        )
        repo.setThemeMode(ThemeMode.DARK)
        repo.setDynamicColor(false)
        repo.setHudEnabled(false)
        repo.setTouchpadDefault(true)
        repo.setDemoEngine(true)
        repo.setDefaultPerformancePreset(PerformancePreset.DATA_SAVER)
        repo.setPresetOverridesProfiles(true)

        // Brand-new repository instance == new process after death.
        val reloaded = AppSettingsRepository(
            context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        )
        val s = reloaded.current
        assertEquals(ThemeMode.DARK, s.themeMode)
        assertFalse(s.dynamicColor)
        assertFalse(s.hudEnabled)
        assertTrue(s.touchpadDefault)
        assertTrue(s.demoEngine)
        assertEquals(PerformancePreset.DATA_SAVER, s.defaultPerformancePreset)
        assertTrue(s.presetOverridesProfiles)
    }

    @Test
    fun stateFlowEmitsOnEveryChangeForReactiveUi() {
        val repo = AppSettingsRepository(
            context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        )
        val seen = mutableListOf<ThemeMode>()
        kotlinx.coroutines.runBlocking {
            val job = launch {
                repo.settings.collect { seen.add(it.themeMode) }
            }
            yield() // collector captures the initial value
            repo.setThemeMode(ThemeMode.LIGHT)
            yield()
            repo.setThemeMode(ThemeMode.DARK)
            yield()
            job.cancelAndJoin()
        }
        assertTrue(seen.isNotEmpty())
        assertEquals(ThemeMode.DARK, seen.last())
        assertEquals(ThemeMode.DARK, repo.current.themeMode)
    }

    @Test
    fun trustedCertificateLifecycleFromTrustToRevocation() {
        val repo = AppSettingsRepository(
            context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        )
        assertFalse(repo.isCertificateTrusted("gw.example.com", "SHA256:ABCD"))

        repo.trustCertificate("gw.example.com", "SHA256:ABCD")
        assertTrue(repo.isCertificateTrusted("gw.example.com", "SHA256:ABCD"))
        // Trust is per host: another server's fingerprint stays untrusted.
        assertFalse(repo.isCertificateTrusted("other.example.com", "SHA256:ABCD"))

        // Duplicate trust calls do not create duplicate rows.
        repo.trustCertificate("gw.example.com", "SHA256:ABCD")
        assertEquals(1, repo.current.trustedCertificates.size)

        // Survives "process death".
        val reloaded = AppSettingsRepository(
            context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        )
        assertEquals(
            listOf(TrustedCertificate("gw.example.com", "SHA256:ABCD")),
            reloaded.current.trustedCertificates
        )

        reloaded.revokeCertificate("gw.example.com", "SHA256:ABCD")
        assertFalse(reloaded.isCertificateTrusted("gw.example.com", "SHA256:ABCD"))
        assertTrue(reloaded.current.trustedCertificates.isEmpty())
    }

    @Test
    fun settingsViewModelDelegatesEveryControl() {
        val repo = AppSettingsRepository(
            context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        )
        val vm = SettingsViewModel(repo, context)

        vm.setThemeMode(ThemeMode.LIGHT)
        vm.setDynamicColor(false)
        vm.setHudEnabled(false)
        vm.setTouchpadDefault(true)
        vm.setDemoEngine(true)
        vm.setDefaultPerformancePreset(PerformancePreset.BATTERY_SAVER)
        vm.setPresetOverridesProfiles(true)
        vm.revokeCertificate(TrustedCertificate("x", "y")) // no-op, must not throw

        val s = vm.appSettings.value
        assertEquals(ThemeMode.LIGHT, s.themeMode)
        assertFalse(s.dynamicColor)
        assertFalse(s.hudEnabled)
        assertTrue(s.touchpadDefault)
        assertTrue(s.demoEngine)
        assertEquals(PerformancePreset.BATTERY_SAVER, s.defaultPerformancePreset)
        assertTrue(s.presetOverridesProfiles)
        assertTrue(s.trustedCertificates.isEmpty())
    }

    @Test
    fun livePresetRecommendationUsesRealNetworkAndBatteryAdapter() {
        val repo = AppSettingsRepository(
            context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        )
        val vm = SettingsViewModel(repo, context)
        val recommendation = vm.recommendedPreset()
        assertNotNull(
            "PerformancePresetAdapter must produce a preset under Robolectric",
            recommendation
        )
        assertTrue(recommendation!! in PerformancePreset.entries)
    }
}
