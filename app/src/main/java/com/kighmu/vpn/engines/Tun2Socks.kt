package com.kighmu.vpn.engines

object Tun2Socks {
    init {
        System.loadLibrary("tun2socks")
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
