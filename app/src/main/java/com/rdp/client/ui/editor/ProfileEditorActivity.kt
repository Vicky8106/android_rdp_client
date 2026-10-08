package com.rdp.client.ui.editor

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ArrayAdapter
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.rdp.client.R
import com.rdp.client.databinding.ActivityProfileEditorBinding
import com.rdp.client.model.AudioMode
import com.rdp.client.model.ColorDepth
import com.rdp.client.model.GestureStyle
import com.rdp.client.model.ResolutionMode
import com.rdp.client.model.ScreenOrientation
import com.rdp.client.model.SecurityType
import com.rdp.client.model.ServerProfile
import com.rdp.client.ui.home.ProfileViewModel
import com.rdp.client.ui.home.ProfileViewModelFactory
import com.rdp.client.ui.session.RdpSessionContract
import com.rdp.client.validator.FormField
import com.rdp.client.validator.ProfileValidator
import kotlinx.coroutines.launch

/**
 * Fullscreen Advanced Profile Editor Activity providing complete parity with AVNC's
 * editor system, adapted for FreeRDP settings.
 */
class ProfileEditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProfileEditorBinding
    private val profileViewModel: ProfileViewModel by viewModels {
        ProfileViewModelFactory.create(applicationContext)
    }

    private var profileId: Long = 0L
    private var loadedProfile: ServerProfile? = null

    companion object {
        const val EXTRA_PROFILE_ID = "com.rdp.client.EXTRA_PROFILE_ID"
        const val EXTRA_PREPOP_HOST = "com.rdp.client.EXTRA_PREPOP_HOST"
        const val EXTRA_PREPOP_PORT = "com.rdp.client.EXTRA_PREPOP_PORT"
        const val EXTRA_PREPOP_DOMAIN = "com.rdp.client.EXTRA_PREPOP_DOMAIN"
        const val EXTRA_PREPOP_USER = "com.rdp.client.EXTRA_PREPOP_USER"
        const val EXTRA_PREPOP_PASS = "com.rdp.client.EXTRA_PREPOP_PASS"

        fun createIntent(context: Context, profileId: Long = 0L): Intent {
            return Intent(context, ProfileEditorActivity::class.java).apply {
                putExtra(EXTRA_PROFILE_ID, profileId)
            }
        }

        fun createPrepopulatedIntent(
            context: Context,
            host: String,
            port: Int,
            domain: String,
            username: String,
            password: String
        ): Intent {
            return Intent(context, ProfileEditorActivity::class.java).apply {
                putExtra(EXTRA_PROFILE_ID, 0L)
                putExtra(EXTRA_PREPOP_HOST, host)
                putExtra(EXTRA_PREPOP_PORT, port)
                putExtra(EXTRA_PREPOP_DOMAIN, domain)
                putExtra(EXTRA_PREPOP_USER, username)
                putExtra(EXTRA_PREPOP_PASS, password)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProfileEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        profileId = intent.getLongExtra(EXTRA_PROFILE_ID, 0L)

        setupToolbar()
        setupDropdownAdapters()
        setupFieldListeners()
        setupSectionToggles()

        if (profileId > 0L) {
            loadExistingProfile(profileId)
        } else {
            populateFromPrepopulatedExtras()
        }
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = if (profileId > 0L) {
            getString(R.string.title_profile_editor_edit)
        } else {
            getString(R.string.title_profile_editor_new)
        }
        binding.toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_profile_editor, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_save -> {
                saveProfile(launchSessionAfterSave = false)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun setupDropdownAdapters() {
        // Security Modes
        val securityModes = arrayOf(
            getString(R.string.security_mode_auto),
            getString(R.string.security_mode_nla),
            getString(R.string.security_mode_tls),
            getString(R.string.security_mode_rdp)
        )
        binding.actvSecurityMode.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, securityModes))
        binding.actvSecurityMode.setText(securityModes[0], false)

        // Resolution Modes
        val resolutionModes = arrayOf(
            getString(R.string.resolution_fit_to_screen),
            getString(R.string.resolution_native),
            getString(R.string.resolution_custom)
        )
        binding.actvResolutionMode.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, resolutionModes))
        binding.actvResolutionMode.setText(resolutionModes[0], false)
        binding.actvResolutionMode.setOnItemClickListener { _, _, position, _ ->
            binding.layoutCustomResolution.isVisible = (position == 2)
        }

        // Color Depths
        val colorDepths = arrayOf(
            getString(R.string.color_depth_32bpp),
            getString(R.string.color_depth_24bpp),
            getString(R.string.color_depth_16bpp),
            getString(R.string.color_depth_8bpp)
        )
        binding.actvColorDepth.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, colorDepths))
        binding.actvColorDepth.setText(colorDepths[0], false)

        // Desktop Scale
        val scaleOptions = arrayOf("100%", "125%", "150%", "200%")
        binding.actvDesktopScale.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, scaleOptions))
        binding.actvDesktopScale.setText(scaleOptions[0], false)

        // Audio Redirection
        val audioModes = arrayOf(
            getString(R.string.audio_mode_local),
            getString(R.string.audio_mode_remote),
            getString(R.string.audio_mode_disabled)
        )
        binding.actvAudioMode.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, audioModes))
        binding.actvAudioMode.setText(audioModes[0], false)

        // Gesture Styles
        val gestureStyles = arrayOf(
            getString(R.string.gesture_auto),
            getString(R.string.gesture_touchscreen),
            getString(R.string.gesture_touchpad)
        )
        binding.actvGestureStyle.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, gestureStyles))
        binding.actvGestureStyle.setText(gestureStyles[0], false)

        // Orientations
        val orientations = arrayOf(
            getString(R.string.orientation_auto),
            getString(R.string.orientation_portrait),
            getString(R.string.orientation_landscape)
        )
        binding.actvOrientation.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, orientations))
        binding.actvOrientation.setText(orientations[0], false)
    }

    private fun setupFieldListeners() {
        binding.etHost.doAfterTextChanged { binding.tilHost.error = null }
        binding.etPort.doAfterTextChanged { binding.tilPort.error = null }
        binding.etCustomWidth.doAfterTextChanged { binding.tilCustomWidth.error = null }
        binding.etCustomHeight.doAfterTextChanged { binding.tilCustomHeight.error = null }
        binding.etGatewayHost.doAfterTextChanged { binding.tilGatewayHost.error = null }
        binding.etGatewayPort.doAfterTextChanged { binding.tilGatewayPort.error = null }
        binding.etWolMac.doAfterTextChanged { binding.tilWolMac.error = null }
        binding.etWolBroadcast.doAfterTextChanged { binding.tilWolBroadcast.error = null }
        binding.etWolPort.doAfterTextChanged { binding.tilWolPort.error = null }

        binding.btnSaveAndConnect.setOnClickListener {
            saveProfile(launchSessionAfterSave = true)
        }

        binding.btnDeleteProfile.setOnClickListener {
            confirmDeleteProfile()
        }
    }

    private fun setupSectionToggles() {
        binding.switchEnableGateway.setOnCheckedChangeListener { _, isChecked ->
            binding.layoutGatewayFields.isVisible = isChecked
        }
        binding.switchEnableWol.setOnCheckedChangeListener { _, isChecked ->
            binding.layoutWolFields.isVisible = isChecked
        }
    }

    private fun loadExistingProfile(id: Long) {
        lifecycleScope.launch {
            val profile = profileViewModel.getProfileById(id)
            if (profile != null) {
                loadedProfile = profile
                populateForm(profile)
                binding.btnDeleteProfile.isVisible = true
            }
        }
    }

    private fun populateFromPrepopulatedExtras() {
        intent.getStringExtra(EXTRA_PREPOP_HOST)?.let { binding.etHost.setText(it) }
        val port = intent.getIntExtra(EXTRA_PREPOP_PORT, 3389)
        binding.etPort.setText(port.toString())
        intent.getStringExtra(EXTRA_PREPOP_DOMAIN)?.let { binding.etDomain.setText(it) }
        intent.getStringExtra(EXTRA_PREPOP_USER)?.let { binding.etUsername.setText(it) }
        intent.getStringExtra(EXTRA_PREPOP_PASS)?.let { binding.etPassword.setText(it) }
    }

    private fun populateForm(p: ServerProfile) {
        binding.etProfileName.setText(p.name)
        binding.etHost.setText(p.host)
        binding.etPort.setText(p.port.toString())
        binding.etDomain.setText(p.domain)
        binding.etUsername.setText(p.username)
        binding.etPassword.setText(p.password)

        // Security
        val secIndex = when (p.securityType) {
            SecurityType.AUTO -> 0
            SecurityType.NLA -> 1
            SecurityType.TLS -> 2
            SecurityType.RDP -> 3
        }
        binding.actvSecurityMode.setText(binding.actvSecurityMode.adapter.getItem(secIndex).toString(), false)

        binding.switchAutoConnect.isChecked = p.connectOnAppStart

        // Resolution
        val resIndex = when (p.resolutionMode) {
            ResolutionMode.FIT_TO_SCREEN -> 0
            ResolutionMode.NATIVE -> 1
            ResolutionMode.CUSTOM -> 2
            ResolutionMode.DYNAMIC -> 0
        }
        binding.actvResolutionMode.setText(binding.actvResolutionMode.adapter.getItem(resIndex).toString(), false)
        binding.layoutCustomResolution.isVisible = (p.resolutionMode == ResolutionMode.CUSTOM)
        binding.etCustomWidth.setText(p.customWidth.toString())
        binding.etCustomHeight.setText(p.customHeight.toString())

        // Color Depth
        val colorIndex = when (p.colorDepth) {
            ColorDepth.DEPTH_32 -> 0
            ColorDepth.DEPTH_24 -> 1
            ColorDepth.DEPTH_16 -> 2
            ColorDepth.DEPTH_8 -> 3
        }
        binding.actvColorDepth.setText(binding.actvColorDepth.adapter.getItem(colorIndex).toString(), false)

        binding.switchDynamicResize.isChecked = (p.resolutionMode == ResolutionMode.DYNAMIC || p.resolutionMode == ResolutionMode.FIT_TO_SCREEN)
        binding.actvDesktopScale.setText("${p.desktopScale}%", false)

        // Audio & Input
        val audioIndex = when (p.audioMode) {
            AudioMode.LOCAL -> 0
            AudioMode.REMOTE -> 1
            AudioMode.MUTE -> 2
        }
        binding.actvAudioMode.setText(binding.actvAudioMode.adapter.getItem(audioIndex).toString(), false)
        binding.switchMicrophone.isChecked = p.microphoneEnabled

        val gestureIndex = when (p.gestureStyle) {
            GestureStyle.TOUCHSCREEN -> 1
            GestureStyle.TOUCHPAD -> 2
            else -> 0
        }
        binding.actvGestureStyle.setText(binding.actvGestureStyle.adapter.getItem(gestureIndex).toString(), false)

        val orientIndex = when (p.screenOrientation) {
            ScreenOrientation.PORTRAIT -> 1
            ScreenOrientation.LANDSCAPE -> 2
            else -> 0
        }
        binding.actvOrientation.setText(binding.actvOrientation.adapter.getItem(orientIndex).toString(), false)
        binding.switchButtonUpDelay.isChecked = p.buttonUpDelay

        // Gateway
        binding.switchEnableGateway.isChecked = p.enableGateway
        binding.layoutGatewayFields.isVisible = p.enableGateway
        binding.etGatewayHost.setText(p.gatewayHost)
        binding.etGatewayPort.setText(p.gatewayPort.toString())
        binding.etGatewayDomain.setText(p.gatewayDomain)
        binding.etGatewayUsername.setText(p.gatewayUsername)
        binding.etGatewayPassword.setText(p.gatewayPassword)
        binding.switchIgnoreCert.isChecked = p.ignoreCertificate

        // WoL
        binding.switchEnableWol.isChecked = p.enableWol
        binding.layoutWolFields.isVisible = p.enableWol
        binding.etWolMac.setText(p.wolMacAddress)
        binding.etWolBroadcast.setText(p.wolBroadcastIp)
        binding.etWolPort.setText(p.wolPort.toString())
    }

    private fun saveProfile(launchSessionAfterSave: Boolean) {
        val formState = extractFormState()
        val validationResult = ProfileValidator.validateProfile(formState)

        if (!validationResult.isValid) {
            applyValidationErrors(validationResult.errors)
            return
        }

        val profile = buildProfileFromForm(formState)

        lifecycleScope.launch {
            val targetId = if (profileId > 0L) {
                profileViewModel.updateProfile(profile)
                profileId
            } else {
                profileViewModel.insertProfile(profile)
            }

            if (launchSessionAfterSave) {
                val sessionIntent = RdpSessionContract.createSessionIntent(this@ProfileEditorActivity, targetId)
                startActivity(sessionIntent)
            }
            finish()
        }
    }

    private fun extractFormState(): ProfileValidator.ProfileFormState {
        val resMode = when (binding.actvResolutionMode.text.toString()) {
            getString(R.string.resolution_native) -> ResolutionMode.NATIVE
            getString(R.string.resolution_custom) -> ResolutionMode.CUSTOM
            else -> ResolutionMode.FIT_TO_SCREEN
        }

        return ProfileValidator.ProfileFormState(
            name = binding.etProfileName.text?.toString()?.trim().orEmpty(),
            host = binding.etHost.text?.toString()?.trim().orEmpty(),
            portStr = binding.etPort.text?.toString()?.trim().orEmpty(),
            domain = binding.etDomain.text?.toString()?.trim().orEmpty(),
            username = binding.etUsername.text?.toString()?.trim().orEmpty(),
            password = binding.etPassword.text?.toString().orEmpty(),
            resolutionMode = resMode,
            customWidthStr = binding.etCustomWidth.text?.toString()?.trim().orEmpty(),
            customHeightStr = binding.etCustomHeight.text?.toString()?.trim().orEmpty(),
            enableGateway = binding.switchEnableGateway.isChecked,
            gatewayHost = binding.etGatewayHost.text?.toString()?.trim().orEmpty(),
            gatewayPortStr = binding.etGatewayPort.text?.toString()?.trim().orEmpty(),
            enableWol = binding.switchEnableWol.isChecked,
            wolMac = binding.etWolMac.text?.toString()?.trim().orEmpty(),
            wolBroadcast = binding.etWolBroadcast.text?.toString()?.trim().orEmpty(),
            wolPortStr = binding.etWolPort.text?.toString()?.trim().orEmpty()
        )
    }

    private fun applyValidationErrors(errors: Map<FormField, String>) {
        var firstErrorView: View? = null

        errors.forEach { (field, message) ->
            when (field) {
                FormField.HOST -> {
                    binding.tilHost.error = message
                    if (firstErrorView == null) firstErrorView = binding.etHost
                }
                FormField.PORT -> {
                    binding.tilPort.error = message
                    if (firstErrorView == null) firstErrorView = binding.etPort
                }
                FormField.CUSTOM_WIDTH -> {
                    binding.tilCustomWidth.error = message
                    if (firstErrorView == null) firstErrorView = binding.etCustomWidth
                }
                FormField.CUSTOM_HEIGHT -> {
                    binding.tilCustomHeight.error = message
                    if (firstErrorView == null) firstErrorView = binding.etCustomHeight
                }
                FormField.GATEWAY_HOST -> {
                    binding.tilGatewayHost.error = message
                    if (firstErrorView == null) firstErrorView = binding.etGatewayHost
                }
                FormField.GATEWAY_PORT -> {
                    binding.tilGatewayPort.error = message
                    if (firstErrorView == null) firstErrorView = binding.etGatewayPort
                }
                FormField.WOL_MAC -> {
                    binding.tilWolMac.error = message
                    if (firstErrorView == null) firstErrorView = binding.etWolMac
                }
                FormField.WOL_BROADCAST -> {
                    binding.tilWolBroadcast.error = message
                    if (firstErrorView == null) firstErrorView = binding.etWolBroadcast
                }
                FormField.WOL_PORT -> {
                    binding.tilWolPort.error = message
                    if (firstErrorView == null) firstErrorView = binding.etWolPort
                }
            }
        }

        firstErrorView?.let { view ->
            view.requestFocus()
            binding.scrollView.smoothScrollTo(0, view.top)
        }
    }

    private fun buildProfileFromForm(state: ProfileValidator.ProfileFormState): ServerProfile {
        val fallbackName = when {
            state.name.isNotEmpty() -> state.name
            state.username.isNotEmpty() -> "${state.username}@${state.host}"
            state.portStr != "3389" && state.portStr.isNotEmpty() -> "${state.host}:${state.portStr}"
            else -> state.host
        }

        val secType = when (binding.actvSecurityMode.text.toString()) {
            getString(R.string.security_mode_nla) -> SecurityType.NLA
            getString(R.string.security_mode_tls) -> SecurityType.TLS
            getString(R.string.security_mode_rdp) -> SecurityType.RDP
            else -> SecurityType.AUTO
        }

        val colorDepth = when (binding.actvColorDepth.text.toString()) {
            getString(R.string.color_depth_24bpp) -> ColorDepth.DEPTH_24
            getString(R.string.color_depth_16bpp) -> ColorDepth.DEPTH_16
            getString(R.string.color_depth_8bpp) -> ColorDepth.DEPTH_8
            else -> ColorDepth.DEPTH_32
        }

        val desktopScale = binding.actvDesktopScale.text.toString().replace("%", "").toIntOrNull() ?: 100

        val audioMode = when (binding.actvAudioMode.text.toString()) {
            getString(R.string.audio_mode_remote) -> AudioMode.REMOTE
            getString(R.string.audio_mode_disabled) -> AudioMode.MUTE
            else -> AudioMode.LOCAL
        }

        val gestureStyle = when (binding.actvGestureStyle.text.toString()) {
            getString(R.string.gesture_touchscreen) -> GestureStyle.TOUCHSCREEN
            getString(R.string.gesture_touchpad) -> GestureStyle.TOUCHPAD
            else -> GestureStyle.AUTO
        }

        val orientation = when (binding.actvOrientation.text.toString()) {
            getString(R.string.orientation_portrait) -> ScreenOrientation.PORTRAIT
            getString(R.string.orientation_landscape) -> ScreenOrientation.LANDSCAPE
            else -> ScreenOrientation.AUTO
        }

        val resMode = if (binding.switchDynamicResize.isChecked && state.resolutionMode == ResolutionMode.FIT_TO_SCREEN) {
            ResolutionMode.DYNAMIC
        } else {
            state.resolutionMode
        }

        return ServerProfile(
            id = profileId,
            name = fallbackName,
            host = state.host,
            port = state.portStr.toIntOrNull() ?: 3389,
            domain = state.domain,
            username = state.username,
            password = state.password,
            securityType = secType,
            resolutionMode = resMode,
            customWidth = state.customWidthStr.toIntOrNull() ?: 1920,
            customHeight = state.customHeightStr.toIntOrNull() ?: 1080,
            colorDepth = colorDepth,
            desktopScale = desktopScale,
            audioMode = audioMode,
            microphoneEnabled = binding.switchMicrophone.isChecked,
            connectOnAppStart = binding.switchAutoConnect.isChecked,
            buttonUpDelay = binding.switchButtonUpDelay.isChecked,
            ignoreCertificate = binding.switchIgnoreCert.isChecked,
            gestureStyle = gestureStyle,
            screenOrientation = orientation,
            enableGateway = state.enableGateway,
            gatewayHost = state.gatewayHost,
            gatewayPort = state.gatewayPortStr.toIntOrNull() ?: 443,
            gatewayDomain = binding.etGatewayDomain.text?.toString()?.trim().orEmpty(),
            gatewayUsername = binding.etGatewayUsername.text?.toString()?.trim().orEmpty(),
            gatewayPassword = binding.etGatewayPassword.text?.toString().orEmpty(),
            enableWol = state.enableWol,
            wolMacAddress = state.wolMac,
            wolBroadcastIp = state.wolBroadcast.ifEmpty { "255.255.255.255" },
            wolPort = state.wolPortStr.toIntOrNull() ?: 9,
            lastConnectedTimestamp = loadedProfile?.lastConnectedTimestamp ?: 0L,
            connectionCount = loadedProfile?.connectionCount ?: 0
        )
    }

    private fun confirmDeleteProfile() {
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_delete_title)
            .setMessage(getString(R.string.dialog_delete_message, loadedProfile?.name ?: ""))
            .setPositiveButton(R.string.action_delete) { _, _ ->
                loadedProfile?.let {
                    profileViewModel.deleteProfile(it)
                    finish()
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }
}
