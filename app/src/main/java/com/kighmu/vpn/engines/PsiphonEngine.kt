package com.kighmu.vpn.engines

import android.content.Context
import ca.psiphon.PsiphonTunnel
import com.kighmu.vpn.utils.KighmuLogger
import com.kighmu.vpn.models.KighmuConfig
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
        const val MTU = 1500
    }

    private val engineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var tunnel: PsiphonTunnel? = null
    private var tun2socksProcess: Process? = null
    private var running = false
    private var socksPort = LOCAL_SOCKS_PORT
    private val readyDeferred = CompletableDeferred<Int>()

    override suspend fun start(): Int = withContext(Dispatchers.IO) {
        KighmuLogger.info(TAG, "=== Demarrage Psiphon ===")
        val serverEntries = try {
            context.assets.open("zifile").bufferedReader().readText()
        } catch (e: Exception) {
            KighmuLogger.error(TAG, "zifile introuvable: ${e.message}")
            ""
        }
        tunnel = PsiphonTunnel.newPsiphonTunnel(this@PsiphonEngine)
        tunnel!!.startTunneling(serverEntries)
        withTimeoutOrNull(30000) { readyDeferred.await() }
            ?: throw Exception("Psiphon timeout apres 30s")
        running = true
        KighmuLogger.info(TAG, "Psiphon pret sur SOCKS port $socksPort")
        socksPort
    }

    override suspend fun stop() {
        running = false
        try { tunnel?.stop() } catch (_: Exception) {}
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
        engineScope.launch(Dispatchers.IO) {
            try {
                val bin = File(context.applicationInfo.nativeLibraryDir, "libtun2socks.so")
                if (!bin.exists()) { KighmuLogger.error(TAG, "libtun2socks.so introuvable"); return@launch }
                bin.setExecutable(true)
                val sockPath = "${context.cacheDir.absolutePath}/tun2socks_psiphon.sock"
                File(sockPath).delete()
                val cmd = arrayOf(bin.absolutePath,
                    "--sock-path", sockPath, "--tunmtu", MTU.toString(),
                    "--netif-ipaddr", "10.0.0.1", "--netif-netmask", "255.255.255.0",
                    "--socks-server-addr", "127.0.0.1:$socksPort",
                    "--enable-udprelay", "--loglevel", "4")
                tun2socksProcess = Runtime.getRuntime().exec(cmd)
                delay(500)
                val localSocket = android.net.LocalSocket()
                localSocket.connect(android.net.LocalSocketAddress(sockPath, android.net.LocalSocketAddress.Namespace.FILESYSTEM))
                val pfd = android.os.ParcelFileDescriptor.fromFd(fd)
                localSocket.setFileDescriptorsForSend(arrayOf(pfd.fileDescriptor))
                localSocket.outputStream.write(1)
                localSocket.outputStream.flush()
                localSocket.close()
                KighmuLogger.info(TAG, "fd=$fd envoye SOCKS=$socksPort OK")
            } catch (e: Exception) {
                KighmuLogger.error(TAG, "startTun2Socks: ${e.message}")
            }
        }
    }

    // ── HostService interface ──
    override fun getContext(): Context = context

    override fun getPsiphonConfig(): String = JSONObject().apply {
        put("PropagationChannelId", "FFFFFFFFFFFFFFFF")
        put("SponsorId", "FFFFFFFFFFFFFFFF")
        put("LocalSocksProxyPort", LOCAL_SOCKS_PORT)
        put("LocalHttpProxyPort", 0)
        put("DisableLocalHTTPProxy", true)
        put("EmitDiagnosticNotices", true)
    }.toString()

    override fun onListeningSocksProxyPort(port: Int) {
        KighmuLogger.info(TAG, "SOCKS pret port=$port")
        socksPort = port
        if (!readyDeferred.isCompleted) readyDeferred.complete(port)
    }
    override fun onConnected() { KighmuLogger.info(TAG, "Connecte!"); running = true }
    override fun onConnecting() { KighmuLogger.info(TAG, "Connexion...") }
    override fun onListeningHttpProxyPort(port: Int) {}
    override fun onUpstreamProxyError(message: String) {}
    override fun onStartedWaitingForNetworkConnectivity() {}
    override fun onStoppedWaitingForNetworkConnectivity() {}
    override fun onClientRegion(region: String) {}
    override fun onHomepage(url: String) {}
    override fun onClientUpgradeDownloaded(filename: String) {}
    override fun onClientIsLatestVersion() {}
    override fun onSocksProxyPortInUse(port: Int) {}
    override fun onHttpProxyPortInUse(port: Int) {}
    override fun onDiagnosticMessage(message: String) { KighmuLogger.info(TAG, message) }
    override fun onAvailableEgressRegions(regions: MutableList<String>) {}
    override fun onActiveAuthorizationIDs(authorizations: MutableList<String>) {}
    override fun onApplicationParameters(parameters: Any) {}
    override fun onServerAlert(reason: String, subject: String, actionURLs: MutableList<String>) {}
    override fun onExiting() {}
}
