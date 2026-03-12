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
        const val SESSION_TIMEOUT_MS = 30000L  // 30s max par session
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
            val engine = SlowDnsEngine(baseConfig, context, null, 0)
            synchronized(engines) { engines.add(engine) }
            return engine.start()
        }

        // Nettoyer les engines précédents avant de relancer (évite "address already in use")
        KighmuLogger.info(TAG, "Nettoyage engines précédents (${engines.size})...")
        synchronized(engines) {
            engines.forEach { e ->
                try { scope.launch { e.stop() } } catch (_: Exception) {}
            }
            engines.clear()
        }
        socksBalancer?.stop()
        socksBalancer = null
        // Attendre que les ports soient libérés
        delay(1500)

        KighmuLogger.info(TAG, "=== STEP 1: Lancement ${selected.size} session(s) en parallèle ===")

        // STEP 1 : Lancer toutes les sessions en parallèle
        val jobs = selected.mapIndexed { idx, profile ->
            scope.async {
                KighmuLogger.info(TAG, "Session[${idx+1}/${selected.size}] démarrage: ${profile.profileName}")
                val engine = SlowDnsEngine(buildConfig(profile), context, null, idx)
                synchronized(engines) { engines.add(engine) }
                val port = try {
                    withTimeoutOrNull(SESSION_TIMEOUT_MS) { engine.start() } ?: -1
                } catch (e: Exception) {
                    KighmuLogger.error(TAG, "Session[${idx+1}] FAILED: ${e.message}")
                    -1
                }
                if (port > 0) {
                    KighmuLogger.info(TAG, "Session[${idx+1}] CONNECTÉE ✓ port=$port (${profile.profileName})")
                } else {
                    KighmuLogger.error(TAG, "Session[${idx+1}] ÉCHEC ✗ (${profile.profileName})")
                }
                Pair(idx, port)
            }
        }

        // STEP 2 : Attendre TOUTES les sessions
        KighmuLogger.info(TAG, "=== STEP 2: Attente de toutes les sessions ===")
        val results = jobs.map { it.await() }
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
        val connectedPorts = engines.filter { it.isRunning() }.mapIndexed { idx, _ ->
            SlowDnsEngine.BASE_SOCKS_PORT + idx
        }.ifEmpty { listOf(SlowDnsEngine.BASE_SOCKS_PORT) }

        KighmuLogger.info(TAG, "Ports SOCKS actifs: $connectedPorts")
        val balancer = SocksBalancer(connectedPorts)
        balancer.start()
        socksBalancer = balancer

        // STEP 4 : tun2socks démarré par KighmuVpnService via startTun2Socks()
        // On retourne le port de la première session connectée
        activePort = successPorts.first()
        KighmuLogger.info(TAG, "=== STEP 3: VPN prêt - port principal=$activePort, ${successPorts.size} tunnels actifs ===")

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
                    KighmuLogger.error(TAG, "Toutes les sessions sont tombées!")
                    break
                }
            }
        }
    }

    override fun startTun2Socks(fd: Int) {
        tunFd = fd
        // Déléguer au premier engine connecté
        val firstConnected = engines.firstOrNull { it.isRunning() } ?: engines.firstOrNull()
        if (firstConnected != null) {
            KighmuLogger.info(TAG, "tun2socks démarré sur session principale port=$activePort")
            firstConnected.startTun2Socks(fd)
        } else {
            KighmuLogger.error(TAG, "Aucune session disponible pour tun2socks!")
        }
    }

    override suspend fun stop() {
        KighmuLogger.info(TAG, "Arrêt de ${engines.size} session(s)...")
        engines.forEach { try { it.stop() } catch (_: Exception) {} }
        engines.clear()
        scope.cancel()
        KighmuLogger.info(TAG, "Toutes les sessions arrêtées")
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
                dnsServer = p.dnsServer,
                nameserver = p.nameserver,
                publicKey = p.publicKey
            )
        )
    }
}
