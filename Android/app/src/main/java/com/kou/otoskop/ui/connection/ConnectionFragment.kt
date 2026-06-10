package com.kou.otoskop.ui.connection

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.kou.otoskop.OtoskopApp
import com.kou.otoskop.R
import com.kou.otoskop.databinding.FragmentConnectionBinding
import com.kou.otoskop.ui.shared.ConnectionStatus
import com.kou.otoskop.ui.shared.ConnectionViewModel
import kotlinx.coroutines.launch

class ConnectionFragment : Fragment(R.layout.fragment_connection) {

    private var _binding: FragmentConnectionBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ConnectionViewModel by activityViewModels {
        ConnectionViewModel.Factory(requireActivity().application)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentConnectionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val app = requireActivity().application as OtoskopApp

        binding.demoSwitch.setOnCheckedChangeListener(null)
        binding.demoSwitch.isChecked = app.isDemoMode
        binding.demoSwitch.setOnCheckedChangeListener { _, checked ->
            app.setDemoMode(checked)
            viewModel.refreshAfterDemoToggle()
        }

        binding.ipInput.setText(viewModel.state.value.ip)
        binding.keyInput.setText(app.geminiApiKey)

        binding.saveKeyBtn.setOnClickListener {
            app.setGeminiApiKey(binding.keyInput.text?.toString().orEmpty())
            android.widget.Toast.makeText(
                requireContext(), R.string.key_saved, android.widget.Toast.LENGTH_SHORT,
            ).show()
        }

        binding.testBtn.setOnClickListener {
            val text = binding.ipInput.text?.toString().orEmpty()
            viewModel.updateIp(text)
            viewModel.testConnection()
        }

        binding.discoverBtn.setOnClickListener {
            viewModel.discover()
        }

        binding.streamBtn.setOnClickListener {
            if (binding.preview.visibility == View.VISIBLE) {
                binding.preview.stop()
                binding.preview.visibility = View.GONE
                binding.streamBtn.setText(R.string.action_test_stream)
            } else {
                binding.preview.visibility = View.VISIBLE
                binding.preview.onError = {
                    binding.errorView.show(
                        com.kou.otoskop.core.AppError(
                            com.kou.otoskop.core.AppErrorKind.CAMERA_STREAM_FAILED,
                            it.message ?: "stream error",
                        ),
                    )
                }
                binding.preview.start(app.esp32Repo.streamUrl)
                binding.streamBtn.setText(R.string.action_hide_stream)
            }
        }

        binding.continueBtn.setOnClickListener {
            findNavController().navigate(R.id.action_connection_to_live)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    val appNow = requireActivity().application as OtoskopApp
                    val testing = state.status == ConnectionStatus.TESTING
                    binding.testBtn.isEnabled = !testing
                    binding.discoverBtn.isEnabled = !testing
                    binding.testBtn.text = if (testing) getString(R.string.status_testing)
                    else getString(R.string.action_test_connection)

                    // Otomatik bulma IP'yi değiştirdiyse kutuyu güncelle (kullanıcı
                    // yazarken bozmayalım diye sadece odak yokken)
                    if (!binding.ipInput.hasFocus() &&
                        binding.ipInput.text?.toString() != state.ip
                    ) {
                        binding.ipInput.setText(state.ip)
                    }

                    val connected = state.status == ConnectionStatus.CONNECTED
                    binding.streamBtn.isEnabled =
                        connected && appNow.esp32Repo.supportsLiveStream
                    binding.continueBtn.isEnabled = connected

                    if (state.error != null) {
                        binding.errorView.show(state.error) {
                            viewModel.testConnection()
                        }
                    } else {
                        binding.errorView.hide()
                    }
                }
            }
        }
    }

    override fun onStop() {
        _binding?.preview?.stop()
        super.onStop()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
