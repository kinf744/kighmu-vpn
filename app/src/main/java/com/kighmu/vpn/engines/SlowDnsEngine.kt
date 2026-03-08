package com.kighmu.vpn.engines

import android.content.Context
import com.trilead.ssh2.Connection
import com.trilead.ssh2.ConnectionInfo
import com.kighmu.vpn.models.KighmuConfig
import com.kighmu.vpn.utils.KighmuLogger
import kotlinx.coroutines.*
import java.io.File

class SlowDnsEngine(
    private val config: KighmuConfig,
    private val context: Context,
    private val vpnService: android.net.VpnService? = null
) : TunnelEngine {

    companion object {
        const val TAG = "SlowDnsEngine"
        const val LOCAL_SOCKS_PORT = 10800
        const val DNSTT_PORT = 7000
        const val VPN_ADDRESS = "10.0.0.2"
        const val VPN_PREFIX = "24"
        const val MTU = 1500
        // Version string identique a SSH Custom
        const val SSH_CLIENT_VERSION = "SSH-2.0-easyPro"
    }

    private var running = false
    private var sshConnection: Connection? = null
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

        // 1. Lancer dnstt-client
        val dnsttBin = extractDnsttBinary()
        startDnsttProcess(dnsttBin)

        // 2. Attendre que dnstt soit pret
        KighmuLogger.info(TAG, "Attente dnstt (15s)...")
        delay(15000)

        // 3. Connecter SSH via trilead (identique SSH Custom)
        startSsh()
        KighmuLogger.info(TAG, "=== SSH connecte, SOCKS5 port $LOCAL_SOCKS_PORT ===")

        LOCAL_SOCKS_PORT
    }

    fun startTun2Socks(fd: Int) {
        KighmuLogger.info(TAG, "Demarrage tun2socks (tunFd=$fd)")
        engineScope.launch(Dispatchers.IO) {
            try {
                Tun2Socks.runTun2Socks(
                    tunFd               = fd,
                    mtu                 = MTU,
                    ip                  = VPN_ADDRESS,
                    prefix              = VPN_PREFIX,
                    socksServerAddress  = "127.0.0.1:$LOCAL_SOCKS_PORT",
                    udpgwServerAddress  = "127.0.0.1:7300",
                    udpgwTransparentDNS = true
                )
            } catch (e: Exception) {
                KighmuLogger.error(TAG, "tun2socks error: ${e.message}")
            }
        }
        KighmuLogger.info(TAG, "tun2socks lance")
    }

    private fun extractDnsttBinary(): File {
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val binFile = File(nativeDir, "libdnstt.so")
        KighmuLogger.info(TAG, "dnstt path: ${binFile.absolutePath}")
        KighmuLogger.info(TAG, "dnstt existe: ${binFile.exists()}, taille: ${binFile.length()}, exec: ${binFile.canExecute()}")
        if (!binFile.exists()) throw Exception("libdnstt.so introuvable dans $nativeDir")
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
        KighmuLogger.info(TAG, "Lancement dnstt: ${cmd.joinToString(" ")}")

        val pb = ProcessBuilder(cmd).redirectErrorStream(true)
        pb.environment()["HOME"]   = context.filesDir.absolutePath
        pb.environment()["TMPDIR"] = context.cacheDir.absolutePath
        pb.directory(context.filesDir)

        val process = pb.start()
        dnsttProcess = process

        Thread {
            try {
                process.inputStream.bufferedReader().forEachLine { line ->
                    KighmuLogger.info(TAG, "dnstt: $line")
                }
            } catch (e: Exception) {
                KighmuLogger.error(TAG, "dnstt stdout: ${e.message}")
            }
        }.start()

        Thread.sleep(2000)
        try {
            val exitVal = process.exitValue()
            throw Exception("dnstt-client crashed (exit=$exitVal)")
        } catch (_: IllegalThreadStateException) {
            KighmuLogger.info(TAG, "dnstt vivant OK")
        }
    }

    private fun startSsh() {
        KighmuLogger.info(TAG, "Connexion SSH trilead -> 127.0.0.1:$DNSTT_PORT")

        // Utiliser trilead-ssh2 exactement comme SSH Custom
        val conn = Connection("127.0.0.1", DNSTT_PORT)
        conn.setClient_version(SSH_CLIENT_VERSION)

        // Connecter avec timeout 120s (tunnel DNS est lent)
        val connInfo: ConnectionInfo = conn.connect(
            null,          // verifier host key: null = accepter tout
            120000,        // connect timeout ms
            120000         // kex timeout ms
        )
        KighmuLogger.info(TAG, "SSH connecte! kex=${connInfo.keyExchangeAlgorithm} cipher=${connInfo.clientToServerCryptoAlgorithm}")

        // Authentifier
        val authenticated = conn.authenticateWithPassword(ssh.username, ssh.password)
        if (!authenticated) throw Exception("SSH auth echoue pour ${ssh.username}")
        KighmuLogger.info(TAG, "SSH authentifie!")

        // Port forwarding local: 127.0.0.1:10800 -> 127.0.0.1:10800 via SSH
        conn.createLocalPortForwarder(LOCAL_SOCKS_PORT, "127.0.0.1", LOCAL_SOCKS_PORT)
        KighmuLogger.info(TAG, "Port forward local $LOCAL_SOCKS_PORT OK")

        sshConnection = conn
    }

    override suspend fun stop() {
        running = false
        try { Tun2Socks.terminateTun2Socks() } catch (_: Exception) {}
        try { sshConnection?.close() } catch (_: Exception) {}
        sshConnection = null
        try { dnsttProcess?.destroy() } catch (_: Exception) {}
        dnsttProcess = null
        engineScope.cancel()
        KighmuLogger.info(TAG, "SlowDNS arrete")
    }

    override suspend fun sendData(data: ByteArray, length: Int) {}
    override suspend fun receiveData(): ByteArray? = null
    override fun isRunning() = running && sshConnection?.isAuthenticationComplete == true
}
