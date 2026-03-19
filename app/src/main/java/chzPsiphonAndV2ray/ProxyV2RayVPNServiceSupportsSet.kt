package chzPsiphonAndV2ray

import android.net.VpnService

/**
 * Doit s'appeler exactement ChzPsiphonAndV2ray$proxyV2RayVPNServiceSupportsSet
 * pour correspondre aux symboles JNI dans libgojni.so
 */
class ChzPsiphonAndV2ray_proxyV2RayVPNServiceSupportsSet(
    private val vpnService: VpnService
) : V2RayVPNServiceSupportsSet {
    override fun protect(fd: Long): Boolean {
        return try { vpnService.protect(fd.toInt()) } catch (_: Exception) { false }
    }
    override fun prepare(): Long = 0L
    override fun setup(conf: String): Long = 0L
    override fun shutdown(): Long = 0L
    override fun onEmitStatus(l: Long, s: String): Long = 0L
}
