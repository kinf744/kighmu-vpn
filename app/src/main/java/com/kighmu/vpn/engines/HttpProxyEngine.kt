package com.kighmu.vpn.engines

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.ParcelFileDescriptor
import com.kighmu.vpn.models.KighmuConfig
import com.kighmu.vpn.utils.KighmuLogger
import com.trilead.ssh2.Connection
import kotlinx.coroutines.*
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

class HttpProxyEngine(
    private val config: KighmuConfig,
    private val context: Context
) : TunnelEngine {

    companion object {
        const val TAG = "HttpProxyEngine"
        fun getFreePort(): Int = try { java.net.ServerSocket(0).use { it.localPort } } catch (_: Exception) { 10801 }
        const val CRLF = "\r\n"
    }
    private var _socksPort: Int = 0
    private val LOCAL_SOCKS_PORT: Int get() {
        if (_socksPort == 0) _socksPort = Companion.getFreePort()
        return _socksPort
    }

    private val MTU = 1500
    private var running = false
    private var sshConnection: Connection? = null
    private var proxySocket: Socket? = null
    private var tun2socksProcess: Process? = null
    private val engineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val proxy get() = config.httpProxy
    private val ssh get() = object {
        val host get() = config.httpProxy.sshHost
        val port get() = config.httpProxy.sshPort
        val username get() = config.httpProxy.sshUser
        val password get() = config.httpProxy.sshPass
    }

    override suspend fun start(): Int = withContext(Dispatchers.IO) {
        running = true
        KighmuLogger.info(TAG, "=== Demarrage HTTP Proxy Engine ===")
        KighmuLogger.info(TAG, "Proxy: ${proxy.proxyHost}:${proxy.proxyPort}")
        KighmuLogger.info(TAG, "SSH: ${ssh.host}:${ssh.port} user=${ssh.username}")

        if (proxy.proxyHost.isBlank()) throw Exception("Proxy Host manquant")
        if (ssh.host.isBlank()) throw Exception("SSH Host manquant")
        if (ssh.username.isBlank()) throw Exception("SSH Username manquant")

        KighmuLogger.info(TAG, "ETAPE 1: Connexion TCP au proxy...")
        val sock = Socket()
        sock.connect(InetSocketAddress(proxy.proxyHost, proxy.proxyPort), 15000)
        sock.soTimeout = 10000
        proxySocket = sock
        KighmuLogger.info(TAG, "TCP connecte au proxy OK")

        val out: OutputStream = sock.getOutputStream()
        val inp: InputStream = sock.getInputStream()

        val rawPayload = if (proxy.customPayload.isNotBlank()) proxy.customPayload
        else "CONNECT [host]:[port] HTTP/1.1[crlf]Host: [host]:[port][crlf]Proxy-Connection: Keep-Alive[crlf][crlf]"

        val payload = rawPayload
            .replace("[host]", ssh.host)
            .replace("[HOST]", ssh.host)
            .replace("[real_host]", ssh.host)
            .replace("[REAL_HOST]", ssh.host)
            .replace("[port]", ssh.port.toString())
            .replace("[PORT]", ssh.port.toString())
            .replace("[proxy_host]", proxy.proxyHost)
            .replace("[proxy_port]", proxy.proxyPort.toString())
            .replace("[crlf]", CRLF)
            .replace("[CRLF]", CRLF)
            .replace("[cr]", "\r")
            .replace("[lf]", "\n")
            .replace("\\r\\n", CRLF)
            .replace("\\r", "\r")
            .replace("\\n", "\n")

        KighmuLogger.info(TAG, "ETAPE 2: Envoi payload...")
        sendPayload(out, payload, rawPayload)
        KighmuLogger.info(TAG, "Payload envoye OK")

        KighmuLogger.info(TAG, "ETAPE 3: Lecture reponse proxy...")
        val isConnect = rawPayload.trimStart().startsWith("CONNECT", ignoreCase = true)

        val firstLine = readHttpLine(inp)
        KighmuLogger.info(TAG, "Proxy reponse: $firstLine")

        val isError = firstLine.contains("400") || firstLine.contains("403") ||
                      firstLine.contains("407") || firstLine.contains("502") ||
                      firstLine.contains("404") || firstLine.contains("500")

        if (isConnect && !firstLine.contains("200") && !firstLine.contains("101")) {
            consumeHeaders(inp)
            throw Exception("Proxy CONNECT refuse: $firstLine")
        }
        if (isError) {
            consumeHeaders(inp)
            throw Exception("Proxy erreur: $firstLine")
        }

        consumeHeaders(inp)
        KighmuLogger.info(TAG, "Headers consommes - tunnel pret pour SSH")

        KighmuLogger.info(TAG, "ETAPE 4: Connexion SSH via socket proxy tunnel...")
        // Creer un ServerSocket local qui expose le socket proxy comme port TCP local
        // Meme principe que SlowDnsEngine avec dnstt
        val bridgeSS = java.net.ServerSocket(0)
        val bridgePort = bridgeSS.localPort
        KighmuLogger.info(TAG, "Bridge local port=$bridgePort")
        val bridgeThread = Thread {
            try {
                val bridgeClient = bridgeSS.accept()
                bridgeSS.close()
                val bIn  = Thread { try { pipe(sock.getInputStream(),      bridgeClient.getOutputStream()) } catch (_: Exception) {} }
                val bOut = Thread { try { pipe(bridgeClient.getInputStream(), sock.getOutputStream())      } catch (_: Exception) {} }
                bIn.isDaemon  = true
                bOut.isDaemon = true
                bIn.start()
                bOut.start()
            } catch (_: Exception) {}
        }
        bridgeThread.isDaemon = true
        bridgeThread.start()

        // Bridge banniere : lit SSH-2.0-xxx puis relaie tout a Trilead
        val bannerProxyPort = run {
            val ss = java.net.ServerSocket(0); ss.reuseAddress = true; val p = ss.localPort; ss.close(); p
        }
        val bannerLatch = java.util.concurrent.CountDownLatch(1)
        var capturedBanner = ""
        Thread {
            try {
                val proxyServer = java.net.ServerSocket(bannerProxyPort)
                bannerLatch.countDown()
                val trileadSock = proxyServer.accept()
                proxyServer.close()
                val realSock = java.net.Socket("127.0.0.1", bridgePort)
                realSock.soTimeout = 5000
                // Lire banniere depuis vrai serveur
                val realIn = realSock.getInputStream()
                val bannerBytes = StringBuilder()
                var b: Int
                while (realIn.read().also { b = it } != -1) {
                    bannerBytes.append(b.toChar())
                    if (bannerBytes.endsWith("\n")) break
                }
                capturedBanner = bannerBytes.toString().trim()
                // Renvoyer la banniere a Trilead
                val trileadOut = trileadSock.getOutputStream()
                trileadOut.write(bannerBytes.toString().toByteArray())
                trileadOut.flush()
                // Relay bidirectionnel
                val t1 = Thread { try { realIn.copyTo(trileadSock.getOutputStream()) } catch (_: Exception) {} }
                val t2 = Thread { try { trileadSock.getInputStream().copyTo(realSock.getOutputStream()) } catch (_: Exception) {} }
                t1.isDaemon = true; t2.isDaemon = true
                t1.start(); t2.start()
            } catch (e: Exception) {
                bannerLatch.countDown()
                KighmuLogger.error(TAG, "BannerProxy error: ${e.message}")
            }
        }.also { it.isDaemon = true }.start()
        bannerLatch.await(3, java.util.concurrent.TimeUnit.SECONDS)
        val conn = Connection("127.0.0.1", bannerProxyPort)
        conn.connect(null, 30000, 30000)
        if (capturedBanner.isNotEmpty()) KighmuLogger.info(TAG, "Server version: $capturedBanner")
        KighmuLogger.info(TAG, "SSH connecte!")

        val authenticated = conn.authenticateWithPassword(ssh.username, ssh.password)
        if (!authenticated) throw Exception("SSH auth echoue pour ${ssh.username}")
        KighmuLogger.info(TAG, "SSH authentifie!")

        conn.createDynamicPortForwarder(LOCAL_SOCKS_PORT)
        KighmuLogger.info(TAG, "SOCKS5 actif sur 127.0.0.1:$LOCAL_SOCKS_PORT")

        sshConnection = conn
        KighmuLogger.info(TAG, "=== HTTP Proxy Tunnel ACTIF port=$LOCAL_SOCKS_PORT ===")
        LOCAL_SOCKS_PORT
    }

    private fun sendPayload(out: OutputStream, payload: String, raw: String) {
        when {
            raw.contains("[split]", ignoreCase = true) -> {
                val parts = payload.split("[split]", ignoreCase = true)
                parts.forEachIndexed { idx, part ->
                    out.write(part.toByteArray(Charsets.ISO_8859_1))
                    out.flush()
                    if (idx < parts.size - 1) {
                        KighmuLogger.info(TAG, "Split fragment ${idx + 1}/${parts.size}")
                        Thread.sleep(200)
                    }
                }
            }
            raw.contains("[delay]", ignoreCase = true) -> {
                val lines = payload.split(CRLF)
                lines.forEachIndexed { idx, line ->
                    val data = if (idx < lines.size - 1) "$line$CRLF" else line
                    out.write(data.toByteArray(Charsets.ISO_8859_1))
                    out.flush()
                    Thread.sleep(100)
                }
            }
            else -> {
                out.write(payload.toByteArray(Charsets.ISO_8859_1))
                out.flush()
            }
        }
    }

    private fun consumeHeaders(inp: InputStream) {
        var h: String
        do {
            h = readHttpLine(inp)
            if (h.isNotEmpty()) {
                val hLower = h.lowercase()
                val skip = hLower.startsWith("report-to") ||
                           hLower.startsWith("nel:") ||
                           hLower.startsWith("cf-") ||
                           hLower.startsWith("alt-svc") ||
                           hLower.startsWith("cf-cache") ||
                           hLower.startsWith("date:") ||
                           hLower.startsWith("sec-websocket-accept")
                if (!skip) KighmuLogger.info(TAG, "Header: $h")
            }
        } while (h.isNotEmpty())
    }

    private fun readHttpLine(inp: InputStream): String {
        val sb = StringBuilder()
        var prev = -1
        while (true) {
            val b = inp.read()
            if (b == -1) break
            if (prev == '\r'.code && b == '\n'.code) {
                if (sb.isNotEmpty()) sb.deleteCharAt(sb.length - 1)
                break
            }
            if (b == '\n'.code) break
            sb.append(b.toChar())
            prev = b
        }
        return sb.toString()
    }

    override fun startTun2Socks(fd: Int) {
        try {
            KighmuLogger.info(TAG, "Démarrage HevTun2Socks (HTTP Proxy) fd=$fd port=$LOCAL_SOCKS_PORT")
            HevTun2Socks.init()
            if (HevTun2Socks.isAvailable) {
                val vpnService = context as? android.net.VpnService
                if (vpnService != null) {
                    HevTun2Socks.start(context, fd, LOCAL_SOCKS_PORT, vpnService, mtu = MTU)
                    KighmuLogger.info(TAG, "HevTun2Socks démarré avec succès ✅")
                } else {
                    KighmuLogger.error(TAG, "Contexte n'est pas un VpnService")
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
        try { sshConnection?.close() } catch (e: Exception) {}
        try { proxySocket?.close() } catch (e: Exception) {}
        try { tun2socksProcess?.destroyForcibly() } catch (e: Exception) {}
        try { tun2socksProcess?.inputStream?.close() } catch (e: Exception) {}
        try { tun2socksProcess?.errorStream?.close() } catch (e: Exception) {}
        engineScope.cancel()
        KighmuLogger.info(TAG, "HttpProxyEngine arrete")
    }

    override suspend fun sendData(data: ByteArray, length: Int) {}
    override suspend fun receiveData(): ByteArray? = null
    override fun isRunning() = running && sshConnection?.isAuthenticationComplete == true

    private fun pipe(inp: java.io.InputStream, out: java.io.OutputStream) {
        val buf = ByteArray(65536)
        try {
            while (true) {
                val n = inp.read(buf)
                if (n == -1) break
                out.write(buf, 0, n)
                if (inp.available() == 0) out.flush()
            }
        } catch (_: Exception) {}
    }
}
