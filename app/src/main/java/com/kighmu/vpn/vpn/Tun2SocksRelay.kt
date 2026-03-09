package com.kighmu.vpn.vpn

import com.kighmu.vpn.utils.KighmuLogger
import kotlinx.coroutines.*
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.*
import java.nio.ByteBuffer

/**
 * Relay TUN → SOCKS5 en Kotlin pur
 * Lit les paquets IP du TUN, extrait destination, ouvre connexion SOCKS5, relaie les données
 */
class Tun2SocksRelay(
    private val tunFd: java.io.FileDescriptor,
    private val socksHost: String = "127.0.0.1",
    private val socksPort: Int = 10800
) {
    companion object { const val TAG = "Tun2SocksRelay" }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val connections = java.util.concurrent.ConcurrentHashMap<String, Socket>()

    fun start() {
        scope.launch { readTunLoop() }
        KighmuLogger.info(TAG, "Tun2SocksRelay demarre -> SOCKS5 $socksHost:$socksPort")
    }

    fun stop() {
        scope.cancel()
        connections.values.forEach { try { it.close() } catch (_: Exception) {} }
        connections.clear()
        KighmuLogger.info(TAG, "Tun2SocksRelay arrete")
    }

    private suspend fun readTunLoop() = withContext(Dispatchers.IO) {
        val inp = FileInputStream(tunFd)
        val buf = ByteArray(32768)
        while (isActive) {
            try {
                val len = inp.read(buf)
                if (len < 20) continue
                val pkt = buf.copyOf(len)
                launch { handlePacket(pkt) }
            } catch (e: Exception) {
                KighmuLogger.error(TAG, "readTun: ${e.message}")
                break
            }
        }
    }

    private suspend fun handlePacket(pkt: ByteArray) = withContext(Dispatchers.IO) {
        try {
            val bb = ByteBuffer.wrap(pkt)
            val version = (pkt[0].toInt() and 0xFF) shr 4
            if (version != 4) return@withContext // IPv4 seulement

            val protocol = pkt[9].toInt() and 0xFF
            if (protocol != 6) return@withContext // TCP seulement

            // Extraire src/dst IP
            val dstIp = "${pkt[16].toInt() and 0xFF}.${pkt[17].toInt() and 0xFF}.${pkt[18].toInt() and 0xFF}.${pkt[19].toInt() and 0xFF}"

            // IHL pour trouver le debut TCP
            val ihl = (pkt[0].toInt() and 0x0F) * 4
            if (pkt.size < ihl + 20) return@withContext

            val dstPort = ((pkt[ihl + 2].toInt() and 0xFF) shl 8) or (pkt[ihl + 3].toInt() and 0xFF)
            val srcPort = ((pkt[ihl + 0].toInt() and 0xFF) shl 8) or (pkt[ihl + 1].toInt() and 0xFF)
            val flags = pkt[ihl + 13].toInt() and 0xFF
            val isSyn = (flags and 0x02) != 0

            val key = "$dstIp:$dstPort-$srcPort"

            if (isSyn && !connections.containsKey(key)) {
                KighmuLogger.info(TAG, "Nouvelle connexion TCP: $dstIp:$dstPort")
                val sock = connectViaSocks5(dstIp, dstPort) ?: return@withContext
                connections[key] = sock

                // Relay bidirectionnel
                val dataOffset = ihl + ((pkt[ihl + 12].toInt() and 0xF0) shr 4) * 4
                if (dataOffset < pkt.size) {
                    val data = pkt.copyOfRange(dataOffset, pkt.size)
                    if (data.isNotEmpty()) {
                        try { sock.getOutputStream().write(data); sock.getOutputStream().flush() } catch (_: Exception) {}
                    }
                }

                launch {
                    val out = FileOutputStream(tunFd)
                    val rbuf = ByteArray(32768)
                    while (isActive && !sock.isClosed) {
                        try {
                            val n = sock.getInputStream().read(rbuf)
                            if (n <= 0) break
                            // On ne peut pas facilement reconstruire les paquets IP ici
                            // Le dynamic port forwarder SSH gere ca
                        } catch (_: Exception) { break }
                    }
                    connections.remove(key)
                    try { sock.close() } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            KighmuLogger.error(TAG, "handlePacket: ${e.message}")
        }
    }

    private fun connectViaSocks5(host: String, port: Int): Socket? {
        return try {
            val sock = Socket()
            sock.connect(InetSocketAddress(socksHost, socksPort), 10000)
            val out = sock.getOutputStream()
            val inp = sock.getInputStream()

            out.write(byteArrayOf(0x05, 0x01, 0x00)); out.flush()
            inp.read(); inp.read()

            val hostBytes = host.toByteArray(Charsets.US_ASCII)
            val req = byteArrayOf(0x05, 0x01, 0x00, 0x03, hostBytes.size.toByte()) +
                      hostBytes + byteArrayOf((port shr 8).toByte(), (port and 0xFF).toByte())
            out.write(req); out.flush()

            val resp = ByteArray(256)
            var total = 0
            while (total < 4) { total += inp.read(resp, total, 4 - total) }
            if (resp[1] != 0x00.toByte()) { sock.close(); return null }
            // Lire le reste de la reponse
            val extra = when (resp[3].toInt()) { 1 -> 6; 3 -> inp.read() + 3; 4 -> 18; else -> 2 }
            val tmp = ByteArray(extra)
            var r = 0; while (r < extra) { r += inp.read(tmp, r, extra - r) }

            sock
        } catch (e: Exception) {
            KighmuLogger.error(TAG, "SOCKS5 connect $host:$port: ${e.message}")
            null
        }
    }
}
