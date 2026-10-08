package com.rdp.client.ui.home

import com.google.common.truth.Truth.assertThat
import com.rdp.client.model.ServerProfile
import org.junit.Test

class ProfileDiffCallbackTest {

    @Test
    fun testAreItemsTheSame_sameIdReturnsTrue() {
        val p1 = ServerProfile(id = 1L, name = "Desktop A", host = "10.0.0.1")
        val p2 = ServerProfile(id = 1L, name = "Updated Name", host = "10.0.0.1")
        assertThat(ProfileDiffCallback.areItemsTheSame(p1, p2)).isTrue()
    }

    @Test
    fun testAreItemsTheSame_differentIdReturnsFalse() {
        val p1 = ServerProfile(id = 1L, name = "Desktop A", host = "10.0.0.1")
        val p2 = ServerProfile(id = 2L, name = "Desktop A", host = "10.0.0.1")
        assertThat(ProfileDiffCallback.areItemsTheSame(p1, p2)).isFalse()
    }

    @Test
    fun testAreContentsTheSame_identicalObjects() {
        val p1 = ServerProfile(id = 1L, name = "Server", host = "192.168.1.10", createdTimestamp = 1000L)
        val p2 = ServerProfile(id = 1L, name = "Server", host = "192.168.1.10", createdTimestamp = 1000L)
        assertThat(ProfileDiffCallback.areContentsTheSame(p1, p2)).isTrue()
    }

    @Test
    fun testAreContentsTheSame_modifiedPropertyReturnsFalse() {
        val p1 = ServerProfile(id = 1L, name = "Server", host = "192.168.1.10", port = 3389, createdTimestamp = 1000L)
        val p2 = ServerProfile(id = 1L, name = "Server", host = "192.168.1.10", port = 3390, createdTimestamp = 1000L)
        assertThat(ProfileDiffCallback.areContentsTheSame(p1, p2)).isFalse()
    }
}
