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
                Class.forName("hev.htproxy.TProxyService")
                hev.htproxy.TProxyService.load()
                loaded = hev.htproxy.TProxyService.isAvailable
                Log.i(TAG, "Init OK loaded=$loaded")
            } catch (e: Throwable) {
                Log.e(TAG, "Init failed: ${e.message} | cause: ${e.cause?.message}")
                // Tentative directe sans Class.forName
                try {
                    System.loadLibrary("hev_jni")
                    Log.i(TAG, "hev_jni chargé directement ✅")
                } catch (e2: Throwable) {
                    Log.e(TAG, "hev_jni direct load failed: ${e2.message}")
                }
            }
        }
    }

    val isAvailable get() = loaded

    fun start(context: Context, fd: Int, socksPort: Int, vpnService: android.net.VpnService, mtu: Int = 8500) {
        val config = buildConfig(socksPort, mtu)
        val configFile = File(context.cacheDir, "hev_config.yaml")
        configFile.writeText(config)
        Log.i(TAG, "Démarrage hev fd=$fd port=$socksPort")

        hev.htproxy.TProxyService.TProxyStartService(configFile.absolutePath, fd)
    }

    fun startMulti(context: Context, fd: Int, ports: List<Int>, vpnService: android.net.VpnService, mtu: Int = 8500) {
        if (ports.isEmpty()) return
        
        Log.i(TAG, "Démarrage hev multi-SOCKS fd=$fd ports=$ports")
        
        val mainPort = ports.first()
        val config = buildConfigMulti(mainPort, ports, mtu)
        
        val configFile = File(context.cacheDir, "hev_config_multi.yaml")
        configFile.writeText(config)
        
        Log.i(TAG, "Config multi sauvegardée: ${configFile.absolutePath}")

        hev.htproxy.TProxyService.TProxyStartService(configFile.absolutePath, fd)
    }

    fun stop() {
        if (loaded) hev.htproxy.TProxyService.TProxyStopService()
    }

    private fun buildConfig(socksPort: Int, mtu: Int): String {
        val effectiveMtu = if (mtu > 1500) 1500 else mtu
        return """
tunnel:
  mtu: ${'$'}effectiveMtu
  ipv4: 10.0.0.1

socks5:
  port: ${'$'}socksPort
  address: 127.0.0.1
  udp: udp

misc:
  log-level: warn
""".trimIndent()
    }

    private fun buildConfigMulti(mainPort: Int, ports: List<Int>, mtu: Int): String {
        val effectiveMtu = if (mtu > 1500) 1500 else mtu
        return """
tunnel:
  mtu: ${'$'}effectiveMtu
  ipv4: 10.0.0.1

socks5:
  port: ${'$'}mainPort
  address: 127.0.0.1
  udp: udp

misc:
  log-level: warn
""".trimIndent()
    }
}
