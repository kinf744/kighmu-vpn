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
            // Attendre que le port 1080 soit libre (max 30s)
            var portWait = 0
            while (portWait < 30) {
                try {
                    java.net.ServerSocket(1080).close()
                    log("Port 1080 LIBRE ✅")
                    break
                } catch (_: Exception) {
                    if (portWait == 0) log("Port 1080 occupé, attente...")
                    Thread.sleep(1000)
                    portWait++
                }
            }
            if (portWait >= 30) throw Exception("Port 1080 occupé après 30s - fermez les autres apps VPN")
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
  "retry": 3,
  "retry_interval": 1,
  "socks5": {
    "listen": "127.0.0.1:$socksPort"
  },
  "insecure": true,
  "recv_window_conn": 4194304,
  "recv_window": 16777216
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
                        // Hysteria 1: "Connected" dans le log serveur
                        // Déclencheur VPN: "UDP running" comme dans kiaje34
                        if (line.contains("UDP running") || line.contains("running")) {
                            serverConnected = true
                            log("Serveur connecté ✅")
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
                serverConnected = false
            } catch (e: Exception) { log("thread: ${e.message}") }
        }.start()
    }

    override fun startTun2Socks(fd: Int) {
        Thread {
            try {
                val bin = java.io.File(context.applicationInfo.nativeLibraryDir, "libtun2socks.so")
                if (!bin.exists()) { log("libtun2socks.so introuvable"); return@Thread }
                val sockPath = "${context.dataDir.absolutePath}/sock_path"
                java.io.File(sockPath).let { if (!it.exists()) it.createNewFile() }

                val cmd = "${bin.absolutePath}" +
                    " --netif-ipaddr 10.0.0.2" +
                    " --netif-netmask 255.255.255.0" +
                    " --socks-server-addr 127.0.0.1:$socksPort" +
                    " --tunmtu 1500" +
                    " --tunfd $fd" +
                    " --sock-path $sockPath" +
                    " --loglevel 3" +
                    " --udpgw-remote-server-addr 127.0.0.1:7300"

                log("tun2socks démarré fd=$fd port=$socksPort ✓")
                tun2socksProcess = Runtime.getRuntime().exec(cmd)

                val t2sIn = Thread { try { tun2socksProcess?.inputStream?.bufferedReader()?.forEachLine { } } catch (_: Exception) {} }
                t2sIn.isDaemon = true
                t2sIn.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, _ -> }
                t2sIn.start()
                val t2sErr = Thread { try { tun2socksProcess?.errorStream?.bufferedReader()?.forEachLine { } } catch (_: Exception) {} }
                t2sErr.isDaemon = true
                t2sErr.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, _ -> }
                t2sErr.start()

                val pfd = android.os.ParcelFileDescriptor.fromFd(fd)
                var sent = false
                repeat(10) {
                    if (!sent) try {
                        try { Thread.sleep(500) } catch (_: InterruptedException) { return@Thread }
                        val s = android.net.LocalSocket()
                        s.connect(android.net.LocalSocketAddress(sockPath, android.net.LocalSocketAddress.Namespace.FILESYSTEM))
                        s.setFileDescriptorsForSend(arrayOf(pfd.fileDescriptor))
                        s.outputStream.write(42)
                        s.shutdownOutput(); s.close()
                        sent = true
                        log("fd=$fd envoyé ✅")
                    } catch (e: Exception) { log("sendFd: ${e.message}") }
                }
                if (!sent) log("ERREUR: fd non envoyé")
                try { tun2socksProcess?.waitFor() } catch (_: Exception) {}
            } catch (e: Exception) { log("tun2socks error: ${e.message}") }
        }.start()
    }

    override suspend fun stop() {
        running = false
        serverConnected = false
        val t2sIn = Thread { try { tun2socksProcess?.inputStream?.bufferedReader()?.forEachLine { } } catch (_: Exception) {} }
        val t2sErr = Thread { try { tun2socksProcess?.errorStream?.bufferedReader()?.forEachLine { } } catch (_: Exception) {} }
        t2sIn.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, _ -> }
        t2sErr.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, _ -> }
        try { tun2socksProcess?.destroy() } catch (_: Exception) {}
        try { hysteriaProcess?.destroy() } catch (_: Exception) {}
        tun2socksProcess = null
        hysteriaProcess = null
        withContext(Dispatchers.IO) { Thread.sleep(2000) }
    }

    override suspend fun sendData(data: ByteArray, length: Int) {}
    override suspend fun receiveData(): ByteArray? = null
    override fun isRunning() = running && serverConnected
}
