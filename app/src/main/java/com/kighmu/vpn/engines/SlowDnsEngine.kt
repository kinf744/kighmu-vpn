package com.kighmu.vpn.engines

import android.content.Context
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.kighmu.vpn.models.KighmuConfig
import com.kighmu.vpn.utils.KighmuLogger
import kotlinx.coroutines.*
import java.net.InetAddress

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
    private val engineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val dns get() = config.slowDns
    private val ssh get() = config.sshCredentials

    override suspend fun start(): Int = withContext(Dispatchers.IO) {
        running = true
        KighmuLogger.info(TAG, "=== Demarrage SSH SlowDNS ===")
        KighmuLogger.info(TAG, "DNS Server: ${dns.dnsServer}")
        KighmuLogger.info(TAG, "Nameserver: ${dns.nameserver}")
        KighmuLogger.info(TAG, "PublicKey: ${if (dns.publicKey.isNotBlank()) dns.publicKey.take(20)+"..." else "VIDE"}")
        KighmuLogger.info(TAG, "SSH: ${ssh.host}:${ssh.port} user=${ssh.username}")

        if (dns.nameserver.isBlank()) throw Exception("Nameserver SlowDNS manquant")
        if (dns.publicKey.isBlank()) throw Exception("Public Key SlowDNS manquante")
        if (ssh.host.isBlank()) throw Exception("SSH Host manquant")
        if (ssh.username.isBlank()) throw Exception("SSH Username manquant")
        if (ssh.password.isBlank()) throw Exception("SSH Password manquant")

        try {
            KighmuLogger.info(TAG, "Resolution DNS de ${ssh.host}...")
            val ip = try {
                InetAddress.getByName(ssh.host).hostAddress ?: ssh.host
            } catch (e: Exception) {
                KighmuLogger.warning(TAG, "Resolution echouee, utilisation directe: ${ssh.host}")
                ssh.host
            }
            KighmuLogger.info(TAG, "IP: $ip")

            val jsch = JSch()
            if (ssh.usePrivateKey && ssh.privateKey.isNotEmpty()) {
                jsch.addIdentity("key", ssh.privateKey.toByteArray(), null, null)
                KighmuLogger.info(TAG, "Cle privee SSH chargee")
            }

            KighmuLogger.info(TAG, "Connexion SSH vers $ip:${ssh.port}...")
            val session = jsch.getSession(ssh.username, ip, ssh.port)
            session.setPassword(ssh.password)
            session.setConfig("StrictHostKeyChecking", "no")
            session.setConfig("PreferredAuthentications", "publickey,password")
            session.connect(15000)

            jschSession = session
            KighmuLogger.info(TAG, "SSH connecte! Version: ${session.serverVersion}")

            session.setPortForwardingL(LOCAL_SOCKS_PORT, "127.0.0.1", LOCAL_SOCKS_PORT)
            KighmuLogger.info(TAG, "SOCKS5 actif sur port $LOCAL_SOCKS_PORT")
            KighmuLogger.info(TAG, "=== SlowDNS TUNNEL ACTIF ===")

            LOCAL_SOCKS_PORT
        } catch (e: com.jcraft.jsch.JSchException) {
            val msg = when {
                e.message?.contains("Auth fail") == true -> "Auth echouee - verifiez username/password"
                e.message?.contains("Connection refused") == true -> "Connexion refusee - verifiez host/port"
                e.message?.contains("timeout") == true -> "Timeout - serveur inaccessible"
                e.message?.contains("UnknownHost") == true -> "Host inconnu - verifiez DNS"
                else -> e.message ?: "Erreur SSH inconnue"
            }
            KighmuLogger.error(TAG, "ECHEC SSH: $msg")
            throw Exception(msg)
        }
    }

    override suspend fun stop() {
        running = false
        jschSession?.disconnect()
        jschSession = null
        engineScope.cancel()
        KighmuLogger.info(TAG, "SlowDNS arrete")
    }

    override suspend fun sendData(data: ByteArray, length: Int) {}
    override suspend fun receiveData(): ByteArray? = null
    override fun isRunning() = running && jschSession?.isConnected == true
}