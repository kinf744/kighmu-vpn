package com.kighmu.vpn.engines

object Tun2Socks {
    val isAvailable get() = dev.epro.tun2socks.Tun2Socks.isAvailable

    fun runTun2Socks(tunFd: Int, mtu: Int, ip: String, prefix: String,
                     socksServerAddress: String, udpgwServerAddress: String,
                     udpgwTransparentDNS: Boolean) {
        try {
            dev.epro.tun2socks.Tun2Socks.runTun2Socks(
                tunFd, mtu, ip, prefix, socksServerAddress, udpgwServerAddress, udpgwTransparentDNS
            )
        } catch (_: Exception) {}
    }

    fun terminateTun2Socks() {
        try { dev.epro.tun2socks.Tun2Socks.terminateTun2Socks() } catch (_: Throwable) {}
    }
}
