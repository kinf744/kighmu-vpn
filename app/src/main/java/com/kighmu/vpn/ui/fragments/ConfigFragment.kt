package com.kighmu.vpn.ui.fragments

import android.os.Bundle
import android.view.*
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.kighmu.vpn.databinding.FragmentConfigBinding
import com.kighmu.vpn.models.*
import com.kighmu.vpn.ui.MainViewModel
import kotlinx.coroutines.launch
import org.json.JSONException
import org.json.JSONObject

/**
 * Configuration fragment — exposes all tunnel settings editors.
 * Uses a TabLayout + ViewPager2 for each config section.
 */
class ConfigFragment : Fragment() {

    private var _binding: FragmentConfigBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentConfigBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupTabs()
        loadConfig()
        setupSaveButton()
    }

    // ─── Tab Setup ────────────────────────────────────────────────────────────

    private fun setupTabs() {
        val tabs = listOf("SSH", "SlowDNS", "HTTP Proxy", "WS", "SSL/TLS", "Xray", "Hysteria")
        tabs.forEachIndexed { i, name ->
            binding.tabLayout.addTab(binding.tabLayout.newTab().setText(name))
        }

        binding.tabLayout.addOnTabSelectedListener(object :
            com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab) {
                showSection(tab.position)
            }
            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab) {}
            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab) {}
        })

        showSection(0)
    }

    private fun showSection(index: Int) {
        val sections = listOf(
            binding.sectionSsh.root,
            binding.sectionSlowdns.root,
            binding.sectionHttpProxy.root,
            binding.sectionSshWs.root,
            binding.sectionSshSsl.root,
            binding.sectionXray.root,
            binding.sectionHysteria.root
        )
        sections.forEachIndexed { i, v -> v.visibility = if (i == index) View.VISIBLE else View.GONE }
    }

    // ─── Load Config ─────────────────────────────────────────────────────────

    private fun loadConfig() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.config.collect { cfg ->
                populateSsh(cfg.sshCredentials)
                populateSlowDns(cfg.slowDns)
                populateHttpProxy(cfg.httpProxy)
                populateSshWs(cfg.sshWebSocket)
                populateSshSsl(cfg.sshSsl)
                populateXray(cfg.xray)
                populateHysteria(cfg.hysteria)
                binding.etConfigName.setText(cfg.configName)
            }
        }
    }

    // ─── SSH Section ─────────────────────────────────────────────────────────

    private fun populateSsh(ssh: SshCredentials) {
        val s = binding.sectionSsh
        s.etSshHost.setText(ssh.host)
        s.etSshPort.setText(ssh.port.toString())
        s.etSshUser.setText(ssh.username)
        s.etSshPassword.setText(ssh.password)
        s.etPrivateKey.setText(ssh.privateKey)
        s.switchUseKey.isChecked = ssh.usePrivateKey
        s.etPrivateKey.visibility = if (ssh.usePrivateKey) View.VISIBLE else View.GONE
        s.switchUseKey.setOnCheckedChangeListener { _, checked ->
            s.etPrivateKey.visibility = if (checked) View.VISIBLE else View.GONE
        }
    }

    private fun collectSsh(): SshCredentials {
        val s = binding.sectionSsh
        return SshCredentials(
            host = s.etSshHost.text.toString(),
            port = s.etSshPort.text.toString().toIntOrNull() ?: 22,
            username = s.etSshUser.text.toString(),
            password = s.etSshPassword.text.toString(),
            privateKey = s.etPrivateKey.text.toString(),
            usePrivateKey = s.switchUseKey.isChecked
        )
    }

    // ─── SlowDNS Section ─────────────────────────────────────────────────────

    private fun populateSlowDns(dns: SlowDnsConfig) {
        val s = binding.sectionSlowdns
        s.etDnsServer.setText(dns.dnsServer)
        s.etDnsPort.setText(dns.dnsPort.toString())
        s.etNameserver.setText(dns.nameserver)
        s.etDnsPublicKey.setText(dns.publicKey)
        s.etDnsPrivateKey.setText(dns.privateKey)
        s.etDnsPayload.setText(dns.dnsPayload)
        s.switchDnsUdp.isChecked = dns.useUdp
    }

    private fun collectSlowDns() = SlowDnsConfig(
        dnsServer = binding.sectionSlowdns.etDnsServer.text.toString(),
        dnsPort = binding.sectionSlowdns.etDnsPort.text.toString().toIntOrNull() ?: 53,
        nameserver = binding.sectionSlowdns.etNameserver.text.toString(),
        publicKey = binding.sectionSlowdns.etDnsPublicKey.text.toString(),
        privateKey = binding.sectionSlowdns.etDnsPrivateKey.text.toString(),
        dnsPayload = binding.sectionSlowdns.etDnsPayload.text.toString(),
        useUdp = binding.sectionSlowdns.switchDnsUdp.isChecked
    )

    // ─── HTTP Proxy Section ───────────────────────────────────────────────────

    private fun populateHttpProxy(proxy: HttpProxyConfig) {
        val s = binding.sectionHttpProxy
        s.etProxyHost.setText(proxy.proxyHost)
        s.etProxyPort.setText(proxy.proxyPort.toString())
        s.etPayload.setText(proxy.customPayload)
        s.etCustomHeaders.setText(
            proxy.customHeaders.entries.joinToString("\n") { "${it.key}: ${it.value}" }
        )
    }

    private fun collectHttpProxy(): HttpProxyConfig {
        val s = binding.sectionHttpProxy
        val headersText = s.etCustomHeaders.text.toString()
        val headers = mutableMapOf<String, String>()
        headersText.lines().forEach { line ->
            val idx = line.indexOf(':')
            if (idx > 0) headers[line.substring(0, idx).trim()] = line.substring(idx + 1).trim()
        }
        return HttpProxyConfig(
            proxyHost = s.etProxyHost.text.toString(),
            proxyPort = s.etProxyPort.text.toString().toIntOrNull() ?: 8080,
            customPayload = s.etPayload.text.toString(),
            customHeaders = headers
        )
    }

    // ─── SSH WebSocket Section ────────────────────────────────────────────────

    private fun populateSshWs(ws: SshWebSocketConfig) {
        val s = binding.sectionSshWs
        s.etWsHost.setText(ws.wsHost)
        s.etWsPort.setText(ws.wsPort.toString())
        s.etWsPath.setText(ws.wsPath)
        s.switchWss.isChecked = ws.useWss
    }

    private fun collectSshWs() = SshWebSocketConfig(
        wsHost = binding.sectionSshWs.etWsHost.text.toString(),
        wsPort = binding.sectionSshWs.etWsPort.text.toString().toIntOrNull() ?: 80,
        wsPath = binding.sectionSshWs.etWsPath.text.toString(),
        useWss = binding.sectionSshWs.switchWss.isChecked
    )

    // ─── SSH SSL/TLS Section ──────────────────────────────────────────────────

    private fun populateSshSsl(ssl: SshSslConfig) {
        val s = binding.sectionSshSsl
        s.etSslHost.setText(ssl.sslHost)
        s.etSslPort.setText(ssl.sslPort.toString())
        s.etSni.setText(ssl.sni)
        s.switchInsecure.isChecked = ssl.allowInsecure
    }

    private fun collectSshSsl() = SshSslConfig(
        sslHost = binding.sectionSshSsl.etSslHost.text.toString(),
        sslPort = binding.sectionSshSsl.etSslPort.text.toString().toIntOrNull() ?: 443,
        sni = binding.sectionSshSsl.etSni.text.toString(),
        tlsVersion = "TLSv1.3",
        allowInsecure = binding.sectionSshSsl.switchInsecure.isChecked
    )

    // ─── Xray Section ─────────────────────────────────────────────────────────

    private fun populateXray(xray: XrayConfig) {
        val s = binding.sectionXray
        s.etXrayJson.setText(xray.jsonConfig)

        // Format button
        s.btnFormatJson.setOnClickListener { formatXrayJson() }

        // Validate button
        s.btnValidateJson.setOnClickListener { validateXrayJson() }

        // Paste button
        s.btnPasteJson.setOnClickListener {
            val clipboard = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
            val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
            if (text.isNotEmpty()) s.etXrayJson.setText(text)
        }

        // Copy button
        s.btnCopyJson.setOnClickListener {
            val clipboard = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("Xray Config", s.etXrayJson.text.toString())
            clipboard.setPrimaryClip(clip)
            com.google.android.material.snackbar.Snackbar.make(requireView(), "Copied to clipboard", com.google.android.material.snackbar.Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun formatXrayJson() {
        try {
            val json = binding.sectionXray.etXrayJson.text.toString()
            val formatted = JSONObject(json).toString(2)
            binding.sectionXray.etXrayJson.setText(formatted)
            binding.sectionXray.tvJsonStatus.text = "✓ Valid JSON"
            binding.sectionXray.tvJsonStatus.setTextColor(resources.getColor(com.kighmu.vpn.R.color.success_green, null))
        } catch (e: JSONException) {
            binding.sectionXray.tvJsonStatus.text = "✗ ${e.message}"
            binding.sectionXray.tvJsonStatus.setTextColor(resources.getColor(com.kighmu.vpn.R.color.error_red, null))
        }
    }

    private fun validateXrayJson() {
        try {
            JSONObject(binding.sectionXray.etXrayJson.text.toString())
            binding.sectionXray.tvJsonStatus.text = "✓ Valid JSON"
            binding.sectionXray.tvJsonStatus.setTextColor(resources.getColor(com.kighmu.vpn.R.color.success_green, null))
        } catch (e: JSONException) {
            binding.sectionXray.tvJsonStatus.text = "✗ Invalid: ${e.message}"
            binding.sectionXray.tvJsonStatus.setTextColor(resources.getColor(com.kighmu.vpn.R.color.error_red, null))
        }
    }

    private fun collectXray(): XrayConfig {
        val json = binding.sectionXray.etXrayJson.text.toString()
        val base = viewModel.config.value.xray
        return try {
            JSONObject(json) // validate
            base.copy(jsonConfig = json)
        } catch (_: Exception) {
            base
        }
    }

    // ─── Hysteria Section ─────────────────────────────────────────────────────

    private fun populateHysteria(h: HysteriaConfig) {
        val s = binding.sectionHysteria
        s.etHysteriaServer.setText(h.serverAddress)
        s.etHysteriaPort.setText(h.serverPort.toString())
        s.etHysteriaAuth.setText(h.authPassword)
        s.etUploadSpeed.setText(h.uploadMbps.toString())
        s.etDownloadSpeed.setText(h.downloadMbps.toString())
        s.etObfsPassword.setText(h.obfsPassword)
        s.etHysteriaSni.setText(h.sni)
        s.switchHysteriaInsecure.isChecked = h.allowInsecure
    }

    private fun collectHysteria() = HysteriaConfig(
        serverAddress = binding.sectionHysteria.etHysteriaServer.text.toString(),
        serverPort = binding.sectionHysteria.etHysteriaPort.text.toString().toIntOrNull() ?: 443,
        authPassword = binding.sectionHysteria.etHysteriaAuth.text.toString(),
        uploadMbps = binding.sectionHysteria.etUploadSpeed.text.toString().toIntOrNull() ?: 10,
        downloadMbps = binding.sectionHysteria.etDownloadSpeed.text.toString().toIntOrNull() ?: 50,
        obfsPassword = binding.sectionHysteria.etObfsPassword.text.toString(),
        sni = binding.sectionHysteria.etHysteriaSni.text.toString(),
        allowInsecure = binding.sectionHysteria.switchHysteriaInsecure.isChecked
    )

    // ─── Save ─────────────────────────────────────────────────────────────────

    private fun setupSaveButton() {
        binding.btnSaveConfig.setOnClickListener {
            val config = viewModel.config.value.copy(
                configName = binding.etConfigName.text.toString(),
                sshCredentials = collectSsh(),
                slowDns = collectSlowDns(),
                httpProxy = collectHttpProxy(),
                sshWebSocket = collectSshWs(),
                sshSsl = collectSshSsl(),
                xray = collectXray(),
                hysteria = collectHysteria()
            )
            viewModel.saveConfig(config)
            com.google.android.material.snackbar.Snackbar.make(
                requireView(), "Configuration saved", com.google.android.material.snackbar.Snackbar.LENGTH_SHORT
            ).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
