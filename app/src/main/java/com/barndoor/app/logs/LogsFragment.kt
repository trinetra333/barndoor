package com.barndoor.app.logs

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.barndoor.app.databinding.FragmentLogsBinding
import com.barndoor.app.dns.LogPrefs
import com.barndoor.app.dns.QueryLogStore

class LogsFragment : Fragment() {

    private var _binding: FragmentLogsBinding? = null
    private val binding get() = _binding!!
    private lateinit var prefs: LogPrefs
    private lateinit var adapter: LogAdapter

    private val listener: () -> Unit = { activity?.runOnUiThread { refreshList() } }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLogsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = LogPrefs(requireContext())
        adapter = LogAdapter()
        binding.logsRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.logsRecyclerView.adapter = adapter

        binding.logsSwitch.isChecked = prefs.enabled
        binding.logsSwitch.setOnCheckedChangeListener { _, checked -> prefs.enabled = checked }

        binding.logsClear.setOnClickListener { QueryLogStore.clear() }

        refreshList()
    }

    override fun onResume() {
        super.onResume()
        QueryLogStore.addListener(listener)
        refreshList()
    }

    override fun onPause() {
        super.onPause()
        QueryLogStore.removeListener(listener)
    }

    private fun refreshList() {
        if (!isAdded) return
        val logs = QueryLogStore.snapshot()
        adapter.submit(logs)
        binding.logsCount.text = "${logs.size} recent queries"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
