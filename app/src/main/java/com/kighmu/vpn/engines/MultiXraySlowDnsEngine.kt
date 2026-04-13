package com.kighmu.vpn.engines

import android.content.Context
import com.kighmu.vpn.models.KighmuConfig
import com.kighmu.vpn.profiles.V2rayDnsProfileRepository
import com.kighmu.vpn.utils.KighmuLogger
import kotlinx.coroutines.*

/**
 * MultiXraySlowDnsEngine - V2Ray + SlowDNS multi-profil
 * Connexion sequentielle avec retry (20 tentatives max par profil).
 * Chaque profil peut lancer N flux dnstt+Xray parallelises (tunnelCount).
 * Tous les ports SOCKS Xray reussis sont equilibres via SocksBalancer.
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
    private val xrayEngines = mutableListOf<XrayEngine>()
    private var activePorts = listOf<Int>()
    private var socksBalancer: SocksBalancer? = null

    override suspend fun start(): Int {
        val repo = V2rayDnsProfileRepository(context)
        val selected = repo.getSelected()

        if (selected.isEmpty()) {
            KighmuLogger.info(TAG, "Aucun profil V2ray+DNS selectionne -> config par defaut")
            val dnstt = SlowDnsEngine(baseConfig, context, null, 0)
            synchronized(dnsttEngines) { dnsttEngines.add(dnstt) }
            val port = dnstt.startDnsttOnly()
            val xray = XrayEngine(baseConfig, context, port, 0, vpnService)
            synchronized(xrayEngines) { xrayEngines.add(xray) }
            activePorts = listOf(port)
            return xray.start()
        }

        // Nettoyage des engines precedents
        KighmuLogger.info(TAG, "Nettoyage engines precedents (${dnsttEngines.size})...")
        synchronized(dnsttEngines) {
            dnsttEngines.forEach { e -> try { runBlocking { e.stop() } } catch (_: Exception) {} }
            dnsttEngines.clear()
        }
        synchronized(xrayEngines) {
            xrayEngines.forEach { e -> try { runBlocking { e.stop() } } catch (_: Exception) {} }
            xrayEngines.clear()
        }
        socksBalancer?.stop()
        socksBalancer = null

        // Liberation des ports dnstt residuels
        try {
            Runtime.getRuntime().exec(arrayOf("sh", "-c", "pkill -9 -f dnstt")).waitFor()
        } catch (_: Exception) {}
        delay(500)

        // Calcul du total de flux (profils x tunnelCount chacun)
        val totalFlux = selected.sumOf { it.tunnelCount.coerceIn(1, 4) }
        KighmuLogger.info(TAG, "=== STEP 1: $totalFlux flux (${selected.size} profil(s)) V2ray+DNS ===")

        val successXrayConfigs = mutableListOf<Pair<Int, KighmuConfig>>()

        // Connexion sequentielle avec retry - N flux par profil
        var globalIdx = 0
        selected.forEach { profile ->
            val count = profile.tunnelCount.coerceIn(1, 4)
            KighmuLogger.info(TAG, "Profil '${profile.profileName}' -> $count flux paralleles")

            val dnsCfg = baseConfig.copy(
                slowDns = baseConfig.slowDns.copy(
                    dnsServer  = profile.dnsServer,
                    dnsPort    = profile.dnsPort,
                    nameserver = profile.nameserver,
                    publicKey  = profile.publicKey
                )
            )
            val xrayCfg = baseConfig.copy(
                xray = baseConfig.xray.copy(
                    jsonConfig     = profile.xrayJsonConfig,
                    protocol       = profile.protocol,
                    serverAddress  = profile.serverAddress,
                    serverPort     = profile.serverPort,
                    uuid           = profile.uuid,
                    encryption     = profile.encryption,
                    transport      = profile.transport,
                    wsPath         = profile.wsPath,
                    wsHost         = profile.wsHost,
                    tls            = profile.tls,
                    sni            = profile.sni,
                    allowInsecure  = profile.allowInsecure
                )
            )

            repeat(count) { fluxIdx ->
                val sessionLabel = "${profile.profileName}[${fluxIdx+1}/$count]"
                KighmuLogger.info(TAG, "Flux[${globalIdx+1}/$totalFlux] demarrage: $sessionLabel")
                var dnsttPort = -1
                var attempt = 0
                val dnsttEngine = SlowDnsEngine(dnsCfg, context, null, globalIdx)
                while (attempt < MAX_RETRIES && dnsttPort <= 0) {
                    attempt++
                    KighmuLogger.info(TAG, "Flux[${globalIdx+1}] tentative $attempt/$MAX_RETRIES...")
                    try {
                        dnsttPort = withTimeoutOrNull(SESSION_TIMEOUT_MS) { dnsttEngine.startDnsttOnly() } ?: -1
                        if (dnsttPort > 0) {
                            synchronized(dnsttEngines) { dnsttEngines.add(dnsttEngine) }
                            KighmuLogger.info(TAG, "Flux[${globalIdx+1}] dnstt OK port=$dnsttPort flux=$sessionLabel")
                        } else {
                            if (attempt < MAX_RETRIES) {
                                KighmuLogger.warning(TAG, "Flux[${globalIdx+1}] echec tentative $attempt - retry dans ${RETRY_DELAY_MS}ms")
                                delay(RETRY_DELAY_MS)
                            }
                        }
                    } catch (e: Exception) {
                        KighmuLogger.error(TAG, "Flux[${globalIdx+1}] exception tentative $attempt: ${e.message}")
                        if (attempt < MAX_RETRIES) delay(RETRY_DELAY_MS)
                    }
                }
                if (dnsttPort > 0) {
                    successXrayConfigs.add(Pair(dnsttPort, xrayCfg))
                } else {
                    try { dnsttEngine.stop() } catch (_: Exception) {}
                    KighmuLogger.error(TAG, "Flux[${globalIdx+1}] ECHEC definitif flux=$sessionLabel")
                }
                globalIdx++
            }
        }

        KighmuLogger.info(TAG, "=== STEP 2: ${successXrayConfigs.size}/$totalFlux flux dnstt connectes ===")
        if (successXrayConfigs.isEmpty()) {
            throw Exception("Aucun flux V2ray+DNS connecte apres tentatives")
        }

        // Demarrer un XrayEngine par flux dnstt reussi
        val xraySocksPorts = mutableListOf<Int>()
        successXrayConfigs.forEachIndexed { idx, (dnsttPort, xrayCfg) ->
            KighmuLogger.info(TAG, "Demarrage XrayEngine[$idx] sur dnsttPort=$dnsttPort")
            val xrayEngine = XrayEngine(xrayCfg, context, dnsttPort, idx, vpnService)
            try {
                val socksPort = xrayEngine.start()
                if (socksPort > 0) {
                    synchronized(xrayEngines) { xrayEngines.add(xrayEngine) }
                    xraySocksPorts.add(socksPort)
                    KighmuLogger.info(TAG, "XrayEngine[$idx] CONNECTE socksPort=$socksPort")
                } else {
                    KighmuLogger.error(TAG, "XrayEngine[$idx] ECHEC port=$socksPort")
                }
            } catch (e: Exception) {
                KighmuLogger.error(TAG, "XrayEngine[$idx] exception: ${e.message}")
            }
        }

        if (xraySocksPorts.isEmpty()) {
            throw Exception("Aucun XrayEngine demarre avec succes")
        }

        // Balancer sur tous les ports SOCKS Xray
        val balancer = SocksBalancer(xraySocksPorts)
        balancer.start()
        socksBalancer = balancer
        activePorts = xraySocksPorts
        val finalPort = SocksBalancer.BALANCER_PORT

        KighmuLogger.info(TAG, "=== V2ray+DNS pret - balancer=$finalPort, ${xraySocksPorts.size} tunnel(s) actif(s) ===")
        return finalPort
    }

    override fun startTun2Socks(fd: Int) {
        HevTun2Socks.init()
        val svc = vpnService ?: run {
            KighmuLogger.error(TAG, "VpnService null - impossible de demarrer HevTun2Socks")
            xrayEngines.firstOrNull()?.startTun2Socks(fd)
            return
        }
        if (HevTun2Socks.isAvailable) {
            val targetPort = if (activePorts.size > 1) SocksBalancer.BALANCER_PORT
                             else activePorts.firstOrNull() ?: return
            KighmuLogger.info(TAG, "hev V2ray+DNS -> port=$targetPort (${activePorts.size} tunnel(s))")
            HevTun2Socks.start(context, fd, targetPort, svc)
        } else {
            xrayEngines.firstOrNull()?.startTun2Socks(fd)
        }
    }

    override suspend fun stop() {
        KighmuLogger.info(TAG, "Arret MultiXraySlowDnsEngine...")
        try { HevTun2Socks.stop() } catch (_: Exception) {}
        try { socksBalancer?.stop(); socksBalancer = null } catch (_: Exception) {}
        synchronized(xrayEngines) {
            xrayEngines.forEach { try { runBlocking { it.stop() } } catch (_: Exception) {} }
            xrayEngines.clear()
        }
        synchronized(dnsttEngines) {
            dnsttEngines.forEach { try { runBlocking { it.stop() } } catch (_: Exception) {} }
            dnsttEngines.clear()
        }
        scope.cancel()
        KighmuLogger.info(TAG, "MultiXraySlowDnsEngine arrete")
    }

    override suspend fun sendData(data: ByteArray, length: Int) =
        xrayEngines.firstOrNull { it.isRunning() }?.sendData(data, length) ?: Unit

    override suspend fun receiveData(): ByteArray? =
        xrayEngines.firstOrNull { it.isRunning() }?.receiveData()

    override fun isRunning() = xrayEngines.any { it.isRunning() }
}
