package com.kighmu.vpn.engines

import android.content.Context
import android.net.VpnService
import com.kighmu.vpn.models.KighmuConfig
import com.kighmu.vpn.profiles.ProfileRepository
import com.kighmu.vpn.profiles.SlowDnsProfile
import com.kighmu.vpn.utils.KighmuLogger
import com.kighmu.vpn.engines.SocksBalancer
import kotlinx.coroutines.*

class MultiSlowDnsEngine(
    private val baseConfig: KighmuConfig,
    private val context: Context,
    private val vpnService: VpnService? = null
) : TunnelEngine {

    companion object {
        const val TAG = "MultiSlowDnsEngine"
        const val SESSION_TIMEOUT_MS = 6000L  // 6s: kex SSH sur DNS peut prendre 3-5s
    }

    private val engines = mutableListOf<SlowDnsEngine>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var activePort = 10800
    private var tunFd: Int = -1
    private var socksBalancer: SocksBalancer? = null

    override suspend fun start(): Int {
        val repo = ProfileRepository(context)
        val selected = repo.getSelected()

        if (selected.isEmpty()) {
            KighmuLogger.info(TAG, "Aucun profil sélectionné → config par défaut")
            val engine = SlowDnsEngine(baseConfig, context, vpnService, 0)
            synchronized(engines) { engines.add(engine) }
            return engine.start()
        }

        // Nettoyer les engines précédents avant de relancer (évite "address already in use")
        KighmuLogger.info(TAG, "Nettoyage engines précédents (${engines.size})...")
        synchronized(engines) {
            engines.forEach { e ->
                // Utilisation de runBlocking pour forcer l'arrêt synchrone avant de continuer
                try { runBlocking { e.stop() } } catch (_: Exception) {}
            }
            engines.clear()
        }
        
        // Principe du build #736 : Nettoyage préventif agressif des ports SOCKS et DNSTT
        try {
            val portsToKill = (10800..10810).joinToString(" ") { "$it/tcp" } + " " +
                             (7000..7010).joinToString(" ") { "$it/tcp" } + " 10900/tcp"
            Runtime.getRuntime().exec(arrayOf("sh", "-c", "fuser -k $portsToKill")).waitFor()
            Runtime.getRuntime().exec(arrayOf("sh", "-c", "pkill -9 -f dnstt")).waitFor()
        } catch (_: Exception) {}

        socksBalancer?.stop()
        socksBalancer = null
        
        // Attendre la libération des ressources noyau (500ms)
        delay(500)

        // Calcul du total de flux (profils × tunnelCount chacun)
        val totalFlux = selected.sumOf { it.tunnelCount.coerceIn(1, 4) }
        KighmuLogger.info(TAG, "=== STEP 1: Connexion séquentielle $totalFlux flux (${selected.size} profil(s)) ===")

        // STEP 1 : Connexion séquentielle avec retry agressif par session
        val results = mutableListOf<Pair<Int, Int>>()
        var globalIdx = 0
        selected.forEach { profile ->
            val count = profile.tunnelCount.coerceIn(1, 4)
            KighmuLogger.info(TAG, "Profil '${profile.profileName}' → $count flux parallèles")
            repeat(count) { fluxIdx ->
                val sessionLabel = "${profile.profileName}[${fluxIdx+1}/$count]"
                KighmuLogger.info(TAG, "Session[${globalIdx+1}/$totalFlux] démarrage: $sessionLabel")
                // Retry agressif : nouvel engine à chaque tentative (ports propres)
                // La session DOIT se connecter avant de passer à la suivante
                val MAX_RETRIES = 30
                val RETRY_DELAY_MS = 800L
                var port = -1
                var attempt = 0
                var activeEngine = SlowDnsEngine(buildConfig(profile), context, vpnService, globalIdx)
                synchronized(engines) { engines.add(activeEngine) }
                while (attempt < MAX_RETRIES && port <= 0) {
                    attempt++
                    if (attempt > 1) {
                        KighmuLogger.warning(TAG, "Session[${globalIdx+1}] retry $attempt/$MAX_RETRIES dans ${RETRY_DELAY_MS}ms...")
                        // Tuer seulement SSH, garder dnstt vivant pour retry rapide
                        try { activeEngine.stopSshOnly() } catch (_: Exception) {}
                        delay(RETRY_DELAY_MS)
                    }
                    port = try {
                        KighmuLogger.info(TAG, "Session[${globalIdx+1}] tentative $attempt/$MAX_RETRIES: $sessionLabel")
                        withTimeoutOrNull(SESSION_TIMEOUT_MS) { activeEngine.start() } ?: -1
                    } catch (e: Exception) {
                        KighmuLogger.error(TAG, "Session[${globalIdx+1}] tentative $attempt FAILED: ${e.message}")
                        -1
                    }
                }

                if (port > 0) {
                    KighmuLogger.info(TAG, "Session[${globalIdx+1}] CONNECTÉE ✓ port=$port (tentative $attempt) flux=$sessionLabel")
                } else {
                    KighmuLogger.error(TAG, "Session[${globalIdx+1}] ABANDON après $MAX_RETRIES tentatives ✗ flux=$sessionLabel")
                }
                results.add(Pair(globalIdx, port))
                globalIdx++
            }
        }

        // STEP 2 : Résultats
        KighmuLogger.info(TAG, "=== STEP 2: ${results.count { it.second > 0 }}/${selected.size} sessions ===")
        val successPorts = results.filter { it.second > 0 }.map { it.second }
        val failedCount = results.count { it.second <= 0 }

        KighmuLogger.info(TAG, "=== STEP 2 terminé: ${successPorts.size}/${selected.size} connectées ===")
        if (failedCount > 0) {
            KighmuLogger.warning(TAG, "$failedCount session(s) ont échoué")
        }

        if (successPorts.isEmpty()) {
            throw Exception("Aucune session SlowDNS connectée sur ${selected.size} tentatives")
        }

        // STEP 3 : Démarrer le balancer sur tous les ports SOCKS connectés
        val connectedPorts = successPorts.ifEmpty { listOf(SlowDnsEngine.BASE_SOCKS_PORT) }

        KighmuLogger.info(TAG, "Ports SOCKS actifs: $connectedPorts")
        val balancer = SocksBalancer(connectedPorts)
        balancer.start()
        socksBalancer = balancer
        activePort = SocksBalancer.BALANCER_PORT
        KighmuLogger.info(TAG, "=== STEP 3: VPN prêt - port=$activePort, ${successPorts.size} tunnels actifs ===")

        // Surveiller les sessions en background
        monitorSessions(selected)

        return activePort
    }

    private fun monitorSessions(profiles: List<SlowDnsProfile>) {
        scope.launch {
            while (isActive) {
                delay(10000)
                val alive = engines.count { it.isRunning() }
                val total = engines.size
                if (total > 0) {
                    KighmuLogger.info(TAG, "Sessions actives: $alive/$total")
                }
                if (alive == 0 && total > 0) {
                    KighmuLogger.error(TAG, "Toutes les sessions tombées - redémarrage...")
                    // Redémarrer toutes les sessions
                    try { start() } catch (e: Exception) {
                        KighmuLogger.error(TAG, "Echec redémarrage sessions: ${e.message}")
                    }
                    break
                }
                // Redémarrer les sessions mortes individuellement
                engines.forEachIndexed { idx, engine ->
                    if (!engine.isRunning() && profiles.size > idx) {
                        KighmuLogger.warning(TAG, "Session[$idx] morte - redémarrage...")
                        scope.launch {
                            try {
                                val port = engine.start()
                                if (port > 0) {
                                    KighmuLogger.info(TAG, "Session[$idx] redémarrée port=$port")
                                    // Mettre à jour le balancer
                                    val alivePorts = engines.filter { it.isRunning() }
                                        .mapNotNull { it.getSocksPort() }
                                    socksBalancer?.updatePorts(alivePorts)
                                }
                            } catch (_: Exception) {}
                        }
                    }
                }
            }
        }
    }

    override fun startTun2Socks(fd: Int) {
        tunFd = fd
        // Protéger le descripteur de fichier au niveau orchestrateur
        try {
            vpnService?.protect(fd)
        } catch (e: Exception) {
            KighmuLogger.error(TAG, "Erreur protection FD: ${e.message}")
        }
        
        // Démarrer tun2socks directement sur le port du balancer
        // Le balancer distribue le trafic sur tous les tunnels actifs
        val balancerPort = SocksBalancer.BALANCER_PORT
        val tunnelCount = engines.count { it.isRunning() }
        KighmuLogger.info(TAG, "tun2socks → Balancer:$balancerPort ($tunnelCount tunnels actifs)")
        try {
            try { HevTun2Socks.init() } catch (_: Exception) {}
            if (HevTun2Socks.isAvailable && vpnService != null) {
                val activePorts = engines.filter { it.isRunning() }.mapNotNull { it.getSocksPort() }
                KighmuLogger.info(TAG, "HevTun2Socks multi-port: $activePorts via balancer:$balancerPort")
                Thread {
                    try {
                        HevTun2Socks.start(context, fd, balancerPort, vpnService, 8500)
                        KighmuLogger.info(TAG, "HevTun2Socks démarré ✅ port=$balancerPort")
                    } catch (e: Exception) {
                        KighmuLogger.error(TAG, "HevTun2Socks erreur: ${e.message}")
                    }
                }.also { it.isDaemon = true }.start()
            } else {
                // Fallback: déléguer au premier engine
                val firstEngine = engines.firstOrNull { it.isRunning() } ?: engines.firstOrNull()
                firstEngine?.startTun2SocksOnPort(fd, balancerPort)
                    ?: KighmuLogger.error(TAG, "Aucune session disponible pour tun2socks!")
            }
        } catch (e: Exception) {
            KighmuLogger.error(TAG, "startTun2Socks erreur: ${e.message}")
        }
    }

    override suspend fun stop() {
        KighmuLogger.info(TAG, "Arrêt de ${engines.size} session(s)...")
        
        // 1. Arrêter le SocksBalancer d'abord
        try {
            socksBalancer?.stop()
            socksBalancer = null
        } catch (_: Exception) {}

        // 2. Arrêter HevTun2Socks JNI globalement
        try {
            com.kighmu.vpn.engines.HevTun2Socks.stop()
        } catch (_: Exception) {}

        // 3. Arrêter chaque moteur individuellement (SSH, DNSTT)
        engines.forEach { engine ->
            try { engine.stop() } catch (_: Exception) {}
        }
        engines.clear()
        
        // 4. Annuler les jobs de monitoring
        scope.cancel()
        
        KighmuLogger.info(TAG, "Toutes les ressources MultiSlowDNS libérées")
    }

    override suspend fun sendData(data: ByteArray, length: Int) {
        engines.firstOrNull { it.isRunning() }?.sendData(data, length)
    }

    override suspend fun receiveData(): ByteArray? {
        return engines.firstOrNull { it.isRunning() }?.receiveData()
    }

    override fun isRunning(): Boolean = engines.any { it.isRunning() }

    private fun buildConfig(p: SlowDnsProfile): KighmuConfig {
        return baseConfig.copy(
            sshCredentials = baseConfig.sshCredentials.copy(
                host = p.sshHost, port = p.sshPort,
                username = p.sshUser, password = p.sshPass
            ),
            slowDns = baseConfig.slowDns.copy(
                sshHost = p.sshHost,
                sshPort = p.sshPort,
                sshUser = p.sshUser,
                sshPass = p.sshPass,
                dnsServer = p.dnsServer,
                nameserver = p.nameserver,
                publicKey = p.publicKey.trim().replace(" ", "").replace("\n", "").replace("\r", "").replace("\t", "")
            )
        )
    }
}
