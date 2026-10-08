package com.rdp.client.ui.home

import android.content.Context
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.isVisible
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.rdp.client.R
import com.rdp.client.databinding.ItemServerCardBinding
import com.rdp.client.model.ResolutionMode
import com.rdp.client.model.ServerProfile

class ProfileAdapter(
    private val actionListener: OnProfileActionListener
) : ListAdapter<ServerProfile, ProfileAdapter.ProfileViewHolder>(ProfileDiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProfileViewHolder {
        val binding = ItemServerCardBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ProfileViewHolder(binding, actionListener)
    }

    override fun onBindViewHolder(holder: ProfileViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ProfileViewHolder(
        private val binding: ItemServerCardBinding,
        private val listener: OnProfileActionListener
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(profile: ServerProfile) {
            val context = itemView.context

            // 1. Server Title
            binding.tvServerName.text = if (profile.name.isNotBlank()) {
                profile.name
            } else {
                "${profile.host}:${profile.port}"
            }

            // 2. Subtitle: host:port and username
            binding.tvServerHost.text = buildSubtitle(profile)

            // 3. Security Badge
            binding.badgeSecurity.text = profile.securityType.displayName
            binding.badgeSecurity.isVisible = true

            // 4. Resolution Badge
            binding.badgeResolution.text = formatResolution(profile)
            binding.badgeResolution.isVisible = true

            // 5. Gateway Badge
            binding.badgeGateway.isVisible = profile.enableGateway && profile.gatewayHost.isNotBlank()

            // 6. Wake-on-LAN Badge
            binding.badgeWol.isVisible = profile.enableWol && profile.wolMacAddress.isNotBlank()

            // 7. Password Saved Indicator
            binding.badgeCredential.isVisible = profile.password.isNotBlank()

            // 8. Last Connected Relative Timestamp
            binding.tvLastConnected.text = formatLastConnected(context, profile.lastConnectedTimestamp)

            // 9. Root Card Click & Long Click
            binding.cardServerItem.setOnClickListener {
                listener.onProfileClick(profile)
            }
            binding.cardServerItem.setOnLongClickListener {
                listener.onProfileLongClick(profile)
            }

            // 10. Overflow Context Menu
            binding.btnOverflowMenu.setOnClickListener { view ->
                showOverflowMenu(view.context, view, profile)
            }
        }

        private fun buildSubtitle(profile: ServerProfile): String {
            val userPrefix = when {
                profile.domain.isNotBlank() && profile.username.isNotBlank() ->
                    "${profile.domain}\\${profile.username}@"
                profile.username.isNotBlank() ->
                    "${profile.username}@"
                else -> ""
            }
            return "$userPrefix${profile.host}:${profile.port}"
        }

        private fun formatResolution(profile: ServerProfile): String {
            return when (profile.resolutionMode) {
                ResolutionMode.FIT_TO_SCREEN -> "Fit"
                ResolutionMode.NATIVE -> "Native"
                ResolutionMode.DYNAMIC -> "Dynamic"
                ResolutionMode.CUSTOM -> "${profile.customWidth}x${profile.customHeight}"
            }
        }

        private fun formatLastConnected(context: Context, timestamp: Long): String {
            return if (timestamp > 0L) {
                val relativeTime = DateUtils.getRelativeTimeSpanString(
                    timestamp,
                    System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS,
                    DateUtils.FORMAT_ABBREV_RELATIVE
                )
                context.getString(R.string.time_last_connected, relativeTime)
            } else {
                context.getString(R.string.time_never_connected)
            }
        }

        private fun showOverflowMenu(
            context: Context,
            anchor: android.view.View,
            profile: ServerProfile
        ) {
            val popup = PopupMenu(context, anchor)
            popup.menuInflater.inflate(R.menu.menu_profile_item, popup.menu)
            popup.setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    R.id.action_connect -> {
                        listener.onProfileClick(profile)
                        true
                    }
                    R.id.action_edit -> {
                        listener.onProfileEdit(profile)
                        true
                    }
                    R.id.action_duplicate -> {
                        listener.onProfileDuplicate(profile)
                        true
                    }
                    R.id.action_wol -> {
                        listener.onProfileWakeOnLan(profile)
                        true
                    }
                    R.id.action_delete -> {
                        listener.onProfileDelete(profile)
                        true
                    }
                    else -> false
                }
            }
            popup.show()
        }
    }
}
