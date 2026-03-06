package com.kighmu.vpn.ui.fragments

import android.os.Bundle
import android.view.*
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.kighmu.vpn.R
import com.kighmu.vpn.databinding.FragmentLogsBinding
import com.kighmu.vpn.models.LogEntry
import com.kighmu.vpn.ui.MainViewModel
import com.kighmu.vpn.utils.KighmuLogger
import kotlinx.coroutines.launch

class LogsFragment : Fragment() {

    private var _binding: FragmentLogsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()
    private lateinit var adapter: LogAdapter
    private var autoScroll = true

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentLogsBinding.inflate(inflater, container, false)
        setHasOptionsMenu(true)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        setupControls()
        observeLogs()
    }

    private fun setupRecyclerView() {
        adapter = LogAdapter()
        binding.rvLogs.layoutManager = LinearLayoutManager(requireContext()).apply { stackFromEnd = true }
        binding.rvLogs.adapter = adapter
    }

    private fun setupControls() {
        binding.btnClearLogs.setOnClickListener { viewModel.clearLogs() }

        binding.btnShareLogs.setOnClickListener {
            val text = KighmuLogger.getAllLogsText()
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_TEXT, text)
                putExtra(android.content.Intent.EXTRA_SUBJECT, "KIGHMU VPN Logs")
            }
            startActivity(android.content.Intent.createChooser(intent, "Share Logs"))
        }

        binding.switchAutoScroll.isChecked = autoScroll
        binding.switchAutoScroll.setOnCheckedChangeListener { _, checked -> autoScroll = checked }

        // Filter by level
        binding.chipAll.setOnClickListener { adapter.setFilter(null) }
        binding.chipInfo.setOnClickListener { adapter.setFilter(LogEntry.LogLevel.INFO) }
        binding.chipError.setOnClickListener { adapter.setFilter(LogEntry.LogLevel.ERROR) }
        binding.chipWarning.setOnClickListener { adapter.setFilter(LogEntry.LogLevel.WARNING) }
    }

    private fun observeLogs() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.logs.collect { logs ->
                adapter.submitList(logs)
                if (autoScroll && logs.isNotEmpty()) {
                    binding.rvLogs.scrollToPosition(logs.size - 1)
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

// ─── Log Adapter ─────────────────────────────────────────────────────────────

class LogAdapter : androidx.recyclerview.widget.RecyclerView.Adapter<LogAdapter.LogViewHolder>() {

    private var allLogs: List<LogEntry> = emptyList()
    private var filteredLogs: List<LogEntry> = emptyList()
    private var currentFilter: LogEntry.LogLevel? = null

    fun submitList(logs: List<LogEntry>) {
        allLogs = logs
        applyFilter()
    }

    fun setFilter(level: LogEntry.LogLevel?) {
        currentFilter = level
        applyFilter()
    }

    private fun applyFilter() {
        filteredLogs = if (currentFilter == null) allLogs
        else allLogs.filter { it.level == currentFilter }
        notifyDataSetChanged()
    }

    override fun getItemCount() = filteredLogs.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_log, parent, false)
        return LogViewHolder(view)
    }

    override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
        holder.bind(filteredLogs[position])
    }

    class LogViewHolder(view: View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(view) {
        private val tvTime: android.widget.TextView = view.findViewById(R.id.tv_log_time)
        private val tvLevel: android.widget.TextView = view.findViewById(R.id.tv_log_level)
        private val tvMessage: android.widget.TextView = view.findViewById(R.id.tv_log_message)

        fun bind(entry: LogEntry) {
            val timeStr = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date(entry.timestamp))
            tvTime.text = timeStr
            tvLevel.text = entry.level.name
            tvMessage.text = "[${entry.tag}] ${entry.message}"

            val color = when (entry.level) {
                LogEntry.LogLevel.ERROR -> 0xFFFF5252.toInt()
                LogEntry.LogLevel.WARNING -> 0xFFFFAB40.toInt()
                LogEntry.LogLevel.DEBUG -> 0xFF9E9E9E.toInt()
                else -> 0xFFE0E0E0.toInt()
            }
            tvMessage.setTextColor(color)
            tvLevel.setTextColor(color)
        }
    }
}
