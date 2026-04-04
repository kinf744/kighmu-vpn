package com.kighmu.vpn.engines

import android.content.Context
import android.util.Log
import hev.htproxy.TProxyService
import java.io.File

object HevTun2Socks {
    const val TAG = "HevTun2Socks"

    val isAvailable get() = TProxyService.isAvailable

    fun start(context: Context, fd: Int, socksPort: Int, mtu: Int = 8500) {
        val config = buildConfig(socksPort, mtu)
        val configFile = File(context.cacheDir, "hev_config.yaml")
        configFile.writeText(config)
        Log.i(TAG, "Démarrage hev fd=$fd port=$socksPort")
        TProxyService.TProxyStartService(configFile.absolutePath, fd)
    }

    fun stop() {
        TProxyService.TProxyStopService()
    }

    private fun buildConfig(socksPort: Int, mtu: Int): String {
        return """
tunnel:
  mtu: $mtu
  ipv4: 198.18.0.1

socks5:
  port: $socksPort
  address: 127.0.0.1
  udp: udp

misc:
  log-level: warn
""".trimIndent()
    }
}
