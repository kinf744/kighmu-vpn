package com.kighmu.vpn.engines

import android.content.Context
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.SocketFactory
import com.kighmu.vpn.models.KighmuConfig
import com.kighmu.vpn.utils.KighmuLogger
import kotlinx.coroutines.*
import java.io.*
import java.net.*

class SlowDnsEngine(
    private val config: KighmuConfig,
    private val context: Context,
    private val vpnService: android.net.VpnService? = null
) : TunnelEngine {

    companion object {
        const val TAG = "SlowDnsEngine"
        const val LOCAL_SOCKS_PORT = 10800
    }

    private var running = false
    private var jschSession: Session? = null
    private var proxyServer: ServerSocket? = null
    private val engineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val dns get() = config.slowDns
    private val ssh get() = config.sshCredentials

    private fun protectSocketFd(socket: Socket): Boolean {
        if (vpnService == null) return false
        // protect(Socket) simple - fonctionne sur Android moderne
        val r = vpnService.protect(socket)
        if (r) { KighmuLogger.info(TAG, "protect(Socket) = true"); return true }
        // Fallback: via FD apres connect
        return try {
            val m = socket.javaClass.getMethod("getFileDescriptor\$")
            val fd = m.invoke(socket) as? java.io.FileDescriptor
            if (fd != null) {
                val f = java.io.FileDescriptor::class.java.getDeclaredField("descriptor")
                f.isAccessible = true
                val fdVal = f.getInt(fd)
                val r2 = vpnService.protect(fdVal)
                KighmuLogger.info(TAG, "protect(fd=$fdVal) = $r2")
                r2
            } else false
        } catch (e: Exception) {
            KighmuLogger.warning(TAG, "protectFd: ${e.message}")
            false
        }
    }

    private fun createProtectedSocket(host: String, port: Int): Socket {
        val s = Socket()
        // protect() avant connect - fonctionne sur Android avec interface VPN active
        val r1 = vpnService?.protect(s) ?: false
        KighmuLogger.info(TAG, "protect avant connect = $r1")
        s.connect(InetSocketAddress(host, port), 20000)
        // protect() apres connect aussi
        val r2 = protectSocketFd(s)
        KighmuLogger.info(TAG, "protect apres connect = $r2")
        s.soTimeout = 0
        s.tcpNoDelay = true
        s.keepAlive = true
        return s
    }

    override suspend fun start(): Int = withContext(Dispatchers.IO) {
        running = true
        KighmuLogger.info(TAG, "=== Demarrage SlowDNS ===")
        KighmuLogger.info(TAG, "SSH : ${ssh.host}:${ssh.port} / ${ssh.username}")

        if (dns.nameserver.isBlank()) throw Exception("Nameserver manquant")
        if (dns.publicKey.isBlank()) throw Exception("Public Key manquante")
        if (ssh.host.isBlank()) throw Exception("SSH Host manquant")
        if (ssh.username.isBlank() || ssh.password.isBlank()) throw Exception("SSH credentials manquants")

        try {
            val server = ServerSocket(0)
            proxyServer = server
            val proxyPort = server.localPort
            KighmuLogger.info(TAG, "SlowDns running - SOCKS5 proxy local:$proxyPort")

            // Thread Java (pas coroutine) pour accepter connexions
            Thread {
                while (running) {
                    try {
                        val client = server.accept()
                        Thread { handleSocks5(client) }.start()
                    } catch (e: Exception) {
                        if (running) KighmuLogger.error(TAG, "Proxy: ${e.message}")
                        break
                    }
                }
            }.start()

            KighmuLogger.info(TAG, "Connecting to ${ssh.host} via SOCKS5")
            startSsh(proxyPort)

            KighmuLogger.info(TAG, "=== SlowDNS CONNECTE port $LOCAL_SOCKS_PORT ===")
            LOCAL_SOCKS_PORT

        } catch (e: com.jcraft.jsch.JSchException) {
            val msg = when {
                e.message?.contains("Auth fail") == true -> "Auth SSH echouee (${ssh.username})"
                e.message?.contains("timeout") == true -> "Timeout SSH"
                e.message?.contains("Premature") == true -> "Connexion SSH prematuree"
                else -> e.message ?: "Erreur SSH"
            }
            KighmuLogger.error(TAG, "ECHEC SSH: $msg")
            throw Exception(msg)
        } catch (e: Exception) {
            KighmuLogger.error(TAG, "ECHEC: ${e.javaClass.simpleName}: ${e.message}")
            throw e
        }
    }

    private fun handleSocks5(client: Socket) {
        try {
            client.soTimeout = 0
            val inp = client.getInputStream()
            val out = client.getOutputStream()

            // SOCKS5 handshake - lire byte par byte
            val ver = inp.read()
            if (ver != 5) { client.close(); return }
            val nMethods = inp.read()
            repeat(nMethods) { inp.read() }
            // No auth
            out.write(byteArrayOf(5, 0))
            out.flush()

            // Request
            inp.read() // VER
            val cmd = inp.read() // CMD
            inp.read() // RSV
            val atyp = inp.read() // ATYP

            if (cmd != 1) { client.close(); return }

            val targetHost: String = when (atyp) {
                1 -> {
                    val a = ByteArray(4)
                    var i = 0; while (i < 4) { a[i] = inp.read().toByte(); i++ }
                    "${a[0].toInt() and 0xFF}.${a[1].toInt() and 0xFF}.${a[2].toInt() and 0xFF}.${a[3].toInt() and 0xFF}"
                }
                3 -> {
                    val len = inp.read()
                    val d = ByteArray(len)
                    var i = 0; while (i < len) { d[i] = inp.read().toByte(); i++ }
                    String(d)
                }
                else -> { client.close(); return }
            }
            val portHi = inp.read()
            val portLo = inp.read()
            val targetPort = (portHi shl 8) or portLo

            KighmuLogger.info(TAG, "SOCKS5 -> $targetHost:$targetPort")

            val remote = createProtectedSocket(targetHost, targetPort)
            remote.soTimeout = 0
            KighmuLogger.info(TAG, "Remote connecte+protege -> $targetHost:$targetPort")

            // Reply success
            out.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
            out.flush()
            KighmuLogger.info(TAG, "SOCKS5 tunnel etabli -> relay demarre")

            val remoteIn = remote.getInputStream()
            val remoteOut = remote.getOutputStream()

            var bytesSent = 0L
            var bytesRecv = 0L
            // Thread relay: client -> remote
            val t1 = Thread {
                try {
                    val buf = ByteArray(4096)
                    while (running) {
                        val len = inp.read(buf)
                        if (len < 0) break
                        remoteOut.write(buf, 0, len)
                        remoteOut.flush()
                        bytesSent += len
                        if (bytesSent <= 200) KighmuLogger.info(TAG, "-> remote: $len bytes (total=$bytesSent)")
                    }
                } catch (e: Exception) { KighmuLogger.error(TAG, "t1 stop: ${e.message}") }
                try { remote.close() } catch (_: Exception) {}
            }
            // Thread relay: remote -> client
            val t2 = Thread {
                try {
                    val buf = ByteArray(4096)
                    while (running) {
                        val len = remoteIn.read(buf)
                        if (len < 0) break
                        out.write(buf, 0, len)
                        out.flush()
                        bytesRecv += len
                        if (bytesRecv <= 200) KighmuLogger.info(TAG, "<- remote: $len bytes (total=$bytesRecv)")
                    }
                } catch (e: Exception) { KighmuLogger.error(TAG, "t2 stop: ${e.message}") }
                try { client.close() } catch (_: Exception) {}
            }
            t1.start()
            t2.start()
            t1.join()
            t2.join()
            KighmuLogger.info(TAG, "Session terminee")

        } catch (e: Exception) {
            KighmuLogger.error(TAG, "SOCKS5 error: ${e.message}")
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun startSsh(proxyPort: Int) {
        val jsch = JSch()
        if (ssh.usePrivateKey && ssh.privateKey.isNotEmpty()) {
            jsch.addIdentity("key", ssh.privateKey.toByteArray(), null, null)
        }
        val session = jsch.getSession(ssh.username, ssh.host, ssh.port)
        session.setPassword(ssh.password)
        session.setConfig("StrictHostKeyChecking", "no")
        session.setConfig("PreferredAuthentications", "password")
        session.setConfig("compression.s2c", "none")
        session.setConfig("compression.c2s", "none")
        session.setTimeout(0)
        // Pas de proxy - connexion directe avec socket protege
        // SocketFactory: protege le socket AVANT et APRES connect
        session.setSocketFactory(object : SocketFactory {
            override fun createSocket(host: String, port: Int): Socket {
                KighmuLogger.info(TAG, "SocketFactory -> $host:$port")
                val s = createProtectedSocket(host, port)
                KighmuLogger.info(TAG, "SocketFactory socket pret")
                return s
            }
            override fun getInputStream(s: Socket): InputStream = s.getInputStream()
            override fun getOutputStream(s: Socket): OutputStream = s.getOutputStream()
        })
        KighmuLogger.info(TAG, "SSH connexion directe avec socket protege (timeout 45s)...")
        session.connect(45000)
        jschSession = session
        KighmuLogger.info(TAG, "SSH connecte! ${session.serverVersion}")
        session.setPortForwardingL(LOCAL_SOCKS_PORT, "127.0.0.1", LOCAL_SOCKS_PORT)
        KighmuLogger.info(TAG, "Socks port $LOCAL_SOCKS_PORT")
    }

    override suspend fun stop() {
        running = false
        jschSession?.disconnect()
        jschSession = null
        try { proxyServer?.close() } catch (_: Exception) {}
        engineScope.cancel()
        KighmuLogger.info(TAG, "SlowDNS arrete")
    }

    override suspend fun sendData(data: ByteArray, length: Int) {}
    override suspend fun receiveData(): ByteArray? = null
    override fun isRunning() = running && jschSession?.isConnected == true
}
