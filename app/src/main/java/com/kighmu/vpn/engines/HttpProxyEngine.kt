package com.kighmu.vpn.engines

import android.content.Context
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.kighmu.vpn.models.KighmuConfig
import com.kighmu.vpn.utils.KighmuLogger
import kotlinx.coroutines.*
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

class HttpProxyEngine(
    private val config: KighmuConfig,
    private val context: Context
) : TunnelEngine {

    companion object {
        const val TAG = "HttpProxyEngine"
        const val LOCAL_SOCKS_PORT = 10801
    }

    private var running = false
    private var jschSession: Session? = null
    private var proxySocket: Socket? = null
    private val engineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val proxy get() = config.httpProxy
    private val ssh get() = config.sshCredentials

    override suspend fun start(): Int = withContext(Dispatchers.IO) {
        running = true
        KighmuLogger.info(TAG, "=== Demarrage SSH HTTP Proxy ===")
        KighmuLogger.info(TAG, "Proxy: ${proxy.proxyHost}:${proxy.proxyPort}")
        KighmuLogger.info(TAG, "SSH: ${ssh.host}:${ssh.port} user=${ssh.username}")
        KighmuLogger.info(TAG, "Payload: ${if (proxy.customPayload.isNotBlank()) proxy.customPayload.take(60) else "defaut CONNECT"}")

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
            val crlf = "
"

            val req = if (proxy.customPayload.isNotBlank()) {
                proxy.customPayload
                    .replace("[host]", ssh.host).replace("[HOST]", ssh.host)
                    .replace("[port]", ssh.port.toString()).replace("[PORT]", ssh.port.toString())
                    .replace("\r\n", crlf).replace("\n", crlf)
            } else {
                "CONNECT ${ssh.host}:${ssh.port} HTTP/1.1${crlf}Host: ${ssh.host}:${ssh.port}${crlf}${crlf}"
            }

            KighmuLogger.info(TAG, "Envoi CONNECT request...")
            out.write(req.toByteArray())
            out.flush()

            val resp = StringBuilder()
            var prev = 0; var curr: Int
            while (true) {
                curr = inp.read(); if (curr == -1) break
                resp.append(curr.toChar())
                if (prev == '
'.code && curr == '
'.code) break
                prev = curr
            }
            val respStr = resp.toString()
            KighmuLogger.info(TAG, "Reponse proxy: ${respStr.take(80)}")
            if (!respStr.contains("200")) throw Exception("Proxy refuse: ${respStr.take(80)}")

            KighmuLogger.info(TAG, "Tunnel HTTP etabli, demarrage SSH...")
            val jsch = JSch()
            if (ssh.usePrivateKey && ssh.privateKey.isNotEmpty()) {
                jsch.addIdentity("key", ssh.privateKey.toByteArray(), null, null)
            }
            val session = jsch.getSession(ssh.username, ssh.host, ssh.port)
            session.setPassword(ssh.password)
            session.setConfig("StrictHostKeyChecking", "no")
            session.setConfig("PreferredAuthentications", "publickey,password")
            session.setProxy(object : com.jcraft.jsch.Proxy {
                override fun connect(sf: com.jcraft.jsch.SocketFactory?, h: String?, p: Int, t: Int) {}
                override fun getInputStream(): InputStream = sock.getInputStream()
                override fun getOutputStream(): OutputStream = sock.getOutputStream()
                override fun getSocket(): Socket = sock
                override fun close() { sock.close() }
            })
            session.connect(15000)
            jschSession = session
            KighmuLogger.info(TAG, "SSH connecte! Version: ${session.serverVersion}")
            session.setPortForwardingL(LOCAL_SOCKS_PORT, "127.0.0.1", LOCAL_SOCKS_PORT)
            KighmuLogger.info(TAG, "=== HTTP Proxy Tunnel ACTIF sur port $LOCAL_SOCKS_PORT ===")
            LOCAL_SOCKS_PORT
        } catch (e: Exception) {
            KighmuLogger.error(TAG, "ECHEC: ${e.javaClass.simpleName}: ${e.message}")
            throw e
        }
    }

    override suspend fun stop() {
        running = false
        jschSession?.disconnect()
        proxySocket?.close()
        engineScope.cancel()
        KighmuLogger.info(TAG, "HttpProxy arrete")
    }

    override suspend fun sendData(data: ByteArray, length: Int) {}
    override suspend fun receiveData(): ByteArray? = null
    override fun isRunning() = running && jschSession?.isConnected == true
}