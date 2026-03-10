package com.kighmu.vpn.engines

import android.content.Context
import com.kighmu.vpn.models.KighmuConfig
import com.kighmu.vpn.utils.KighmuLogger
import kotlinx.coroutines.*
import java.io.File

class ZivpnEngine(
    private val config: KighmuConfig,
    private val context: Context
) : TunnelEngine {

    companion object {
        const val TAG = "ZivpnEngine"
        const val LOCAL_SOCKS_PORT = 10802
        const val MTU = 1500
    }

    private var zivpnProcess: Process? = null
    private var tun2socksProcess: Process? = null
    private val engineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val zivpn get() = config.zivpn

    override suspend fun start(): Int = withContext(Dispatchers.IO) {
        KighmuLogger.info(TAG, "=== Demarrage UDP ZIVPN ===")
        KighmuLogger.info(TAG, "Host: ${zivpn.host}")

        val nativeDir = context.applicationInfo.nativeLibraryDir
        val bin = File(nativeDir, "libzivpn.so")
        if (!bin.exists()) throw Exception("libzivpn.so introuvable dans $nativeDir")
        bin.setExecutable(true)

        val configDir = File(context.filesDir, "zivpn")
        configDir.mkdirs()
        val configFile = File(configDir, "client.json")
        configFile.writeText("""
{
  "server": "${zivpn.host}:5667",
  "obfs": "zivpn",
  "auth_str": "${zivpn.password}",
  "insecure": true,
  "socks5": {
    "listen": "127.0.0.1:$LOCAL_SOCKS_PORT"
  },
  "up_mbps": 100,
  "down_mbps": 100
}
""".trimIndent())

        val cmd = arrayOf(bin.absolutePath, "client", "-c", configFile.absolutePath, "--log-level", "warn")
        KighmuLogger.info(TAG, "Lancement: ${cmd.joinToString(" ")}")
        val pb = ProcessBuilder(*cmd).redirectErrorStream(true)
        zivpnProcess = pb.start()

        Thread {
            zivpnProcess?.inputStream?.bufferedReader()?.forEachLine { line ->
                KighmuLogger.info(TAG, "zivpn: $line")
            }
        }.start()

        // Attendre SOCKS5 pret
        var ready = false
        repeat(20) {
            if (!ready) {
                Thread.sleep(500)
                try {
                    val sock = java.net.Socket()
                    sock.connect(java.net.InetSocketAddress("127.0.0.1", LOCAL_SOCKS_PORT), 500)
                    sock.close()
                    ready = true
                    KighmuLogger.info(TAG, "ZIVPN SOCKS5 pret sur port $LOCAL_SOCKS_PORT")
                } catch (_: Exception) {}
            }
        }

        if (!ready) throw Exception("ZIVPN SOCKS5 ne repond pas sur $LOCAL_SOCKS_PORT")
        LOCAL_SOCKS_PORT
    }

    override suspend fun stop() {
        try { zivpnProcess?.destroy() } catch (_: Exception) {}
        try { tun2socksProcess?.destroy() } catch (_: Exception) {}
        zivpnProcess = null
        tun2socksProcess = null
        engineScope.cancel()
        KighmuLogger.info(TAG, "ZIVPN arrete")
    }

    override suspend fun sendData(data: ByteArray, length: Int) {}
    override suspend fun receiveData(): ByteArray? = null
    override fun isRunning(): Boolean = zivpnProcess?.isAlive == true

    override fun startTun2Socks(fd: Int) {
        KighmuLogger.info(TAG, "Demarrage tun2socks pour ZIVPN fd=$fd")
        engineScope.launch(Dispatchers.IO) {
            try {
                val nativeDir = context.applicationInfo.nativeLibraryDir
                val bin = File(nativeDir, "libtun2socks.so")
                if (!bin.exists()) { KighmuLogger.error(TAG, "libtun2socks.so introuvable"); return@launch }
                bin.setExecutable(true)

                val sockPath = "${context.cacheDir.absolutePath}/tun2socks_zivpn.sock"
                File(sockPath).delete()

                val cmd = arrayOf(
                    bin.absolutePath,
                    "--sock-path", sockPath,
                    "--tunmtu", MTU.toString(),
                    "--netif-ipaddr", "10.0.0.1",
                    "--netif-netmask", "255.255.255.0",
                    "--socks-server-addr", "127.0.0.1:$LOCAL_SOCKS_PORT",
                    "--enable-udprelay",
                    "--loglevel", "4"
                )
                tun2socksProcess = Runtime.getRuntime().exec(cmd)
                Thread {
                    tun2socksProcess?.inputStream?.bufferedReader()?.forEachLine { line ->
                        KighmuLogger.info(TAG, "tun2socks: $line")
                    }
                }.start()

                delay(500)
                val localSocket = android.net.LocalSocket()
                localSocket.connect(android.net.LocalSocketAddress(sockPath, android.net.LocalSocketAddress.Namespace.FILESYSTEM))
                val pfd = android.os.ParcelFileDescriptor.fromFd(fd)
                localSocket.setFileDescriptorsForSend(arrayOf(pfd.fileDescriptor))
                localSocket.outputStream.write(1)
                localSocket.outputStream.flush()
                localSocket.close()
                KighmuLogger.info(TAG, "fd=$fd envoye via sock-path ZIVPN OK")
            } catch (e: Exception) {
                KighmuLogger.error(TAG, "startTun2Socks error: ${e.message}")
            }
        }
    }
}
