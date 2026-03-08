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
        // FD disponible seulement apres connect()
        return try {
            val m = socket.javaClass.getMethod("getFileDescriptor\$")
            val fd = m.invoke(socket) as? java.io.FileDescriptor
            if (fd != null) {
                val f = java.io.FileDescriptor::class.java.getDeclaredField("descriptor")
                f.isAccessible = true
                val fdVal = f.getInt(fd)
                val r = vpnService.protect(fdVal)
                KighmuLogger.info(TAG, "protect(fd=$fdVal) = $r")
                r
            } else false
        } catch (e: Exception) {
            KighmuLogger.warning(TAG, "protectFd: ${e.message}")
            vpnService.protect(socket)
        }
    }

    private fun protectSocket(socket: Socket): Boolean {
        // protect() avant connect() ne marche pas (fd=-1)
        // On protege APRES connect() via FD
        return false // placeholder - appeler protectSocketFd apres connect
    }

    override suspend fun start(): Int = withContext(Dispatchers.IO) {
        running = true
        KighmuLogger.info(TAG, "=== Demarrage SlowDNS ===")
        KighmuLogger.info(TAG, "DNS : ${dns.dnsServer}:${dns.dnsPort}")
        KighmuLogger.info(TAG, "Nameserver : ${dns.nameserver}")
        KighmuLogger.info(TAG, "SSH : ${ssh.host}:${ssh.port} / ${ssh.username}")
        KighmuLogger.info(TAG, "VpnService present: ${vpnService != null}")

        if (dns.nameserver.isBlank()) throw Exception("Nameserver manquant")
        if (dns.publicKey.isBlank()) throw Exception("Public Key manquante")
        if (ssh.host.isBlank()) throw Exception("SSH Host manquant")
        if (ssh.username.isBlank() || ssh.password.isBlank()) throw Exception("SSH credentials manquants")

        try {
            val server = ServerSocket(0)
            proxyServer = server
            val proxyPort = server.localPort
            KighmuLogger.info(TAG, "SlowDns running - SOCKS5 proxy local:$proxyPort")

            engineScope.launch(Dispatchers.IO) {
                while (running) {
                    try {
                        val client = server.accept()
                        launch { handleSocks5(client) }
                    } catch (e: Exception) {
                        if (running) KighmuLogger.error(TAG, "Proxy: ${e.message}")
                        break
                    }
                }
            }

            KighmuLogger.info(TAG, "Connecting to ${ssh.host} via SOCKS5 proxy")
            startSsh(proxyPort)

            KighmuLogger.info(TAG, "=== SlowDNS CONNECTE port $LOCAL_SOCKS_PORT ===")
            LOCAL_SOCKS_PORT

        } catch (e: com.jcraft.jsch.JSchException) {
            val msg = when {
                e.message?.contains("Auth fail") == true -> "Auth SSH echouee (${ssh.username})"
                e.message?.contains("timeout") == true -> "Timeout SSH - boucle VPN?"
                e.message?.contains("Premature") == true -> "Connexion SSH prematuree"
                e.message?.contains("Connection refused") == true -> "Connexion refusee"
                else -> e.message ?: "Erreur SSH"
            }
            KighmuLogger.error(TAG, "ECHEC SSH: $msg")
            throw Exception(msg)
        } catch (e: Exception) {
            KighmuLogger.error(TAG, "ECHEC: ${e.javaClass.simpleName}: ${e.message}")
            throw e
        }
    }

    private suspend fun handleSocks5(client: Socket) = withContext(Dispatchers.IO) {
        try {
            val inp = client.getInputStream()
            val out = client.getOutputStream()

            // SOCKS5 handshake
            val ver = inp.read()
            if (ver != 5) { client.close(); return@withContext }
            val nMethods = inp.read()
            val methods = ByteArray(nMethods)
            inp.read(methods)
            out.write(byteArrayOf(5, 0))
            out.flush()

            val req = ByteArray(4)
            inp.read(req)
            if (req[1].toInt() != 1) { client.close(); return@withContext }

            val targetHost: String = when (req[3].toInt()) {
                1 -> {
                    val addr = ByteArray(4); inp.read(addr)
                    "${addr[0].toInt() and 0xFF}.${addr[1].toInt() and 0xFF}.${addr[2].toInt() and 0xFF}.${addr[3].toInt() and 0xFF}"
                }
                3 -> {
                    val len = inp.read()
                    val domain = ByteArray(len); inp.read(domain)
                    String(domain)
                }
                else -> { client.close(); return@withContext }
            }
            val portHi = inp.read()
            val portLo = inp.read()
            val targetPort = (portHi shl 8) or portLo

            KighmuLogger.info(TAG, "SOCKS5 CONNECT -> $targetHost:$targetPort")

            // Creer et proteger le socket remote AVANT connect
            val remote = Socket()
            val prot = protectSocket(remote)
            KighmuLogger.info(TAG, "Remote socket protege=$prot")

            remote.connect(InetSocketAddress(targetHost, targetPort), 15000)
            val protR = protectSocketFd(remote)
            KighmuLogger.info(TAG, "Remote connecte+protege=$protR vers $targetHost:$targetPort")

            // Reply success
            out.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
            out.flush()
            KighmuLogger.info(TAG, "SOCKS5 tunnel etabli")

            val remoteIn = remote.getInputStream()
            val remoteOut = remote.getOutputStream()

            val t1 = launch {
                try {
                    val buf = ByteArray(8192)
                    while (running) {
                        val len = inp.read(buf)
                        if (len <= 0) break
                        remoteOut.write(buf, 0, len)
                        remoteOut.flush()
                    }
                } catch (_: Exception) {}
            }
            val t2 = launch {
                try {
                    val buf = ByteArray(8192)
                    while (running) {
                        val len = remoteIn.read(buf)
                        if (len <= 0) break
                        out.write(buf, 0, len)
                        out.flush()
                    }
                } catch (_: Exception) {}
            }
            t1.join()
            t2.cancel()
            KighmuLogger.info(TAG, "Session SOCKS5 terminee")
            remote.close()

        } catch (e: Exception) {
            KighmuLogger.error(TAG, "SOCKS5 error: ${e.message}")
        } finally {
            client.close()
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

        // SocketFactory: proteger le socket SSH AVANT connect
        session.setSocketFactory(object : SocketFactory {
            override fun createSocket(host: String, port: Int): Socket {
                KighmuLogger.info(TAG, "SocketFactory: creation socket pour $host:$port")
                val s = Socket()
                val prot = protectSocket(s)
                KighmuLogger.info(TAG, "SocketFactory: socket protege=$prot")
                s.connect(InetSocketAddress(host, port), 20000)
                val protJ = protectSocketFd(s)
                KighmuLogger.info(TAG, "SocketFactory: socket connecte+protege=$protJ")
                s.soTimeout = 0
                return s
            }
            override fun getInputStream(socket: Socket): InputStream = socket.getInputStream()
            override fun getOutputStream(socket: Socket): OutputStream = socket.getOutputStream()
        })

        // JSch se connecte via proxy SOCKS5 local
        session.setProxy(com.jcraft.jsch.ProxySOCKS5("127.0.0.1", proxyPort))

        KighmuLogger.info(TAG, "SSH connexion via SOCKS5 proxy local:$proxyPort (timeout 45s)...")
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
