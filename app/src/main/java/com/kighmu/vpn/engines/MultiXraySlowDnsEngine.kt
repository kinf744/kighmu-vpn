package com.kighmu.vpn.engines

import android.content.Context
import com.kighmu.vpn.models.KighmuConfig
import com.kighmu.vpn.profiles.V2rayDnsProfileRepository
import com.kighmu.vpn.utils.KighmuLogger
import kotlinx.coroutines.*

class MultiXraySlowDnsEngine(
    private val baseConfig: KighmuConfig,
    private val context: Context,
    private val vpnService: android.net.VpnService? = null
) : TunnelEngine {

    private val TAG = "MultiXraySlo"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val dnsttEngines = mutableListOf<SlowDnsEngine>()
    private var xray: XrayEngine? = null
    private var activePorts = listOf<Int>()

    override suspend fun start(): Int {
        val repo = V2rayDnsProfileRepository(context)
        val selected = repo.getSelected()

        if (selected.isEmpty()) {
            KighmuLogger.info(TAG, "Aucun profil V2ray+DNS → config par défaut")
            val dnstt = SlowDnsEngine(baseConfig, context, null, 0)
            dnsttEngines.add(dnstt)
            val port = dnstt.startDnsttOnly()
            
            xray = XrayEngine(baseConfig, context, port, 0, vpnService)
            activePorts = listOf(port)
            return xray!!.start()
        }

        KighmuLogger.info(TAG, "=== Démarrage ${selected.size} tunnels V2ray+DNS ===")

        // Lancer tous les dnstt en parallèle
        val results = selected.mapIndexed { idx, profile ->
            scope.async {
                try {
                    val cfg = baseConfig.copy(
                        slowDns = baseConfig.slowDns.copy(
                            dnsServer = profile.dnsServer,
                            dnsPort = profile.dnsPort,
                            nameserver = profile.nameserver,
                            publicKey = profile.publicKey
                        )
                    )
                    val engine = SlowDnsEngine(cfg, context, null, idx)
                    synchronized(dnsttEngines) { dnsttEngines.add(engine) }
                    val port = engine.startDnsttOnly()
                    KighmuLogger.info(TAG, "V2ray+DNS[$idx] ${profile.profileName} prêt port=$port")
                    port
                } catch (e: Exception) {
                    KighmuLogger.error(TAG, "V2ray+DNS[$idx] ${profile.profileName} ÉCHEC: ${e.message}")
                    -1
                }
            }
        }.awaitAll()

        val successPorts = results.filter { it > 0 }
        if (successPorts.isEmpty()) throw Exception("Aucun tunnel V2ray+DNS connecté")

        activePorts = successPorts
        KighmuLogger.info(TAG, "=== STEP 2: ${successPorts.size}/${selected.size} connectées ===")
        KighmuLogger.info(TAG, "Ports dnstt actifs: $activePorts")

        // Utiliser le premier profil réussi pour configurer Xray
        val firstSuccessIdx = results.indexOfFirst { it > 0 }
        val firstProfile = selected[firstSuccessIdx]
        
        val xrayConfig = baseConfig.copy(
            xray = baseConfig.xray.copy(
                jsonConfig = firstProfile.xrayJsonConfig,
                protocol = firstProfile.protocol,
                serverAddress = firstProfile.serverAddress,
                serverPort = firstProfile.serverPort,
                uuid = firstProfile.uuid,
                encryption = firstProfile.encryption,
                transport = firstProfile.transport,
                wsPath = firstProfile.wsPath,
                wsHost = firstProfile.wsHost,
                tls = firstProfile.tls,
                sni = firstProfile.sni,
                allowInsecure = firstProfile.allowInsecure
            )
        )

        xray = XrayEngine(xrayConfig, context, successPorts.first(), 0, vpnService)
        return xray!!.start()
    }

    override fun startTun2Socks(fd: Int) {
        KighmuLogger.info(TAG, "hev multi-SOCKS fd=$fd ports=$activePorts")
        com.kighmu.vpn.engines.HevTun2Socks.init()
        if (com.kighmu.vpn.engines.HevTun2Socks.isAvailable && activePorts.size > 1) {
            // Multi-SOCKS via hev pour plusieurs tunnels
            com.kighmu.vpn.engines.HevTun2Socks.startMulti(context, fd, activePorts, vpnService ?: return)
        } else {
            xray?.startTun2Socks(fd)
        }
    }

    override suspend fun stop() {
        com.kighmu.vpn.engines.HevTun2Socks.stop()
        xray?.stop()
        xray = null
        dnsttEngines.forEach { try { it.stop() } catch (_: Exception) {} }
        dnsttEngines.clear()
        scope.cancel()
    }

    override suspend fun sendData(data: ByteArray, length: Int) = xray?.sendData(data, length) ?: Unit
    override suspend fun receiveData(): ByteArray? = xray?.receiveData()
    override fun isRunning() = xray?.isRunning() ?: false
}
