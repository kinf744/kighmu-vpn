package com.kighmu.vpn.engines

import android.content.Context
import com.kighmu.vpn.models.KighmuConfig
import com.kighmu.vpn.models.TunnelMode

/**
 * Abstract tunnel engine interface.
 * Each mode implements this to start/stop its underlying transport.
 * Returns the local SOCKS5/HTTP proxy port that the VPN interface routes traffic through.
 */
interface TunnelEngine {
    /** Start the engine. Returns the local proxy port. */
    suspend fun start(): Int

    /** Stop the engine and clean up resources. */
    suspend fun stop()

    /** Send raw packet data into the tunnel. */
    suspend fun sendData(data: ByteArray, length: Int)

    /** Receive raw packet data from the tunnel. Returns null when done. */
    suspend fun receiveData(): ByteArray?

    /** Is the tunnel currently running? */
    fun isRunning(): Boolean
}

// ─────────────────────────────────────────────────────────────────────────────
// Factory
// ─────────────────────────────────────────────────────────────────────────────

object TunnelEngineFactory {
    fun create(config: KighmuConfig, context: Context): TunnelEngine = when (config.tunnelMode) {
        TunnelMode.SLOW_DNS      -> SlowDnsEngine(config, context)
        TunnelMode.HTTP_PROXY    -> HttpProxyEngine(config, context)
        TunnelMode.SSH_WEBSOCKET -> SshWebSocketEngine(config, context)
        TunnelMode.SSH_SSL_TLS   -> SshSslEngine(config, context)
        TunnelMode.V2RAY_XRAY    -> XrayEngine(config, context)
        TunnelMode.V2RAY_SLOWDNS -> XraySlowDnsEngine(config, context)
        TunnelMode.HYSTERIA_UDP  -> HysteriaEngine(config, context)
    }
}
