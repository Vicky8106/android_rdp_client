package com.rdp.client.ui.home

import androidx.recyclerview.widget.DiffUtil
import com.rdp.client.model.ServerProfile

/**
 * High-performance DiffUtil callback ensuring optimal RecyclerView animations.
 */
object ProfileDiffCallback : DiffUtil.ItemCallback<ServerProfile>() {

    override fun areItemsTheSame(oldItem: ServerProfile, newItem: ServerProfile): Boolean {
        return oldItem.id == newItem.id
    }

    override fun areContentsTheSame(oldItem: ServerProfile, newItem: ServerProfile): Boolean {
        return oldItem == newItem
    }
}
