package com.kighmu.vpn.engines

import android.net.VpnService

/**
 * Implémente V2RayVPNServiceSupportsSet de libgojni.so
 * Exactement comme OpenCustom
 */
class HysteriaDialer(private val vpnService: VpnService) :
    chzPsiphonAndV2ray.V2RayVPNServiceSupportsSet {

    override fun protect(fd: Long): Boolean {
        return try {
            vpnService.protect(fd.toInt())
        } catch (e: Exception) {
            false
        }
    }

    override fun prepare(): Long = 0L
    override fun setup(conf: String): Long = 0L
    override fun shutdown(): Long = 0L
    override fun onEmitStatus(l: Long, s: String): Long = 0L
}
