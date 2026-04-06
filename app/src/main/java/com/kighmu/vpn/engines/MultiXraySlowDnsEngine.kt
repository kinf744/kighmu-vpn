package com.kighmu.vpn.engines

import android.content.Context
import com.kighmu.vpn.models.KighmuConfig
import com.kighmu.vpn.profiles.V2rayDnsProfileRepository
import com.kighmu.vpn.utils.KighmuLogger
import kotlinx.coroutines.*

class MultiXraySlowDnsEngine(
    private val baseConfig: KighmuConfig,
    private val context: Context,
    private val vpnService: android.net.VpnService?
) : TunnelEngine {

    private val TAG = "MultiXraySlowDns"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val dnsttEngines = mutableListOf<SlowDnsEngine>()
    private val xrayEngines = mutableListOf<XrayEngine>()
    private var balancer: SocksBalancer? = null
    private var activeXrayPorts = mutableListOf<Int>()

    override suspend fun start(): Int {
        val repo = V2rayDnsProfileRepository(context)
        val selected = repo.getSelected()

        if (selected.isEmpty()) {
            KighmuLogger.info(TAG, "Aucun profil V2ray+DNS → config par défaut")
            val dnstt = SlowDnsEngine(baseConfig, context, null, 0)
            dnsttEngines.add(dnstt)
            val dnsttPort = dnstt.startDnsttOnly()
            val xray = XrayEngine(baseConfig, context)
            xray.dnsttProxyPort = dnsttPort
            xrayEngines.add(xray)
            val xrayPort = xray.start()
            activeXrayPorts = mutableListOf(xrayPort)
            return xrayPort
        }

        KighmuLogger.info(TAG, "=== Démarrage ${selected.size} tunnels V2ray+DNS (Multi-Instance) ===")

        // Lancer chaque paire DNSTT + Xray en parallèle
        val results = selected.mapIndexed { idx, profile ->
            scope.async {
                try {
                    // 1. Démarrer DNSTT pour ce profil
                    val dnsttCfg = baseConfig.copy(
                        slowDns = baseConfig.slowDns.copy(
                            dnsServer = profile.dnsServer,
                            dnsPort = profile.dnsPort,
                            nameserver = profile.nameserver,
                            publicKey = profile.publicKey
                        )
                    )
                    val dnstt = SlowDnsEngine(dnsttCfg, context, null, idx)
                    synchronized(dnsttEngines) { dnsttEngines.add(dnstt) }
                    val dnsttPort = dnstt.startDnsttOnly()
                    
                    // 2. Démarrer Xray routé vers ce DNSTT
                    val xrayCfg = baseConfig.copy(
                        xray = baseConfig.xray.copy(
                            jsonConfig = profile.xrayJsonConfig,
                            protocol = profile.protocol,
                            serverAddress = profile.serverAddress,
                            serverPort = profile.serverPort,
                            uuid = profile.uuid,
                            encryption = profile.encryption,
                            transport = profile.transport,
                            wsPath = profile.wsPath,
                            wsHost = profile.wsHost,
                            tls = profile.tls,
                            sni = profile.sni,
                            allowInsecure = profile.allowInsecure
                        )
                    )
                    val xray = XrayEngine(xrayCfg, context)
                    xray.dnsttProxyPort = dnsttPort
                    synchronized(xrayEngines) { xrayEngines.add(xray) }
                    val xrayPort = xray.start()
                    
                    KighmuLogger.info(TAG, "V2ray+DNS[$idx] ${profile.profileName} prêt: DNSTT=$dnsttPort, Xray=$xrayPort")
                    xrayPort
                } catch (e: Exception) {
                    KighmuLogger.error(TAG, "V2ray+DNS[$idx] ${profile.profileName} ÉCHEC: ${e.message}")
                    -1
                }
            }
        }.awaitAll()

        val successPorts = results.filter { it > 0 }
        if (successPorts.isEmpty()) throw Exception("Aucun tunnel V2ray+DNS n'a pu démarrer")

        activeXrayPorts = successPorts.toMutableList()
        KighmuLogger.info(TAG, "=== STEP 2: ${successPorts.size}/${selected.size} instances connectées ===")
        
        // Démarrer le balancer local pour répartir sur tous les Xray
        balancer = SocksBalancer(activeXrayPorts)
        balancer?.start()
        
        return SocksBalancer.BALANCER_PORT
    }

    override fun startTun2Socks(fd: Int) {
        val targetPort = SocksBalancer.BALANCER_PORT
        KighmuLogger.info(TAG, "Démarrage tunnel interface via Balancer (port=$targetPort)")
        
        com.kighmu.vpn.engines.HevTun2Socks.init()
        if (com.kighmu.vpn.engines.HevTun2Socks.isAvailable) {
            // Utiliser hev-socks5-tunnel JNI (très rapide)
            KighmuLogger.info(TAG, "hev tun2socks JNI fd=$fd port=$targetPort")
            vpnService?.protect(fd)
            vpnService?.let { com.kighmu.vpn.engines.HevTun2Socks.start(context, fd, targetPort, it) }
        } else {
            // Fallback: utiliser le premier Xray engine pour démarrer tun2socks (qui pointera vers le balancer)
            vpnService?.protect(fd)
            xrayEngines.firstOrNull()?.startTun2Socks(fd)
        }
    }

    override suspend fun stop() {
        com.kighmu.vpn.engines.HevTun2Socks.stop()
        balancer?.stop()
        xrayEngines.forEach { try { it.stop() } catch (_: Exception) {} }
        dnsttEngines.forEach { try { it.stop() } catch (_: Exception) {} }
        xrayEngines.clear()
        dnsttEngines.clear()
        scope.cancel()
    }

    override suspend fun sendData(data: ByteArray, length: Int) {
        xrayEngines.firstOrNull()?.sendData(data, length)
    }
    override suspend fun receiveData(): ByteArray? = xrayEngines.firstOrNull()?.receiveData()
    override fun isRunning() = xrayEngines.any { it.isRunning() }
}
