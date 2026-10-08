package com.rdp.client.ui.home

import android.view.View
import androidx.test.core.app.ActivityScenario
import com.google.common.truth.Truth.assertThat
import com.rdp.client.R
import com.rdp.client.repository.IProfileRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HomeActivityTest {

    @Before
    fun setUp() {
        val repo = mockk<IProfileRepository>(relaxed = true)
        every { repo.allProfiles } returns MutableStateFlow(emptyList())
        ProfileViewModelFactory.testRepository = repo
    }

    @After
    fun tearDown() {
        ProfileViewModelFactory.testRepository = null
    }

    @Test
    fun testHomeActivityLaunches_toolbarInitialized() {
        ActivityScenario.launch(HomeActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val toolbar = activity.findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.topAppBar)
                assertThat(toolbar).isNotNull()
                assertThat(toolbar.title.toString()).isEqualTo("Remote Desktops")
            }
        }
    }

    @Test
    fun testFabExistsAndIsClickable() {
        ActivityScenario.launch(HomeActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val fab = activity.findViewById<View>(R.id.fabAddProfile)
                assertThat(fab).isNotNull()
                assertThat(fab.isClickable).isTrue()
            }
        }
    }

    @Test
    fun testRecyclerViewAndEmptyStateInitialized() {
        ActivityScenario.launch(HomeActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val recyclerView = activity.findViewById<View>(R.id.recyclerViewProfiles)
                assertThat(recyclerView).isNotNull()
                val emptyState = activity.findViewById<View>(R.id.layoutEmptyState)
                assertThat(emptyState).isNotNull()
            }
        }
    }
}
