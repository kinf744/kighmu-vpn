package com.kighmu.vpn.engines

import android.content.Context
import android.net.VpnService
import com.kighmu.vpn.models.KighmuConfig
import com.kighmu.vpn.utils.KighmuLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import java.io.File

class HysteriaEngine(
    private val config: KighmuConfig,
    private val context: Context,
    private val vpnService: VpnService?
) : TunnelEngine {

    private val TAG = "HysteriaEngine"
    private val hConfig = config.hysteria
    private val engineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var running = false
    private var hysteriaProcess: Process? = null
    private var socksPort = 1080
    @Volatile private var serverConnected = false

    private fun log(msg: String) {
        KighmuLogger.info(TAG, msg)
        try {
            val ts = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
            val line = "[$ts] [HYSTERIA] $msg\n"
            val f = java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "kighmu_close.txt")
            f.appendText(line)
        } catch (_: Exception) {}
    }

    override suspend fun start(): Int {
        running = true
        return withContext(Dispatchers.IO) {
            val ip = try {
                java.net.InetAddress.getByName(hConfig.serverAddress).hostAddress ?: hConfig.serverAddress
            } catch (_: Exception) { hConfig.serverAddress }

            val portHopping = if (hConfig.portHopping.isNotBlank()) hConfig.portHopping else "20000-50000"
            val server = "$ip:$portHopping"
            log("Démarrage Hysteria: $server")

            val configFile = writeConfig(server)
            val binary = extractBinary() ?: throw Exception("libhysteria.so introuvable")

            try { hysteriaProcess?.destroy() } catch (_: Exception) {}
            hysteriaProcess = null

            startProcess(binary, configFile)

            // Attendre que Hysteria soit connecté au SERVEUR (log "Connected")
            // pas juste au port SOCKS5 local
            serverConnected = false
            repeat(30) {
                if (!serverConnected) {
                    try { hysteriaProcess?.exitValue(); return@repeat } catch (_: Exception) {}
                    if (serverConnected) return@repeat
                    Thread.sleep(500)
                }
            }

            if (!serverConnected) throw Exception("Hysteria: connexion serveur impossible")
            socksPort
        }
    }

    private fun writeConfig(server: String): File {
        val file = File(context.filesDir, "hysteria_config.json")
        val obfsSection = if (hConfig.obfsPassword.isNotBlank()) """
  "obfs": {
    "type": "salamander",
    "salamander": {
      "password": "${hConfig.obfsPassword}"
    }
  },""" else ""
        val config = """{
  "server": "$server",
  "auth": "${hConfig.authPassword}",$obfsSection
  "bandwidth": {
    "up": "${hConfig.uploadMbps} mbps",
    "down": "${hConfig.downloadMbps} mbps"
  },
  "tls": {
    "insecure": true
  },
  "socks5": {
    "listen": "127.0.0.1:$socksPort"
  }
}"""
        file.writeText(config)
        log("Config écrite: $server")
        return file
    }

    private fun extractBinary(): File? {
        val bin = File(context.applicationInfo.nativeLibraryDir, "libhysteria.so")
        if (bin.exists()) { bin.setExecutable(true); return bin }
        log("libhysteria.so introuvable")
        return null
    }

    private fun startProcess(binary: File, configFile: File) {
        val cmd = arrayOf(binary.absolutePath, "client",
            "--config", configFile.absolutePath,
            "--log-level", "warn",
            "--disable-update-check")
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
                        if (line.contains("SOCKS5 server up") && line.contains("127.0.0.1:")) {
                            val port = Regex("""127\.0\.0\.1:(\d+)""").find(line)?.groupValues?.get(1)?.toIntOrNull()
                            if (port != null && port > 0) {
                                socksPort = port
                                log("Port SOCKS5 détecté: $port")
                            }
                        }
                        // Connexion serveur confirmée
                        if (line.contains("Connected") && !line.contains("SOCKS5")) {
                            serverConnected = true
                            log("Connexion serveur confirmée ✅")
                        }
                    }
                }
                val code = hysteriaProcess?.waitFor() ?: -1
                log("Hysteria exit code: $code")
            } catch (e: Exception) {
                log("thread error: ${e.message}")
            }
        }.start()
    }

    override fun startTun2Socks(fd: Int) {
        Thread {
            try {
                val bin = File(context.applicationInfo.nativeLibraryDir, "libtun2socks.so")
                if (!bin.exists()) { log("libtun2socks.so introuvable"); return@Thread }
                bin.setExecutable(true)
                val sockPath = "${context.cacheDir.absolutePath}/tun2socks_hysteria.sock"
                File(sockPath).delete()
                val cmd = listOf(bin.absolutePath,
                    "--sock-path", sockPath,
                    "--tunmtu", "1500",
                    "--netif-ipaddr", "10.0.0.2",
                    "--netif-netmask", "255.255.255.0",
                    "--socks-server-addr", "127.0.0.1:$socksPort",
                    "--enable-udprelay",
                    "--loglevel", "4")
                val proc = Runtime.getRuntime().exec(cmd.toTypedArray())
                Thread { proc.errorStream.bufferedReader().forEachLine { KighmuLogger.debug(TAG, it) } }.start()
                Thread.sleep(500)
                val localSocket = android.net.LocalSocket()
                localSocket.connect(android.net.LocalSocketAddress(sockPath, android.net.LocalSocketAddress.Namespace.FILESYSTEM))
                val pfd = android.os.ParcelFileDescriptor.fromFd(fd)
                localSocket.setFileDescriptorsForSend(arrayOf(pfd.fileDescriptor))
                localSocket.outputStream.write(1)
                localSocket.outputStream.flush()
                localSocket.close()
                log("tun2socks fd=$fd envoyé")
            } catch (e: Exception) {
                log("tun2socks error: ${e.message}")
            }
        }.start()
    }

    override suspend fun stop() {
        running = false
        try { hysteriaProcess?.destroy() } catch (_: Exception) {}
        hysteriaProcess = null
        engineScope.cancel()
    }

    override suspend fun sendData(data: ByteArray, length: Int) {}
    override suspend fun receiveData(): ByteArray? = null
    override fun isRunning() = running
}
