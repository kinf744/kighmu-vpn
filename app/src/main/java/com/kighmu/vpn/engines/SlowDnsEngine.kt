package com.kighmu.vpn.engines

import android.content.Context
import com.trilead.ssh2.Connection
import com.trilead.ssh2.ConnectionInfo
import com.kighmu.vpn.models.KighmuConfig
import com.kighmu.vpn.utils.KighmuLogger
import kotlinx.coroutines.*
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

class SlowDnsEngine(
    private val config: KighmuConfig,
    private val context: Context,
    private val vpnService: android.net.VpnService? = null
) : TunnelEngine {

    companion object {
        const val TAG = "SlowDnsEngine"
        const val LOCAL_SOCKS_PORT = 10800
        const val DNSTT_PORT = 7000
        const val SSH_RELAY_PORT = 7001   // port local ou on relaie SSH via SOCKS5
        const val VPN_ADDRESS = "10.0.0.2"
        const val VPN_PREFIX = "24"
        const val MTU = 1500
    }

    private var running = false
    private var sshConnection: Connection? = null
    private var dnsttProcess: Process? = null
    private var relaySocket: Socket? = null
    private var relayServer: ServerSocket? = null
    private val engineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val dns get() = config.slowDns
    private val ssh get() = config.sshCredentials

    override suspend fun start(): Int = withContext(Dispatchers.IO) {
        running = true
        KighmuLogger.info(TAG, "=== Demarrage SlowDNS ===")
        KighmuLogger.info(TAG, "DNS: ${dns.dnsServer}:${dns.dnsPort}")
        KighmuLogger.info(TAG, "SSH: ${ssh.host}:${ssh.port} / ${ssh.username}")

        if (dns.nameserver.isBlank()) throw Exception("Nameserver manquant")
        if (dns.publicKey.isBlank()) throw Exception("Public Key manquante")

        val dnsttBin = extractDnsttBinary()
        startDnsttProcess(dnsttBin)

        KighmuLogger.info(TAG, "Attente dnstt (15s)...")
        delay(15000)

        // Ouvrir tunnel SOCKS5 vers serveur SSH
        startSshRelay()

        // Connecter SSH via le relay local
        startSsh()
        KighmuLogger.info(TAG, "=== SSH connecte, SOCKS5 port $LOCAL_SOCKS_PORT ===")

        LOCAL_SOCKS_PORT
    }

    fun startTun2Socks(fd: Int) {
        KighmuLogger.info(TAG, "Demarrage tun2socks (tunFd=$fd)")
        engineScope.launch(Dispatchers.IO) {
            try {
                Tun2Socks.runTun2Socks(
                    tunFd               = fd,
                    mtu                 = MTU,
                    ip                  = VPN_ADDRESS,
                    prefix              = VPN_PREFIX,
                    socksServerAddress  = "127.0.0.1:$LOCAL_SOCKS_PORT",
                    udpgwServerAddress  = "127.0.0.1:7300",
                    udpgwTransparentDNS = true
                )
            } catch (e: Exception) {
                KighmuLogger.error(TAG, "tun2socks error: ${e.message}")
            }
        }
    }

    private fun extractDnsttBinary(): File {
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val binFile = File(nativeDir, "libdnstt.so")
        KighmuLogger.info(TAG, "dnstt path: ${binFile.absolutePath}, existe: ${binFile.exists()}")
        if (!binFile.exists()) throw Exception("libdnstt.so introuvable dans $nativeDir")
        return binFile
    }

    private fun startDnsttProcess(bin: File) {
        val cmd = listOf(
            bin.absolutePath,
            "-udp", "${dns.dnsServer}:${dns.dnsPort}",
            "-pubkey", dns.publicKey.trim(),
            dns.nameserver,
            "127.0.0.1:$DNSTT_PORT"
        )
        KighmuLogger.info(TAG, "Lancement dnstt: ${cmd.joinToString(" ")}")

        val pb = ProcessBuilder(cmd).redirectErrorStream(true)
        pb.environment()["HOME"]   = context.filesDir.absolutePath
        pb.environment()["TMPDIR"] = context.cacheDir.absolutePath
        pb.directory(context.filesDir)

        val process = pb.start()
        dnsttProcess = process

        Thread {
            try {
                process.inputStream.bufferedReader().forEachLine { line ->
                    KighmuLogger.info(TAG, "dnstt: $line")
                }
            } catch (e: Exception) {
                KighmuLogger.error(TAG, "dnstt stdout: ${e.message}")
            }
        }.start()

        Thread.sleep(2000)
        try {
            val exitVal = process.exitValue()
            throw Exception("dnstt crashed (exit=$exitVal)")
        } catch (_: IllegalThreadStateException) {
            KighmuLogger.info(TAG, "dnstt vivant OK")
        }
    }

    /**
     * Ouvre une connexion SOCKS5 via dnstt vers le vrai serveur SSH
     * et l'expose sur un ServerSocket local (SSH_RELAY_PORT)
     * pour que trilead puisse s'y connecter normalement
     */
    private fun startSshRelay() {
        KighmuLogger.info(TAG, "Ouverture tunnel SOCKS5: dnstt:$DNSTT_PORT -> ${ssh.host}:${ssh.port}")

        // Connecter via SOCKS5 au vrai serveur SSH
        val sock = connectViaSocks5("127.0.0.1", DNSTT_PORT, ssh.host, ssh.port)
        relaySocket = sock
        KighmuLogger.info(TAG, "SOCKS5 tunnel etabli vers ${ssh.host}:${ssh.port}")

        // Exposer via un ServerSocket local pour trilead
        val server = ServerSocket(SSH_RELAY_PORT)
        relayServer = server

        engineScope.launch(Dispatchers.IO) {
            val client = server.accept()
            server.close()
            KighmuLogger.info(TAG, "trilead connecte au relay local")

            // Bidirectionnel relay
            val j1 = launch {
                try {
                    val buf = ByteArray(8192)
                    val inp = client.getInputStream()
                    val out = sock.getOutputStream()
                    var n: Int
                    while (inp.read(buf).also { n = it } > 0) {
                        out.write(buf, 0, n); out.flush()
                    }
                } catch (_: Exception) {}
            }
            val j2 = launch {
                try {
                    val buf = ByteArray(8192)
                    val inp = sock.getInputStream()
                    val out = client.getOutputStream()
                    var n: Int
                    while (inp.read(buf).also { n = it } > 0) {
                        out.write(buf, 0, n); out.flush()
                    }
                } catch (_: Exception) {}
            }
            j1.join(); j2.cancel()
            client.close()
        }
    }

    private fun connectViaSocks5(proxyHost: String, proxyPort: Int, targetHost: String, targetPort: Int): Socket {
        val sock = Socket()
        sock.connect(InetSocketAddress(proxyHost, proxyPort), 30000)
        val out = sock.getOutputStream()
        val inp = sock.getInputStream()

        // Handshake: version 5, 1 method, no auth
        out.write(byteArrayOf(0x05, 0x01, 0x00))
        out.flush()
        val serverChoice = ByteArray(2)
        inp.read(serverChoice)
        KighmuLogger.info(TAG, "SOCKS5 handshake: ${serverChoice[0]} ${serverChoice[1]}")

        // CONNECT request avec domain name
        val hostBytes = targetHost.toByteArray(Charsets.US_ASCII)
        val req = byteArrayOf(0x05, 0x01, 0x00, 0x03, hostBytes.size.toByte()) +
                  hostBytes +
                  byteArrayOf((targetPort shr 8).toByte(), (targetPort and 0xFF).toByte())
        out.write(req)
        out.flush()

        // Lire reponse (au moins 10 bytes)
        val resp = ByteArray(256)
        var total = 0
        // Lire minimum 4 bytes pour connaitre le type d'adresse
        while (total < 4) { total += inp.read(resp, total, 4 - total) }
        val atyp = resp[3].toInt()
        val remaining = when (atyp) {
            0x01 -> 4 + 2  // IPv4 + port
            0x03 -> inp.read().also { resp[4] = it.toByte() } + 2  // domain len + port
            0x04 -> 16 + 2 // IPv6 + port
            else -> 2
        }
        while (total < 4 + remaining) { total += inp.read(resp, total, 4 + remaining - total) }

        if (resp[1] != 0x00.toByte()) {
            throw Exception("SOCKS5 CONNECT echoue: code=${resp[1]}")
        }
        KighmuLogger.info(TAG, "SOCKS5 CONNECT OK vers $targetHost:$targetPort")
        return sock
    }

    private fun startSsh() {
        KighmuLogger.info(TAG, "Connexion SSH trilead -> relay local 127.0.0.1:$SSH_RELAY_PORT")

        // trilead se connecte au relay local (qui pointe vers le vrai SSH via SOCKS5/dnstt)
        val conn = Connection("127.0.0.1", SSH_RELAY_PORT)
        val connInfo: ConnectionInfo = conn.connect(null, 120000, 120000)
        KighmuLogger.info(TAG, "SSH connecte! banner=${connInfo.serverVersion} kex=${connInfo.keyExchangeAlgorithm} cipher=${connInfo.clientToServerCryptoAlgorithm}")

        val authenticated = conn.authenticateWithPassword(ssh.username, ssh.password)
        if (!authenticated) throw Exception("SSH auth echoue pour ${ssh.username}")
        KighmuLogger.info(TAG, "SSH authentifie!")

        // Dynamic SOCKS5 forwarding pour tun2socks
        conn.createDynamicPortForwarder(LOCAL_SOCKS_PORT)
        KighmuLogger.info(TAG, "Dynamic SOCKS5 forwarding actif sur $LOCAL_SOCKS_PORT")

        sshConnection = conn
    }

    override suspend fun stop() {
        running = false
        try { Tun2Socks.terminateTun2Socks() } catch (_: Exception) {}
        try { sshConnection?.close() } catch (_: Exception) {}
        sshConnection = null
        try { relaySocket?.close() } catch (_: Exception) {}
        relaySocket = null
        try { relayServer?.close() } catch (_: Exception) {}
        relayServer = null
        try { dnsttProcess?.destroy() } catch (_: Exception) {}
        dnsttProcess = null
        engineScope.cancel()
        KighmuLogger.info(TAG, "SlowDNS arrete")
    }

    override suspend fun sendData(data: ByteArray, length: Int) {}
    override suspend fun receiveData(): ByteArray? = null
    override fun isRunning() = running && sshConnection?.isAuthenticationComplete == true
}
