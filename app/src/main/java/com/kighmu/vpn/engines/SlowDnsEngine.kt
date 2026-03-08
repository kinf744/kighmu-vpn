package com.kighmu.vpn.engines

import android.content.Context
import com.trilead.ssh2.Connection
import com.trilead.ssh2.ConnectionInfo
import com.trilead.ssh2.crypto.CryptoWishList
import com.trilead.ssh2.transport.ProxyData
import com.kighmu.vpn.models.KighmuConfig
import com.kighmu.vpn.utils.KighmuLogger
import kotlinx.coroutines.*
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

class SlowDnsEngine(
    private val config: KighmuConfig,
    private val context: Context,
    private val vpnService: android.net.VpnService? = null
) : TunnelEngine {

    companion object {
        const val TAG = "SlowDnsEngine"
        const val LOCAL_SOCKS_PORT = 10800
        const val DNSTT_PORT = 7000       // dnstt expose SOCKS5 ici
        const val VPN_ADDRESS = "10.0.0.2"
        const val VPN_PREFIX = "24"
        const val MTU = 1500
    }

    private var running = false
    private var sshConnection: Connection? = null
    private var dnsttProcess: Process? = null
    private val engineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val dns get() = config.slowDns
    private val ssh get() = config.sshCredentials

    override suspend fun start(): Int = withContext(Dispatchers.IO) {
        running = true
        KighmuLogger.info(TAG, "=== Demarrage SlowDNS ===")
        KighmuLogger.info(TAG, "DNS: ${dns.dnsServer}:${dns.dnsPort}")
        KighmuLogger.info(TAG, "Nameserver: ${dns.nameserver}")
        KighmuLogger.info(TAG, "SSH: ${ssh.host}:${ssh.port} / ${ssh.username}")

        if (dns.nameserver.isBlank()) throw Exception("Nameserver manquant")
        if (dns.publicKey.isBlank()) throw Exception("Public Key manquante")

        val dnsttBin = extractDnsttBinary()
        startDnsttProcess(dnsttBin)

        KighmuLogger.info(TAG, "Attente dnstt (15s)...")
        delay(15000)

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
        KighmuLogger.info(TAG, "dnstt path: ${binFile.absolutePath}")
        KighmuLogger.info(TAG, "dnstt existe: ${binFile.exists()}, taille: ${binFile.length()}")
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

    private fun startSsh() {
        KighmuLogger.info(TAG, "Connexion SSH via SOCKS5 dnstt (127.0.0.1:$DNSTT_PORT) -> ${ssh.host}:${ssh.port}")

        // Architecture SSH Custom:
        // trilead se connecte au VRAI serveur SSH (host:port)
        // MAIS via le proxy SOCKS5 que dnstt expose sur 127.0.0.1:7000
        val conn = Connection(ssh.host, ssh.port)

        // Proxy SOCKS5 = dnstt local port
        conn.setProxyData(object : ProxyData {
            override fun openConnection(hostname: String, port: Int): Socket {
                KighmuLogger.info(TAG, "SOCKS5 connect via dnstt: $hostname:$port")
                val sock = Socket()
                sock.connect(InetSocketAddress("127.0.0.1", DNSTT_PORT), 30000)

                val out = sock.getOutputStream()
                val inp = sock.getInputStream()

                // SOCKS5 handshake
                out.write(byteArrayOf(0x05, 0x01, 0x00)) // version, 1 method, no auth
                out.flush()
                inp.read(); inp.read() // server choice

                // SOCKS5 CONNECT request
                val hostBytes = hostname.toByteArray()
                val req = byteArrayOf(
                    0x05, 0x01, 0x00, 0x03,             // ver, cmd CONNECT, rsv, atyp DOMAINNAME
                    hostBytes.size.toByte()
                ) + hostBytes + byteArrayOf(
                    (port shr 8).toByte(),
                    (port and 0xFF).toByte()
                )
                out.write(req)
                out.flush()

                // Read response (10 bytes min)
                val resp = ByteArray(10)
                var total = 0
                while (total < 10) {
                    val n = inp.read(resp, total, 10 - total)
                    if (n < 0) break
                    total += n
                }
                if (resp[1] != 0x00.toByte()) {
                    throw Exception("SOCKS5 erreur: ${resp[1]}")
                }
                KighmuLogger.info(TAG, "SOCKS5 tunnel etabli vers $hostname:$port")
                return sock
            }
        })

        // Algos identiques SSH Custom
        val wishList = CryptoWishList()
        wishList.kexAlgos      = arrayOf("diffie-hellman-group-exchange-sha256", "diffie-hellman-group14-sha1")
        wishList.c2s_enc_algos = arrayOf("aes256-ctr", "aes128-ctr", "aes256-cbc", "aes128-cbc")
        wishList.s2c_enc_algos = arrayOf("aes256-ctr", "aes128-ctr", "aes256-cbc", "aes128-cbc")
        wishList.c2s_mac_algos = arrayOf("hmac-sha2-512", "hmac-sha2-256", "hmac-sha1")
        wishList.s2c_mac_algos = arrayOf("hmac-sha2-512", "hmac-sha2-256", "hmac-sha1")

        val connInfo: ConnectionInfo = conn.connect(null, 120000, 120000, wishList)
        KighmuLogger.info(TAG, "SSH connecte! banner=${connInfo.serverVersion} kex=${connInfo.keyExchangeAlgorithm} cipher=${connInfo.clientToServerCryptoAlgorithm}")

        val authenticated = conn.authenticateWithPassword(ssh.username, ssh.password)
        if (!authenticated) throw Exception("SSH auth echoue pour ${ssh.username}")
        KighmuLogger.info(TAG, "SSH authentifie!")

        // Dynamic SOCKS5 forwarding sur LOCAL_SOCKS_PORT pour tun2socks
        conn.createDynamicPortForwarder(LOCAL_SOCKS_PORT)
        KighmuLogger.info(TAG, "Dynamic SOCKS5 forwarding actif sur port $LOCAL_SOCKS_PORT")

        sshConnection = conn
    }

    override suspend fun stop() {
        running = false
        try { Tun2Socks.terminateTun2Socks() } catch (_: Exception) {}
        try { sshConnection?.close() } catch (_: Exception) {}
        sshConnection = null
        try { dnsttProcess?.destroy() } catch (_: Exception) {}
        dnsttProcess = null
        engineScope.cancel()
        KighmuLogger.info(TAG, "SlowDNS arrete")
    }

    override suspend fun sendData(data: ByteArray, length: Int) {}
    override suspend fun receiveData(): ByteArray? = null
    override fun isRunning() = running && sshConnection?.isAuthenticationComplete == true
}
