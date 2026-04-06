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
            val xray = XrayEngine(baseConfig, context, dnsttProxyPort = dnsttPort, instanceId = 0)
            xrayEngines.add(xray)
            val xrayPort = xray.start()
            activeXrayPorts = mutableListOf(xrayPort)
            return xrayPort
        }

        KighmuLogger.info(TAG, "=== Démarrage séquentiel de ${selected.size} tunnels V2ray+DNS ===")

        // Connexion séquentielle stricte avec Retry (comme SSH SlowDNS)
        for (idx in selected.indices) {
            val profile = selected[idx]
            var connected = false
            var attempts = 0
            val maxAttempts = 20 // Nombre de tentatives par profil (robustesse maximale)

            while (!connected && attempts < maxAttempts) {
                attempts++
                KighmuLogger.info(TAG, "Profil [${idx + 1}/${selected.size}] tentative $attempts/$maxAttempts: ${profile.profileName}")
                
                try {
                    // Nettoyer les moteurs précédents pour cet index en cas de retry
                    synchronized(dnsttEngines) {
                        if (dnsttEngines.size > idx) {
                            val oldDnstt = dnsttEngines[idx]
                            scope.launch { try { oldDnstt.stop() } catch (_: Exception) {} }
                        }
                    }
                    synchronized(xrayEngines) {
                        if (xrayEngines.size > idx) {
                            val oldXray = xrayEngines[idx]
                            scope.launch { try { oldXray.stop() } catch (_: Exception) {} }
                        }
                    }
                    if (attempts > 1) delay(2000) // Petite pause entre les retries

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
                    synchronized(dnsttEngines) { 
                        if (dnsttEngines.size > idx) dnsttEngines[idx] = dnstt else dnsttEngines.add(dnstt)
                    }
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
                    val xray = XrayEngine(xrayCfg, context, dnsttProxyPort = dnsttPort, instanceId = idx)
                    synchronized(xrayEngines) { 
                        if (xrayEngines.size > idx) xrayEngines[idx] = xray else xrayEngines.add(xray)
                    }
                    
                    // Attendre que Xray soit prêt
                    val xrayPort = withTimeout(30000L) { xray.start() }
                    
                    if (xrayPort > 0) {
                        activeXrayPorts.add(xrayPort)
                        KighmuLogger.info(TAG, "Profil [${idx + 1}] CONNECTÉ ✓ (Xray=$xrayPort)")
                        connected = true
                    } else {
                        throw Exception("Xray n'a pas retourné de port valide")
                    }
                } catch (e: Exception) {
                    KighmuLogger.warning(TAG, "Profil [${idx + 1}] tentative $attempts échouée: ${e.message}")
                    if (attempts >= maxAttempts) {
                        KighmuLogger.error(TAG, "Profil [${idx + 1}] ÉCHEC définitif après $maxAttempts tentatives")
                        throw Exception("Échec critique du profil ${profile.profileName}: ${e.message}")
                    }
                }
            }
        }

        KighmuLogger.info(TAG, "=== STEP 2: ${activeXrayPorts.size}/${selected.size} instances connectées ===")
        
        // Démarrer le balancer Kotlin (SocksBalancer) pour la compatibilité et le monitoring
        balancer = SocksBalancer(activeXrayPorts)
        balancer?.start()
        
        return SocksBalancer.BALANCER_PORT
    }

    override fun startTun2Socks(fd: Int) {
        // Aligné sur SSH SlowDNS : Utiliser exclusivement le SocksBalancer Kotlin
        val targetPort = SocksBalancer.BALANCER_PORT
        KighmuLogger.info(TAG, "tun2socks → Balancer Kotlin:$targetPort (${activeXrayPorts.size} tunnels)")
        
        // On utilise le premier moteur Xray pour lancer tun2socks, mais dirigé vers le port du balancer
        val firstXray = xrayEngines.firstOrNull()
        if (firstXray != null) {
            vpnService?.protect(fd)
            firstXray.startTun2SocksOnPort(fd, targetPort)
        } else {
            KighmuLogger.error(TAG, "Aucune instance Xray disponible pour tun2socks!")
        }
    }

    override suspend fun stop() {
        com.kighmu.vpn.engines.HevTun2Socks.stop()
        balancer?.stop()
        xrayEngines.forEach { try { it.stop() } catch (_: Exception) {} }
        dnsttEngines.forEach { try { it.stop() } catch (_: Exception) {} }
        xrayEngines.clear()
        dnsttEngines.clear()
        activeXrayPorts.clear()
        scope.cancel()
    }

    override suspend fun sendData(data: ByteArray, length: Int) {
        xrayEngines.firstOrNull()?.sendData(data, length)
    }
    override suspend fun receiveData(): ByteArray? = xrayEngines.firstOrNull()?.receiveData()
    override fun isRunning() = xrayEngines.any { it.isRunning() }
}
