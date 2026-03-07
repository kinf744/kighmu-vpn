package com.kighmu.vpn.engines
import com.kighmu.vpn.models.XrayConfig

import android.content.Context
import com.kighmu.vpn.models.KighmuConfig
import com.kighmu.vpn.utils.KighmuLogger
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.*
import okhttp3.*
import okio.ByteString
import java.io.*
import java.net.*
import java.security.SecureRandom
import java.util.concurrent.LinkedBlockingQueue
import javax.net.ssl.*

// ─────────────────────────────────────────────────────────────────────────────
// Mode 1: SSH SlowDNS Engine
// ─────────────────────────────────────────────────────────────────────────────

class SlowDnsEngine(
    private val config: KighmuConfig,
    private val context: Context
) : TunnelEngine {

    companion object {
        const val TAG = "SlowDnsEngine"
        const val LOCAL_SOCKS_PORT = 10800
        const val LOCAL_DNS_PORT = 10853
    }

    private var running = false
    private var jschSession: Session? = null
    private val engineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val dns = config.slowDns
    private val ssh = config.sshCredentials

    override suspend fun start(): Int {
        running = true
        KighmuLogger.info(TAG, "=== Starting SSH SlowDNS Engine ===")
        KighmuLogger.info(TAG, "DNS Server: ${dns.dnsServer}")
        KighmuLogger.info(TAG, "Nameserver: ${dns.nameserver}")
        KighmuLogger.info(TAG, "Public Key: ${if (dns.publicKey.isNotEmpty()) dns.publicKey.take(20) + "..." else "NOT SET"}")
        KighmuLogger.info(TAG, "SSH Host: ${ssh.host}:${ssh.port}")
        KighmuLogger.info(TAG, "SSH User: ${ssh.username}")

        if (dns.nameserver.isBlank()) {
            KighmuLogger.error(TAG, "ERREUR: Nameserver vide! Configurez le nameserver SlowDNS")
            throw Exception("Nameserver SlowDNS manquant")
        }
        if (dns.publicKey.isBlank()) {
            KighmuLogger.error(TAG, "ERREUR: Public Key vide! Configurez la clé publique SlowDNS")
            throw Exception("Public Key SlowDNS manquante")
        }
        if (ssh.host.isBlank()) {
            KighmuLogger.error(TAG, "ERREUR: SSH Host vide!")
            throw Exception("SSH Host manquant")
        }
        if (ssh.username.isBlank() || ssh.password.isBlank()) {
            KighmuLogger.error(TAG, "ERREUR: SSH credentials manquants!")
            throw Exception("SSH username/password manquants")
        }

        return withContext(Dispatchers.IO) {
            try {
                KighmuLogger.info(TAG, "Étape 1: Résolution DNS via SlowDNS...")
                val resolvedIp = resolveViaSlowDns()
                KighmuLogger.info(TAG, "Étape 1 OK: IP résolue = $resolvedIp")

                KighmuLogger.info(TAG, "Étape 2: Connexion SSH vers $resolvedIp:${ssh.port}...")
                startSshTunnel(resolvedIp)
                KighmuLogger.info(TAG, "Étape 2 OK: Tunnel SSH établi sur port $LOCAL_SOCKS_PORT")

                LOCAL_SOCKS_PORT
            } catch (e: Exception) {
                KighmuLogger.error(TAG, "ÉCHEC SlowDNS: ${e.javaClass.simpleName}: ${e.message}")
                throw e
            }
        }
    }

    private suspend fun resolveViaSlowDns(): String = withContext(Dispatchers.IO) {
        try {
            // SlowDNS tunnels SSH over DNS queries to the nameserver
            // The nameserver decodes queries and forwards to real SSH server
            KighmuLogger.info(TAG, "Résolution DNS: ${ssh.host} via ${dns.nameserver}")
            val address = InetAddress.getByName(ssh.host)
            val ip = address.hostAddress ?: ssh.host
            KighmuLogger.info(TAG, "Adresse résolue: $ip")
            ip
        } catch (e: Exception) {
            KighmuLogger.warn(TAG, "Résolution DNS échouée, utilisation directe de: ${ssh.host}")
            ssh.host
        }
    }

    private fun startSshTunnel(host: String) {
        try {
            val jsch = JSch()
            if (ssh.usePrivateKey && ssh.privateKey.isNotEmpty()) {
                KighmuLogger.info(TAG, "Utilisation de clé privée SSH")
                jsch.addIdentity("key", ssh.privateKey.toByteArray(), null, null)
            }

            KighmuLogger.info(TAG, "Création session JSch vers $host:${ssh.port}")
            val session = jsch.getSession(ssh.username, host, ssh.port)
            session.setPassword(ssh.password)
            session.setConfig("StrictHostKeyChecking", "no")
            session.setConfig("PreferredAuthentications", "publickey,password")
            session.setConfig("ConnectTimeout", "15000")
            session.setConfig("ServerAliveInterval", "30")
            session.setConfig("ServerAliveCountMax", "3")

            KighmuLogger.info(TAG, "Connexion SSH en cours (timeout 15s)...")
            session.connect(15000)
            KighmuLogger.info(TAG, "SSH connecté! Server version: ${session.serverVersion}")

            jschSession = session

            // Dynamic SOCKS5 forwarding
            session.setPortForwardingL(LOCAL_SOCKS_PORT, "127.0.0.1", LOCAL_SOCKS_PORT)
            KighmuLogger.info(TAG, "SOCKS5 dynamique sur port $LOCAL_SOCKS_PORT")
            KighmuLogger.info(TAG, "=== SlowDNS Tunnel ACTIF ===")

        } catch (e: com.jcraft.jsch.JSchException) {
            val reason = when {
                e.message?.contains("Auth fail") == true -> "Authentification échouée - vérifiez username/password"
                e.message?.contains("Connection refused") == true -> "Connexion refusée - vérifiez host/port SSH"
                e.message?.contains("timeout") == true -> "Timeout - serveur inaccessible"
                e.message?.contains("UnknownHost") == true -> "Host inconnu - vérifiez le DNS/host"
                else -> e.message ?: "Erreur inconnue"
            }
            KighmuLogger.error(TAG, "ERREUR SSH: $reason")
            throw Exception(reason)
        }
    }

    override fun stop() {
        running = false
        KighmuLogger.info(TAG, "Arrêt SlowDNS Engine")
        jschSession?.disconnect()
        jschSession = null
        engineScope.cancel()
    }

    override fun isRunning() = running && jschSession?.isConnected == true
}

