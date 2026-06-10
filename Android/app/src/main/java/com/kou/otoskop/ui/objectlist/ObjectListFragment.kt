package com.kou.otoskop.ui.objectlist

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
import androidx.recyclerview.widget.LinearLayoutManager
import com.kou.otoskop.R
import com.kou.otoskop.databinding.FragmentObjectListBinding
import com.kou.otoskop.ui.shared.ObjectsViewModel
import com.kou.otoskop.ui.shared.TelescopeViewModel
import kotlinx.coroutines.launch

class ObjectListFragment : Fragment(R.layout.fragment_object_list) {

    private var _binding: FragmentObjectListBinding? = null
    private val binding get() = _binding!!

    private val objects: ObjectsViewModel by activityViewModels {
        ObjectsViewModel.Factory(requireActivity().application)
    }
    private val telescope: TelescopeViewModel by activityViewModels {
        TelescopeViewModel.Factory(requireActivity().application)
    }

    private val adapter = CelestialObjectAdapter { obj ->
        telescope.selectTarget(obj)
        findNavController().navigate(R.id.action_objectList_to_control)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentObjectListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }

        binding.list.layoutManager = LinearLayoutManager(requireContext())
        binding.list.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                objects.state.collect { st ->
                    adapter.submitList(st.objects)
                    binding.emptyText.visibility =
                        if (st.objects.isEmpty() && !st.loading) View.VISIBLE
                        else View.GONE
                    if (st.error != null) binding.errorView.show(st.error)
                    else binding.errorView.hide()
                }
            }
        }
    }

    override fun onDestroyView() {
        binding.list.adapter = null
        _binding = null
        super.onDestroyView()
    }
}
