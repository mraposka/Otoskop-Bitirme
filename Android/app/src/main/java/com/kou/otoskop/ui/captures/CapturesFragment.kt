package com.kou.otoskop.ui.captures

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.kou.otoskop.R
import com.kou.otoskop.data.capture.CaptureItem
import com.kou.otoskop.databinding.FragmentCapturesBinding
import com.kou.otoskop.ui.shared.CaptureViewModel
import kotlinx.coroutines.launch

class CapturesFragment : Fragment(R.layout.fragment_captures) {

    private var _binding: FragmentCapturesBinding? = null
    private val binding get() = _binding!!

    private val capture: CaptureViewModel by activityViewModels {
        CaptureViewModel.Factory(requireActivity().application)
    }

    private lateinit var adapter: CaptureAdapter

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentCapturesBinding.bind(view)

        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }

        adapter = CaptureAdapter(
            fileResolver = { (requireActivity().application as com.kou.otoskop.OtoskopApp).captureRepo.fileOf(it) },
            onOpen = ::openItem,
            onDelete = ::confirmDelete,
        )
        binding.list.layoutManager = LinearLayoutManager(requireContext())
        binding.list.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                capture.items.collect { items ->
                    adapter.submitList(items)
                    binding.emptyText.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }
    }

    private fun openItem(item: CaptureItem) {
        val app = requireActivity().application as com.kou.otoskop.OtoskopApp
        val file = app.captureRepo.fileOf(item)
        if (!file.exists()) {
            Toast.makeText(requireContext(), "Dosya bulunamadı", Toast.LENGTH_SHORT).show()
            return
        }
        val uri: Uri = FileProvider.getUriForFile(
            requireContext(),
            "${requireContext().packageName}.fileprovider",
            file,
        )
        val mime = if (item.type == "video") "video/mp4" else "image/jpeg"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(requireContext(), "Açacak uygulama yok", Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmDelete(item: CaptureItem) {
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(R.string.captures_delete_confirm)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.action_delete) { _, _ -> capture.delete(item.id) }
            .show()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
