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
    private lateinit var btnConnect: com.google.android.material.button.MaterialButton
    private lateinit var tvStatus: TextView
    private lateinit var spinnerMode: Spinner
    private lateinit var viewGlowRing: View
    private lateinit var cardUserMessage: androidx.cardview.widget.CardView
    private lateinit var tvUserMessage: TextView

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_home, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        try {
            btnConnect = view.findViewById(R.id.btn_connect)
            tvStatus = view.findViewById(R.id.tv_connection_status)
            viewGlowRing = view.findViewById(R.id.view_glow_ring)
            cardUserMessage = view.findViewById(R.id.card_user_message)
            tvUserMessage = view.findViewById(R.id.tv_user_message)
            
            // Afficher message de la config exportée (en bas)
            viewLifecycleOwner.lifecycleScope.launch {
                viewModel.config.collect { cfg ->
                    val msg = cfg.exportConfig?.userMessage ?: ""
                    if (msg.isNotBlank()) {
                        tvUserMessage.text = msg
                        cardUserMessage.visibility = android.view.View.VISIBLE
                    } else {
                        cardUserMessage.visibility = android.view.View.GONE
                    }
                }
            }
            spinnerMode = view.findViewById(R.id.spinner_tunnel_mode)

            val modes = TunnelMode.values().map { it.label }
            val adapter = ArrayAdapter(requireContext(), R.layout.spinner_item, modes)
            adapter.setDropDownViewResource(R.layout.spinner_item)
            spinnerMode.adapter = adapter
            spinnerMode.setSelection(viewModel.config.value.tunnelMode.ordinal)

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
                    ConnectionStatus.CONNECTED, ConnectionStatus.CONNECTING,
                    ConnectionStatus.RECONNECTING, ConnectionStatus.ERROR ->
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
                    
                    // Mise à jour dynamique de la couleur du bouton et de l'anneau
                    val color = when (status) {
                        ConnectionStatus.CONNECTED -> android.graphics.Color.parseColor("#4CAF50") // Vert
                        ConnectionStatus.CONNECTING, ConnectionStatus.RECONNECTING, ConnectionStatus.ERROR -> 
                            android.graphics.Color.parseColor("#F44336") // Rouge
                        else -> android.graphics.Color.parseColor("#2196F3") // Bleu par défaut
                    }
                    
                    // Appliquer la couleur à l'anneau (glow)
                    viewGlowRing.backgroundTintList = android.content.res.ColorStateList.valueOf(color)
                    // Appliquer la couleur au contour du bouton
                    btnConnect.setStrokeColor(android.content.res.ColorStateList.valueOf(color))
                }
            }

            viewLifecycleOwner.lifecycleScope.launch {
                viewModel.config.collect { cfg ->
                    spinnerMode.setSelection(cfg.tunnelMode.ordinal)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}