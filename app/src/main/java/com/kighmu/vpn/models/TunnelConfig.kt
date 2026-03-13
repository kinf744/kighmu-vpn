package com.kighmu.vpn.models

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

// ─────────────────────────────────────────────────────────────────────────────
// Tunnel Mode Enum
// ─────────────────────────────────────────────────────────────────────────────

enum class TunnelMode(val id: Int, val label: String) {
    SLOW_DNS(0, "SlowDNS"),
    HTTP_PROXY(1, "HTTP Proxy + Payload"),
    SSH_WEBSOCKET(2, "SSH WebSocket"),
    SSH_SSL_TLS(3, "SSH SSL/TLS"),
    V2RAY_XRAY(4, "V2Ray / Xray"),
    V2RAY_SLOWDNS(5, "V2Ray + SlowDNS"),
    HYSTERIA_UDP(6, "Hysteria UDP");

    companion object {
        fun fromId(id: Int) = values().firstOrNull { it.id == id } ?: HTTP_PROXY
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Connection Status
// ─────────────────────────────────────────────────────────────────────────────

enum class ConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    ERROR
}

// ─────────────────────────────────────────────────────────────────────────────
// SSH Credentials (shared across SSH-based modes)
// ─────────────────────────────────────────────────────────────────────────────

data class SshCredentials(
    @SerializedName("host") var host: String = "",
    @SerializedName("port") var port: Int = 22,
    @SerializedName("username") var username: String = "",
    @SerializedName("password") var password: String = "",
    @SerializedName("privateKey") var privateKey: String = "",
    @SerializedName("usePrivateKey") var usePrivateKey: Boolean = false
)

// ─────────────────────────────────────────────────────────────────────────────
// SlowDNS Config
// ─────────────────────────────────────────────────────────────────────────────

data class SlowDnsConfig(
    @SerializedName("dnsServer") var dnsServer: String = "8.8.8.8",
    @SerializedName("dnsPort") var dnsPort: Int = 53,
    @SerializedName("nameserver") var nameserver: String = "",
    @SerializedName("publicKey") var publicKey: String = "",
    @SerializedName("privateKey") var privateKey: String = "",
    @SerializedName("dnsPayload") var dnsPayload: String = "",
    @SerializedName("useUdp") var useUdp: Boolean = true
)

// ─────────────────────────────────────────────────────────────────────────────
// HTTP Proxy + Payload Config
// ─────────────────────────────────────────────────────────────────────────────

data class HttpProxyConfig(
    @SerializedName("proxyHost") var proxyHost: String = "",
    @SerializedName("proxyPort") var proxyPort: Int = 8080,
    @SerializedName("customPayload") var customPayload: String = defaultPayload,
    @SerializedName("customHeaders") var customHeaders: MutableMap<String, String> = mutableMapOf()
) {
    companion object {
        const val defaultPayload = "GET / HTTP/1.1[crlf]Host: [host][crlf]Connection: Keep-Alive[crlf]Upgrade: websocket[crlf][crlf]"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// SSH WebSocket Config
// ─────────────────────────────────────────────────────────────────────────────


data class SshWebSocketConfig(
    @SerializedName("wsHost") var wsHost: String = "",
    @SerializedName("wsPort") var wsPort: Int = 80,
    @SerializedName("wsPath") var wsPath: String = "/",
    @SerializedName("useWss") var useWss: Boolean = false,
    @SerializedName("wsHeaders") var wsHeaders: MutableMap<String, String> = mutableMapOf()
)

// ─────────────────────────────────────────────────────────────────────────────
// SSH SSL/TLS Config
// ─────────────────────────────────────────────────────────────────────────────

data class SshSslConfig(
    @SerializedName("sslHost") var sslHost: String = "",
    @SerializedName("sslPort") var sslPort: Int = 443,
    @SerializedName("sni") var sni: String = "",
    @SerializedName("tlsVersion") var tlsVersion: String = "TLSv1.3",
    @SerializedName("allowInsecure") var allowInsecure: Boolean = false
)

// ─────────────────────────────────────────────────────────────────────────────
// V2Ray / Xray Config
// ─────────────────────────────────────────────────────────────────────────────

data class XrayConfig(
    @SerializedName("jsonConfig") var jsonConfig: String = defaultXrayConfig,
    @SerializedName("protocol") var protocol: String = "vmess",
    @SerializedName("serverAddress") var serverAddress: String = "",
    @SerializedName("serverPort") var serverPort: Int = 443,
    @SerializedName("uuid") var uuid: String = "",
    @SerializedName("encryption") var encryption: String = "auto",
    @SerializedName("transport") var transport: String = "ws",
    @SerializedName("wsPath") var wsPath: String = "/",
    @SerializedName("wsHost") var wsHost: String = "",
    @SerializedName("tls") var tls: Boolean = true,
    @SerializedName("sni") var sni: String = "",
    @SerializedName("allowInsecure") var allowInsecure: Boolean = false
) {
    companion object {
        const val defaultXrayConfig = """{
  "log": { "loglevel": "warning" },
  "inbounds": [{
    "port": 10808,
    "protocol": "socks",
    "settings": { "udp": true }
  }],
  "outbounds": [{
    "protocol": "vmess",
    "settings": {
      "vnext": [{
        "address": "example.com",
        "port": 443,
        "users": [{ "id": "your-uuid-here", "alterId": 0 }]
      }]
    },
    "streamSettings": {
      "network": "ws",
      "security": "tls",
      "wsSettings": { "path": "/" },
      "tlsSettings": { "serverName": "example.com" }
    }
  }]
}"""
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Hysteria Config
// ─────────────────────────────────────────────────────────────────────────────

data class HysteriaConfig(
    @SerializedName("serverAddress") var serverAddress: String = "",
    @SerializedName("serverPort") var serverPort: Int = 443,
    @SerializedName("authPassword") var authPassword: String = "",
    @SerializedName("uploadMbps") var uploadMbps: Int = 10,
    @SerializedName("downloadMbps") var downloadMbps: Int = 50,
    @SerializedName("obfsPassword") var obfsPassword: String = "",
    @SerializedName("sni") var sni: String = "",
    @SerializedName("allowInsecure") var allowInsecure: Boolean = false,
    @SerializedName("version") var version: Int = 2
)

// ─────────────────────────────────────────────────────────────────────────────
// Master Configuration (stored in .kighmu file)
// ─────────────────────────────────────────────────────────────────────────────

data class KighmuConfig(
    @SerializedName("version") val version: Int = 1,
    @SerializedName("configName") var configName: String = "KIGHMU Config",
    @SerializedName("creator") var creator: String = "",
    @SerializedName("createdAt") val createdAt: Long = System.currentTimeMillis(),
    @SerializedName("expiresAt") var expiresAt: Long = 0L,          // 0 = no expiry
    @SerializedName("hardwareId") var hardwareId: String = "",      // empty = no lock
    @SerializedName("signature") var signature: String = "",

    @SerializedName("tunnelMode") var tunnelMode: TunnelMode = TunnelMode.HTTP_PROXY,
    @SerializedName("sshCredentials") var sshCredentials: SshCredentials = SshCredentials(),
    @SerializedName("slowDns") var slowDns: SlowDnsConfig = SlowDnsConfig(),
    @SerializedName("slowDnsProfiles") var slowDnsProfiles: MutableList<SlowDnsConfig> = mutableListOf(),
    @SerializedName("exportConfig") var exportConfig: ExportConfig? = null,
    @SerializedName("xrayConfig") var xrayConfig: XrayConfig = XrayConfig(),
    @SerializedName("httpProxy") var httpProxy: HttpProxyConfig = HttpProxyConfig(),
    @SerializedName("sshWebSocket") var sshWebSocket: SshWebSocketConfig = SshWebSocketConfig(),
    @SerializedName("sshSsl") var sshSsl: SshSslConfig = SshSslConfig(),
    @SerializedName("xray") var xray: XrayConfig = XrayConfig(),
    @SerializedName("hysteria") var hysteria: HysteriaConfig = HysteriaConfig(),

    @SerializedName("dnsLeak") var dnsLeakProtection: Boolean = true,
    @SerializedName("killSwitch") var killSwitch: Boolean = false,
    @SerializedName("autoReconnect") var autoReconnect: Boolean = true
) {
    fun toJson(): String = Gson().toJson(this)

    companion object {
        fun fromJson(json: String): KighmuConfig = Gson().fromJson(json, KighmuConfig::class.java)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// VPN Stats
// ─────────────────────────────────────────────────────────────────────────────

data class VpnStats(
    var uploadBytes: Long = 0,
    var downloadBytes: Long = 0,
    var uploadSpeed: Long = 0,
    var downloadSpeed: Long = 0,
    var connectedAt: Long = 0,
    var ping: Int = 0
) {
    val elapsedSeconds: Long
        get() = if (connectedAt > 0) (System.currentTimeMillis() - connectedAt) / 1000 else 0

    fun formatUpload() = formatBytes(uploadBytes)
    fun formatDownload() = formatBytes(downloadBytes)
    fun formatUploadSpeed() = "${formatBytes(uploadSpeed)}/s"
    fun formatDownloadSpeed() = "${formatBytes(downloadSpeed)}/s"

    fun formatElapsed(): String {
        val s = elapsedSeconds
        return String.format("%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024))
        else -> String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024))
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Log Entry
// ─────────────────────────────────────────────────────────────────────────────

data class LogEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val level: LogLevel = LogLevel.INFO,
    val message: String,
    val tag: String = "KIGHMU"
) {
    enum class LogLevel { DEBUG, INFO, WARNING, ERROR }
}


// ─────────────────────────────────────────────────────────────────────────────
// Export Config Security Model
// ─────────────────────────────────────────────────────────────────────────────

data class XrayConfig(
    @SerializedName("protocol") var protocol: String = "vless",
    @SerializedName("address") var address: String = "",
    @SerializedName("port") var port: Int = 443,
    @SerializedName("uuid") var uuid: String = "",
    @SerializedName("password") var password: String = "",
    @SerializedName("flow") var flow: String = "",
    @SerializedName("network") var network: String = "tcp",
    @SerializedName("tls") var tls: Boolean = true,
    @SerializedName("sni") var sni: String = "",
    @SerializedName("wsPath") var wsPath: String = "/",
    @SerializedName("wsHost") var wsHost: String = "",
    @SerializedName("grpcServiceName") var grpcServiceName: String = "",
    @SerializedName("fingerprint") var fingerprint: String = "chrome",
    @SerializedName("publicKey") var publicKey: String = "",
    @SerializedName("shortId") var shortId: String = "",
    @SerializedName("spiderX") var spiderX: String = ""
)

data class ExportConfig(
    @SerializedName("fileName") var fileName: String = "kighmu_config",
    @SerializedName("accessCode") var accessCode: String = "",         // code ex: WmdE6mSB
    @SerializedName("expiresAt") var expiresAt: Long = 0L,             // 0 = no expiry
    @SerializedName("hardwareId") var hardwareId: String = "",         // device lock
    @SerializedName("lockOperator") var lockOperator: Boolean = false,
    @SerializedName("operatorName") var operatorName: String = "",
    @SerializedName("blockRoot") var blockRoot: Boolean = false,
    @SerializedName("mobileDataOnly") var mobileDataOnly: Boolean = false,
    @SerializedName("lockDeviceId") var lockDeviceId: Boolean = false,
    @SerializedName("playStoreOnly") var playStoreOnly: Boolean = false,
    @SerializedName("disableOverride") var disableOverride: Boolean = false,
    @SerializedName("blockTorrent") var blockTorrent: Boolean = false,
    @SerializedName("gameMode") var gameMode: Boolean = false,
    @SerializedName("userMessage") var userMessage: String = "",
    @SerializedName("securitySignature") var securitySignature: String = "",
    // Export type: "normal" | "burn" | "expiry"
    @SerializedName("exportType") var exportType: String = "normal",
    // Burn after import - config supprimée après 1ère utilisation
    @SerializedName("burnAfterImport") var burnAfterImport: Boolean = false,
    @SerializedName("burnToken") var burnToken: String = "",
    // Verrouiller toutes les informations de la config
    @SerializedName("lockAllConfig") var lockAllConfig: Boolean = false,
    // Unique app identifier - empêche import dans autres apps
    @SerializedName("appId") var appId: String = "com.kighmu.vpn"
)