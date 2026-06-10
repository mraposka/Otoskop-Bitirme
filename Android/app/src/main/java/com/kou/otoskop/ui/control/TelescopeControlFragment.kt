package com.kou.otoskop.ui.control

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
import com.kou.otoskop.data.repository.MoveDirection
import com.kou.otoskop.data.repository.MoveStep
import com.kou.otoskop.databinding.FragmentTelescopeControlBinding
import com.kou.otoskop.ui.shared.SensorViewModel
import com.kou.otoskop.ui.shared.TelescopeViewModel
import kotlinx.coroutines.launch

class TelescopeControlFragment : Fragment(R.layout.fragment_telescope_control) {

    private var _binding: FragmentTelescopeControlBinding? = null
    private val binding get() = _binding!!

    private var step: MoveStep = MoveStep.MEDIUM

    private val telescope: TelescopeViewModel by activityViewModels {
        TelescopeViewModel.Factory(requireActivity().application)
    }
    private val sensor: SensorViewModel by activityViewModels {
        val app = requireActivity().application as OtoskopApp
        SensorViewModel.Factory(app.sensorRepo)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentTelescopeControlBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }

        binding.chipGps.setLabel(getString(R.string.chip_gps))
        binding.chipImu.setLabel(getString(R.string.chip_imu))
        binding.chipTracking.setLabel(getString(R.string.chip_tracking))
        binding.chipLocked.setLabel("Hedef")

        binding.stepGroup.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            step = when (id) {
                R.id.stepSmall -> MoveStep.SMALL
                R.id.stepLarge -> MoveStep.LARGE
                else -> MoveStep.MEDIUM
            }
        }
        binding.stepGroup.check(R.id.stepMedium)

        binding.btnUp.setOnClickListener { telescope.manualMove(MoveDirection.UP, step) }
        binding.btnDown.setOnClickListener { telescope.manualMove(MoveDirection.DOWN, step) }
        binding.btnLeft.setOnClickListener { telescope.manualMove(MoveDirection.LEFT, step) }
        binding.btnRight.setOnClickListener { telescope.manualMove(MoveDirection.RIGHT, step) }

        binding.calibrateBtn.setOnClickListener { telescope.calibrate() }
        binding.verifyBtn.setOnClickListener {
            telescope.verifyAndCorrect(sensor.state.value)
        }

        binding.trackingSwitch.setOnCheckedChangeListener { _, isChecked ->
            telescope.toggleTracking(isChecked)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    telescope.state.collect { s ->
                        binding.currentReadout.text =
                            "%.2f° / %.2f°".format(s.status.azimuth, s.status.altitude)
                        binding.targetReadout.text =
                            "%.2f° / %.2f°".format(
                                s.status.targetAzimuth, s.status.targetAltitude,
                            )
                        binding.servoReadout.text =
                            "%.1f° / %.1f°".format(s.status.servoAz, s.status.servoAlt)

                        binding.chipImu.setOk(s.status.imuOk)
                        binding.chipImu.setDetail(if (s.status.imuOk) null else "veri yok")
                        binding.chipTracking.setOk(s.status.tracking)
                        updateGpsChip(s.status.gpsFix, sensor.state.value.hasGps)
                        binding.chipLocked.setOk(s.status.targetLocked)
                        binding.chipLocked.setLabel(
                            if (s.status.targetLocked)
                                getString(R.string.status_target_locked)
                            else "Hedef",
                        )
                        binding.chipLocked.setDetail(s.selectedTarget?.name)

                        if (binding.trackingSwitch.isChecked != s.status.tracking) {
                            binding.trackingSwitch.setOnCheckedChangeListener(null)
                            binding.trackingSwitch.isChecked = s.status.tracking
                            binding.trackingSwitch.setOnCheckedChangeListener { _, c ->
                                telescope.toggleTracking(c)
                            }
                        }

                        if (s.error != null) binding.errorView.show(s.error)
                        else binding.errorView.hide()

                        binding.verifyBtn.isEnabled = !s.busy
                        binding.verifyBtn.text = if (s.busy)
                            getString(R.string.status_verifying)
                        else getString(R.string.action_verify)
                    }
                }
                launch {
                    sensor.state.collect {
                        updateGpsChip(telescope.state.value.status.gpsFix, it.hasGps)
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

    override fun onStart() {
        super.onStart()
        telescope.startPolling()
    }

    override fun onStop() {
        telescope.stopPolling()
        super.onStop()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
