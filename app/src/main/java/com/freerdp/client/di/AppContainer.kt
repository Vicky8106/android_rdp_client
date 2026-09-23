package com.freerdp.client.di

import android.content.Context
import android.content.SharedPreferences
import android.view.View
import com.freerdp.client.session.AndroidClipboardBridge
import com.freerdp.client.session.AndroidNetworkMonitor
import com.freerdp.client.session.ClipboardBridge
import com.freerdp.client.session.HapticMouseController
import com.freerdp.client.session.NetworkMonitor
import com.freerdp.client.session.ReconnectTuning
import com.freerdp.client.session.SessionViewModel
import com.freerdp.client.settings.AppSettingsRepository
import com.freerdp.core.engine.IRdpEngine
import com.freerdp.core.engine.MockRdpEngine
import com.freerdp.core.engine.NativeFreeRdpEngine
import com.freerdp.feature.mouse.CoordinateTransformer
import com.freerdp.feature.mouse.GestureDisambiguationEngine
import com.freerdp.feature.mouse.GestureEventListener
import com.freerdp.feature.session.ProfileRepository
import com.freerdp.feature.session.data.AtomicFileProfileRepository
import com.freerdp.feature.session.security.CredentialStore
import com.freerdp.feature.session.security.KeystoreCredentialStore
import com.freerdp.feature.telemetry.metrics.TelemetryCollector
import com.freerdp.feature.telemetry.pacer.FramePacer
import com.freerdp.feature.telemetry.preset.AdaptivePresetSwitcher
import com.freerdp.feature.telemetry.preset.PerformancePresetAdapter
import com.freerdp.feature.telemetry.reconnect.AutoReconnectManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.io.File

/**
 * Manual dependency container for the application (PROJECT.md: ":app — AppContainer
 * DI & Lifecycle Coordination"). One instance lives for the whole process, so state
 * held here (session ViewModel, telemetry, repositories) survives rotation.
 *
 * Engine selection: [engineOverride] is used when provided (tests); otherwise the
 * Demo engine ([MockRdpEngine]) is used when the user enabled Demo mode in Settings
 * and the real [NativeFreeRdpEngine] for actual sessions. When the native `.so`
 * libraries are missing, NativeFreeRdpEngine fails gracefully (code 1001) and the
 * session UI turns that into a clear error + retry flow.
 */
class AppContainer(
    val appContext: Context,
    private val engineOverride: ((demo: Boolean) -> IRdpEngine)? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val sessionScopeOverride: CoroutineScope? = null,
    val reconnectTuning: ReconnectTuning = ReconnectTuning(),
    profileStorageFile: File? = null,
    settingsPrefs: SharedPreferences? = null,
    private val clipboardOverride: ClipboardBridge? = null,
    private val networkMonitorOverride: ((AutoReconnectManager) -> NetworkMonitor)? = null
) {

    // ------------------------------------------------------------------ core

    val settings: AppSettingsRepository =
        settingsPrefs?.let { AppSettingsRepository(it) } ?: AppSettingsRepository(appContext)

    val profileRepository: ProfileRepository = AtomicFileProfileRepository(
        storageFile = profileStorageFile ?: File(appContext.filesDir, "profiles.json"),
        ioDispatcher = ioDispatcher
    )

    val credentialStore: CredentialStore =
        KeystoreCredentialStore(appContext, prefFileName = "secure_rdp_vault")

    // ------------------------------------------------------------ telemetry ---

    val telemetry = TelemetryCollector()

    /** Single-slot blit-latest pacer; dropped frames feed the telemetry HUD. */
    val framePacer = FramePacer(onFrameDroppedCallback = { telemetry.recordFrameDropped() })

    /** Bandwidth/battery aware preset recommendation (used by the Settings screen). */
    val presetAdapter: PerformancePresetAdapter
        get() = PerformancePresetAdapter

    /**
     * Anti-flap adaptive preset selection (3 confirmations + 3 s dwell hysteresis).
     * Owned at container scope so its active preset survives rotation; the Settings
     * "suggested right now" chip feeds it network + battery samples via
     * [com.freerdp.feature.telemetry.preset.AdaptivePresetSwitcher.evaluate] instead of
     * calling the raw adapter (which has no anti-flap protection).
     */
    val presetSwitcher = AdaptivePresetSwitcher()

    // ---------------------------------------------------------- factories ----

    fun engineFor(demo: Boolean): IRdpEngine =
        engineOverride?.invoke(demo) ?: if (demo) {
            MockRdpEngine()
        } else {
            NativeFreeRdpEngine(appContext.applicationContext, ioDispatcher)
        }

    fun createGestureEngine(listener: GestureEventListener): GestureDisambiguationEngine =
        GestureDisambiguationEngine(listener = listener)

    fun createMouseController(
        engine: IRdpEngine,
        transformer: CoordinateTransformer,
        hapticTarget: View?
    ): HapticMouseController = HapticMouseController.create(engine, transformer, hapticTarget)

    // ------------------------------------------------- session lifecycle -----

    private var sessionViewModel: SessionViewModel? = null
    private var sessionProfileId: String? = null

    /**
     * Cached so the session survives Activity recreation (rotation): the container
     * outlives the UI. Released only after the user confirms disconnect.
     */
    fun sessionViewModel(initialProfileId: String? = null): SessionViewModel {
        val existing = sessionViewModel
        if (existing != null && initialProfileId != null && sessionProfileId != initialProfileId) {
            // Switching to a different profile without an explicit release — start clean.
            releaseSessionViewModel()
        }
        sessionViewModel?.let { return it }
        sessionProfileId = initialProfileId
        val demo = settings.current.demoEngine
        return SessionViewModel(
            engine = engineFor(demo),
            demoMode = demo,
            profileRepository = profileRepository,
            credentialStore = credentialStore,
            settings = settings,
            telemetry = telemetry,
            framePacer = framePacer,
            clipboard = clipboardOverride ?: AndroidClipboardBridge(appContext),
            networkMonitorFactory = networkMonitorOverride
                ?: { manager -> AndroidNetworkMonitor(appContext, manager) },
            gestureEngineFactory = { listener -> createGestureEngine(listener) },
            mouseControllerFactory = { engine, transformer, target ->
                createMouseController(engine, transformer, target)
            },
            scopeOverride = sessionScopeOverride,
            connectDispatcher = ioDispatcher,
            reconnectTuning = reconnectTuning,
            initialProfileId = initialProfileId
        ).also { sessionViewModel = it }
    }

    fun releaseSessionViewModel() {
        sessionViewModel?.terminate()
        sessionViewModel = null
    }

    /**
     * One-tap recovery from error 1001 (native FreeRDP `.so` not packaged): flips the
     * Settings "Demo engine" switch on — persistently, via
     * [AppSettingsRepository.setDemoEngine] — and releases the cached session ViewModel
     * so the next [sessionViewModel] call rebuilds the session on the demo engine
     * ([MockRdpEngine]). The session screen re-enters afterwards, which retries the
     * connect on the engine that actually works in this build.
     */
    fun enableDemoEngineAndRestart() {
        settings.setDemoEngine(true)
        releaseSessionViewModel()
    }
}
