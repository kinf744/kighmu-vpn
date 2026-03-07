package com.kighmu.vpn.engines

import android.content.Context
import com.kighmu.vpn.models.KighmuConfig
import com.kighmu.vpn.models.TunnelMode
import com.kighmu.vpn.utils.KighmuLogger

object TunnelEngineFactory {

    private const val TAG = "TunnelEngineFactory"

    fun create(config: KighmuConfig, context: Context): TunnelEngine {
        val mode = config.tunnelMode
        KighmuLogger.info(TAG, "=== Création engine: ${mode.label} ===")
        KighmuLogger.info(TAG, "Mode ID: ${mode.id}")

        return when (mode) {
            TunnelMode.SLOW_DNS -> {
                KighmuLogger.info(TAG, "Engine: SlowDnsEngine")
                KighmuLogger.info(TAG, "DNS Server: ${config.slowDns.dnsServer}")
                KighmuLogger.info(TAG, "Nameserver: ${config.slowDns.nameserver}")
                KighmuLogger.info(TAG, "SSH: ${config.sshCredentials.host}:${config.sshCredentials.port}")
                SlowDnsEngine(config, context)
            }
            TunnelMode.HTTP_PROXY -> {
                KighmuLogger.info(TAG, "Engine: SshHttpProxyEngine")
                KighmuLogger.info(TAG, "Proxy: ${config.httpProxy.proxyHost}:${config.httpProxy.proxyPort}")
                KighmuLogger.info(TAG, "SSH: ${config.sshCredentials.host}:${config.sshCredentials.port}")
                SshHttpProxyEngine(config, context)
            }
            TunnelMode.SSH_WEBSOCKET -> {
                KighmuLogger.info(TAG, "Engine: SshWebSocketEngine")
                KighmuLogger.info(TAG, "WS: ${config.sshWebSocket.wsHost}:${config.sshWebSocket.wsPort}")
                KighmuLogger.info(TAG, "SSH: ${config.sshCredentials.host}:${config.sshCredentials.port}")
                SshWebSocketEngine(config, context)
            }
            TunnelMode.SSH_SSL_TLS -> {
                KighmuLogger.info(TAG, "Engine: SshSslEngine")
                KighmuLogger.info(TAG, "SSL: ${config.sshSsl.sslHost}:${config.sshSsl.sslPort}")
                KighmuLogger.info(TAG, "SNI: ${config.sshSsl.sni}")
                SshSslEngine(config, context)
            }
            TunnelMode.V2RAY_XRAY -> {
                KighmuLogger.info(TAG, "Engine: XrayEngine")
                KighmuLogger.info(TAG, "Server: ${config.xray.serverAddress}:${config.xray.serverPort}")
                XrayEngine(config, context)
            }
            TunnelMode.V2RAY_SLOWDNS -> {
                KighmuLogger.info(TAG, "Engine: XraySlowDnsEngine")
                KighmuLogger.info(TAG, "Nameserver: ${config.slowDns.nameserver}")
                XraySlowDnsEngine(config, context)
            }
            TunnelMode.HYSTERIA_UDP -> {
                KighmuLogger.info(TAG, "Engine: HysteriaEngine")
                KighmuLogger.info(TAG, "Server: ${config.hysteria.serverAddress}:${config.hysteria.serverPort}")
                HysteriaEngine(config, context)
            }
        }
    }
}