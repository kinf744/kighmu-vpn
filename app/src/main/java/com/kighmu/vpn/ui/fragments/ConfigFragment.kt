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
import android.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.kighmu.vpn.ui.adapters.DnsProfileAdapter
import com.kighmu.vpn.ui.adapters.SlowDnsProfileAdapter
import com.kighmu.vpn.ui.dialogs.ProfileEditDialog
import com.kighmu.vpn.profiles.ProfileRepository
import com.kighmu.vpn.profiles.SlowDnsProfile

class ConfigFragment : Fragment() {
    private var dnsProfileAdapter: DnsProfileAdapter? = null
    private var slowDnsProfileAdapter: SlowDnsProfileAdapter? = null
    private var parsedJsonFromLink: String = ""
    private var parsedJsonFromV2dnsLink: String = ""
    private lateinit var profileRepo: ProfileRepository
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
        )

        // SSH not needed for SlowDNS(0 - utilise profils), xray(4), v2dns(5), hysteria(6)
        val noSshTabs = setOf(0, 4, 5, 6, 7, 8)

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

        // RadioGroup : afficher/cacher panels Link ou JSON
        val rgMode = view.findViewById<android.widget.RadioGroup>(R.id.rg_xray_mode)
        val panelLink = view.findViewById<android.view.View>(R.id.panel_xray_link)
        val panelJson = view.findViewById<android.view.View>(R.id.panel_xray_json)
        val tvWarning = view.findViewById<android.widget.TextView>(R.id.tv_xray_mode_warning)

        rgMode.setOnCheckedChangeListener { _, checkedId ->
            tvWarning.visibility = android.view.View.GONE
            when (checkedId) {
                R.id.rb_xray_link -> {
                    panelLink.visibility = android.view.View.VISIBLE
                    panelJson.visibility = android.view.View.GONE
                }
                R.id.rb_xray_json -> {
                    panelLink.visibility = android.view.View.GONE
                    panelJson.visibility = android.view.View.VISIBLE
                }
            }
        }

        view.findViewById<Button>(R.id.btn_parse_link).setOnClickListener {
            val link = view.findViewById<EditText>(R.id.et_xray_link).text.toString()
            val status = view.findViewById<TextView>(R.id.tv_link_status)
            validateLink(link, status)
        }

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
                    status.text = "❌ Format invalide. Utilisez vmess:// vless:// trojan://"
                    status.setTextColor(0xFFFF5252.toInt())
                }
            } catch (e: Exception) {
                status.text = "❌ Erreur: ${e.message}"
                status.setTextColor(0xFFFF5252.toInt())
            }
        }

        view.findViewById<Button>(R.id.btn_save_config).setOnClickListener {
            saveConfig(view)
        }
    }

    private fun validateLink(link: String, statusView: TextView) {
        if (link.isBlank()) return
        try {
            val json = parseLinkToJson(link)
            if (json != null) {
                view?.findViewById<android.widget.EditText>(R.id.et_xray_json)?.setText(json)
                parsedJsonFromLink = json
                statusView.text = "✓ Config générée avec succès"
                statusView.setTextColor(0xFF00C853.toInt())
            } else {
                statusView.text = "❌ Format invalide. Utilisez vmess:// vless:// trojan://"
                statusView.setTextColor(0xFFFF5252.toInt())
            }
        } catch (e: Exception) {
            statusView.text = "❌ Erreur: ${e.message}"
            statusView.setTextColor(0xFFFF5252.toInt())
        }
    }

    private fun parseLinkToJson(link: String): String? {
        return when {
            link.startsWith("vmess://") -> parseVmess(link)
            link.startsWith("vless://") -> parseVless(link)
            link.startsWith("trojan://") -> parseTrojan(link)
            else -> null
        }
    }

    private fun parseVmess(link: String): String {
        val b64 = link.removePrefix("vmess://")
        val decoded = String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT))
        val obj = org.json.JSONObject(decoded)
        val add = obj.optString("add", "")
        val port = obj.optInt("port", 443)
        val id = obj.optString("id", "")
        val net = obj.optString("net", "tcp")
        val path = obj.optString("path", "/")
        val host = obj.optString("host", "")
        val tls = obj.optString("tls", "") == "tls"
        val sni = obj.optString("sni", host)
        val security = if (tls) "tls" else "none"
        val wsSettings = if (net == "ws") ""","wsSettings":{"path":"$path","headers":{"Host":"$host"}}""" else ""
        val tlsSettings = if (tls) ""","tlsSettings":{"serverName":"$sni","allowInsecure":false}""" else ""
        return """{
  "log":{"loglevel":"warning"},
  "inbounds":[{"port":10808,"protocol":"socks","settings":{"udp":true}}],
  "outbounds":[{
    "protocol":"vmess",
    "settings":{"vnext":[{"address":"$add","port":$port,"users":[{"id":"$id","alterId":0,"security":"auto"}]}]},
    "streamSettings":{"network":"$net","security":"$security"$wsSettings$tlsSettings}
  },{"protocol":"freedom","tag":"direct"}],
  "routing":{"rules":[{"type":"field","ip":["geoip:private"],"outboundTag":"direct"}]}
}"""
    }

    private fun parseVless(link: String): String {
        val uri = android.net.Uri.parse(link)
        val uuid = uri.userInfo ?: ""
        val address = uri.host ?: ""
        val port = uri.port.takeIf { it > 0 } ?: 443
        val flow = uri.getQueryParameter("flow") ?: ""
        val net = uri.getQueryParameter("type") ?: "tcp"
        val security = uri.getQueryParameter("security") ?: "none"
        val sni = uri.getQueryParameter("sni") ?: address
        val path = uri.getQueryParameter("path") ?: "/"
        val host = uri.getQueryParameter("host") ?: address
        val fp = uri.getQueryParameter("fp") ?: "chrome"
        val pbk = uri.getQueryParameter("pbk") ?: ""
        val sid = uri.getQueryParameter("sid") ?: ""
        val tlsBlock = when (security) {
            "tls" -> ""","tlsSettings":{"serverName":"$sni","allowInsecure":false,"fingerprint":"$fp"}"""
            "reality" -> ""","realitySettings":{"serverName":"$sni","fingerprint":"$fp","publicKey":"$pbk","shortId":"$sid"}"""
            else -> ""
        }
        val wsBlock = if (net == "ws") ""","wsSettings":{"path":"$path","headers":{"Host":"$host"}}""" else ""
        val grpcBlock = if (net == "grpc") ""","grpcSettings":{"serviceName":"$path"}""" else ""
        val flowBlock = if (flow.isNotBlank()) """"flow":"$flow",""" else ""
        return """{
  "log":{"loglevel":"warning"},
  "inbounds":[{"port":10808,"protocol":"socks","settings":{"udp":true}}],
  "outbounds":[{
    "protocol":"vless",
    "settings":{"vnext":[{"address":"$address","port":$port,"users":[{${flowBlock}"id":"$uuid","encryption":"none"}]}]},
    "streamSettings":{"network":"$net","security":"$security"$tlsBlock$wsBlock$grpcBlock}
  },{"protocol":"freedom","tag":"direct"}],
  "routing":{"rules":[{"type":"field","ip":["geoip:private"],"outboundTag":"direct"}]}
}"""
    }

    private fun parseTrojan(link: String): String {
        val uri = android.net.Uri.parse(link)
        val password = uri.userInfo ?: ""
        val address = uri.host ?: ""
        val port = uri.port.takeIf { it > 0 } ?: 443
        val sni = uri.getQueryParameter("sni") ?: address
        val net = uri.getQueryParameter("type") ?: "tcp"
        val path = uri.getQueryParameter("path") ?: "/"
        val host = uri.getQueryParameter("host") ?: address
        val wsBlock = if (net == "ws") ""","wsSettings":{"path":"$path","headers":{"Host":"$host"}}""" else ""
        return """{
  "log":{"loglevel":"warning"},
  "inbounds":[{"port":10808,"protocol":"socks","settings":{"udp":true}}],
  "outbounds":[{
    "protocol":"trojan",
    "settings":{"servers":[{"address":"$address","port":$port,"password":"$password"}]},
    "streamSettings":{"network":"$net","security":"tls","tlsSettings":{"serverName":"$sni","allowInsecure":false}$wsBlock}
  },{"protocol":"freedom","tag":"direct"}],
  "routing":{"rules":[{"type":"field","ip":["geoip:private"],"outboundTag":"direct"}]}
}"""
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
        // Initialiser ProfileRepository et RecyclerView
        profileRepo = ProfileRepository(requireContext())
        val rv = view.findViewById<RecyclerView>(R.id.rv_dns_profiles)
        slowDnsProfileAdapter = SlowDnsProfileAdapter(
            profileRepo.getAll(),
            onSelectionChanged = { id, selected -> profileRepo.updateSelection(id, selected) },
            onEdit = { profile ->
                ProfileEditDialog.show(requireContext(), profile) { updated ->
                    profileRepo.update(updated)
                    slowDnsProfileAdapter?.setProfiles(profileRepo.getAll())
                }
            },
            onDelete = { profile ->
                profileRepo.delete(profile.id)
                slowDnsProfileAdapter?.setProfiles(profileRepo.getAll())
            },
            onClone = { profile ->
                profileRepo.clone(profile.id)
                slowDnsProfileAdapter?.setProfiles(profileRepo.getAll())
            }
        )
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = slowDnsProfileAdapter

        view.findViewById<Button>(R.id.btn_add_dns_profile).setOnClickListener {
            ProfileEditDialog.show(requireContext()) { newProfile ->
                profileRepo.add(newProfile)
                slowDnsProfileAdapter?.setProfiles(profileRepo.getAll())
            }
        }

        view.findViewById<EditText>(R.id.et_proxy_host).setText(c.httpProxy.proxyHost)
        view.findViewById<EditText>(R.id.et_proxy_port).setText(c.httpProxy.proxyPort.toString())
        view.findViewById<EditText>(R.id.et_payload).setText(c.httpProxy.customPayload)
        view.findViewById<EditText>(R.id.et_ws_host).setText(c.sshWebSocket.wsHost)
        view.findViewById<EditText>(R.id.et_ws_port).setText(c.sshWebSocket.wsPort.toString())
        view.findViewById<EditText>(R.id.et_ws_path).setText(c.sshWebSocket.wsPath)
        view.findViewById<EditText>(R.id.et_sni).setText(c.sshSsl.sni)
        view.findViewById<EditText>(R.id.et_xray_json).setText(c.xray.jsonConfig)
        // Restaurer le mode radio sélectionné
        val savedMode = c.xray.inputMode
        val rgRestore = view.findViewById<android.widget.RadioGroup>(R.id.rg_xray_mode)
        val pLink = view.findViewById<android.view.View>(R.id.panel_xray_link)
        val pJson = view.findViewById<android.view.View>(R.id.panel_xray_json)
        when (savedMode) {
            "link" -> {
                rgRestore.check(R.id.rb_xray_link)
                pLink.visibility = android.view.View.VISIBLE
                pJson.visibility = android.view.View.GONE
            }
            "json" -> {
                rgRestore.check(R.id.rb_xray_json)
                pLink.visibility = android.view.View.GONE
                pJson.visibility = android.view.View.VISIBLE
            }
        }
        view.findViewById<EditText>(R.id.et_v2dns_dns_server).setText(c.slowDns.dnsServer)
        view.findViewById<EditText>(R.id.et_v2dns_dns_port).setText(c.slowDns.dnsPort.toString())
        view.findViewById<EditText>(R.id.et_v2dns_nameserver).setText(c.slowDns.nameserver)
        view.findViewById<EditText>(R.id.et_v2dns_pubkey).setText(c.slowDns.publicKey)
        view.findViewById<EditText>(R.id.et_hys_host).setText(c.hysteria.serverAddress)
        view.findViewById<EditText>(R.id.et_hys_port).setText(c.hysteria.serverPort.toString())
        view.findViewById<EditText>(R.id.et_hys_auth).setText(c.hysteria.authPassword)
        view.findViewById<EditText>(R.id.et_hys_upload).setText(c.hysteria.uploadMbps.toString())
        view.findViewById<EditText>(R.id.et_hys_download).setText(c.hysteria.downloadMbps.toString())
        view.findViewById<EditText>(R.id.et_hys_obfs).setText(c.hysteria.obfsPassword)
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
        val v2dnsDnsServer = view.findViewById<EditText>(R.id.et_v2dns_dns_server).text.toString().trim()
        val v2dnsDnsPort = view.findViewById<EditText>(R.id.et_v2dns_dns_port).text.toString().toIntOrNull() ?: 53
        val dns = c.slowDns.copy(
            dnsServer = if (c.tunnelMode == com.kighmu.vpn.models.TunnelMode.V2RAY_SLOWDNS && v2dnsDnsServer.isNotBlank()) v2dnsDnsServer
                        else profileRepo.getSelected().firstOrNull()?.dnsServer ?: "8.8.8.8",
            dnsPort = if (c.tunnelMode == com.kighmu.vpn.models.TunnelMode.V2RAY_SLOWDNS) v2dnsDnsPort else c.slowDns.dnsPort,
            nameserver = view.findViewById<EditText>(R.id.et_v2dns_nameserver).text.toString().trim(),
            publicKey = view.findViewById<EditText>(R.id.et_v2dns_pubkey).text.toString().trim()
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
            sslHost = view.findViewById<EditText>(R.id.et_ssh_host).text.toString(),
            sslPort = view.findViewById<EditText>(R.id.et_ssh_port).text.toString().toIntOrNull() ?: 443,
            sni = view.findViewById<EditText>(R.id.et_sni).text.toString()
        )
        // Vérifier que le mode Xray est sélectionné si on est en mode V2RAY_XRAY
        val rgXray = view.findViewById<android.widget.RadioGroup>(R.id.rg_xray_mode)
        val tvXrayWarning = view.findViewById<android.widget.TextView>(R.id.tv_xray_mode_warning)
        if (c.tunnelMode == com.kighmu.vpn.models.TunnelMode.V2RAY_XRAY &&
            rgXray.checkedRadioButtonId == -1) {
            tvXrayWarning.visibility = android.view.View.VISIBLE
            return
        }
        // Mode V2RAY_SLOWDNS: utiliser le JSON parsé depuis le lien V2DNS
        val isV2dnsMode = currentTab == 5
        val xrayJson = if (isV2dnsMode) {
            when {
                parsedJsonFromV2dnsLink.isNotBlank() -> parsedJsonFromV2dnsLink
                c.xray.jsonConfig.isNotBlank() && c.xray.jsonConfig != com.kighmu.vpn.models.XrayConfig.defaultXrayConfig -> c.xray.jsonConfig
                else -> ""
            }
        } else when (rgXray.checkedRadioButtonId) {
            R.id.rb_xray_link -> {
                if (parsedJsonFromLink.isNotBlank()) parsedJsonFromLink
                else view.findViewById<EditText>(R.id.et_xray_json).text.toString()
            }
            else -> view.findViewById<EditText>(R.id.et_xray_json).text.toString()
        }
        if (xrayJson.isBlank() || xrayJson == com.kighmu.vpn.models.XrayConfig.defaultXrayConfig) {
            tvXrayWarning.text = "⚠️ Config vide - parsez d'abord le lien ou collez un JSON"
            tvXrayWarning.visibility = android.view.View.VISIBLE
            return
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
        viewModel.saveConfig(c.copy(
            sshCredentials = ssh, slowDns = dns, httpProxy = http, hysteria = hys,
            xray = xray,
            slowDnsProfiles = dnsProfileAdapter?.getProfiles() ?: mutableListOf()
        ))
        Toast.makeText(requireContext(), "Config saved!", Toast.LENGTH_SHORT).show()
    }

}
