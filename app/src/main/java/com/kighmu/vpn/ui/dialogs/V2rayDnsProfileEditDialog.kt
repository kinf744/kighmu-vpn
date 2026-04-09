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
                
                // ✅ FIX: Parser le lien D'ABORD pour extraire les données
                val parsedData = parseLinkIntoData(link)
                
                // ✅ FIX: Créer le JSON config depuis les données parsées
                val xrayJsonConfig = buildXrayJsonConfig(parsedData)
                
                val updated = p.copy(
                    profileName = etName.text.toString().ifEmpty { "V2ray+DNS" },
                    xrayLink = link,
                    xrayJsonConfig = xrayJsonConfig,
                    protocol = parsedData["protocol"] as? String ?: "vmess",
                    serverAddress = parsedData["serverAddress"] as? String ?: "",
                    serverPort = parsedData["serverPort"] as? Int ?: 443,
                    uuid = parsedData["uuid"] as? String ?: "",
                    encryption = parsedData["encryption"] as? String ?: "auto",
                    transport = parsedData["transport"] as? String ?: "tcp",
                    wsPath = parsedData["wsPath"] as? String ?: "/",
                    wsHost = parsedData["wsHost"] as? String ?: "",
                    tls = parsedData["tls"] as? Boolean ?: false,
                    sni = parsedData["sni"] as? String ?: "",
                    allowInsecure = parsedData["allowInsecure"] as? Boolean ?: false,
                    dnsServer = etDnsServer.text.toString().ifEmpty { "8.8.8.8" },
                    dnsPort = etDnsPort.text.toString().toIntOrNull() ?: 53,
                    nameserver = etNameserver.text.toString(),
                    publicKey = etPubKey.text.toString()
                )

                android.util.Log.d("V2rayDnsProfileEditDialog", "Profil à sauvegarder: ${updated.profileName} - link: ${updated.xrayLink} - server: ${updated.serverAddress}")
                onSave(updated)
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun parseLinkIntoData(link: String): Map<String, Any> {
        val data = mutableMapOf<String, Any>()
        data["protocol"] = "vmess"
        data["serverAddress"] = ""
        data["serverPort"] = 443
        data["uuid"] = ""
        data["encryption"] = "auto"
        data["transport"] = "tcp"
        data["wsPath"] = "/"
        data["wsHost"] = ""
        data["tls"] = false
        data["sni"] = ""
        data["allowInsecure"] = false

        try {
            when {
                link.startsWith("vmess://") -> {
                    val b64 = link.removePrefix("vmess://")
                    val json = String(Base64.decode(b64, Base64.DEFAULT))
                    val obj = JSONObject(json)
                    data["protocol"] = "vmess"
                    data["serverAddress"] = obj.optString("add", "")
                    data["serverPort"] = obj.optInt("port", 443)
                    data["uuid"] = obj.optString("id", "")
                    data["encryption"] = obj.optString("scy", "auto")
                    data["transport"] = obj.optString("net", "tcp")
                    data["wsPath"] = obj.optString("path", "/")
                    data["wsHost"] = obj.optString("host", "")
                    data["tls"] = obj.optString("tls", "") == "tls"
                    data["sni"] = obj.optString("sni", data["serverAddress"] as String)
                }
                link.startsWith("vless://") || link.startsWith("trojan://") -> {
                    val uri = URI(link)
                    data["protocol"] = if (link.startsWith("vless://")) "vless" else "trojan"
                    data["uuid"] = uri.userInfo ?: ""
                    data["serverAddress"] = uri.host ?: ""
                    data["serverPort"] = uri.port.takeIf { it > 0 } ?: 443
                    val params = uri.query?.split("&")?.associate {
                        it.split("=").let { parts -> parts[0] to (parts.getOrNull(1) ?: "") }
                    } ?: emptyMap()
                    data["transport"] = params["type"] ?: "tcp"
                    data["tls"] = params["security"] == "tls" || params["security"] == "reality"
                    data["sni"] = params["sni"] ?: params["host"] ?: (data["serverAddress"] as String)
                    data["wsPath"] = params["path"] ?: "/"
                    data["wsHost"] = params["host"] ?: ""
                    data["allowInsecure"] = params["allowInsecure"] == "1" || params["allowInsecure"] == "true"
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("V2rayDnsProfileEditDialog", "Error parsing link", e)
        }

        return data
    }

    private fun buildXrayJsonConfig(data: Map<String, Any>): String {
        val protocol = data["protocol"] as? String ?: "vmess"
        val serverAddress = data["serverAddress"] as? String ?: ""
        val serverPort = data["serverPort"] as? Int ?: 443
        val uuid = data["uuid"] as? String ?: ""
        val encryption = data["encryption"] as? String ?: "auto"
        val transport = data["transport"] as? String ?: "tcp"
        val wsPath = data["wsPath"] as? String ?: "/"
        val wsHost = data["wsHost"] as? String ?: ""
        val tls = data["tls"] as? Boolean ?: false
        val sni = data["sni"] as? String ?: serverAddress
        val allowInsecure = data["allowInsecure"] as? Boolean ?: false

        val outbound = when (protocol) {
            "vmess" -> {
                val users = """[{"id": "$uuid", "alterId": 0, "security": "$encryption"}]"""
                """{
                    "protocol": "vmess",
                    "settings": {"vnext": [{"address": "$serverAddress", "port": $serverPort, "users": $users}]},
                    "streamSettings": {"network": "$transport", "security": "${if (tls) "tls" else "none"}", "tlsSettings": {"serverName": "$sni", "allowInsecure": $allowInsecure}}
                }"""
            }
            "vless" -> {
                val users = """[{"id": "$uuid", "encryption": "none"}]"""
                """{
                    "protocol": "vless",
                    "settings": {"vnext": [{"address": "$serverAddress", "port": $serverPort, "users": $users}]},
                    "streamSettings": {"network": "$transport", "security": "${if (tls) "tls" else "none"}", "tlsSettings": {"serverName": "$sni", "allowInsecure": $allowInsecure}}
                }"""
            }
            "trojan" -> {
                """{
                    "protocol": "trojan",
                    "settings": {"servers": [{"address": "$serverAddress", "port": $serverPort, "password": "$uuid"}]},
                    "streamSettings": {"network": "$transport", "security": "${if (tls) "tls" else "none"}", "tlsSettings": {"serverName": "$sni", "allowInsecure": $allowInsecure}}
                }"""
            }
            else -> {
                """{
                    "protocol": "$protocol",
                    "settings": {},
                    "streamSettings": {"network": "$transport", "security": "${if (tls) "tls" else "none"}", "tlsSettings": {"serverName": "$sni", "allowInsecure": $allowInsecure}}
                }"""
            }
        }

        return """{
            "log": {"loglevel": "warning"},
            "inbounds": [{"port": 10808, "protocol": "socks", "settings": {"udp": true}}],
            "outbounds": [$outbound],
            "routing": {"rules": []}
        }"""
    }
}
