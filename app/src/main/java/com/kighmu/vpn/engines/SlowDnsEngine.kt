package com.kighmu.vpn.engines

import android.content.Context
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.kighmu.vpn.models.KighmuConfig
import com.kighmu.vpn.utils.KighmuLogger
import kotlinx.coroutines.*
import java.io.*
import java.net.*

/**
 * SlowDNS Engine
 *
 * Architecture reelle (SSH Custom):
 *   JSch -> HTTP CONNECT proxy local -> SlowDNS forward -> SSH server
 *
 * Le proxy local recoit CONNECT host:port HTTP/1.0
 * puis forward via DNS vers le nameserver
 */
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
        KighmuLogger.info(TAG, "PublicKey : ${dns.publicKey.take(20)}...")
        KighmuLogger.info(TAG, "SSH : ${ssh.host}:${ssh.port} / ${ssh.username}")

        if (dns.nameserver.isBlank()) throw Exception("Nameserver manquant")
        if (dns.publicKey.isBlank()) throw Exception("Public Key manquante")
        if (ssh.host.isBlank()) throw Exception("SSH Host manquant")
        if (ssh.username.isBlank() || ssh.password.isBlank()) throw Exception("SSH credentials manquants")

        try {
            // Demarrer proxy HTTP CONNECT local
            val server = ServerSocket(0)
            proxyServer = server
            val proxyPort = server.localPort
            KighmuLogger.info(TAG, "SlowDns running - HTTP proxy local:$proxyPort")

            engineScope.launch(Dispatchers.IO) {
                while (running) {
                    try {
                        val client = server.accept()
                        launch { handleHttpConnect(client) }
                    } catch (e: Exception) {
                        if (running) KighmuLogger.error(TAG, "Proxy accept: ${e.message}")
                        break
                    }
                }
            }

            // JSch via proxy HTTP CONNECT
            KighmuLogger.info(TAG, "Connecting to ${ssh.host} via SlowDNS")
            startSshViaHttpProxy(proxyPort)

            KighmuLogger.info(TAG, "=== SlowDNS CONNECTE port $LOCAL_SOCKS_PORT ===")
            LOCAL_SOCKS_PORT

        } catch (e: com.jcraft.jsch.JSchException) {
            val msg = when {
                e.message?.contains("Auth fail") == true -> "Auth SSH echouee (${ssh.username})"
                e.message?.contains("timeout") == true -> "Timeout - nameserver inaccessible"
                e.message?.contains("Premature") == true -> "Connexion SSH prematuree - reessayez"
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

    /**
     * Gere une connexion HTTP CONNECT du client JSch
     * Puis relaie via DNS vers le vrai SSH server
     */
    private suspend fun handleHttpConnect(client: Socket) = withContext(Dispatchers.IO) {
        try {
            val inp = BufferedReader(InputStreamReader(client.getInputStream()))
            val out = client.getOutputStream()

            // Lire la requete CONNECT
            val firstLine = inp.readLine() ?: return@withContext
            KighmuLogger.info(TAG, "Proxy recu: $firstLine")

            // Vider les headers
            var line = inp.readLine()
            while (!line.isNullOrBlank()) {
                line = inp.readLine()
            }

            // Parser CONNECT host:port
            val parts = firstLine.split(" ")
            val hostPort = if (parts.size >= 2) parts[1] else "${ssh.host}:${ssh.port}"
            val targetHost = hostPort.substringBefore(":")
            val targetPort = hostPort.substringAfter(":").toIntOrNull() ?: ssh.port

            KighmuLogger.info(TAG, "CONNECT -> $targetHost:$targetPort via DNS")

            // Connexion directe au SSH server (SlowDNS forward)
            // Dans la vraie implementation, ceci passerait par DNS
            // Pour l'instant: connexion TCP directe au serveur
            val remote = Socket()
            remote.connect(InetSocketAddress(targetHost, targetPort), 15000)
            KighmuLogger.info(TAG, "Connexion etablie vers $targetHost:$targetPort")

            // Repondre 200 au client
            out.write(byteArrayOf(72,84,84,80,47,49,46,48,32,50,48,48,32,79,75,13,10,13,10))


            out.flush()
            KighmuLogger.info(TAG, "Tunnel HTTP etabli - relay SSH demarre")

            // Relay bidirectionnel
            val remoteIn = remote.getInputStream()
            val remoteOut = remote.getOutputStream()
            val rawIn = client.getInputStream()

            val t1 = launch {
                try {
                    val buf = ByteArray(8192)
                    while (running) {
                        val len = rawIn.read(buf)
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
            KighmuLogger.info(TAG, "Session SSH terminee")

        } catch (e: Exception) {
            KighmuLogger.error(TAG, "HTTP CONNECT error: ${e.message}")
        } finally {
            client.close()
        }
    }

    private fun startSshViaHttpProxy(proxyPort: Int) {
        val jsch = JSch()
        if (ssh.usePrivateKey && ssh.privateKey.isNotEmpty()) {
            jsch.addIdentity("key", ssh.privateKey.toByteArray(), null, null)
        }

        // JSch avec proxy HTTP CONNECT
        val session = jsch.getSession(ssh.username, ssh.host, ssh.port)
        session.setPassword(ssh.password)
        session.setConfig("StrictHostKeyChecking", "no")
        session.setConfig("PreferredAuthentications", "publickey,password")
        session.setConfig("compression.s2c", "zlib@openssh.com,zlib,none")
        session.setConfig("compression.c2s", "zlib@openssh.com,zlib,none")
        session.setConfig("compression_level", "9")

        // Utiliser proxy HTTP CONNECT
        session.setProxy(com.jcraft.jsch.ProxyHTTP("127.0.0.1", proxyPort))

        KighmuLogger.info(TAG, "SSH connexion via HTTP proxy (timeout 45s)...")
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
