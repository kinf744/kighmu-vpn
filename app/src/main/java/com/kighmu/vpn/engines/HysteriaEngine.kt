package com.kighmu.vpn.engines

import android.content.Context
import android.net.VpnService
import com.kighmu.vpn.models.KighmuConfig
import com.kighmu.vpn.utils.KighmuLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class HysteriaEngine(
    private val config: KighmuConfig,
    private val context: Context,
    private val vpnService: VpnService?
) : TunnelEngine {

    private val TAG = "HysteriaEngine"
    private val hConfig = config.hysteria
    @Volatile private var running = false
    @Volatile private var hysteriaProcess: Process? = null
    @Volatile private var tun2socksProcess: Process? = null
    @Volatile private var socksPort = 1080
    @Volatile private var serverConnected = false

    private fun log(msg: String) {
        KighmuLogger.info(TAG, msg)
        try {
            val ts = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
                java.util.Locale.getDefault()).format(java.util.Date())
            val line = "[$ts] [HYSTERIA] $msg\n"
            val f = File(
                android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS),
                "kighmu_close.txt")
            f.appendText(line)
        } catch (_: Exception) {}
    }

    override suspend fun start(): Int {
        running = true
        serverConnected = false
        return withContext(Dispatchers.IO) {
            val ip = try {
                java.net.InetAddress.getByName(hConfig.serverAddress).hostAddress
                    ?: hConfig.serverAddress
            } catch (_: Exception) { hConfig.serverAddress }

            val portHopping = if (hConfig.portHopping.isNotBlank())
                hConfig.portHopping else "20000-50000"
            val server = "$ip:$portHopping"
            log("Démarrage Hysteria: $server")

            val configFile = writeConfig(server)
            val binary = extractBinary("libhysteria.so")
                ?: throw Exception("libhysteria.so introuvable")

            try { hysteriaProcess?.destroy() } catch (_: Exception) {}
            hysteriaProcess = null

            startHysteriaProcess(binary, configFile)

            // Attendre connexion serveur via log
            repeat(30) {
                if (!serverConnected) {
                    try { hysteriaProcess?.exitValue(); return@repeat } catch (_: Exception) {}
                    Thread.sleep(500)
                }
            }

            if (!serverConnected) throw Exception("Hysteria: connexion serveur impossible")
            log("Hysteria prêt sur port $socksPort ✅")
            socksPort
        }
    }

    private fun writeConfig(server: String): File {
        val file = File(context.filesDir, "hysteria_config.json")
        val config = """{
  "server": "$server",
  "obfs": "${hConfig.obfsPassword}",
  "auth_str": "${hConfig.authPassword}",
  "up_mbps": ${hConfig.uploadMbps},
  "down_mbps": ${hConfig.downloadMbps},
  "insecure": true,
  "socks5": {
    "listen": "127.0.0.1:$socksPort"
  }
}"""
        file.writeText(config)
        log("Config écrite: $server")
        return file
    }

    private fun extractBinary(name: String): File? {
        val bin = File(context.applicationInfo.nativeLibraryDir, name)
        if (bin.exists()) { bin.setExecutable(true); return bin }
        log("$name introuvable dans ${context.applicationInfo.nativeLibraryDir}")
        return null
    }

    private fun startHysteriaProcess(binary: File, configFile: File) {
        val cmd = arrayOf(binary.absolutePath, "client",
            "--config", configFile.absolutePath)
        val pb = ProcessBuilder(*cmd)
        pb.environment()["HOME"] = context.filesDir.absolutePath
        pb.environment()["TMPDIR"] = context.cacheDir.absolutePath
        pb.redirectErrorStream(true)
        hysteriaProcess = pb.start()
        log("Hysteria PID démarré")

        Thread {
            try {
                hysteriaProcess?.inputStream?.bufferedReader()?.forEachLine { line ->
                    if (running) {
                        log("[out] $line")
                        if (line.contains("connected", ignoreCase = true) ||
                            line.contains("Connected")) {
                            serverConnected = true
                        }
                        if (line.contains("SOCKS5 server up") &&
                            line.contains("127.0.0.1:")) {
                            Regex("""127\.0\.0\.1:(\d+)""").find(line)
                                ?.groupValues?.get(1)?.toIntOrNull()?.let {
                                    if (it > 0) { socksPort = it; log("Port SOCKS5: $it") }
                                }
                        }
                    }
                }
                val code = hysteriaProcess?.waitFor() ?: -1
                log("Hysteria exit: $code")
            } catch (e: Exception) { log("thread: ${e.message}") }
        }.start()
    }

    override fun startTun2Socks(fd: Int) {
        Thread {
            try {
                val bin = extractBinary("libtun2socks.so")
                    ?: return@Thread
                val cmd = "${bin.absolutePath}" +
                    " --netif-ipaddr 10.0.0.2" +
                    " --netif-netmask 255.255.255.0" +
                    " --socks-server-addr 127.0.0.1:$socksPort" +
                    " --tunmtu 1500" +
                    " --tunfd $fd" +
                    " --loglevel 4" +
                    " --udpgw-remote-server-addr 127.0.0.1:7300"
                log("tun2socks: $cmd")
                tun2socksProcess = Runtime.getRuntime().exec(cmd)
                Thread {
                    tun2socksProcess?.inputStream?.bufferedReader()
                        ?.forEachLine { KighmuLogger.debug(TAG, it) }
                }.start()
                log("tun2socks démarré fd=$fd port=$socksPort ✅")
            } catch (e: Exception) { log("tun2socks: ${e.message}") }
        }.start()
    }

    override suspend fun stop() {
        running = false
        serverConnected = false
        try { tun2socksProcess?.destroy() } catch (_: Exception) {}
        try { hysteriaProcess?.destroy() } catch (_: Exception) {}
        tun2socksProcess = null
        hysteriaProcess = null
    }

    override suspend fun sendData(data: ByteArray, length: Int) {}
    override suspend fun receiveData(): ByteArray? = null
    override fun isRunning() = running && serverConnected
}
