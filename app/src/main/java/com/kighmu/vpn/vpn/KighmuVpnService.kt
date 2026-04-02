package com.kighmu.vpn.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.kighmu.vpn.profiles.ProfileRepository
import com.kighmu.vpn.profiles.SessionManager
import com.kighmu.vpn.models.SshCredentials
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
import kotlinx.coroutines.runBlocking
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
    private val MAX_RECONNECT = 20
    private var userRequestedStop = false
    private var currentProfileIndex = 0
    private var sessionManager: SessionManager? = null
    private val RECONNECT_DELAY = 5000L
    private val maxReconnectAttempts = 5
    private var statsJob: Job? = null
    private var vpnJob: Job? = null
    private var tun2socksRelay: Tun2SocksRelay? = null
    private var wakeLock: android.os.PowerManager.WakeLock? = null

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
        startForeground(NOTIFICATION_ID, buildNotification("Connecting"))
        when (intent?.action) {
            ACTION_START -> startVpn()
            ACTION_STOP -> stopVpn()
            ACTION_RECONNECT -> reconnect()
            null -> if (!userRequestedStop) startVpn()
        }
        return if (userRequestedStop) START_NOT_STICKY else START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)

    override fun onRevoke() {
        logToFile("=== VPN REVOKED BY ANDROID ===")
        logToFile("userRequestedStop=$userRequestedStop")
        // Android révoque le VPN - on reconnecte immédiatement
        KighmuLogger.info(TAG, "VPN révoqué par Android - reconnexion...")
        try { vpnInterface?.close() } catch (_: Exception) {}
        vpnInterface = null
        if (!userRequestedStop) {
            serviceScope.launch {
                delay(1000)
                startVpn()
            }
        }
    }

    override fun onDestroy() {
        logToFile("=== SERVICE DESTROYED ===")
        logToFile("userRequestedStop=$userRequestedStop")
        logToFile("reconnectAttempts=$reconnectAttempts")
        logToFile("currentStatus=$currentStatus")
        vpnJob?.cancel()
        statsJob?.cancel()
        serviceJob.cancel()
        tunnelEngine = null
        // Fermer interface TUN - clé VPN disparaît ICI
        try { vpnInterface?.close() } catch (_: Exception) {}
        vpnInterface = null
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {
            try { @Suppress("DEPRECATION") stopForeground(true) } catch (_: Exception) {}
        }
        updateStatus(ConnectionStatus.DISCONNECTED, "Disconnected")
        super.onDestroy()
    }

    private var isStartingVpn = false
    private fun startVpn() {
        if (isStartingVpn) { logToFile("startVpn() ignoré - déjà en cours"); return }
        isStartingVpn = true
        userRequestedStop = false
        // Acquérir WakeLock pour empêcher Android de tuer le service
        if (wakeLock == null || wakeLock?.isHeld == false) {
            val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
            wakeLock = pm.newWakeLock(
                android.os.PowerManager.PARTIAL_WAKE_LOCK,
                "KighmuVPN::WakeLock"
            )
            wakeLock?.acquire(8 * 60 * 60 * 1000L) // Max 8 heures
        }
        if (reconnectAttempts == 0) currentProfileIndex = 0
        vpnJob = serviceScope.launch {
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
                startForeground(NOTIFICATION_ID, buildNotification("Connecting"))

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
                KighmuLogger.info("VpnService", "=== DÉMARRAGE VPN ===")
                KighmuLogger.info("VpnService", "Mode: ${currentConfig.tunnelMode.label}")



                val localPort = try {
                    tunnelEngine = TunnelEngineFactory.create(currentConfig, this@KighmuVpnService, this@KighmuVpnService)
                    tunnelEngine!!.start()
                } catch (e: Exception) {
                    KighmuLogger.error("VpnService", "Engine failed: ${e.javaClass.simpleName}: ${e.message}")
                    try { tempVpn?.close() } catch (_: Exception) {}
                    if (!userRequestedStop && reconnectAttempts < MAX_RECONNECT) {
                        reconnectAttempts++
                        isStartingVpn = false // Reset pour permettre la reconnexion
                        updateStatus(ConnectionStatus.CONNECTING, "Reconnecting... ($reconnectAttempts/$MAX_RECONNECT)")
                        delay(RECONNECT_DELAY)
                        startVpn()
                    } else {
                        reconnectAttempts = 0
                        updateStatus(ConnectionStatus.ERROR, "Echec apres $MAX_RECONNECT tentatives")
                    }
                    isStartingVpn = false
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
                // Garder vpnInterface ouvert - le fermer au stop libère la clé VPN
                KighmuLogger.info("VpnService", "Appel startTun2Socks fd=${vpnInterface!!.fd}")
                tunnelEngine?.startTun2Socks(vpnInterface!!.fd)
                KighmuLogger.info("VpnService", "startTun2Socks terminé")

                reconnectAttempts = 0
                stats = VpnStats(connectedAt = System.currentTimeMillis())
                isStartingVpn = false
                updateStatus(ConnectionStatus.CONNECTED, "Connected")
                updateNotification("Connected")
                startStatsUpdate()
                startWatchdog()

            } catch (e: Exception) {
                logToFile("=== VPN START ERROR ===")
                logToFile("Exception: ${e.javaClass.simpleName}: ${e.message}")
                Log.e(TAG, "VPN start error", e)
                updateStatus(ConnectionStatus.ERROR, e.message ?: "Connection failed")
            }
        }
    }

                    private fun stopVpn() {
        userRequestedStop = true
        KighmuLogger.info(TAG, "=== DÉCONNEXION NUCLÉAIRE DÉMARRÉE ===")
        
        // 1. Libérer WakeLock immédiatement
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null
        reconnectAttempts = 0
        
        // 2. Annuler tous les jobs de monitoring
        vpnJob?.cancel()
        statsJob?.cancel()
        
        val engineRef = tunnelEngine
        tunnelEngine = null
        tun2socksRelay = null
        stats = VpnStats()

        // 3. Arrêter le moteur et tuer les processus natifs de manière SYNCHRONE
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO)
        scope.launch {
            try {
                KighmuLogger.info(TAG, "Arrêt du moteur...")
                engineRef?.stop()
                
                // Tuer TOUS les processus natifs qui pourraient tenir le FD de l'interface TUN
                KighmuLogger.info(TAG, "Nettoyage des processus natifs...")
                val killCmd = "killall -9 libtun2socks.so xray hysteria libhysteria.so dnstt"
                Runtime.getRuntime().exec(arrayOf("sh", "-c", killCmd)).waitFor()
                
                // Sécurité supplémentaire : tuer tout processus occupant les ports SOCKS
                try {
                    Runtime.getRuntime().exec(arrayOf("sh", "-c", "fuser -k 1080/tcp 10808/tcp")).waitFor()
                } catch (_: Exception) {}
                
                // Délai de grâce pour laisser le noyau Linux libérer les ressources
                kotlinx.coroutines.delay(500)
                
                // 4. Fermer VPN interface - C'est l'étape CRITIQUE pour la clé VPN
                try {
                    vpnInterface?.close()
                    KighmuLogger.info(TAG, "VPN Interface fermée avec succès")
                } catch (e: Exception) {
                    KighmuLogger.error(TAG, "Erreur fermeture interface: ${e.message}")
                }
                vpnInterface = null

                // 5. Retirer notification et arrêter le service
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    try {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                    } catch (_: Exception) {
                        try { @Suppress("DEPRECATION") stopForeground(true) } catch (_: Exception) {}
                    }
                    updateStatus(ConnectionStatus.DISCONNECTED, "Disconnected")
                    stopSelf()
                    KighmuLogger.info(TAG, "Service VPN arrêté proprement")
                }
            } catch (e: Exception) {
                KighmuLogger.error(TAG, "Erreur lors de la déconnexion: ${e.message}")
                // Fallback de secours : fermer l'interface quoi qu'il arrive
                try { vpnInterface?.close() } catch (_: Exception) {}
                vpnInterface = null
                stopSelf()
            }
        }
    }

    private fun reconnect() {
        serviceScope.launch {
            try { tunnelEngine?.stop() } catch (_: Exception) {}
            tunnelEngine = null
            try { vpnInterface?.close() } catch (_: Exception) {}
            vpnInterface = null
            isStartingVpn = false // Reset guard pour permettre reconnexion
            userRequestedStop = false
            delay(500)
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
            var pingCounter = 0
            while (isActive) {
                delay(1000)
                stats.uploadSpeed = stats.uploadBytes - lastUp
                stats.downloadSpeed = stats.downloadBytes - lastDown
                lastUp = stats.uploadBytes
                lastDown = stats.downloadBytes
                // Ping toutes les 30 secondes seulement
                pingCounter++
                if (pingCounter >= 30) {
                    pingCounter = 0
                    try {
                        val start = System.currentTimeMillis()
                        val socket = java.net.Socket()
                        socket.connect(java.net.InetSocketAddress("8.8.8.8", 53), 2000)
                        socket.close()
                        stats.ping = (System.currentTimeMillis() - start).toInt()
                    } catch (_: Exception) {}
                }
                updateNotification("Connected")
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

    private fun requestBatteryOptimizationExemption() {
        try {
            val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                val intent = android.content.Intent(
                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                ).apply { data = android.net.Uri.parse("package:$packageName") }
                intent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                startActivity(intent)
            }
        } catch (_: Exception) {}
    }

    private fun startWatchdog() {
        serviceScope.launch {
            while (isActive) {
                delay(10000) // Vérifier toutes les 10 secondes
                if (!userRequestedStop && currentStatus == ConnectionStatus.CONNECTED) {
                    val engine = tunnelEngine
                    if (engine == null || !engine.isRunning()) {
                        logToFile("WATCHDOG: engine mort - reconnexion")
                        try { vpnInterface?.close() } catch (_: Exception) {}
                        vpnInterface = null
                        startVpn()
                        break
                    }
                }
            }
        }
    }

    private fun logToFile(msg: String) {
        try {
            // Écrire dans le dossier interne (pas besoin de permission)
            val file = java.io.File(filesDir, "kighmu_close.txt")
            val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
            file.appendText("[$timestamp] $msg\n")
            // Aussi copier vers Download si possible
            try {
                val dlFile = java.io.File(android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS), "kighmu_close.txt")
                dlFile.appendText("[$timestamp] $msg\n")
            } catch (_: Exception) {}
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
        val reconnectIntent = PendingIntent.getService(
            this, 2,
            Intent(this, KighmuVpnService::class.java).apply { action = ACTION_START },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = "KIGHMU VPN"

        val isConnected = text == "Connected"
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_vpn_key)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_close, "Stop", stopIntent)
            .addAction(R.drawable.ic_vpn_key, "Reconnect", reconnectIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (isConnected && stats.connectedAt > 0) {
            builder.setUsesChronometer(true)
            builder.setWhen(stats.connectedAt)
            builder.setChronometerCountDown(false)
        }

        return builder.build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }
}
