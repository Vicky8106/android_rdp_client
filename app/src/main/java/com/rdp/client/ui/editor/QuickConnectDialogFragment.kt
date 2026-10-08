package com.rdp.client.ui.editor

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.rdp.client.databinding.DialogQuickConnectBinding
import com.rdp.client.model.AudioMode
import com.rdp.client.model.ColorDepth
import com.rdp.client.model.ResolutionMode
import com.rdp.client.model.SecurityType
import com.rdp.client.model.ServerProfile
import com.rdp.client.ui.home.ProfileViewModel
import com.rdp.client.ui.home.ProfileViewModelFactory
import com.rdp.client.ui.session.RdpSessionContract
import com.rdp.client.validator.ProfileValidator
import kotlinx.coroutines.launch

/**
 * Modal Quick Connect dialog replicating AVNC's rapid server connection UX.
 * Provides two primary execution paths:
 * 1. Immediate transient connection (Connect button - no Room DB persistence)
 * 2. Persisted connection (Save & Connect button - writes to Room DB then launches)
 */
class QuickConnectDialogFragment : DialogFragment() {

    private var _binding: DialogQuickConnectBinding? = null
    private val binding get() = _binding!!

    // Shared ViewModel for inserting profiles into Room DB
    private val profileViewModel: ProfileViewModel by activityViewModels {
        ProfileViewModelFactory.create(requireContext().applicationContext)
    }

    companion object {
        const val TAG = "QuickConnectDialogFragment"

        fun newInstance(): QuickConnectDialogFragment {
            return QuickConnectDialogFragment()
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.window?.apply {
            requestFeature(Window.FEATURE_NO_TITLE)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogQuickConnectBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupInputWatchers()
        setupActionListeners()
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.92).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun setupInputWatchers() {
        binding.etQuickHost.doAfterTextChanged {
            binding.tilQuickHost.error = null
        }
        binding.etQuickPort.doAfterTextChanged {
            binding.tilQuickPort.error = null
        }
    }

    private fun setupActionListeners() {
        binding.btnQuickCancel.setOnClickListener {
            dismiss()
        }

        binding.btnQuickConnect.setOnClickListener {
            handleConnect(saveToDatabase = false)
        }

        binding.btnQuickSaveAndConnect.setOnClickListener {
            handleConnect(saveToDatabase = true)
        }

        binding.btnQuickAdvanced.setOnClickListener {
            escalateToAdvancedEditor()
        }
    }

    private fun handleConnect(saveToDatabase: Boolean) {
        val host = binding.etQuickHost.text?.toString()?.trim().orEmpty()
        val portStr = binding.etQuickPort.text?.toString()?.trim().orEmpty()
        val domain = binding.etQuickDomain.text?.toString()?.trim().orEmpty()
        val username = binding.etQuickUsername.text?.toString()?.trim().orEmpty()
        val password = binding.etQuickPassword.text?.toString().orEmpty()

        // Validate Host
        val hostValidation = ProfileValidator.validateHost(host)
        if (!hostValidation.isValid) {
            binding.tilQuickHost.error = hostValidation.errorMessage
            binding.etQuickHost.requestFocus()
            return
        }

        // Validate Port (defaults to 3389 if empty)
        val port = if (portStr.isEmpty()) 3389 else portStr.toIntOrNull() ?: -1
        val portValidation = ProfileValidator.validatePort(port)
        if (!portValidation.isValid) {
            binding.tilQuickPort.error = portValidation.errorMessage
            binding.etQuickPort.requestFocus()
            return
        }

        // Synthesize profile title
        val profileName = when {
            username.isNotEmpty() -> "$username@$host"
            port != 3389 -> "$host:$port"
            else -> host
        }

        val profile = ServerProfile(
            id = 0L,
            name = profileName,
            host = host,
            port = port,
            domain = domain,
            username = username,
            password = password,
            securityType = SecurityType.AUTO,
            resolutionMode = ResolutionMode.FIT_TO_SCREEN,
            colorDepth = ColorDepth.DEPTH_32,
            audioMode = AudioMode.LOCAL,
            microphoneEnabled = false,
            isQuickConnect = !saveToDatabase,
            lastConnectedTimestamp = System.currentTimeMillis()
        )

        if (saveToDatabase) {
            viewLifecycleOwner.lifecycleScope.launch {
                val newProfileId = profileViewModel.insertProfile(profile)
                dismiss()
                val intent = RdpSessionContract.createSessionIntent(requireContext(), newProfileId)
                startActivity(intent)
            }
        } else {
            dismiss()
            val intent = RdpSessionContract.createTransientSessionIntent(requireContext(), profile)
            startActivity(intent)
        }
    }

    private fun escalateToAdvancedEditor() {
        val host = binding.etQuickHost.text?.toString()?.trim().orEmpty()
        val portStr = binding.etQuickPort.text?.toString()?.trim().orEmpty()
        val port = portStr.toIntOrNull() ?: 3389
        val domain = binding.etQuickDomain.text?.toString()?.trim().orEmpty()
        val username = binding.etQuickUsername.text?.toString()?.trim().orEmpty()
        val password = binding.etQuickPassword.text?.toString().orEmpty()

        val intent = ProfileEditorActivity.createPrepopulatedIntent(
            context = requireContext(),
            host = host,
            port = port,
            domain = domain,
            username = username,
            password = password
        )
        dismiss()
        startActivity(intent)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
