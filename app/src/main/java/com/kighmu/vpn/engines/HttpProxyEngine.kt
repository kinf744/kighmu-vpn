package com.kighmu.vpn.engines

import android.content.Context
import com.trilead.ssh2.Connection
import com.kighmu.vpn.models.KighmuConfig
import com.kighmu.vpn.utils.KighmuLogger
import kotlinx.coroutines.*
import java.net.InetSocketAddress
import java.net.Socket

class HttpProxyEngine(
    private val config: KighmuConfig,
    private val context: Context
) : TunnelEngine {

    companion object {
        const val TAG = "HttpProxyEngine"
        const val LOCAL_SOCKS_PORT = 10801
        val CRLF = "\r\n"
    }

    private var running = false
    private var sshConnection: Connection? = null
    private var proxySocket: Socket? = null
    private val engineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val proxy get() = config.httpProxy
    private val ssh get() = config.sshCredentials

    override suspend fun start(): Int = withContext(Dispatchers.IO) {
        running = true
        KighmuLogger.info(TAG, "=== Demarrage SSH HTTP Proxy ===")
        KighmuLogger.info(TAG, "Proxy: ${proxy.proxyHost}:${proxy.proxyPort}")
        KighmuLogger.info(TAG, "SSH: ${ssh.host}:${ssh.port} user=${ssh.username}")

        if (proxy.proxyHost.isBlank()) throw Exception("Proxy Host manquant")
        if (ssh.host.isBlank()) throw Exception("SSH Host manquant")
        if (ssh.username.isBlank()) throw Exception("SSH Username manquant")

        try {
            KighmuLogger.info(TAG, "Connexion au proxy ${proxy.proxyHost}:${proxy.proxyPort}...")
            val sock = Socket()
            sock.connect(InetSocketAddress(proxy.proxyHost, proxy.proxyPort), 10000)
            proxySocket = sock
            KighmuLogger.info(TAG, "Proxy TCP connecte")

            val out = sock.getOutputStream()
            val inp = sock.getInputStream()

            val req = if (proxy.customPayload.isNotBlank()) {
                proxy.customPayload
                    .replace("[host]", ssh.host).replace("[HOST]", ssh.host)
                    .replace("[port]", ssh.port.toString()).replace("[PORT]", ssh.port.toString())
                    .replace("\\r\\n", CRLF).replace("\\n", CRLF)
            } else {
                "CONNECT ${ssh.host}:${ssh.port} HTTP/1.1${CRLF}Host: ${ssh.host}:${ssh.port}${CRLF}${CRLF}"
            }

            KighmuLogger.info(TAG, "Envoi CONNECT request...")
            out.write(req.toByteArray())
            out.flush()

            val resp = StringBuilder()
            var last4 = ""
            while (true) {
                val curr = inp.read(); if (curr == -1) break
                resp.append(curr.toChar())
                last4 = (last4 + curr.toChar()).takeLast(4)
                if (last4 == "\r\n\r\n") break
                // Aussi accepter 


                if (last4.endsWith("\n\n")) break
            }
            val respStr = resp.toString()
            KighmuLogger.info(TAG, "Reponse proxy: ${respStr.take(80)}")
            if (!respStr.contains("200")) throw Exception("Proxy refuse: ${respStr.take(80)}")

            KighmuLogger.info(TAG, "Tunnel HTTP etabli, demarrage SSH trilead...")

            // Pont local: trilead -> ServerSocket local -> socket proxy
            val bridge = java.net.ServerSocket(0)
            val bridgePort = bridge.localPort
            Thread {
                try {
                    val client = bridge.accept()
                    bridge.close()
                    val proxyIn = sock.getInputStream()
                    val proxyOut = sock.getOutputStream()
                    val clientIn = client.getInputStream()
                    val clientOut = client.getOutputStream()
                    // Relay bidirectionnel
                    Thread {
                        try { proxyIn.copyTo(clientOut) } catch (_: Exception) {}
                        try { client.close() } catch (_: Exception) {}
                    }.start()
                    try { clientIn.copyTo(proxyOut) } catch (_: Exception) {}
                } catch (e: Exception) {
                    KighmuLogger.error(TAG, "bridge error: ${e.message}")
                }
            }.start()
            val conn = Connection("127.0.0.1", bridgePort)
            conn.connect(null, 15000, 15000)

            val authenticated = conn.authenticateWithPassword(ssh.username, ssh.password)
            if (!authenticated) throw Exception("SSH auth echoue pour ${ssh.username}")

            conn.createLocalPortForwarder(LOCAL_SOCKS_PORT, "127.0.0.1", LOCAL_SOCKS_PORT)
            sshConnection = conn
            KighmuLogger.info(TAG, "=== HTTP Proxy Tunnel ACTIF sur port $LOCAL_SOCKS_PORT ===")
            LOCAL_SOCKS_PORT
        } catch (e: Exception) {
            KighmuLogger.error(TAG, "ECHEC: ${e.javaClass.simpleName}: ${e.message}")
            throw e
        }
    }

    override suspend fun stop() {
        running = false
        try { sshConnection?.close() } catch (_: Exception) {}
        try { proxySocket?.close() } catch (_: Exception) {}
        engineScope.cancel()
        KighmuLogger.info(TAG, "HttpProxy arrete")
    }

    override suspend fun sendData(data: ByteArray, length: Int) {}
    override suspend fun receiveData(): ByteArray? = null
    override fun isRunning() = running && sshConnection?.isAuthenticationComplete == true
}
