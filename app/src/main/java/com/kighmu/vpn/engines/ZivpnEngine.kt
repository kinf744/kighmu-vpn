package com.kighmu.vpn.engines

import android.content.Context
import com.kighmu.vpn.models.KighmuConfig
import com.kighmu.vpn.models.SshSslConfig
import com.kighmu.vpn.utils.KighmuLogger
import java.io.File
import java.io.IOException

class ZivpnEngine(
    private val config: KighmuConfig,
    private val context: Context
) : TunnelEngine {
    private val TAG = "ZivpnEngine"

    override suspend fun start(): Int {
        KighmuLogger.info(TAG, "Starting ZIVPN UDP engine")
        val host = config.zivpnHost
        val password = config.zivpnPassword
        if (host.isEmpty() || password.isEmpty()) {
            KighmuLogger.error(TAG, "ZIVPN host or password missing")
            throw IllegalArgumentException("ZIVPN host/password not set")
        }
        try {
            val binary = File(context.filesDir, "libzib.so")
            if (!binary.exists()) {
                KighmuLogger.error(TAG, "libzib.so not found in filesDir")
                throw IllegalStateException("Binary libzib.so missing")
            }
            binary.setExecutable(true)
            val cmd = arrayOf(
                binary.absolutePath,
                "-h", host,
                "-p", password,
                "-u", "udp"
            )
            KighmuLogger.info(TAG, "Executing: ${cmd.joinToString(" ")}")
            val proc = ProcessBuilder(*cmd)
                .directory(context.filesDir)
                .redirectErrorStream(true)
                .start()
            proc.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line -> KighmuLogger.info(TAG, line) }
            }
            proc.waitFor()
            // ZIVPN binary does not expose a local SOCKS port; we just return 0
            return 0
        } catch (e: IOException) {
            KighmuLogger.error(TAG, "ZIVPN start error: ${e.message}")
            throw e
        }
    }

    override suspend fun stop() {
        KighmuLogger.info(TAG, "Stopping ZIVPN engine (no persistent process) ")
    }

    override suspend fun sendData(data: ByteArray, length: Int) {}
    override suspend fun receiveData(): ByteArray? = null
    override fun isRunning(): Boolean = false
    override fun startTun2Socks(fd: Int) {}
}