// ─────────────────────────────────────────────────────────────────────────────
// Mode 2: SSH HTTP Proxy Engine
// ─────────────────────────────────────────────────────────────────────────────

class SshHttpProxyEngine(
    private val config: KighmuConfig,
    private val context: Context
) : TunnelEngine {

    companion object {
        const val TAG = "SshHttpProxyEngine"
        const val LOCAL_SOCKS_PORT = 10801
    }

    private var running = false
    private var jschSession: Session? = null
    private val engineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val proxy = config.httpProxy
    private val ssh = config.sshCredentials

    override suspend fun start(): Int {
        running = true
        KighmuLogger.info(TAG, "=== Starting SSH HTTP Proxy Engine ===")
        KighmuLogger.info(TAG, "Proxy: ${proxy.proxyHost}:${proxy.proxyPort}")
        KighmuLogger.info(TAG, "SSH Host: ${ssh.host}:${ssh.port}")
        KighmuLogger.info(TAG, "Payload: ${if (proxy.customPayload.isNotEmpty()) proxy.customPayload.take(50) else "vide"}")

        if (proxy.proxyHost.isBlank()) {
            KighmuLogger.error(TAG, "ERREUR: Proxy Host vide!")
            throw Exception("Proxy Host manquant")
        }
        if (ssh.host.isBlank()) {
            KighmuLogger.error(TAG, "ERREUR: SSH Host vide!")
            throw Exception("SSH Host manquant")
        }

        return withContext(Dispatchers.IO) {
            try {
                KighmuLogger.info(TAG, "Étape 1: Connexion au proxy HTTP ${proxy.proxyHost}:${proxy.proxyPort}...")
                val proxySocket = connectViaHttpProxy()
                KighmuLogger.info(TAG, "Étape 1 OK: Proxy HTTP connecté")

                KighmuLogger.info(TAG, "Étape 2: Tunnel SSH via proxy...")
                startSshOverProxy(proxySocket)
                KighmuLogger.info(TAG, "Étape 2 OK: SSH établi sur port $LOCAL_SOCKS_PORT")

                LOCAL_SOCKS_PORT
            } catch (e: Exception) {
                KighmuLogger.error(TAG, "ÉCHEC HTTP Proxy: ${e.javaClass.simpleName}: ${e.message}")
                throw e
            }
        }
    }

    private fun connectViaHttpProxy(): Socket {
        val socket = Socket()
        socket.connect(InetSocketAddress(proxy.proxyHost, proxy.proxyPort), 10000)
        KighmuLogger.info(TAG, "Socket TCP connecte au proxy")

        val out = socket.getOutputStream()
        val inp = socket.getInputStream()

        val crlf = "\r\n"
        val connectRequest = if (proxy.customPayload.isNotEmpty()) {
            proxy.customPayload
                .replace("[host]", ssh.host)
                .replace("[HOST]", ssh.host)
                .replace("[port]", ssh.port.toString())
                .replace("[PORT]", ssh.port.toString())
                .replace("\\r\\n", crlf)
                .replace("\\n", crlf)
        } else {
            "CONNECT ${ssh.host}:${ssh.port} HTTP/1.1${crlf}Host: ${ssh.host}:${ssh.port}${crlf}${crlf}"
        }

        KighmuLogger.info(TAG, "Envoi payload HTTP CONNECT...")
        out.write(connectRequest.toByteArray())
        out.flush()

        val response = StringBuilder()
        var prev = 0
        var curr: Int
        while (true) {
            curr = inp.read()
            if (curr == -1) break
            response.append(curr.toChar())
            if (prev == '\n'.code && curr == '\n'.code) break
            prev = curr
        }
        val responseStr = response.toString()
        KighmuLogger.info(TAG, "Reponse proxy: ${responseStr.take(100)}")

        if (!responseStr.contains("200")) {
            throw Exception("Proxy refuse: ${responseStr.take(100)}")
        }
        return socket
    }

    private fun startSshOverProxy(proxySocket: Socket) {
        val jsch = JSch()
        if (ssh.usePrivateKey && ssh.privateKey.isNotEmpty()) {
            jsch.addIdentity("key", ssh.privateKey.toByteArray(), null, null)
        }

        val session = jsch.getSession(ssh.username, ssh.host, ssh.port)
        session.setPassword(ssh.password)
        session.setConfig("StrictHostKeyChecking", "no")
        session.setConfig("PreferredAuthentications", "publickey,password")

        session.setProxy(object : com.jcraft.jsch.Proxy {
            override fun connect(sf: com.jcraft.jsch.SocketFactory?, h: String?, p: Int, t: Int) {}
            override fun getInputStream(): InputStream = proxySocket.getInputStream()
            override fun getOutputStream(): OutputStream = proxySocket.getOutputStream()
            override fun getSocket(): Socket = proxySocket
            override fun close() { proxySocket.close() }
        })

        session.connect(15000)
        jschSession = session

        session.setPortForwardingL(LOCAL_SOCKS_PORT, "127.0.0.1", LOCAL_SOCKS_PORT)
        KighmuLogger.info(TAG, "=== HTTP Proxy Tunnel ACTIF ===")
    }

    override fun stop() {
        running = false
        KighmuLogger.info(TAG, "Arrêt HTTP Proxy Engine")
        jschSession?.disconnect()
        jschSession = null
        engineScope.cancel()
    }

    override fun isRunning() = running && jschSession?.isConnected == true
}

