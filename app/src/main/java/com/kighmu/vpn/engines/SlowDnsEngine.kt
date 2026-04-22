package com.kighmu.vpn.engines

import android.content.Context
import com.trilead.ssh2.Connection
import com.kighmu.vpn.models.KighmuConfig
import com.kighmu.vpn.utils.KighmuLogger
import kotlinx.coroutines.*
import java.io.File

class SlowDnsEngine(
    private val config: KighmuConfig,
    val context: Context,
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
            // Décalage plus important pour éviter les collisions entre profils
            _dnsttPort = findFreePort(7000 + (profileIndex * 10))
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
    @Volatile private var sshAlive = false
    private var sshConnection: Connection? = null
    private var dnsttProcess: Process? = null
    private var relayPfd: android.os.ParcelFileDescriptor? = null
    private var relayInstance: com.kighmu.vpn.vpn.Tun2SocksRelay? = null
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

        // Phase 1 : démarrer dnstt seulement si pas déjà vivant
        if (dnsttProcess == null || dnsttProcess?.isAlive == false) {
            val dnsttBin = extractDnsttBinary()
            startDnsttProcess(dnsttBin)

            // Attendre que dnstt soit prêt (max 8s, check toutes les 200ms)
            KighmuLogger.info(TAG, "Attente dnstt prêt...")
            var waited = 0
            while (waited < 8000) {
                delay(200)
                waited += 200
                try {
                    val sock = java.net.Socket()
                    sock.connect(java.net.InetSocketAddress("127.0.0.1", dnsttPort), 100)
                    sock.close()
                    KighmuLogger.info(TAG, "dnstt prêt en ${waited}ms")
                    break
                } catch (_: Exception) {}
            }
        } else {
            KighmuLogger.info(TAG, "dnstt déjà vivant - réutilisation ✓")
        }

        // Phase 2 : SSH uniquement (rapide, retry possible sans relancer dnstt)
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
                // Forcer l'initialisation du JNI si nécessaire
                try { com.kighmu.vpn.engines.HevTun2Socks.init() } catch (_: Exception) {}

                // 1. Priorité 1 : HevTun2Socks (UDP natif, MTU 8500, pas de udpgw)
                if (com.kighmu.vpn.engines.HevTun2Socks.isAvailable && vpnService != null) {
                    KighmuLogger.info(TAG, "HevTun2Socks fd=$fd port=$targetPort")
                    val t = Thread {
                        try {
                            com.kighmu.vpn.engines.HevTun2Socks.start(context, fd, targetPort, vpnService, 8500)
                            KighmuLogger.info(TAG, "HevTun2Socks démarré ✅")
                        } catch (e: Exception) {
                            KighmuLogger.error(TAG, "Erreur HevTun2Socks: ${e.message}")
                        }
                    }
                    t.isDaemon = true
                    t.start()
                    KighmuLogger.info(TAG, "fd $fd routé via HevTun2Socks")
                    return@launch
                }

                // 2. Fallback : Tun2Socks JNI (UDP limité, requiert udpgw:7300)
                if (Tun2Socks.isAvailable) {
                    KighmuLogger.warning(TAG, "HevTun2Socks indisponible - fallback Tun2Socks JNI (available=${Tun2Socks.isAvailable})")
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
                    KighmuLogger.info(TAG, "fd $fd routé via Tun2Socks JNI")
                    return@launch
                }

                // 3. Dernier recours : Relay Kotlin
                KighmuLogger.warning(TAG, "HevTun2Socks=false Tun2Socks=false → Relay Kotlin port=$targetPort ⚠️")
                relayPfd?.close()
                relayPfd = android.os.ParcelFileDescriptor.fromFd(fd)
                relayInstance = com.kighmu.vpn.vpn.Tun2SocksRelay(relayPfd!!.fileDescriptor, "127.0.0.1", targetPort)
                relayInstance!!.start()
                KighmuLogger.info(TAG, "Relay Kotlin démarré ✓")
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
        delay(1000)
        // Vérifier que dnstt est toujours vivant
        if (dnsttProcess?.isAlive == false) throw Exception("dnstt mort au démarrage")
        KighmuLogger.info(TAG, "DNS tunnel prêt ✓")
        return dnsttPort
    }


    private fun startDnsttProcess(bin: File) {
        // DNSTT ne supporte pas le flag -mtu en ligne de commande. 
        // La stabilité sera gérée par les buffers Xray et Tun2SocksRelay.
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

        Thread.sleep(500)
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

        // Bridge banniere : lit SSH-2.0-xxx puis relaie tout a Trilead
        val bannerProxyPort = run {
            val ss = java.net.ServerSocket(0); ss.reuseAddress = true; val p = ss.localPort; ss.close(); p
        }
        val bannerLatch = java.util.concurrent.CountDownLatch(1)
        var capturedBanner = ""
        Thread {
            try {
                val proxyServer = java.net.ServerSocket(bannerProxyPort)
                bannerLatch.countDown()
                val trileadSock = proxyServer.accept()
                proxyServer.close()
                // Protéger le socket trilead contre le tunnel VPN
                try { vpnService?.protect(trileadSock) } catch (_: Exception) {}
                val realSock = java.net.Socket("127.0.0.1", dnsttPort)
                // Protéger le socket dnstt contre le tunnel VPN
                try { vpnService?.protect(realSock) } catch (_: Exception) {}
                realSock.soTimeout = 0 // pas de timeout: bridge doit vivre tant que SSH vit
                val realIn = realSock.getInputStream()
                val bannerBytes = StringBuilder()
                var b: Int
                while (realIn.read().also { b = it } != -1) {
                    bannerBytes.append(b.toChar())
                    if (bannerBytes.endsWith("\n")) break
                }
                capturedBanner = bannerBytes.toString().trim()
                val trileadOut = trileadSock.getOutputStream()
                trileadOut.write(bannerBytes.toString().toByteArray())
                trileadOut.flush()
                try { realSock.tcpNoDelay = true } catch (_: Exception) {}
                try { trileadSock.tcpNoDelay = true } catch (_: Exception) {}
                val t1 = Thread { try { realIn.copyTo(trileadSock.getOutputStream()) } catch (_: Exception) {} }
                val t2 = Thread { try { trileadSock.getInputStream().copyTo(realSock.getOutputStream()) } catch (_: Exception) {} }
                t1.isDaemon = true; t2.isDaemon = true
                t1.start(); t2.start()
            } catch (e: Exception) {
                bannerLatch.countDown()
                KighmuLogger.error(TAG, "BannerProxy error: ${e.message}")
            }
        }.also { it.isDaemon = true }.start()
        bannerLatch.await(3, java.util.concurrent.TimeUnit.SECONDS)
        val conn = Connection("127.0.0.1", bannerProxyPort)

        // ── Compression zlib : réduit volume DNS de 40-60% ─────────────────
        conn.setCompression(true)

        // ── Timeouts réduits : détection rapide des pannes ─────────────────
        conn.connect(null, 3000, 8000)
        if (capturedBanner.isNotEmpty()) KighmuLogger.info(TAG, "Server version: $capturedBanner")
        KighmuLogger.info(TAG, "SSH connecté ✓")

        val authenticated = conn.authenticateWithPassword(sshUserVal, sshPassVal)
        if (!authenticated) throw Exception("SSH auth echoue pour ${sshUserVal}")
        KighmuLogger.info(TAG, "SSH authentifié ✓")

        // ── SOCKS5 proxy local port libre garanti ───────────────────────────
        // Utiliser le port déjà calculé dans socksPort getter (évite race condition)
        if (_socksPort == 0) _socksPort = findFreePort(10800 + profileIndex)
        conn.createDynamicPortForwarder(java.net.InetSocketAddress("127.0.0.1", _socksPort))
        KighmuLogger.info(TAG, "SOCKS5 actif sur port $_socksPort ✓")

        // ── Keep-alive toutes les 20s avec détection de mort ─────────────────
        engineScope.launch {
            var keepAliveRunning = true
            while (running && keepAliveRunning) {
                delay(20_000)
                if (!running) { keepAliveRunning = false; continue }
                try {
                    val ok = withTimeoutOrNull(5_000) { conn.sendIgnorePacket(); true } ?: false
                    if (!ok) {
                        KighmuLogger.error(TAG, "Keep-alive timeout → tunnel mort, marquage sshAlive=false")
                        sshAlive = false
                        keepAliveRunning = false
                    }
                } catch (e: Exception) {
                    KighmuLogger.error(TAG, "Keep-alive erreur → tunnel mort: ${e.message}")
                    sshAlive = false
                    keepAliveRunning = false
                }
            }
        }

        sshConnection = conn
        sshAlive = true
    }

    // Arrêter seulement SSH - garder dnstt vivant pour retry rapide
    fun stopSshOnly() {
        sshAlive = false
        try { sshConnection?.close() } catch (_: Exception) {}
        sshConnection = null
        _socksPort = 0
        KighmuLogger.info(TAG, "SSH fermé (dnstt conservé pour retry)")
    }

    override suspend fun stop() {
        running = false
        sshAlive = false
        // HevTun2Socks géré globalement par MultiSlowDnsEngine
        try { if (Tun2Socks.isAvailable) Tun2Socks.terminateTun2Socks() } catch (_: Exception) {}
        try { tun2socksProcess?.destroyForcibly() } catch (_: Exception) {}
        tun2socksProcess = null
        try { sshConnection?.close() } catch (_: Exception) {}
        sshConnection = null
        try {
            dnsttProcess?.destroyForcibly()
            dnsttProcess?.destroy()
        } catch (_: Exception) {}
        
        // Principe du build #736 : Nettoyage ciblé du port dnstt
        try { Runtime.getRuntime().exec(arrayOf("sh", "-c", "kill -9 \$(lsof -ti:$dnsttPort) 2>/dev/null")) } catch (_: Exception) {}
        try { Runtime.getRuntime().exec(arrayOf("sh", "-c", "fuser -k $dnsttPort/tcp 2>/dev/null")) } catch (_: Exception) {}
        
        dnsttProcess = null
        try { relayInstance?.stop() } catch (_: Exception) {}
        relayInstance = null
        try { relayPfd?.close() } catch (_: Exception) {}
        relayPfd = null
        engineScope.cancel()
        
        // Délai de grâce optimisé (500ms) pour libérer les sockets noyau sans ralentir l'UI
        kotlinx.coroutines.delay(500)
        
        KighmuLogger.info(TAG, "SlowDNS arrete")
    }

    override suspend fun sendData(data: ByteArray, length: Int) {}
    override suspend fun receiveData(): ByteArray? = null
    override fun isRunning() = running && sshAlive
}
