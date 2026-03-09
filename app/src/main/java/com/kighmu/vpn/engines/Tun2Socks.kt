package com.kighmu.vpn.engines

object Tun2Socks {
    var isAvailable = false

    init {
        isAvailable = try {
            System.loadLibrary("tun2socks")
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        }
    }

    external fun runTun2Socks(
        tunFd: Int,
        mtu: Int,
        ip: String,
        prefix: String,
        socksServerAddress: String,
        udpgwServerAddress: String,
        udpgwTransparentDNS: Boolean
    )

    external fun terminateTun2Socks()
}
