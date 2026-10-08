package com.rdp.client.ui.home

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.rdp.client.R
import com.rdp.client.databinding.ActivityHomeBinding
import com.rdp.client.model.ProfileSortOrder
import com.rdp.client.model.ServerProfile
import com.rdp.client.ui.editor.ProfileEditorActivity
import com.rdp.client.ui.editor.QuickConnectDialogFragment
import com.rdp.client.ui.session.RdpSessionActivity
import com.rdp.client.ui.session.RdpSessionContract
import com.rdp.client.utils.WolHelper
import kotlinx.coroutines.launch

/**
 * Main Activity providing complete AVNC parity for RDP connection profile management.
 */
class HomeActivity : AppCompatActivity(), OnProfileActionListener {

    private lateinit var binding: ActivityHomeBinding
    private lateinit var profileAdapter: ProfileAdapter

    private val viewModel: ProfileViewModel by viewModels {
        ProfileViewModelFactory.create(applicationContext)
    }

    private var searchView: SearchView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupRecyclerView()
        setupFab()
        setupEmptyStateActions()
        observeUiState()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.topAppBar)
        supportActionBar?.setDisplayShowTitleEnabled(true)
    }

    private fun setupRecyclerView() {
        profileAdapter = ProfileAdapter(this)
        binding.recyclerViewProfiles.apply {
            layoutManager = LinearLayoutManager(this@HomeActivity)
            adapter = profileAdapter
            setHasFixedSize(false)

            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (dy > 0 && binding.fabAddProfile.isExtended) {
                        binding.fabAddProfile.shrink()
                    } else if (dy < 0 && !binding.fabAddProfile.isExtended) {
                        binding.fabAddProfile.extend()
                    }
                }
            })
        }
    }

    private fun setupFab() {
        binding.fabAddProfile.setOnClickListener {
            launchAddProfile()
        }
    }

    private fun setupEmptyStateActions() {
        binding.layoutEmptyState.btnEmptyAddProfile.setOnClickListener {
            launchAddProfile()
        }
        binding.layoutEmptyState.btnEmptyQuickConnect.setOnClickListener {
            launchQuickConnect()
        }
        binding.layoutEmptyState.btnEmptyClearSearch.setOnClickListener {
            searchView?.setQuery("", true)
            viewModel.setSearchQuery("")
        }
    }

    private fun observeUiState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    renderUiState(state)
                }
            }
        }
    }

    private fun renderUiState(state: ProfileUiState) {
        // 1. Loading indicator
        binding.progressBarLoading.isVisible = state.isLoading

        // 2. Submit list to adapter
        profileAdapter.submitList(state.profiles)

        // 3. Empty state handling
        val isEmpty = state.profiles.isEmpty() && !state.isLoading
        binding.layoutEmptyState.layoutEmptyRoot.isVisible = isEmpty
        binding.recyclerViewProfiles.isVisible = !isEmpty

        if (isEmpty) {
            if (state.searchQuery.isNotBlank()) {
                binding.layoutEmptyState.tvEmptyHeadline.text =
                    getString(R.string.empty_search_headline)
                binding.layoutEmptyState.tvEmptyDescription.text =
                    getString(R.string.empty_search_description, state.searchQuery)
                binding.layoutEmptyState.btnEmptyAddProfile.isVisible = false
                binding.layoutEmptyState.btnEmptyQuickConnect.isVisible = false
                binding.layoutEmptyState.btnEmptyClearSearch.isVisible = true
            } else {
                binding.layoutEmptyState.tvEmptyHeadline.text =
                    getString(R.string.empty_state_headline)
                binding.layoutEmptyState.tvEmptyDescription.text =
                    getString(R.string.empty_state_description)
                binding.layoutEmptyState.btnEmptyAddProfile.isVisible = true
                binding.layoutEmptyState.btnEmptyQuickConnect.isVisible = true
                binding.layoutEmptyState.btnEmptyClearSearch.isVisible = false
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_home, menu)

        val searchItem = menu.findItem(R.id.action_search)
        searchView = searchItem?.actionView as? SearchView
        searchView?.apply {
            queryHint = getString(R.string.action_search)
            setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?): Boolean {
                    viewModel.setSearchQuery(query.orEmpty())
                    return true
                }

                override fun onQueryTextChange(newText: String?): Boolean {
                    viewModel.setSearchQuery(newText.orEmpty())
                    return true
                }
            })
        }

        searchItem?.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem): Boolean = true
            override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                viewModel.setSearchQuery("")
                return true
            }
        })

        // Sync sort checkmark with ViewModel state
        val sortOrder = viewModel.sortOrder.value
        val sortItemId = when (sortOrder) {
            ProfileSortOrder.NAME_ASC, ProfileSortOrder.NAME_DESC -> R.id.sort_by_name
            ProfileSortOrder.DATE_ADDED -> R.id.sort_by_date_added
            ProfileSortOrder.LAST_CONNECTED -> R.id.sort_by_last_connected
            ProfileSortOrder.MOST_USED -> R.id.sort_by_most_used
        }
        menu.findItem(sortItemId)?.isChecked = true

        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.sort_by_name -> {
                item.isChecked = true
                viewModel.setSortOrder(ProfileSortOrder.NAME_ASC)
                true
            }
            R.id.sort_by_date_added -> {
                item.isChecked = true
                viewModel.setSortOrder(ProfileSortOrder.DATE_ADDED)
                true
            }
            R.id.sort_by_last_connected -> {
                item.isChecked = true
                viewModel.setSortOrder(ProfileSortOrder.LAST_CONNECTED)
                true
            }
            R.id.sort_by_most_used -> {
                item.isChecked = true
                viewModel.setSortOrder(ProfileSortOrder.MOST_USED)
                true
            }
            R.id.action_quick_connect -> {
                launchQuickConnect()
                true
            }
            R.id.action_settings -> {
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // -------------------------------------------------------------
    // OnProfileActionListener Implementation
    // -------------------------------------------------------------

    override fun onProfileClick(profile: ServerProfile) {
        viewModel.recordConnection(profile.id)
        val intent = RdpSessionContract.createSessionIntent(this, profile.id)
        startActivity(intent)
    }

    override fun onProfileLongClick(profile: ServerProfile): Boolean {
        showProfileOptionsDialog(profile)
        return true
    }

    override fun onProfileEdit(profile: ServerProfile) {
        val intent = ProfileEditorActivity.createIntent(this, profile.id)
        startActivity(intent)
    }

    override fun onProfileDuplicate(profile: ServerProfile) {
        viewModel.duplicateProfile(profile)
        Snackbar.make(
            binding.root,
            getString(R.string.msg_profile_duplicated, profile.getDisplayName()),
            Snackbar.LENGTH_SHORT
        ).show()
    }

    override fun onProfileWakeOnLan(profile: ServerProfile) {
        if (profile.enableWol && profile.wolMacAddress.isNotBlank()) {
            lifecycleScope.launch {
                val success = WolHelper.sendMagicPacket(
                    macAddress = profile.wolMacAddress,
                    broadcastIp = profile.wolBroadcastIp,
                    port = profile.wolPort
                )
                if (success) {
                    Snackbar.make(
                        binding.root,
                        getString(R.string.msg_wol_sent, profile.wolMacAddress),
                        Snackbar.LENGTH_SHORT
                    ).show()
                } else {
                    Snackbar.make(
                        binding.root,
                        "Failed to send Wake-on-LAN packet. Check MAC format.",
                        Snackbar.LENGTH_LONG
                    ).show()
                }
            }
        } else {
            Snackbar.make(
                binding.root,
                getString(R.string.msg_wol_not_configured),
                Snackbar.LENGTH_LONG
            ).setAction(R.string.action_edit) {
                onProfileEdit(profile)
            }.show()
        }
    }

    override fun onProfileDelete(profile: ServerProfile) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_delete_title)
            .setMessage(getString(R.string.dialog_delete_message, profile.getDisplayName()))
            .setPositiveButton(R.string.action_delete) { _, _ ->
                viewModel.deleteProfile(profile)
                Snackbar.make(
                    binding.root,
                    getString(R.string.msg_profile_deleted, profile.getDisplayName()),
                    Snackbar.LENGTH_LONG
                ).setAction(R.string.action_undo) {
                    viewModel.undoDelete()
                }.show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showProfileOptionsDialog(profile: ServerProfile) {
        val options = arrayOf(
            getString(R.string.action_connect),
            getString(R.string.action_edit),
            getString(R.string.action_duplicate),
            getString(R.string.action_wake_on_lan),
            getString(R.string.action_delete)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(profile.getDisplayName())
            .setItems(options) { _, which ->
                when (which) {
                    0 -> onProfileClick(profile)
                    1 -> onProfileEdit(profile)
                    2 -> onProfileDuplicate(profile)
                    3 -> onProfileWakeOnLan(profile)
                    4 -> onProfileDelete(profile)
                }
            }
            .show()
    }

    private fun launchAddProfile() {
        val intent = ProfileEditorActivity.createIntent(this, 0L)
        startActivity(intent)
    }

    private fun launchQuickConnect() {
        QuickConnectDialogFragment.newInstance().show(supportFragmentManager, QuickConnectDialogFragment.TAG)
    }
}
