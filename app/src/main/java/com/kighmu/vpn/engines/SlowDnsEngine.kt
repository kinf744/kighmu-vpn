package com.kighmu.vpn.engines

import android.content.Context
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.kighmu.vpn.models.KighmuConfig
import com.kighmu.vpn.utils.KighmuLogger
import kotlinx.coroutines.*
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * SlowDNS Engine
 * Architecture (comme SSH Custom / HTTP Injector):
 *   1. SlowDNS proxy local (UDP DNS queries -> Nameserver)
 *   2. SSH via socket proxy SlowDNS
 *   3. SOCKS5 dynamique sur port local
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
    private var dnsttServerSocket: ServerSocket? = null
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
        KighmuLogger.info(TAG, "SSH Pass     : ${if (ssh.password.isNotBlank()) "****" else "VIDE!"}")

        if (dns.nameserver.isBlank()) throw Exception("Nameserver manquant")
        if (dns.publicKey.isBlank()) throw Exception("Public Key DNSTT manquante")
        if (ssh.host.isBlank()) throw Exception("SSH Host manquant")
        if (ssh.username.isBlank() || ssh.password.isBlank()) throw Exception("SSH credentials manquants")

        try {
            // Etape 1: Demarrer proxy SlowDNS local sur port dynamique
            KighmuLogger.info(TAG, "SlowDns running")
            val dnsttPort = startSlowDnsProxy()
            KighmuLogger.info(TAG, "SlowDNS proxy local sur port $dnsttPort")

            // Etape 2: SSH via socket proxy SlowDNS
            KighmuLogger.info(TAG, "Connecting to ${ssh.host}")
            KighmuLogger.info(TAG, "SSH connect via socket proxy")
            startSshViaProxy(dnsttPort)

            KighmuLogger.info(TAG, "=== SlowDNS CONNECTE - SOCKS5 port $LOCAL_SOCKS_PORT ===")
            LOCAL_SOCKS_PORT

        } catch (e: com.jcraft.jsch.JSchException) {
            val msg = when {
                e.message?.contains("Auth fail") == true -> "SSH Auth echouee (user=${ssh.username})"
                e.message?.contains("Connection refused") == true -> "SSH Connexion refusee (${ssh.host}:${ssh.port})"
                e.message?.contains("timeout") == true -> "SSH Timeout - verifiez nameserver"
                e.message?.contains("Premature") == true -> "SSH Premature close - serveur occupe, reessayez"
                else -> "SSH: ${e.message}"
            }
            KighmuLogger.error(TAG, "ECHEC SSH: $msg")
            throw Exception(msg)
        } catch (e: Exception) {
            KighmuLogger.error(TAG, "ECHEC SlowDNS: ${e.javaClass.simpleName}: ${e.message}")
            throw e
        }
    }

    private fun startSlowDnsProxy(): Int {
        // Utiliser port 0 = OS choisit un port libre automatiquement
        val serverSocket = ServerSocket(0)
        val port = serverSocket.localPort
        dnsttServerSocket = serverSocket

        KighmuLogger.info(TAG, "Penetration running")
        KighmuLogger.info(TAG, "Endpoint forwarder 127.0.0.1:${ssh.port}")

        engineScope.launch(Dispatchers.IO) {
            while (running) {
                try {
                    val client = serverSocket.accept()
                    launch { relayViaDns(client) }
                } catch (e: Exception) {
                    if (running) KighmuLogger.error(TAG, "SlowDNS accept: ${e.message}")
                    break
                }
            }
        }
        return port
    }

    private suspend fun relayViaDns(client: Socket) = withContext(Dispatchers.IO) {
        var udpSock: DatagramSocket? = null
        try {
            val dnsAddr = InetAddress.getByName(dns.dnsServer)
            val clientIn = client.getInputStream()
            val clientOut = client.getOutputStream()

            // Envoyer CONNECT via DNS (comme SSH Custom: CONNECT 127.0.0.1:port HTTP/1.0)
            val connectMsg = "CONNECT ${ssh.host}:${ssh.port} HTTP/1.0\r\n\r\n"

            KighmuLogger.info(TAG, "CONNECT ${ssh.host}:${ssh.port} HTTP/1.0")
            KighmuLogger.info(TAG, "Sending payload")

            udpSock = DatagramSocket()
            udpSock!!.soTimeout = 5000

            val buf = ByteArray(4096)
            // Relay loop: client -> DNS -> nameserver -> SSH server -> DNS -> client
            val readerJob = launch {
                while (running && !client.isClosed) {
                    val len = clientIn.read(buf)
                    if (len <= 0) break
                    // Encapsuler dans query DNS TXT vers nameserver
                    sendDnsData(udpSock!!, dnsAddr, buf, len)
                }
            }

            val writerJob = launch {
                while (running && !client.isClosed) {
                    val data = receiveDnsData(udpSock!!) ?: continue
                    clientOut.write(data)
                    clientOut.flush()
                }
            }

            readerJob.join()
            writerJob.cancel()
        } catch (e: Exception) {
            if (running) KighmuLogger.error(TAG, "DNS relay: ${e.message}")
        } finally {
            udpSock?.close()
            client.close()
        }
    }

    private fun sendDnsData(sock: DatagramSocket, dnsAddr: InetAddress, data: ByteArray, len: Int) {
        val encoded = android.util.Base64.encodeToString(
            data.copyOf(len),
            android.util.Base64.NO_PADDING or android.util.Base64.URL_SAFE
        ).replace("=", "").take(180)
        val query = buildDnsTxtQuery("${encoded}.${dns.nameserver}")
        val packet = DatagramPacket(query, query.size, dnsAddr, dns.dnsPort)
        sock.send(packet)
    }

    private fun receiveDnsData(sock: DatagramSocket): ByteArray? {
        return try {
            val buf = ByteArray(512)
            val packet = DatagramPacket(buf, buf.size)
            sock.receive(packet)
            parseDnsTxtResponse(packet.data, packet.length)
        } catch (e: Exception) { null }
    }

    private fun buildDnsTxtQuery(domain: String): ByteArray {
        val buf = mutableListOf<Byte>()
        val id = (Math.random() * 65535).toInt()
        buf.add((id shr 8).toByte()); buf.add((id and 0xFF).toByte())
        buf.add(0x01); buf.add(0x00) // standard query
        buf.add(0x00); buf.add(0x01) // 1 question
        repeat(6) { buf.add(0x00) }
        domain.split(".").forEach { label ->
            buf.add(label.length.toByte())
            label.forEach { buf.add(it.code.toByte()) }
        }
        buf.add(0x00)
        buf.add(0x00); buf.add(0x10) // TXT
        buf.add(0x00); buf.add(0x01) // IN
        return buf.toByteArray()
    }

    private fun parseDnsTxtResponse(data: ByteArray, len: Int): ByteArray? {
        return try {
            if (len < 12) return null
            val answers = ((data[6].toInt() and 0xFF) shl 8) or (data[7].toInt() and 0xFF)
            if (answers == 0) return null
            var pos = 12
            while (pos < len && data[pos] != 0.toByte()) pos += (data[pos].toInt() and 0xFF) + 1
            pos += 5
            if (pos + 10 >= len) return null
            pos += 10
            val txtLen = data[pos].toInt() and 0xFF
            pos++
            if (pos + txtLen > len) return null
            android.util.Base64.decode(
                String(data, pos, txtLen),
                android.util.Base64.NO_PADDING or android.util.Base64.URL_SAFE
            )
        } catch (e: Exception) { null }
    }

    private fun startSshViaProxy(proxyPort: Int) {
        val jsch = JSch()
        if (ssh.usePrivateKey && ssh.privateKey.isNotEmpty()) {
            jsch.addIdentity("key", ssh.privateKey.toByteArray(), null, null)
        }

        // SSH se connecte au proxy SlowDNS local
        val session = jsch.getSession(ssh.username, "127.0.0.1", proxyPort)
        session.setPassword(ssh.password)
        session.setConfig("StrictHostKeyChecking", "no")
        session.setConfig("PreferredAuthentications", "publickey,password")
        session.setConfig("compression.s2c", "zlib@openssh.com,zlib,none")
        session.setConfig("compression.c2s", "zlib@openssh.com,zlib,none")
        session.setConfig("compression_level", "9")

        KighmuLogger.info(TAG, "Connexion SSH (timeout 30s)...")
        session.connect(30000)
        jschSession = session

        KighmuLogger.info(TAG, "SSH connecte! Server: ${session.serverVersion}")
        KighmuLogger.info(TAG, "Algo: ${session.getConfig("kex")}")

        // SOCKS5 dynamique
        session.setPortForwardingL(LOCAL_SOCKS_PORT, "127.0.0.1", LOCAL_SOCKS_PORT)
        KighmuLogger.info(TAG, "Socks port $LOCAL_SOCKS_PORT")
        KighmuLogger.info(TAG, "=== SlowDNS TUNNEL ACTIF ===")
    }

    override suspend fun stop() {
        running = false
        jschSession?.disconnect()
        jschSession = null
        try { dnsttServerSocket?.close() } catch (_: Exception) {}
        engineScope.cancel()
        KighmuLogger.info(TAG, "SlowDNS arrete")
    }

    override suspend fun sendData(data: ByteArray, length: Int) {}
    override suspend fun receiveData(): ByteArray? = null
    override fun isRunning() = running && jschSession?.isConnected == true
}