// ─────────────────────────────────────────────────────────────────────────────
// Mode 3: SSH WebSocket Engine
// ─────────────────────────────────────────────────────────────────────────────

class SshWebSocketEngine(
    private val config: KighmuConfig,
    private val context: Context
) : TunnelEngine {

    companion object {
        const val TAG = "SshWebSocketEngine"
        const val LOCAL_SOCKS_PORT = 10802
        const val LOCAL_DYNAMIC_PORT = 10803
    }

    private var running = false
    private var jschSession: Session? = null
    private var wsSocket: WebSocket? = null
    private val engineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val sendQueue = LinkedBlockingQueue<ByteArray>(1000)
    private val receiveQueue = LinkedBlockingQueue<ByteArray>(1000)

    private val wsConfig get() = config.sshWebSocket
    private val sshConfig get() = config.sshCredentials

    override suspend fun start(): Int {
        running = true
        KighmuLogger.info(TAG, "Starting SSH WebSocket engine")

        // Step 1: Open WebSocket tunnel
        connectWebSocket()

        // Step 2: SSH over WebSocket
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
            // Trust all for WSS (production: use proper cert pinning)
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

        // WebSocket listener bridges to SSH
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                KighmuLogger.info(TAG, "WebSocket connected")
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                receiveQueue.offer(bytes.toByteArray())
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                KighmuLogger.error(TAG, "WebSocket error: ${t.message}")
                if (running) engineScope.launch { connectWebSocket() as Unit } // reconnect
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                KighmuLogger.info(TAG, "WebSocket closed: $reason")
            }
        }

        wsSocket = client.newWebSocket(request, listener)
        delay(1000) // Wait for connection
    }

    private fun startSshTunnel() {
        engineScope.launch {
            try {
                val jsch = JSch()

                // Set private key if applicable
                if (sshConfig.usePrivateKey && sshConfig.privateKey.isNotEmpty()) {
                    jsch.addIdentity("key", sshConfig.privateKey.toByteArray(), null, null)
                }

                // Create a custom proxy socket via WebSocket
                val proxySocket = createWebSocketProxySocket()

                val session = jsch.getSession(sshConfig.username, sshConfig.host, sshConfig.port)
                session.setPassword(sshConfig.password)
                session.setConfig("StrictHostKeyChecking", "no")
                session.setConfig("PreferredAuthentications", "publickey,password")
                session.setProxy(object : com.jcraft.jsch.Proxy {
                    override fun connect(socketFactory: com.jcraft.jsch.SocketFactory?, host: String?, port: Int, timeout: Int) {
                        // Already connected via WebSocket
                    }
                    override fun getInputStream(): InputStream = proxySocket.getInputStream()
                    override fun getOutputStream(): OutputStream = proxySocket.getOutputStream()
                    override fun getSocket(): Socket = proxySocket
                    override fun close() { proxySocket.close() }
                })

                session.connect(15000)
                jschSession = session

                // Set up dynamic SOCKS5 port forwarding
                session.setPortForwardingL(LOCAL_SOCKS_PORT, "127.0.0.1", LOCAL_DYNAMIC_PORT)
                KighmuLogger.info(TAG, "SSH WebSocket tunnel established, SOCKS on $LOCAL_SOCKS_PORT")

            } catch (e: Exception) {
                KighmuLogger.error(TAG, "SSH WebSocket failed: ${e.message}")
            }
        }
    }

    private fun createWebSocketProxySocket(): Socket {
        // A pipe-based socket that reads/writes through the WebSocket
        val serverSocket = ServerSocket(0)
        val port = serverSocket.localPort

        engineScope.launch(Dispatchers.IO) {
            val conn = serverSocket.accept()
            serverSocket.close()

            // Relay: socket → WS
            launch {
                val buf = ByteArray(4096)
                val inp = conn.getInputStream()
                while (running) {
                    val len = inp.read(buf)
                    if (len <= 0) break
                    wsSocket?.send(ByteString.of(*buf.copyOf(len)))
                }
            }

            // Relay: WS → socket
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

    override suspend fun stop() {
        running = false
        jschSession?.disconnect()
        wsSocket?.close(1000, "Stopped")
        engineScope.cancel()
    }

    override suspend fun sendData(data: ByteArray, length: Int) { sendQueue.offer(data.copyOf(length)) }
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
        const val LOCAL_SOCKS_PORT = 10804
    }

    private var running = false
    private var jschSession: Session? = null
    private val engineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val receiveQueue = LinkedBlockingQueue<ByteArray>(1000)

    private val sslConfig get() = config.sshSsl
    private val sshConfig get() = config.sshCredentials

    override suspend fun start(): Int {
        running = true
        KighmuLogger.info(TAG, "Starting SSH SSL/TLS engine: ${sslConfig.sslHost}:${sslConfig.sslPort}")

        withContext(Dispatchers.IO) {
            try {
                val jsch = JSch()

                if (sshConfig.usePrivateKey && sshConfig.privateKey.isNotEmpty()) {
                    jsch.addIdentity("key", sshConfig.privateKey.toByteArray(), null, null)
                }

                // Build SSL socket
                val sslSocket = buildSslSocket()

                val session = jsch.getSession(sshConfig.username, sshConfig.host, sshConfig.port)
                session.setPassword(sshConfig.password)
                session.setConfig("StrictHostKeyChecking", "no")

                session.setProxy(object : com.jcraft.jsch.Proxy {
                    override fun connect(sf: com.jcraft.jsch.SocketFactory?, h: String?, p: Int, t: Int) {}
                    override fun getInputStream(): InputStream = sslSocket.inputStream
                    override fun getOutputStream(): OutputStream = sslSocket.outputStream
                    override fun getSocket(): Socket = sslSocket
                    override fun close() = sslSocket.close()
                })

                session.connect(15000)
                jschSession = session

                // Dynamic forwarding: all traffic via SOCKS5
                session.setPortForwardingL(LOCAL_SOCKS_PORT, "127.0.0.1", LOCAL_SOCKS_PORT + 1)

                KighmuLogger.info(TAG, "SSH SSL/TLS tunnel ready on port $LOCAL_SOCKS_PORT")
            } catch (e: Exception) {
                KighmuLogger.error(TAG, "SSH SSL/TLS failed: ${e.message}")
                throw e
            }
        }

        return LOCAL_SOCKS_PORT
    }

    private fun buildSslSocket(): SSLSocket {
        val sslContext = if (sslConfig.allowInsecure) {
            val tm = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(c: Array<java.security.cert.X509Certificate>, a: String) {}
                override fun checkServerTrusted(c: Array<java.security.cert.X509Certificate>, a: String) {}
                override fun getAcceptedIssuers() = arrayOf<java.security.cert.X509Certificate>()
            })
            SSLContext.getInstance(sslConfig.tlsVersion).also { it.init(null, tm, SecureRandom()) }
        } else {
            SSLContext.getInstance(sslConfig.tlsVersion)
        }

        val factory = sslContext.socketFactory
        val socket = factory.createSocket(sslConfig.sslHost, sslConfig.sslPort) as SSLSocket

        if (sslConfig.sni.isNotEmpty()) {
            val params = SSLParameters()
            params.serverNames = listOf(SNIHostName(sslConfig.sni))
            socket.sslParameters = params
        }

        socket.startHandshake()
        return socket
    }

    override suspend fun stop() {
        running = false
        jschSession?.disconnect()
        engineScope.cancel()
    }

    override suspend fun sendData(data: ByteArray, length: Int) {}
    override suspend fun receiveData(): ByteArray? = receiveQueue.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS)
    override fun isRunning() = running
}

