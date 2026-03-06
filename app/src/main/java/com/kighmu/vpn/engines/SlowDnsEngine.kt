package com.kighmu.vpn.engines

import android.content.Context
import android.util.Log
import com.kighmu.vpn.models.KighmuConfig
import com.kighmu.vpn.utils.KighmuLogger
import kotlinx.coroutines.*
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.*
import java.util.concurrent.LinkedBlockingQueue

/**
 * SlowDNS Tunnel Engine
 *
 * Tunnels TCP/UDP traffic over DNS queries.
 * Architecture:
 *   App → SOCKS5 local proxy (10800) → DNS encode → DNS Server → Remote → Internet
 *
 * Implementation uses a local SOCKS5 proxy that encapsulates packets
 * into DNS TXT queries sent to the configured DNS server/nameserver.
 */
class SlowDnsEngine(
    private val config: KighmuConfig,
    private val context: Context
) : TunnelEngine {

    companion object {
        const val TAG = "SlowDnsEngine"
        const val LOCAL_SOCKS_PORT = 10800
        const val DNS_TIMEOUT_MS = 5000
        const val MAX_DNS_PAYLOAD = 220  // bytes per DNS query
    }

    private var running = false
    private var socksServerJob: Job? = null
    private var dnsWorkerJob: Job? = null
    private val engineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val sendQueue = LinkedBlockingQueue<ByteArray>(1000)
    private val receiveQueue = LinkedBlockingQueue<ByteArray>(1000)

    private val dnsConfig get() = config.slowDns
    private val sshConfig get() = config.sshCredentials

    override suspend fun start(): Int {
        KighmuLogger.info(TAG, "Starting SlowDNS engine: ${dnsConfig.dnsServer}:${dnsConfig.dnsPort}")
        running = true

        // Start local SOCKS5 proxy server
        startLocalSocksProxy()

        // Start DNS tunnel worker
        startDnsTunnelWorker()

        KighmuLogger.info(TAG, "SlowDNS engine started on port $LOCAL_SOCKS_PORT")
        return LOCAL_SOCKS_PORT
    }

    override suspend fun stop() {
        running = false
        socksServerJob?.cancel()
        dnsWorkerJob?.cancel()
        engineScope.cancel()
        KighmuLogger.info(TAG, "SlowDNS engine stopped")
    }

    override suspend fun sendData(data: ByteArray, length: Int) {
        if (running) sendQueue.offer(data.copyOf(length))
    }

    override suspend fun receiveData(): ByteArray? {
        return if (running) receiveQueue.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS)
        else null
    }

    override fun isRunning() = running

    // ─── Local SOCKS5 Proxy ───────────────────────────────────────────────────

    private fun startLocalSocksProxy() {
        socksServerJob = engineScope.launch {
            val serverSocket = ServerSocket(LOCAL_SOCKS_PORT)
            KighmuLogger.info(TAG, "SOCKS5 proxy listening on port $LOCAL_SOCKS_PORT")
            while (running) {
                try {
                    val client = serverSocket.accept()
                    launch { handleSocksClient(client) }
                } catch (e: Exception) {
                    if (running) KighmuLogger.error(TAG, "SOCKS accept error: ${e.message}")
                }
            }
            serverSocket.close()
        }
    }

    private suspend fun handleSocksClient(socket: Socket) {
        try {
            val input = DataInputStream(socket.getInputStream())
            val output = DataOutputStream(socket.getOutputStream())

            // SOCKS5 handshake
            val version = input.read()
            if (version != 5) { socket.close(); return }

            val nmethods = input.read()
            val methods = ByteArray(nmethods)
            input.readFully(methods)

            // Accept no auth
            output.write(byteArrayOf(5, 0))
            output.flush()

            // SOCKS5 request
            val cmd = input.read()  // skip version
            input.read()            // cmd
            input.read()            // rsv
            val atyp = input.read()

            val destHost = when (atyp) {
                1 -> { // IPv4
                    val addr = ByteArray(4); input.readFully(addr)
                    InetAddress.getByAddress(addr).hostAddress
                }
                3 -> { // Domain
                    val len = input.read()
                    val domain = ByteArray(len); input.readFully(domain)
                    String(domain)
                }
                4 -> { // IPv6
                    val addr = ByteArray(16); input.readFully(addr)
                    InetAddress.getByAddress(addr).hostAddress
                }
                else -> { socket.close(); return }
            }

            val destPort = (input.read() shl 8) or input.read()

            // Send success response
            output.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
            output.flush()

            KighmuLogger.info(TAG, "SOCKS5 tunnel: $destHost:$destPort")

            // Tunnel through DNS
            tunnelTcpOverDns(socket, destHost, destPort)

        } catch (e: Exception) {
            KighmuLogger.error(TAG, "SOCKS client error: ${e.message}")
        } finally {
            socket.close()
        }
    }

    // ─── DNS Tunnel Worker ────────────────────────────────────────────────────

    private fun startDnsTunnelWorker() {
        dnsWorkerJob = engineScope.launch {
            while (running) {
                try {
                    val data = withTimeoutOrNull(500) {
                        withContext(Dispatchers.IO) {
                            sendQueue.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS)
                        }
                    } ?: continue

                    // Encode data as DNS query and send
                    val response = sendDnsQuery(data)
                    if (response != null) {
                        receiveQueue.offer(response)
                    }
                } catch (e: Exception) {
                    if (running) KighmuLogger.error(TAG, "DNS worker error: ${e.message}")
                }
            }
        }
    }

    private suspend fun tunnelTcpOverDns(clientSocket: Socket, destHost: String, destPort: Int) {
        // Real implementation would encode/decode TCP stream into DNS queries
        // Here we establish the DNS session and relay data
        withContext(Dispatchers.IO) {
            try {
                // Establish DNS tunnel session
                val sessionId = establishDnsSession(destHost, destPort)
                if (sessionId == null) {
                    KighmuLogger.error(TAG, "Failed to establish DNS session")
                    return@withContext
                }

                // Bidirectional relay: client socket <-> DNS tunnel
                val clientInput = clientSocket.getInputStream()
                val clientOutput = clientSocket.getOutputStream()
                val buffer = ByteArray(MAX_DNS_PAYLOAD)

                val readerJob = launch {
                    while (running && !clientSocket.isClosed) {
                        val len = clientInput.read(buffer)
                        if (len <= 0) break
                        sendViaDns(sessionId, buffer, len)
                    }
                }

                val writerJob = launch {
                    while (running && !clientSocket.isClosed) {
                        val data = receiveFromDns(sessionId) ?: continue
                        clientOutput.write(data)
                        clientOutput.flush()
                    }
                }

                readerJob.join()
                writerJob.cancel()
            } catch (e: Exception) {
                KighmuLogger.error(TAG, "DNS tunnel relay error: ${e.message}")
            }
        }
    }

    private fun establishDnsSession(destHost: String, destPort: Int): String? {
        return try {
            // Send SYN-like DNS query to SlowDNS server
            val connectPayload = "CONNECT:${destHost}:${destPort}:${sshConfig.username}"
            val encoded = encodeForDns(connectPayload.toByteArray())
            val query = buildDnsQuery("${encoded}.${dnsConfig.nameserver}")
            val response = sendRawDnsQuery(query)
            parseDnsSessionId(response)
        } catch (e: Exception) {
            KighmuLogger.error(TAG, "Session establishment failed: ${e.message}")
            null
        }
    }

    private fun sendViaDns(sessionId: String, data: ByteArray, len: Int) {
        val chunks = data.copyOf(len).toList().chunked(MAX_DNS_PAYLOAD)
        chunks.forEach { chunk ->
            val encoded = encodeForDns(chunk.toByteArray())
            val query = buildDnsQuery("${sessionId}.${encoded}.${dnsConfig.nameserver}")
            sendRawDnsQuery(query)
        }
    }

    private fun receiveFromDns(sessionId: String): ByteArray? {
        val query = buildDnsQuery("RECV.${sessionId}.${dnsConfig.nameserver}")
        val response = sendRawDnsQuery(query) ?: return null
        return parseDnsData(response)
    }

    private fun sendDnsQuery(data: ByteArray): ByteArray? {
        val encoded = encodeForDns(data)
        val query = buildDnsQuery("${encoded}.${dnsConfig.nameserver}")
        return sendRawDnsQuery(query)
    }

    private fun encodeForDns(data: ByteArray): String {
        // Base32-like encoding safe for DNS labels
        return android.util.Base64.encodeToString(data, android.util.Base64.NO_PADDING or android.util.Base64.URL_SAFE)
            .replace("=", "")
            .chunked(63)
            .joinToString(".")
    }

    private fun buildDnsQuery(domain: String): ByteArray {
        // Build raw DNS TXT query packet
        val id = (Math.random() * 65535).toInt()
        val packet = mutableListOf<Byte>()

        // Header
        packet.add((id shr 8).toByte())
        packet.add((id and 0xFF).toByte())
        packet.add(0x01.toByte()); packet.add(0x00.toByte()) // Standard query
        packet.add(0x00.toByte()); packet.add(0x01.toByte()) // 1 question
        packet.add(0x00.toByte()); packet.add(0x00.toByte()) // 0 answers
        packet.add(0x00.toByte()); packet.add(0x00.toByte()) // 0 authority
        packet.add(0x00.toByte()); packet.add(0x00.toByte()) // 0 additional

        // QNAME
        domain.split(".").forEach { label ->
            packet.add(label.length.toByte())
            label.toByteArray().forEach { packet.add(it) }
        }
        packet.add(0x00.toByte()) // End of QNAME

        // QTYPE = TXT (16), QCLASS = IN (1)
        packet.add(0x00.toByte()); packet.add(0x10.toByte())
        packet.add(0x00.toByte()); packet.add(0x01.toByte())

        return packet.toByteArray()
    }

    private fun sendRawDnsQuery(query: ByteArray): ByteArray? {
        return try {
            if (dnsConfig.useUdp) {
                val socket = DatagramSocket()
                socket.soTimeout = DNS_TIMEOUT_MS
                val packet = DatagramPacket(
                    query, query.size,
                    InetAddress.getByName(dnsConfig.dnsServer),
                    dnsConfig.dnsPort
                )
                socket.send(packet)
                val buf = ByteArray(4096)
                val resp = DatagramPacket(buf, buf.size)
                socket.receive(resp)
                socket.close()
                resp.data.copyOf(resp.length)
            } else {
                // TCP DNS
                val socket = Socket()
                socket.soTimeout = DNS_TIMEOUT_MS
                socket.connect(InetSocketAddress(dnsConfig.dnsServer, dnsConfig.dnsPort), DNS_TIMEOUT_MS)
                val out = socket.getOutputStream()
                // TCP DNS: 2-byte length prefix
                out.write(byteArrayOf((query.size shr 8).toByte(), (query.size and 0xFF).toByte()))
                out.write(query)
                out.flush()
                val inp = DataInputStream(socket.getInputStream())
                val len = inp.readShort().toInt()
                val resp = ByteArray(len)
                inp.readFully(resp)
                socket.close()
                resp
            }
        } catch (e: Exception) {
            KighmuLogger.error(TAG, "DNS query failed: ${e.message}")
            null
        }
    }

    private fun parseDnsSessionId(response: ByteArray?): String? {
        if (response == null || response.size < 12) return null
        // Parse TXT record from DNS response (simplified)
        return try {
            val txt = extractTxtRecord(response)
            if (txt.startsWith("SID:")) txt.substring(4) else null
        } catch (e: Exception) { null }
    }

    private fun parseDnsData(response: ByteArray?): ByteArray? {
        if (response == null) return null
        return try {
            val txt = extractTxtRecord(response)
            if (txt.isNotEmpty()) android.util.Base64.decode(txt, android.util.Base64.URL_SAFE)
            else null
        } catch (e: Exception) { null }
    }

    private fun extractTxtRecord(response: ByteArray): String {
        // Simplified TXT record extraction
        if (response.size < 12) return ""
        val ancount = ((response[6].toInt() and 0xFF) shl 8) or (response[7].toInt() and 0xFF)
        if (ancount == 0) return ""

        var offset = 12
        // Skip questions
        while (offset < response.size && response[offset] != 0.toByte()) {
            offset += (response[offset].toInt() and 0xFF) + 1
        }
        offset += 5 // null + qtype + qclass

        // Read first answer
        if (offset + 12 > response.size) return ""
        offset += 12 // name(2) + type(2) + class(2) + ttl(4) + rdlength(2)

        val rdlength = ((response[offset - 2].toInt() and 0xFF) shl 8) or (response[offset - 1].toInt() and 0xFF)
        if (offset + rdlength > response.size || rdlength < 2) return ""

        val txtLen = response[offset].toInt() and 0xFF
        return String(response, offset + 1, minOf(txtLen, rdlength - 1))
    }
}
