package com.kighmu.vpn.engines

import android.net.VpnService

/**
 * ProtectedDialer pour Hysteria 2 via libgojni.so (comme OpenCustom)
 * Permet à Hysteria de créer des sockets UDP protégés hors du tunnel VPN
 */
class HysteriaDialer(private val vpnService: VpnService) {

    // Méthode appelée par libgojni via JNI (comme chzPsiphonAndV2ray.ProtectedDialer)
    fun protect(fd: Long): Boolean {
        return try {
            vpnService.protect(fd.toInt())
        } catch (e: Exception) {
            false
        }
    }

    fun isVServerReady(): Boolean = true

    fun prepareResolveChan() {}
}
