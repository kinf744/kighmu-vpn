package com.kighmu.vpn.engines

import com.kighmu.vpn.models.XrayConfig
import android.content.Context
import android.os.ParcelFileDescriptor
import com.kighmu.vpn.models.KighmuConfig
import com.kighmu.vpn.utils.KighmuLogger
import com.trilead.ssh2.Connection
import kotlinx.coroutines.*
import okhttp3.*
import okio.ByteString
import java.io.*
import java.net.*
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.net.VpnService
import java.security.SecureRandom
import java.util.concurrent.LinkedBlockingQueue
import javax.net.ssl.*

// ─────────────────────────────────────────────────────────────────────────────
// Mode 4: SSH SSL/TLS Engine
// ─────────────────────────────────────────────────────────────────────────────

class SshSslEngine(
    private val config: KighmuConfig,
    private val context: Context
) : TunnelEngine {

    companion object {
        const val TAG = "SshSslEngine"
        fun getFreePort(): Int = try { java.net.ServerSocket(0).use { it.localPort } } catch (_: Exception) { 10804 }
    }

    private var _socksPort: Int = 0
    private val LOCAL_SOCKS_PORT: Int get() {
        if (_socksPort == 0) _socksPort = Companion.getFreePort()
        return _socksPort
    }
    private var running = false
    private var sshConnection: Connection? = null
    private val engineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val receiveQueue = LinkedBlockingQueue<ByteArray>(1000)

    private val sslConfig get() = config.sshSsl
    private val sshConfig get() = config.sshCredentials

    override suspend fun start(): Int {
        running = true
        KighmuLogger.info(TAG, "Starting SSH SSL/TLS engine: ${sslConfig.sshHost}:${sslConfig.sshPort}")

        withContext(Dispatchers.IO) {
            try {
                // SSH via SSL socket - utiliser ProxyData pour passer par le tunnel TLS
                val sslSocket = buildSslSocket()
                KighmuLogger.info(TAG, "SSL handshake OK: ${sslSocket.session.protocol} ${sslSocket.session.cipherSuite}")
                // Créer un ServerSocket local qui forward vers le SSL socket
                val localPort = findFreeLocalPort()
                val bridge = java.net.ServerSocket(localPort)
                bridge.soTimeout = 10000 // Timeout court pour l'acceptation
                
                Thread {
                    try {
                        val client = bridge.accept()
                        bridge.close()
                        client.tcpNoDelay = true
                        
                        val t1 = Thread {
                            try {
                                val i = client.getInputStream()
                                val o = sslSocket.outputStream
                                val buf = ByteArray(16384)
                                var n: Int
                                while (running && !client.isClosed && !sslSocket.isClosed) {
                                    n = i.read(buf)
                                    if (n <= 0) break
                                    o.write(buf, 0, n)
                                    o.flush()
                                }
                            } catch (_: Exception) {} finally {
                                try { client.close() } catch (_: Exception) {}
                                try { sslSocket.close() } catch (_: Exception) {}
                            }
                        }
                        
                        val t2 = Thread {
                            try {
                                val i = sslSocket.inputStream
                                val o = client.getOutputStream()
                                val buf = ByteArray(16384)
                                var n: Int
                                while (running && !client.isClosed && !sslSocket.isClosed) {
                                    n = i.read(buf)
                                    if (n <= 0) break
                                    o.write(buf, 0, n)
                                    o.flush()
                                }
                            } catch (_: Exception) {} finally {
                                try { client.close() } catch (_: Exception) {}
                                try { sslSocket.close() } catch (_: Exception) {}
                            }
                        }
                        
                        t1.isDaemon = true
                        t2.isDaemon = true
                        t1.start()
                        t2.start()
                    } catch (e: Exception) {
                        KighmuLogger.error(TAG, "Bridge error: ${e.message}")
                        try { bridge.close() } catch (_: Exception) {}
                    }
                }.start()
                // Lire la banniere SSH AVANT que Trilead consomme le socket
                val serverBanner = try {
                    val bannerSock = java.net.Socket("127.0.0.1", localPort)
                    bannerSock.soTimeout = 3000
                    val banner = bannerSock.getInputStream().bufferedReader().readLine() ?: ""
                    bannerSock.close()
                    banner.trim()
                } catch (_: Exception) { "" }
                if (serverBanner.isNotEmpty()) KighmuLogger.info(TAG, "Server version: $serverBanner")
                val conn = Connection("127.0.0.1", localPort)
                conn.connect(null, 30000, 30000)
                val authenticated = conn.authenticateWithPassword(sslConfig.sshUser, sslConfig.sshPass)
                if (!authenticated) throw Exception("SSH auth echoue pour ${sslConfig.sshUser}")
                conn.createDynamicPortForwarder(LOCAL_SOCKS_PORT)
                sshConnection = conn
                KighmuLogger.info(TAG, "SSH SSL/TLS tunnel ready - SOCKS5 sur port $LOCAL_SOCKS_PORT")
            } catch (e: Exception) {
                KighmuLogger.error(TAG, "SSH SSL/TLS failed: ${e.message}")
                throw e
            }
        }

        return LOCAL_SOCKS_PORT
    }

    private fun findFreeLocalPort(): Int = java.net.ServerSocket(0).use { it.localPort }

    private fun buildSslSocket(): SSLSocket {
        val tm = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(c: Array<java.security.cert.X509Certificate>, a: String) {}
            override fun checkServerTrusted(c: Array<java.security.cert.X509Certificate>, a: String) {}
            override fun getAcceptedIssuers() = arrayOf<java.security.cert.X509Certificate>()
        })
        val tlsVer = if (sslConfig.tlsVersion.isBlank()) "TLS" else sslConfig.tlsVersion
        val sslContext = SSLContext.getInstance(tlsVer).also { it.init(null, tm, SecureRandom()) }

        val factory = sslContext.socketFactory
        val host = if (sslConfig.sshHost.isNotBlank()) sslConfig.sshHost else sshConfig.host
        val port = if (sslConfig.sshPort > 0) sslConfig.sshPort else 443
        val socket = factory.createSocket(host, port) as SSLSocket

        if (sslConfig.sni.isNotEmpty()) {
            val params = SSLParameters()
            params.serverNames = listOf(SNIHostName(sslConfig.sni))
            socket.sslParameters = params
        }

        socket.startHandshake()
        return socket
    }


    override fun startTun2Socks(fd: Int) {
        try {
            KighmuLogger.info(TAG, "Démarrage HevTun2Socks (SSH SSL/TLS) fd=$fd port=$LOCAL_SOCKS_PORT")
            HevTun2Socks.init()
            if (HevTun2Socks.isAvailable) {
                // On récupère le VpnService depuis le contexte si possible, ou on utilise le relay Kotlin en fallback
                val vpnService = context as? android.net.VpnService
                if (vpnService != null) {
                    HevTun2Socks.start(context, fd, LOCAL_SOCKS_PORT, vpnService, mtu = 1500)
                    KighmuLogger.info(TAG, "HevTun2Socks démarré avec succès ✅")
                } else {
                    KighmuLogger.error(TAG, "Contexte n'est pas un VpnService, fallback impossible")
                }
            } else {
                KighmuLogger.error(TAG, "HevTun2Socks non disponible")
            }
        } catch (e: Exception) {
            KighmuLogger.error(TAG, "Erreur HevTun2Socks: ${e.message}")
        }
    }
    override suspend fun stop() {
        running = false
        try { HevTun2Socks.stop() } catch (_: Exception) {}
        try { sshConnection?.close() } catch (_: Exception) {}
        engineScope.cancel()
    }

    override suspend fun sendData(data: ByteArray, length: Int) {}
    override suspend fun receiveData(): ByteArray? = receiveQueue.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS)
    override fun isRunning() = running
}

