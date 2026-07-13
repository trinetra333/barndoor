package com.barndoor.app.dns

import android.app.Dialog
import android.net.VpnService
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.barndoor.app.MainActivity
import com.barndoor.app.R
import com.barndoor.app.databinding.DialogAddDnsBinding
import com.barndoor.app.databinding.DialogSpeedTestBinding
import com.barndoor.app.databinding.FragmentDnsBinding
import kotlinx.coroutines.launch

class DnsFragment : Fragment() {

    private var _binding: FragmentDnsBinding? = null
    private val binding get() = _binding!!
    private lateinit var repo: DnsRepository
    private lateinit var adapter: DnsListAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDnsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        repo = DnsRepository(requireContext())

        adapter = DnsListAdapter(
            onSelect = { index ->
                repo.setSelectedIndex(index)
                if (repo.isProxyRunning()) startProxyFlow() // restart with new selection
                refresh()
            },
            onDelete = { index ->
                repo.getServers().getOrNull(index)?.let { repo.removeServer(it) }
                refresh()
            }
        )
        binding.dnsRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.dnsRecyclerView.adapter = adapter

        binding.dnsStartStopButton.setOnClickListener {
            if (repo.isProxyRunning()) {
                DnsVpnService.stop(requireContext())
                repo.setProxyRunning(false)
                refresh()
            } else {
                startProxyFlow()
            }
        }

        binding.dnsAddCustom.setOnClickListener { showAddDialog() }
        binding.dnsSpeedTest.setOnClickListener { showSpeedTestDialog() }

        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun startProxyFlow() {
        val intent = VpnService.prepare(requireContext())
        (activity as? MainActivity)?.requestVpnConsent(intent) {
            DnsVpnService.start(requireContext())
            // Reflect optimistic state; the service also persists this once it's up.
            repo.setProxyRunning(true)
            binding.dnsRecyclerView.postDelayed({ if (isAdded) refresh() }, 400)
        }
    }

    private fun refresh() {
        val servers = repo.getServers()
        adapter.submit(servers, repo.getSelectedIndex())
        val running = repo.isProxyRunning()
        val selected = repo.getSelectedServer()
        binding.dnsStatusText.text = if (running && selected != null) {
            "Running \u2022 ${selected.name}"
        } else {
            "Stopped"
        }
        binding.dnsStartStopButton.text =
            getString(if (running) R.string.dns_stop else R.string.dns_start)
    }

    private fun showAddDialog() {
        val dialogBinding = DialogAddDnsBinding.inflate(LayoutInflater.from(requireContext()))
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.dns_add_dialog_title)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.action_save) { _, _ ->
                val name = dialogBinding.inputName.text?.toString()?.trim().orEmpty()
                val primaryInput = dialogBinding.inputPrimary.text?.toString()?.trim().orEmpty()
                val secondaryInput = dialogBinding.inputSecondary.text?.toString()?.trim()
                val dotInput = dialogBinding.inputDot.text?.toString()?.trim()

                val primary = primaryInput.takeIf { isValidIpv4(it) }
                val dotHostname = dotInput?.takeIf { it.isNotEmpty() }

                if (name.isNotEmpty() && (primary != null || dotHostname != null)) {
                    repo.addCustomServer(
                        DnsServer(
                            name = name,
                            primary = primary,
                            secondary = secondaryInput?.takeIf { it.isNotEmpty() && isValidIpv4(it) },
                            dotHostname = dotHostname,
                            custom = true
                        )
                    )
                    refresh()
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun showSpeedTestDialog() {
        val dialogBinding = DialogSpeedTestBinding.inflate(LayoutInflater.from(requireContext()))
        val servers = repo.getServers()
        val resultsAdapter = SpeedTestAdapter(servers)
        dialogBinding.speedTestRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        dialogBinding.speedTestRecyclerView.adapter = resultsAdapter
        dialogBinding.speedTestStatus.text = "Testing ${servers.size} resolvers\u2026"

        val dialog = Dialog(requireContext())
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(dialogBinding.root)
        dialog.setCancelable(true)
        dialogBinding.speedTestClose.setOnClickListener { dialog.dismiss() }
        dialog.show()

        viewLifecycleOwner.lifecycleScope.launch {
            var done = 0
            SpeedTest.runAll(servers) { result ->
                done++
                resultsAdapter.setResult(result)
                if (isAdded) {
                    dialogBinding.speedTestStatus.text =
                        if (done < servers.size) "Testing\u2026 ($done/${servers.size})" else "Done"
                }
            }
        }
    }

    private fun isValidIpv4(value: String): Boolean {
        val parts = value.split(".")
        if (parts.size != 4) return false
        return parts.all { it.toIntOrNull()?.let { n -> n in 0..255 } == true }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
