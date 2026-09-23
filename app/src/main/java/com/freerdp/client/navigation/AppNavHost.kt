package com.freerdp.client.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import com.freerdp.client.di.AppContainer
import com.freerdp.client.ui.editor.ProfileEditorScreen
import com.freerdp.client.ui.profiles.ProfileListScreen
import com.freerdp.client.ui.session.SessionScreen
import com.freerdp.client.ui.settings.SettingsScreen

/**
 * App navigation host built on [NavigationModel]. The back stack is saved across
 * process death; the profile list itself reloads from disk (AtomicFileProfileRepository).
 *
 * The session screen installs its own BackHandler for confirm-exit; Compose gives the
 * most recently registered handler priority, so the system back button asks for
 * confirmation inside a session and pops normally everywhere else.
 */
@Composable
fun AppNavHost(container: AppContainer) {
    val nav = rememberSaveable(saver = NavigationModel.Saver) { NavigationModel() }

    BackHandler(enabled = nav.canPop) { nav.pop() }

    when (val route = nav.current) {
        is AppRoute.ProfileList -> ProfileListScreen(
            container = container,
            onConnect = { profile -> nav.push(AppRoute.Session(profile.id)) },
            onEditProfile = { id -> nav.push(AppRoute.ProfileEditor(id)) },
            onOpenSettings = { nav.push(AppRoute.Settings) }
        )

        is AppRoute.ProfileEditor -> ProfileEditorScreen(
            container = container,
            profileId = route.profileId,
            onDone = { nav.pop() }
        )

        is AppRoute.Settings -> SettingsScreen(
            container = container,
            onBack = { nav.pop() }
        )

        is AppRoute.Session -> {
            // Bumped by the failure screen's "Enable demo engine & retry" action: the
            // container persists the demo switch and drops the cached (native-engine)
            // ViewModel; the key change rebuilds [vm] on the demo engine and gives
            // SessionScreen a fresh composition so its start effect runs against it.
            var sessionEpoch by remember { mutableIntStateOf(0) }
            val vm = remember(route.profileId, sessionEpoch) {
                container.sessionViewModel(route.profileId)
            }
            key(sessionEpoch) {
                SessionScreen(
                    vm = vm,
                    profileId = route.profileId,
                    onExitConfirmed = {
                        container.releaseSessionViewModel()
                        nav.pop()
                    },
                    onEnableDemoEngineAndRetry = {
                        container.enableDemoEngineAndRestart()
                        sessionEpoch++
                    }
                )
            }
        }
    }
}
