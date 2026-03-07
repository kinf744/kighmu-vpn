package com.kighmu.vpn.engines

import android.content.Context
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.kighmu.vpn.models.KighmuConfig
import com.kighmu.vpn.utils.KighmuLogger
import kotlinx.coroutines.*
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * SlowDNS Engine - utilise le vrai binaire dnstt-client
 *
 * Architecture:
 *   JSch -> dnstt-client (proxy TCP local) -> DNS TXT queries -> nameserver -> SSH server
 */
class SlowDnsEngine(
    private val config: KighmuConfig,
    private val context: Context
) : TunnelEngine {

    companion object {
        const val TAG = "SlowDnsEngine"
        const val LOCAL_SOCKS_PORT = 10800
        const val DNSTT_PROXY_PORT = 10854
    }

    private var running = false
    private var jschSession: Session? = null
    private var dnsttProcess: Process? = null
    private val engineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val dns get() = config.slowDns
    private val ssh get() = config.sshCredentials

    override suspend fun start(): Int = withContext(Dispatchers.IO) {
        running = true
        KighmuLogger.info(TAG, "=== Demarrage SlowDNS ===")
        KighmuLogger.info(TAG, "DNS Resolver : ${dns.dnsServer}:${dns.dnsPort}")
        KighmuLogger.info(TAG, "Nameserver   : ${dns.nameserver}")
        KighmuLogger.info(TAG, "Public Key   : ${if (dns.publicKey.isNotBlank()) dns.publicKey.take(30)+"..." else "VIDE!"}")
        KighmuLogger.info(TAG, "SSH Host     : ${ssh.host}:${ssh.port}")
        KighmuLogger.info(TAG, "SSH User     : ${ssh.username}")

        if (dns.nameserver.isBlank()) throw Exception("Nameserver manquant")
        if (dns.publicKey.isBlank()) throw Exception("Public Key manquante")
        if (ssh.host.isBlank()) throw Exception("SSH Host manquant")
        if (ssh.username.isBlank() || ssh.password.isBlank()) throw Exception("SSH credentials manquants")

        try {
            // Etape 1: Extraire et demarrer dnstt-client
            val dnsttBin = extractDnsttBinary()
            if (dnsttBin != null) {
                KighmuLogger.info(TAG, "dnstt-client trouve: ${dnsttBin.absolutePath}")
                startDnsttClient(dnsttBin)
                delay(2000) // Attendre que dnstt-client soit pret
                KighmuLogger.info(TAG, "SlowDns running via dnstt-client")
            } else {
                KighmuLogger.warning(TAG, "dnstt-client non trouve, utilisation fallback TCP")
                startFallbackProxy()
                delay(500)
            }

            // Etape 2: SSH via dnstt proxy local
            KighmuLogger.info(TAG, "Connecting to ${ssh.host} via dnstt proxy")
            startSsh()

            KighmuLogger.info(TAG, "=== SlowDNS CONNECTE - SOCKS5 port $LOCAL_SOCKS_PORT ===")
            LOCAL_SOCKS_PORT

        } catch (e: com.jcraft.jsch.JSchException) {
            val msg = when {
                e.message?.contains("Auth fail") == true -> "Auth SSH echouee (user=${ssh.username})"
                e.message?.contains("timeout") == true -> "Timeout - dnstt proxy ou nameserver inaccessible"
                e.message?.contains("Premature") == true -> "Connexion prematuree - reessayez"
                e.message?.contains("Connection refused") == true -> "dnstt proxy non demarre"
                else -> "SSH: ${e.message}"
            }
            KighmuLogger.error(TAG, "ECHEC SSH: $msg")
            throw Exception(msg)
        } catch (e: Exception) {
            KighmuLogger.error(TAG, "ECHEC: ${e.javaClass.simpleName}: ${e.message}")
            throw e
        }
    }

    private fun extractDnsttBinary(): File? {
        return try {
            val binFile = File(context.filesDir, "dnstt-client")
            // Extraire depuis assets si pas encore present
            val assetName = "dnstt/dnstt-client"
            val assetList = context.assets.list("dnstt") ?: emptyArray()
            KighmuLogger.info(TAG, "Assets dnstt: ${assetList.joinToString()}")

            if (assetList.contains("dnstt-client")) {
                context.assets.open(assetName).use { inp ->
                    binFile.outputStream().use { out -> inp.copyTo(out) }
                }
                binFile.setExecutable(true, false)
                KighmuLogger.info(TAG, "dnstt-client extrait: ${binFile.absolutePath} (${binFile.length()} bytes)")
                binFile
            } else {
                KighmuLogger.warning(TAG, "dnstt-client absent des assets")
                null
            }
        } catch (e: Exception) {
            KighmuLogger.error(TAG, "Extraction dnstt: ${e.message}")
            null
        }
    }

    private fun startDnsttClient(bin: File) {
        // dnstt-client -udp 8.8.8.8:53 -pubkey-base64 KEY -transport dns NAMESERVER:PORT 127.0.0.1:DNSTT_PORT
        val cmd = listOf(
            bin.absolutePath,
            "-udp", "${dns.dnsServer}:${dns.dnsPort}",
            "-pubkey-base64", dns.publicKey,
            "-transport", "dns",
            "${dns.nameserver}:${ssh.port}",
            "127.0.0.1:$DNSTT_PROXY_PORT"
        )
        KighmuLogger.info(TAG, "Commande dnstt: ${cmd.joinToString(" ")}")

        val pb = ProcessBuilder(cmd)
            .redirectErrorStream(true)
            .directory(context.filesDir)
        dnsttProcess = pb.start()

        // Logger la sortie dnstt
        engineScope.launch(Dispatchers.IO) {
            dnsttProcess?.inputStream?.bufferedReader()?.forEachLine { line ->
                KighmuLogger.info("dnstt", line)
            }
        }
        KighmuLogger.info(TAG, "dnstt-client demarre (PID: ${dnsttProcess?.pid()})")
    }

    private fun startFallbackProxy() {
        // Proxy TCP simple qui forward vers SSH directement (sans DNS)
        KighmuLogger.warning(TAG, "FALLBACK: connexion SSH directe (sans DNS)")
        engineScope.launch(Dispatchers.IO) {
            val server = ServerSocket(DNSTT_PROXY_PORT)
            while (running) {
                try {
                    val client = server.accept()
                    launch {
                        val remote = Socket()
                        remote.connect(InetSocketAddress(ssh.host, ssh.port), 10000)
                        val t1 = launch { client.getInputStream().copyTo(remote.getOutputStream()) }
                        val t2 = launch { remote.getInputStream().copyTo(client.getOutputStream()) }
                        t1.join(); t2.cancel()
                        client.close(); remote.close()
                    }
                } catch (e: Exception) {
                    if (running) KighmuLogger.error(TAG, "Fallback proxy: ${e.message}")
                    break
                }
            }
            server.close()
        }
    }

    private fun startSsh() {
        val jsch = JSch()
        if (ssh.usePrivateKey && ssh.privateKey.isNotEmpty()) {
            jsch.addIdentity("key", ssh.privateKey.toByteArray(), null, null)
        }

        val session = jsch.getSession(ssh.username, "127.0.0.1", DNSTT_PROXY_PORT)
        session.setPassword(ssh.password)
        session.setConfig("StrictHostKeyChecking", "no")
        session.setConfig("PreferredAuthentications", "publickey,password")
        session.setConfig("compression.s2c", "zlib@openssh.com,zlib,none")
        session.setConfig("compression.c2s", "zlib@openssh.com,zlib,none")
        session.setConfig("compression_level", "9")

        KighmuLogger.info(TAG, "SSH connexion via dnstt proxy (timeout 45s)...")
        session.connect(45000)
        jschSession = session

        KighmuLogger.info(TAG, "SSH connecte! ${session.serverVersion}")
        session.setPortForwardingL(LOCAL_SOCKS_PORT, "127.0.0.1", LOCAL_SOCKS_PORT)
        KighmuLogger.info(TAG, "Socks port $LOCAL_SOCKS_PORT")
        KighmuLogger.info(TAG, "=== SlowDNS TUNNEL ACTIF ===")
    }

    override suspend fun stop() {
        running = false
        jschSession?.disconnect()
        jschSession = null
        dnsttProcess?.destroy()
        dnsttProcess = null
        engineScope.cancel()
        KighmuLogger.info(TAG, "SlowDNS arrete")
    }

    override suspend fun sendData(data: ByteArray, length: Int) {}
    override suspend fun receiveData(): ByteArray? = null
    override fun isRunning() = running && jschSession?.isConnected == true
}