// ─────────────────────────────────────────────────────────────────────────────
// Mode 5: Xray Engine
// ─────────────────────────────────────────────────────────────────────────────

class XrayEngine(
    private val config: KighmuConfig,
    private val context: Context,
    var dnsttProxyPort: Int = 0,  // Si > 0, Xray route via dnstt sur ce port
    private val instanceId: Int = 0,
    private val vpnService: VpnService? = null
) : TunnelEngine {
    private var _socksPort: Int = 0
    private val LOCAL_SOCKS_PORT: Int get() {
        if (_socksPort == 0) _socksPort = Companion.getFreePort()
        return _socksPort
    }

    companion object {
        const val TAG = "XrayEngine"
        const val LOCAL_HTTP_PORT = 10809
        // Port dynamique choisi par l'OS
        fun getFreePort(): Int = try { java.net.ServerSocket(0).use { it.localPort } } catch (_: Exception) { 10808 }
    }

    private var running = false
    private var xrayProcess: Process? = null
    private val engineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val receiveQueue = LinkedBlockingQueue<ByteArray>(1000)

    override suspend fun start(): Int {
        running = true
        KighmuLogger.info(TAG, "Starting Xray engine")

        withContext(Dispatchers.IO) {
            val xrayConfigFile = writeXrayConfig()
            val xrayBinary = extractXrayBinary()
            if (xrayBinary != null) {
                try {
                    startXrayProcess(xrayBinary, xrayConfigFile)
                    // Attendre que Xray soit prêt sur le port
                    var ready = false
                    repeat(20) {
                        if (!ready) {
                            delay(500)
                            try {
                                val s = java.net.Socket()
                                s.connect(java.net.InetSocketAddress("127.0.0.1", LOCAL_SOCKS_PORT), 200)
                                s.close()
                                ready = true
                                KighmuLogger.info(TAG, "Xray SOCKS5 pret sur port $LOCAL_SOCKS_PORT")
                            } catch (_: Exception) {}
                        }
                    }
                    if (!ready) throw Exception("Xray n'a pas demarre dans les temps")
                } catch (e: Exception) {
                    KighmuLogger.error(TAG, "Xray start error: ${e.message}")
                    throw e
                }
            } else {
                throw Exception("Xray binary introuvable - verifiez libxray.so dans jniLibs")
            }
        }

        return LOCAL_SOCKS_PORT
    }

    private fun writeXrayConfig(): File {
        val xrayConfig = config.xray
        // Choisir la bonne config selon le mode tunnel
        val isV2Dns = config.tunnelMode == com.kighmu.vpn.models.TunnelMode.V2RAY_SLOWDNS
        var jsonConfig = when {
            isV2Dns && xrayConfig.v2dnsJsonConfig.isNotBlank() -> xrayConfig.v2dnsJsonConfig
            isV2Dns && xrayConfig.xrayLinkJson.isNotBlank() -> xrayConfig.xrayLinkJson
            xrayConfig.inputMode == "link" && xrayConfig.xrayLinkJson.isNotBlank() -> xrayConfig.xrayLinkJson
            xrayConfig.jsonConfig.isNotBlank() && xrayConfig.jsonConfig != XrayConfig.defaultXrayConfig -> xrayConfig.jsonConfig
            else -> buildXrayConfigFromFields(xrayConfig)
        }
        // Normaliser le port SOCKS et nettoyer geoip/geosite via JSON parsing
        try {
            val obj = org.json.JSONObject(jsonConfig)
            // Forcer le port SOCKS à LOCAL_SOCKS_PORT (10808) et s'assurer qu'il existe
            val inbounds = obj.optJSONArray("inbounds")
            var hasSocks = false
            if (inbounds != null) {
                for (i in 0 until inbounds.length()) {
                    val inbound = inbounds.getJSONObject(i)
                    if (inbound.optString("protocol") == "socks") {
                        inbound.put("port", LOCAL_SOCKS_PORT)
                        inbound.put("listen", "127.0.0.1")
                        hasSocks = true
                        KighmuLogger.info(TAG, "SOCKS inbound port forcé à $LOCAL_SOCKS_PORT")
                    }
                }
                if (!hasSocks) {
                    val socksInbound = org.json.JSONObject()
                    socksInbound.put("listen", "127.0.0.1")
                    socksInbound.put("port", LOCAL_SOCKS_PORT)
                    socksInbound.put("protocol", "socks")
                    socksInbound.put("settings", org.json.JSONObject().put("udp", true))
                    inbounds.put(socksInbound)
                    obj.put("inbounds", inbounds)
                    KighmuLogger.info(TAG, "SOCKS inbound ajouté sur port $LOCAL_SOCKS_PORT")
                }
            }
            jsonConfig = obj.toString()
            val routing = obj.optJSONObject("routing")
            if (routing != null) {
                val rules = routing.optJSONArray("rules")
                if (rules != null) {
                    val cleaned = org.json.JSONArray()
                    for (i in 0 until rules.length()) {
                        val rule = rules.getJSONObject(i)
                        val ip = rule.optJSONArray("ip")?.toString() ?: ""
                        val domain = rule.optJSONArray("domain")?.toString() ?: ""
                        if (!ip.contains("geoip:") && !domain.contains("geosite:")) {
                            cleaned.put(rule)
                        }
                    }
                    routing.put("rules", cleaned)
                    obj.put("routing", routing)
                    jsonConfig = obj.toString(2)
                    KighmuLogger.info(TAG, "Routing nettoyé: ${cleaned.length()} règles")
                }
            }
            // Mode V2RAY_SLOWDNS: remplacer address/port par 127.0.0.1:dnsttPort
            if (dnsttProxyPort > 0) {
                val outbounds = obj.optJSONArray("outbounds")
                if (outbounds != null) {
                    for (i in 0 until outbounds.length()) {
                        val ob = outbounds.getJSONObject(i)
                        val proto = ob.optString("protocol")
                        val tag = ob.optString("tag", "")
                        if (proto != "freedom" && proto != "blackhole" && proto != "socks" && tag != "direct") {
                            val settings = ob.optJSONObject("settings")
                            val vnext = settings?.optJSONArray("vnext")
                            if (vnext != null && vnext.length() > 0) {
                                val server = vnext.getJSONObject(0)
                                server.put("address", "127.0.0.1")
                                server.put("port", dnsttProxyPort)
                                KighmuLogger.info(TAG, "dnstt: address=127.0.0.1 port=$dnsttProxyPort pour $proto")
                            }
                        }
                    }
                    obj.put("outbounds", outbounds)
                    jsonConfig = obj.toString()
                    KighmuLogger.info(TAG, "Config Xray+dnstt finalisée")
                }
            }
        } catch (e: Exception) {
            KighmuLogger.error(TAG, "JSON cleanup error: ${e.message}")
        }
        KighmuLogger.info(TAG, "Xray config prête (${jsonConfig.length} chars)")
        val fileName = if (instanceId == 0) "xray_config.json" else "xray_config_$instanceId.json"
        val file = File(context.filesDir, fileName)
        file.writeText(jsonConfig)
        return file
    }
    private fun buildXrayConfigFromFields(xc: com.kighmu.vpn.models.XrayConfig): String {
        // Optimisation StreamSettings: Mux activé pour la stabilité du flux streaming
        val streamSettings = when (xc.transport) {
            "ws" -> """
                "streamSettings": {
                    "network": "ws",
                    "security": "${if (xc.tls) "tls" else "none"}",
                    "wsSettings": { "path": "${xc.wsPath}", "headers": { "Host": "${xc.wsHost}" } } // 'Host' dans 'headers' est déprécié, mais Xray ne supporte pas encore 'host' indépendant ici,
                    "tlsSettings": { "serverName": "${xc.sni}" } // allowInsecure est déprécié, utiliser pinnedPeerCertSha256 et verifyPeerCertByName,
                    "sockopt": { "tcpFastOpen": true, "mark": 255 }
                }
            """.trimIndent()
            "grpc" -> """
                "streamSettings": {
                    "network": "grpc",
                    "security": "${if (xc.tls) "tls" else "none"}",
                    "grpcSettings": { "serviceName": "${xc.wsPath}" },
                    "tlsSettings": { "serverName": "${xc.sni}" } // allowInsecure est déprécié, utiliser pinnedPeerCertSha256 et verifyPeerCertByName,
                    "sockopt": { "tcpFastOpen": true, "mark": 255 }
                }
            """.trimIndent()
            else -> """ "streamSettings": { "sockopt": { "tcpFastOpen": true, "mark": 255 } } """
        }

        val outbound = when (xc.protocol) {
            "vmess" -> """{ "protocol": "vmess", "settings": { "vnext": [{ "address": "${xc.serverAddress}", "port": ${xc.serverPort}, "users": [{ "id": "${xc.uuid}", "alterId": 0, "security": "${xc.encryption}" }] }] }, $streamSettings, "mux": { "enabled": true, "concurrency": 8 } }"""
            "vless" -> """{ "protocol": "vless", "settings": { "vnext": [{ "address": "${xc.serverAddress}", "port": ${xc.serverPort}, "users": [{ "id": "${xc.uuid}", "encryption": "none" }] }] }, $streamSettings, "mux": { "enabled": true, "concurrency": 8 } }"""
            "trojan" -> """{ "protocol": "trojan", "settings": { "servers": [{ "address": "${xc.serverAddress}", "port": ${xc.serverPort}, "password": "${xc.uuid}" }] }, $streamSettings, "mux": { "enabled": true, "concurrency": 8 } }"""
            else -> """{ "protocol": "${xc.protocol}", "settings": {} }"""
        }

        // Optimisation Inbound: Sniffing activé pour un meilleur routage DNS/HTTP
        val inbounds = """
            [
                { 
                    "port": $LOCAL_SOCKS_PORT, 
                    "protocol": "socks", 
                    "settings": { "udp": true, "auth": "noauth" },
                    "sniffing": { "enabled": true, "destOverride": ["http", "tls"] }
                },
                { 
                    "port": $LOCAL_HTTP_PORT, 
                    "protocol": "http",
                    "sniffing": { "enabled": true, "destOverride": ["http", "tls"] }
                }
            ]
        """.trimIndent()

        return """{ 
            "log": { "loglevel": "warning" }, 
            "inbounds": $inbounds, 
            "outbounds": [$outbound, { "protocol": "freedom", "tag": "direct" }], 
            "routing": { "domainStrategy": "AsIs", "rules": [{ "type": "field", "outboundTag": "direct", "domain": ["geosite:google"] }] } 
        }"""
    }

    private fun extractXrayBinary(): File? {
        // Chercher libxray.so dans nativeLibraryDir (jniLibs)
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val libxray = File(nativeDir, "libxray.so")
        if (libxray.exists()) {
            libxray.setExecutable(true)
            KighmuLogger.info(TAG, "Xray binary trouve: ${libxray.absolutePath}")
            return libxray
        }
        // Fallback assets
        val abi = android.os.Build.SUPPORTED_ABIS[0]
        val target = File(context.filesDir, "xray")
        return try {
            context.assets.open("xray/$abi/xray").use { inp ->
                target.outputStream().use { out -> inp.copyTo(out) }
            }
            target.setExecutable(true)
            target
        } catch (e: Exception) {
            KighmuLogger.error(TAG, "Xray binary introuvable: ${e.message}")
            null
        }
    }

    private fun startXrayProcess(binary: File, configFile: File) {
        val cmd = arrayOf(binary.absolutePath, "run", "-c", configFile.absolutePath)
        xrayProcess = Runtime.getRuntime().exec(cmd)
        val proc = xrayProcess!!
        // Thread stderr Xray - filtré
        Thread {
            try {
                proc.errorStream.bufferedReader().forEachLine { line ->
                    if (line.length > 500) return@forEachLine
                    val skip = line.contains("accepted") || line.contains("begin stream")
                        || line.contains("Reading config") || line.contains("from tcp")
                        || line.contains("from udp") || line.contains("tunneling")
                        || line.contains("proxy") || line.contains("dial")
                        || line.contains("connection") || line.contains("127.0.0.1")
                        || line.isBlank()
                    if (!skip) KighmuLogger.error(TAG, "[xray] ${line.take(200)}")
                }
            } catch (_: Exception) {}
        }.start()
        // Thread stdout Xray - filtré
        Thread {
            try {
                proc.inputStream.bufferedReader().forEachLine { line ->
                    if (line.length > 500) return@forEachLine
                    val skip = line.contains("accepted") || line.contains("begin stream")
                        || line.contains("Reading config") || line.contains("from tcp")
                        || line.contains("from udp") || line.contains("tunneling")
                        || line.contains("proxy") || line.contains("dial")
                        || line.contains("connection") || line.contains("127.0.0.1")
                        || line.isBlank()
                    if (!skip) KighmuLogger.info(TAG, "[xray] ${line.take(200)}")
                }
            } catch (_: Exception) {}
        }.start()
        KighmuLogger.info(TAG, "Xray process started")
    }

    private fun startFallbackProxy() {
        engineScope.launch {
            val server = ServerSocket(LOCAL_SOCKS_PORT)
            while (running) {
                try {
                    val client = server.accept()
                    launch { handleDirectSocks(client) }
                } catch (e: Exception) { if (running) KighmuLogger.error(TAG, e.message ?: "") }
            }
            server.close()
        }
    }

    private suspend fun handleDirectSocks(client: Socket) = withContext(Dispatchers.IO) {
        try {
            val inp = DataInputStream(client.getInputStream())
            val out = DataOutputStream(client.getOutputStream())
            if (inp.read() != 5) { client.close(); return@withContext }
            val nm = inp.read(); inp.skipBytes(nm)
            out.write(byteArrayOf(5, 0)); out.flush()
            inp.read(); inp.read(); inp.read()
            val atyp = inp.read()
            val host = when (atyp) {
                1 -> { val b = ByteArray(4); inp.readFully(b); InetAddress.getByAddress(b).hostAddress!! }
                3 -> { val l = inp.read(); val b = ByteArray(l); inp.readFully(b); String(b) }
                4 -> { val b = ByteArray(16); inp.readFully(b); InetAddress.getByAddress(b).hostAddress!! }
                else -> { client.close(); return@withContext }
            }
            val port = (inp.read() shl 8) or inp.read()
            out.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0)); out.flush()
            val server = Socket(host, port)
            val buf = ByteArray(8192)
            val j1 = launch { try { val i = client.getInputStream(); val o = server.getOutputStream(); var l: Int; while (i.read(buf).also { l = it } > 0) { o.write(buf, 0, l); o.flush() } } catch (_: Exception) {} }
            val j2 = launch { try { val i = server.getInputStream(); val o = client.getOutputStream(); var l: Int; while (i.read(buf).also { l = it } > 0) { o.write(buf, 0, l); o.flush() } } catch (_: Exception) {} }
            j1.join(); j2.cancel()
            server.close()
        } catch (_: Exception) { } finally { client.close() }
    }


    fun startTun2SocksOnPort(fd: Int, port: Int) {
        startTun2SocksInternal(fd, port)
    }

    override fun startTun2Socks(fd: Int) {
        startTun2SocksInternal(fd, LOCAL_SOCKS_PORT)
    }

    private fun startTun2SocksInternal(fd: Int, targetPort: Int) {
        KighmuLogger.info(TAG, "XrayEngine startTun2Socks fd=$fd port=$targetPort")
        engineScope.launch(Dispatchers.IO) {
            try {
                // 1. Priorité 1 : Tun2Socks JNI (Standard SSH SlowDNS)
                if (com.kighmu.vpn.engines.Tun2Socks.isAvailable) {
                    KighmuLogger.info(TAG, "Utilisation de Tun2Socks JNI (Standard SSH) fd=$fd port=$targetPort")
                    val t = Thread {
                        val result = com.kighmu.vpn.engines.Tun2Socks.runTun2Socks(
                            fd, 1500, "10.0.0.2", "255.255.255.0",
                            "127.0.0.1:$targetPort", "127.0.0.1:7300",
                            false, 3
                        )
                        KighmuLogger.info(TAG, "Tun2Socks JNI terminé: $result")
                    }
                    t.isDaemon = true
                    t.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, _ -> }
                    t.start()
                    KighmuLogger.info(TAG, "fd $fd routé via Tun2Socks JNI")
                    return@launch
                }

                // 2. Priorité 2 : HevTun2Socks JNI (Fallback JNI)
                com.kighmu.vpn.engines.HevTun2Socks.init()
                if (com.kighmu.vpn.engines.HevTun2Socks.isAvailable) {
                    KighmuLogger.info(TAG, "Utilisation de HevTun2Socks JNI fd=$fd port=$targetPort")
                    val t = Thread {
                        try {

                            vpnService?.let { com.kighmu.vpn.engines.HevTun2Socks.start(context, fd, targetPort, it, 1500) } // vpnService.protect(fd) a été supprimé de HevTun2Socks.kt
                            KighmuLogger.info(TAG, "HevTun2Socks JNI démarré")
                        } catch (e: Exception) {
                            KighmuLogger.error(TAG, "Erreur HevTun2Socks JNI: ${e.message}")
                        }
                    }
                    t.isDaemon = true
                    t.start()
                    KighmuLogger.info(TAG, "fd $fd routé via HevTun2Socks JNI")
                    return@launch
                }

                // 3. Fallback : Relay Kotlin (Dernier recours)
                KighmuLogger.warning(TAG, "Aucun moteur JNI disponible - Utilisation du Relay Kotlin")
                val pfd = android.os.ParcelFileDescriptor.fromFd(fd)
                val relay = com.kighmu.vpn.vpn.Tun2SocksRelay(pfd.fileDescriptor, "127.0.0.1", targetPort)
                relay.start()
            } catch (e: Exception) {
                KighmuLogger.error(TAG, "Erreur critique startTun2Socks: ${e.message}")
            }
        }
    }

    override suspend fun stop() {
        running = false
        KighmuLogger.info(TAG, "Arrêt forcé de Xray...")
        
        try {
            xrayProcess?.let { p ->
                p.inputStream?.close()
                p.errorStream?.close()
                p.outputStream?.close()
                p.destroyForcibly()
            }
        } catch (_: Exception) {}
        
        xrayProcess = null
        _socksPort = 0
        dnsttProxyPort = 0
        
        // Commande de secours pour libérer le port SOCKS si nécessaire
        try {
            Runtime.getRuntime().exec(arrayOf("sh", "-c", "fuser -k 10808/tcp")).waitFor()
        } catch (_: Exception) {}
        
        engineScope.cancel()
    }

    override suspend fun sendData(data: ByteArray, length: Int) {}
    override suspend fun receiveData(): ByteArray? = receiveQueue.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS)
    override fun isRunning() = running
}

