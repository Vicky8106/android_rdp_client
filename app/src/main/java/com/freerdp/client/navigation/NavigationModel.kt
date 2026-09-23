package com.freerdp.client.navigation

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.snapshots.SnapshotStateList

/** Destinations of the application. Serializable so the back stack survives process death. */
sealed interface AppRoute {
    data object ProfileList : AppRoute
    data class ProfileEditor(val profileId: String?) : AppRoute
    data object Settings : AppRoute
    data class Session(val profileId: String) : AppRoute

    fun encode(): String = when (this) {
        ProfileList -> "profiles"
        is ProfileEditor -> "editor:${profileId ?: ""}"
        Settings -> "settings"
        is Session -> "session:$profileId"
    }

    companion object {
        fun decode(raw: String): AppRoute? = when {
            raw == "profiles" -> ProfileList
            raw == "settings" -> Settings
            raw.startsWith("editor:") -> ProfileEditor(raw.removePrefix("editor:").ifBlank { null })
            raw.startsWith("session:") -> raw.removePrefix("session:")
                .takeIf { it.isNotBlank() }?.let { Session(it) }
            else -> null
        }
    }
}

/**
 * Minimal explicit back-stack model. Pure (no Android dependencies) so every push/pop
 * decision — including the session confirm-exit flow — is unit-testable.
 */
class NavigationModel(initial: List<AppRoute> = listOf(AppRoute.ProfileList)) {

    // MUST be Compose snapshot state: AppNavHost reads `current`/`canPop` during
    // composition, so push/pop have to invalidate that composition. A plain
    // ArrayList is never observed — taps updated the stack but the UI never
    // recomposed, which made every navigation button (New profile, Settings,
    // Connect, Edit, save/back) appear dead on device. Reproduced and pinned by
    // UiInteractionSmokeTest.
    private val stack: SnapshotStateList<AppRoute> = mutableStateListOf<AppRoute>().apply {
        addAll(if (initial.isEmpty()) listOf(AppRoute.ProfileList) else initial)
    }

    val current: AppRoute get() = stack.last()
    val canPop: Boolean get() = stack.size > 1
    fun snapshot(): List<AppRoute> = stack.toList()

    fun push(route: AppRoute) {
        if (route == current) return
        stack.add(route)
    }

    /** Pops one entry if possible. Returns true when the stack actually changed. */
    fun pop(): Boolean {
        if (!canPop) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    /** Replaces the whole stack (used when leaving a finished flow, e.g. after exit). */
    fun resetTo(route: AppRoute) {
        stack.clear()
        stack.add(route)
    }

    companion object {
        /** listSaver produces Saver<Original, Any>; entries are encoded route strings. */
        val Saver: Saver<NavigationModel, Any> =
            listSaver(save = { model -> model.snapshot().map { it.encode() } },
                restore = { entries ->
                    (entries as? List<*>)?.filterIsInstance<String>()
                        ?.mapNotNull { AppRoute.decode(it) }
                        ?.ifEmpty { listOf(AppRoute.ProfileList) }
                        ?.let { NavigationModel(it) }
                })
    }
}
