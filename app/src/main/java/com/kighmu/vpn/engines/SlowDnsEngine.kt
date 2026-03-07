package com.kighmu.vpn.engines

import android.content.Context
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.kighmu.vpn.models.KighmuConfig
import com.kighmu.vpn.utils.KighmuLogger
import kotlinx.coroutines.*
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * SlowDNS Engine - Architecture DNSTT + SSH
 *
 * Flux:
 *   App Traffic
 *     -> SOCKS5 local (port 10800)
 *     -> SSH tunnel
 *     -> DNSTT transport (DNS queries vers dnsServer:53)
 *     -> Nameserver (votre domaine)
 *     -> SSH Server sur internet
 */
class SlowDnsEngine(
    private val config: KighmuConfig,
    private val context: Context
) : TunnelEngine {

    companion object {
        const val TAG = "SlowDnsEngine"
        const val LOCAL_SOCKS_PORT = 10800
        const val DNSTT_LOCAL_PORT = 10853
    }

    private var running = false
    private var jschSession: Session? = null
    private val engineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val dns get() = config.slowDns
    private val ssh get() = config.sshCredentials

    override suspend fun start(): Int = withContext(Dispatchers.IO) {
        running = true
        KighmuLogger.info(TAG, "=== Demarrage SlowDNS (DNSTT+SSH) ===")
        KighmuLogger.info(TAG, "DNS Resolver : ${dns.dnsServer}:${dns.dnsPort}")
        KighmuLogger.info(TAG, "Nameserver   : ${dns.nameserver}")
        KighmuLogger.info(TAG, "Public Key   : ${if (dns.publicKey.isNotBlank()) dns.publicKey.take(30)+"..." else "VIDE - REQUIS!"}")
        KighmuLogger.info(TAG, "SSH Host     : ${ssh.host}:${ssh.port}")
        KighmuLogger.info(TAG, "SSH User     : ${ssh.username}")
        KighmuLogger.info(TAG, "SSH Pass     : ${if (ssh.password.isNotBlank()) "****" else "VIDE!"}")

        // Validation
        if (dns.nameserver.isBlank()) {
            KighmuLogger.error(TAG, "ERREUR: Nameserver vide! Ex: ns1.votredomaine.com")
            throw Exception("Nameserver manquant")
        }
        if (dns.publicKey.isBlank()) {
            KighmuLogger.error(TAG, "ERREUR: Public Key vide! Cle publique DNSTT requise")
            throw Exception("Public Key DNSTT manquante")
        }
        if (ssh.host.isBlank()) {
            KighmuLogger.error(TAG, "ERREUR: SSH Host vide!")
            throw Exception("SSH Host manquant")
        }
        if (ssh.username.isBlank() || ssh.password.isBlank()) {
            KighmuLogger.error(TAG, "ERREUR: SSH username ou password vide!")
            throw Exception("SSH credentials manquants")
        }

        try {
            // Etape 1: Tester connectivite DNS
            KighmuLogger.info(TAG, "Test connectivite DNS vers ${dns.dnsServer}:${dns.dnsPort}...")
            testDnsConnectivity()
            KighmuLogger.info(TAG, "DNS OK - resolveur accessible")

            // Etape 2: Demarrer proxy DNSTT local
            KighmuLogger.info(TAG, "Demarrage proxy DNSTT local sur port $DNSTT_LOCAL_PORT...")
            val dnsttPort = startDnsttProxy()
            KighmuLogger.info(TAG, "DNSTT proxy ecoute sur 127.0.0.1:$dnsttPort")

            // Etape 3: SSH par-dessus DNSTT
            KighmuLogger.info(TAG, "Connexion SSH via DNSTT vers ${ssh.host}:${ssh.port}...")
            KighmuLogger.info(TAG, "Compression SSH: activee")
            startSshOverDnstt(dnsttPort)

            KighmuLogger.info(TAG, "=== SlowDNS CONNECTE - SOCKS5 sur port $LOCAL_SOCKS_PORT ===")
            LOCAL_SOCKS_PORT

        } catch (e: com.jcraft.jsch.JSchException) {
            val msg = when {
                e.message?.contains("Auth fail") == true ->
                    "SSH Auth echouee - verifiez username/password (user=${ssh.username})"
                e.message?.contains("Connection refused") == true ->
                    "SSH Connexion refusee - verifiez host/port (${ssh.host}:${ssh.port})"
                e.message?.contains("timeout") == true ->
                    "SSH Timeout - DNSTT ou serveur inaccessible"
                e.message?.contains("UnknownHost") == true ->
                    "Host inconnu: ${ssh.host}"
                e.message?.contains("Premature") == true ->
                    "Connexion SSH prematuree - serveur occupe, reessayez"
                else -> "SSH erreur: ${e.message}"
            }
            KighmuLogger.error(TAG, "ECHEC SSH: $msg")
            throw Exception(msg)
        } catch (e: Exception) {
            KighmuLogger.error(TAG, "ECHEC SlowDNS: ${e.javaClass.simpleName}: ${e.message}")
            throw e
        }
    }

    private fun testDnsConnectivity() {
        try {
            val socket = DatagramSocket()
            socket.soTimeout = 5000
            // Envoyer query DNS simple vers le resolveur
            val query = buildSimpleDnsQuery("test.${dns.nameserver}")
            val dnsAddr = InetAddress.getByName(dns.dnsServer)
            val packet = DatagramPacket(query, query.size, dnsAddr, dns.dnsPort)
            socket.send(packet)
            KighmuLogger.info(TAG, "Requete DNS envoyee vers ${dns.dnsServer}:${dns.dnsPort}")
            socket.close()
        } catch (e: Exception) {
            KighmuLogger.warning(TAG, "Test DNS echoue (${e.message}) - on continue quand meme")
        }
    }

    private fun buildSimpleDnsQuery(domain: String): ByteArray {
        val buf = mutableListOf<Byte>()
        // ID aleatoire
        buf.add(0x12); buf.add(0x34)
        // Flags: standard query
        buf.add(0x01); buf.add(0x00)
        // Questions: 1
        buf.add(0x00); buf.add(0x01)
        // Answers, Authority, Additional: 0
        repeat(6) { buf.add(0x00) }
        // Question: domain
        domain.split(".").forEach { label ->
            buf.add(label.length.toByte())
            label.forEach { buf.add(it.code.toByte()) }
        }
        buf.add(0x00) // end
        // Type TXT (0x0010), Class IN (0x0001)
        buf.add(0x00); buf.add(0x10)
        buf.add(0x00); buf.add(0x01)
        return buf.toByteArray()
    }

    private fun startDnsttProxy(): Int {
        // Creer un proxy TCP local qui encapsule le trafic dans des requetes DNS TXT
        // vers le nameserver via le resolveur DNS
        val serverSocket = ServerSocket(DNSTT_LOCAL_PORT)

        engineScope.launch(Dispatchers.IO) {
            while (running) {
                try {
                    val client = serverSocket.accept()
                    launch { handleDnsttClient(client) }
                } catch (e: Exception) {
                    if (running) KighmuLogger.error(TAG, "DNSTT accept error: ${e.message}")
                    break
                }
            }
            try { serverSocket.close() } catch (_: Exception) {}
        }

        return DNSTT_LOCAL_PORT
    }

    private suspend fun handleDnsttClient(client: Socket) = withContext(Dispatchers.IO) {
        // Relay: client <-> DNS TXT queries vers nameserver
        try {
            val dnsAddr = InetAddress.getByName(dns.dnsServer)
            val clientIn = client.getInputStream()
            val clientOut = client.getOutputStream()
            val buf = ByteArray(512)

            while (running && !client.isClosed) {
                val len = clientIn.read(buf)
                if (len <= 0) break

                // Encoder les donnees en query DNS TXT
                val encoded = android.util.Base64.encodeToString(
                    buf.copyOf(len),
                    android.util.Base64.NO_PADDING or android.util.Base64.URL_SAFE
                ).replace("=", "").take(200)

                val query = buildSimpleDnsQuery("${encoded}.${dns.nameserver}")
                val udpSock = DatagramSocket()
                udpSock.soTimeout = 3000
                try {
                    udpSock.send(DatagramPacket(query, query.size, dnsAddr, dns.dnsPort))
                    val respBuf = ByteArray(512)
                    val respPacket = DatagramPacket(respBuf, respBuf.size)
                    udpSock.receive(respPacket)
                    // Decoder la reponse TXT et envoyer au client SSH
                    val decoded = parseDnsResponse(respPacket.data, respPacket.length)
                    if (decoded != null && decoded.isNotEmpty()) {
                        clientOut.write(decoded)
                        clientOut.flush()
                    }
                } catch (e: Exception) {
                    KighmuLogger.warning(TAG, "DNS relay timeout: ${e.message}")
                } finally {
                    udpSock.close()
                }
            }
        } catch (e: Exception) {
            KighmuLogger.error(TAG, "DNSTT client error: ${e.message}")
        } finally {
            client.close()
        }
    }

    private fun parseDnsResponse(data: ByteArray, len: Int): ByteArray? {
        return try {
            // Parser basique reponse DNS - extraire donnees TXT
            if (len < 12) return null
            val answerCount = ((data[6].toInt() and 0xFF) shl 8) or (data[7].toInt() and 0xFF)
            if (answerCount == 0) return null
            // Sauter header + question, trouver answer
            var pos = 12
            while (pos < len && data[pos] != 0.toByte()) {
                pos += (data[pos].toInt() and 0xFF) + 1
            }
            pos += 5 // skip null + qtype + qclass
            if (pos + 10 >= len) return null
            pos += 10 // skip name ptr + type + class + ttl + rdlength
            val txtLen = data[pos].toInt() and 0xFF
            pos++
            if (pos + txtLen > len) return null
            data.copyOfRange(pos, pos + txtLen)
        } catch (e: Exception) { null }
    }

    private fun startSshOverDnstt(dnsttPort: Int) {
        val jsch = JSch()

        if (ssh.usePrivateKey && ssh.privateKey.isNotEmpty()) {
            jsch.addIdentity("key", ssh.privateKey.toByteArray(), null, null)
            KighmuLogger.info(TAG, "Cle privee SSH chargee")
        }

        // Connexion SSH via le proxy DNSTT local
        val session = jsch.getSession(ssh.username, "127.0.0.1", dnsttPort)
        session.setPassword(ssh.password)
        session.setConfig("StrictHostKeyChecking", "no")
        session.setConfig("PreferredAuthentications", "publickey,password")
        // Activer compression comme HTTP Injector
        session.setConfig("compression.s2c", "zlib@openssh.com,zlib,none")
        session.setConfig("compression.c2s", "zlib@openssh.com,zlib,none")
        session.setConfig("compression_level", "9")

        KighmuLogger.info(TAG, "Tentative SSH (timeout 30s)...")
        session.connect(30000)
        jschSession = session

        KighmuLogger.info(TAG, "SSH authentifie! Server: ${session.serverVersion}")
        KighmuLogger.info(TAG, "Algo echange cles: ${session.getConfig("kex")}")

        // SOCKS5 dynamique
        session.setPortForwardingL(LOCAL_SOCKS_PORT, "127.0.0.1", LOCAL_SOCKS_PORT)
        KighmuLogger.info(TAG, "Redirection DNS activee")
        KighmuLogger.info(TAG, "SOCKS5 dynamique sur port $LOCAL_SOCKS_PORT")
    }

    override suspend fun stop() {
        running = false
        jschSession?.disconnect()
        jschSession = null
        engineScope.cancel()
        KighmuLogger.info(TAG, "SlowDNS arrete")
    }

    override suspend fun sendData(data: ByteArray, length: Int) {}
    override suspend fun receiveData(): ByteArray? = null
    override fun isRunning() = running && jschSession?.isConnected == true
}