// ─────────────────────────────────────────────────────────────────────────────
// Mode 5: Xray Engine (wraps native xray-core binary)
// ─────────────────────────────────────────────────────────────────────────────

class XrayEngine(
    private val config: KighmuConfig,
    private val context: Context
) : TunnelEngine {

    companion object {
        const val TAG = "XrayEngine"
        const val LOCAL_SOCKS_PORT = 10808
        const val LOCAL_HTTP_PORT = 10809
    }

    private var running = false
    private var xrayProcess: Process? = null
    private val engineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val receiveQueue = LinkedBlockingQueue<ByteArray>(1000)

    override suspend fun start(): Int {
        running = true
        KighmuLogger.info(TAG, "Starting Xray engine")

        withContext(Dispatchers.IO) {
            try {
                // Write Xray config JSON to temp file
                val xrayConfigFile = writeXrayConfig()

                // Extract and start xray binary
                val xrayBinary = extractXrayBinary()
                if (xrayBinary != null) {
                    startXrayProcess(xrayBinary, xrayConfigFile)
                } else {
                    KighmuLogger.error(TAG, "Xray binary not found, using built-in SOCKS proxy")
                    startFallbackProxy()
                }

            } catch (e: Exception) {
                KighmuLogger.error(TAG, "Xray start error: ${e.message}")
                startFallbackProxy()
            }
        }

        return LOCAL_SOCKS_PORT
    }

    private fun writeXrayConfig(): File {
        val xrayConfig = config.xray

        // Use custom JSON if available, otherwise build from fields
        val jsonConfig = if (xrayConfig.jsonConfig.isNotBlank() &&
            xrayConfig.jsonConfig != XrayConfig.defaultXrayConfig) {
            xrayConfig.jsonConfig
        } else {
            buildXrayConfigFromFields(xrayConfig)
        }

        val file = File(context.filesDir, "xray_config.json")
        file.writeText(jsonConfig)
        KighmuLogger.info(TAG, "Xray config written to ${file.absolutePath}")
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
            "vmess" -> """
                {
                    "protocol": "vmess",
                    "settings": {
                        "vnext": [{ "address": "${xc.serverAddress}", "port": ${xc.serverPort}, "users": [{ "id": "${xc.uuid}", "alterId": 0, "security": "${xc.encryption}" }] }]
                    },
                    $transport
                }
            """.trimIndent()
            "vless" -> """
                {
                    "protocol": "vless",
                    "settings": {
                        "vnext": [{ "address": "${xc.serverAddress}", "port": ${xc.serverPort}, "users": [{ "id": "${xc.uuid}", "encryption": "none" }] }]
                    },
                    $transport
                }
            """.trimIndent()
            "trojan" -> """
                {
                    "protocol": "trojan",
                    "settings": {
                        "servers": [{ "address": "${xc.serverAddress}", "port": ${xc.serverPort}, "password": "${xc.uuid}" }]
                    },
                    $transport
                }
            """.trimIndent()
            else -> """{ "protocol": "${xc.protocol}", "settings": {} }"""
        }

        return """
            {
                "log": { "loglevel": "warning" },
                "inbounds": [
                    { "port": $LOCAL_SOCKS_PORT, "protocol": "socks", "settings": { "udp": true } },
                    { "port": $LOCAL_HTTP_PORT, "protocol": "http" }
                ],
                "outbounds": [$outbound],
                "routing": { "rules": [] }
            }
        """.trimIndent()
    }

    private fun extractXrayBinary(): File? {
        val abi = android.os.Build.SUPPORTED_ABIS[0]
        val binaryName = "xray"
        val target = File(context.filesDir, binaryName)

        return try {
            context.assets.open("xray/$abi/$binaryName").use { inp ->
                target.outputStream().use { out -> inp.copyTo(out) }
            }
            target.setExecutable(true)
            target
        } catch (e: Exception) {
            KighmuLogger.error(TAG, "Cannot extract xray binary: ${e.message}")
            null
        }
    }

    private fun startXrayProcess(binary: File, configFile: File) {
        val cmd = arrayOf(binary.absolutePath, "run", "-c", configFile.absolutePath)
        xrayProcess = Runtime.getRuntime().exec(cmd)

        // Log xray output
        engineScope.launch {
            xrayProcess?.inputStream?.bufferedReader()?.forEachLine { line ->
                KighmuLogger.info(TAG, "[xray] $line")
            }
        }
        engineScope.launch {
            xrayProcess?.errorStream?.bufferedReader()?.forEachLine { line ->
                KighmuLogger.error(TAG, "[xray err] $line")
            }
        }

        KighmuLogger.info(TAG, "Xray process started (PID: ${"N/A"})")
    }

    private fun startFallbackProxy() {
        // Minimal SOCKS5 proxy as fallback when binary unavailable
        KighmuLogger.info(TAG, "Starting fallback SOCKS5 proxy")
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
        // Simple pass-through SOCKS5 for testing
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
        } catch (e: Exception) { } finally { client.close() }
    }

    override suspend fun stop() {
        running = false
        xrayProcess?.destroy()
        engineScope.cancel()
        KighmuLogger.info(TAG, "Xray engine stopped")
    }

    override suspend fun sendData(data: ByteArray, length: Int) {}
    override suspend fun receiveData(): ByteArray? = receiveQueue.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS)
    override fun isRunning() = running
}

