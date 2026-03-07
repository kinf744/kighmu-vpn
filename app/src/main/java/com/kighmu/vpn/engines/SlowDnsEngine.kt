package com.kighmu.vpn.engines

import android.content.Context
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.kighmu.vpn.models.KighmuConfig
import com.kighmu.vpn.utils.KighmuLogger
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * SlowDNS Engine
 *
 * Architecture reelle (SSH Custom / HTTP Injector):
 *   JSch -> proxy local TCP -> encode DNS TXT -> 8.8.8.8:53 -> nameserver -> SSH server
 *
 * Le proxy local:
 *   1. Accepte connexion TCP de JSch
 *   2. Relay les bytes via DNS TXT queries vers nameserver
 *   3. Retourne les reponses DNS au JSch
 */
class SlowDnsEngine(
    private val config: KighmuConfig,
    private val context: Context
) : TunnelEngine {

    companion object {
        const val TAG = "SlowDnsEngine"
        const val LOCAL_SOCKS_PORT = 10800
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
        KighmuLogger.info(TAG, "DNS Resolver : ${dns.dnsServer}:${dns.dnsPort}")
        KighmuLogger.info(TAG, "Nameserver   : ${dns.nameserver}")
        KighmuLogger.info(TAG, "Public Key   : ${if (dns.publicKey.isNotBlank()) dns.publicKey.take(30)+"..." else "VIDE!"}")
        KighmuLogger.info(TAG, "SSH Host     : ${ssh.host}:${ssh.port}")
        KighmuLogger.info(TAG, "SSH User     : ${ssh.username}")

        if (dns.nameserver.isBlank()) throw Exception("Nameserver manquant")
        if (dns.publicKey.isBlank()) throw Exception("Public Key manquante")
        if (ssh.host.isBlank()) throw Exception("SSH Host manquant")
        if (ssh.username.isBlank() || ssh.password.isBlank()) throw Exception("SSH credentials manquants")

        try {
            // Etape 1: Demarrer proxy DNS local (port dynamique)
            val server = ServerSocket(0)
            proxyServer = server
            val proxyPort = server.localPort
            KighmuLogger.info(TAG, "SlowDns running")
            KighmuLogger.info(TAG, "Proxy DNS local sur port $proxyPort")

            // Etape 2: Demarrer thread qui accepte et relaie via DNS
            engineScope.launch(Dispatchers.IO) {
                while (running) {
                    try {
                        val client = server.accept()
                        KighmuLogger.info(TAG, "Connexion SSH acceptee, relay DNS demarre")
                        launch { relayTcpOverDns(client) }
                    } catch (e: Exception) {
                        if (running) KighmuLogger.error(TAG, "Proxy accept: ${e.message}")
                        break
                    }
                }
            }

            // Etape 3: SSH se connecte au proxy local
            KighmuLogger.info(TAG, "Connecting to ${ssh.host} via SlowDNS proxy")
            startSsh(proxyPort)

            KighmuLogger.info(TAG, "=== SlowDNS CONNECTE - SOCKS5 port $LOCAL_SOCKS_PORT ===")
            LOCAL_SOCKS_PORT

        } catch (e: com.jcraft.jsch.JSchException) {
            val msg = when {
                e.message?.contains("Auth fail") == true -> "Auth SSH echouee (user=${ssh.username})"
                e.message?.contains("timeout") == true -> "Timeout - DNS relay lent ou nameserver inaccessible"
                e.message?.contains("Premature") == true -> "Connexion prematuree - reessayez"
                e.message?.contains("Connection refused") == true -> "Connexion refusee"
                else -> "SSH: ${e.message}"
            }
            KighmuLogger.error(TAG, "ECHEC SSH: $msg")
            throw Exception(msg)
        } catch (e: Exception) {
            KighmuLogger.error(TAG, "ECHEC: ${e.javaClass.simpleName}: ${e.message}")
            throw e
        }
    }

    /**
     * Relaie une connexion TCP via DNS TXT queries
     * Client (JSch) <-> proxy local <-> DNS queries <-> nameserver <-> SSH server
     */
    private suspend fun relayTcpOverDns(client: Socket) = withContext(Dispatchers.IO) {
        val dnsAddr = InetAddress.getByName(dns.dnsServer)
        val udp = DatagramSocket()
        udp.soTimeout = 8000

        try {
            val inp = client.getInputStream()
            val out = client.getOutputStream()
            val buf = ByteArray(1024)

            // Session ID unique pour ce tunnel
            val sessionId = System.currentTimeMillis().toString(16)
            KighmuLogger.info(TAG, "DNS relay session: $sessionId")

            // Init session avec nameserver
            val initQuery = buildTxtQuery("init.${sessionId}.${dns.nameserver}")
            udp.send(DatagramPacket(initQuery, initQuery.size, dnsAddr, dns.dnsPort))
            KighmuLogger.info(TAG, "Session DNS initialisee")

            // Relay bidirectionnel
            val sendJob = launch {
                while (running && !client.isClosed) {
                    val len = withContext(Dispatchers.IO) { inp.read(buf) }
                    if (len <= 0) break
                    // Chunker les donnees en morceaux DNS-safe
                    val chunks = buf.copyOf(len).toList().chunked(100)
                    chunks.forEachIndexed { idx, chunk ->
                        val b64 = android.util.Base64.encodeToString(
                            chunk.toByteArray(),
                            android.util.Base64.NO_PADDING or android.util.Base64.URL_SAFE
                        ).replace("=","").replace("\n","")
                        val q = buildTxtQuery("d${idx}.${sessionId}.${b64}.${dns.nameserver}")
                        udp.send(DatagramPacket(q, q.size, dnsAddr, dns.dnsPort))
                    }
                }
            }

            val recvJob = launch {
                val rbuf = ByteArray(512)
                while (running && !client.isClosed) {
                    try {
                        val rpacket = DatagramPacket(rbuf, rbuf.size)
                        udp.receive(rpacket)
                        val decoded = parseTxtResponse(rbuf, rpacket.length) ?: continue
                        if (decoded.isNotEmpty()) {
                            out.write(decoded)
                            out.flush()
                        }
                    } catch (e: java.net.SocketTimeoutException) {
                        // keepalive
                        val ka = buildTxtQuery("ka.${sessionId}.${dns.nameserver}")
                        udp.send(DatagramPacket(ka, ka.size, dnsAddr, dns.dnsPort))
                    }
                }
            }

            sendJob.join()
            recvJob.cancel()
            KighmuLogger.info(TAG, "DNS relay session terminee")

        } catch (e: Exception) {
            KighmuLogger.error(TAG, "DNS relay erreur: ${e.message}")
        } finally {
            udp.close()
            client.close()
        }
    }

    private fun startSsh(proxyPort: Int) {
        val jsch = JSch()
        if (ssh.usePrivateKey && ssh.privateKey.isNotEmpty()) {
            jsch.addIdentity("key", ssh.privateKey.toByteArray(), null, null)
        }

        // SSH -> proxy local -> DNS -> nameserver -> SSH server
        val session = jsch.getSession(ssh.username, "127.0.0.1", proxyPort)
        session.setPassword(ssh.password)
        session.setConfig("StrictHostKeyChecking", "no")
        session.setConfig("PreferredAuthentications", "publickey,password")
        session.setConfig("compression.s2c", "zlib@openssh.com,zlib,none")
        session.setConfig("compression.c2s", "zlib@openssh.com,zlib,none")
        session.setConfig("compression_level", "9")

        KighmuLogger.info(TAG, "SSH connexion via proxy local:$proxyPort (timeout 45s)...")
        session.connect(45000)
        jschSession = session

        KighmuLogger.info(TAG, "Fingerprint: ${session.serverVersion}")
        KighmuLogger.info(TAG, "SSH connecte!")

        session.setPortForwardingL(LOCAL_SOCKS_PORT, "127.0.0.1", LOCAL_SOCKS_PORT)
        KighmuLogger.info(TAG, "Socks port $LOCAL_SOCKS_PORT")
    }

    private fun buildTxtQuery(domain: String): ByteArray {
        val buf = mutableListOf<Byte>()
        val id = (Math.random() * 65535).toInt()
        buf.add((id shr 8).toByte()); buf.add((id and 0xFF).toByte())
        buf.add(0x01); buf.add(0x00)
        buf.add(0x00); buf.add(0x01)
        repeat(6) { buf.add(0x00) }
        domain.split(".").forEach { label ->
            if (label.isNotEmpty()) {
                buf.add(label.length.toByte())
                label.forEach { buf.add(it.code.toByte()) }
            }
        }
        buf.add(0x00)
        buf.add(0x00); buf.add(0x10) // TXT
        buf.add(0x00); buf.add(0x01) // IN
        return buf.toByteArray()
    }

    private fun parseTxtResponse(data: ByteArray, len: Int): ByteArray? {
        return try {
            if (len < 12) return null
            val answers = ((data[6].toInt() and 0xFF) shl 8) or (data[7].toInt() and 0xFF)
            if (answers == 0) return null
            var pos = 12
            while (pos < len && data[pos] != 0.toByte()) {
                val labelLen = data[pos].toInt() and 0xFF
                if (labelLen and 0xC0 == 0xC0) { pos += 2; break }
                pos += labelLen + 1
            }
            if (data[pos] == 0.toByte()) pos++
            pos += 4 // skip qtype+qclass
            // Answer record
            if (pos + 2 >= len) return null
            if (data[pos].toInt() and 0xC0 == 0xC0) pos += 2 else {
                while (pos < len && data[pos] != 0.toByte()) pos++
                pos++
            }
            pos += 8 // type+class+ttl
            if (pos + 2 >= len) return null
            val rdLen = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos+1].toInt() and 0xFF)
            pos += 2
            val txtLen = data[pos].toInt() and 0xFF
            pos++
            if (pos + txtLen > len) return null
            android.util.Base64.decode(
                String(data, pos, txtLen),
                android.util.Base64.NO_PADDING or android.util.Base64.URL_SAFE
            )
        } catch (e: Exception) { null }
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
