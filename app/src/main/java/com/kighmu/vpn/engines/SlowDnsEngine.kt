package com.kighmu.vpn.engines

import android.content.Context
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.kighmu.vpn.models.KighmuConfig
import com.kighmu.vpn.utils.KighmuLogger
import kotlinx.coroutines.*
import java.net.*
import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * SlowDNS Engine - Protocole DNSTT natif en Kotlin
 *
 * Le protocole dnstt:
 * 1. Client envoie des DNS TXT queries encodees en base32
 * 2. Le nameserver decode et forward au SSH server
 * 3. Les reponses DNS TXT contiennent les donnees SSH encryptees
 * 4. JSch se connecte au proxy local qui fait ce relay
 */
class SlowDnsEngine(
    private val config: KighmuConfig,
    private val context: Context
) : TunnelEngine {

    companion object {
        const val TAG = "SlowDnsEngine"
        const val LOCAL_SOCKS_PORT = 10800
        // Base32 alphabet utilisé par dnstt
        val BASE32 = "abcdefghijklmnopqrstuvwxyz234567"
    }

    private var running = false
    private var jschSession: Session? = null
    private var proxyServer: ServerSocket? = null
    private val engineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val dns get() = config.slowDns
    private val ssh get() = config.sshCredentials

    override suspend fun start(): Int = withContext(Dispatchers.IO) {
        running = true
        KighmuLogger.info(TAG, "=== Demarrage SlowDNS ===")
        KighmuLogger.info(TAG, "DNS : ${dns.dnsServer}:${dns.dnsPort}")
        KighmuLogger.info(TAG, "Nameserver : ${dns.nameserver}")
        KighmuLogger.info(TAG, "PublicKey : ${dns.publicKey.take(20)}...")
        KighmuLogger.info(TAG, "SSH : ${ssh.host}:${ssh.port} / ${ssh.username}")

        if (dns.nameserver.isBlank()) throw Exception("Nameserver manquant")
        if (dns.publicKey.isBlank()) throw Exception("Public Key manquante")
        if (ssh.host.isBlank()) throw Exception("SSH Host manquant")
        if (ssh.username.isBlank() || ssh.password.isBlank()) throw Exception("SSH credentials manquants")

        try {
            // Demarrer proxy DNSTT local
            val server = ServerSocket(0)
            proxyServer = server
            val proxyPort = server.localPort
            KighmuLogger.info(TAG, "SlowDns running - proxy local port $proxyPort")

            engineScope.launch(Dispatchers.IO) {
                while (running) {
                    try {
                        val client = server.accept()
                        KighmuLogger.info(TAG, "Client SSH connecte au proxy DNSTT")
                        launch { handleDnsttSession(client) }
                    } catch (e: Exception) {
                        if (running) KighmuLogger.error(TAG, "Proxy: ${e.message}")
                        break
                    }
                }
            }

            // SSH via proxy DNSTT
            KighmuLogger.info(TAG, "Connecting to ${ssh.host} via DNSTT")
            startSsh(proxyPort)

            KighmuLogger.info(TAG, "=== SlowDNS CONNECTE port $LOCAL_SOCKS_PORT ===")
            LOCAL_SOCKS_PORT

        } catch (e: com.jcraft.jsch.JSchException) {
            val msg = when {
                e.message?.contains("Auth fail") == true -> "Auth SSH echouee (${ssh.username})"
                e.message?.contains("timeout") == true -> "Timeout DNSTT - nameserver inaccessible?"
                e.message?.contains("Premature") == true -> "Connexion SSH prematuree"
                e.message?.contains("Connection refused") == true -> "Proxy non pret"
                else -> e.message ?: "Erreur SSH"
            }
            KighmuLogger.error(TAG, "ECHEC SSH: $msg")
            throw Exception(msg)
        } catch (e: Exception) {
            KighmuLogger.error(TAG, "ECHEC: ${e.javaClass.simpleName}: ${e.message}")
            throw e
        }
    }

    private suspend fun handleDnsttSession(client: Socket) = withContext(Dispatchers.IO) {
        val dnsAddr = InetAddress.getByName(dns.dnsServer)
        val sendUdp = DatagramSocket()
        val recvUdp = DatagramSocket()
        sendUdp.soTimeout = 5000
        recvUdp.soTimeout = 100

        try {
            val inp = client.getInputStream()
            val out = client.getOutputStream()
            val sendBuf = ByteArray(512)
            val recvBuf = ByteArray(4096)

            // ID de session unique
            val sessId = (System.nanoTime() and 0xFFFFFFL).toInt()
            KighmuLogger.info(TAG, "Session DNSTT: $sessId -> ${dns.nameserver}")

            val sendJob = launch {
                while (running && !client.isClosed) {
                    val len = inp.read(sendBuf)
                    if (len <= 0) break
                    try {
                        val data = sendBuf.copyOf(len)
                        val queries = encodeAsDnsQueries(data, sessId)
                        queries.forEach { q ->
                            val pkt = DatagramPacket(q, q.size, dnsAddr, dns.dnsPort)
                            sendUdp.send(pkt)
                        }
                    } catch (e: Exception) {
                        KighmuLogger.error(TAG, "Send DNS: ${e.message}")
                    }
                }
            }

            val recvJob = launch {
                while (running && !client.isClosed) {
                    try {
                        val pkt = DatagramPacket(recvBuf, recvBuf.size)
                        recvUdp.receive(pkt)
                        val decoded = decodeDnsResponse(recvBuf, pkt.length)
                        if (decoded != null && decoded.isNotEmpty()) {
                            out.write(decoded)
                            out.flush()
                        }
                    } catch (e: SocketTimeoutException) {
                        // keepalive ping
                        try {
                            val ka = buildKeepalive(sessId)
                            sendUdp.send(DatagramPacket(ka, ka.size, dnsAddr, dns.dnsPort))
                        } catch (_: Exception) {}
                    } catch (e: Exception) {
                        if (running) KighmuLogger.error(TAG, "Recv DNS: ${e.message}")
                    }
                }
            }

            sendJob.join()
            recvJob.cancel()

        } catch (e: Exception) {
            KighmuLogger.error(TAG, "Session DNSTT: ${e.message}")
        } finally {
            sendUdp.close()
            recvUdp.close()
            client.close()
        }
    }

    private fun encodeAsDnsQueries(data: ByteArray, sessId: Int): List<ByteArray> {
        val queries = mutableListOf<ByteArray>()
        val chunkSize = 30
        var offset = 0
        var seq = 0
        while (offset < data.size) {
            val end = minOf(offset + chunkSize, data.size)
            val chunk = data.copyOfRange(offset, end)
            val encoded = base32Encode(chunk)
            val seqStr = seq.toString(16).padStart(4, '0')
            val sessStr = sessId.toString(16).padStart(6, '0')
            val domain = "${encoded}.${seqStr}.${sessStr}.${dns.nameserver}"
            queries.add(buildDnsQuery(domain, 16)) // TXT
            offset = end
            seq++
        }
        return queries
    }

    private fun buildKeepalive(sessId: Int): ByteArray {
        val sessStr = sessId.toString(16).padStart(6, '0')
        return buildDnsQuery("ka.${sessStr}.${dns.nameserver}", 16)
    }

    private fun buildDnsQuery(domain: String, qtype: Int): ByteArray {
        val buf = mutableListOf<Byte>()
        val id = (Math.random() * 65535).toInt()
        buf.add((id shr 8).toByte()); buf.add((id and 0xFF).toByte())
        buf.add(0x01); buf.add(0x00) // standard query, recursion desired
        buf.add(0x00); buf.add(0x01) // 1 question
        buf.add(0x00); buf.add(0x00)
        buf.add(0x00); buf.add(0x00)
        buf.add(0x00); buf.add(0x00)
        domain.split(".").forEach { label ->
            if (label.isNotEmpty()) {
                buf.add(label.length.toByte())
                label.forEach { buf.add(it.code.toByte()) }
            }
        }
        buf.add(0x00)
        buf.add((qtype shr 8).toByte()); buf.add((qtype and 0xFF).toByte())
        buf.add(0x00); buf.add(0x01) // IN
        return buf.toByteArray()
    }

    private fun decodeDnsResponse(data: ByteArray, len: Int): ByteArray? {
        return try {
            if (len < 12) return null
            val ancount = ((data[6].toInt() and 0xFF) shl 8) or (data[7].toInt() and 0xFF)
            if (ancount == 0) return null
            var pos = 12
            // Skip question section
            while (pos < len && data[pos] != 0.toByte()) {
                val l = data[pos].toInt() and 0xFF
                if (l and 0xC0 == 0xC0) { pos += 2; break }
                pos += l + 1
            }
            if (pos < len && data[pos] == 0.toByte()) pos++
            pos += 4 // qtype + qclass

            // Parse answer
            if (pos >= len) return null
            if (data[pos].toInt() and 0xC0 == 0xC0) pos += 2
            else { while (pos < len && data[pos] != 0.toByte()) pos++; pos++ }

            pos += 2 + 2 + 4 // type + class + ttl
            if (pos + 2 >= len) return null
            val rdlen = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos+1].toInt() and 0xFF)
            pos += 2
            if (pos >= len) return null

            // TXT record: premier octet = longueur du string
            val txtLen = data[pos].toInt() and 0xFF
            pos++
            if (pos + txtLen > len) return null
            val txt = String(data, pos, txtLen)
            base32Decode(txt)
        } catch (e: Exception) { null }
    }

    private fun base32Encode(data: ByteArray): String {
        val sb = StringBuilder()
        var buffer = 0
        var bitsLeft = 0
        for (b in data) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bitsLeft += 8
            while (bitsLeft >= 5) {
                bitsLeft -= 5
                sb.append(BASE32[(buffer shr bitsLeft) and 31])
            }
        }
        if (bitsLeft > 0) sb.append(BASE32[(buffer shl (5 - bitsLeft)) and 31])
        return sb.toString()
    }

    private fun base32Decode(s: String): ByteArray {
        val result = mutableListOf<Byte>()
        var buffer = 0
        var bitsLeft = 0
        for (c in s.lowercase()) {
            val idx = BASE32.indexOf(c)
            if (idx < 0) continue
            buffer = (buffer shl 5) or idx
            bitsLeft += 5
            if (bitsLeft >= 8) {
                bitsLeft -= 8
                result.add((buffer shr bitsLeft).toByte())
                buffer = buffer and ((1 shl bitsLeft) - 1)
            }
        }
        return result.toByteArray()
    }

    private fun startSsh(proxyPort: Int) {
        val jsch = JSch()
        if (ssh.usePrivateKey && ssh.privateKey.isNotEmpty()) {
            jsch.addIdentity("key", ssh.privateKey.toByteArray(), null, null)
        }
        val session = jsch.getSession(ssh.username, "127.0.0.1", proxyPort)
        session.setPassword(ssh.password)
        session.setConfig("StrictHostKeyChecking", "no")
        session.setConfig("PreferredAuthentications", "publickey,password")
        session.setConfig("compression.s2c", "zlib@openssh.com,zlib,none")
        session.setConfig("compression.c2s", "zlib@openssh.com,zlib,none")
        session.setConfig("compression_level", "9")

        KighmuLogger.info(TAG, "SSH connexion (timeout 45s)...")
        session.connect(45000)
        jschSession = session
        KighmuLogger.info(TAG, "SSH connecte! ${session.serverVersion}")
        session.setPortForwardingL(LOCAL_SOCKS_PORT, "127.0.0.1", LOCAL_SOCKS_PORT)
        KighmuLogger.info(TAG, "Socks port $LOCAL_SOCKS_PORT")
    }

    override suspend fun stop() {
        running = false
        jschSession?.disconnect()
        jschSession = null
        try { proxyServer?.close() } catch (_: Exception) {}
        engineScope.cancel()
        KighmuLogger.info(TAG, "SlowDNS arrete")
    }

    override suspend fun sendData(data: ByteArray, length: Int) {}
    override suspend fun receiveData(): ByteArray? = null
    override fun isRunning() = running && jschSession?.isConnected == true
}
