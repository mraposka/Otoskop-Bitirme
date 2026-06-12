package com.kou.otoskop.ui.skyarea

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
import com.kou.otoskop.core.AppConfig
import com.kou.otoskop.core.AppError
import com.kou.otoskop.core.AppErrorKind
import com.kou.otoskop.core.AstroMath
import com.kou.otoskop.data.model.PhoneSensorData
import com.kou.otoskop.data.model.SkyArea
import com.kou.otoskop.databinding.FragmentSkyAreaBinding
import com.kou.otoskop.ui.shared.ObjectsViewModel
import com.kou.otoskop.ui.shared.SensorViewModel
import com.kou.otoskop.ui.shared.TelescopeViewModel
import kotlinx.coroutines.launch

class SkyAreaFragment : Fragment(R.layout.fragment_sky_area) {

    private var _binding: FragmentSkyAreaBinding? = null
    private val binding get() = _binding!!

    private var halfWidth: Float = AppConfig.DEFAULT_AREA_HALF_WIDTH_DEG

    /**
     * Sadece "scan" tetiklediğinde otomatik navigate olalım. Aksi halde
     * geri dönüldüğünde önceki sonuçlar yüzünden tekrar listeye atlayabilir.
     */
    private var pendingNavigateOnResult: Boolean = false

    private val sensor: SensorViewModel by activityViewModels {
        val app = requireActivity().application as OtoskopApp
        SensorViewModel.Factory(app.sensorRepo)
    }
    private val objects: ObjectsViewModel by activityViewModels {
        ObjectsViewModel.Factory(requireActivity().application)
    }
    private val telescope: TelescopeViewModel by activityViewModels {
        TelescopeViewModel.Factory(requireActivity().application)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentSkyAreaBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }

        binding.halfWidthSlider.value = halfWidth
        binding.halfWidthSlider.addOnChangeListener { _, value, _ ->
            halfWidth = value
            refresh(sensor.state.value)
        }

        binding.scanBtn.setOnClickListener {
            val s = sensor.state.value
            val area = buildArea(s)
            objects.setArea(area)
            pendingNavigateOnResult = true
            objects.scanArea(s)
            // Görev 5: seçilen alanın merkezine teleskobu döndür (gerçek dünya
            // azimut/altitude; kalibrasyon offset'i firmware'de uygulanır).
            telescope.aimAtArea(area.centerAzimuth, area.centerAltitude)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { sensor.state.collect { refresh(it) } }
                launch {
                    objects.state.collect { st ->
                        // Konum çözümü ESP GPS -> telefon GPS -> demo sırasıyla
                        // denenir; bu yüzden butonu sadece yüklenirken kilitle.
                        // Konum gerçekten yoksa tarama net bir hata gösterir.
                        binding.scanBtn.isEnabled = !st.loading
                        binding.scanBtn.text = if (st.loading)
                            getString(R.string.status_scanning)
                        else getString(R.string.action_scan_area)

                        // Tarama bittiğinde: hata yoksa listeye geç (boş olsa
                        // bile listede "obje yok" açıklaması görünsün; eskiden
                        // boş sonuçta hiçbir şey olmuyordu). Hata varsa burada
                        // kal ve hatayı göster.
                        if (pendingNavigateOnResult && !st.loading) {
                            pendingNavigateOnResult = false
                            if (st.error == null) {
                                findNavController()
                                    .navigate(R.id.action_skyArea_to_objectList)
                            }
                        }
                        if (st.error != null) binding.errorView.show(st.error)
                    }
                }
            }
        }
    }

    private fun refresh(s: PhoneSensorData) {
        val demo = (requireActivity().application as OtoskopApp).isDemoMode
        binding.compass.setAzimuth(s.derivedAzimuth.toFloat())
        binding.compassReadout.text = buildString {
            append(AstroMath.cardinal(s.derivedAzimuth))
            append("  Az ").append("%.1f".format(s.derivedAzimuth)).append('°')
            append("  Alt ").append("%.1f".format(s.derivedAltitude)).append('°')
        }

        val area = buildArea(s)
        binding.chipAz.setLabel("Az")
        binding.chipAz.setOk(true)
        binding.chipAz.setDetail(
            "%.0f° - %.0f°".format(area.azimuthMin, area.azimuthMax),
        )
        binding.chipAlt.setLabel("Alt")
        binding.chipAlt.setOk(true)
        binding.chipAlt.setDetail(
            "%.0f° - %.0f°".format(area.altitudeMin, area.altitudeMax),
        )

        when {
            demo -> binding.errorView.hide()
            !s.compassCalibrated -> binding.errorView.show(
                AppError(
                    AppErrorKind.COMPASS_UNCALIBRATED,
                    "Telefonu 8 işareti çizecek şekilde sallayın",
                ),
            )
            !s.hasGps -> binding.errorView.show(
                AppError(AppErrorKind.GPS_UNAVAILABLE, "GPS bekleniyor..."),
            )
            else -> binding.errorView.hide()
        }
    }

    private fun buildArea(s: PhoneSensorData): SkyArea = SkyArea(
        centerAzimuth = s.derivedAzimuth,
        centerAltitude = s.derivedAltitude,
        halfWidthDeg = halfWidth.toDouble(),
    )

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
