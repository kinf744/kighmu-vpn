package com.kighmu.vpn.ui.dialogs

import android.app.AlertDialog
import android.content.Context
import android.widget.*
import com.kighmu.vpn.profiles.V2rayDnsProfile
import org.json.JSONObject
import android.util.Base64
import java.net.URI

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

        section("V2RAY / XRAY LINK")
        val etLink = EditText(context).apply {
            hint = "vmess:// vless:// trojan:// ss://"
            setText(p.xrayLink)
            setTextColor(0xFF000000.toInt())
            setHintTextColor(0xFF888888.toInt())
            isSingleLine = false
            setLines(3)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = 8 }
            layout.addView(this)
        }

        section("SLOWDNS CONFIGURATION")
        val etDnsServer = field("DNS Server", p.dnsServer)
        val etDnsPort = field("DNS Port", p.dnsPort.toString(), true)
        val etNameserver = field("Nameserver (dnstt target)", p.nameserver)
        val etPubKey = field("Public Key", p.publicKey)

        AlertDialog.Builder(context)
            .setTitle(if (isEdit) "Modifier V2ray+DNS" else "Nouveau V2ray+DNS")
            .setView(scroll)
            .setPositiveButton("Sauvegarder") { _, _ ->
                val link = etLink.text.toString().trim()
                val updated = p.copy(
                    profileName = etName.text.toString().ifEmpty { "V2ray+DNS" },
                    xrayLink = link,
                    dnsServer = etDnsServer.text.toString().ifEmpty { "8.8.8.8" },
                    dnsPort = etDnsPort.text.toString().toIntOrNull() ?: 53,
                    nameserver = etNameserver.text.toString(),
                    publicKey = etPubKey.text.toString()
                )
                
                // Parser le lien pour remplir les champs internes pour la compatibilité
                parseLinkIntoProfile(link, updated)
                
                onSave(updated)
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun parseLinkIntoProfile(link: String, p: V2rayDnsProfile) {
        try {
            when {
                link.startsWith("vmess://") -> {
                    val b64 = link.removePrefix("vmess://")
                    val json = String(Base64.decode(b64, Base64.DEFAULT))
                    val obj = JSONObject(json)
                    p.protocol = "vmess"
                    p.serverAddress = obj.optString("add", "")
                    p.serverPort = obj.optInt("port", 443)
                    p.uuid = obj.optString("id", "")
                    p.encryption = obj.optString("scy", "auto")
                    p.transport = obj.optString("net", "tcp")
                    p.wsPath = obj.optString("path", "/")
                    p.wsHost = obj.optString("host", "")
                    p.tls = obj.optString("tls", "") == "tls"
                    p.sni = obj.optString("sni", p.serverAddress)
                }
                link.startsWith("vless://") || link.startsWith("trojan://") -> {
                    val uri = URI(link)
                    p.protocol = if (link.startsWith("vless://")) "vless" else "trojan"
                    p.uuid = uri.userInfo ?: ""
                    p.serverAddress = uri.host ?: ""
                    p.serverPort = uri.port.takeIf { it > 0 } ?: 443
                    val params = uri.query?.split("&")?.associate { 
                        it.split("=").let { parts -> parts[0] to (parts.getOrNull(1) ?: "") } 
                    } ?: emptyMap()
                    p.transport = params["type"] ?: "tcp"
                    p.tls = params["security"] == "tls" || params["security"] == "reality"
                    p.sni = params["sni"] ?: params["host"] ?: p.serverAddress
                    p.wsPath = params["path"] ?: "/"
                    p.wsHost = params["host"] ?: ""
                }
            }
        } catch (e: Exception) {
            // En cas d'erreur de parsing, on laisse les champs tels quels
        }
    }
}
