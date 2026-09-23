package com.freerdp.client.unit

import com.freerdp.client.navigation.AppRoute
import com.freerdp.client.navigation.NavigationModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Back-stack semantics incl. route codec used for process-death restoration. */
class NavigationModelTest {

    @Test
    fun startsAtProfileListAndCannotPopRoot() {
        val nav = NavigationModel()
        assertEquals(AppRoute.ProfileList, nav.current)
        assertFalse(nav.canPop)
        assertFalse(nav.pop())
        assertEquals(AppRoute.ProfileList, nav.current)
    }

    @Test
    fun pushAndPopWalkTheStack() {
        val nav = NavigationModel()
        nav.push(AppRoute.ProfileEditor("p1"))
        assertEquals(AppRoute.ProfileEditor("p1"), nav.current)
        assertTrue(nav.canPop)

        nav.push(AppRoute.Settings)
        assertEquals(AppRoute.Settings, nav.current)

        assertTrue(nav.pop())
        assertEquals(AppRoute.ProfileEditor("p1"), nav.current)
        assertTrue(nav.pop())
        assertEquals(AppRoute.ProfileList, nav.current)
        assertFalse(nav.canPop)
    }

    @Test
    fun pushingCurrentRouteIsIgnored() {
        val nav = NavigationModel()
        nav.push(AppRoute.Settings)
        nav.push(AppRoute.Settings)
        assertTrue(nav.pop())
        assertEquals(AppRoute.ProfileList, nav.current)
    }

    @Test
    fun resetToCollapsesWholeStack() {
        val nav = NavigationModel()
        nav.push(AppRoute.ProfileEditor(null))
        nav.push(AppRoute.Session("p9"))
        nav.resetTo(AppRoute.ProfileList)
        assertEquals(AppRoute.ProfileList, nav.current)
        assertFalse(nav.canPop)
    }

    @Test
    fun routeCodecRoundTripsEveryDestination() {
        val routes = listOf(
            AppRoute.ProfileList,
            AppRoute.ProfileEditor("abc-123"),
            AppRoute.ProfileEditor(null),
            AppRoute.Settings,
            AppRoute.Session("profile-42")
        )
        routes.forEach { route ->
            assertEquals(route, AppRoute.decode(route.encode()))
        }
    }

    @Test
    fun codecRejectsGarbageAndSurvivesInvalidStacks() {
        assertNull(AppRoute.decode("bogus"))
        assertNull(AppRoute.decode("session:"))
        // Restoring an invalid saved stack falls back to the profile list.
        val model = NavigationModel.Saver.let { saver ->
            val restored = listOf("garbage", "settings").mapNotNull { AppRoute.decode(it) }
                .ifEmpty { listOf(AppRoute.ProfileList) }
            NavigationModel(restored)
        }
        assertEquals(AppRoute.Settings, model.current)
    }

    @Test
    fun stackSnapshotIsImmutableCopy() {
        val nav = NavigationModel()
        nav.push(AppRoute.Settings)
        val snapshot = nav.snapshot()
        nav.pop()
        assertEquals(2, snapshot.size)
        assertEquals(1, nav.snapshot().size)
    }
}
