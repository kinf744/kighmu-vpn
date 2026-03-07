package com.kighmu.vpn.engines

import android.content.Context
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.kighmu.vpn.models.KighmuConfig
import com.kighmu.vpn.utils.KighmuLogger
import kotlinx.coroutines.*
import java.io.*
import java.net.*

class SlowDnsEngine(
    private val config: KighmuConfig,
    private val context: Context
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

    override suspend fun start(): Int = withContext(Dispatchers.IO) {
        running = true
        KighmuLogger.info(TAG, "=== Demarrage SlowDNS ===")
        KighmuLogger.info(TAG, "DNS : ${dns.dnsServer}:${dns.dnsPort}")
        KighmuLogger.info(TAG, "Nameserver : ${dns.nameserver}")
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
                e.message?.contains("timeout") == true -> "Timeout SSH"
                e.message?.contains("Premature") == true -> "Connexion SSH prematuree"
                e.message?.contains("Connection refused") == true -> "Proxy non pret"
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
            // 1. Client: VER=5, NMETHODS, METHODS
            val ver = inp.read()
            if (ver != 5) { client.close(); return@withContext }
            val nMethods = inp.read()
            val methods = ByteArray(nMethods)
            inp.read(methods)

            // 2. Server: VER=5, METHOD=0 (no auth)
            out.write(byteArrayOf(5, 0))
            out.flush()

            // 3. Client: VER=5, CMD=1(CONNECT), RSV=0, ATYP, DST.ADDR, DST.PORT
            val req = ByteArray(4)
            inp.read(req)
            if (req[1].toInt() != 1) { client.close(); return@withContext } // Only CONNECT

            val targetHost: String
            val atyp = req[3].toInt()
            targetHost = when (atyp) {
                1 -> { // IPv4
                    val addr = ByteArray(4); inp.read(addr)
                    "${addr[0].toInt() and 0xFF}.${addr[1].toInt() and 0xFF}.${addr[2].toInt() and 0xFF}.${addr[3].toInt() and 0xFF}"
                }
                3 -> { // Domain
                    val len = inp.read()
                    val domain = ByteArray(len); inp.read(domain)
                    String(domain)
                }
                4 -> { // IPv6
                    val addr = ByteArray(16); inp.read(addr)
                    "::1"
                }
                else -> { client.close(); return@withContext }
            }
            val portHi = inp.read()
            val portLo = inp.read()
            val targetPort = (portHi shl 8) or portLo

            KighmuLogger.info(TAG, "SOCKS5 CONNECT -> $targetHost:$targetPort")

            // Connexion au serveur cible
            val remote = Socket()
            remote.connect(InetSocketAddress(targetHost, targetPort), 15000)
            KighmuLogger.info(TAG, "Connexion etablie vers $targetHost:$targetPort")

            // 4. Server reply: VER=5, REP=0(success), RSV=0, ATYP=1, BND.ADDR, BND.PORT
            out.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
            out.flush()
            KighmuLogger.info(TAG, "SOCKS5 tunnel etabli")

            // Relay bidirectionnel
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
            KighmuLogger.info(TAG, "Session terminee")

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
        session.setConfig("PreferredAuthentications", "publickey,password")
        session.setConfig("compression.s2c", "zlib@openssh.com,zlib,none")
        session.setConfig("compression.c2s", "zlib@openssh.com,zlib,none")
        session.setConfig("compression_level", "9")
        session.setProxy(com.jcraft.jsch.ProxySOCKS5("127.0.0.1", proxyPort))

        KighmuLogger.info(TAG, "SSH connexion via SOCKS5 proxy (timeout 30s)...")
        session.connect(30000)
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
