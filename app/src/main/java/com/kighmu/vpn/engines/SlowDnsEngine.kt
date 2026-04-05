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
    private val vpnService: android.net.VpnService? = null,
    private val profileIndex: Int = 0
) : TunnelEngine {

    companion object {
        const val TAG = "SlowDnsEngine"
        const val BASE_SOCKS_PORT = 10800

        // Pour compatibilité


        const val VPN_ADDRESS = "10.0.0.2"
        const val VPN_PREFIX = "24"
        const val MTU = 1500
    }

    private var _socksPort: Int = 0
    fun getSocksPort(): Int? = if (_socksPort > 0) _socksPort else null
    private val socksPort: Int get() {
        if (_socksPort == 0) {
            _socksPort = findFreePort(10800 + profileIndex)
        }
        return _socksPort
    }
    private var _dnsttPort: Int = 0
    private val dnsttPort: Int get() {
        if (_dnsttPort == 0) {
            _dnsttPort = findFreePort(7000 + profileIndex)
        }
        return _dnsttPort
    }
    private fun isPortFree(port: Int): Boolean = try {
        java.net.ServerSocket(port).use { true }
    } catch (_: Exception) { false }
    private fun findFreePort(preferred: Int): Int {
        for (p in preferred..preferred+20) {
            if (isPortFree(p)) return p
        }
        return java.net.ServerSocket(0).use { it.localPort }
    }
    private var running = false
    private var sshConnection: Connection? = null
    private var dnsttProcess: Process? = null
    private val engineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val dns get() = config.slowDns
    private val cleanPublicKey get() = config.slowDns.publicKey
        .trim()
        .replace(" ", "")
        .replace("\n", "")
        .replace("\r", "")
        .replace("\t", "")
        .replace("(", "")
        .replace(")", "")
        .replace("'", "")
        .replace("\"", "")
        .replace("`", "")
        .replace(";", "")
        .replace("&", "")
        .replace("|", "")
        .replace("$", "")
    // Fix: lire directement depuis config.slowDns (rempli par buildConfig)
    private val sshHostVal: String get() = config.slowDns.sshHost.substringBefore(":")
    private val sshPortVal: Int    get() = config.slowDns.sshPort
    private val sshUserVal: String get() = config.slowDns.sshUser
    private val sshPassVal: String get() = config.slowDns.sshPass
    override suspend fun start(): Int = withContext(Dispatchers.IO) {
        running = true
        KighmuLogger.info(TAG, "=== Demarrage SlowDNS ===")
        KighmuLogger.info(TAG, "Connexion SlowDNS en cours...")
        KighmuLogger.info(TAG, "Serveur SSH configuré ✓")

        if (dns.nameserver.isBlank()) throw Exception("Nameserver manquant")
        if (cleanPublicKey.isBlank()) throw Exception("Public Key manquante")

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
                sock.connect(java.net.InetSocketAddress("127.0.0.1", dnsttPort), 200)
                sock.close()
                KighmuLogger.info(TAG, "dnstt pret en ${waited}ms")
                break
            } catch (_: Exception) {}
        }

        // dnstt expose le flux SSH brut directement sur port 7000
        // trilead se connecte directement a 127.0.0.1:7000
        startSsh()
        KighmuLogger.info(TAG, "=== Tunnel SlowDNS actif ✓ ===")

        _socksPort
    }

    private var tun2socksProcess: Process? = null

    fun startTun2SocksOnPort(fd: Int, port: Int) {
        startTun2SocksInternal(fd, port)
    }

    override fun startTun2Socks(fd: Int) {
        startTun2SocksInternal(fd, socksPort)
    }

    private fun startTun2SocksInternal(fd: Int, targetPort: Int) {
        KighmuLogger.info(TAG, "Démarrage tunnel interface...")
        engineScope.launch(Dispatchers.IO) {
            try {
                if (Tun2Socks.isAvailable) {
                    // Utiliser tun2socks JNI SSH Custom (plus rapide)
                    KighmuLogger.info(TAG, "tun2socks JNI SSC fd=$fd port=$targetPort")
                    val t = Thread {
                        val result = Tun2Socks.runTun2Socks(
                            fd, MTU, "10.0.0.2", "255.255.255.0",
                            "127.0.0.1:$targetPort", "127.0.0.1:7300",
                            false, 3
                        )
                        KighmuLogger.info(TAG, "tun2socks JNI terminé: $result")
                    }
                    t.isDaemon = true
                    t.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, _ -> }
                    t.start()
                    KighmuLogger.info(TAG, "fd $fd envoye via JNI")
                    return@launch
                } else if (com.kighmu.vpn.engines.HevTun2Socks.isAvailable) {
                    // Utiliser hev-socks5-tunnel JNI (fallback rapide)
                    KighmuLogger.info(TAG, "hev tun2socks JNI fd=$fd port=$targetPort")
                    val t = Thread {
                        com.kighmu.vpn.engines.HevTun2Socks.start(context, fd, targetPort, MTU)
                        KighmuLogger.info(TAG, "hev tun2socks JNI terminé")
                    }
                    t.isDaemon = true
                    t.start()
                    KighmuLogger.info(TAG, "hev fd $fd démarré via JNI")
                    return@launch
                }
                // Fallback: processus externe
                val nativeDir = context.applicationInfo.nativeLibraryDir
                val bin = File(nativeDir, "libtun2socks.so")
                if (!bin.exists()) {
                    KighmuLogger.error(TAG, "libtun2socks.so introuvable dans $nativeDir")
                    return@launch
                }
                bin.setExecutable(true)
                // xjasonlyu/tun2socks v2 - passer fd via /proc/PID/fd/
                val pid = android.os.Process.myPid()
                val cmd = listOf(
                    bin.absolutePath,
                    "-device", "fd:///proc/$pid/fd/$fd",
                    "-proxy", "socks5://127.0.0.1:$targetPort",
                    "-loglevel", "warn"
                )
                KighmuLogger.info(TAG, "Interface VPN configurée ✓ fd=$fd port=$targetPort")
                val cmdArray = cmd.toTypedArray()
                tun2socksProcess = Runtime.getRuntime().exec(cmdArray)
                val proc = tun2socksProcess!!
                Thread {
                    try {
                        proc.errorStream.bufferedReader().forEachLine { line ->
                            if (running) KighmuLogger.info(TAG, "tun2socks: $line")
                        }
                    } catch (_: Exception) {}
                }.start()
                Thread {
                    try {
                        proc.inputStream.bufferedReader().forEachLine { line ->
                            if (running) KighmuLogger.info(TAG, "tun2socks: $line")
                        }
                    } catch (_: Exception) {}
                }.start()
                KighmuLogger.info(TAG, "xjasonlyu tun2socks démarré fd=$fd")
                try {
                    val exitCode = proc.exitValue()
                    KighmuLogger.error(TAG, "tun2socks terminé immédiatement: $exitCode")
                } catch (_: Exception) {
                    // Normal - processus toujours actif
                    KighmuLogger.info(TAG, "tun2socks actif")
                }
            } catch (e: Exception) {
                KighmuLogger.error(TAG, "tun2socks error: ${e.message}")
            }
        }
    }

    private fun extractDnsttBinary(): File {
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val binFile = File(nativeDir, "libdnstt.so")
        KighmuLogger.info(TAG, "Binaire DNS trouvé ✓")
        if (!binFile.exists()) throw Exception("libdnstt.so introuvable dans $nativeDir")
        return binFile
    }

    // Démarrer seulement dnstt sans SSH - pour XraySlowDnsEngine
    suspend fun startDnsttOnly(): Int {
        running = true
        val dns = config.slowDns
        if (dns.nameserver.isBlank()) throw Exception("Nameserver manquant")
        if (cleanPublicKey.isBlank()) throw Exception("Public Key manquante")
        val dnsttBin = extractDnsttBinary()
        startDnsttProcess(dnsttBin)
        // Attendre que dnstt ouvre le listener TCP
        KighmuLogger.info(TAG, "Attente dnstt pret...")
        delay(3000)
        // Vérifier que dnstt est toujours vivant
        if (dnsttProcess?.isAlive == false) throw Exception("dnstt mort au démarrage")
        KighmuLogger.info(TAG, "DNS tunnel prêt ✓")
        return dnsttPort
    }


    private fun startDnsttProcess(bin: File) {
        val cmd = listOf(
            bin.absolutePath,
            "-udp", "${dns.dnsServer}:${dns.dnsPort}",
            "-pubkey", cleanPublicKey,
            dns.nameserver,
            "127.0.0.1:$dnsttPort"
        )
        KighmuLogger.info(TAG, "Démarrage DNS tunnel...")
        // Log détaillé pour debug
        try {
            val f = java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "kighmu_key.txt")
            f.writeText(buildString {
                appendLine("=== DNSTT DEBUG ===")
                appendLine("time: ${java.util.Date()}")
                appendLine("pubkey raw: '${dns.publicKey}'")
                appendLine("pubkey clean: '${cleanPublicKey}'")
                appendLine("pubkey length: ${cleanPublicKey.length}")
                appendLine("nameserver: '${dns.nameserver}'")
                appendLine("dnsServer: '${dns.dnsServer}:${dns.dnsPort}'")
                appendLine("cmd: ${cmd.joinToString(" ")}")
            })
        } catch (_: Exception) {}

        val pb = ProcessBuilder(cmd).redirectErrorStream(true)
        pb.environment()["HOME"]   = context.filesDir.absolutePath
        pb.environment()["TMPDIR"] = context.cacheDir.absolutePath
        pb.directory(context.filesDir)

        val process = pb.start()
        dnsttProcess = process

        Thread {
            try {
                process.inputStream.bufferedReader().forEachLine { line ->
                    val skipDnstt = line.contains("begin stream") ||
                            line.contains("end stream") ||
                            line.contains("accepted") ||
                            line.contains("connection reset") ||
                            line.contains("broken pipe") ||
                            line.contains("copy stream") ||
                            line.contains("copy local") ||
                            line.contains("EOF")
                    if (running && !skipDnstt) KighmuLogger.info(TAG, "dnstt: $line")
                }
            } catch (e: Exception) {
                if (running) KighmuLogger.error(TAG, "dnstt stdout: ${e.message}")
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
        KighmuLogger.info(TAG, "Établissement connexion SSH...")

        val conn = Connection("127.0.0.1", dnsttPort)
        val connInfo: ConnectionInfo = conn.connect(null, 120000, 120000)
        KighmuLogger.info(TAG, "SSH connecté ✓")

        val authenticated = conn.authenticateWithPassword(sshUserVal, sshPassVal)
        if (!authenticated) throw Exception("SSH auth echoue pour ${sshUserVal}")
        KighmuLogger.info(TAG, "SSH authentifie!")

        // SOCKS5 proxy local avec port choisi par l'OS - garanti libre
        val socksServer = java.net.ServerSocket(0)
        _socksPort = socksServer.localPort
        socksServer.close()
        conn.createDynamicPortForwarder(_socksPort)
        KighmuLogger.info(TAG, "Proxy SOCKS5 actif ✓")
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
        try { Runtime.getRuntime().exec(arrayOf("sh", "-c", "kill -9 \$(lsof -ti:$dnsttPort) 2>/dev/null")) } catch (_: Exception) {}
        try { Runtime.getRuntime().exec(arrayOf("sh", "-c", "fuser -k $dnsttPort/tcp 2>/dev/null")) } catch (_: Exception) {}
        dnsttProcess = null
        engineScope.cancel()
        kotlinx.coroutines.delay(3000)
        KighmuLogger.info(TAG, "SlowDNS arrete")
    }

    override suspend fun sendData(data: ByteArray, length: Int) {}
    override suspend fun receiveData(): ByteArray? = null
    override fun isRunning() = running && sshConnection?.isAuthenticationComplete == true
}
