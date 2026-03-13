package com.kighmu.vpn.engines

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.ParcelFileDescriptor
import com.kighmu.vpn.models.KighmuConfig
import com.kighmu.vpn.utils.KighmuLogger
import kotlinx.coroutines.*
import java.io.File

class XrayEngine(
    private val config: KighmuConfig,
    private val context: Context
) : TunnelEngine {

    companion object {
        const val TAG = "XrayEngine"
        const val LOCAL_SOCKS_PORT = 10808
        const val MTU = 1500
    }

    private var xrayProcess: Process? = null
    private var tun2socksProcess: Process? = null
    private var running = false
    private val engineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override suspend fun start(): Int = withContext(Dispatchers.IO) {
        running = true
        KighmuLogger.info(TAG, "=== Démarrage XrayEngine ===")

        val xrayBin = File(context.applicationInfo.nativeLibraryDir, "libxray.so")
        if (!xrayBin.exists()) throw Exception("libxray.so introuvable")
        xrayBin.setExecutable(true)

        // Générer config.json Xray depuis KighmuConfig
        val configJson = buildXrayConfig()
        val configFile = File(context.cacheDir, "xray_config.json")
        configFile.writeText(configJson)
        KighmuLogger.info(TAG, "Config Xray écrite: ${configFile.absolutePath}")

        // Lancer Xray
        val cmd = arrayOf(xrayBin.absolutePath, "run", "-c", configFile.absolutePath)
        xrayProcess = Runtime.getRuntime().exec(cmd)
        val proc = xrayProcess!!

        // Logger stdout/stderr
        Thread {
            try {
                val es = proc.errorStream
                val sb = StringBuilder()
                while (running) {
                    val b = es.read()
                    if (b == -1) break
                    if (b == '\n'.code) {
                        if (sb.isNotEmpty()) KighmuLogger.info(TAG, "xray: $sb")
                        sb.clear()
                    } else if (b != '\r'.code) sb.append(b.toChar())
                }
            } catch (_: Exception) {}
        }.start()

        // Attendre que Xray soit prêt
        var ready = false
        repeat(20) {
            if (!ready) {
                delay(500)
                try {
                    val s = java.net.Socket()
                    s.connect(java.net.InetSocketAddress("127.0.0.1", LOCAL_SOCKS_PORT), 200)
                    s.close()
                    ready = true
                    KighmuLogger.info(TAG, "Xray SOCKS5 prêt sur port $LOCAL_SOCKS_PORT ✓")
                } catch (_: Exception) {}
            }
        }
        if (!ready) throw Exception("Xray n'a pas démarré dans les temps")

        LOCAL_SOCKS_PORT
    }

    private fun buildXrayConfig(): String {
        val xray = config.xrayConfig
        val outbound = when (xray.protocol) {
            "vless" -> """
                {
                    "protocol": "vless",
                    "settings": {
                        "vnext": [{
                            "address": "${xray.address}",
                            "port": ${xray.port},
                            "users": [{"id": "${xray.uuid}", "encryption": "none", "flow": "${xray.flow}"}]
                        }]
                    },
                    "streamSettings": ${buildStreamSettings(xray)}
                }"""
            "vmess" -> """
                {
                    "protocol": "vmess",
                    "settings": {
                        "vnext": [{
                            "address": "${xray.address}",
                            "port": ${xray.port},
                            "users": [{"id": "${xray.uuid}", "alterId": 0, "security": "auto"}]
                        }]
                    },
                    "streamSettings": ${buildStreamSettings(xray)}
                }"""
            "trojan" -> """
                {
                    "protocol": "trojan",
                    "settings": {
                        "servers": [{
                            "address": "${xray.address}",
                            "port": ${xray.port},
                            "password": "${xray.password}"
                        }]
                    },
                    "streamSettings": ${buildStreamSettings(xray)}
                }"""
            else -> """{"protocol": "freedom", "settings": {}}"""
        }

        return """
        {
            "log": {"loglevel": "warning"},
            "inbounds": [{
                "port": $LOCAL_SOCKS_PORT,
                "listen": "127.0.0.1",
                "protocol": "socks",
                "settings": {"udp": true, "auth": "noauth"}
            }],
            "outbounds": [$outbound,
                {"protocol": "freedom", "tag": "direct"},
                {"protocol": "blackhole", "tag": "block"}
            ],
            "routing": {
                "domainStrategy": "IPIfNonMatch",
                "rules": [
                    {"type": "field", "ip": ["geoip:private"], "outboundTag": "direct"}
                ]
            },
            "dns": {
                "servers": ["8.8.8.8", "1.1.1.1"]
            }
        }""".trimIndent()
    }

    private fun buildStreamSettings(xray: com.kighmu.vpn.models.XrayConfig): String {
        val tlsBlock = if (xray.tls) """
            "tlsSettings": {
                "serverName": "${xray.sni.ifBlank { xray.address }}",
                "allowInsecure": false
            }""" else ""

        val wsBlock = if (xray.network == "ws") """
            "wsSettings": {
                "path": "${xray.wsPath}",
                "headers": {"Host": "${xray.wsHost.ifBlank { xray.address }}"}
            }""" else ""

        val grpcBlock = if (xray.network == "grpc") """
            "grpcSettings": {"serviceName": "${xray.grpcServiceName}"}
            """ else ""

        val security = if (xray.tls) "tls" else "none"

        return """{
            "network": "${xray.network}",
            "security": "$security"
            ${if (tlsBlock.isNotEmpty()) ",$tlsBlock" else ""}
            ${if (wsBlock.isNotEmpty()) ",$wsBlock" else ""}
            ${if (grpcBlock.isNotEmpty()) ",$grpcBlock" else ""}
        }"""
    }

    override fun startTun2Socks(fd: Int) {
        KighmuLogger.info(TAG, "Démarrage tun2socks fd=$fd -> SOCKS $LOCAL_SOCKS_PORT")
        engineScope.launch(Dispatchers.IO) {
            try {
                val nativeDir = context.applicationInfo.nativeLibraryDir
                val bin = File(nativeDir, "libtun2socks.so")
                if (!bin.exists()) { KighmuLogger.error(TAG, "libtun2socks.so introuvable"); return@launch }
                bin.setExecutable(true)
                val sockPath = "${context.cacheDir}/tun2socks_xray.sock"
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
                    try {
                        val es = tun2socksProcess?.errorStream ?: return@Thread
                        val sb = StringBuilder()
                        while (running) {
                            val b = es.read()
                            if (b == -1) break
                            if (b == '\n'.code) {
                                if (sb.isNotEmpty()) KighmuLogger.info(TAG, "tun2socks: $sb")
                                sb.clear()
                            } else if (b != '\r'.code) sb.append(b.toChar())
                        }
                    } catch (_: Exception) {}
                }.start()
                delay(500)
                try {
                    val localSocket = LocalSocket()
                    localSocket.connect(LocalSocketAddress(sockPath, LocalSocketAddress.Namespace.FILESYSTEM))
                    val pfd = ParcelFileDescriptor.fromFd(fd)
                    localSocket.setFileDescriptorsForSend(arrayOf(pfd.fileDescriptor))
                    localSocket.outputStream.write(1)
                    localSocket.outputStream.flush()
                    localSocket.close()
                    KighmuLogger.info(TAG, "fd=$fd envoyé à tun2socks ✓")
                } catch (e: Exception) {
                    KighmuLogger.error(TAG, "sock-path error: ${e.message}")
                }
            } catch (e: Exception) {
                KighmuLogger.error(TAG, "tun2socks error: ${e.message}")
            }
        }
    }

    override suspend fun stop() {
        running = false
        try { xrayProcess?.destroyForcibly() } catch (_: Exception) {}
        try { tun2socksProcess?.destroyForcibly() } catch (_: Exception) {}
        engineScope.cancel()
        KighmuLogger.info(TAG, "XrayEngine arrêté")
    }

    override suspend fun sendData(data: ByteArray, length: Int) {}
    override suspend fun receiveData(): ByteArray? = null
    override fun isRunning() = running && (xrayProcess?.isAlive == true)
}
