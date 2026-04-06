package com.kighmu.vpn.ui.dialogs

import android.app.AlertDialog
import android.content.Context
import android.widget.*
import com.kighmu.vpn.profiles.V2rayDnsProfile

object V2rayDnsProfileEditDialog {
    fun show(
        context: Context,
        profile: V2rayDnsProfile? = null,
        onSave: (V2rayDnsProfile) -> Unit
    ) {
        val isEdit = profile != null
        val p = profile ?: V2rayDnsProfile()

        val scroll = ScrollView(context)
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 16)
        }
        scroll.addView(layout)

        fun section(title: String) = TextView(context).apply {
            text = title
            setTextColor(0xFF4FC3F7.toInt())
            textSize = 12f
            setPadding(0, 16, 0, 4)
            layoutParams = LinearLayout.LayoutParams(-1, -2)
            layout.addView(this)
        }

        fun field(hint: String, value: String, numeric: Boolean = false) = EditText(context).apply {
            this.hint = hint
            setText(value)
            setTextColor(0xFF000000.toInt())
            setHintTextColor(0xFF888888.toInt())
            if (numeric) inputType = android.text.InputType.TYPE_CLASS_NUMBER
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = 8 }
            layout.addView(this)
        }

        section("PROFILE")
        val etName = field("Profile Name", p.profileName)

        section("V2RAY / XRAY CONFIGURATION")
        val etProtocol = field("Protocol (vmess/vless/trojan)", p.protocol)
        val etServer = field("Server Address", p.serverAddress)
        val etPort = field("Server Port", p.serverPort.toString(), true)
        val etUuid = field("UUID / ID", p.uuid)
        val etEncryption = field("Encryption (auto/aes-128-gcm)", p.encryption)
        val etTransport = field("Transport (ws/tcp/kcp)", p.transport)
        val etWsPath = field("WebSocket Path", p.wsPath)
        val etWsHost = field("WebSocket Host", p.wsHost)
        val etSni = field("SNI", p.sni)

        val cbTls = CheckBox(context).apply {
            text = "Enable TLS"
            isChecked = p.tls
            setTextColor(0xFFFFFFFF.toInt())
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = 8 }
            layout.addView(this)
        }

        val cbInsecure = CheckBox(context).apply {
            text = "Allow Insecure (Skip SSL Verification)"
            isChecked = p.allowInsecure
            setTextColor(0xFFFFFFFF.toInt())
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = 4 }
            layout.addView(this)
        }

        section("SLOWDNS CONFIGURATION")
        val etDnsServer = field("DNS Server", p.dnsServer)
        val etDnsPort = field("DNS Port", p.dnsPort.toString(), true)
        val etNameserver = field("Nameserver (dnstt target)", p.nameserver)
        val etPubKey = field("Public Key", p.publicKey)

        val etJsonConfig = EditText(context).apply {
            hint = "Xray JSON Config (optionnel)"
            setText(p.xrayJsonConfig)
            setTextColor(0xFF000000.toInt())
            setHintTextColor(0xFF888888.toInt())
            isSingleLine = false
            setLines(3)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = 8 }
            layout.addView(this)
        }

        AlertDialog.Builder(context)
            .setTitle(if (isEdit) "Modifier V2ray+DNS" else "Nouveau V2ray+DNS")
            .setView(scroll)
            .setPositiveButton("Sauvegarder") { _, _ ->
                val updated = p.copy(
                    profileName = etName.text.toString().ifEmpty { "V2ray+DNS" },
                    protocol = etProtocol.text.toString().ifEmpty { "vmess" },
                    serverAddress = etServer.text.toString(),
                    serverPort = etPort.text.toString().toIntOrNull() ?: 443,
                    uuid = etUuid.text.toString(),
                    encryption = etEncryption.text.toString().ifEmpty { "auto" },
                    transport = etTransport.text.toString().ifEmpty { "ws" },
                    wsPath = etWsPath.text.toString().ifEmpty { "/" },
                    wsHost = etWsHost.text.toString(),
                    sni = etSni.text.toString(),
                    tls = cbTls.isChecked,
                    allowInsecure = cbInsecure.isChecked,
                    dnsServer = etDnsServer.text.toString().ifEmpty { "8.8.8.8" },
                    dnsPort = etDnsPort.text.toString().toIntOrNull() ?: 53,
                    nameserver = etNameserver.text.toString(),
                    publicKey = etPubKey.text.toString(),
                    xrayJsonConfig = etJsonConfig.text.toString()
                )
                onSave(updated)
            }
            .setNegativeButton("Annuler", null)
            .show()
    }
}
