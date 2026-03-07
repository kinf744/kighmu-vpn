package com.kighmu.vpn.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.kighmu.vpn.R
import com.kighmu.vpn.models.*
import com.kighmu.vpn.ui.MainViewModel

class ConfigFragment : Fragment() {
    private val viewModel: MainViewModel by activityViewModels()
    private var currentTab = 0

    private lateinit var panels: List<LinearLayout>
    private lateinit var tabs: List<Button>

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_config, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tabs = listOf(
            view.findViewById(R.id.tab_slowdns),
            view.findViewById(R.id.tab_http),
            view.findViewById(R.id.tab_ws),
            view.findViewById(R.id.tab_ssl),
            view.findViewById(R.id.tab_xray),
            view.findViewById(R.id.tab_v2dns),
            view.findViewById(R.id.tab_hysteria)
        )

        panels = listOf(
            view.findViewById(R.id.panel_slowdns),
            view.findViewById(R.id.panel_http),
            view.findViewById(R.id.panel_ws),
            view.findViewById(R.id.panel_ssl),
            view.findViewById(R.id.panel_xray),
            view.findViewById(R.id.panel_xray),
            view.findViewById(R.id.panel_hysteria)
        )

        tabs.forEachIndexed { index, btn ->
            btn.setOnClickListener { selectTab(index) }
        }

        loadConfig(view)
        selectTab(0)

