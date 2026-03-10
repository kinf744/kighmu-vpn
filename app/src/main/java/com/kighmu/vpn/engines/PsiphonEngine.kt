package com.kighmu.vpn.engines

import android.content.Context
import ca.psiphon.PsiphonTunnel
import com.kighmu.vpn.models.KighmuConfig
import com.kighmu.vpn.utils.KighmuLogger
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File

class PsiphonEngine(
    private val config: KighmuConfig,
    private val context: Context
) : TunnelEngine, PsiphonTunnel.HostService {

    companion object {
        const val TAG = "PsiphonEngine"
        const val LOCAL_SOCKS_PORT = 10803
    }

    private val engineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var tunnel: PsiphonTunnel? = null
    private var tun2socksProcess: Process? = null
    private var running = false
    private var socksPort = 0
    private val MTU = 1500
    private val readyDeferred = CompletableDeferred<Int>()

    override suspend fun start(): Int = withContext(Dispatchers.IO) {
        KighmuLogger.info(TAG, "=== Demarrage Psiphon (UDP ZIVPN) ===")

        // Lire zifile depuis assets
        val serverEntries = try {
            context.assets.open("zifile").bufferedReader().readText()
        } catch (e: Exception) {
            KighmuLogger.error(TAG, "zifile introuvable: ${e.message}")
            ""
        }

        tunnel = PsiphonTunnel.newPsiphonTunnel(this)
        tunnel!!.startTunneling(serverEntries)

        // Attendre que le tunnel soit pret (SOCKS port disponible)
        withTimeoutOrNull(30000) {
            readyDeferred.await()
        } ?: throw Exception("Psiphon timeout - tunnel non etabli apres 30s")

        KighmuLogger.info(TAG, "Psiphon pret sur port SOCKS $socksPort")
        running = true
        socksPort
    }

    override suspend fun stop() {
        running = false
        try { tunnel?.stopTunneling() } catch (_: Exception) {}
        try { tun2socksProcess?.destroy() } catch (_: Exception) {}
        tunnel = null
        tun2socksProcess = null
        engineScope.cancel()
        KighmuLogger.info(TAG, "Psiphon arrete")
    }

    override fun isRunning() = running
    override suspend fun sendData(data: ByteArray, length: Int) {}
    override suspend fun receiveData(): ByteArray? = null

    override fun startTun2Socks(fd: Int) {
        KighmuLogger.info(TAG, "Demarrage tun2socks pour Psiphon fd=$fd port=$socksPort")
        engineScope.launch(Dispatchers.IO) {
            try {
                val nativeDir = context.applicationInfo.nativeLibraryDir
                val bin = File(nativeDir, "libtun2socks.so")
                if (!bin.exists()) { KighmuLogger.error(TAG, "libtun2socks.so introuvable"); return@launch }
                bin.setExecutable(true)

                val sockPath = "${context.cacheDir.absolutePath}/tun2socks_psiphon.sock"
                File(sockPath).delete()

                val cmd = arrayOf(
                    bin.absolutePath,
                    "--sock-path", sockPath,
                    "--tunmtu", MTU.toString(),
                    "--netif-ipaddr", "10.0.0.1",
                    "--netif-netmask", "255.255.255.0",
                    "--socks-server-addr", "127.0.0.1:$socksPort",
                    "--enable-udprelay",
                    "--loglevel", "4"
                )
                tun2socksProcess = Runtime.getRuntime().exec(cmd)
                Thread {
                    tun2socksProcess?.inputStream?.bufferedReader()?.forEachLine { line ->
                        KighmuLogger.info(TAG, "tun2socks: $line")
                    }
                }.start()

                delay(500)
                val localSocket = android.net.LocalSocket()
                localSocket.connect(android.net.LocalSocketAddress(sockPath, android.net.LocalSocketAddress.Namespace.FILESYSTEM))
                val pfd = android.os.ParcelFileDescriptor.fromFd(fd)
                localSocket.setFileDescriptorsForSend(arrayOf(pfd.fileDescriptor))
                localSocket.outputStream.write(1)
                localSocket.outputStream.flush()
                localSocket.close()
                KighmuLogger.info(TAG, "fd=$fd envoye via sock-path Psiphon OK")
            } catch (e: Exception) {
                KighmuLogger.error(TAG, "startTun2Socks error: ${e.message}")
            }
        }
    }

    // ── PsiphonTunnel.HostService callbacks ──

    override fun getPsiphonConfig(): String {
        return JSONObject().apply {
            put("PropagationChannelId", "FFFFFFFFFFFFFFFF")
            put("SponsorId", "FFFFFFFFFFFFFFFF")
            put("LocalSocksProxyPort", LOCAL_SOCKS_PORT)
            put("LocalHttpProxyPort", 0)
            put("DisableLocalSocksProxy", false)
            put("DisableLocalHTTPProxy", true)
            put("EmitDiagnosticNotices", true)
        }.toString()
    }

    override fun onListeningSocksProxyPort(port: Int) {
        KighmuLogger.info(TAG, "Psiphon SOCKS pret sur port $port")
        socksPort = port
        if (!readyDeferred.isCompleted) readyDeferred.complete(port)
    }

    override fun onConnected() {
        KighmuLogger.info(TAG, "Psiphon connecte!")
        running = true
    }

    override fun onConnecting() { KighmuLogger.info(TAG, "Psiphon connexion en cours...") }
    override fun onDisconnected() { KighmuLogger.info(TAG, "Psiphon deconnecte") }
    override fun onHomepage(url: String) {}
    override fun onClientUpgradeDownloaded(filename: String) {}
    override fun onClientIsLatestVersion() {}
    override fun onSocksProxyPortInUse(port: Int) { KighmuLogger.error(TAG, "Port SOCKS $port deja utilise") }
    override fun onHttpProxyPortInUse(port: Int) {}
    override fun onDiagnosticMessage(message: String) { KighmuLogger.info(TAG, "psiphon: $message") }
    override fun onAvailableEgressRegions(regions: List<String>) {}
    override fun onInternetReachabilityChanged(isReachable: Boolean) {}
    override fun onActiveAuthorizationIDs(authorizations: List<String>) {}
    override fun onExpiringAuthorizationTokens(authorizations: List<String>) {}
    override fun onTrafficRateLimitsChanged(upstreamBytesPerSecond: Long, downstreamBytesPerSecond: Long) {}
    override fun onApplicationParameters(parameters: Any) {}
    override fun onServerAlert(reason: String, subject: String, actionURLs: List<String>) {}
    override fun onInproxyProxyActivity(connectingClients: Int, connectedClients: Int, bytesUp: Long, bytesDown: Long) {}
    override fun getPsiphonEmbeddedServerEntries() = ""
    override fun getPsiphonEmbeddedServerEntriesPath() = ""
    override fun getNetworkID() = "WIFI"
    override fun hasNetworkConnectivity() = true
    override fun bindToDevice(fileDescriptor: Long) = false
    override fun notice(noticeJSON: String) { KighmuLogger.info(TAG, "notice: $noticeJSON") }
    override fun onActiveSpeedBoost(activeSpeedBoost: Boolean) {}
}
