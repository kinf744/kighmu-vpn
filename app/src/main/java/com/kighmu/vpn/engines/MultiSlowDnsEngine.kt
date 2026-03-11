package com.kighmu.vpn.engines

import android.content.Context
import android.net.VpnService
import com.kighmu.vpn.models.KighmuConfig
import com.kighmu.vpn.models.SshCredentials
import com.kighmu.vpn.models.SlowDnsConfig
import com.kighmu.vpn.profiles.ProfileRepository
import com.kighmu.vpn.profiles.SlowDnsProfile
import com.kighmu.vpn.utils.KighmuLogger
import kotlinx.coroutines.*

class MultiSlowDnsEngine(
    private val baseConfig: KighmuConfig,
    private val context: Context,
    private val vpnService: VpnService? = null
) : TunnelEngine {

    companion object { const val TAG = "MultiSlowDnsEngine" }

    private val engines = mutableListOf<SlowDnsEngine>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var activePort = 10800

    override suspend fun start(): Int {
        val repo = ProfileRepository(context)
        val selected = repo.getSelected()

        if (selected.isEmpty()) {
            KighmuLogger.info(TAG, "Aucun profil sélectionné, démarrage avec config par défaut")
            val engine = SlowDnsEngine(baseConfig, context, vpnService, 0)
            engines.add(engine)
            return engine.start()
        }

        KighmuLogger.info(TAG, "=== Démarrage ${selected.size} session(s) SlowDNS ===")

        // Lancer chaque profil dans sa propre coroutine
        val jobs = selected.mapIndexed { idx, profile ->
            scope.async {
                val config = buildConfig(profile)
                val engine = SlowDnsEngine(config, context, if (idx == 0) vpnService else null, idx)
                engines.add(engine)
                val port = try {
                    engine.start()
                } catch (e: Exception) {
                    KighmuLogger.error(TAG, "Profil[${idx+1}] ${profile.profileName} FAILED: ${e.message}")
                    -1
                }
                KighmuLogger.info(TAG, "Profil[${idx+1}] ${profile.profileName} → port=$port")
                Pair(idx, port)
            }
        }

        // Attendre tous les résultats
        val results = jobs.map { it.await() }
        val successPorts = results.filter { it.second > 0 }.map { it.second }

        KighmuLogger.info(TAG, "${successPorts.size}/${selected.size} sessions connectées: $successPorts")

        if (successPorts.isEmpty()) throw Exception("Aucune session SlowDNS n'a pu se connecter")

        activePort = successPorts.first()
        return activePort
    }

    override fun stop() {
        engines.forEach { 
            try { it.stop() } catch (_: Exception) {}
        }
        engines.clear()
        scope.cancel()
        KighmuLogger.info(TAG, "Toutes les sessions arrêtées")
    }

    private fun buildConfig(p: SlowDnsProfile): KighmuConfig {
        return baseConfig.copy(
            sshCredentials = baseConfig.sshCredentials.copy(
                host = p.sshHost,
                port = p.sshPort,
                username = p.sshUser,
                password = p.sshPass
            ),
            slowDns = baseConfig.slowDns.copy(
                dnsServer = p.dnsServer,
                nameserver = p.nameserver,
                publicKey = p.publicKey
            )
        )
    }
}