        view.findViewById<Button>(R.id.btn_save_config).setOnClickListener {
            saveConfig(view)
        }
    }

    private fun selectTab(index: Int) {
        currentTab = index
        tabs.forEachIndexed { i, btn ->
            btn.backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (i == index) 0xFF2196F3.toInt() else 0xFF333344.toInt()
            )
        }
        panels.forEachIndexed { i, panel ->
            panel.visibility = if (i == index) View.VISIBLE else View.GONE
        }
    }

    private fun loadConfig(view: View) {
        val c = viewModel.config.value
        view.findViewById<EditText>(R.id.et_ssh_host).setText(c.sshCredentials.host)
        view.findViewById<EditText>(R.id.et_ssh_port).setText(c.sshCredentials.port.toString())
        view.findViewById<EditText>(R.id.et_ssh_user).setText(c.sshCredentials.username)
        view.findViewById<EditText>(R.id.et_ssh_pass).setText(c.sshCredentials.password)
        view.findViewById<EditText>(R.id.et_dns_server).setText(c.slowDns.dnsServer)
        view.findViewById<EditText>(R.id.et_dns_nameserver).setText(c.slowDns.nameserver)
        view.findViewById<EditText>(R.id.et_dns_pubkey).setText(c.slowDns.publicKey)
        view.findViewById<EditText>(R.id.et_proxy_host).setText(c.httpProxy.proxyHost)
        view.findViewById<EditText>(R.id.et_proxy_port).setText(c.httpProxy.proxyPort.toString())
        view.findViewById<EditText>(R.id.et_payload).setText(c.httpProxy.customPayload)
        view.findViewById<EditText>(R.id.et_ws_host).setText(c.sshWebSocket.wsHost)
        view.findViewById<EditText>(R.id.et_ws_port).setText(c.sshWebSocket.wsPort.toString())
        view.findViewById<EditText>(R.id.et_ws_path).setText(c.sshWebSocket.wsPath)
        view.findViewById<EditText>(R.id.et_ssl_host).setText(c.sshSsl.sslHost)
        view.findViewById<EditText>(R.id.et_ssl_port).setText(c.sshSsl.sslPort.toString())
        view.findViewById<EditText>(R.id.et_sni).setText(c.sshSsl.sni)
        view.findViewById<EditText>(R.id.et_xray_host).setText(c.xray.serverAddress)
        view.findViewById<EditText>(R.id.et_xray_port).setText(c.xray.serverPort.toString())
        view.findViewById<EditText>(R.id.et_xray_uuid).setText(c.xray.uuid)
        view.findViewById<EditText>(R.id.et_xray_path).setText(c.xray.wsPath)
        view.findViewById<EditText>(R.id.et_xray_sni).setText(c.xray.sni)
        view.findViewById<EditText>(R.id.et_xray_json).setText(c.xray.jsonConfig)
        view.findViewById<EditText>(R.id.et_hys_host).setText(c.hysteria.serverHost)
        view.findViewById<EditText>(R.id.et_hys_port).setText(c.hysteria.serverPort.toString())
        view.findViewById<EditText>(R.id.et_hys_auth).setText(c.hysteria.authString)
        view.findViewById<EditText>(R.id.et_hys_sni).setText(c.hysteria.sni)
    }

    private fun saveConfig(view: View) {
        val c = viewModel.config.value
        val ssh = c.sshCredentials.copy(
            host = view.findViewById<EditText>(R.id.et_ssh_host).text.toString(),
            port = view.findViewById<EditText>(R.id.et_ssh_port).text.toString().toIntOrNull() ?: 22,
            username = view.findViewById<EditText>(R.id.et_ssh_user).text.toString(),
            password = view.findViewById<EditText>(R.id.et_ssh_pass).text.toString()
        )
        val dns = c.slowDns.copy(
            dnsServer = view.findViewById<EditText>(R.id.et_dns_server).text.toString(),
            nameserver = view.findViewById<EditText>(R.id.et_dns_nameserver).text.toString(),
            publicKey = view.findViewById<EditText>(R.id.et_dns_pubkey).text.toString()
        )
        val http = c.httpProxy.copy(
            proxyHost = view.findViewById<EditText>(R.id.et_proxy_host).text.toString(),
            proxyPort = view.findViewById<EditText>(R.id.et_proxy_port).text.toString().toIntOrNull() ?: 8080,
            customPayload = view.findViewById<EditText>(R.id.et_payload).text.toString()
        )
        val ws = c.sshWebSocket.copy(
            wsHost = view.findViewById<EditText>(R.id.et_ws_host).text.toString(),
            wsPort = view.findViewById<EditText>(R.id.et_ws_port).text.toString().toIntOrNull() ?: 80,
            wsPath = view.findViewById<EditText>(R.id.et_ws_path).text.toString()
        )
        val ssl = c.sshSsl.copy(
            sslHost = view.findViewById<EditText>(R.id.et_ssl_host).text.toString(),
            sslPort = view.findViewById<EditText>(R.id.et_ssl_port).text.toString().toIntOrNull() ?: 443,
            sni = view.findViewById<EditText>(R.id.et_sni).text.toString()
        )
        val xray = c.xray.copy(
            serverAddress = view.findViewById<EditText>(R.id.et_xray_host).text.toString(),
            serverPort = view.findViewById<EditText>(R.id.et_xray_port).text.toString().toIntOrNull() ?: 443,
            uuid = view.findViewById<EditText>(R.id.et_xray_uuid).text.toString(),
            wsPath = view.findViewById<EditText>(R.id.et_xray_path).text.toString(),
            sni = view.findViewById<EditText>(R.id.et_xray_sni).text.toString(),
            jsonConfig = view.findViewById<EditText>(R.id.et_xray_json).text.toString()
        )
        val hys = c.hysteria.copy(
            serverHost = view.findViewById<EditText>(R.id.et_hys_host).text.toString(),
            serverPort = view.findViewById<EditText>(R.id.et_hys_port).text.toString().toIntOrNull() ?: 443,
            authString = view.findViewById<EditText>(R.id.et_hys_auth).text.toString(),
            sni = view.findViewById<EditText>(R.id.et_hys_sni).text.toString()
        )
        viewModel.saveConfig(c.copy(
            sshCredentials = ssh, slowDns = dns, httpProxy = http,
            sshWebSocket = ws, sshSsl = ssl, xray = xray, hysteria = hys
        ))
        Toast.makeText(requireContext(), "Config saved!", Toast.LENGTH_SHORT).show()
    }
}