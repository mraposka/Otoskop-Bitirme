package com.kou.otoskop.ui.control

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.kou.otoskop.OtoskopApp
import com.kou.otoskop.R
import com.kou.otoskop.core.AstroMath
import com.kou.otoskop.data.repository.MoveDirection
import com.kou.otoskop.data.repository.MoveStep
import com.kou.otoskop.databinding.FragmentTelescopeControlBinding
import com.kou.otoskop.ui.shared.SensorViewModel
import com.kou.otoskop.ui.shared.TelescopeViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
        binding.dirCalibrateBtn.setOnClickListener { showDirectionCalibration() }
        binding.settingsBtn.setOnClickListener { showSettingsDialog() }

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

    // ----------------------- Yön kalibrasyonu -------------------------------
    /**
     * Telefonla 2 adımlı yön hizalama: (1) azimut, (2) altitude. Her adımda
     * telefon (gerçek referans) ile teleskobun MPU okuması arasındaki farkı
     * yakalar ve firmware'e artımsal offset olarak gönderir.
     */
    private fun showDirectionCalibration() {
        val st = telescope.state.value.status
        val app = requireActivity().application as OtoskopApp
        if (!app.isDemoMode && st.megaAgeMs < 0) {
            Toast.makeText(requireContext(), R.string.dir_calib_no_status, Toast.LENGTH_LONG).show()
            return
        }
        // Adım 1: azimut
        runCalibStep(
            messageRes = R.string.dir_calib_step_az,
            phoneValue = { sensor.state.value.derivedAzimuth },
            telescopeValue = { telescope.state.value.status.azimuth },
        ) { phoneAz, telAz ->
            val daz = AstroMath.azimuthDelta(telAz, phoneAz)
            telescope.sendDirectionOffset(daz, 0.0)
            // Adım 2: altitude
            runCalibStep(
                messageRes = R.string.dir_calib_step_alt,
                phoneValue = { sensor.state.value.derivedAltitude },
                telescopeValue = { telescope.state.value.status.altitude },
            ) { phoneAlt, telAlt ->
                val dalt = phoneAlt - telAlt
                telescope.sendDirectionOffset(0.0, dalt)
                Toast.makeText(
                    requireContext(),
                    getString(R.string.dir_calib_done, daz, dalt),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    /** Canlı güncellenen tek adımlık yakalama diyaloğu. */
    private fun runCalibStep(
        messageRes: Int,
        phoneValue: () -> Double,
        telescopeValue: () -> Double,
        onCapture: (phone: Double, telescope: Double) -> Unit,
    ) {
        val text = TextView(requireContext()).apply {
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            textSize = 15f
        }
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.dir_calib_title)
            .setView(text)
            .setPositiveButton(R.string.dir_calib_capture) { _, _ ->
                onCapture(phoneValue(), telescopeValue())
            }
            .setNegativeButton(R.string.action_cancel, null)
            .create()

        var job: Job? = null
        dialog.setOnShowListener {
            job = viewLifecycleOwner.lifecycleScope.launch {
                while (isActive) {
                    text.text = getString(messageRes, phoneValue(), telescopeValue())
                    delay(150)
                }
            }
        }
        dialog.setOnDismissListener { job?.cancel() }
        dialog.show()
    }

    // ----------------------------- Ayarlar ----------------------------------
    /** Altitude yukarı limiti ayar diyaloğu (kullanıcı belirler). */
    private fun showSettingsDialog() {
        val current = telescope.state.value.status.altMax.takeIf { it > 0 } ?: 90.0
        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        val label = TextView(requireContext()).apply {
            text = getString(R.string.settings_alt_limit_label, current)
            textSize = 16f
        }
        val hint = TextView(requireContext()).apply {
            setText(R.string.settings_alt_limit_hint)
            textSize = 12f
            alpha = 0.7f
        }
        val seek = SeekBar(requireContext()).apply {
            max = 179
            progress = (current.toInt() - 1).coerceIn(0, 179)
        }
        var value = current
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                value = (progress + 1).toDouble()
                label.text = getString(R.string.settings_alt_limit_label, value)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        container.addView(label)
        container.addView(hint)
        container.addView(seek)

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.settings_title)
            .setView(container)
            .setPositiveButton(R.string.settings_save) { _, _ ->
                telescope.setAltLimit(value)
                Toast.makeText(requireContext(), R.string.settings_saved, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
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
