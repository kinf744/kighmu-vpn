package com.kighmu.vpn.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.kighmu.vpn.R
import com.kighmu.vpn.profiles.ProfileRepository
import com.kighmu.vpn.profiles.SlowDnsProfileAdapter
import com.kighmu.vpn.ui.MainViewModel

class ConfigFragment : Fragment() {

    private val viewModel: MainViewModel by activityViewModels()
    private var slowDnsProfileAdapter: SlowDnsProfileAdapter? = null
    private var parsedJsonFromLink: String = ""
    private var parsedJsonFromV2dnsLink: String = ""
    private var currentTab = 0
    private lateinit var profileRepo: ProfileRepository

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_config, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        profileRepo = ProfileRepository(requireContext())

        val tabs = listOf(
            view.findViewById<Button>(R.id.tab_slowdns),
            view.findViewById<Button>(R.id.tab_http),
            view.findViewById<Button>(R.id.tab_ws),
            view.findViewById<Button>(R.id.tab_ssl),
            view.findViewById<Button>(R.id.tab_xray),
            view.findViewById<Button>(R.id.tab_v2dns),
            view.findViewById<Button>(R.id.tab_hysteria),
        )

        val panels = listOf(
            view.findViewById<LinearLayout>(R.id.panel_slowdns),
            view.findViewById<LinearLayout>(R.id.panel_http),
            view.findViewById<LinearLayout>(R.id.panel_ws),
            view.findViewById<LinearLayout>(R.id.panel_ssl),
            view.findViewById<LinearLayout>(R.id.panel_xray),
            view.findViewById<LinearLayout>(R.id.panel_v2dns),
            view.findViewById<LinearLayout>(R.id.panel_hysteria),
        )

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
        }

        tabs.forEachIndexed { index, btn -> btn.setOnClickListener { selectTab(index) } }

        // RadioGroup Xray
        val rgMode = view.findViewById<android.widget.RadioGroup>(R.id.rg_xray_mode)
        val panelLink = view.findViewById<android.view.View>(R.id.panel_xray_link)
        val panelJson = view.findViewById<android.view.View>(R.id.panel_xray_json)
        val tvWarning = view.findViewById<android.widget.TextView>(R.id.tv_xray_mode_warning)

        rgMode.setOnCheckedChangeListener { _, id ->
            tvWarning.visibility = View.GONE
            when (id) {
                R.id.rb_xray_link -> { panelLink.visibility = View.VISIBLE; panelJson.visibility = View.GONE }
                R.id.rb_xray_json -> { panelJson.visibility = View.VISIBLE; panelLink.visibility = View.GONE }
            }
        }

        // Parse Xray link
        view.findViewById<Button>(R.id.btn_parse_link).setOnClickListener {
            val link = view.findViewById<EditText>(R.id.et_xray_link).text.toString()
            val statusView = view.findViewById<TextView>(R.id.tv_link_status)
            val json = parseLinkToJson(link)
            if (json != null) {
                view.findViewById<EditText>(R.id.et_xray_json).setText(json)
                parsedJsonFromLink = json
                statusView.text = "✓ Config générée avec succès"
                statusView.setTextColor(0xFF00C853.toInt())
                saveConfig(view)
            } else {
                statusView.text = "❌ Format invalide"
                statusView.setTextColor(0xFFFF5252.toInt())
            }
        }

        // JSON buttons
        view.findViewById<Button>(R.id.btn_paste_json).setOnClickListener {
            val cm = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val text = cm.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
            view.findViewById<EditText>(R.id.et_xray_json).setText(text)
        }
        view.findViewById<Button>(R.id.btn_copy_json).setOnClickListener {
            val cm = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val text = view.findViewById<EditText>(R.id.et_xray_json).text.toString()
            cm.setPrimaryClip(android.content.ClipData.newPlainText("json", text))
            Toast.makeText(requireContext(), "Copié!", Toast.LENGTH_SHORT).show()
        }
        view.findViewById<Button>(R.id.btn_format_json).setOnClickListener {
            try {
                val text = view.findViewById<EditText>(R.id.et_xray_json).text.toString()
                val formatted = org.json.JSONObject(text).toString(2)
                view.findViewById<EditText>(R.id.et_xray_json).setText(formatted)
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "JSON invalide", Toast.LENGTH_SHORT).show()
            }
        }
        view.findViewById<Button>(R.id.btn_select_all_json).setOnClickListener {
            view.findViewById<EditText>(R.id.et_xray_json).selectAll()
        }

        // Parse V2DNS link
        view.findViewById<Button>(R.id.btn_parse_v2dns_link).setOnClickListener {
            val link = view.findViewById<EditText>(R.id.et_v2dns_link).text.toString()
            val status = view.findViewById<TextView>(R.id.tv_v2dns_link_status)
            try {
                val json = parseLinkToJson(link)
                if (json != null) {
                    parsedJsonFromV2dnsLink = json
                    status.text = "✓ Config générée avec succès"
                    status.setTextColor(0xFF00C853.toInt())
                    saveConfig(view)
                } else {
                    status.text = "❌ Format invalide"
                    status.setTextColor(0xFFFF5252.toInt())
                }
            } catch (e: Exception) {
                status.text = "❌ Erreur: ${e.message}"
                status.setTextColor(0xFFFF5252.toInt())
            }
        }

        // SlowDNS profiles
        val rv = view.findViewById<RecyclerView>(R.id.rv_dns_profiles)
        rv.layoutManager = LinearLayoutManager(requireContext())
        val profiles = profileRepo.getAll().toMutableList()
        slowDnsProfileAdapter = SlowDnsProfileAdapter(profiles, requireContext())
        rv.adapter = slowDnsProfileAdapter

        view.findViewById<Button>(R.id.btn_add_dns_profile).setOnClickListener {
            slowDnsProfileAdapter?.addProfile()
        }

        view.findViewById<Button>(R.id.btn_save_config).setOnClickListener { saveConfig(view) }

        viewModel.config.observe(viewLifecycleOwner) { loadConfig(view, it) }

        selectTab(currentTab)
    }

    private fun loadConfig(view: View, c: com.kighmu.vpn.models.KighmuConfig) {
        // HTTP
        view.findViewById<EditText>(R.id.et_http_ssh_host).setText(c.httpProxy.sshHost)
        view.findViewById<EditText>(R.id.et_http_ssh_port).setText(c.httpProxy.sshPort.toString())
        view.findViewById<EditText>(R.id.et_http_ssh_user).setText(c.httpProxy.sshUser)
        view.findViewById<EditText>(R.id.et_http_ssh_pass).setText(c.httpProxy.sshPass)
        view.findViewById<EditText>(R.id.et_proxy_host).setText(c.httpProxy.proxyHost)
        view.findViewById<EditText>(R.id.et_proxy_port).setText(c.httpProxy.proxyPort.toString())
        view.findViewById<EditText>(R.id.et_payload).setText(c.httpProxy.customPayload)
        // WS
        view.findViewById<EditText>(R.id.et_ws_ssh_host).setText(c.sshWebSocket.sshHost)
        view.findViewById<EditText>(R.id.et_ws_ssh_port).setText(c.sshWebSocket.sshPort.toString())
        view.findViewById<EditText>(R.id.et_ws_ssh_user).setText(c.sshWebSocket.sshUser)
        view.findViewById<EditText>(R.id.et_ws_ssh_pass).setText(c.sshWebSocket.sshPass)
        view.findViewById<EditText>(R.id.et_ws_host).setText(c.sshWebSocket.wsHost)
        view.findViewById<EditText>(R.id.et_ws_port).setText(c.sshWebSocket.wsPort.toString())
        view.findViewById<EditText>(R.id.et_ws_path).setText(c.sshWebSocket.wsPath)
        // SSL
        view.findViewById<EditText>(R.id.et_ssl_ssh_host).setText(c.sshSsl.sshHost)
        view.findViewById<EditText>(R.id.et_ssl_ssh_port).setText(c.sshSsl.sshPort.toString())
        view.findViewById<EditText>(R.id.et_ssl_ssh_user).setText(c.sshSsl.sshUser)
        view.findViewById<EditText>(R.id.et_ssl_ssh_pass).setText(c.sshSsl.sshPass)
        view.findViewById<EditText>(R.id.et_sni).setText(c.sshSsl.sni)
        // Xray
        view.findViewById<EditText>(R.id.et_xray_json).setText(c.xray.jsonConfig)
        val savedMode = c.xray.inputMode
        val rgRestore = view.findViewById<android.widget.RadioGroup>(R.id.rg_xray_mode)
        val pLink = view.findViewById<android.view.View>(R.id.panel_xray_link)
        val pJson = view.findViewById<android.view.View>(R.id.panel_xray_json)
        when (savedMode) {
            "link" -> { rgRestore.check(R.id.rb_xray_link); pLink.visibility = View.VISIBLE; pJson.visibility = View.GONE }
            "json" -> { rgRestore.check(R.id.rb_xray_json); pJson.visibility = View.VISIBLE; pLink.visibility = View.GONE }
        }
        // V2DNS
        view.findViewById<EditText>(R.id.et_v2dns_dns_server).setText(c.slowDns.dnsServer)
        view.findViewById<EditText>(R.id.et_v2dns_dns_port).setText(c.slowDns.dnsPort.toString())
        view.findViewById<EditText>(R.id.et_v2dns_nameserver).setText(c.slowDns.nameserver)
        view.findViewById<EditText>(R.id.et_v2dns_pubkey).setText(c.slowDns.publicKey)
        // Hysteria
        view.findViewById<EditText>(R.id.et_hys_host).setText(c.hysteria.serverAddress)
        view.findViewById<EditText>(R.id.et_hys_port).setText(c.hysteria.serverPort.toString())
        view.findViewById<EditText>(R.id.et_hys_auth).setText(c.hysteria.authPassword)
        view.findViewById<EditText>(R.id.et_hys_upload).setText(c.hysteria.uploadMbps.toString())
        view.findViewById<EditText>(R.id.et_hys_download).setText(c.hysteria.downloadMbps.toString())
        view.findViewById<EditText>(R.id.et_hys_obfs).setText(c.hysteria.obfsPassword)
        view.findViewById<EditText>(R.id.et_hys_sni).setText(c.hysteria.sni)
        // Tab
        val tabIndex = when (c.tunnelMode) {
            com.kighmu.vpn.models.TunnelMode.SLOW_DNS -> 0
            com.kighmu.vpn.models.TunnelMode.HTTP_PROXY -> 1
            com.kighmu.vpn.models.TunnelMode.SSH_WEBSOCKET -> 2
            com.kighmu.vpn.models.TunnelMode.SSH_SSL_TLS -> 3
            com.kighmu.vpn.models.TunnelMode.V2RAY_XRAY -> 4
            com.kighmu.vpn.models.TunnelMode.V2RAY_SLOWDNS -> 5
            com.kighmu.vpn.models.TunnelMode.HYSTERIA_UDP -> 6
            else -> 0
        }
        currentTab = tabIndex
    }

    private fun saveConfig(view: View) {
        val c = viewModel.config.value ?: return

        val http = c.httpProxy.copy(
            sshHost = view.findViewById<EditText>(R.id.et_http_ssh_host).text.toString(),
            sshPort = view.findViewById<EditText>(R.id.et_http_ssh_port).text.toString().toIntOrNull() ?: 22,
            sshUser = view.findViewById<EditText>(R.id.et_http_ssh_user).text.toString(),
            sshPass = view.findViewById<EditText>(R.id.et_http_ssh_pass).text.toString(),
            proxyHost = view.findViewById<EditText>(R.id.et_proxy_host).text.toString(),
            proxyPort = view.findViewById<EditText>(R.id.et_proxy_port).text.toString().toIntOrNull() ?: 8080,
            customPayload = view.findViewById<EditText>(R.id.et_payload).text.toString()
        )

        val ws = c.sshWebSocket.copy(
            sshHost = view.findViewById<EditText>(R.id.et_ws_ssh_host).text.toString(),
            sshPort = view.findViewById<EditText>(R.id.et_ws_ssh_port).text.toString().toIntOrNull() ?: 22,
            sshUser = view.findViewById<EditText>(R.id.et_ws_ssh_user).text.toString(),
            sshPass = view.findViewById<EditText>(R.id.et_ws_ssh_pass).text.toString(),
            wsHost = view.findViewById<EditText>(R.id.et_ws_host).text.toString(),
            wsPort = view.findViewById<EditText>(R.id.et_ws_port).text.toString().toIntOrNull() ?: 80,
            wsPath = view.findViewById<EditText>(R.id.et_ws_path).text.toString()
        )

        val ssl = c.sshSsl.copy(
            sshHost = view.findViewById<EditText>(R.id.et_ssl_ssh_host).text.toString(),
            sshPort = view.findViewById<EditText>(R.id.et_ssl_ssh_port).text.toString().toIntOrNull() ?: 22,
            sshUser = view.findViewById<EditText>(R.id.et_ssl_ssh_user).text.toString(),
            sshPass = view.findViewById<EditText>(R.id.et_ssl_ssh_pass).text.toString(),
            sni = view.findViewById<EditText>(R.id.et_sni).text.toString()
        )

        val dns = c.slowDns.copy(
            dnsServer = view.findViewById<EditText>(R.id.et_v2dns_dns_server).text.toString().trim(),
            dnsPort = view.findViewById<EditText>(R.id.et_v2dns_dns_port).text.toString().toIntOrNull() ?: 53,
            nameserver = view.findViewById<EditText>(R.id.et_v2dns_nameserver).text.toString().trim(),
            publicKey = view.findViewById<EditText>(R.id.et_v2dns_pubkey).text.toString().trim()
        )

        val rgXray = view.findViewById<android.widget.RadioGroup>(R.id.rg_xray_mode)
        val tvXrayWarning = view.findViewById<android.widget.TextView>(R.id.tv_xray_mode_warning)
        if (currentTab == 4 && rgXray.checkedRadioButtonId == -1) {
            tvXrayWarning.visibility = View.VISIBLE
            return
        }

        val xrayJson = if (currentTab == 5) {
            when {
                parsedJsonFromV2dnsLink.isNotBlank() -> parsedJsonFromV2dnsLink
                c.xray.jsonConfig.isNotBlank() && c.xray.jsonConfig != com.kighmu.vpn.models.XrayConfig.defaultXrayConfig -> c.xray.jsonConfig
                else -> ""
            }
        } else when (rgXray.checkedRadioButtonId) {
            R.id.rb_xray_link -> if (parsedJsonFromLink.isNotBlank()) parsedJsonFromLink else view.findViewById<EditText>(R.id.et_xray_json).text.toString()
            else -> view.findViewById<EditText>(R.id.et_xray_json).text.toString()
        }

        val xray = c.xray.copy(
            jsonConfig = xrayJson,
            inputMode = when (rgXray.checkedRadioButtonId) {
                R.id.rb_xray_link -> "link"
                R.id.rb_xray_json -> "json"
                else -> c.xray.inputMode
            }
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

        val newTunnelMode = when (currentTab) {
            0 -> com.kighmu.vpn.models.TunnelMode.SLOW_DNS
            1 -> com.kighmu.vpn.models.TunnelMode.HTTP_PROXY
            2 -> com.kighmu.vpn.models.TunnelMode.SSH_WEBSOCKET
            3 -> com.kighmu.vpn.models.TunnelMode.SSH_SSL_TLS
            4 -> com.kighmu.vpn.models.TunnelMode.V2RAY_XRAY
            5 -> com.kighmu.vpn.models.TunnelMode.V2RAY_SLOWDNS
            6 -> com.kighmu.vpn.models.TunnelMode.HYSTERIA_UDP
            else -> c.tunnelMode
        }

        viewModel.saveConfig(c.copy(
            tunnelMode = newTunnelMode,
            httpProxy = http,
            sshWebSocket = ws,
            sshSsl = ssl,
            slowDns = dns,
            xray = xray,
            hysteria = hys,
            slowDnsProfiles = slowDnsProfileAdapter?.getProfiles() ?: mutableListOf()
        ))
        Toast.makeText(requireContext(), "Config saved!", Toast.LENGTH_SHORT).show()
    }

    private fun parseLinkToJson(link: String): String? {
        return when {
            link.startsWith("vmess://") -> parseVmess(link)
            link.startsWith("vless://") -> parseVless(link)
            link.startsWith("trojan://") -> parseTrojan(link)
            else -> null
        }
    }

    private fun parseVless(link: String): String {
        val uri = java.net.URI(link)
        val uuid = uri.userInfo ?: ""
        val host = uri.host ?: ""
        val port = uri.port.takeIf { it > 0 } ?: 443
        val params = uri.query?.split("&")?.associate { it.split("=").let { p -> p[0] to (p.getOrNull(1) ?: "") } } ?: emptyMap()
        val type = params["type"] ?: "tcp"
        val security = params["security"] ?: "none"
        val sni = params["sni"] ?: params["host"] ?: host
        val wsPath = params["path"] ?: "/"
        val wsHost = params["host"] ?: host
        val fp = params["fp"] ?: ""
        val pbk = params["pbk"] ?: ""
        val sid = params["sid"] ?: ""
        val flow = params["flow"] ?: ""

        val streamSettings = when (type) {
            "ws" -> when (security) {
                "tls" -> """"streamSettings":{"network":"ws","security":"tls","tlsSettings":{"serverName":"$sni","allowInsecure":false${if (fp.isNotEmpty()) ""","fingerprint":"$fp"""" else ""}},"wsSettings":{"path":"$wsPath","headers":{"Host":"$wsHost"}}}"""
                else -> """"streamSettings":{"network":"ws","security":"none","wsSettings":{"path":"$wsPath","headers":{"Host":"$wsHost"}}}"""
            }
            "grpc" -> """"streamSettings":{"network":"grpc","security":"$security","grpcSettings":{"serviceName":"${params["serviceName"] ?: ""}"}}"""
            "reality" -> """"streamSettings":{"network":"tcp","security":"reality","realitySettings":{"serverName":"$sni","fingerprint":"$fp","publicKey":"$pbk","shortId":"$sid"}}"""
            else -> when (security) {
                "tls" -> """"streamSettings":{"network":"tcp","security":"tls","tlsSettings":{"serverName":"$sni","allowInsecure":false}}"""
                else -> """"streamSettings":{"network":"tcp","security":"none"}"""
            }
        }

        return """{
  "log":{"loglevel":"warning"},
  "inbounds":[{"port":10808,"protocol":"socks","settings":{"udp":true}}],
  "outbounds":[{
    "protocol":"vless",
    "settings":{"vnext":[{"address":"$host","port":$port,"users":[{"id":"$uuid","encryption":"none"${if (flow.isNotEmpty()) ""","flow":"$flow"""" else ""}}]}]},
    $streamSettings
  },{"protocol":"freedom","tag":"direct"}],
  "routing":{"rules":[]}
}"""
    }

    private fun parseVmess(link: String): String {
        val b64 = link.removePrefix("vmess://")
        val json = String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT))
        val obj = org.json.JSONObject(json)
        val host = obj.optString("add", "")
        val port = obj.optInt("port", 443)
        val uuid = obj.optString("id", "")
        val alterId = obj.optInt("aid", 0)
        val net = obj.optString("net", "tcp")
        val tls = obj.optString("tls", "")
        val sni = obj.optString("sni", host)
        val wsPath = obj.optString("path", "/")
        val wsHost = obj.optString("host", host)

        val streamSettings = when (net) {
            "ws" -> if (tls == "tls") """"streamSettings":{"network":"ws","security":"tls","tlsSettings":{"serverName":"$sni","allowInsecure":false},"wsSettings":{"path":"$wsPath","headers":{"Host":"$wsHost"}}}"""
                    else """"streamSettings":{"network":"ws","security":"none","wsSettings":{"path":"$wsPath","headers":{"Host":"$wsHost"}}}"""
            else -> if (tls == "tls") """"streamSettings":{"network":"tcp","security":"tls","tlsSettings":{"serverName":"$sni","allowInsecure":false}}"""
                    else """"streamSettings":{"network":"tcp","security":"none"}"""
        }

        return """{
  "log":{"loglevel":"warning"},
  "inbounds":[{"port":10808,"protocol":"socks","settings":{"udp":true}}],
  "outbounds":[{
    "protocol":"vmess",
    "settings":{"vnext":[{"address":"$host","port":$port,"users":[{"id":"$uuid","alterId":$alterId,"security":"auto"}]}]},
    $streamSettings
  },{"protocol":"freedom","tag":"direct"}],
  "routing":{"rules":[]}
}"""
    }

    private fun parseTrojan(link: String): String {
        val uri = java.net.URI(link)
        val password = uri.userInfo ?: ""
        val host = uri.host ?: ""
        val port = uri.port.takeIf { it > 0 } ?: 443
        val params = uri.query?.split("&")?.associate { it.split("=").let { p -> p[0] to (p.getOrNull(1) ?: "") } } ?: emptyMap()
        val sni = params["sni"] ?: host

        return """{
  "log":{"loglevel":"warning"},
  "inbounds":[{"port":10808,"protocol":"socks","settings":{"udp":true}}],
  "outbounds":[{
    "protocol":"trojan",
    "settings":{"servers":[{"address":"$host","port":$port,"password":"$password"}]},
    "streamSettings":{"network":"tcp","security":"tls","tlsSettings":{"serverName":"$sni","allowInsecure":false}}
  },{"protocol":"freedom","tag":"direct"}],
  "routing":{"rules":[]}
}"""
    }
}
