package com.kighmu.vpn.engines

import android.util.Log

object HevTun2Socks {
    private var loaded = false
    const val TAG = "HevTun2Socks"

    init {
        try {
            System.loadLibrary("tun2socks")
            loaded = true
            Log.i(TAG, "hev-socks5-tunnel chargé ✅")
        } catch (e: Throwable) {
            Log.e(TAG, "Load failed: ${e.message}")
        }
    }

    val isAvailable get() = loaded

    external fun hev_socks5_tunnel_main_from_str(config: String, len: Int): Int
    external fun hev_socks5_tunnel_stop()

    fun buildConfig(tunFd: Int, socksPort: Int, mtu: Int = 8500): String {
        return """
tunnel:
  fd: $tunFd
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

    fun buildConfigMulti(tunFd: Int, socksPorts: List<Int>, mtu: Int = 8500): String {
        // hev utilise un seul port — on prend le premier
        // Le balancing se fait au niveau SSH
        return buildConfig(tunFd, socksPorts.first(), mtu)
    }
}
