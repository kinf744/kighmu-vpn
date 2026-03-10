package com.kighmu.vpn.ui.fragments

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.kighmu.vpn.R
import com.kighmu.vpn.models.*
import com.kighmu.vpn.ui.MainViewModel
import org.json.JSONObject

class ConfigFragment : Fragment() {
    private val viewModel: MainViewModel by activityViewModels()
    private var currentTab = 0

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_config, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val tabs = listOf(
            view.findViewById<Button>(R.id.tab_slowdns),
            view.findViewById<Button>(R.id.tab_http),
            view.findViewById<Button>(R.id.tab_ws),
            view.findViewById<Button>(R.id.tab_ssl),
            view.findViewById<Button>(R.id.tab_xray),
            view.findViewById<Button>(R.id.tab_v2dns),
            view.findViewById<Button>(R.id.tab_hysteria),
            view.findViewById<Button>(R.id.tab_zivpn),
            view.findViewById<Button>(R.id.tab_psiphon)
        )

        val sshSection = view.findViewById<LinearLayout>(R.id.section_ssh)
        val panels = listOf(
            view.findViewById<LinearLayout>(R.id.panel_slowdns),
            view.findViewById<LinearLayout>(R.id.panel_http),
            view.findViewById<LinearLayout>(R.id.panel_ws),
            view.findViewById<LinearLayout>(R.id.panel_ssl),
            view.findViewById<LinearLayout>(R.id.panel_xray),
            view.findViewById<LinearLayout>(R.id.panel_v2dns),
            view.findViewById<LinearLayout>(R.id.panel_hysteria),
            view.findViewById<LinearLayout>(R.id.panel_zivpn),
            view.findViewById<LinearLayout>(R.id.panel_psiphon)
        )

        // SSH not needed for xray(4), v2dns(5), hysteria(6)
        val noSshTabs = setOf(4, 5, 6, 7, 8)

        fun selectTab(index: Int) {
            currentTab = index
            tabs.forEachIndexed { i, btn ->
                btn.backgroundTintList = android.content.res.ColorStateList.valueOf(
                    if (i == index) 0xFF2196F3.toInt() else 0xFF333344.toInt()
                )
            }
            panels.forEachIndexed { i, panel ->
                panel.visibility = if (i == index) View.VISIBLE else View.GONE
            }
            sshSection.visibility = if (index in noSshTabs) View.GONE else View.VISIBLE
        }

        tabs.forEachIndexed { index, btn -> btn.setOnClickListener { selectTab(index) } }
        loadConfig(view)
        selectTab(0)

        // JSON buttons
        val etJson = view.findViewById<EditText>(R.id.et_xray_json)
        val tvJsonStatus = view.findViewById<TextView>(R.id.tv_json_status)

        view.findViewById<Button>(R.id.btn_paste_json).setOnClickListener {
            val cb = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = cb.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
            etJson.setText(text)
            validateJson(text, tvJsonStatus)
        }

        view.findViewById<Button>(R.id.btn_copy_json).setOnClickListener {
            val cb = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cb.setPrimaryClip(ClipData.newPlainText("xray_config", etJson.text.toString()))
            Toast.makeText(requireContext(), "Copied!", Toast.LENGTH_SHORT).show()
        }

        view.findViewById<Button>(R.id.btn_format_json).setOnClickListener {
            try {
                val formatted = JSONObject(etJson.text.toString()).toString(2)
                etJson.setText(formatted)
                tvJsonStatus.text = "Valid JSON"
                tvJsonStatus.setTextColor(0xFF00C853.toInt())
            } catch (e: Exception) {
                tvJsonStatus.text = "Invalid JSON: ${e.message}"
                tvJsonStatus.setTextColor(0xFFFF5252.toInt())
            }
        }

        view.findViewById<Button>(R.id.btn_select_all_json).setOnClickListener {
            etJson.selectAll()
        }

        view.findViewById<Button>(R.id.btn_parse_link).setOnClickListener {
            val link = view.findViewById<EditText>(R.id.et_xray_link).text.toString()
            val status = view.findViewById<TextView>(R.id.tv_link_status)
            validateLink(link, status)
        }

