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
        var BALANCER_PORT = 10900
    }

    private var serverSocket: ServerSocket? = null
    private var running = false
    private val counter = AtomicInteger(0)

    fun start() {
        running = true
        val ss = ServerSocket(0)
        BALANCER_PORT = ss.localPort
        serverSocket = ss
        KighmuLogger.info(TAG, "Balancer demarre sur port $BALANCER_PORT -> ports: $ports")

        Thread {
            while (running) {
                try {
                    val client = serverSocket?.accept() ?: break
                    val targetPort = nextPort()
                    Thread { relay(client, targetPort) }.start()
                } catch (e: Exception) {
                    if (running) KighmuLogger.error(TAG, "Accept error: ${e.message}")
                }
            }
        }.start()
    }

    fun updatePorts(newPorts: List<Int>) {
        if (newPorts.isNotEmpty()) {
            KighmuLogger.info(TAG, "Balancer ports mis à jour: $newPorts")
        }
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
            client.setPerformancePreferences(0, 0, 1) // optimiser débit
            client.receiveBufferSize = 65536
            client.sendBufferSize = 65536
            val server = Socket()
            server.connect(InetSocketAddress("127.0.0.1", targetPort), 5000)
            server.soTimeout = 30000
            server.receiveBufferSize = 65536
            server.sendBufferSize = 65536
            server.setPerformancePreferences(0, 0, 1)

            val clientIn = client.getInputStream()
            val clientOut = client.getOutputStream()
            val serverIn = server.getInputStream()
            val serverOut = server.getOutputStream()

            if (NativeRelay.isAvailable) {
                try {
                    val cfd = android.system.Os.dup(client.fileDescriptor)
                    val sfd = android.system.Os.dup(server.fileDescriptor)
                    NativeRelay.relay(
                        cfd.fd,
                        sfd.fd
                    )
                } catch (_: Exception) {
                    Thread { try { pipe(clientIn, serverOut) } catch (_: Exception) {} }.start()
                    try { pipe(serverIn, clientOut) } catch (_: Exception) {}
                }
            } else {
                Thread { try { pipe(clientIn, serverOut) } catch (_: Exception) {} }.start()
                try { pipe(serverIn, clientOut) } catch (_: Exception) {}
            }
            try { client.close() } catch (_: Exception) {}
            try { server.close() } catch (_: Exception) {}

        } catch (e: Exception) {
            KighmuLogger.error(TAG, "Relay error port $targetPort: ${e.message}")
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun pipe(inp: InputStream, out: OutputStream) {
        val buf = ByteArray(65536) // 64KB buffer pour meilleur débit
        var n: Int
        try {
            while (true) {
                n = inp.read(buf)
                if (n == -1) break
                out.write(buf, 0, n)
                // flush seulement si pas de données en attente
                if (inp.available() == 0) out.flush()
            }
        } catch (_: Exception) {}
    }
}
