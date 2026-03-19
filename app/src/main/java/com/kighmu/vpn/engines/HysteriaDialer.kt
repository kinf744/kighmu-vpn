package com.kighmu.vpn.engines

import android.net.VpnService

fun createHysteriaDialer(vpnService: VpnService): chzPsiphonAndV2ray.V2RayVPNServiceSupportsSet {
    return chzPsiphonAndV2ray.ChzPsiphonAndV2ray_proxyV2RayVPNServiceSupportsSet(vpnService)
}
