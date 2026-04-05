package com.kighmu.vpn.engines

import android.content.Context
import android.util.Log
import java.io.File

object HevTun2Socks {
    const val TAG = "HevTun2Socks"
    private var loaded = false

    fun init() {
        if (!loaded) {
            try {
                // Enregistrer la classe avant loadLibrary
                Class.forName("hev.htproxy.TProxyService")
                hev.htproxy.TProxyService.load()
                loaded = hev.htproxy.TProxyService.isAvailable
            } catch (e: Throwable) {
                Log.e(TAG, "Init failed: ${e.message}")
            }
        }
    }

    val isAvailable get() = loaded

    fun start(context: Context, fd: Int, socksPort: Int, mtu: Int = 8500) {
        startMulti(context, fd, listOf(socksPort), mtu)
    }

    fun startMulti(context: Context, fd: Int, socksPorts: List<Int>, mtu: Int = 8500) {
        val config = buildConfigMulti(socksPorts, mtu)
        val configFile = File(context.cacheDir, "hev_config.yaml")
        configFile.writeText(config)
        Log.i(TAG, "Démarrage hev fd=$fd ports=$socksPorts")
        Log.i(TAG, "Config YAML:\n$config")
        hev.htproxy.TProxyService.TProxyStartService(configFile.absolutePath, fd)
    }

    fun stop() {
        if (loaded) hev.htproxy.TProxyService.TProxyStopService()
    }

    private fun buildConfigMulti(socksPorts: List<Int>, mtu: Int): String {
        val sb = StringBuilder()
        sb.appendLine("tunnel:")
        sb.appendLine("  mtu: $mtu")
        sb.appendLine("  ipv4: 198.18.0.1")
        sb.appendLine()
        // hev patché supporte liste de serveurs socks5
        sb.appendLine("socks5:")
        if (socksPorts.size == 1) {
            sb.appendLine("  port: ${socksPorts[0]}")
            sb.appendLine("  address: 127.0.0.1")
            sb.appendLine("  udp: udp")
        } else {
            sb.appendLine("  servers:")
            socksPorts.forEach { port ->
                sb.appendLine("    - address: 127.0.0.1")
                sb.appendLine("      port: $port")
                sb.appendLine("      udp: udp")
            }
        }
        sb.appendLine("misc:")
        sb.appendLine("  log-level: warn")
        return sb.toString()
    }
}
