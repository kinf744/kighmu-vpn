package com.kighmu.vpn.engines

import android.content.Context
import android.net.VpnService
import com.kighmu.vpn.models.KighmuConfig
import com.kighmu.vpn.utils.KighmuLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class ZivpnEngine(
    private val config: KighmuConfig,
    private val context: Context,
    private val vpnService: VpnService? = null
) : TunnelEngine {

    companion object {
        const val TAG = "ZivpnEngine"
        fun getFreePort(): Int = try {
            java.net.ServerSocket(0).use { it.localPort }
        } catch (_: Exception) { 1080 }
    }

    @Volatile private var running = false
    @Volatile private var zivpnProcess: Process? = null
    @Volatile private var serverConnected = false
    @Volatile private var socksPort: Int = 0

    init { socksPort = getFreePort() }

    private fun log(msg: String) {
        KighmuLogger.info(TAG, msg)
        try {
            val ts = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
            File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "kighmu_close.txt")
                .appendText("[$ts] [ZIVPN] $msg\n")
        } catch (_: Exception) {}
    }

    override suspend fun start(): Int {
        running = true
        serverConnected = false
        return withContext(Dispatchers.IO) {
            val host     = config.zivpnHost
            val password = config.zivpnPassword
            if (host.isEmpty() || password.isEmpty()) {
                log("ERREUR: host ou password manquant")
                throw IllegalArgumentException("ZIVPN host/password non configurés")
            }
            log("Démarrage ZIVPN UDP: $host  socks=$socksPort")

            val configFile = writeConfig(host, password)
            val binary = extractBinary("libuz.so")
                ?: throw IllegalStateException("libuz.so introuvable")

            try { zivpnProcess?.destroy() } catch (_: Exception) {}
            zivpnProcess = null

            startZivpnProcess(binary, configFile)

            repeat(30) {
                if (!serverConnected) {
                    try { zivpnProcess?.exitValue(); return@repeat } catch (_: Exception) {}
                    Thread.sleep(500)
                }
            }

            if (!serverConnected) throw Exception("ZIVPN: connexion serveur impossible")
            log("ZIVPN prêt sur port SOCKS5 $socksPort ✅")
            socksPort
        }
    }

    private fun writeConfig(host: String, password: String): File {
        val file = File(context.filesDir, "zivpn_config.yaml")
        file.writeText("""
server: $host

auth: $password

transport:
  udp:
    hopInterval: 30s

tls:
  insecure: true

bandwidth:
  up: 50 mbps
  down: 100 mbps

socks5:
  listen: 127.0.0.1:$socksPort

fastOpen: true
""".trimIndent())
        log("Config YAML écrite: $host")
        return file
    }

    private fun extractBinary(name: String): File? {
        val bin = File(context.applicationInfo.nativeLibraryDir, name)
        if (bin.exists()) { bin.setExecutable(true); return bin }
        val fallback = File(context.filesDir, name)
        if (fallback.exists()) { fallback.setExecutable(true); return fallback }
        log("$name introuvable dans ${context.applicationInfo.nativeLibraryDir}")
        return null
    }

    private fun startZivpnProcess(binary: File, configFile: File) {
        val pb = ProcessBuilder(binary.absolutePath, "--config", configFile.absolutePath)
        pb.environment()["HOME"]   = context.filesDir.absolutePath
        pb.environment()["TMPDIR"] = context.cacheDir.absolutePath
        pb.redirectErrorStream(true)
        zivpnProcess = pb.start()
        log("ZIVPN process démarré")

        Thread {
            try {
                zivpnProcess?.inputStream?.bufferedReader()?.forEachLine { line ->
                    if (running) {
                        val lower = line.lowercase()
                        when {
                            lower.contains("connected") || lower.contains("udp-zivpn") ||
                            lower.contains("zivpn_udp") -> {
                                serverConnected = true
                                log("Serveur connecté ✅ — $line")
                            }
                            lower.contains("socks5") && lower.contains("127.0.0.1") -> {
                                Regex("""127\.0\.0\.1:(\d+)""").find(line)
                                    ?.groupValues?.get(1)?.toIntOrNull()?.let {
                                        if (it > 0) { socksPort = it; log("Port SOCKS5: $it") }
                                    }
                                serverConnected = true
                            }
                            lower.contains("listening") || lower.contains("running") -> {
                                serverConnected = true
                                log("ZIVPN en écoute ✅ — $line")
                            }
                            lower.contains("error") || lower.contains("fatal") ->
                                log("ZIVPN erreur: $line")
                            else -> log(line)
                        }
                    }
                }
                val code = zivpnProcess?.waitFor() ?: -1
                log("ZIVPN exit code: $code")
                serverConnected = false
            } catch (e: Exception) { log("Thread ZIVPN: ${e.message}") }
        }.start()
    }

    override fun startTun2Socks(fd: Int) {
        try {
            if (vpnService == null) { log("ERREUR: VpnService null"); return }
            log("Démarrage HevTun2Socks fd=$fd port=$socksPort")
            HevTun2Socks.init()
            if (HevTun2Socks.isAvailable) {
                HevTun2Socks.start(context, fd, socksPort, vpnService, mtu = 8500)
                log("HevTun2Socks démarré ✅")
            } else {
                log("ERREUR: HevTun2Socks non disponible")
            }
        } catch (e: Exception) { log("Erreur HevTun2Socks: ${e.message}") }
    }

    override suspend fun stop() {
        running = false
        serverConnected = false
        log("Arrêt ZIVPN...")
        try { HevTun2Socks.stop() } catch (_: Exception) {}
        try {
            zivpnProcess?.let { p ->
                p.inputStream?.close()
                p.errorStream?.close()
                p.outputStream?.close()
                p.destroyForcibly()
            }
        } catch (_: Exception) {}
        zivpnProcess = null
        withContext(Dispatchers.IO) { Thread.sleep(300) }
        log("ZIVPN arrêté ✅")
    }

    override suspend fun sendData(data: ByteArray, length: Int) {}
    override suspend fun receiveData(): ByteArray? = null
    override fun isRunning() = running && serverConnected
}
