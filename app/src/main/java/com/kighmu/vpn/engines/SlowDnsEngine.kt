package com.kighmu.vpn.engines

import android.content.Context
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.SocketFactory
import com.kighmu.vpn.models.KighmuConfig
import com.kighmu.vpn.utils.KighmuLogger
import kotlinx.coroutines.*
import org.xbill.DNS.*
import org.xbill.DNS.Record
import java.io.*
import java.net.*

/**
 * SlowDNS Engine - Vrai protocole DNSTT via dnsjava
 * SSH passe par des queries DNS TXT vers le nameserver
 */
class SlowDnsEngine(
    private val config: KighmuConfig,
    private val context: Context,
    private val vpnService: android.net.VpnService? = null
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

    private fun protectSocketFd(socket: Socket): Boolean {
        if (vpnService == null) return false
        val r = vpnService.protect(socket)
        if (r) return true
        return try {
            val m = socket.javaClass.getMethod("getFileDescriptor\$")
            val fd = m.invoke(socket) as? java.io.FileDescriptor
            if (fd != null) {
                val f = java.io.FileDescriptor::class.java.getDeclaredField("descriptor")
                f.isAccessible = true
                vpnService.protect(f.getInt(fd))
            } else false
        } catch (_: Exception) { false }
    }

    private fun createProtectedSocket(host: String, port: Int): Socket {
        val physicalIface = try {
            NetworkInterface.getNetworkInterfaces()?.toList()
                ?.filter { it.isUp && !it.isLoopback && !it.isVirtual }
                ?.filter { !it.name.startsWith("tun") && !it.name.startsWith("ppp") }
                ?.firstOrNull { iface ->
                    iface.inetAddresses?.toList()?.any { it is Inet4Address && !it.isLoopbackAddress } == true
                }
        } catch (_: Exception) { null }

        val s = Socket()
        if (physicalIface != null) {
            try {
                val addr = physicalIface.inetAddresses?.toList()
                    ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
                if (addr != null) {
                    s.bind(InetSocketAddress(addr, 0))
                    KighmuLogger.info(TAG, "Socket lie a ${addr.hostAddress} (${physicalIface.name})")
                }
            } catch (e: Exception) {
                KighmuLogger.warning(TAG, "Bind: ${e.message}")
            }
        }
        vpnService?.protect(s)
        s.connect(InetSocketAddress(host, port), 20000)
        protectSocketFd(s)
        s.soTimeout = 0
        s.tcpNoDelay = true
        return s
    }

    override suspend fun start(): Int = withContext(Dispatchers.IO) {
        running = true
        KighmuLogger.info(TAG, "=== Demarrage SlowDNS ===")
        KighmuLogger.info(TAG, "DNS : ${dns.dnsServer}:${dns.dnsPort}")
        KighmuLogger.info(TAG, "Nameserver : ${dns.nameserver}")
        KighmuLogger.info(TAG, "SSH : ${ssh.host}:${ssh.port} / ${ssh.username}")

        if (dns.nameserver.isBlank()) throw Exception("Nameserver manquant")
        if (dns.publicKey.isBlank()) throw Exception("Public Key manquante")
        if (ssh.host.isBlank()) throw Exception("SSH Host manquant")
        if (ssh.username.isBlank() || ssh.password.isBlank()) throw Exception("SSH credentials manquants")

        try {
            // Proxy SOCKS5 local - relay via DNSTT
            val server = ServerSocket(0)
            proxyServer = server
            val proxyPort = server.localPort
            KighmuLogger.info(TAG, "SlowDns running - DNSTT proxy local:$proxyPort")

            Thread {
                while (running) {
                    try {
                        val client = server.accept()
                        Thread { handleSocks5(client) }.start()
                    } catch (e: Exception) {
                        if (running) KighmuLogger.error(TAG, "Proxy: ${e.message}")
                        break
                    }
                }
            }.start()

            startSsh(proxyPort)
            KighmuLogger.info(TAG, "=== SlowDNS CONNECTE port $LOCAL_SOCKS_PORT ===")
            LOCAL_SOCKS_PORT

        } catch (e: com.jcraft.jsch.JSchException) {
            val msg = when {
                e.message?.contains("Auth fail") == true -> "Auth SSH echouee (${ssh.username})"
                e.message?.contains("timeout") == true -> "Timeout - verifiez nameserver et credentials"
                else -> e.message ?: "Erreur SSH"
            }
            KighmuLogger.error(TAG, "ECHEC SSH: $msg")
            throw Exception(msg)
        } catch (e: Exception) {
            KighmuLogger.error(TAG, "ECHEC: ${e.javaClass.simpleName}: ${e.message}")
            throw e
        }
    }

    private fun handleSocks5(client: Socket) {
        try {
            client.soTimeout = 0
            val inp = client.getInputStream()
            val out = client.getOutputStream()

            val ver = inp.read(); if (ver != 5) { client.close(); return }
            val n = inp.read(); repeat(n) { inp.read() }
            out.write(byteArrayOf(5, 0)); out.flush()

            inp.read() // VER
            val cmd = inp.read(); inp.read() // CMD, RSV
            val atyp = inp.read()
            if (cmd != 1) { client.close(); return }

            val targetHost: String = when (atyp) {
                1 -> { val a = ByteArray(4); repeat(4) { i -> a[i] = inp.read().toByte() }
                    "${a[0].toInt() and 0xFF}.${a[1].toInt() and 0xFF}.${a[2].toInt() and 0xFF}.${a[3].toInt() and 0xFF}" }
                3 -> { val len = inp.read(); val d = ByteArray(len); repeat(len) { i -> d[i] = inp.read().toByte() }; String(d) }
                else -> { client.close(); return }
            }
            val targetPort = (inp.read() shl 8) or inp.read()
            KighmuLogger.info(TAG, "DNSTT CONNECT -> $targetHost:$targetPort")

            // Connexion via DNSTT (DNS queries vers nameserver)
            val dnsttStream = DnsttStream(targetHost, targetPort)
            dnsttStream.connect()
            KighmuLogger.info(TAG, "DNSTT stream etabli")

            out.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0)); out.flush()
            KighmuLogger.info(TAG, "SOCKS5 reply envoye - relay demarre")

            var sent = 0L; var recv = 0L
            val t1 = Thread {
                try {
                    val buf = ByteArray(1024)
                    while (running) {
                        val len = inp.read(buf); if (len < 0) break
                        dnsttStream.write(buf, len)
                        sent += len
                        if (sent <= 500) KighmuLogger.info(TAG, "-> DNS: ${len}b (total=${sent})")
                    }
                } catch (e: Exception) { KighmuLogger.error(TAG, "t1: ${e.message}") }
                dnsttStream.close()
            }
            val t2 = Thread {
                try {
                    while (running) {
                        val data = dnsttStream.read() ?: break
                        out.write(data); out.flush()
                        recv += data.size
                        if (recv <= 500) KighmuLogger.info(TAG, "<- DNS: ${data.size}b (total=${recv})")
                    }
                } catch (e: Exception) { KighmuLogger.error(TAG, "t2: ${e.message}") }
                client.close()
            }
            t1.start(); t2.start(); t1.join(); t2.join()
            KighmuLogger.info(TAG, "Session terminee sent=$sent recv=$recv")

        } catch (e: Exception) {
            KighmuLogger.error(TAG, "SOCKS5: ${e.message}")
            client.close()
        }
    }

    /**
     * Stream DNSTT - encapsule TCP dans des DNS TXT queries
     * Compatible avec le serveur dnstt (jech/dnstt)
     */
    inner class DnsttStream(private val targetHost: String, private val targetPort: Int) {
        private val sendQueue = java.util.concurrent.LinkedBlockingQueue<ByteArray>()
        private val recvQueue = java.util.concurrent.LinkedBlockingQueue<ByteArray>()
        private var connected = false
        private var dnsSocket: DatagramSocket? = null
        private val sessId = (Math.random() * 0xFFFFFF).toInt()
        private var seqSend = 0
        private var seqRecv = 0

        fun connect() {
            val dnsAddr = InetAddress.getByName(dns.dnsServer)
            val sock = DatagramSocket()
            dnsSocket = sock

            // Proteger le socket DNS aussi
            if (vpnService != null) {
                try {
                    val f = sock.javaClass.getMethod("getFileDescriptor\$")
                    val fd = f.invoke(sock) as? java.io.FileDescriptor
                    if (fd != null) {
                        val df = java.io.FileDescriptor::class.java.getDeclaredField("descriptor")
                        df.isAccessible = true
                        val r = vpnService.protect(df.getInt(fd))
                        KighmuLogger.info(TAG, "DNS socket protege = $r")
                    }
                } catch (e: Exception) {
                    KighmuLogger.warning(TAG, "DNS protect: ${e.message}")
                }
            }

            sock.soTimeout = 3000
            connected = true
            KighmuLogger.info(TAG, "DNSTT sessId=${sessId.toString(16)} -> $targetHost:$targetPort via ${dns.nameserver}")

            // Thread lecteur DNS
            Thread {
                val buf = ByteArray(512)
                while (connected) {
                    try {
                        val pkt = DatagramPacket(buf, buf.size)
                        sock.receive(pkt)
                        val data = parseDnsTxt(buf, pkt.length)
                        if (data != null && data.isNotEmpty()) recvQueue.offer(data)
                    } catch (_: SocketTimeoutException) {
                        // Keepalive
                        try { sendDnsQuery(sock, dnsAddr, "ka.${sessId.toString(16)}.${dns.nameserver}", null) }
                        catch (_: Exception) {}
                    } catch (e: Exception) {
                        if (connected) KighmuLogger.error(TAG, "DNS recv: ${e.message}")
                        break
                    }
                }
            }.start()
        }

        fun write(data: ByteArray, len: Int) {
            val dnsAddr = InetAddress.getByName(dns.dnsServer)
            val sock = dnsSocket ?: return
            val chunk = data.copyOf(len)
            val b32 = base32Encode(chunk)
            val seq = seqSend++
            val domain = "${b32.take(60)}.${seq.toString(16)}.${sessId.toString(16)}.${dns.nameserver}"
            sendDnsQuery(sock, dnsAddr, domain, chunk)
        }

        fun read(): ByteArray? {
            return try { recvQueue.poll(10, java.util.concurrent.TimeUnit.SECONDS) }
            catch (_: Exception) { null }
        }

        fun close() {
            connected = false
            dnsSocket?.close()
        }

        private fun sendDnsQuery(sock: DatagramSocket, addr: InetAddress, domain: String, data: ByteArray?) {
            val query = buildDnsQuery(domain)
            sock.send(DatagramPacket(query, query.size, addr, dns.dnsPort))
        }

        private fun buildDnsQuery(domain: String): ByteArray {
            val buf = mutableListOf<Byte>()
            val id = (Math.random() * 65535).toInt()
            buf.add((id shr 8).toByte()); buf.add((id and 0xFF).toByte())
            buf.add(0x01); buf.add(0x00)
            buf.add(0x00); buf.add(0x01)
            repeat(6) { buf.add(0x00) }
            domain.split(".").forEach { label ->
                if (label.isNotEmpty() && label.length <= 63) {
                    buf.add(label.length.toByte())
                    label.forEach { buf.add(it.code.toByte()) }
                }
            }
            buf.add(0x00)
            buf.add(0x00); buf.add(0x10) // TXT
            buf.add(0x00); buf.add(0x01) // IN
            return buf.toByteArray()
        }

        private fun parseDnsTxt(data: ByteArray, len: Int): ByteArray? {
            return try {
                if (len < 12) return null
                val ancount = ((data[6].toInt() and 0xFF) shl 8) or (data[7].toInt() and 0xFF)
                if (ancount == 0) return null
                var pos = 12
                while (pos < len && data[pos] != 0.toByte()) {
                    val l = data[pos].toInt() and 0xFF
                    if (l and 0xC0 == 0xC0) { pos += 2; break }
                    pos += l + 1
                }
                if (pos < len && data[pos] == 0.toByte()) pos++
                pos += 4
                if (pos >= len) return null
                if (data[pos].toInt() and 0xC0 == 0xC0) pos += 2
                else { while (pos < len && data[pos] != 0.toByte()) pos++; pos++ }
                pos += 10
                if (pos + 1 >= len) return null
                val txtLen = data[pos].toInt() and 0xFF; pos++
                if (pos + txtLen > len) return null
                base32Decode(String(data, pos, txtLen))
            } catch (_: Exception) { null }
        }

        private val B32 = "abcdefghijklmnopqrstuvwxyz234567"
        private fun base32Encode(d: ByteArray): String {
            val sb = StringBuilder(); var buf = 0; var bits = 0
            for (b in d) { buf = (buf shl 8) or (b.toInt() and 0xFF); bits += 8
                while (bits >= 5) { bits -= 5; sb.append(B32[(buf shr bits) and 31]) } }
            if (bits > 0) sb.append(B32[(buf shl (5 - bits)) and 31])
            return sb.toString()
        }
        private fun base32Decode(s: String): ByteArray {
            val r = mutableListOf<Byte>(); var buf = 0; var bits = 0
            for (c in s.lowercase()) { val i = B32.indexOf(c); if (i < 0) continue
                buf = (buf shl 5) or i; bits += 5
                if (bits >= 8) { bits -= 8; r.add((buf shr bits).toByte()); buf = buf and ((1 shl bits) - 1) } }
            return r.toByteArray()
        }
    }

    private fun startSsh(proxyPort: Int) {
        val jsch = JSch()
        if (ssh.usePrivateKey && ssh.privateKey.isNotEmpty()) {
            jsch.addIdentity("key", ssh.privateKey.toByteArray(), null, null)
        }
        val session = jsch.getSession(ssh.username, ssh.host, ssh.port)
        session.setPassword(ssh.password)
        session.setConfig("StrictHostKeyChecking", "no")
        session.setConfig("PreferredAuthentications", "password")
        session.setConfig("compression.s2c", "none")
        session.setConfig("compression.c2s", "none")
        session.setTimeout(0)
        session.setProxy(com.jcraft.jsch.ProxySOCKS5("127.0.0.1", proxyPort))
        KighmuLogger.info(TAG, "SSH connexion via DNSTT proxy (timeout 60s)...")
        session.connect(60000)
        jschSession = session
        KighmuLogger.info(TAG, "SSH connecte! ${session.serverVersion}")
        session.setPortForwardingL(LOCAL_SOCKS_PORT, "127.0.0.1", LOCAL_SOCKS_PORT)
        KighmuLogger.info(TAG, "Socks port $LOCAL_SOCKS_PORT")
    }

    override suspend fun stop() {
        running = false
        jschSession?.disconnect(); jschSession = null
        try { proxyServer?.close() } catch (_: Exception) {}
        engineScope.cancel()
        KighmuLogger.info(TAG, "SlowDNS arrete")
    }

    override suspend fun sendData(data: ByteArray, length: Int) {}
    override suspend fun receiveData(): ByteArray? = null
    override fun isRunning() = running && jschSession?.isConnected == true
}
