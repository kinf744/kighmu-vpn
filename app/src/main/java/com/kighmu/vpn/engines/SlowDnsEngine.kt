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
    private val context: Context,
    private val vpnService: android.net.VpnService? = null
) : TunnelEngine {

    companion object {
        const val TAG = "SlowDnsEngine"
        const val LOCAL_SOCKS_PORT = 10800
        const val DNSTT_PORT = 7000
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
        KighmuLogger.info(TAG, "DNS: ${dns.dnsServer}:${dns.dnsPort}")
        KighmuLogger.info(TAG, "Nameserver: ${dns.nameserver}")
        KighmuLogger.info(TAG, "SSH: ${ssh.host}:${ssh.port} / ${ssh.username}")

        if (dns.nameserver.isBlank()) throw Exception("Nameserver manquant")
        if (dns.publicKey.isBlank()) throw Exception("Public Key manquante")

        // Extraire le binaire dnstt-client depuis les assets
        val dnsttBin = extractDnsttBinary()
        KighmuLogger.info(TAG, "dnstt binaire: ${dnsttBin.absolutePath}")

        // Lancer dnstt-client
        startDnsttProcess(dnsttBin)

        // Attendre que dnstt soit pret
        KighmuLogger.info(TAG, "Attente dnstt (5s)...")
        delay(5000)

        // Connecter SSH via dnstt
        startSsh()
        KighmuLogger.info(TAG, "=== SlowDNS CONNECTE port $LOCAL_SOCKS_PORT ===")
        LOCAL_SOCKS_PORT
    }

    private fun extractDnsttBinary(): File {
        val binFile = File(context.filesDir, "dnstt-client")
        if (!binFile.exists() || binFile.length() == 0L) {
            KighmuLogger.info(TAG, "Extraction dnstt-client...")
            context.assets.open("dnstt-client").use { inp ->
                binFile.outputStream().use { out -> inp.copyTo(out) }
            }
        }
        binFile.setExecutable(true, false)
        KighmuLogger.info(TAG, "dnstt-client: ${binFile.length()} bytes, executable=${binFile.canExecute()}")
        return binFile
    }

    private fun startDnsttProcess(bin: File) {
        val cmd = listOf(
            bin.absolutePath,
            "-udp", "${dns.dnsServer}:${dns.dnsPort}",
            "-pubkey", dns.publicKey.trim(),
            dns.nameserver,
            "127.0.0.1:$DNSTT_PORT"
        )
        KighmuLogger.info(TAG, "Lancement: ${cmd.joinToString(" ")}")

        val pb = ProcessBuilder(cmd)
            .redirectErrorStream(true)
        
        // Proteger le processus du VPN si possible
        val process = pb.start()
        dnsttProcess = process

        // Logger les outputs de dnstt
        Thread {
            try {
                process.inputStream.bufferedReader().forEachLine { line ->
                    KighmuLogger.info(TAG, "dnstt: $line")
                }
            } catch (_: Exception) {}
        }.start()

        KighmuLogger.info(TAG, "dnstt-client lance (pid via Process)")
    }

    private fun startSsh() {
        KighmuLogger.info(TAG, "Connexion SSH via dnstt 127.0.0.1:$DNSTT_PORT...")
        val jsch = JSch()
        if (ssh.usePrivateKey && ssh.privateKey.isNotEmpty()) {
            jsch.addIdentity("key", ssh.privateKey.toByteArray(), null, null)
        }
        val session = jsch.getSession(ssh.username, "127.0.0.1", DNSTT_PORT)
        session.setPassword(ssh.password)
        session.setConfig("StrictHostKeyChecking", "no")
        session.setConfig("PreferredAuthentications", "password")
        session.setConfig("compression.s2c", "none")
        session.setConfig("compression.c2s", "none")
        session.setTimeout(0)
        KighmuLogger.info(TAG, "SSH connect (timeout 60s)...")
        session.connect(60000)
        jschSession = session
        KighmuLogger.info(TAG, "SSH connecte! ${session.serverVersion}")
        session.setPortForwardingL(LOCAL_SOCKS_PORT, "127.0.0.1", LOCAL_SOCKS_PORT)
        KighmuLogger.info(TAG, "SOCKS port $LOCAL_SOCKS_PORT forwarde")
    }

    override suspend fun stop() {
        running = false
        try { jschSession?.disconnect() } catch (_: Exception) {}
        jschSession = null
        try { dnsttProcess?.destroy() } catch (_: Exception) {}
        dnsttProcess = null
        engineScope.cancel()
        KighmuLogger.info(TAG, "SlowDNS arrete")
    }

    override suspend fun sendData(data: ByteArray, length: Int) {}
    override suspend fun receiveData(): ByteArray? = null
    override fun isRunning() = running && jschSession?.isConnected == true
}
