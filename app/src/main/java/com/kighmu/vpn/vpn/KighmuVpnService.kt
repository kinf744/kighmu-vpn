package com.kighmu.vpn.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.kighmu.vpn.R
import com.kighmu.vpn.config.ConfigManager
import com.kighmu.vpn.config.ConfigEncryption
import com.kighmu.vpn.engines.TunnelEngine
import com.kighmu.vpn.engines.TunnelEngineFactory
import com.kighmu.vpn.models.*
import com.kighmu.vpn.ui.activities.MainActivity
import com.kighmu.vpn.utils.KighmuLogger
import kotlinx.coroutines.*
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetAddress

class KighmuVpnService : VpnService() {

    companion object {
        const val TAG = "KighmuVpnService"
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "kighmu_vpn_channel"

        const val ACTION_START = "com.kighmu.vpn.START"
        const val ACTION_STOP = "com.kighmu.vpn.STOP"
        const val ACTION_RECONNECT = "com.kighmu.vpn.RECONNECT"

        // Broadcast actions for UI updates
        const val BROADCAST_STATUS = "com.kighmu.vpn.STATUS"
        const val EXTRA_STATUS = "status"
        const val EXTRA_MESSAGE = "message"

        var instance: KighmuVpnService? = null
        var currentStatus: ConnectionStatus = ConnectionStatus.DISCONNECTED
        var stats = VpnStats()
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var tunnelEngine: TunnelEngine? = null
    private var serviceJob = SupervisorJob()
    private var serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    private lateinit var configManager: ConfigManager
    private var currentConfig: KighmuConfig = KighmuConfig()
    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 5

    private var statsJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        configManager = ConfigManager(this)
        createNotificationChannel()
        Log.d(TAG, "VPN Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startVpn()
            ACTION_STOP -> stopVpn()
            ACTION_RECONNECT -> reconnect()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)

    override fun onDestroy() {
        instance = null
        stopVpn()
        serviceJob.cancel()
        super.onDestroy()
    }

    // ─── Start VPN ───────────────────────────────────────────────────────────

    private fun startVpn() {
        serviceScope.launch {
            try {
                updateStatus(ConnectionStatus.CONNECTING, "Loading configuration...")
                currentConfig = configManager.loadCurrentConfig()

                // Validate config
                val validationResult = ConfigEncryption.validateConfig(this@KighmuVpnService, currentConfig)
                when (validationResult) {
                    is ConfigEncryption.ValidationResult.Expired -> {
                        updateStatus(ConnectionStatus.ERROR, "Config expired")
                        stopSelf()
                        return@launch
                    }
                    is ConfigEncryption.ValidationResult.WrongDevice -> {
                        updateStatus(ConnectionStatus.ERROR, "Config locked to another device")
                        stopSelf()
                        return@launch
                    }
                    else -> {}
                }

                updateStatus(ConnectionStatus.CONNECTING, "Starting tunnel engine...")
                startForeground(NOTIFICATION_ID, buildNotification("Connecting..."))

                // Log config summary
                com.kighmu.vpn.utils.KighmuLogger.info("VpnService", "=== DÉMARRAGE VPN ===")
                com.kighmu.vpn.utils.KighmuLogger.info("VpnService", "Mode: ${currentConfig.tunnelMode.label}")
                com.kighmu.vpn.utils.KighmuLogger.info("VpnService", "Config: ${currentConfig.configName}")
                com.kighmu.vpn.utils.KighmuLogger.info("VpnService", "SSH host: '${currentConfig.sshCredentials.host}'")
                com.kighmu.vpn.utils.KighmuLogger.info("VpnService", "SSH port: ${currentConfig.sshCredentials.port}")
                com.kighmu.vpn.utils.KighmuLogger.info("VpnService", "SSH user: '${currentConfig.sshCredentials.username}'")
                com.kighmu.vpn.utils.KighmuLogger.info("VpnService", "SSH pass empty: ${currentConfig.sshCredentials.password.isEmpty()}")
                com.kighmu.vpn.utils.KighmuLogger.info("VpnService", "DNS server: '${currentConfig.slowDns.dnsServer}'")
                com.kighmu.vpn.utils.KighmuLogger.info("VpnService", "Nameserver: '${currentConfig.slowDns.nameserver}'")

                // Start tunnel engine
                val localPort = try {
                    tunnelEngine = TunnelEngineFactory.create(currentConfig, this@KighmuVpnService, this@KighmuVpnService)
                    tunnelEngine!!.start()
                } catch (e: Exception) {
                    com.kighmu.vpn.utils.KighmuLogger.error("VpnService", "Engine failed: ${e.javaClass.simpleName}: ${e.message}")
                    updateStatus(ConnectionStatus.ERROR, "Tunnel error: ${e.message}")
                    stopSelf()
                    return@launch
                }
                com.kighmu.vpn.utils.KighmuLogger.info("VpnService", "Engine démarré sur port $localPort")

                updateStatus(ConnectionStatus.CONNECTING, "Creating VPN interface...")

                // Build VPN interface
                vpnInterface = buildVpnInterface(localPort)

                if (vpnInterface == null) {
                    updateStatus(ConnectionStatus.ERROR, "Failed to create VPN interface")
                    tunnelEngine?.stop()
                    stopSelf()
                    return@launch
                }

                // Start traffic routing
                startTrafficRouting(vpnInterface!!, localPort)

                reconnectAttempts = 0
                stats = VpnStats(connectedAt = System.currentTimeMillis())
                updateStatus(ConnectionStatus.CONNECTED, "Connected via ${currentConfig.tunnelMode.label}")
                startStatsUpdate()

            } catch (e: Exception) {
                Log.e(TAG, "VPN start error", e)
                updateStatus(ConnectionStatus.ERROR, e.message ?: "Connection failed")
                handleReconnect()
            }
        }
    }

    // ─── Stop VPN ────────────────────────────────────────────────────────────

    private fun stopVpn() {
        statsJob?.cancel()
        serviceScope.launch {
            try {
                updateStatus(ConnectionStatus.DISCONNECTED, "Disconnected")
                tunnelEngine?.stop()
                tunnelEngine = null
                vpnInterface?.close()
                vpnInterface = null
                stats = VpnStats()
            } catch (e: Exception) {
                Log.e(TAG, "Stop error", e)
            }
        }
        stopForeground(true)
        stopSelf()
    }

    private fun reconnect() {
        serviceScope.launch {
            tunnelEngine?.stop()
            vpnInterface?.close()
            delay(2000)
            startVpn()
        }
    }

    // ─── VPN Interface Builder ────────────────────────────────────────────────

    private fun buildVpnInterface(localProxyPort: Int): ParcelFileDescriptor? {
        return try {
            val builder = Builder()
                .setSession("KIGHMU VPN")
                .addAddress("10.0.0.2", 24)
                .addRoute("0.0.0.0", 0)           // Route all traffic
                .addDnsServer("1.1.1.1")
                .addDnsServer("8.8.8.8")
                .setMtu(1500)
                .setBlocking(true)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setMetered(false)
            }

            // DNS leak protection: block direct DNS
            if (currentConfig.dnsLeakProtection) {
                builder.addRoute("8.8.8.8", 32)
                builder.addRoute("8.8.4.4", 32)
                builder.addRoute("1.1.1.1", 32)
            }

            // Bypass apps if needed (self-bypass)
            builder.addDisallowedApplication(packageName)

            builder.establish()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to establish VPN interface", e)
            null
        }
    }

