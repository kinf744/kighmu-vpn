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
    }

    private var running = false
    private var sshConnection: Connection? = null
    private var dnsttProcess: Process? = null
    private val engineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val dns get() = config.slowDns
    private val ssh get() = config.sshCredentials
    // Host SSH sans le port (au cas ou l'utilisateur met host:port dans le champ)
    private val sshHost get() = ssh.host.substringBefore(":")

    override suspend fun start(): Int = withContext(Dispatchers.IO) {
        running = true
        KighmuLogger.info(TAG, "=== Demarrage SlowDNS ===")
        KighmuLogger.info(TAG, "DNS: ${dns.dnsServer}:${dns.dnsPort}")
        KighmuLogger.info(TAG, "SSH: $sshHost:${ssh.port} / ${ssh.username}")

        if (dns.nameserver.isBlank()) throw Exception("Nameserver manquant")
        if (dns.publicKey.isBlank()) throw Exception("Public Key manquante")

        val dnsttBin = extractDnsttBinary()
        startDnsttProcess(dnsttBin)

        // Attendre que dnstt soit pret (max 30s, check toutes les 500ms)
        KighmuLogger.info(TAG, "Attente dnstt pret...")
        var waited = 0
        while (waited < 30000) {
            delay(500)
            waited += 500
            try {
                val sock = java.net.Socket()
                sock.connect(java.net.InetSocketAddress("127.0.0.1", DNSTT_PORT), 200)
                sock.close()
                KighmuLogger.info(TAG, "dnstt pret en ${waited}ms")
                break
            } catch (_: Exception) {}
        }

        // dnstt expose le flux SSH brut directement sur port 7000
        // trilead se connecte directement a 127.0.0.1:7000
        startSsh()
        KighmuLogger.info(TAG, "=== SSH connecte, SOCKS5 port $LOCAL_SOCKS_PORT ===")

        LOCAL_SOCKS_PORT
    }

    private var tun2socksProcess: Process? = null

    fun startTun2Socks(fd: Int) {
        KighmuLogger.info(TAG, "Demarrage tun2socks process (tunFd=$fd)")
        engineScope.launch(Dispatchers.IO) {
            try {
                // Utiliser le binaire tun2socks-armv7 comme processus externe
                val nativeDir = context.applicationInfo.nativeLibraryDir
                val bin = File(nativeDir, "libtun2socks.so")
                if (!bin.exists()) {
                    KighmuLogger.error(TAG, "libtun2socks.so introuvable dans $nativeDir")
                    return@launch
                }
                bin.setExecutable(true)
                // tun2socks v2 utilise --device /dev/fd/N
                // Utiliser /proc/self/fd/ pour acceder au fd VPN
                val fdPath = "/proc/self/fd/$fd"
                val cmd = listOf(
                    bin.absolutePath,
                    "--device", fdPath,
                    "--proxy", "socks5://127.0.0.1:$LOCAL_SOCKS_PORT",
                    "--loglevel", "info"
                )
                KighmuLogger.info(TAG, "tun2socks cmd: ${cmd.joinToString(" ")}")
                val pb = ProcessBuilder(cmd)
                pb.redirectErrorStream(true)
                tun2socksProcess = pb.start()
                // Lire les logs
                tun2socksProcess!!.inputStream.bufferedReader().forEachLine { line ->
                    KighmuLogger.info(TAG, "tun2socks: $line")
                }
            } catch (e: Exception) {
                KighmuLogger.error(TAG, "tun2socks error: ${e.message}")
            }
        }
    }

    private fun extractDnsttBinary(): File {
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val binFile = File(nativeDir, "libdnstt.so")
        KighmuLogger.info(TAG, "dnstt path: ${binFile.absolutePath}, existe: ${binFile.exists()}")
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
            throw Exception("dnstt crashed (exit=$exitVal)")
        } catch (_: IllegalThreadStateException) {
            KighmuLogger.info(TAG, "dnstt vivant OK")
        }
    }

    private fun startSsh() {
        // dnstt expose le flux SSH brut sur 127.0.0.1:7000
        // trilead se connecte directement comme si c'etait le vrai serveur SSH
        KighmuLogger.info(TAG, "Connexion SSH trilead -> dnstt 127.0.0.1:$DNSTT_PORT")

        val conn = Connection("127.0.0.1", DNSTT_PORT)
        val connInfo: ConnectionInfo = conn.connect(null, 120000, 120000)
        KighmuLogger.info(TAG, "SSH connecte! kex=${connInfo.keyExchangeAlgorithm} cipher=${connInfo.clientToServerCryptoAlgorithm}")

        val authenticated = conn.authenticateWithPassword(ssh.username, ssh.password)
        if (!authenticated) throw Exception("SSH auth echoue pour ${ssh.username}")
        KighmuLogger.info(TAG, "SSH authentifie!")

        // Dynamic SOCKS5 forwarding pour tun2socks
        conn.createDynamicPortForwarder(LOCAL_SOCKS_PORT)
        KighmuLogger.info(TAG, "Dynamic SOCKS5 forwarding actif sur $LOCAL_SOCKS_PORT")

        sshConnection = conn
    }

    override suspend fun stop() {
        running = false
        try { if (Tun2Socks.isAvailable) Tun2Socks.terminateTun2Socks() } catch (_: Exception) {}
        try { tun2socksProcess?.destroyForcibly() } catch (_: Exception) {}
        tun2socksProcess = null
        try { sshConnection?.close() } catch (_: Exception) {}
        sshConnection = null
        try { 
            dnsttProcess?.destroyForcibly()
            dnsttProcess?.destroy()
        } catch (_: Exception) {}
        // Killer aussi par nom de process au cas ou
        try {
            Runtime.getRuntime().exec("killall libdnstt.so")
            Runtime.getRuntime().exec("pkill -f libdnstt")
        } catch (_: Exception) {}
        dnsttProcess = null
        engineScope.cancel()
        KighmuLogger.info(TAG, "SlowDNS arrete")
    }

    override suspend fun sendData(data: ByteArray, length: Int) {}
    override suspend fun receiveData(): ByteArray? = null
    override fun isRunning() = running && sshConnection?.isAuthenticationComplete == true
}
