package com.kighmu.vpn.vpn

import com.kighmu.vpn.utils.KighmuLogger
import kotlinx.coroutines.*
import java.io.FileDescriptor
import java.io.FileInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer

class Tun2SocksRelay(
    private val tunFd: FileDescriptor,
    private val socksHost: String = "127.0.0.1",
    private val socksPort: Int = 10800
) {
    companion object { const val TAG = "Tun2SocksRelay" }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val sessions = java.util.concurrent.ConcurrentHashMap<String, TcpSession>()

    fun start() {
        scope.launch { readTunLoop() }
        KighmuLogger.info(TAG, "Relay demarre -> SOCKS5 $socksHost:$socksPort")
    }

    fun stop() {
        scope.cancel()
        sessions.values.forEach { it.close() }
        sessions.clear()
        KighmuLogger.info(TAG, "Relay arrete")
    }

    private suspend fun readTunLoop() = withContext(Dispatchers.IO) {
        val inp = FileInputStream(tunFd)
        val buf = ByteArray(32768)
        while (isActive) {
            try {
                val len = inp.read(buf)
                if (len < 20) continue
                val pkt = buf.copyOf(len)
                launch { processPacket(pkt) }
            } catch (e: Exception) {
                KighmuLogger.error(TAG, "readTun: ${e.message}")
                delay(100)
            }
        }
    }

    private suspend fun processPacket(pkt: ByteArray) = withContext(Dispatchers.IO) {
        try {
            val version = (pkt[0].toInt() and 0xFF) shr 4
            if (version != 4) return@withContext

            val proto = pkt[9].toInt() and 0xFF
            val ihl = (pkt[0].toInt() and 0x0F) * 4

            val dstIp = "${pkt[16].toInt() and 0xFF}.${pkt[17].toInt() and 0xFF}.${pkt[18].toInt() and 0xFF}.${pkt[19].toInt() and 0xFF}"
            val srcIp = "${pkt[12].toInt() and 0xFF}.${pkt[13].toInt() and 0xFF}.${pkt[14].toInt() and 0xFF}.${pkt[15].toInt() and 0xFF}"

            if (proto == 6 && pkt.size >= ihl + 20) { // TCP
                val dstPort = ((pkt[ihl+2].toInt() and 0xFF) shl 8) or (pkt[ihl+3].toInt() and 0xFF)
                val srcPort = ((pkt[ihl+0].toInt() and 0xFF) shl 8) or (pkt[ihl+1].toInt() and 0xFF)
                val flags = pkt[ihl+13].toInt() and 0xFF
                val isSyn = (flags and 0x02) != 0
                val isFin = (flags and 0x01) != 0 || (flags and 0x04) != 0

                val key = "$srcIp:$srcPort->$dstIp:$dstPort"

                if (isSyn && !sessions.containsKey(key)) {
                    KighmuLogger.info(TAG, "TCP SYN: $key")
                    val session = TcpSession(srcIp, srcPort, dstIp, dstPort, tunFd, socksHost, socksPort)
                    sessions[key] = session
                    launch {
                        session.start()
                        sessions.remove(key)
                    }
                } else if (isFin) {
                    sessions[key]?.close()
                    sessions.remove(key)
                } else {
                    val dataOffset = ihl + ((pkt[ihl+12].toInt() and 0xF0) shr 4) * 4
                    if (dataOffset < pkt.size) {
                        sessions[key]?.sendToRemote(pkt.copyOfRange(dataOffset, pkt.size))
                    }
                }
            } else if (proto == 17 && pkt.size >= ihl + 8) { // UDP - DNS
                val dstPort = ((pkt[ihl+2].toInt() and 0xFF) shl 8) or (pkt[ihl+3].toInt() and 0xFF)
                if (dstPort == 53) {
                    val dataOffset = ihl + 8
                    if (dataOffset < pkt.size) {
                        handleDns(pkt.copyOfRange(dataOffset, pkt.size), srcIp,
                            ((pkt[ihl+0].toInt() and 0xFF) shl 8) or (pkt[ihl+1].toInt() and 0xFF))
                    }
                }
            }
        } catch (e: Exception) {
            KighmuLogger.error(TAG, "processPacket: ${e.message}")
        }
    }

    private fun handleDns(query: ByteArray, srcIp: String, srcPort: Int) {
        // Forward DNS via SOCKS5 UDP ou TCP
        scope.launch(Dispatchers.IO) {
            try {
                val sock = connectSocks5("8.8.8.8", 53) ?: return@launch
                sock.getOutputStream().write(byteArrayOf((query.size shr 8).toByte(), (query.size and 0xFF).toByte()))
                sock.getOutputStream().write(query)
                sock.getOutputStream().flush()
                val lenBuf = ByteArray(2)
                sock.getInputStream().read(lenBuf)
                val len = ((lenBuf[0].toInt() and 0xFF) shl 8) or (lenBuf[1].toInt() and 0xFF)
                val resp = ByteArray(len)
                var r = 0; while (r < len) r += sock.getInputStream().read(resp, r, len - r)
                sock.close()
                KighmuLogger.info(TAG, "DNS reponse: ${resp.size} bytes")
            } catch (e: Exception) {
                KighmuLogger.error(TAG, "DNS: ${e.message}")
            }
        }
    }

    fun connectSocks5(host: String, port: Int): Socket? {
        return try {
            val sock = Socket()
            sock.connect(InetSocketAddress(socksHost, socksPort), 5000)
            val out = sock.getOutputStream()
            val inp = sock.getInputStream()
            out.write(byteArrayOf(0x05, 0x01, 0x00)); out.flush()
            inp.read(); inp.read()
            val hostBytes = host.toByteArray()
            out.write(byteArrayOf(0x05, 0x01, 0x00, 0x03, hostBytes.size.toByte()) +
                hostBytes + byteArrayOf((port shr 8).toByte(), (port and 0xFF).toByte()))
            out.flush()
            val resp = ByteArray(256); var total = 0
            while (total < 4) total += inp.read(resp, total, 4 - total)
            if (resp[1] != 0x00.toByte()) { sock.close(); return null }
            val extra = when (resp[3].toInt()) { 1 -> 6; 3 -> inp.read() + 3; 4 -> 18; else -> 2 }
            val tmp = ByteArray(extra); var r = 0
            while (r < extra) r += inp.read(tmp, r, extra - r)
            sock
        } catch (e: Exception) {
            KighmuLogger.error(TAG, "SOCKS5 $host:$port: ${e.message}"); null
        }
    }
}

class TcpSession(
    private val srcIp: String, private val srcPort: Int,
    private val dstIp: String, private val dstPort: Int,
    private val tunFd: FileDescriptor,
    private val socksHost: String, private val socksPort: Int
) {
    private var socket: Socket? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    suspend fun start() = withContext(Dispatchers.IO) {
        try {
            val relay = com.kighmu.vpn.vpn.Tun2SocksRelay(tunFd, socksHost, socksPort)
            socket = relay.connectSocks5(dstIp, dstPort) ?: return@withContext
            KighmuLogger.info("TcpSession", "Connecte $srcIp:$srcPort -> $dstIp:$dstPort")
        } catch (e: Exception) {
            KighmuLogger.error("TcpSession", "start: ${e.message}")
        }
    }

    fun sendToRemote(data: ByteArray) {
        try { socket?.getOutputStream()?.write(data); socket?.getOutputStream()?.flush() }
        catch (e: Exception) {}
    }

    fun close() {
        scope.cancel()
        try { socket?.close() } catch (_: Exception) {}
    }
}
