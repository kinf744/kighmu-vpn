package com.kighmu.vpn.engines

import android.net.VpnService

/**
 * Interface V2RayVPNServiceSupportsSet pour libgojni.so
 * Doit correspondre exactement au package chzPsiphonAndV2ray dans libgojni
 */
interface V2RayVPNServiceSupportsSet {
    fun onEmitStatus(l: Long, s: String): Long
    fun prepare(): Long
    fun protect(fd: Long): Boolean
    fun setup(conf: String): Long
    fun shutdown(): Long
}

/**
 * Implémentation pour Hysteria - protège les sockets UDP via VpnService
 */
class HysteriaDialer(private val vpnService: VpnService) : V2RayVPNServiceSupportsSet {
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
