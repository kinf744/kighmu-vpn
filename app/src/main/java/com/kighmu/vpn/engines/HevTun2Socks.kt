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
        val config = buildConfig(socksPort, mtu)
        val configFile = File(context.cacheDir, "hev_config.yaml")
        configFile.writeText(config)
        Log.i(TAG, "Démarrage hev fd=$fd port=$socksPort")
        hev.htproxy.TProxyService.TProxyStartService(configFile.absolutePath, fd)
    }

    fun stop() {
        if (loaded) hev.htproxy.TProxyService.TProxyStopService()
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
  pipeline: true

misc:
  task-stack-size: 163840
  tcp-buffer-size: 131072
  udp-recv-buffer-size: 1048576
  max-session-count: 0
  connect-timeout: 5000
  tcp-read-write-timeout: 300000
  log-level: warn
""".trimIndent()
    }
}