// ─────────────────────────────────────────────────────────────────────────────
// Mode 6: Xray + SlowDNS
// ─────────────────────────────────────────────────────────────────────────────

class XraySlowDnsEngine(
    private val config: KighmuConfig,
    private val context: Context
) : TunnelEngine {

    private val dnsttEngine = SlowDnsEngine(config, context)
    private val xray = XrayEngine(config, context)
    private val TAG = "XraySlowDns"

    override suspend fun start(): Int {
        KighmuLogger.info(TAG, "Démarrage dnstt (sans SSH)...")
        val dnsttPort = dnsttEngine.startDnsttOnly()
        KighmuLogger.info(TAG, "dnstt prêt sur port $dnsttPort - démarrage Xray")
        xray.dnsttProxyPort = dnsttPort
        return xray.start()
    }

    override fun startTun2Socks(fd: Int) = xray.startTun2Socks(fd)
    override suspend fun stop() { xray.dnsttProxyPort = 0; xray.stop(); dnsttEngine.stop() }
    override suspend fun sendData(data: ByteArray, length: Int) = xray.sendData(data, length)
    override suspend fun receiveData(): ByteArray? = xray.receiveData()
    override fun isRunning() = xray.isRunning()
}

// ─────────────────────────────────────────────────────────────────────────────
// Mode 7: Hysteria Engine
// ─────────────────────────────────────────────────────────────────────────────
