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
                val bin = extractBinary("libtun2socks.so") ?: return@Thread
                val sockPath = "${context.dataDir.absolutePath}/sock_path"
                val sockFile = java.io.File(sockPath)
                if (!sockFile.exists()) sockFile.createNewFile()

                val cmd = "${bin.absolutePath}" +
                    " --netif-ipaddr 10.0.0.2" +
                    " --netif-netmask 255.255.255.0" +
                    " --socks-server-addr 127.0.0.1:$socksPort" +
                    " --tunmtu 1500" +
                    " --tunfd $fd" +
                    " --sock-path $sockPath" +
                    " --loglevel 3" +
                    " --udpgw-remote-server-addr 127.0.0.1:7300"
                log("tun2socks: $cmd")
                tun2socksProcess = Runtime.getRuntime().exec(cmd)
                Thread { tun2socksProcess?.inputStream?.bufferedReader()?.forEachLine { log("[t2s] $it") } }.start()
                Thread { tun2socksProcess?.errorStream?.bufferedReader()?.forEachLine { log("[t2s-err] $it") } }.start()

                // sendFd() comme udphystvpn
                val pfd = android.os.ParcelFileDescriptor.fromFd(fd)
                var sent = false
                repeat(10) {
                    if (!sent) {
                        try {
                            Thread.sleep(500)
                            val localSocket = android.net.LocalSocket()
                            localSocket.connect(android.net.LocalSocketAddress(sockPath, android.net.LocalSocketAddress.Namespace.FILESYSTEM))
                            localSocket.setFileDescriptorsForSend(arrayOf(pfd.fileDescriptor))
                            localSocket.outputStream.write(42)
                            localSocket.shutdownOutput()
                            localSocket.close()
                            sent = true
                            log("fd=$fd envoyé via sock-path ✅")
                        } catch (e: Exception) {
                            log("sendFd: ${e.message}")
                        }
                    }
                }
                if (!sent) log("ERREUR: fd non envoyé")
                tun2socksProcess?.waitFor()
            } catch (e: Exception) { log("tun2socks error: ${e.message}") }
        }.start()
    }

    override suspend fun stop() {
        running = false
        serverConnected = false
        try { tun2socksProcess?.destroy() } catch (_: Exception) {}
        try { hysteriaProcess?.destroy() } catch (_: Exception) {}
        tun2socksProcess = null
        hysteriaProcess = null
        // Attendre 2s comme MyUDPThread.stopVudp() pour libérer le port
        withContext(Dispatchers.IO) { Thread.sleep(2000) }
    }

    override suspend fun sendData(data: ByteArray, length: Int) {}
    override suspend fun receiveData(): ByteArray? = null
    override fun isRunning() = running && serverConnected
}
