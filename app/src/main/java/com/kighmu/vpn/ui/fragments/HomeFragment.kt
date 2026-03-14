package com.kighmu.vpn.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.kighmu.vpn.R
import com.kighmu.vpn.models.ConnectionStatus
import com.kighmu.vpn.models.TunnelMode
import com.kighmu.vpn.ui.MainViewModel
import com.kighmu.vpn.ui.activities.MainActivity
import kotlinx.coroutines.launch

class HomeFragment : Fragment() {
    private val viewModel: MainViewModel by activityViewModels()
    private lateinit var btnConnect: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvMode: TextView
    private lateinit var spinnerMode: Spinner

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_home, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        try {
            btnConnect = view.findViewById(R.id.btn_connect)
            tvStatus = view.findViewById(R.id.tv_connection_status)
            tvMode = view.findViewById(R.id.tv_current_mode)
            spinnerMode = view.findViewById(R.id.spinner_tunnel_mode)

            val modes = TunnelMode.values().map { it.label }
            val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, modes)
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinnerMode.adapter = adapter
            spinnerMode.setSelection(viewModel.config.value.tunnelMode.id)

            spinnerMode.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>, view: android.view.View?, position: Int, id: Long) {
                    val selectedMode = TunnelMode.values()[position]
                    if (selectedMode != viewModel.config.value.tunnelMode) {
                        viewModel.updateTunnelMode(selectedMode)
                    }
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>) {}
            }

            btnConnect.setOnClickListener {
                val activity = requireActivity() as MainActivity
                when (viewModel.connectionStatus.value) {
                    ConnectionStatus.CONNECTED, ConnectionStatus.CONNECTING ->
                        activity.requestVpnDisconnect()
                    else -> activity.requestVpnConnect()
                }
            }

            viewLifecycleOwner.lifecycleScope.launch {
                viewModel.connectionStatus.collect { status ->
                    tvStatus.text = when (status) {
                        ConnectionStatus.CONNECTED -> "CONNECTED"
                        ConnectionStatus.CONNECTING, ConnectionStatus.RECONNECTING -> "CONNECTING"
                        else -> "DISCONNECTED"
                    }
                    btnConnect.text = if (status == ConnectionStatus.CONNECTED) "DISCONNECT" else "CONNECT"
                }
            }

            viewLifecycleOwner.lifecycleScope.launch {
                viewModel.config.collect { cfg ->
                    tvMode.text = cfg.tunnelMode.label
                    spinnerMode.setSelection(cfg.tunnelMode.id)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}