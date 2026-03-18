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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import com.kighmu.vpn.ui.adapters.SlowDnsProfileAdapter
import com.kighmu.vpn.profiles.SlowDnsProfile
import com.kighmu.vpn.profiles.ProfileRepository
import com.kighmu.vpn.ui.MainViewModel

class ConfigFragment : Fragment() {

    private val viewModel: MainViewModel by activityViewModels()
    private var slowDnsProfileAdapter: SlowDnsProfileAdapter? = null
    private var parsedJsonFromLink: String = ""
    private val dnsProfiles: MutableList<com.kighmu.vpn.profiles.SlowDnsProfile> = mutableListOf()
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
            val etJson = view.findViewById<EditText>(R.id.et_xray_json)
            // Restaurer le JSON du nouveau tab SEULEMENT depuis les variables dédiées
            currentTab = index
            if (index == 4) {
                etJson.setText(parsedJsonFromLink)
            } else if (index == 5) {
                etJson.setText(parsedJsonFromV2dnsLink)
            }
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
                R.id.rb_xray_link -> {
                    panelLink.visibility = View.VISIBLE
                    panelJson.visibility = View.GONE
                    // Vider le champ JSON quand on passe en mode Lien
                    view.findViewById<android.widget.EditText>(R.id.et_xray_json).setText("")
                    parsedJsonFromLink = ""
                }
                R.id.rb_xray_json -> {
                    panelJson.visibility = View.VISIBLE
                    panelLink.visibility = View.GONE
                    // Vider le champ Lien quand on passe en mode JSON
                    view.findViewById<android.widget.EditText>(R.id.et_xray_link).setText("")
                }
            }
        }

        // Parse Xray link
        view.findViewById<Button>(R.id.btn_parse_link).setOnClickListener {
            val link = view.findViewById<EditText>(R.id.et_xray_link).text.toString()
            val statusView = view.findViewById<TextView>(R.id.tv_link_status)
            val json = parseLinkToJson(link)
            if (json != null) {
                // Stocker le lien parsé sans toucher le champ JSON
                parsedJsonFromLink = json
                statusView.text = "✓ Lien valide - config prête"
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
        dnsProfiles.clear(); dnsProfiles.addAll(profileRepo.getAll())
        slowDnsProfileAdapter = SlowDnsProfileAdapter(
            dnsProfiles,
            onSelectionChanged = { _, _ -> },
            onEdit = { p -> showAddProfileDialog(p) },
            onDelete = { p -> dnsProfiles.remove(p); slowDnsProfileAdapter?.notifyDataSetChanged(); saveConfig(view) },
            onClone = { p -> dnsProfiles.add(p.copy(id = java.util.UUID.randomUUID().toString())); slowDnsProfileAdapter?.notifyDataSetChanged(); saveConfig(view) }
        )
        rv.adapter = slowDnsProfileAdapter

        view.findViewById<Button>(R.id.btn_add_dns_profile).setOnClickListener {
            showAddProfileDialog()
        }

        view.findViewById<Button>(R.id.btn_save_config).setOnClickListener { saveConfig(view) }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.config.collect { c -> loadConfig(view, c) }
        }

        selectTab(currentTab)
    }

    private fun loadConfig(view: View, c: com.kighmu.vpn.models.KighmuConfig) {
        // Verrouiller config si lockAllConfig = true
        val isLocked = c.exportConfig?.lockAllConfig == true
        applyConfigLock(view, isLocked)
        // Recharger profils SlowDNS
        val savedProfiles = profileRepo.getAll()
        if (savedProfiles.isNotEmpty() || dnsProfiles.isEmpty()) {
            dnsProfiles.clear()
            dnsProfiles.addAll(savedProfiles)
            slowDnsProfileAdapter?.notifyDataSetChanged()
        }
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
        // Xray - initialiser les variables mémoire depuis la config sauvegardée
        if (parsedJsonFromLink.isBlank()) parsedJsonFromLink = c.xray.xrayLinkJson
        if (parsedJsonFromV2dnsLink.isBlank()) parsedJsonFromV2dnsLink = c.xray.v2dnsJsonConfig
        // Charger la config JSON selon le tab et le mode
        if (currentTab == 5) {
            view.findViewById<EditText>(R.id.et_xray_json).setText(c.xray.v2dnsJsonConfig)
        } else if (c.xray.inputMode == "json") {
            // Mode JSON : charger seulement jsonConfig dans et_xray_json
            view.findViewById<EditText>(R.id.et_xray_json).setText(c.xray.jsonConfig)
        } else {
            // Mode Lien : et_xray_json reste vide, et_xray_link contient le lien
            view.findViewById<EditText>(R.id.et_xray_json).setText("")
            view.findViewById<EditText>(R.id.et_xray_link).setText(c.xray.xrayLink)
        }
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
        view.findViewById<EditText>(R.id.et_hys_auth).setText(c.hysteria.authPassword)
        view.findViewById<EditText>(R.id.et_hys_upload).setText(c.hysteria.uploadMbps.toString())
        view.findViewById<EditText>(R.id.et_hys_download).setText(c.hysteria.downloadMbps.toString())
        view.findViewById<EditText>(R.id.et_hys_obfs).setText(c.hysteria.obfsPassword)
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
                c.xray.v2dnsJsonConfig.isNotBlank() -> c.xray.v2dnsJsonConfig
                else -> ""
            }
        } else when (rgXray.checkedRadioButtonId) {
            R.id.rb_xray_link -> if (parsedJsonFromLink.isNotBlank()) parsedJsonFromLink else view.findViewById<EditText>(R.id.et_xray_json).text.toString()
            else -> view.findViewById<EditText>(R.id.et_xray_json).text.toString()
        }

        val xray = if (currentTab == 5) {
            c.xray.copy(v2dnsJsonConfig = xrayJson)
        } else {
            val mode = when (rgXray.checkedRadioButtonId) {
                R.id.rb_xray_link -> "link"
                R.id.rb_xray_json -> "json"
                else -> c.xray.inputMode
            }
            if (mode == "link") {
                // Mode lien : sauvegarder lien brut + JSON parsé séparément
                // jsonConfig reste INTACT - pas de contamination
                val rawLink = view.findViewById<android.widget.EditText>(R.id.et_xray_link).text.toString()
                c.xray.copy(
                    xrayLink = rawLink,
                    xrayLinkJson = parsedJsonFromLink,
                    inputMode = "link"
                )
            } else {
                // Mode JSON : sauvegarder seulement dans jsonConfig
                c.xray.copy(
                    jsonConfig = view.findViewById<android.widget.EditText>(R.id.et_xray_json).text.toString(),
                    inputMode = "json"
                )
            }
        }

        val hys = c.hysteria.copy(
            serverAddress = view.findViewById<EditText>(R.id.et_hys_host).text.toString(),
            authPassword = view.findViewById<EditText>(R.id.et_hys_auth).text.toString(),
            uploadMbps = view.findViewById<EditText>(R.id.et_hys_upload).text.toString().toIntOrNull() ?: 10,
            downloadMbps = view.findViewById<EditText>(R.id.et_hys_download).text.toString().toIntOrNull() ?: 50,
            obfsPassword = view.findViewById<EditText>(R.id.et_hys_obfs).text.toString(),
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

        // Persister les profils SlowDNS
        profileRepo.save(dnsProfiles)
        viewModel.saveConfig(c.copy(
            tunnelMode = newTunnelMode,
            httpProxy = http,
            sshWebSocket = ws,
            sshSsl = ssl,
            slowDns = dns,
            xray = xray,
            hysteria = hys,
            slowDnsProfiles = dnsProfiles.map { p -> com.kighmu.vpn.models.SlowDnsConfig(dnsServer = p.dnsServer, nameserver = p.nameserver, publicKey = p.publicKey, sshHost = p.sshHost, sshPort = p.sshPort, sshUser = p.sshUser, sshPass = p.sshPass) }.toMutableList()
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

    private fun applyConfigLock(view: View, locked: Boolean) {
        if (!locked) return
        // Désactiver seulement les EditText - pas les boutons de navigation
        fun lockView(v: android.view.View) {
            if (v is android.widget.EditText) {
                v.isEnabled = false
                v.setText("••••••••")
                v.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            if (v is android.view.ViewGroup) {
                for (i in 0 until v.childCount) lockView(v.getChildAt(i))
            }
        }
        val content = view.findViewById<android.view.ViewGroup>(R.id.config_content)
        lockView(content)
        // Garder le bouton save visible mais désactivé
        val btnSave = view.findViewById<android.widget.Button>(R.id.btn_save_config)
        btnSave.isEnabled = !locked
        if (locked) {
            btnSave.text = "🔒 CONFIG VERROUILLÉE"
            btnSave.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF888888.toInt())
        }
    }

    private fun showAddProfileDialog(existing: com.kighmu.vpn.profiles.SlowDnsProfile? = null) {
        val ctx = requireContext()
        val layout = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
        }
        fun et(hint: String, value: String = "", pwd: Boolean = false) = android.widget.EditText(ctx).apply {
            this.hint = hint; setText(value)
            if (pwd) inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            layoutParams = android.widget.LinearLayout.LayoutParams(-1, -2).apply { topMargin = 16 }
            layout.addView(this)
        }
        val etName = et("Nom du profil", existing?.profileName ?: "")
        val etHost = et("Host / IP", existing?.sshHost ?: "")
        val etPort = et("Port SSH (22)", existing?.sshPort?.toString() ?: "22")
        val etUser = et("Username", existing?.sshUser ?: "")
        val etPass = et("Password", existing?.sshPass ?: "", pwd = true)
        val etNs   = et("Nameserver (NS)", existing?.nameserver ?: "")
        val etKey  = et("Clé publique", existing?.publicKey ?: "")
        val etDns  = et("DNS Server (8.8.8.8)", existing?.dnsServer ?: "8.8.8.8")

        android.app.AlertDialog.Builder(ctx)
            .setTitle(if (existing == null) "Nouveau profil SlowDNS" else "Modifier profil")
            .setView(layout)
            .setPositiveButton("Enregistrer") { _, _ ->
                val profile = (existing ?: com.kighmu.vpn.profiles.SlowDnsProfile()).apply {
                    profileName = etName.text.toString()
                    sshHost = etHost.text.toString()
                    sshPort = etPort.text.toString().toIntOrNull() ?: 22
                    sshUser = etUser.text.toString()
                    sshPass = etPass.text.toString()
                    nameserver = etNs.text.toString()
                    publicKey = etKey.text.toString()
                    dnsServer = etDns.text.toString()
                }
                if (existing == null) {
                    dnsProfiles.add(profile)
                } else {
                    val idx = dnsProfiles.indexOf(existing)
                    if (idx >= 0) dnsProfiles[idx] = profile
                }
                slowDnsProfileAdapter?.notifyDataSetChanged()
                view?.let { saveConfig(it) }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

}
