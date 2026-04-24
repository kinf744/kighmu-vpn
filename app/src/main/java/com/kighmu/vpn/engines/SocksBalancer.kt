package com.kighmu.vpn.engines

import com.kighmu.vpn.utils.KighmuLogger
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.Executors

/**
 * SOCKS5 Load Balancer local
 * Ecoute sur un port local et distribue les connexions en round-robin
 * sur tous les ports SOCKS actifs (10800, 10801, 10802...)
 */
class SocksBalancer(initialPorts: List<Int>, private val vpnService: android.net.VpnService? = null) {

    companion object {
        const val TAG = "SocksBalancer"
        var BALANCER_PORT = 10900
    }

    private var serverSocket: ServerSocket? = null
    private var running = false
    private val counter = AtomicInteger(0)
    @Volatile private var activePorts: List<Int> = initialPorts
    private val threadPool = Executors.newFixedThreadPool(40)
    private val totalConnections = AtomicInteger(0)
    private val successConnections = AtomicInteger(0)
    private val failedConnections = AtomicInteger(0)
    private val totalBytesTransferred = java.util.concurrent.atomic.AtomicLong(0)
    fun getBytesTransferred(): Long = totalBytesTransferred.get()
    fun resetBytesTransferred() = totalBytesTransferred.set(0)

    fun start() {
        running = true
        val ss = ServerSocket(0)
        BALANCER_PORT = ss.localPort
        serverSocket = ss
        KighmuLogger.info(TAG, "Balancer demarre sur port $BALANCER_PORT -> ports: $activePorts")

        Thread {
            while (running) {
                try {
                    val client = serverSocket?.accept() ?: break
                    val total = totalConnections.incrementAndGet()
                    val targetPort = nextPort()
                    threadPool.execute { relay(client, targetPort) }
                } catch (e: Exception) {
                    if (running) KighmuLogger.error(TAG, "Accept error: ${e.message}")
                }
            }
        }.start()
    }

    fun updatePorts(newPorts: List<Int>) {
        if (newPorts.isNotEmpty()) {
            activePorts = newPorts.toList()
            counter.set(0)
            KighmuLogger.info(TAG, "Balancer ports mis à jour: $activePorts")
        }
    }

    fun stop() {
        running = false
        threadPool.shutdown()
        try { serverSocket?.close() } catch (_: Exception) {}
        KighmuLogger.info(TAG, "Balancer arrete")
    }

    private fun nextPort(): Int {
        val current = activePorts
        if (current.isEmpty()) return 10800
        val idx = counter.getAndIncrement() % current.size
        return current[idx]
    }

    private fun connectToPort(targetPort: Int): Socket {
        val server = Socket()
        try { vpnService?.protect(server) } catch (_: Exception) {}
        server.connect(InetSocketAddress("127.0.0.1", targetPort), 5000)
        return server
    }

    private fun relay(client: Socket, targetPort: Int) {
        try {
            client.soTimeout = 0
            client.setPerformancePreferences(0, 0, 1) // optimiser débit
            client.receiveBufferSize = 65536
            client.sendBufferSize = 65536

            // Essayer targetPort, puis fallback sur les autres ports actifs
            var server: Socket? = null
            val ports = activePorts.toList()
            val candidates = listOf(targetPort) + ports.filter { it != targetPort }
            for (port in candidates) {
                try {
                    server = connectToPort(port)
                    if (port != targetPort) {
                        KighmuLogger.warning(TAG, "Fallback port $targetPort → $port ⚠️")
                    }
                    break
                } catch (_: Exception) {}
            }
            if (server == null) {
                failedConnections.incrementAndGet()
                KighmuLogger.error(TAG, "Tous les ports SOCKS inaccessibles ❌ ports=$ports total_fail=${failedConnections.get()}")
                try { client.close() } catch (_: Exception) {}
                return
            }
            successConnections.incrementAndGet()
            val s = server!!
            s.soTimeout = 0
            s.receiveBufferSize = 65536
            s.sendBufferSize = 65536
            s.setPerformancePreferences(0, 0, 1)

            val clientIn = client.getInputStream()
            val clientOut = client.getOutputStream()
            val serverIn = s.getInputStream()
            val serverOut = s.getOutputStream()

            threadPool.execute { try { pipe(clientIn, serverOut) } catch (_: Exception) {} }
            try { pipe(serverIn, clientOut) } catch (_: Exception) {}
            try { client.close() } catch (_: Exception) {}
            try { s.close() } catch (_: Exception) {}

        } catch (e: Exception) {
            // Ne pas logger les erreurs de connexion refusée (trop verbeux et "sale")
            val msg = e.message ?: ""
            if (!msg.contains("ECONNREFUSED") && !msg.contains("Connection refused") &&
                !msg.contains("failed to connect") && !msg.contains("isConnected failed")) {
                KighmuLogger.error(TAG, "Relay error port $targetPort: $msg")
            }
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun pipe(inp: InputStream, out: OutputStream) {
        // Buffer agrandi (128KB) pour maximiser le débit streaming
        val buf = ByteArray(131072)
        var n: Int
        try {
            while (true) {
                n = inp.read(buf)
                if (n == -1) break
                out.write(buf, 0, n)
                totalBytesTransferred.addAndGet(n.toLong())
                // flush intelligent: seulement si le flux semble se calmer
                if (inp.available() == 0) out.flush()
            }
        } catch (_: Exception) {}
    }
}
