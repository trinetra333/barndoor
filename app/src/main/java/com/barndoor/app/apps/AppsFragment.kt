package com.barndoor.app.apps

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
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
    private var loadedApps: List<AppInfo> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAppsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        repo = DnsRepository(requireContext())

        adapter = AppListAdapter { app -> showAssignmentPicker(app) }
        binding.appsRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.appsRecyclerView.adapter = adapter

        updateScopeLabel()
        loadApps()
    }

    override fun onResume() {
        super.onResume()
        if (loadedApps.isNotEmpty()) {
            adapter.submit(loadedApps, buildAssignmentLabels())
        }
        updateScopeLabel()
    }

    private fun loadApps() {
        binding.appsProgress.visibility = View.VISIBLE
        viewLifecycleOwner.lifecycleScope.launch {
            val apps = AppRepository.loadLaunchableApps(requireContext())
            if (!isAdded) return@launch
            loadedApps = apps
            binding.appsProgress.visibility = View.GONE
            adapter.submit(loadedApps, buildAssignmentLabels())
        }
    }

    private fun buildAssignmentLabels(): Map<String, String> {
        val servers = repo.getServers().associateBy { it.id }
        return repo.getAppAssignments().mapValues { (_, id) -> servers[id]?.name ?: "Default" }
    }

    private fun showAssignmentPicker(app: AppInfo) {
        val servers = repo.getServers()
        val defaultLabel = "Default (${repo.getSelectedServer()?.name ?: "none set"})"
        val options = listOf(defaultLabel) + servers.map { it.name }
        val currentId = repo.getAppAssignments()[app.packageName]
        val currentIndex = if (currentId == null) 0 else (servers.indexOfFirst { it.id == currentId } + 1).coerceAtLeast(0)

        AlertDialog.Builder(requireContext())
            .setTitle(app.label)
            .setSingleChoiceItems(options.toTypedArray(), currentIndex) { dialog, which ->
                if (which == 0) {
                    repo.setAppAssignment(app.packageName, null)
                } else {
                    repo.setAppAssignment(app.packageName, servers[which - 1].id)
                }
                adapter.submit(loadedApps, buildAssignmentLabels())
                updateScopeLabel()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun updateScopeLabel() {
        val count = repo.getAppAssignments().size
        binding.appsScopeLabel.text = if (count == 0) {
            getString(R.string.apps_scope_device_wide)
        } else {
            "$count app${if (count == 1) "" else "s"} with a custom DNS assignment"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
