package com.kighmu.vpn.engines

import android.content.Context
import java.io.File
import com.trilead.ssh2.Connection
import com.kighmu.vpn.models.KighmuConfig
import com.kighmu.vpn.utils.KighmuLogger
import kotlinx.coroutines.*
import java.net.InetSocketAddress
import java.net.Socket

class HttpProxyEngine(
    private val config: KighmuConfig,
    private val context: Context
) : TunnelEngine {
    private val MTU = 1500
    private var tun2socksProcess: Process? = null

    companion object {
        const val TAG = "HttpProxyEngine"
        const val LOCAL_SOCKS_PORT = 10801
        val CRLF = "\r\n"
    }

    private var running = false
    private var sshConnection: Connection? = null
    private var proxySocket: Socket? = null
    private val engineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val proxy get() = config.httpProxy
    private val ssh get() = config.sshCredentials

    override suspend fun start(): Int = withContext(Dispatchers.IO) {
        running = true
        KighmuLogger.info(TAG, "=== Demarrage SSH HTTP Proxy ===")
        KighmuLogger.info(TAG, "Proxy: ${proxy.proxyHost}:${proxy.proxyPort}")
        KighmuLogger.info(TAG, "SSH: ${ssh.host}:${ssh.port} user=${ssh.username}")

        if (proxy.proxyHost.isBlank()) throw Exception("Proxy Host manquant")
        if (ssh.host.isBlank()) throw Exception("SSH Host manquant")
        if (ssh.username.isBlank()) throw Exception("SSH Username manquant")

        try {
            KighmuLogger.info(TAG, "Connexion au proxy ${proxy.proxyHost}:${proxy.proxyPort}...")
            val sock = Socket()
            sock.connect(InetSocketAddress(proxy.proxyHost, proxy.proxyPort), 10000)
            proxySocket = sock
            KighmuLogger.info(TAG, "Proxy TCP connecte")

            val out = sock.getOutputStream()
            val inp = sock.getInputStream()

            val req = if (proxy.customPayload.isNotBlank()) {
                proxy.customPayload
                    .replace("[host]", ssh.host).replace("[HOST]", ssh.host)
                    .replace("[port]", ssh.port.toString()).replace("[PORT]", ssh.port.toString())
                    .replace("\\r\\n", CRLF).replace("\\n", CRLF)
            } else {
                "CONNECT ${ssh.host}:${ssh.port} HTTP/1.1${CRLF}Host: ${ssh.host}:${ssh.port}${CRLF}${CRLF}"
            }

            KighmuLogger.info(TAG, "Envoi CONNECT request...")
            out.write(req.toByteArray())
            out.flush()

            val resp = StringBuilder()
            var last4 = ""
            while (true) {
                val curr = inp.read(); if (curr == -1) break
                resp.append(curr.toChar())
                last4 = (last4 + curr.toChar()).takeLast(4)
                if (last4 == "\r\n\r\n") break
                // Aussi accepter 


                if (last4.endsWith("\n\n")) break
            }
            val respStr = resp.toString()
            KighmuLogger.info(TAG, "Reponse proxy: ${respStr.take(80)}")
            if (!respStr.contains("200")) throw Exception("Proxy refuse: ${respStr.take(80)}")

            KighmuLogger.info(TAG, "Tunnel HTTP etabli, demarrage SSH trilead...")

            // Injecter les streams du socket proxy dans trilead via reflexion
            val conn = Connection(ssh.host, ssh.port)
            val connClass = conn.javaClass
            try {
                val tdField = connClass.getDeclaredField("td")
                tdField.isAccessible = true
                val td = tdField.get(conn)
                val tdClass = td.javaClass
                // Injecter le socket
                val sockField = tdClass.getDeclaredField("sock")
                sockField.isAccessible = true
                sockField.set(td, sock)
            } catch (e: Exception) {
                KighmuLogger.error(TAG, "reflexion failed: ${e.message}")
            }
            conn.connect(null, 15000, 15000)

            val authenticated = conn.authenticateWithPassword(ssh.username, ssh.password)
            if (!authenticated) throw Exception("SSH auth echoue pour ${ssh.username}")

            // Dynamic SOCKS5 forwarding comme SlowDNS
            conn.requestRemotePortForwarding("", 0, "127.0.0.1", 0)
            val dynForward = conn.createDynamicPortForwarder(LOCAL_SOCKS_PORT)
            KighmuLogger.info(TAG, "Dynamic SOCKS5 actif sur $LOCAL_SOCKS_PORT")
            sshConnection = conn
            KighmuLogger.info(TAG, "=== HTTP Proxy Tunnel ACTIF sur port $LOCAL_SOCKS_PORT ===")
            LOCAL_SOCKS_PORT
        } catch (e: Exception) {
            KighmuLogger.error(TAG, "ECHEC: ${e.javaClass.simpleName}: ${e.message}")
            throw e
        }
    }

    override suspend fun stop() {
        running = false
        try { sshConnection?.close() } catch (_: Exception) {}
        try { proxySocket?.close() } catch (_: Exception) {}
        engineScope.cancel()
        KighmuLogger.info(TAG, "HttpProxy arrete")
    }

    override suspend fun sendData(data: ByteArray, length: Int) {}
    override suspend fun receiveData(): ByteArray? = null
    override fun isRunning() = running && sshConnection?.isAuthenticationComplete == true


    override fun startTun2Socks(fd: Int) {
        KighmuLogger.info(TAG, "Demarrage BadVPN tun2socks --tunfd=$fd")
        engineScope.launch(Dispatchers.IO) {
            try {
                val nativeDir = context.applicationInfo.nativeLibraryDir
                val bin = File(nativeDir, "libtun2socks.so")
                if (!bin.exists()) {
                    KighmuLogger.error(TAG, "libtun2socks.so introuvable dans $nativeDir")
                    return@launch
                }
                bin.setExecutable(true)
                val sockPath = "${context.cacheDir.absolutePath}/tun2socks_fd.sock"
                File(sockPath).delete()
                val cmd = listOf(
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
                // Utiliser Runtime.exec avec tableau pour eviter probleme d'espaces
                val cmdArray = cmd.toTypedArray()
                tun2socksProcess = Runtime.getRuntime().exec(cmdArray)
                // Lire stdout+stderr dans fichier
                val proc = tun2socksProcess!!
                Thread {
                    proc.errorStream.bufferedReader().forEachLine { line ->
                        KighmuLogger.info(TAG, "tun2socks: $line")
                        KighmuLogger.info(TAG, "tun2socks stderr: $line")
                    }
                }.start()
                // Envoyer le fd via socket Unix a BadVPN
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
                tun2socksProcess!!.inputStream.bufferedReader().forEachLine { line ->
                    KighmuLogger.info(TAG, "tun2socks: $line")
                }
            } catch (e: Exception) {
                KighmuLogger.error(TAG, "tun2socks error: ${e.message}")
            }
        }
    }
}