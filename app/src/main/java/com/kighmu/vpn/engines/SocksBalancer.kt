package com.kighmu.vpn.engines

import com.kighmu.vpn.utils.KighmuLogger
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/**
 * SOCKS5 Load Balancer local
 * Ecoute sur un port local et distribue les connexions en round-robin
 * sur tous les ports SOCKS actifs (10800, 10801, 10802...)
 */
class SocksBalancer(private val ports: List<Int>) {

    companion object {
        const val TAG = "SocksBalancer"
        const val BALANCER_PORT = 10900
    }

    private var serverSocket: ServerSocket? = null
    private var running = false
    private val counter = AtomicInteger(0)

    fun start() {
        running = true
        serverSocket = ServerSocket(BALANCER_PORT)
        KighmuLogger.info(TAG, "Balancer demarre sur port $BALANCER_PORT -> ports: $ports")

        Thread {
            while (running) {
                try {
                    val client = serverSocket?.accept() ?: break
                    val targetPort = nextPort()
                    KighmuLogger.info(TAG, "Nouvelle connexion -> SOCKS:$targetPort")
                    Thread { relay(client, targetPort) }.start()
                } catch (e: Exception) {
                    if (running) KighmuLogger.error(TAG, "Accept error: ${e.message}")
                }
            }
        }.start()
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        KighmuLogger.info(TAG, "Balancer arrete")
    }

    private fun nextPort(): Int {
        val idx = counter.getAndIncrement() % ports.size
        return ports[idx]
    }

    private fun relay(client: Socket, targetPort: Int) {
        try {
            client.soTimeout = 30000
            val server = Socket()
            server.connect(InetSocketAddress("127.0.0.1", targetPort), 5000)
            server.soTimeout = 30000

            val clientIn = client.getInputStream()
            val clientOut = client.getOutputStream()
            val serverIn = server.getInputStream()
            val serverOut = server.getOutputStream()

            // client -> socks server
            Thread {
                try {
                    pipe(clientIn, serverOut)
                } catch (_: Exception) {}
                try { server.close() } catch (_: Exception) {}
            }.start()

            // socks server -> client
            try {
                pipe(serverIn, clientOut)
            } catch (_: Exception) {}
            try { client.close() } catch (_: Exception) {}
            try { server.close() } catch (_: Exception) {}

        } catch (e: Exception) {
            KighmuLogger.error(TAG, "Relay error port $targetPort: ${e.message}")
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun pipe(inp: InputStream, out: OutputStream) {
        val buf = ByteArray(8192)
        var n: Int
        while (true) {
            n = inp.read(buf)
            if (n == -1) break
            out.write(buf, 0, n)
            out.flush()
        }
    }
}
