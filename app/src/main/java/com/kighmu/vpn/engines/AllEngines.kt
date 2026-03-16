package com.kighmu.vpn.engines

import com.kighmu.vpn.models.XrayConfig
import android.content.Context
import com.kighmu.vpn.models.KighmuConfig
import com.kighmu.vpn.utils.KighmuLogger
import com.trilead.ssh2.Connection
import kotlinx.coroutines.*
import okhttp3.*
import okio.ByteString
import java.io.*
import java.net.*
import java.security.SecureRandom
import java.util.concurrent.LinkedBlockingQueue
import javax.net.ssl.*

// ─────────────────────────────────────────────────────────────────────────────
// Mode 3: SSH WebSocket Engine
// ─────────────────────────────────────────────────────────────────────────────

class SshWebSocketEngine(
    private val config: KighmuConfig,
    private val context: Context
) : TunnelEngine {

    companion object {
        const val TAG = "SshWebSocketEngine"
        fun getFreePort(): Int = try { java.net.ServerSocket(0).use { it.localPort } } catch (_: Exception) { 10802 }
    }

    private var _socksPort: Int = 0
    private val LOCAL_SOCKS_PORT: Int get() {
        if (_socksPort == 0) _socksPort = Companion.getFreePort()
        return _socksPort
    }
    private var running = false
    private var sshConnection: Connection? = null
    private var wsSocket: WebSocket? = null
    private val engineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val receiveQueue = LinkedBlockingQueue<ByteArray>(1000)

    private val wsConfig get() = config.sshWebSocket
    private val sshConfig get() = config.sshCredentials

    override suspend fun start(): Int {
        running = true
        KighmuLogger.info(TAG, "Starting SSH WebSocket engine")
        connectWebSocket()
        startSshTunnel()
        return LOCAL_SOCKS_PORT
    }

    private suspend fun connectWebSocket(): Unit = withContext(Dispatchers.IO) {
        val scheme = if (wsConfig.useWss) "wss" else "ws"
        val url = "$scheme://${wsConfig.wsHost}:${wsConfig.wsPort}${wsConfig.wsPath}"
        KighmuLogger.info(TAG, "Connecting WebSocket: $url")

        val clientBuilder = OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)

        if (wsConfig.useWss) {
            val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
            })
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, trustAll, SecureRandom())
            clientBuilder.sslSocketFactory(sslContext.socketFactory, trustAll[0] as X509TrustManager)
            clientBuilder.hostnameVerifier { _, _ -> true }
        }

        val requestBuilder = Request.Builder().url(url)
        wsConfig.wsHeaders.forEach { (k, v) -> requestBuilder.addHeader(k, v) }

        val client = clientBuilder.build()
        val request = requestBuilder.build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                KighmuLogger.info(TAG, "WebSocket connected")
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                receiveQueue.offer(bytes.toByteArray())
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                KighmuLogger.error(TAG, "WebSocket error: ${t.message}")
                if (running) engineScope.launch { connectWebSocket() }
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                KighmuLogger.info(TAG, "WebSocket closed: $reason")
            }
        }

        wsSocket = client.newWebSocket(request, listener)
        delay(1000)
    }

    private fun startSshTunnel() {
        engineScope.launch {
            try {
                val proxySocket = createWebSocketProxySocket()
                val conn = Connection(sshConfig.host, sshConfig.port)
                conn.connect(null, 15000, 15000)
                val authenticated = conn.authenticateWithPassword(sshConfig.username, sshConfig.password)
                if (!authenticated) throw Exception("SSH auth echoue")
                conn.createLocalPortForwarder(LOCAL_SOCKS_PORT, "127.0.0.1", LOCAL_SOCKS_PORT + 1)
                sshConnection = conn
                KighmuLogger.info(TAG, "SSH WebSocket tunnel established, SOCKS on $LOCAL_SOCKS_PORT")
            } catch (e: Exception) {
                KighmuLogger.error(TAG, "SSH WebSocket failed: ${e.message}")
            }
        }
    }

    private fun createWebSocketProxySocket(): Socket {
        val serverSocket = ServerSocket(0)
        val port = serverSocket.localPort

        engineScope.launch(Dispatchers.IO) {
            val conn = serverSocket.accept()
            serverSocket.close()

            launch {
                val buf = ByteArray(4096)
                val inp = conn.getInputStream()
                while (running) {
                    val len = inp.read(buf)
                    if (len <= 0) break
                    wsSocket?.send(ByteString.of(*buf.copyOf(len)))
                }
            }

            launch {
                val out = conn.getOutputStream()
                while (running) {
                    val data = receiveQueue.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                    out.write(data)
                    out.flush()
                }
            }
        }

        return Socket("127.0.0.1", port)
    }


    override fun startTun2Socks(fd: Int) {
        engineScope.launch(Dispatchers.IO) {
            try {
                val bin = File(context.applicationInfo.nativeLibraryDir, "libtun2socks.so")
                if (!bin.exists()) { KighmuLogger.error(TAG, "libtun2socks.so introuvable"); return@launch }
                bin.setExecutable(true)
                val sockPath = "${context.cacheDir.absolutePath}/tun2socks_ws.sock"
                File(sockPath).delete()
                val cmd = listOf(bin.absolutePath, "--sock-path", sockPath, "--tunmtu", "1500", "--netif-ipaddr", "10.0.0.2", "--netif-netmask", "255.255.255.0", "--socks-server-addr", "127.0.0.1:$LOCAL_SOCKS_PORT", "--enable-udprelay", "--loglevel", "4")
                val proc = Runtime.getRuntime().exec(cmd.toTypedArray())
                Thread { try { proc.errorStream.bufferedReader().forEachLine { KighmuLogger.error(TAG, "[tun2socks] $it") } } catch (_: Exception) {} }.start()
                delay(500)
                val localSocket = android.net.LocalSocket()
                localSocket.connect(android.net.LocalSocketAddress(sockPath, android.net.LocalSocketAddress.Namespace.FILESYSTEM))
                val pfd = android.os.ParcelFileDescriptor.fromFd(fd)
                localSocket.setFileDescriptorsForSend(arrayOf(pfd.fileDescriptor))
                localSocket.outputStream.write(1); localSocket.outputStream.flush(); localSocket.close()
                KighmuLogger.info(TAG, "SshWsEngine fd $fd envoye via sock-path")
            } catch (e: Exception) { KighmuLogger.error(TAG, "tun2socks error: ${e.message}") }
        }
    }
    override suspend fun stop() {
        running = false
        try { sshConnection?.close() } catch (_: Exception) {}
        wsSocket?.close(1000, "Stopped")
        engineScope.cancel()
    }

    override suspend fun sendData(data: ByteArray, length: Int) {}
    override suspend fun receiveData(): ByteArray? = receiveQueue.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS)
    override fun isRunning() = running
}

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
                Thread {
                    try {
                        val client = bridge.accept()
                        bridge.close()
                        val buf = ByteArray(8192)
                        val t1 = Thread {
                            try { val i = client.getInputStream(); val o = sslSocket.outputStream; var n: Int; while (i.read(buf).also { n = it } > 0) { o.write(buf, 0, n); o.flush() } } catch (_: Exception) {}
                        }
                        val t2 = Thread {
                            try { val i = sslSocket.inputStream; val o = client.getOutputStream(); var n: Int; while (i.read(buf).also { n = it } > 0) { o.write(buf, 0, n); o.flush() } } catch (_: Exception) {}
                        }
                        t1.start(); t2.start(); t1.join()
                    } catch (_: Exception) {}
                }.start()
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
        engineScope.launch(Dispatchers.IO) {
            try {
                val bin = File(context.applicationInfo.nativeLibraryDir, "libtun2socks.so")
                if (!bin.exists()) { KighmuLogger.error(TAG, "libtun2socks.so introuvable"); return@launch }
                bin.setExecutable(true)
                val sockPath = "${context.cacheDir.absolutePath}/tun2socks_ssl.sock"
                File(sockPath).delete()
                val cmd = listOf(bin.absolutePath, "--sock-path", sockPath, "--tunmtu", "1500", "--netif-ipaddr", "10.0.0.2", "--netif-netmask", "255.255.255.0", "--socks-server-addr", "127.0.0.1:$LOCAL_SOCKS_PORT", "--enable-udprelay", "--loglevel", "4")
                val proc = Runtime.getRuntime().exec(cmd.toTypedArray())
                Thread { try { proc.errorStream.bufferedReader().forEachLine { KighmuLogger.error(TAG, "[tun2socks] $it") } } catch (_: Exception) {} }.start()
                delay(500)
                val localSocket = android.net.LocalSocket()
                localSocket.connect(android.net.LocalSocketAddress(sockPath, android.net.LocalSocketAddress.Namespace.FILESYSTEM))
                val pfd = android.os.ParcelFileDescriptor.fromFd(fd)
                localSocket.setFileDescriptorsForSend(arrayOf(pfd.fileDescriptor))
                localSocket.outputStream.write(1); localSocket.outputStream.flush(); localSocket.close()
                KighmuLogger.info(TAG, "SshSslEngine fd $fd envoye via sock-path")
            } catch (e: Exception) { KighmuLogger.error(TAG, "tun2socks error: ${e.message}") }
        }
    }
    override suspend fun stop() {
        running = false
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
    var dnsttProxyPort: Int = 0  // Si > 0, Xray route via dnstt sur ce port
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
        var jsonConfig = if (xrayConfig.jsonConfig.isNotBlank() &&
            xrayConfig.jsonConfig != XrayConfig.defaultXrayConfig) {
            xrayConfig.jsonConfig
        } else {
            buildXrayConfigFromFields(xrayConfig)
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
        val file = File(context.filesDir, "xray_config.json")
        file.writeText(jsonConfig)
        return file
    }
    private fun buildXrayConfigFromFields(xc: com.kighmu.vpn.models.XrayConfig): String {
        val transport = when (xc.transport) {
            "ws" -> """
                "streamSettings": {
                    "network": "ws",
                    "security": "${if (xc.tls) "tls" else "none"}",
                    "wsSettings": { "path": "${xc.wsPath}", "headers": { "Host": "${xc.wsHost}" } },
                    "tlsSettings": { "serverName": "${xc.sni}", "allowInsecure": ${xc.allowInsecure} }
                }
            """.trimIndent()
            "grpc" -> """
                "streamSettings": {
                    "network": "grpc",
                    "security": "${if (xc.tls) "tls" else "none"}",
                    "grpcSettings": { "serviceName": "${xc.wsPath}" },
                    "tlsSettings": { "serverName": "${xc.sni}", "allowInsecure": ${xc.allowInsecure} }
                }
            """.trimIndent()
            else -> ""
        }

        val outbound = when (xc.protocol) {
            "vmess" -> """{ "protocol": "vmess", "settings": { "vnext": [{ "address": "${xc.serverAddress}", "port": ${xc.serverPort}, "users": [{ "id": "${xc.uuid}", "alterId": 0, "security": "${xc.encryption}" }] }] }, $transport }"""
            "vless" -> """{ "protocol": "vless", "settings": { "vnext": [{ "address": "${xc.serverAddress}", "port": ${xc.serverPort}, "users": [{ "id": "${xc.uuid}", "encryption": "none" }] }] }, $transport }"""
            "trojan" -> """{ "protocol": "trojan", "settings": { "servers": [{ "address": "${xc.serverAddress}", "port": ${xc.serverPort}, "password": "${xc.uuid}" }] }, $transport }"""
            else -> """{ "protocol": "${xc.protocol}", "settings": {} }"""
        }

        return """{ "log": { "loglevel": "warning" }, "inbounds": [{ "port": $LOCAL_SOCKS_PORT, "protocol": "socks", "settings": { "udp": true } }, { "port": $LOCAL_HTTP_PORT, "protocol": "http" }], "outbounds": [$outbound], "routing": { "rules": [] } }"""
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
        // Thread séparé pour stderr - survit au cancel du scope
        Thread {
            try {
                val sb = StringBuilder()
                val es = proc.errorStream
                while (true) {
                    val b = es.read()
                    if (b == -1) break
                    if (b == '\n'.code) {
                        if (sb.isNotEmpty()) {
                            val skip = sb.contains("accepted") || sb.contains("begin stream") || sb.contains("end stream") || sb.contains(" from ")
                            if (!skip) KighmuLogger.error(TAG, "[xray] $sb")
                        }
                        sb.clear()
                    } else if (b != '\r'.code) sb.append(b.toChar())
                }
            } catch (_: Exception) {}
        }.start()
        Thread {
            try {
                val sb = StringBuilder()
                val is2 = proc.inputStream
                while (true) {
                    val b = is2.read()
                    if (b == -1) break
                    if (b == '\n'.code) {
                        if (sb.isNotEmpty()) KighmuLogger.info(TAG, "[xray] $sb")
                        if (sb.isNotEmpty()) {
                            val skip = sb.contains("accepted") || sb.contains("begin stream") || sb.contains("end stream") || sb.contains(" from ")
                            if (!skip) KighmuLogger.info(TAG, "[xray] $sb")
                        }
                    } else if (b != '\r'.code) sb.append(b.toChar())
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


    override fun startTun2Socks(fd: Int) {
        val socksPort = LOCAL_SOCKS_PORT
        KighmuLogger.info(TAG, "XrayEngine startTun2Socks fd=$fd port=$socksPort")
        engineScope.launch(Dispatchers.IO) {
            try {
                val bin = File(context.applicationInfo.nativeLibraryDir, "libtun2socks.so")
                if (!bin.exists()) { KighmuLogger.error(TAG, "libtun2socks.so introuvable"); return@launch }
                bin.setExecutable(true)
                val sockPath = "${context.cacheDir.absolutePath}/tun2socks_xray.sock"
                File(sockPath).delete()
                val cmd = listOf(
                    bin.absolutePath,
                    "--sock-path", sockPath,
                    "--tunmtu", "1500",
                    "--netif-ipaddr", "10.0.0.2",
                    "--netif-netmask", "255.255.255.0",
                    "--socks-server-addr", "127.0.0.1:$socksPort",
                    "--enable-udprelay",
                    "--loglevel", "4"
                )
                KighmuLogger.info(TAG, "tun2socks cmd: ${cmd.joinToString(" ")}")
                val proc = Runtime.getRuntime().exec(cmd.toTypedArray())
                Thread {
                    try { proc.errorStream.bufferedReader().forEachLine { KighmuLogger.error(TAG, "[tun2socks] $it") } } catch (_: Exception) {}
                }.start()
                delay(500)
                try {
                    val localSocket = android.net.LocalSocket()
                    localSocket.connect(android.net.LocalSocketAddress(sockPath, android.net.LocalSocketAddress.Namespace.FILESYSTEM))
                    val pfd = android.os.ParcelFileDescriptor.fromFd(fd)
                    localSocket.setFileDescriptorsForSend(arrayOf(pfd.fileDescriptor))
                    localSocket.outputStream.write(1)
                    localSocket.outputStream.flush()
                    localSocket.close()
                    KighmuLogger.info(TAG, "fd $fd envoye via sock-path")
                } catch (e: Exception) {
                    KighmuLogger.error(TAG, "sock-path error: ${e.message}")
                }
                try { proc.inputStream.bufferedReader().forEachLine { KighmuLogger.info(TAG, "[tun2socks] $it") } } catch (_: Exception) {}
            } catch (e: Exception) {
                KighmuLogger.error(TAG, "tun2socks error: ${e.message}")
            }
        }
    }
    override suspend fun stop() {
        running = false
        _socksPort = 0
        dnsttProxyPort = 0
        _socksPort = 0  // Reset port pour prochain démarrage
        xrayProcess?.destroy()
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

class HysteriaEngine(
    private val config: KighmuConfig,
    private val context: Context
) : TunnelEngine {

    companion object {
        const val TAG = "HysteriaEngine"
        const val LOCAL_HTTP_PORT = 10821
        fun getFreePort(): Int = try { java.net.ServerSocket(0).use { it.localPort } } catch (_: Exception) { 10820 }
    }

    private var _socksPort: Int = 0
    private val LOCAL_SOCKS_PORT: Int get() {
        if (_socksPort == 0) _socksPort = Companion.getFreePort()
        return _socksPort
    }

    private var running = false
    private var hysteriaProcess: Process? = null
    private val engineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val receiveQueue = LinkedBlockingQueue<ByteArray>(1000)
    private val hConfig get() = config.hysteria

    override suspend fun start(): Int {
        running = true
        KighmuLogger.info(TAG, "Starting Hysteria: ${hConfig.serverAddress}")
        withContext(Dispatchers.IO) {
            val configFile = writeHysteriaConfig()
            val binary = extractHysteriaBinary()
            if (binary != null) startHysteriaProcess(binary, configFile)
            else KighmuLogger.error(TAG, "Hysteria binary not available")
        }
        return LOCAL_SOCKS_PORT
    }

    private fun writeHysteriaConfig(): File {
        val file = File(context.filesDir, "hysteria_config.json")
        // Format exact Hysteria v1
        val config = """{
  "server": "${hConfig.serverAddress}",
  "obfs": "${hConfig.obfsPassword}",
  "auth_str": "${hConfig.authPassword}",
  "up_mbps": ${hConfig.uploadMbps},
  "down_mbps": ${hConfig.downloadMbps},
  "retry": 3,
  "retry_interval": 1,
  "socks5": {
    "listen": "127.0.0.1:$LOCAL_SOCKS_PORT"
  },
  "insecure": true
}"""
        KighmuLogger.info(TAG, "Hysteria config ecrite")
        file.writeText(config)
        return file
    }

    private fun extractHysteriaBinary(): File? {
        // Chercher libhysteria.so dans nativeLibraryDir
        val bin = File(context.applicationInfo.nativeLibraryDir, "libhysteria.so")
        if (bin.exists()) {
            bin.setExecutable(true)
            KighmuLogger.info(TAG, "Hysteria binary: ${bin.absolutePath}")
            return bin
        }
        KighmuLogger.error(TAG, "libhysteria.so introuvable dans ${context.applicationInfo.nativeLibraryDir}")
        return null
    }

    private fun startHysteriaProcess(binary: File, configFile: File) {
        val cmd = arrayOf(binary.absolutePath, "client", "--config", configFile.absolutePath)
        hysteriaProcess = Runtime.getRuntime().exec(cmd)
        engineScope.launch { hysteriaProcess?.inputStream?.bufferedReader()?.forEachLine { KighmuLogger.info(TAG, "[hysteria] $it") } }
        engineScope.launch { hysteriaProcess?.errorStream?.bufferedReader()?.forEachLine { KighmuLogger.error(TAG, "[hysteria err] $it") } }
        KighmuLogger.info(TAG, "Hysteria started, SOCKS5 on $LOCAL_SOCKS_PORT")
        // Attendre que Hysteria soit prêt
        var ready = false
        repeat(20) {
            if (!ready) try {
                java.net.Socket().use { s ->
                    s.connect(java.net.InetSocketAddress("127.0.0.1", LOCAL_SOCKS_PORT), 200)
                    ready = true
                    KighmuLogger.info(TAG, "Hysteria SOCKS5 pret sur port $LOCAL_SOCKS_PORT")
                }
            } catch (_: Exception) { Thread.sleep(500) }
        }
        if (!ready) throw Exception("Hysteria n'a pas démarré dans les temps")
    }

    override fun startTun2Socks(fd: Int) {
        val socksPort = LOCAL_SOCKS_PORT
        engineScope.launch(Dispatchers.IO) {
            try {
                val bin = File(context.applicationInfo.nativeLibraryDir, "libtun2socks.so")
                if (!bin.exists()) { KighmuLogger.error(TAG, "libtun2socks.so introuvable"); return@launch }
                bin.setExecutable(true)
                val sockPath = "${context.cacheDir.absolutePath}/tun2socks_hysteria.sock"
                File(sockPath).delete()
                val cmd = listOf(bin.absolutePath, "--sock-path", sockPath, "--tunmtu", "1500",
                    "--netif-ipaddr", "10.0.0.2", "--netif-netmask", "255.255.255.0",
                    "--socks-server-addr", "127.0.0.1:$socksPort", "--enable-udprelay", "--loglevel", "4")
                val proc = Runtime.getRuntime().exec(cmd.toTypedArray())
                Thread { try { proc.errorStream.bufferedReader().forEachLine { KighmuLogger.error(TAG, "[tun2socks] $it") } } catch (_: Exception) {} }.start()
                delay(500)
                val localSocket = android.net.LocalSocket()
                localSocket.connect(android.net.LocalSocketAddress(sockPath, android.net.LocalSocketAddress.Namespace.FILESYSTEM))
                val pfd = android.os.ParcelFileDescriptor.fromFd(fd)
                localSocket.setFileDescriptorsForSend(arrayOf(pfd.fileDescriptor))
                localSocket.outputStream.write(1); localSocket.outputStream.flush(); localSocket.close()
                KighmuLogger.info(TAG, "Hysteria fd $fd envoye via sock-path")
            } catch (e: Exception) { KighmuLogger.error(TAG, "tun2socks error: ${e.message}") }
        }
    }

    override suspend fun stop() {
        running = false
        _socksPort = 0
        hysteriaProcess?.destroy()
        engineScope.cancel()
    }

    override suspend fun sendData(data: ByteArray, length: Int) {}
    override suspend fun receiveData(): ByteArray? = receiveQueue.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS)
    override fun isRunning() = running
}
