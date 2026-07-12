package com.barndoor.app.apps

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.barndoor.app.R
import com.barndoor.app.databinding.FragmentAppsBinding
import com.barndoor.app.dns.DnsRepository
import kotlinx.coroutines.launch

class AppsFragment : Fragment() {

    private var _binding: FragmentAppsBinding? = null
    private val binding get() = _binding!!
    private lateinit var repo: DnsRepository
    private lateinit var adapter: AppListAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAppsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        repo = DnsRepository(requireContext())

        adapter = AppListAdapter { app, checked ->
            val current = repo.getSelectedApps().toMutableSet()
            if (checked) current.add(app.packageName) else current.remove(app.packageName)
            repo.setSelectedApps(current)
            updateScopeLabel()
        }
        binding.appsRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.appsRecyclerView.adapter = adapter

        updateScopeLabel()
        loadApps()
    }

    private fun loadApps() {
        binding.appsProgress.visibility = View.VISIBLE
        viewLifecycleOwner.lifecycleScope.launch {
            val apps = AppRepository.loadLaunchableApps(requireContext())
            if (!isAdded) return@launch
            binding.appsProgress.visibility = View.GONE
            adapter.submit(apps, repo.getSelectedApps())
        }
    }

    private fun updateScopeLabel() {
        val count = repo.getSelectedApps().size
        binding.appsScopeLabel.text = if (count == 0) {
            getString(R.string.apps_scope_device_wide)
        } else {
            "Limited to $count selected app${if (count == 1) "" else "s"}"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