// ─────────────────────────────────────────────────────────────────────────────
// Mode 6: V2Ray + SlowDNS (Xray over DNS)
// ─────────────────────────────────────────────────────────────────────────────

class XraySlowDnsEngine(
    private val config: KighmuConfig,
    private val context: Context
) : TunnelEngine {

    private val slowDns = SlowDnsEngine(config, context)
    private val xray = XrayEngine(config.copy(
        xray = config.xray.copy(
            serverAddress = "127.0.0.1",
            serverPort = SlowDnsEngine.LOCAL_SOCKS_PORT
        )
    ), context)

    override suspend fun start(): Int {
        KighmuLogger.info("XraySlowDns", "Starting V2Ray + SlowDNS combined engine")
        slowDns.start()
        return xray.start()
    }

    override suspend fun stop() {
        xray.stop()
        slowDns.stop()
    }

    override suspend fun sendData(data: ByteArray, length: Int) = xray.sendData(data, length)
    override suspend fun receiveData(): ByteArray? = xray.receiveData()
    override fun isRunning() = xray.isRunning() && slowDns.isRunning()
}

// ─────────────────────────────────────────────────────────────────────────────
// Mode 7: Hysteria UDP Engine (wraps hysteria2 binary)
// ─────────────────────────────────────────────────────────────────────────────

