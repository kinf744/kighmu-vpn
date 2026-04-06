package com.kighmu.vpn.engines

import android.content.Context
import com.kighmu.vpn.models.KighmuConfig
import com.kighmu.vpn.profiles.ProfileRepository
import com.kighmu.vpn.utils.KighmuLogger
import kotlinx.coroutines.*

class MultiXraySlowDnsEngine(
    private val baseConfig: KighmuConfig,
    private val context: Context
) : TunnelEngine {

    private val TAG = "MultiXraySlowDns"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val dnsttEngines = mutableListOf<SlowDnsEngine>()
    private val xray = XrayEngine(baseConfig, context)
    private var activePorts = listOf<Int>()

    override suspend fun start(): Int {
        val repo = ProfileRepository(context)
        val selected = repo.getSelected()

        if (selected.isEmpty()) {
            KighmuLogger.info(TAG, "Aucun profil → config par défaut")
            val dnstt = SlowDnsEngine(baseConfig, context, null, 0)
            dnsttEngines.add(dnstt)
            val port = dnstt.startDnsttOnly()
            xray.dnsttProxyPort = port
            activePorts = listOf(port)
            return xray.start()
        }

        KighmuLogger.info(TAG, "=== Démarrage ${selected.size} tunnels dnstt ===")

        // Lancer tous les dnstt en parallèle
        val results = selected.mapIndexed { idx, profile ->
            scope.async {
                try {
                    val cfg = baseConfig.copy(
                        slowDns = baseConfig.slowDns.copy(
                            dnsServer = profile.dnsServer,
                            nameserver = profile.nameserver,
                            publicKey = profile.publicKey
                        )
                    )
                    val engine = SlowDnsEngine(cfg, context, null, idx)
                    synchronized(dnsttEngines) { dnsttEngines.add(engine) }
                    val port = engine.startDnsttOnly()
                    KighmuLogger.info(TAG, "dnstt[$idx] prêt port=$port")
                    port
                } catch (e: Exception) {
                    KighmuLogger.error(TAG, "dnstt[$idx] ÉCHEC: ${e.message}")
                    -1
                }
            }
        }.awaitAll()

        val successPorts = results.filter { it > 0 }
        if (successPorts.isEmpty()) throw Exception("Aucun tunnel dnstt connecté")

        activePorts = successPorts
        KighmuLogger.info(TAG, "Ports dnstt actifs: $activePorts")

        // Xray utilise le premier port dnstt
        xray.dnsttProxyPort = successPorts.first()
        return xray.start()
    }

    override fun startTun2Socks(fd: Int) {
        KighmuLogger.info(TAG, "hev multi-SOCKS fd=$fd ports=$activePorts")
        com.kighmu.vpn.engines.HevTun2Socks.init()
        if (com.kighmu.vpn.engines.HevTun2Socks.isAvailable && activePorts.size > 1) {
            // Multi-SOCKS via hev pour plusieurs tunnels
            com.kighmu.vpn.engines.HevTun2Socks.startMulti(context, fd, activePorts)
        } else {
            xray.startTun2Socks(fd)
        }
    }

    override suspend fun stop() {
        com.kighmu.vpn.engines.HevTun2Socks.stop()
        xray.dnsttProxyPort = 0
        xray.stop()
        dnsttEngines.forEach { try { it.stop() } catch (_: Exception) {} }
        dnsttEngines.clear()
        scope.cancel()
    }

    override suspend fun sendData(data: ByteArray, length: Int) = xray.sendData(data, length)
    override suspend fun receiveData(): ByteArray? = xray.receiveData()
    override fun isRunning() = xray.isRunning()
}
