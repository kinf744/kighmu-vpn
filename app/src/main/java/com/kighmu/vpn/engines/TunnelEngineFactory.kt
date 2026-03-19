package com.kighmu.vpn.engines

import android.content.Context
import com.kighmu.vpn.models.KighmuConfig
import com.kighmu.vpn.models.TunnelMode
import com.kighmu.vpn.utils.KighmuLogger

object TunnelEngineFactory {
    private const val TAG = "TunnelEngineFactory"

    fun create(config: KighmuConfig, context: Context, vpnService: android.net.VpnService? = null, profileIndex: Int = 0): TunnelEngine {
        val mode = config.tunnelMode
        KighmuLogger.info(TAG, "=== Creation engine: ${mode.label} ===")
        return when (mode) {
            TunnelMode.SLOW_DNS      -> MultiSlowDnsEngine(config, context, vpnService)
            TunnelMode.HTTP_PROXY    -> HttpProxyEngine(config, context)
            TunnelMode.SSH_WEBSOCKET -> SshWebSocketEngine(config, context)
            TunnelMode.SSH_SSL_TLS   -> SshSslEngine(config, context)
            TunnelMode.V2RAY_XRAY    -> XrayEngine(config, context)
            TunnelMode.V2RAY_SLOWDNS -> XraySlowDnsEngine(config, context)
            TunnelMode.HYSTERIA_UDP  -> HysteriaEngine(config, context, vpnService)
        }
    }
}