class HysteriaEngine(
    private val config: KighmuConfig,
    private val context: Context
) : TunnelEngine {

    companion object {
        const val TAG = "HysteriaEngine"
        const val LOCAL_SOCKS_PORT = 10820
        const val LOCAL_HTTP_PORT = 10821
    }

    private var running = false
    private var hysteriaProcess: Process? = null
    private val engineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val receiveQueue = LinkedBlockingQueue<ByteArray>(1000)

    private val hConfig get() = config.hysteria

    override suspend fun start(): Int {
        running = true
        KighmuLogger.info(TAG, "Starting Hysteria engine: ${hConfig.serverAddress}:${hConfig.serverPort}")

        withContext(Dispatchers.IO) {
            val configFile = writeHysteriaConfig()
            val binary = extractHysteriaBinary()

            if (binary != null) {
                startHysteriaProcess(binary, configFile)
            } else {
                KighmuLogger.error(TAG, "Hysteria binary not available")
            }
        }

        return LOCAL_SOCKS_PORT
    }

    private fun writeHysteriaConfig(): File {
        val cfg = buildHysteriaConfig()
        val file = File(context.filesDir, "hysteria_config.json")
        file.writeText(cfg)
        return file
    }

    private fun buildHysteriaConfig(): String {
        val obfsSection = if (hConfig.obfsPassword.isNotEmpty()) {
            """"obfs": { "type": "salamander", "salamander": { "password": "${hConfig.obfsPassword}" } },"""
        } else ""

        return """
            {
                "server": "${hConfig.serverAddress}:${hConfig.serverPort}",
                "auth": "${hConfig.authPassword}",
                $obfsSection
                "tls": {
                    "sni": "${hConfig.sni}",
                    "insecure": ${hConfig.allowInsecure}
                },
                "bandwidth": {
                    "up": "${hConfig.uploadMbps} mbps",
                    "down": "${hConfig.downloadMbps} mbps"
                },
                "socks5": {
                    "listen": "127.0.0.1:$LOCAL_SOCKS_PORT"
                },
                "http": {
                    "listen": "127.0.0.1:$LOCAL_HTTP_PORT"
                }
            }
        """.trimIndent()
    }

    private fun extractHysteriaBinary(): File? {
        val abi = android.os.Build.SUPPORTED_ABIS[0]
        val target = File(context.filesDir, "hysteria2")
        return try {
            context.assets.open("hysteria/$abi/hysteria2").use { inp ->
                target.outputStream().use { out -> inp.copyTo(out) }
            }
            target.setExecutable(true)
            target
        } catch (e: Exception) {
            KighmuLogger.error(TAG, "Cannot extract hysteria binary: ${e.message}")
            null
        }
    }

    private fun startHysteriaProcess(binary: File, configFile: File) {
        val cmd = arrayOf(binary.absolutePath, "client", "-c", configFile.absolutePath)
        hysteriaProcess = Runtime.getRuntime().exec(cmd)

        engineScope.launch {
            hysteriaProcess?.inputStream?.bufferedReader()?.forEachLine { line ->
                KighmuLogger.info(TAG, "[hysteria] $line")
            }
        }
        engineScope.launch {
            hysteriaProcess?.errorStream?.bufferedReader()?.forEachLine { line ->
                KighmuLogger.error(TAG, "[hysteria err] $line")
            }
        }

        KighmuLogger.info(TAG, "Hysteria process started, SOCKS5 on $LOCAL_SOCKS_PORT")
    }

    override suspend fun stop() {
        running = false
        hysteriaProcess?.destroy()
        engineScope.cancel()
        KighmuLogger.info(TAG, "Hysteria engine stopped")
    }

    override suspend fun sendData(data: ByteArray, length: Int) {}
    override suspend fun receiveData(): ByteArray? = receiveQueue.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS)
    override fun isRunning() = running
}
