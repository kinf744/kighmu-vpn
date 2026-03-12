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

        // ÉTAPE 1 : Connexion TCP au proxy
        KighmuLogger.info(TAG, "ÉTAPE 1: Connexion TCP au proxy...")
        val sock = Socket()
        sock.connect(InetSocketAddress(proxy.proxyHost, proxy.proxyPort), 15000)
        sock.soTimeout = 30000
        proxySocket = sock
        KighmuLogger.info(TAG, "TCP connecté au proxy ✓")

        val out: OutputStream = sock.getOutputStream()
            ?: throw Exception("OutputStream null")
        val inp: InputStream = sock.getInputStream()
            ?: throw Exception("InputStream null")

        // ÉTAPE 2 : Envoyer CONNECT / Custom Payload
        KighmuLogger.info(TAG, "ÉTAPE 2: Envoi payload HTTP CONNECT...")
        val payload = if (proxy.customPayload.isNotBlank()) {
            proxy.customPayload
                .replace("[host]", ssh.host).replace("[HOST]", ssh.host)
                .replace("[port]", ssh.port.toString()).replace("[PORT]", ssh.port.toString())
                .replace("\\r\\n", CRLF).replace("\\n", CRLF)
        } else {
            "CONNECT ${ssh.host}:${ssh.port} HTTP/1.1${CRLF}" +
            "Host: ${ssh.host}:${ssh.port}${CRLF}" +
            "Proxy-Connection: Keep-Alive${CRLF}${CRLF}"
        }

        // Technique HTTP Injector : envoyer payload avec méthode optimale
        sendPayload(out, payload)
        KighmuLogger.info(TAG, "Payload envoyé ✓")

        // ÉTAPE 3 : Lire et traiter la réponse selon le type de payload
        KighmuLogger.info(TAG, "ÉTAPE 3: Lecture réponse proxy...")
        val isConnect = payload.trimStart().startsWith("CONNECT", ignoreCase = true)
        val isUpgrade = payload.contains("Upgrade", ignoreCase = true) ||
                        payload.contains("websocket", ignoreCase = true)

        val firstLine = readLine(inp)
        KighmuLogger.info(TAG, "Proxy réponse: $firstLine")

        if (isConnect) {
            // CONNECT : doit retourner 200
            if (!firstLine.contains("200")) {
                throw Exception("Proxy CONNECT refusé: $firstLine")
            }
            // Consommer tous les headers
            var h = ""
            do {
                h = readLine(inp)
                if (h.isNotEmpty()) KighmuLogger.info(TAG, "Header: $h")
            } while (h.isNotEmpty())
            KighmuLogger.info(TAG, "Tunnel CONNECT établi ✓")

        } else if (isUpgrade) {
            // WebSocket Upgrade : doit retourner 101 Switching Protocols
            if (!firstLine.contains("101")) {
                KighmuLogger.warning(TAG, "Upgrade inattendu: $firstLine - on continue quand même")
            }
            // Consommer headers jusqu'à ligne vide
            var h = ""
            do {
                h = readLine(inp)
                if (h.isNotEmpty()) KighmuLogger.info(TAG, "Header: $h")
            } while (h.isNotEmpty())
            KighmuLogger.info(TAG, "Tunnel WebSocket Upgrade établi ✓")

        } else {
            // GET/POST/autres : le proxy peut répondre 200 ou 101
            // On consomme tout jusqu'à ligne vide et on tente SSH directement
            KighmuLogger.info(TAG, "Payload non-CONNECT ($firstLine) - consommation headers...")
            if (firstLine.contains("400") || firstLine.contains("403") ||
                firstLine.contains("407") || firstLine.contains("502")) {
                throw Exception("Proxy erreur: $firstLine")
            }
            var h = ""
            do {
                h = readLine(inp)
                if (h.isNotEmpty()) KighmuLogger.info(TAG, "Header: $h")
            } while (h.isNotEmpty())
            KighmuLogger.info(TAG, "Headers consommés - tentative SSH directe ✓")
        }

        // ÉTAPE 4 : Relay local - écouter sur port aléatoire, relayer vers socket proxy
        KighmuLogger.info(TAG, "ÉTAPE 4: Démarrage relay local pour SSH...")
        val relayPort = startLocalRelay(sock, inp, out)
        KighmuLogger.info(TAG, "Relay local prêt sur 127.0.0.1:$relayPort ✓")

        // SSH se connecte au relay local (qui est connecté au proxy)
        val conn = Connection("127.0.0.1", relayPort)
        conn.connect(null, 30000, 30000)
        KighmuLogger.info(TAG, "SSH connecté via relay ✓")

        // ÉTAPE 5 : Authentification
        KighmuLogger.info(TAG, "ÉTAPE 5: Authentification SSH...")
        val authenticated = conn.authenticateWithPassword(ssh.username, ssh.password)
        if (!authenticated) throw Exception("SSH auth échoué pour ${ssh.username}")
        KighmuLogger.info(TAG, "SSH authentifié ✓")

        // ÉTAPE 6 : SOCKS5 dynamique local
        KighmuLogger.info(TAG, "ÉTAPE 6: Création SOCKS5 local port=$LOCAL_SOCKS_PORT...")
        conn.createDynamicPortForwarder(LOCAL_SOCKS_PORT)
        KighmuLogger.info(TAG, "SOCKS5 actif sur 127.0.0.1:$LOCAL_SOCKS_PORT ✓")

        sshConnection = conn
        KighmuLogger.info(TAG, "=== HTTP Proxy Tunnel ACTIF port=$LOCAL_SOCKS_PORT ===")
        LOCAL_SOCKS_PORT
    }

    // Technique HTTP Injector : envoi payload optimisé
    private fun sendPayload(out: OutputStream, payload: String) {
        val bytes = payload.toByteArray(Charsets.ISO_8859_1)

        // Détecter si split est nécessaire (présence de [split] dans payload original)
        if (proxy.customPayload.contains("[split]", ignoreCase = true)) {
            // Split au niveau du [split] marker
            val splitPayload = proxy.customPayload
                .replace("[host]", ssh.host).replace("[HOST]", ssh.host)
                .replace("[port]", ssh.port.toString()).replace("[PORT]", ssh.port.toString())
                .replace("[crlf]", CRLF).replace("[CRLF]", CRLF)
                .replace("[cr]", "").replace("[lf]", "
")
                .replace("\r\n", CRLF).replace("\n", CRLF)
            val parts = splitPayload.split("[split]", ignoreCase = true)
            parts.forEachIndexed { idx, part ->
                out.write(part.toByteArray(Charsets.ISO_8859_1))
                out.flush()
                if (idx < parts.size - 1) {
                    KighmuLogger.info(TAG, "Split fragment ${idx+1}/${parts.size} envoyé")
                    Thread.sleep(200) // délai entre fragments
                }
            }
        } else if (proxy.customPayload.contains("[delay]", ignoreCase = true)) {
            // Slow headers - envoyer ligne par ligne avec délai
            val lines = payload.split(CRLF)
            lines.forEachIndexed { idx, line ->
                val lineData = if (idx < lines.size - 1) "$line$CRLF" else line
                out.write(lineData.toByteArray(Charsets.ISO_8859_1))
                out.flush()
                Thread.sleep(100)
            }
        } else {
            // Envoi normal en un seul bloc
            out.write(bytes)
            out.flush()
        }
    }

    // Relay TCP local - relaie la socket proxy vers un port local pour trilead
    private fun startLocalRelay(proxySock: Socket, proxyIn: InputStream, proxyOut: OutputStream): Int {
        val serverSocket = java.net.ServerSocket(0) // port aléatoire
        val port = serverSocket.localPort
        Thread {
            try {
                val clientSock = serverSocket.accept()
                serverSocket.close()
                val clientIn = clientSock.getInputStream()
                val clientOut = clientSock.getOutputStream()

                // Thread: client → proxy
                Thread {
                    try {
                        val buf = ByteArray(8192)
                        var n: Int
                        while (clientSock.isConnected && !proxySock.isClosed) {
                            n = clientIn.read(buf)
                            if (n == -1) break
                            proxyOut.write(buf, 0, n)
                            proxyOut.flush()
                        }
                    } catch (_: Exception) {}
                }.start()

                // Thread: proxy → client
                try {
                    val buf = ByteArray(8192)
                    var n: Int
                    while (!proxySock.isClosed && clientSock.isConnected) {
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

    // Lire une ligne depuis InputStream byte par byte (safe pour SSH)
    private fun readLine(inp: InputStream): String {
        val sb = StringBuilder()
        var prev = -1
        while (true) {
            val b = inp.read()
            if (b == -1) break
            if (prev == '\r'.code && b == '\n'.code) {
                // Supprimer le \r déjà ajouté
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
        KighmuLogger.info(TAG, "Démarrage tun2socks fd=$fd")
        engineScope.launch(Dispatchers.IO) {
            try {
                val nativeDir = context.applicationInfo.nativeLibraryDir
                val bin = File(nativeDir, "libtun2socks.so")
                if (!bin.exists()) {
                    KighmuLogger.error(TAG, "libtun2socks.so introuvable")
                    return@launch
                }
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
                KighmuLogger.info(TAG, "cmd: ${cmd.joinToString(" ")}")
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
                    KighmuLogger.info(TAG, "fd=$fd envoyé à tun2socks ✓")
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
        KighmuLogger.info(TAG, "HttpProxyEngine arrêté")
    }

    override suspend fun sendData(data: ByteArray, length: Int) {}
    override suspend fun receiveData(): ByteArray? = null
    override fun isRunning() = running && sshConnection?.isAuthenticationComplete == true
}
