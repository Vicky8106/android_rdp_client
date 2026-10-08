package com.rdp.client.ui.home

import com.rdp.client.model.ServerProfile

/**
 * User interaction callback interface for profile items in the home list.
 */
interface OnProfileActionListener {
    /** Called when a bookmark card is tapped to initiate connection. */
    fun onProfileClick(profile: ServerProfile)

    /** Called when a bookmark card is long-pressed for context options. */
    fun onProfileLongClick(profile: ServerProfile): Boolean

    /** Called when Edit is selected in the overflow menu. */
    fun onProfileEdit(profile: ServerProfile)

    /** Called when Duplicate is selected in the overflow menu. */
    fun onProfileDuplicate(profile: ServerProfile)

    /** Called when Wake-on-LAN is selected in the overflow menu. */
    fun onProfileWakeOnLan(profile: ServerProfile)

    /** Called when Delete is selected in the overflow menu. */
    fun onProfileDelete(profile: ServerProfile)
}
