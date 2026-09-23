package com.freerdp.client.settings

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import com.freerdp.core.engine.PerformancePreset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** UI theme selection strategy. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** A trust-on-first-use certificate decision persisted by the user. */
data class TrustedCertificate(
    val host: String,
    val fingerprint: String
)

/** Immutable snapshot of every application-level preference. */
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val hudEnabled: Boolean = true,
    val touchpadDefault: Boolean = false,
    val demoEngine: Boolean = false,
    val defaultPerformancePreset: PerformancePreset = PerformancePreset.BALANCED,
    val presetOverridesProfiles: Boolean = false,
    val trustedCertificates: List<TrustedCertificate> = emptyList()
)

/**
 * Application settings backed by SharedPreferences with a reactive [StateFlow].
 *
 * Also owns the trust-on-first-use (TOFU) certificate store: every certificate the
 * user explicitly trusts during [com.freerdp.core.engine.RdpEventListener.onCertificateVerification]
 * is recorded here and surfaced for revocation in the Settings screen.
 */
class AppSettingsRepository(
    private val prefs: SharedPreferences,
    prefFileName: String = PREFS_NAME
) {
    constructor(context: Context) : this(
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
        PREFS_NAME
    )

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    val current: AppSettings get() = _settings.value

    private fun load(): AppSettings {
        val trusted = prefs.getStringSet(KEY_TRUSTED, emptySet())
            .orEmpty()
            .mapNotNull { entry ->
                val sep = entry.indexOf('|')
                if (sep <= 0 || sep == entry.lastIndex) null
                else TrustedCertificate(
                    fingerprint = entry.substring(0, sep),
                    host = entry.substring(sep + 1)
                )
            }
        return AppSettings(
            themeMode = runCatching {
                ThemeMode.valueOf(prefs.getString(KEY_THEME, ThemeMode.SYSTEM.name) ?: ThemeMode.SYSTEM.name)
            }.getOrDefault(ThemeMode.SYSTEM),
            dynamicColor = prefs.getBoolean(KEY_DYNAMIC_COLOR, true),
            hudEnabled = prefs.getBoolean(KEY_HUD, true),
            touchpadDefault = prefs.getBoolean(KEY_TOUCHPAD, false),
            demoEngine = prefs.getBoolean(KEY_DEMO_ENGINE, false),
            defaultPerformancePreset = runCatching {
                PerformancePreset.valueOf(
                    prefs.getString(KEY_PRESET, PerformancePreset.BALANCED.name) ?: PerformancePreset.BALANCED.name
                )
            }.getOrDefault(PerformancePreset.BALANCED),
            presetOverridesProfiles = prefs.getBoolean(KEY_PRESET_OVERRIDE, false),
            trustedCertificates = trusted
        )
    }

    @SuppressLint("ApplySharedPref")
    private fun persist(transform: (AppSettings) -> AppSettings) {
        val next = transform(_settings.value)
        prefs.edit()
            .putString(KEY_THEME, next.themeMode.name)
            .putBoolean(KEY_DYNAMIC_COLOR, next.dynamicColor)
            .putBoolean(KEY_HUD, next.hudEnabled)
            .putBoolean(KEY_TOUCHPAD, next.touchpadDefault)
            .putBoolean(KEY_DEMO_ENGINE, next.demoEngine)
            .putString(KEY_PRESET, next.defaultPerformancePreset.name)
            .putBoolean(KEY_PRESET_OVERRIDE, next.presetOverridesProfiles)
            .putStringSet(
                KEY_TRUSTED,
                next.trustedCertificates.map { "${it.fingerprint}|${it.host}" }.toSet()
            )
            .commit()
        _settings.value = load()
    }

    fun setThemeMode(mode: ThemeMode) = persist { it.copy(themeMode = mode) }

    fun setDynamicColor(enabled: Boolean) = persist { it.copy(dynamicColor = enabled) }

    fun setHudEnabled(enabled: Boolean) = persist { it.copy(hudEnabled = enabled) }

    fun setTouchpadDefault(enabled: Boolean) = persist { it.copy(touchpadDefault = enabled) }

    fun setDemoEngine(enabled: Boolean) = persist { it.copy(demoEngine = enabled) }

    fun setDefaultPerformancePreset(preset: PerformancePreset) =
        persist { it.copy(defaultPerformancePreset = preset) }

    fun setPresetOverridesProfiles(enabled: Boolean) = persist { it.copy(presetOverridesProfiles = enabled) }

    /** Trust-on-first-use: record that the user verified [fingerprint] for [host]. */
    fun trustCertificate(host: String, fingerprint: String) = persist { s ->
        if (s.trustedCertificates.any { it.host == host && it.fingerprint == fingerprint }) s
        else s.copy(trustedCertificates = s.trustedCertificates + TrustedCertificate(host, fingerprint))
    }

    fun isCertificateTrusted(host: String, fingerprint: String): Boolean =
        _settings.value.trustedCertificates.any { it.host == host && it.fingerprint == fingerprint }

    fun revokeCertificate(host: String, fingerprint: String) = persist { s ->
        s.copy(trustedCertificates = s.trustedCertificates.filterNot { it.host == host && it.fingerprint == fingerprint })
    }

    companion object {
        const val PREFS_NAME = "app_settings"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_DYNAMIC_COLOR = "dynamic_color"
        private const val KEY_HUD = "hud_enabled"
        private const val KEY_TOUCHPAD = "touchpad_default"
        private const val KEY_DEMO_ENGINE = "demo_engine"
        private const val KEY_PRESET = "default_performance_preset"
        private const val KEY_PRESET_OVERRIDE = "preset_overrides_profiles"
        private const val KEY_TRUSTED = "trusted_certificates"
    }
}
