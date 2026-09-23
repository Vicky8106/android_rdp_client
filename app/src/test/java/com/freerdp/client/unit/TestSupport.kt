package com.freerdp.client.unit

import android.content.Context
import com.freerdp.client.session.AndroidClipboardBridge
import com.freerdp.client.session.ReconnectTuning
import com.freerdp.client.session.SessionViewModel
import com.freerdp.client.settings.AppSettingsRepository
import com.freerdp.core.engine.MockRdpEngine
import com.freerdp.feature.session.CredentialStorageType
import com.freerdp.feature.session.RdpProfile
import com.freerdp.feature.session.data.AtomicFileProfileRepository
import com.freerdp.feature.session.security.KeystoreCredentialStore
import com.freerdp.feature.telemetry.metrics.TelemetryCollector
import com.freerdp.feature.telemetry.pacer.FramePacer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import java.io.File
import java.io.IOException

/**
 * Drains ALL scheduled work on the shared virtual scheduler — foreground AND background.
 *
 * kotlinx-coroutines-test 1.9.0's `TestScope.advanceUntilIdle()` deliberately stops as
 * soon as only background-tagged events remain in the queue
 * (`TestCoroutineScheduler.advanceUntilIdleOr { events.none(TestDispatchEvent<*>::isForeground) }`),
 * and every coroutine launched in `TestScope.backgroundScope` carries the `BackgroundWork`
 * marker. A plain `advanceUntilIdle()` therefore leaves backgroundScope work — every
 * ViewModel init collector, sessionScope chain, reconnect job — unexecuted.
 *
 * `advanceTimeBy()` has no such foreground filter: it runs every event scheduled before
 * the target time (cascades included), which is the "runs all enqueued tasks" behaviour
 * the app tests were written against. `runCurrent()` then sweeps anything registered at
 * exactly the horizon. No assertion in any test is affected — this only guarantees the
 * work actually runs.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun TestScope.pumpAll(horizonMs: Long = 600_000L) {
    advanceTimeBy(horizonMs)
    runCurrent()
}

/**
 * Shared helpers for the app-level ViewModel tests. Everything here wires the REAL
 * production components (real repository over a temp file, real Keystore vault with its
 * AES-GCM fallback, real MockRdpEngine test double, real telemetry) — the only injected
 * parts are coroutine dispatchers (virtual time) and fault points.
 */

/** Polls [condition] on the test thread until it holds or [timeoutMs] elapses. */
fun awaitUntil(timeoutMs: Long = 5000, description: String = "condition", condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (condition()) return
        Thread.sleep(10)
    }
    if (!condition()) {
        throw AssertionError("Timed out after ${timeoutMs}ms waiting for: $description")
    }
}

/** Everything a session test needs, wired exactly like AppContainer does in production. */
class SessionHarness(
    val vm: SessionViewModel,
    val mock: MockRdpEngine,
    val repo: AtomicFileProfileRepository,
    val settings: AppSettingsRepository,
    val credentials: KeystoreCredentialStore,
    val telemetry: TelemetryCollector,
    val pacer: FramePacer,
    val bridge: AndroidClipboardBridge,
    val profile: RdpProfile
)

/**
 * Builds a SessionViewModel on top of real dependencies. [connectOnVirtualTime] routes
 * engine.connect through the shared test scheduler (fully deterministic); certificate
 * tests disable it because the TOFU gate blocks its calling thread awaiting the user.
 */
fun TestScope.sessionHarness(
    context: Context,
    profile: RdpProfile,
    mock: MockRdpEngine = MockRdpEngine(),
    storedPassword: String? = "s3cret",
    settings: AppSettingsRepository = AppSettingsRepository(
        context.getSharedPreferences("test_app_settings", Context.MODE_PRIVATE)
    ),
    scope: CoroutineScope = backgroundScope,
    dispatcher: TestDispatcher = StandardTestDispatcher(testScheduler),
    connectDispatcher: CoroutineDispatcher? = null,
    repoFile: File = File(context.filesDir, "profiles_test.json"),
    certificateDecisionTimeoutMs: Long = 60_000L
): SessionHarness {
    // The REAL repository over a real file; Unconfined makes its file IO run inline
    // (fully deterministic without depending on the virtual scheduler being pumped).
    val repo = AtomicFileProfileRepository(repoFile, Dispatchers.Unconfined)
    val credentials = KeystoreCredentialStore(context, prefFileName = "test_secure_rdp_vault")
    val telemetry = TelemetryCollector()
    val pacer = FramePacer(onFrameDroppedCallback = { telemetry.recordFrameDropped() })
    val bridge = AndroidClipboardBridge(context)

    // Seed the profile and (optionally) its Keystore secret.
    runBlocking {
        repo.saveProfile(profile)
    }
    if (storedPassword != null) {
        credentials.saveSecret(profile.id, storedPassword.toCharArray())
    }

    val vm = SessionViewModel(
        engine = mock,
        demoMode = true,
        profileRepository = repo,
        credentialStore = credentials,
        settings = settings,
        telemetry = telemetry,
        framePacer = pacer,
        clipboard = bridge,
        networkMonitorFactory = { manager ->
            com.freerdp.client.session.AndroidNetworkMonitor(context, manager)
        },
        scopeOverride = scope,
        connectDispatcher = connectDispatcher ?: dispatcher,
        reconnectTuning = ReconnectTuning(
            maxAttempts = 3,
            baseDelayMs = 100L,
            maxDelayMs = 100L,
            jitter = { min, _ -> min }
        ),
        toolbarScopeOverride = scope,
        toolbarDispatcher = dispatcher,
        layoutDebounceMs = 250L,
        dropSettleDelayMs = 250L,
        certificateDecisionTimeoutMs = certificateDecisionTimeoutMs
    )
    return SessionHarness(vm, mock, repo, settings, credentials, telemetry, pacer, bridge, profile)
}

/** Standard profile with NLA + keystore credentials (secret seeded by [sessionHarness]). */
fun testProfile(
    id: String = "profile-1",
    hostname: String = "work.example.com",
    storage: CredentialStorageType = CredentialStorageType.KEYSTORE_ENCRYPTED,
    autoReconnect: Boolean = true
): RdpProfile = RdpProfile(
    id = id,
    label = "Work PC",
    hostname = hostname,
    port = 3389,
    username = "alice",
    domain = "CORP",
    credentialStorageType = storage,
    networkConfig = com.freerdp.feature.session.NetworkConfig(autoReconnect = autoReconnect)
)

/** Fault-injection wrapper around the REAL repository (delete path only). */
class FailingDeleteRepository(
    private val delegate: AtomicFileProfileRepository
) : com.freerdp.feature.session.ProfileRepository by delegate {
    @Volatile
    var failDeletes: Boolean = false

    override suspend fun deleteProfile(id: String): Boolean {
        if (failDeletes) throw IOException("Simulated disk failure")
        return delegate.deleteProfile(id)
    }
}