        view.findViewById<Button>(R.id.btn_parse_v2dns_link).setOnClickListener {
            val link = view.findViewById<EditText>(R.id.et_v2dns_link).text.toString()
            val status = view.findViewById<TextView>(R.id.tv_v2dns_link_status)
            validateLink(link, status)
        }

        view.findViewById<Button>(R.id.btn_save_config).setOnClickListener {
            saveConfig(view)
        }
    }

    private fun validateLink(link: String, statusView: TextView) {
        val valid = link.startsWith("vmess://") || link.startsWith("vless://") ||
                    link.startsWith("trojan://") || link.startsWith("socks://")
        if (valid) {
            statusView.text = "Valid link"
            statusView.setTextColor(0xFF00C853.toInt())
        } else {
            statusView.text = "Invalid link. Must start with vmess://, vless://, trojan:// or socks://"
            statusView.setTextColor(0xFFFF5252.toInt())
        }
    }

    private fun validateJson(json: String, statusView: TextView) {
        if (json.isBlank()) return
        try {
            JSONObject(json)
            statusView.text = "Valid JSON"
            statusView.setTextColor(0xFF00C853.toInt())
        } catch (e: Exception) {
            statusView.text = "Invalid JSON"
            statusView.setTextColor(0xFFFF5252.toInt())
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
        view.findViewById<EditText>(R.id.et_xray_json).setText(c.xray.jsonConfig)
        view.findViewById<EditText>(R.id.et_v2dns_nameserver).setText(c.slowDns.nameserver)
        view.findViewById<EditText>(R.id.et_v2dns_pubkey).setText(c.slowDns.publicKey)
        view.findViewById<EditText>(R.id.et_hys_host).setText(c.hysteria.serverAddress)
        view.findViewById<EditText>(R.id.et_hys_port).setText(c.hysteria.serverPort.toString())
        view.findViewById<EditText>(R.id.et_hys_auth).setText(c.hysteria.authPassword)
        view.findViewById<EditText>(R.id.et_hys_upload).setText(c.hysteria.uploadMbps.toString())
        view.findViewById<EditText>(R.id.et_hys_download).setText(c.hysteria.downloadMbps.toString())
        view.findViewById<EditText>(R.id.et_hys_obfs).setText(c.hysteria.obfsPassword)
        view.findViewById<EditText>(R.id.et_hys_sni).setText(c.hysteria.sni)
        view.findViewById<EditText>(R.id.et_zivpn_host).setText(c.zivpn.host)
        view.findViewById<EditText>(R.id.et_zivpn_password).setText(c.zivpn.password)
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
            jsonConfig = view.findViewById<EditText>(R.id.et_xray_json).text.toString()
        )
        val hys = c.hysteria.copy(
            serverAddress = view.findViewById<EditText>(R.id.et_hys_host).text.toString(),
            serverPort = view.findViewById<EditText>(R.id.et_hys_port).text.toString().toIntOrNull() ?: 443,
            authPassword = view.findViewById<EditText>(R.id.et_hys_auth).text.toString(),
            uploadMbps = view.findViewById<EditText>(R.id.et_hys_upload).text.toString().toIntOrNull() ?: 10,
            downloadMbps = view.findViewById<EditText>(R.id.et_hys_download).text.toString().toIntOrNull() ?: 50,
            obfsPassword = view.findViewById<EditText>(R.id.et_hys_obfs).text.toString(),
            sni = view.findViewById<EditText>(R.id.et_hys_sni).text.toString()
        )
        val zivpn = c.zivpn.copy(
            host = view.findViewById<EditText>(R.id.et_zivpn_host).text.toString(),
            password = view.findViewById<EditText>(R.id.et_zivpn_password).text.toString()
        )
        viewModel.saveConfig(c.copy(
            sshCredentials = ssh, slowDns = dns, httpProxy = http,
            sshWebSocket = ws, sshSsl = ssl, xray = xray, hysteria = hys, zivpn = zivpn
        ))
        Toast.makeText(requireContext(), "Config saved!", Toast.LENGTH_SHORT).show()
    }
}