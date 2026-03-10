package com.kighmu.vpn.engines

// Wrapper vers le vrai package JNI
object Tun2Socks {
    val isAvailable get() = dev.epro.tun2socks.Tun2Socks.isAvailable

    fun runTun2Socks(tunFd: Int, mtu: Int, ip: String, prefix: String,
                     socksServerAddress: String, udpgwServerAddress: String,
                     udpgwTransparentDNS: Boolean) {
        dev.epro.tun2socks.Tun2Socks.runTun2Socks(
            tunFd, mtu, ip, prefix, socksServerAddress, udpgwServerAddress, udpgwTransparentDNS
        )
    }

    fun terminateTun2Socks() {
        if (isAvailable) {
            try { dev.epro.tun2socks.Tun2Socks.terminateTun2Socks() } catch (_: Exception) {}
        }
    }
}
