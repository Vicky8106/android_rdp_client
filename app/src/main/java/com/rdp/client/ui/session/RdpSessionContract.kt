package com.rdp.client.ui.session

import android.content.Context
import android.content.Intent
import com.rdp.client.model.ServerProfile

/**
 * Interface contract governing navigation between the Profile Editors and RdpSessionActivity.
 * Guarantees zero coupling between UI activities while ensuring strict type safety.
 */
object RdpSessionContract {

    /** Extra holding the primary key ID of a persisted ServerProfile in Room DB */
    const val EXTRA_PROFILE_ID = "com.rdp.client.EXTRA_PROFILE_ID"

    /** Extra boolean flag indicating an ad-hoc session launched via Quick Connect */
    const val EXTRA_QUICK_CONNECT = "com.rdp.client.EXTRA_QUICK_CONNECT"

    /** Extra holding transient ServerProfile data for ad-hoc sessions not stored in DB */
    const val EXTRA_TRANSIENT_PROFILE = "com.rdp.client.EXTRA_TRANSIENT_PROFILE"

    /**
     * Creates an Intent to launch a session backed by a persisted Room DB profile.
     */
    fun createSessionIntent(context: Context, profileId: Long): Intent {
        return Intent(context, RdpSessionActivity::class.java).apply {
            putExtra(EXTRA_PROFILE_ID, profileId)
            putExtra(EXTRA_QUICK_CONNECT, false)
        }
    }

    /**
     * Creates an Intent to launch an ad-hoc session without database persistence.
     */
    fun createTransientSessionIntent(context: Context, transientProfile: ServerProfile): Intent {
        return Intent(context, RdpSessionActivity::class.java).apply {
            putExtra(EXTRA_PROFILE_ID, 0L)
            putExtra(EXTRA_QUICK_CONNECT, true)
            putExtra(EXTRA_TRANSIENT_PROFILE, transientProfile)
        }
    }
}
