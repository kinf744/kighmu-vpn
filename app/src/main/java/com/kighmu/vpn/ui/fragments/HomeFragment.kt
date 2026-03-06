package com.kighmu.vpn.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.kighmu.vpn.R
import com.kighmu.vpn.databinding.FragmentHomeBinding
import com.kighmu.vpn.models.ConnectionStatus
import com.kighmu.vpn.models.TunnelMode
import com.kighmu.vpn.ui.MainViewModel
import com.kighmu.vpn.ui.activities.MainActivity
import kotlinx.coroutines.launch

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupTunnelModeSpinner()
        setupConnectButton()
        observeViewModel()
    }

    // ─── Tunnel Mode Selector ─────────────────────────────────────────────────

    private fun setupTunnelModeSpinner() {
        val modes = TunnelMode.values().map { it.label }
        val adapter = android.widget.ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            modes
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        binding.spinnerTunnelMode.adapter = adapter
        binding.spinnerTunnelMode.setSelection(viewModel.config.value.tunnelMode.id)

        binding.spinnerTunnelMode.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>, view: View?, pos: Int, id: Long) {
                    viewModel.updateTunnelMode(TunnelMode.fromId(pos))
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>) {}
            }
    }

    // ─── Connect Button ───────────────────────────────────────────────────────

    private fun setupConnectButton() {
        binding.btnConnect.setOnClickListener {
            val activity = requireActivity() as MainActivity
            when (viewModel.connectionStatus.value) {
                ConnectionStatus.CONNECTED, ConnectionStatus.CONNECTING, ConnectionStatus.RECONNECTING ->
                    activity.requestVpnDisconnect()
                else ->
                    activity.requestVpnConnect()
            }
        }
    }

    // ─── Observe State ────────────────────────────────────────────────────────

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.connectionStatus.collect { status ->
                updateConnectionUI(status)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.stats.collect { stats ->
                binding.tvUploadSpeed.text = "↑ ${stats.formatUploadSpeed()}"
                binding.tvDownloadSpeed.text = "↓ ${stats.formatDownloadSpeed()}"
                binding.tvUploadTotal.text = stats.formatUpload()
                binding.tvDownloadTotal.text = stats.formatDownload()
                binding.tvDuration.text = stats.formatElapsed()
                binding.tvPing.text = if (stats.ping > 0) "${stats.ping}ms" else "--"
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.statusMessage.collect { msg ->
                if (msg.isNotEmpty()) binding.tvStatusMessage.text = msg
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.config.collect { cfg ->
                binding.tvCurrentMode.text = cfg.tunnelMode.label
                binding.tvConfigName.text = cfg.configName
                binding.spinnerTunnelMode.setSelection(cfg.tunnelMode.id)
            }
        }
    }

    private fun updateConnectionUI(status: ConnectionStatus) {
        val (btnText, btnColor, statusText, statusColor, indicatorRes) = when (status) {
            ConnectionStatus.CONNECTED -> listOf(
                getString(R.string.disconnect),
                R.color.error_red,
                getString(R.string.status_connected),
                R.color.success_green,
                R.drawable.ic_vpn_lock_on
            )
            ConnectionStatus.CONNECTING -> listOf(
                getString(R.string.cancel),
                R.color.warning_orange,
                getString(R.string.status_connecting),
                R.color.warning_orange,
                R.drawable.ic_vpn_lock_connecting
            )
            ConnectionStatus.RECONNECTING -> listOf(
                getString(R.string.cancel),
                R.color.warning_orange,
                getString(R.string.status_reconnecting),
                R.color.warning_orange,
                R.drawable.ic_vpn_lock_connecting
            )
            ConnectionStatus.ERROR -> listOf(
                getString(R.string.connect),
                R.color.accent_blue,
                getString(R.string.status_error),
                R.color.error_red,
                R.drawable.ic_vpn_lock_off
            )
            else -> listOf(
                getString(R.string.connect),
                R.color.accent_blue,
                getString(R.string.status_disconnected),
                R.color.text_secondary,
                R.drawable.ic_vpn_lock_off
            )
        }

        binding.btnConnect.text = btnText as String
        binding.tvConnectionStatus.text = statusText as String
        binding.tvConnectionStatus.setTextColor(resources.getColor(statusColor as Int, null))
        binding.ivVpnStatus.setImageResource(indicatorRes as Int)

        // Stats visibility
        val showStats = status == ConnectionStatus.CONNECTED
        binding.cardStats.visibility = if (showStats) View.VISIBLE else View.GONE

        // Loading indicator
        binding.progressConnecting.visibility = if (
            status == ConnectionStatus.CONNECTING || status == ConnectionStatus.RECONNECTING
        ) View.VISIBLE else View.GONE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
