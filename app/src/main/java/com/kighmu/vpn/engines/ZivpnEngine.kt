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
            val ts = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS",
                java.util.Locale.getDefault()).format(java.util.Date())
            val f = File(android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS), "kighmu_zivpn.txt")
            f.appendText("[$ts] [ZIVPN] $msg
")
        } catch (_: Exception) {}
    }

    override suspend fun start(): Int {
        running = true
        serverConnected = false
        return withContext(Dispatchers.IO) {
            try { zivpnProcess?.destroy() } catch (_: Exception) {}
            zivpnProcess = null
            val host = config.zivpnHost.trim()
            val password = config.zivpnPassword.trim()
            log("========== DEMARRAGE ZIVPN UDP ==========")
            log("Host: $host | SocksPort: $socksPort")
            if (host.isEmpty()) throw IllegalArgumentException("ZIVPN: host non configure")
            if (password.isEmpty()) throw IllegalArgumentException("ZIVPN: password non configure")
            val configFile = writeConfig(host, password)
            val binary = extractBinary("libuz.so")
                ?: throw IllegalStateException("libuz.so introuvable")
            log("Binaire: ${binary.absolutePath}")
            startZivpnProcess(binary, configFile)
            repeat(30) {
                if (!serverConnected) {
                    try { zivpnProcess?.exitValue(); return@repeat } catch (_: Exception) {}
                    Thread.sleep(500)
                }
            }
            if (!serverConnected) {
                log("Timeout — process actif, mode optimiste")
                serverConnected = true
            }
            log("ZIVPN pret sur port $socksPort")
            socksPort
        }
    }

    private fun writeConfig(host: String, password: String): File {
        val file = File(context.filesDir, "zivpn_config.json")
        val startPort = config.zivpnPort.ifBlank { "6000" }
            .split("-").firstOrNull()?.trim() ?: "6000"
        val json = "{" +
            ""server": "$host:$startPort"," +
            ""obfs": "udp"," +
            ""auth": "$password"," +
            ""up": "50 mbps"," +
            ""down": "100 mbps"," +
            ""socks5": {"listen": "127.0.0.1:$socksPort"}," +
            ""insecure": true," +
            ""recvwindowconn": 1048576," +
            ""recvwindow": 2621440}"
        file.writeText(json)
        log("Config ecrite: $host:$startPort")
        return file
    }

    private fun extractBinary(name: String): File? {
        val bin = File(context.applicationInfo.nativeLibraryDir, name)
        if (bin.exists()) { bin.setExecutable(true); return bin }
        log("ERREUR: $name introuvable dans ${context.applicationInfo.nativeLibraryDir}")
        return null
    }

    private fun startZivpnProcess(binary: File, configFile: File) {
        val cmd = arrayOf(binary.absolutePath, "-s", "udp", "--config", configFile.absolutePath)
        val pb = ProcessBuilder(*cmd)
        pb.environment()["HOME"] = context.filesDir.absolutePath
        pb.environment()["TMPDIR"] = context.cacheDir.absolutePath
        pb.redirectErrorStream(true)
        zivpnProcess = pb.start()
        log("Process lance")
        Thread {
            try {
                zivpnProcess?.inputStream?.bufferedReader()?.forEachLine { line ->
                    if (running) {
                        log("[OUT] $line")
                        val lower = line.lowercase()
                        when {
                            lower.contains("connected") || lower.contains("running") ||
                            lower.contains("started") || lower.contains("listening") ||
                            lower.contains("ready") -> { serverConnected = true; log("CONNEXION OK") }
                            lower.contains("socks5") || lower.contains("socks") -> { serverConnected = true; log("SOCKS5 pret") }
                            lower.contains("error") || lower.contains("fatal") || lower.contains("panic") -> log("ERREUR: $line")
                        }
                    }
                }
                val code = zivpnProcess?.waitFor() ?: -1
                log("Process termine exit=$code")
                serverConnected = false
            } catch (e: Exception) { log("Thread: ${e.message}") }
        }.apply { isDaemon = true; name = "zivpn-reader" }.start()
    }

    override fun startTun2Socks(fd: Int) {
        try {
            if (vpnService == null) { log("ERREUR: VpnService null"); return }
            log("Demarrage HevTun2Socks fd=$fd port=$socksPort")
            HevTun2Socks.init()
            if (HevTun2Socks.isAvailable) {
                HevTun2Socks.start(context, fd, socksPort, vpnService, mtu = 8500)
                log("HevTun2Socks demarre")
            } else { log("ERREUR: HevTun2Socks non disponible") }
        } catch (e: Exception) { log("Erreur HevTun2Socks: ${e.message}") }
    }

    override suspend fun stop() {
        running = false
        serverConnected = false
        log("=== ARRET ZIVPN ===")
        try { HevTun2Socks.stop() } catch (_: Exception) {}
        try {
            zivpnProcess?.let { p ->
                runCatching { p.inputStream?.close() }
                runCatching { p.errorStream?.close() }
                runCatching { p.outputStream?.close() }
                p.destroyForcibly()
                log("Process detruit")
            }
        } catch (e: Exception) { log("Erreur arret: ${e.message}") }
        zivpnProcess = null
        withContext(Dispatchers.IO) { Thread.sleep(300) }
        log("ZIVPN arrete")
    }

    override suspend fun sendData(data: ByteArray, length: Int) {}
    override suspend fun receiveData(): ByteArray? = null
    override fun isRunning() = running && serverConnected
}