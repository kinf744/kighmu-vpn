package com.kighmu.vpn.engines

import android.content.Context
import com.kighmu.vpn.models.KighmuConfig
import com.kighmu.vpn.profiles.V2rayDnsProfileRepository
import com.kighmu.vpn.utils.KighmuLogger
import kotlinx.coroutines.*

/**
 * MultiXraySlowDnsEngine — V2Ray + SlowDNS multi-profil
 *
 * Connexion SÉQUENTIELLE avec retry (20 tentatives max par profil),
 * identique au comportement de MultiSlowDnsEngine.
 * Si un profil se connecte → on passe au suivant.
 * Si un profil échoue après 20 tentatives → on passe au suivant.
 * Tous les tunnels réussis sont ensuite équilibrés via SocksBalancer + HEV JNI.
 */
class MultiXraySlowDnsEngine(
    private val baseConfig: KighmuConfig,
    private val context: Context,
    private val vpnService: android.net.VpnService? = null
) : TunnelEngine {

    companion object {
        const val TAG = "MultiXraySlo"
        const val MAX_RETRIES = 20
        const val RETRY_DELAY_MS = 2000L
        const val SESSION_TIMEOUT_MS = 30000L
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val dnsttEngines = mutableListOf<SlowDnsEngine>()
    private var xray: XrayEngine? = null
    private var activePorts = listOf<Int>()
    private var socksBalancer: SocksBalancer? = null

    override suspend fun start(): Int {
        val repo = V2rayDnsProfileRepository(context)
        val selected = repo.getSelected()

        if (selected.isEmpty()) {
            KighmuLogger.info(TAG, "Aucun profil V2ray+DNS sélectionné → config par défaut")
            val dnstt = SlowDnsEngine(baseConfig, context, null, 0)
            synchronized(dnsttEngines) { dnsttEngines.add(dnstt) }
            val port = dnstt.startDnsttOnly()
            xray = XrayEngine(baseConfig, context, port, 0, vpnService)
            activePorts = listOf(port)
            return xray!!.start()
        }

        // Nettoyage des engines précédents
        KighmuLogger.info(TAG, "Nettoyage engines précédents (${dnsttEngines.size})...")
        synchronized(dnsttEngines) {
            dnsttEngines.forEach { e -> try { runBlocking { e.stop() } } catch (_: Exception) {} }
            dnsttEngines.clear()
        }
        try { xray?.stop() } catch (_: Exception) {}
        xray = null
        socksBalancer?.stop()
        socksBalancer = null

        // Libération des ports dnstt résiduels
        try {
            Runtime.getRuntime().exec(arrayOf("sh", "-c", "pkill -9 -f dnstt")).waitFor()
        } catch (_: Exception) {}
        delay(500)

        KighmuLogger.info(TAG, "=== STEP 1: Connexion SÉQUENTIELLE ${selected.size} profil(s) V2ray+DNS ===")

        val successPorts = mutableListOf<Int>()
        val successXrayConfigs = mutableListOf<Pair<Int, KighmuConfig>>()

        // ── Connexion séquentielle avec retry par profil ────────────────────
        selected.forEachIndexed { idx, profile ->
            KighmuLogger.info(TAG, "Profil[${idx + 1}/${selected.size}] démarrage: ${profile.profileName}")

            val dnsCfg = baseConfig.copy(
                slowDns = baseConfig.slowDns.copy(
                    dnsServer = profile.dnsServer,
                    dnsPort = profile.dnsPort,
                    nameserver = profile.nameserver,
                    publicKey = profile.publicKey
                )
            )

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

            var port = -1
            var attempt = 0

            while (attempt < MAX_RETRIES && port <= 0) {
                attempt++
                KighmuLogger.info(TAG, "Profil[${idx + 1}] tentative $attempt/$MAX_RETRIES...")
                val engine = SlowDnsEngine(dnsCfg, context, null, idx)
                try {
                    port = withTimeoutOrNull(SESSION_TIMEOUT_MS) { engine.startDnsttOnly() } ?: -1
                    if (port > 0) {
                        synchronized(dnsttEngines) { dnsttEngines.add(engine) }
                        KighmuLogger.info(TAG, "Profil[${idx + 1}] CONNECTÉ ✓ tentative=$attempt port=$port")
                    } else {
                        try { engine.stop() } catch (_: Exception) {}
                        if (attempt < MAX_RETRIES) {
                            KighmuLogger.warning(TAG, "Profil[${idx + 1}] échec tentative $attempt — retry dans ${RETRY_DELAY_MS}ms")
                            delay(RETRY_DELAY_MS)
                        }
                    }
                } catch (e: Exception) {
                    try { engine.stop() } catch (_: Exception) {}
                    KighmuLogger.error(TAG, "Profil[${idx + 1}] exception tentative $attempt: ${e.message}")
                    if (attempt < MAX_RETRIES) delay(RETRY_DELAY_MS)
                }
            }

            if (port > 0) {
                successPorts.add(port)
                successXrayConfigs.add(Pair(port, xrayCfg))
            } else {
                KighmuLogger.error(TAG, "Profil[${idx + 1}] ÉCHEC définitif après $MAX_RETRIES tentatives ✗")
            }
        }

        KighmuLogger.info(TAG, "=== STEP 2: ${successPorts.size}/${selected.size} profils connectés ===")

        if (successPorts.isEmpty()) {
            throw Exception("Aucun profil V2ray+DNS connecté après $MAX_RETRIES tentatives chacun")
        }

        // ── Démarrer Xray sur le premier profil réussi ─────────────────────
        val (firstPort, firstXrayCfg) = successXrayConfigs.first()
        xray = XrayEngine(firstXrayCfg, context, firstPort, 0, vpnService)
        val xraySocksPort = xray!!.start()

        // ── Balancer multi-profil si plusieurs tunnels réussis ──────────────
        activePorts = if (successPorts.size > 1) successPorts else listOf(xraySocksPort)

        if (successPorts.size > 1) {
            KighmuLogger.info(TAG, "=== STEP 3: Démarrage SocksBalancer sur ${successPorts.size} ports ===")
            val balancer = SocksBalancer(successPorts)
            balancer.start()
            socksBalancer = balancer
            KighmuLogger.info(TAG, "Balancer actif sur port ${SocksBalancer.BALANCER_PORT}")
        }

        val finalPort = if (successPorts.size > 1) SocksBalancer.BALANCER_PORT else xraySocksPort
        KighmuLogger.info(TAG, "=== V2ray+DNS prêt — port=$finalPort, ${successPorts.size} tunnel(s) actif(s) ===")
        return finalPort
    }

    override fun startTun2Socks(fd: Int) {
        KighmuLogger.info(TAG, "hev multi-SOCKS fd=$fd ports=$activePorts")
        HevTun2Socks.init()
        val svc = vpnService ?: run {
            KighmuLogger.error(TAG, "VpnService null — impossible de démarrer HevTun2Socks")
            xray?.startTun2Socks(fd)
            return
        }
        if (HevTun2Socks.isAvailable) {
            if (activePorts.size > 1) {
                HevTun2Socks.startMulti(context, fd, activePorts, svc)
            } else {
                HevTun2Socks.start(context, fd, activePorts.firstOrNull() ?: return, svc)
            }
        } else {
            xray?.startTun2Socks(fd)
        }
    }

    override suspend fun stop() {
        KighmuLogger.info(TAG, "Arrêt MultiXraySlowDnsEngine...")
        try { HevTun2Socks.stop() } catch (_: Exception) {}
        try { socksBalancer?.stop(); socksBalancer = null } catch (_: Exception) {}
        try { xray?.stop(); xray = null } catch (_: Exception) {}
        synchronized(dnsttEngines) {
            dnsttEngines.forEach { try { runBlocking { it.stop() } } catch (_: Exception) {} }
            dnsttEngines.clear()
        }
        scope.cancel()
        KighmuLogger.info(TAG, "MultiXraySlowDnsEngine arrêté ✅")
    }

    override suspend fun sendData(data: ByteArray, length: Int) = xray?.sendData(data, length) ?: Unit
    override suspend fun receiveData(): ByteArray? = xray?.receiveData()
    override fun isRunning() = xray?.isRunning() ?: false
}
