package com.freerdp.client.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import com.freerdp.client.settings.AppSettings
import com.freerdp.client.settings.AppSettingsRepository
import com.freerdp.client.settings.ThemeMode
import com.freerdp.client.settings.TrustedCertificate
import com.freerdp.core.engine.PerformancePreset
import com.freerdp.feature.telemetry.preset.AdaptivePresetSwitcher
import com.freerdp.feature.telemetry.preset.PerformancePresetAdapter
import kotlinx.coroutines.flow.StateFlow

/**
 * Thin, fully-delegating ViewModel for the Settings screen. All persistence happens in
 * [AppSettingsRepository] (SharedPreferences) so state is identical across process death.
 */
class SettingsViewModel(
    private val settingsRepo: AppSettingsRepository,
    private val androidContext: Context? = null,
    // Anti-flap selection with hysteresis (latency_perf §4): NEVER call
    // PerformancePresetAdapter.determinePreset directly for live recommendations —
    // it has no protection against preset thrash.
    private val presetSwitcher: AdaptivePresetSwitcher = AdaptivePresetSwitcher()
) : ViewModel() {

    val appSettings: StateFlow<AppSettings> = settingsRepo.settings

    fun setThemeMode(mode: ThemeMode) = settingsRepo.setThemeMode(mode)

    fun setDynamicColor(enabled: Boolean) = settingsRepo.setDynamicColor(enabled)

    fun setHudEnabled(enabled: Boolean) = settingsRepo.setHudEnabled(enabled)

    fun setTouchpadDefault(enabled: Boolean) = settingsRepo.setTouchpadDefault(enabled)

    fun setDemoEngine(enabled: Boolean) = settingsRepo.setDemoEngine(enabled)

    fun setDefaultPerformancePreset(preset: PerformancePreset) =
        settingsRepo.setDefaultPerformancePreset(preset)

    fun setPresetOverridesProfiles(enabled: Boolean) = settingsRepo.setPresetOverridesProfiles(enabled)

    fun revokeCertificate(certificate: TrustedCertificate) =
        settingsRepo.revokeCertificate(certificate.host, certificate.fingerprint)

    /**
     * Asks the adaptive preset switcher (hysteresis over network quality + battery)
     * what it would pick right now — shown in Settings as a live recommendation chip.
     * Returns null when no Android context is available (pure JVM unit tests).
     */
    fun recommendedPreset(): PerformancePreset? {
        val context = androidContext ?: return null
        return try {
            val quality = PerformancePresetAdapter.getNetworkQuality(context)
            val battery = PerformancePresetAdapter.getBatteryInfo(context)
            presetSwitcher.evaluate(quality, battery)
                .toCorePreset()
        } catch (t: Throwable) {
            null
        }
    }
}
