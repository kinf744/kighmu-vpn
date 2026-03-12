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
        const val LOCAL_SOCKS_PORT = 10801
        const val CRLF = "\r\n"
    }

    private val MTU = 1500
    private var running = false
    private var sshConnection: Connection? = null
    private var proxySocket: Socket? = null
    private var tun2socksProcess: Process? = null
    private val engineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val proxy get() = config.httpProxy
    private val ssh get() = config.sshCredentials

    override suspend fun start(): Int = withContext(Dispatchers.IO) {
        running = true
        KighmuLogger.info(TAG, "=== Démarrage HTTP Proxy Engine ===")
        KighmuLogger.info(TAG, "Proxy: ${proxy.proxyHost}:${proxy.proxyPort}")
        KighmuLogger.info(TAG, "SSH: ${ssh.host}:${ssh.port} user=${ssh.username}")

        if (proxy.proxyHost.isBlank()) throw Exception("Proxy Host manquant")
        if (ssh.host.isBlank()) throw Exception("SSH Host manquant")
        if (ssh.username.isBlank()) throw Exception("SSH Username manquant")

        // ETAPE 1 : Connexion TCP au proxy
        KighmuLogger.info(TAG, "ETAPE 1: Connexion TCP au proxy...")
        val sock = Socket()
        sock.connect(InetSocketAddress(proxy.proxyHost, proxy.proxyPort), 15000)
        sock.soTimeout = 30000
        proxySocket = sock
        KighmuLogger.info(TAG, "TCP connecte au proxy OK")

        val out: OutputStream = sock.getOutputStream()
        val inp: InputStream = sock.getInputStream()

        // ETAPE 2 : Construire et envoyer payload
        val rawPayload = proxy.customPayload.ifBlank {
            "CONNECT [host]:[port] HTTP/1.1[crlf]Host: [host]:[port][crlf]Proxy-Connection: Keep-Alive[crlf][crlf]"
        }

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

        // ETAPE 3 : Lire reponse proxy
        KighmuLogger.info(TAG, "ETAPE 3: Lecture reponse proxy...")
        val isConnect = rawPayload.trimStart().startsWith("CONNECT", ignoreCase = true)
        val isUpgrade = rawPayload.contains("Upgrade", ignoreCase = true) ||
                        rawPayload.contains("websocket", ignoreCase = true)

        val firstLine = readLine(inp)
        KighmuLogger.info(TAG, "Proxy reponse: $firstLine")

        val isError = firstLine.contains("400") || firstLine.contains("403") ||
                      firstLine.contains("407") || firstLine.contains("502") ||
                      firstLine.contains("404") || firstLine.contains("500")

        if (isConnect && !firstLine.contains("200")) {
            consumeHeaders(inp)
            throw Exception("Proxy CONNECT refuse: $firstLine")
        }
        if (isError) {
            consumeHeaders(inp)
            throw Exception("Proxy erreur: $firstLine")
        }

        consumeHeaders(inp)
        KighmuLogger.info(TAG, "Headers consommes - tunnel pret pour SSH")

        // ETAPE 4 : Relay local pour trilead
        KighmuLogger.info(TAG, "ETAPE 4: Demarrage relay local...")
        val relayPort = startLocalRelay(sock, inp, out)
        KighmuLogger.info(TAG, "Relay local pret sur 127.0.0.1:$relayPort")

        // ETAPE 5 : SSH via relay
        KighmuLogger.info(TAG, "ETAPE 5: Connexion SSH via relay...")
        val conn = Connection("127.0.0.1", relayPort)
        conn.connect(null, 30000, 30000)
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
                // Split payload en 2 parties
                val parts = payload.split("[split]", ignoreCase = true)
                parts.forEachIndexed { idx, part ->
                    out.write(part.toByteArray(Charsets.ISO_8859_1))
                    out.flush()
                    if (idx < parts.size - 1) {
                        KighmuLogger.info(TAG, "Split fragment ${idx+1}/${parts.size}")
                        Thread.sleep(200)
                    }
                }
            }
            raw.contains("[delay]", ignoreCase = true) -> {
                // Slow headers - ligne par ligne
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
        var h = ""
        do {
            h = readLine(inp)
            if (h.isNotEmpty()) KighmuLogger.info(TAG, "Header: $h")
        } while (h.isNotEmpty())
    }

    private fun readLine(inp: InputStream): String {
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

    private fun startLocalRelay(proxySock: Socket, proxyIn: InputStream, proxyOut: OutputStream): Int {
        val serverSocket = java.net.ServerSocket(0)
        val port = serverSocket.localPort
        Thread {
            try {
                val clientSock = serverSocket.accept()
                serverSocket.close()
                val clientIn = clientSock.getInputStream()
                val clientOut = clientSock.getOutputStream()
                // client -> proxy
                Thread {
                    try {
                        val buf = ByteArray(8192)
                        var n: Int
                        while (!proxySock.isClosed) {
                            n = clientIn.read(buf)
                            if (n == -1) break
                            proxyOut.write(buf, 0, n)
                            proxyOut.flush()
                        }
                    } catch (_: Exception) {}
                }.start()
                // proxy -> client
                try {
                    val buf = ByteArray(8192)
                    var n: Int
                    while (!proxySock.isClosed) {
                        n = proxyIn.read(buf)
                        if (n == -1) break
                        clientOut.write(buf, 0, n)
                        clientOut.flush()
                    }
                } catch (_: Exception) {}
            } catch (e: Exception) {
                KighmuLogger.error(TAG, "Relay error: ${e.message}")
            }
        }.start()
        return port
    }

    override fun startTun2Socks(fd: Int) {
        KighmuLogger.info(TAG, "Demarrage tun2socks fd=$fd")
        engineScope.launch(Dispatchers.IO) {
            try {
                val nativeDir = context.applicationInfo.nativeLibraryDir
                val bin = File(nativeDir, "libtun2socks.so")
                if (!bin.exists()) { KighmuLogger.error(TAG, "libtun2socks.so introuvable"); return@launch }
                bin.setExecutable(true)
                val sockPath = "${context.cacheDir}/tun2socks_http.sock"
                File(sockPath).delete()
                val cmd = arrayOf(
                    bin.absolutePath,
                    "--sock-path", sockPath,
                    "--tunmtu", MTU.toString(),
                    "--netif-ipaddr", "10.0.0.1",
                    "--netif-netmask", "255.255.255.0",
                    "--socks-server-addr", "127.0.0.1:$LOCAL_SOCKS_PORT",
                    "--enable-udprelay",
                    "--loglevel", "4"
                )
                tun2socksProcess = Runtime.getRuntime().exec(cmd)
                Thread {
                    tun2socksProcess?.errorStream?.bufferedReader()?.forEachLine { line ->
                        if (running) KighmuLogger.info(TAG, "tun2socks: $line")
                    }
                }.start()
                delay(500)
                try {
                    val localSocket = LocalSocket()
                    localSocket.connect(LocalSocketAddress(sockPath, LocalSocketAddress.Namespace.FILESYSTEM))
                    val pfd = ParcelFileDescriptor.fromFd(fd)
                    localSocket.setFileDescriptorsForSend(arrayOf(pfd.fileDescriptor))
                    localSocket.outputStream.write(1)
                    localSocket.outputStream.flush()
                    localSocket.close()
                    KighmuLogger.info(TAG, "fd=$fd envoye OK")
                } catch (e: Exception) {
                    KighmuLogger.error(TAG, "sock-path error: ${e.message}")
                }
                tun2socksProcess?.inputStream?.bufferedReader()?.forEachLine { line ->
                    if (running) KighmuLogger.info(TAG, "tun2socks: $line")
                }
            } catch (e: Exception) {
                KighmuLogger.error(TAG, "tun2socks error: ${e.message}")
            }
        }
    }

    override suspend fun stop() {
        running = false
        try { sshConnection?.close() } catch (_: Exception) {}
        try { proxySocket?.close() } catch (_: Exception) {}
        try { tun2socksProcess?.destroyForcibly() } catch (_: Exception) {}
        engineScope.cancel()
        KighmuLogger.info(TAG, "HttpProxyEngine arrete")
    }

    override suspend fun sendData(data: ByteArray, length: Int) {}
    override suspend fun receiveData(): ByteArray? = null
    override fun isRunning() = running && sshConnection?.isAuthenticationComplete == true
}
