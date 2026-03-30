package dev.epro.tun2socks

import android.os.ParcelFileDescriptor
import android.util.Log

object Tun2Socks {
    private var loaded = false
    
    init {
        try {
            System.loadLibrary("tun2socks_ssc")
            loaded = true
        } catch (e: Throwable) {
            Log.e("Tun2Socks", "Failed to load libtun2socks_ssc: ${e.message}")
        }
    }

    val isAvailable get() = loaded

    // tunFd, mtu, socksAddr, udpgwAddr, dns, netifAddr, netmask, loglevel
    private external fun runTun2Socks(
        tunFd: Int, mtu: Int,
        socksAddr: String, udpgwAddr: String,
        dns: String, netifAddr: String,
        netmask: String, loglevel: Int
    ): Int

    private external fun terminateTun2Socks(): Int

    fun start(
        tunFd: Int, mtu: Int = 1500,
        socksAddr: String,
        udpgwAddr: String = "127.0.0.1:7300",
        dns: String = "8.8.8.8",
        netifAddr: String = "10.0.0.2",
        netmask: String = "255.255.255.0",
        loglevel: Int = 3
    ): Int {
        return runTun2Socks(tunFd, mtu, socksAddr, udpgwAddr, dns, netifAddr, netmask, loglevel)
    }

    fun stop() {
        try { terminateTun2Socks() } catch (_: Exception) {}
    }

    // Callback depuis lib native
    @JvmStatic
    fun logTun2Socks(level: String, channel: String, message: String) {
        Log.d("Tun2Socks", "[$channel] $message")
    }
}
