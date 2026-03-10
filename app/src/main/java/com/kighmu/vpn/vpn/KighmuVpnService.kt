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
import com.kighmu.vpn.engines.SlowDnsEngine
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
    private var tun2socksRelay: Tun2SocksRelay? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        configManager = ConfigManager(this)
        createNotificationChannel()
        // Capturer et sauvegarder les crashes dans un fichier
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                // Ecrire dans stockage externe accessible
                val crashFile = java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "kighmu_crash.txt")
                val stack = throwable.stackTrace.take(10).joinToString("|")
                val msg = "CRASH: " + throwable.javaClass.name + " | " + throwable.message + " | Thread: " + thread.name + " | " + stack
                crashFile.writeText(msg)
            } catch (_: Exception) {}
            defaultHandler?.uncaughtException(thread, throwable)
        }
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
        try { instance = null } catch (_: Exception) {}
        try { statsJob?.cancel() } catch (_: Exception) {}
        try { serviceJob.cancel() } catch (_: Exception) {}
        try {
            tunnelEngine = null
            vpnInterface?.close()
            vpnInterface = null
        } catch (_: Exception) {}
        try { stopForeground(true) } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun startVpn() {
        serviceScope.launch {
            try {
                updateStatus(ConnectionStatus.CONNECTING, "Loading configuration...")
                currentConfig = configManager.loadCurrentConfig()

                val validationResult = ConfigEncryption.validateConfig(this@KighmuVpnService, currentConfig)
                when (validationResult) {
                    is ConfigEncryption.ValidationResult.Expired -> {
                        updateStatus(ConnectionStatus.ERROR, "Config expired")
                        return@launch
                    }
                    is ConfigEncryption.ValidationResult.WrongDevice -> {
                        updateStatus(ConnectionStatus.ERROR, "Config locked to another device")
                        return@launch
                    }
                    else -> {}
                }

                updateStatus(ConnectionStatus.CONNECTING, "Starting tunnel engine...")
                startForeground(NOTIFICATION_ID, buildNotification("Connecting..."))

                val tempVpn = try {
                    Builder()
                        .setSession("KIGHMU VPN")
                        .addAddress("10.0.0.2", 24)
                        .addRoute("0.0.0.0", 0)
                        .addDnsServer("8.8.8.8")
                        .setMtu(1500)
                        .addDisallowedApplication(packageName)
                        .establish()
                } catch (e: Exception) {
                    KighmuLogger.warning("VpnService", "TempVPN: ${e.message}")
                    null
                }
                KighmuLogger.info("VpnService", "Interface temp etablie: ${tempVpn != null}")
                KighmuLogger.info("VpnService", "=== DÉMARRAGE VPN ===")
                KighmuLogger.info("VpnService", "Mode: ${currentConfig.tunnelMode.label}")
                KighmuLogger.info("VpnService", "Config: ${currentConfig.configName}")
                KighmuLogger.info("VpnService", "SSH host: '${currentConfig.sshCredentials.host}'")
                KighmuLogger.info("VpnService", "SSH port: ${currentConfig.sshCredentials.port}")
                KighmuLogger.info("VpnService", "SSH user: '${currentConfig.sshCredentials.username}'")
                KighmuLogger.info("VpnService", "SSH pass empty: ${currentConfig.sshCredentials.password.isEmpty()}")
                KighmuLogger.info("VpnService", "DNS server: '${currentConfig.slowDns.dnsServer}'")
                KighmuLogger.info("VpnService", "Nameserver: '${currentConfig.slowDns.nameserver}'")

                val localPort = try {
                    tunnelEngine = TunnelEngineFactory.create(currentConfig, this@KighmuVpnService, this@KighmuVpnService)
                    tunnelEngine!!.start()
                } catch (e: Exception) {
                    KighmuLogger.error("VpnService", "Engine failed: ${e.javaClass.simpleName}: ${e.message}")
                    updateStatus(ConnectionStatus.ERROR, "Tunnel error: ${e.message}")
                    try { tempVpn?.close() } catch (_: Exception) {}
                    return@launch
                }
                KighmuLogger.info("VpnService", "Engine démarré sur port $localPort")

                try { tempVpn?.close() } catch (_: Exception) {}

                updateStatus(ConnectionStatus.CONNECTING, "Creating VPN interface...")
                vpnInterface = buildVpnInterface(localPort)

                if (vpnInterface == null) {
                    updateStatus(ConnectionStatus.ERROR, "Failed to create VPN interface")
                    try { tunnelEngine?.stop() } catch (_: Exception) {}
                    return@launch
                }

                // Routing via tun2socks JNI (arm64) ou Kotlin relay (fallback)
                val eng = tunnelEngine
                tunnelEngine?.startTun2Socks(vpnInterface!!.detachFd())

                reconnectAttempts = 0
                stats = VpnStats(connectedAt = System.currentTimeMillis())
                updateStatus(ConnectionStatus.CONNECTED, "Connected via ${currentConfig.tunnelMode.label}")
                startStatsUpdate()

            } catch (e: Exception) {
                Log.e(TAG, "VPN start error", e)
                updateStatus(ConnectionStatus.ERROR, e.message ?: "Connection failed")
            }
        }
    }

    private fun stopVpn() {
        try { statsJob?.cancel() } catch (_: Exception) {}
        try { updateStatus(ConnectionStatus.DISCONNECTED, "Disconnected") } catch (_: Exception) {}
        // Arreter dans un thread separé pour ne pas bloquer/crasher
        val engineRef = tunnelEngine
        val relayRef = tun2socksRelay
        val vpnRef = vpnInterface
        tunnelEngine = null
        tun2socksRelay = null
        vpnInterface = null
        stats = VpnStats()
        serviceScope.launch {
            try { relayRef?.stop() } catch (_: Exception) {}
            try { engineRef?.stop() } catch (_: Exception) {}
            try { vpnRef?.close() } catch (_: Exception) {}
        }
        try { stopForeground(true) } catch (_: Exception) {}
        // stopSelf retire - le service reste actif
    }

    private fun reconnect() {
        serviceScope.launch {
            try { tunnelEngine?.stop() } catch (_: Exception) {}
            try { vpnInterface?.close() } catch (_: Exception) {}
            delay(2000)
            startVpn()
        }
    }

    private fun buildVpnInterface(localProxyPort: Int): ParcelFileDescriptor? {
        return try {
            val builder = Builder()
                .setSession("KIGHMU VPN")
                .addAddress("10.0.0.2", 24)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("1.1.1.1")
                .addDnsServer("8.8.8.8")
                .setMtu(1500)
                .setBlocking(true)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setMetered(false)
            }

            builder.addDisallowedApplication(packageName)
            builder.establish()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to establish VPN interface", e)
            null
        }
    }

    private fun startSocks5Routing(vpnFd: ParcelFileDescriptor, socksPort: Int) {
        KighmuLogger.info(TAG, "VPN interface active sur port $socksPort - routing actif")
        tun2socksRelay = Tun2SocksRelay(vpnFd.fileDescriptor, "127.0.0.1", socksPort)
        tun2socksRelay?.start()
        KighmuLogger.info(TAG, "Tun2SocksRelay demarre")
    }

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

    private fun handleReconnect() {
        // Ne jamais fermer le service - juste signaler l'erreur
    }

    private fun updateStatus(status: ConnectionStatus, message: String = "") {
        try {
            currentStatus = status
            KighmuLogger.log(message, if (status == ConnectionStatus.ERROR) LogEntry.LogLevel.ERROR else LogEntry.LogLevel.INFO)
            sendBroadcast(Intent(BROADCAST_STATUS).apply {
                putExtra(EXTRA_STATUS, status.name)
                putExtra(EXTRA_MESSAGE, message)
            })
        } catch (_: Exception) {}
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "KIGHMU VPN", NotificationManager.IMPORTANCE_LOW).apply {
                description = "VPN connection status"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
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
