package com.kou.otoskop.ui.livecamera

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.kou.otoskop.OtoskopApp
import com.kou.otoskop.R
import com.kou.otoskop.core.AppError
import com.kou.otoskop.core.AppErrorKind
import com.kou.otoskop.databinding.FragmentLiveCameraBinding
import com.kou.otoskop.ui.shared.CaptureContext
import com.kou.otoskop.ui.shared.CaptureViewModel
import com.kou.otoskop.ui.shared.SensorViewModel
import com.kou.otoskop.ui.shared.TelescopeViewModel
import kotlinx.coroutines.launch

class LiveCameraFragment : Fragment(R.layout.fragment_live_camera) {

    private var _binding: FragmentLiveCameraBinding? = null
    private val binding get() = _binding!!

    /** Aynı Toast'u tekrar tekrar göstermemek için son gösterilen mesaj. */
    private var lastCaptureMsg: String? = null

    private val telescope: TelescopeViewModel by activityViewModels {
        TelescopeViewModel.Factory(requireActivity().application)
    }
    private val sensor: SensorViewModel by activityViewModels {
        val app = requireActivity().application as OtoskopApp
        SensorViewModel.Factory(app.sensorRepo)
    }
    private val capture: CaptureViewModel by activityViewModels {
        CaptureViewModel.Factory(requireActivity().application)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentLiveCameraBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.chipLocked.setLabel("Hedef")
        binding.chipGps.setLabel(getString(R.string.chip_gps))
        binding.chipImu.setLabel(getString(R.string.chip_imu))
        binding.chipTracking.setLabel(getString(R.string.chip_tracking))

        binding.toolbar.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.action_tracking -> {
                    telescope.toggleTracking(!telescope.state.value.status.tracking)
                    true
                }
                R.id.action_control -> {
                    findNavController().navigate(R.id.action_live_to_control)
                    true
                }
                R.id.action_captures -> {
                    findNavController().navigate(R.id.action_live_to_captures)
                    true
                }
                R.id.action_console -> {
                    // Her aç/kapatta konsolu temizle: liste uzayınca oluşan
                    // takılmayı önler, temiz bir görünümle başlar.
                    telescope.clearDebug()
                    binding.consoleScroll.visibility =
                        if (binding.consoleScroll.visibility == View.VISIBLE) View.GONE
                        else View.VISIBLE
                    true
                }
                else -> false
            }
        }

        binding.defineAreaBtn.setOnClickListener {
            findNavController().navigate(R.id.action_live_to_skyArea)
        }
        binding.verifyBtn.setOnClickListener {
            telescope.verifyAndCorrect(sensor.state.value)
        }
        binding.photoBtn.setOnClickListener {
            capture.capturePhoto(buildCaptureContext())
        }
        binding.recordBtn.setOnClickListener {
            if (capture.state.value.recording) {
                capture.stopRecording()
            } else {
                capture.startRecording(buildCaptureContext())
            }
        }

        binding.stream.onError = { err ->
            binding.errorView.show(
                AppError(AppErrorKind.CAMERA_STREAM_FAILED, err.message ?: "stream error"),
            )
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    telescope.state.collect { s ->
                        binding.chipLocked.setLabel(
                            if (s.status.targetLocked)
                                getString(R.string.status_target_locked)
                            else "Hedef",
                        )
                        binding.chipLocked.setOk(s.status.targetLocked)
                        binding.chipLocked.setDetail(s.selectedTarget?.name)
                        // IMU yalnızca ESP telemetrisinden gelir. GPS çipi
                        // kaynağı (teleskop/telefon) gösterir: eğer "telefon"
                        // yazıyorsa ESP telemetrisi gelmiyordur (Mega↔ESP hattı)
                        // -> IMU de bu yüzden kırmızı kalır.
                        binding.chipImu.setOk(s.status.imuOk)
                        binding.chipImu.setDetail(if (s.status.imuOk) null else "veri yok")
                        binding.chipTracking.setOk(s.status.tracking)
                        updateGpsChip(s.status.gpsFix, sensor.state.value.hasGps)

                        if (s.error != null) binding.errorView.show(s.error)
                        else binding.errorView.hide()

                        binding.verifyBtn.isEnabled = !s.busy
                        binding.verifyBtn.text =
                            if (s.busy) getString(R.string.status_verifying)
                            else getString(R.string.action_verify)
                    }
                }
                launch {
                    sensor.state.collect {
                        updateGpsChip(telescope.state.value.status.gpsFix, it.hasGps)
                    }
                }
                launch {
                    capture.state.collect { cs ->
                        binding.recordBtn.text = getString(
                            if (cs.recording) R.string.action_record_stop
                            else R.string.action_record_start,
                        )
                        binding.recordBtn.isEnabled = !cs.busy
                        binding.photoBtn.isEnabled = !cs.busy && !cs.recording
                        if (cs.message != null && cs.message != lastCaptureMsg) {
                            lastCaptureMsg = cs.message
                            Toast.makeText(requireContext(), cs.message, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                launch {
                    telescope.debugLog.collect { lines ->
                        if (lines.isEmpty()) {
                            binding.consoleText.setText(R.string.console_empty)
                        } else {
                            binding.consoleText.text = lines.joinToString("\n")
                            // En alta kaydır (yeni satırlar görünsün).
                            binding.consoleScroll.post {
                                binding.consoleScroll.fullScroll(View.FOCUS_DOWN)
                            }
                        }
                    }
                }
            }
        }
    }

    /** GPS çipini kaynağıyla birlikte günceller (teleskop > telefon). */
    private fun updateGpsChip(espFix: Boolean, phoneGps: Boolean) {
        binding.chipGps.setOk(espFix || phoneGps)
        binding.chipGps.setDetail(
            when {
                espFix -> "teleskop"
                phoneGps -> "telefon"
                else -> null
            },
        )
    }

    /** O anki telemetri + konumdan çekim bağlamı üret (foto/video meta verisi). */
    private fun buildCaptureContext(): CaptureContext {
        val s = telescope.state.value
        val sd = sensor.state.value
        return CaptureContext(
            targetName = s.selectedTarget?.name,
            objectType = s.selectedTarget?.type,
            azimuth = s.status.azimuth,
            altitude = s.status.altitude,
            gpsLat = sd.latitude,
            gpsLon = sd.longitude,
            magnitude = s.selectedTarget?.magnitude,
        )
    }

    override fun onStart() {
        super.onStart()
        val app = requireActivity().application as OtoskopApp
        if (app.esp32Repo.supportsLiveStream) {
            binding.demoPlaceholder.visibility = View.GONE
            binding.stream.visibility = View.VISIBLE
            // Akış karelerini video kaydedicisine ilet (kayıt aktifse kodlanır).
            binding.stream.onFrame = { bmp -> capture.onStreamFrame(bmp) }
            binding.stream.start(app.esp32Repo.streamUrl)
        } else {
            binding.stream.stop()
            binding.stream.visibility = View.GONE
            binding.demoPlaceholder.visibility = View.VISIBLE
        }
        telescope.startPolling()
    }

    override fun onStop() {
        // Kayıt sürüyorsa kapat (uygulama arka plana giderken kaydı sonlandır).
        if (capture.state.value.recording) capture.stopRecording()
        _binding?.stream?.onFrame = null
        _binding?.stream?.stop()
        telescope.stopPolling()
        super.onStop()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
