package com.kighmu.vpn.engines

import android.content.Context
import com.kighmu.vpn.models.KighmuConfig
import com.kighmu.vpn.utils.KighmuLogger
import kotlinx.coroutines.*
import java.io.*
import java.net.*
import java.util.concurrent.LinkedBlockingQueue

/**
 * HTTP Proxy + Custom Payload Engine
 *
 * Establishes a TCP tunnel through an HTTP proxy using a custom HTTP payload
 * (e.g. WebSocket upgrade, CONNECT method with custom headers).
 *
 * Flow:
 *   App → Local SOCKS5 (10801) → HTTP Payload → Proxy Host:Port → Internet
 */
class HttpProxyEngine(
    private val config: KighmuConfig,
    private val context: Context
) : TunnelEngine {

    companion object {
        const val TAG = "HttpProxyEngine"
        const val LOCAL_SOCKS_PORT = 10801
    }

    private var running = false
    private val engineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val sendQueue = LinkedBlockingQueue<ByteArray>(1000)
    private val receiveQueue = LinkedBlockingQueue<ByteArray>(1000)

    private val proxyConfig get() = config.httpProxy

    override suspend fun start(): Int {
        KighmuLogger.info(TAG, "Starting HTTP Proxy engine: ${proxyConfig.proxyHost}:${proxyConfig.proxyPort}")
        running = true
        startLocalSocksProxy()
        return LOCAL_SOCKS_PORT
    }

    override suspend fun stop() {
        running = false
        engineScope.cancel()
        KighmuLogger.info(TAG, "HTTP Proxy engine stopped")
    }

    override suspend fun sendData(data: ByteArray, length: Int) {
        if (running) sendQueue.offer(data.copyOf(length))
    }

    override suspend fun receiveData(): ByteArray? =
        if (running) receiveQueue.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS)
        else null

    override fun isRunning() = running

    // ─── SOCKS5 Proxy ─────────────────────────────────────────────────────────

    private fun startLocalSocksProxy() {
        engineScope.launch {
            val server = ServerSocket(LOCAL_SOCKS_PORT)
            KighmuLogger.info(TAG, "HTTP Proxy SOCKS5 on port $LOCAL_SOCKS_PORT")
            while (running) {
                try {
                    val client = server.accept()
                    launch { handleSocksClient(client) }
                } catch (e: Exception) {
                    if (running) KighmuLogger.error(TAG, "Accept error: ${e.message}")
                }
            }
            server.close()
        }
    }

    private suspend fun handleSocksClient(clientSocket: Socket) = withContext(Dispatchers.IO) {
        try {
            val inp = DataInputStream(clientSocket.getInputStream())
            val out = DataOutputStream(clientSocket.getOutputStream())

            // SOCKS5 negotiation
            if (inp.read() != 5) { clientSocket.close(); return@withContext }
            val nm = inp.read()
            inp.skipBytes(nm)
            out.write(byteArrayOf(5, 0)); out.flush()

            // Request
            inp.read() // ver
            inp.read() // cmd
            inp.read() // rsv
            val atyp = inp.read()

            val destHost = when (atyp) {
                1 -> { val b = ByteArray(4); inp.readFully(b); InetAddress.getByAddress(b).hostAddress!! }
                3 -> { val l = inp.read(); val b = ByteArray(l); inp.readFully(b); String(b) }
                4 -> { val b = ByteArray(16); inp.readFully(b); InetAddress.getByAddress(b).hostAddress!! }
                else -> { clientSocket.close(); return@withContext }
            }
            val destPort = (inp.read() shl 8) or inp.read()

            // Reply success
            out.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0)); out.flush()

            // Connect to proxy with custom payload
            val proxySocket = connectWithPayload(destHost, destPort)
            if (proxySocket == null) {
                clientSocket.close()
                return@withContext
            }

            KighmuLogger.info(TAG, "HTTP tunnel: $destHost:$destPort")
            relayTraffic(clientSocket, proxySocket)

        } catch (e: Exception) {
            KighmuLogger.error(TAG, "SOCKS handler error: ${e.message}")
        } finally {
            clientSocket.close()
        }
    }

    // ─── Connect via Payload ──────────────────────────────────────────────────

    private fun connectWithPayload(destHost: String, destPort: Int): Socket? {
        return try {
            val socket = Socket()
            socket.soTimeout = 15000
            socket.connect(
                InetSocketAddress(proxyConfig.proxyHost, proxyConfig.proxyPort),
                10000
            )

            val payload = buildPayload(destHost, destPort)
            socket.getOutputStream().write(payload.toByteArray(Charsets.UTF_8))
            socket.getOutputStream().flush()

            // Read proxy response
            val response = readHttpResponse(socket.getInputStream())
            KighmuLogger.info(TAG, "Proxy response: ${response.take(100)}")

            if (response.contains("200") || response.contains("101") || response.lowercase().contains("upgrade")) {
                socket
            } else {
                KighmuLogger.error(TAG, "Proxy rejected: $response")
                socket.close()
                null
            }
        } catch (e: Exception) {
            KighmuLogger.error(TAG, "Connection failed: ${e.message}")
            null
        }
    }

    private fun buildPayload(destHost: String, destPort: Int): String {
        var payload = proxyConfig.customPayload

        // Replace placeholders
        payload = payload
            .replace("[host]", destHost)
            .replace("[port]", destPort.toString())
            .replace("[crlf]", "\r\n")
            .replace("[cr]", "\r")
            .replace("[lf]", "\n")
            .replace("\\r\\n", "\r\n")

        // Add custom headers
        if (proxyConfig.customHeaders.isNotEmpty()) {
            val headerStr = proxyConfig.customHeaders.entries.joinToString("\r\n") { "${it.key}: ${it.value}" }
            if (payload.contains("\r\n\r\n")) {
                payload = payload.replace("\r\n\r\n", "\r\n$headerStr\r\n\r\n")
            }
        }

        return payload
    }

    private fun readHttpResponse(inputStream: InputStream): String {
        val sb = StringBuilder()
        var prev = -1
        var cur: Int
        while (true) {
            cur = inputStream.read()
            if (cur == -1) break
            sb.append(cur.toChar())
            // Detect end of HTTP headers
            if (prev == '\n'.code && cur == '\n'.code) break
            if (sb.endsWith("\r\n\r\n")) break
            prev = cur
        }
        return sb.toString()
    }

    // ─── Bidirectional Relay ──────────────────────────────────────────────────

    private suspend fun relayTraffic(client: Socket, server: Socket) = coroutineScope {
        val buf = ByteArray(32768)

        val c2s = launch(Dispatchers.IO) {
            try {
                val ci = client.getInputStream()
                val so = server.getOutputStream()
                while (running) {
                    val len = ci.read(buf)
                    if (len <= 0) break
                    so.write(buf, 0, len)
                    so.flush()
                }
            } catch (_: Exception) {}
        }

        val s2c = launch(Dispatchers.IO) {
            try {
                val si = server.getInputStream()
                val co = client.getOutputStream()
                while (running) {
                    val len = si.read(buf)
                    if (len <= 0) break
                    co.write(buf, 0, len)
                    co.flush()
                }
            } catch (_: Exception) {}
        }

        c2s.join()
        s2c.cancel()
        client.close()
        server.close()
    }
}