    // ─── Traffic Routing ──────────────────────────────────────────────────────

    private fun startTrafficRouting(vpnFd: ParcelFileDescriptor, localPort: Int) {
        // In → Proxy
        serviceScope.launch {
            val inputStream = FileInputStream(vpnFd.fileDescriptor)
            val buffer = ByteArray(32768)
            while (isActive && currentStatus == ConnectionStatus.CONNECTED) {
                val len = inputStream.read(buffer)
                if (len > 0) {
                    stats.uploadBytes += len
                    tunnelEngine?.sendData(buffer, len)
                }
            }
        }

        // Proxy → Out
        serviceScope.launch {
            val outputStream = FileOutputStream(vpnFd.fileDescriptor)
            while (isActive && currentStatus == ConnectionStatus.CONNECTED) {
                val data = tunnelEngine?.receiveData() ?: break
                outputStream.write(data)
                stats.downloadBytes += data.size
            }
        }
    }

    // ─── Stats Update ─────────────────────────────────────────────────────────

    private fun startStatsUpdate() {
        statsJob = serviceScope.launch {
            var lastUp = 0L
            var lastDown = 0L
            while (isActive) {
                delay(1000)
                stats.uploadSpeed = stats.uploadBytes - lastUp
                stats.downloadSpeed = stats.downloadBytes - lastDown
                lastUp = stats.uploadBytes
                lastDown = stats.downloadBytes

                // Ping measurement
                try {
                    val start = System.currentTimeMillis()
                    if (InetAddress.getByName("8.8.8.8").isReachable(2000)) {
                        stats.ping = (System.currentTimeMillis() - start).toInt()
                    }
                } catch (_: Exception) {}

                updateNotification("↑ ${stats.formatUploadSpeed()} ↓ ${stats.formatDownloadSpeed()} | ${stats.formatElapsed()}")
            }
        }
    }

    // ─── Auto Reconnect ───────────────────────────────────────────────────────

    private fun handleReconnect() {
        if (!currentConfig.autoReconnect) {
            stopSelf()
            return
        }
        if (reconnectAttempts >= maxReconnectAttempts) {
            updateStatus(ConnectionStatus.ERROR, "Max reconnect attempts reached")
            stopSelf()
            return
        }
        reconnectAttempts++
        updateStatus(ConnectionStatus.RECONNECTING, "Reconnecting (attempt $reconnectAttempts)...")
        serviceScope.launch {
            delay(3000L * reconnectAttempts)
            startVpn()
        }
    }

    // ─── Status Broadcast ─────────────────────────────────────────────────────

    private fun updateStatus(status: ConnectionStatus, message: String = "") {
        currentStatus = status
        KighmuLogger.log(message, if (status == ConnectionStatus.ERROR) LogEntry.LogLevel.ERROR else LogEntry.LogLevel.INFO)
        sendBroadcast(Intent(BROADCAST_STATUS).apply {
            putExtra(EXTRA_STATUS, status.name)
            putExtra(EXTRA_MESSAGE, message)
        })
    }

    // ─── Notifications ────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "KIGHMU VPN",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "VPN connection status"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, KighmuVpnService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("KIGHMU VPN")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_vpn_key)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_close, "Disconnect", stopIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }
}
