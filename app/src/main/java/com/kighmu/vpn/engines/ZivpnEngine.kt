package com.kighmu.vpn.engines

import android.content.Context
import android.net.VpnService
import com.kighmu.vpn.models.KighmuConfig
import com.kighmu.vpn.utils.KighmuLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class ZivpnEngine(
    private val config: KighmuConfig,
    private val context: Context,
    private val vpnService: VpnService? = null
) : TunnelEngine {

    companion object {
        const val TAG = "ZivpnEngine"
        fun getFreePort(): Int = try {
            java.net.ServerSocket(0).use { it.localPort }
        } catch (_: Exception) { 1080 }
    }

    @Volatile private var running = false
    @Volatile private var zivpnProcess: Process? = null
    @Volatile private var serverConnected = false
    @Volatile private var socksPort: Int = 0

    init { socksPort = getFreePort() }

    private val logFile: File by lazy {
        File(
            android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS
            ), "kighmu_zivpn.txt"
        )
    }

    private fun log(msg: String) {
        KighmuLogger.info(TAG, msg)
        try {
            val ts = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.getDefault()).format(java.util.Date())
            logFile.appendText("[$ts] [ZIVPN] $msg\n")
        } catch (_: Exception) {}
    }

    override suspend fun start(): Int {
        running = true
        serverConnected = false
        return withContext(Dispatchers.IO) {

            // --- Effacer le log précédent ---
            try { logFile.writeText("") } catch (_: Exception) {}

            val host     = config.zivpnHost.trim()
            val password = config.zivpnPassword.trim()

            log("========== DÉMARRAGE ZIVPN UDP ==========")
            log("Android version: ${android.os.Build.VERSION.RELEASE} | ABI: ${android.os.Build.SUPPORTED_ABIS.firstOrNull()}")
            log("Host configuré  : '${if (host.isEmpty()) "VIDE ❌" else host}'")
            log("Password configuré: ${when { password.isEmpty() -> "VIDE ❌"; password.length < 4 -> "****"; else -> "${"*".repeat(password.length - 2)}${password.takeLast(2)}" }}")
            log("Port SOCKS5 alloué: $socksPort")
            log("Répertoire natif : ${context.applicationInfo.nativeLibraryDir}")
            log("filesDir         : ${context.filesDir.absolutePath}")

            // --- Vérifications préalables ---
            if (host.isEmpty()) {
                log("ERREUR FATALE: host vide — configurer l'IP/Host dans l'onglet ZIVPN ❌")
                throw IllegalArgumentException("ZIVPN: host non configuré")
            }
            if (password.isEmpty()) {
                log("ERREUR FATALE: password vide — configurer le mot de passe dans l'onglet ZIVPN ❌")
                throw IllegalArgumentException("ZIVPN: password non configuré")
            }

            // --- Vérifier libuz.so ---
            val nativeDir = context.applicationInfo.nativeLibraryDir
            listOf(
                File(nativeDir, "libuz.so"),
                File(context.filesDir, "libuz.so")
            ).forEach { f ->
                log("Recherche ${f.absolutePath}: ${if (f.exists()) "trouvé ✅ (${f.length()} octets, exec=${f.canExecute()})" else "absent"}")
            }

            // --- Écriture config YAML ---
            val configFile = writeConfig(host, password)
            log("Port ZIVPN utilisé: " + "6000-19999")
            log("Config YAML écrite: ${configFile.absolutePath} (${configFile.length()} octets)")
            log("--- Contenu YAML (password masqué) ---")
            configFile.readText().replace(password, "****").lines().forEach { log("  $it") }
            log("--------------------------------------")

            // --- Extraction binaire ---
            val binary = extractBinary("libuz.so")
                ?: run {
                    log("ERREUR FATALE: libuz.so introuvable dans $nativeDir ❌")
                    log("→ Vérifier que libuz.so est bien dans jniLibs/armeabi-v7a/ et que le build.yml le télécharge")
                    throw IllegalStateException("libuz.so introuvable")
                }

            log("Binaire sélectionné: ${binary.absolutePath}")
            log("  Taille : ${binary.length()} octets")
            log("  Exécutable: ${binary.canExecute()}")

            // --- Lancement process ---
            try { zivpnProcess?.destroyForcibly() } catch (_: Exception) {}
            zivpnProcess = null

            startZivpnProcess(binary, configFile)
            log("Process lancé — attente connexion (max 15s)...")

            // --- Attente connexion ---
            var processExited = false
            repeat(30) { iteration ->
                if (!serverConnected && !processExited) {
                    try {
                        val exitCode = zivpnProcess?.exitValue()
                        log("⚠️ Process terminé prématurément à l'itération $iteration (exit code=$exitCode)")
                        processExited = true
                    } catch (_: IllegalThreadStateException) {
                        // Process toujours en cours → normal
                    }
                    if (!processExited && !serverConnected) {
                        log("  [attente ${(iteration + 1) * 500}ms] connected=$serverConnected")
                        Thread.sleep(500)
                    }
                }
            }

            when {
                processExited -> {
                    log("ÉCHEC: process terminé avant établissement connexion ❌")
                    log("→ Causes possibles: binaire incompatible, config invalide, port inaccessible")
                    throw Exception("ZIVPN: process terminé avant connexion")
                }
                !serverConnected -> {
                    log("⚠️ TIMEOUT 15s: aucune confirmation de connexion — process toujours actif")
                    log("→ Peut être normal si libuz.so utilise un format de log non reconnu")
                    log("→ Continuer avec serverConnected=true (mode optimiste)")
                    serverConnected = true
                }
            }

            log("ZIVPN prêt: SOCKS5 127.0.0.1:$socksPort ✅")
            log("=========================================")
            socksPort
        }
    }

    private fun writeConfig(host: String, password: String): File {
        val file = File(context.filesDir, "zivpn_config.json")
        val portRange = config.zivpnPort.ifBlank { "6000-19999" }
        val startPort = portRange.split("-").firstOrNull()?.trim() ?: "6000"
        file.writeText("""
{
  "server": "$host:${"$"}{startPort}",
  "auth": "$password",
  "obfs": {
    "type": "salamander",
    "salamander": {
      "password": "zivpn"
    }
  },
  "transport": {
    "type": "udp",
    "udp": {
      "hopInterval": "30s",
      "hopPorts": "${"$"}{portRange}"
    }
  },
  "tls": {
    "insecure": true
  },
  "bandwidth": {
    "up": "50 mbps",
    "down": "100 mbps"
  },
  "socks5": {
    "listen": "127.0.0.1:$socksPort"
  }
}
        """.trimIndent())
        return file
    }

    private fun extractBinary(name: String): File? {
        // Exécuter directement depuis nativeLibraryDir — SELinux l'autorise
        val bin = File(context.applicationInfo.nativeLibraryDir, name)
        if (!bin.exists()) {
            log("ERREUR: $name introuvable dans nativeLibraryDir")
            return null
        }
        bin.setExecutable(true)
        log("Binaire natif: ${bin.absolutePath} (${bin.length()} octets, exec=${bin.canExecute()})")
        return bin
    }

    private fun startZivpnProcess(binary: File, configFile: File) {
        // Test 1: lancer --help pour voir si le binaire répond
        try {
            val linkerH = "/system/bin/linker"
            val helpCmd = if (java.io.File(linkerH).exists())
                listOf(linkerH, binary.absolutePath, "client", "--help")
            else listOf(binary.absolutePath, "client", "--help")
            val helpProc = ProcessBuilder(helpCmd)
                .apply {
                    environment()["HOME"]   = context.filesDir.absolutePath
                    environment()["TMPDIR"] = context.cacheDir.absolutePath
                    redirectErrorStream(true)
                }.start()
            val helpOut = helpProc.inputStream.bufferedReader().readText()
            helpProc.waitFor()
            log("=== HELP OUTPUT ===")
            helpOut.lines().take(20).forEach { log("  HELP: $it") }
            log("===================")
        } catch (e: Exception) {
            log("HELP failed: ${e.message}")
        }

        val cmd = listOf(binary.absolutePath, "client", "--config", configFile.absolutePath)
        log("Commande directe: ${cmd.joinToString(" ")}")
        val pb = ProcessBuilder(cmd).directory(context.noBackupFilesDir).apply {
            environment()["HOME"]             = context.filesDir.absolutePath
            environment()["TMPDIR"]           = context.cacheDir.absolutePath
            environment()["ZIVPN_LOG_LEVEL"]  = "debug"
            environment()["ZIVPN_LOG_FORMAT"] = "console"
            environment()["GOTRACEBACK"]       = "crash"
            environment()["GODEBUG"]           = "asyncpreemptoff=1"
            redirectErrorStream(false)
        }
        val stderrFile = java.io.File(context.filesDir, "zivpn_stderr.txt")
        zivpnProcess = pb.start()
        // Capturer stderr séparément
        Thread {
            try {
                val err = zivpnProcess?.errorStream?.bufferedReader()?.readText() ?: ""
                if (err.isNotBlank()) {
                    stderrFile.writeText(err)
                    err.lines().forEach { log("STDERR: $it") }
                } else {
                    log("STDERR: vide")
                }
            } catch (e: Exception) { log("STDERR err: ${e.message}") }
        }.apply { isDaemon = true }.start()

        // Lire stdout dans un thread séparé IMMÉDIATEMENT
        Thread {
            var lineCount = 0
            try {
                val reader = zivpnProcess?.inputStream?.bufferedReader()
                // Lire ligne par ligne avec timeout
                var line: String?
                while (running) {
                    line = reader?.readLine()
                    if (line == null) break
                    lineCount++
                    val lower = line.lowercase()
                    log("[#$lineCount] $line")
                    when {
                        lower.contains("connected") || lower.contains("established") ||
                        lower.contains("udp-zivpn") || lower.contains("zivpn_udp") ||
                        lower.contains("tunnel") && lower.contains("ok") -> {
                            serverConnected = true
                            log("✅ CONNEXION ÉTABLIE (ligne $lineCount)")
                        }
                        lower.contains("socks5") || lower.contains("socks") -> {
                            Regex("""127\.0\.0\.1:(\d+)""").find(line)
                                ?.groupValues?.get(1)?.toIntOrNull()
                                ?.takeIf { it > 0 }
                                ?.let { p -> socksPort = p; log("Port SOCKS5 confirmé: $p") }
                            serverConnected = true
                            log("✅ SOCKS5 prêt (ligne $lineCount)")
                        }
                        lower.contains("listening") || lower.contains("running") ||
                        lower.contains("started") || lower.contains("ready") -> {
                            serverConnected = true
                            log("✅ Service prêt (ligne $lineCount)")
                        }
                        lower.contains("error") || lower.contains("fatal") ||
                        lower.contains("failed") || lower.contains("panic") -> {
                            log("❌ ERREUR: $line")
                        }
                        lower.contains("refused") || lower.contains("unreachable") ||
                        lower.contains("timeout") || lower.contains("no route") -> {
                            log("⚠️ RÉSEAU: $line")
                        }
                        lower.contains("auth") || lower.contains("password") -> {
                            log("🔑 AUTH: $line")
                        }
                        lower.contains("tls") || lower.contains("quic") ||
                        lower.contains("handshake") -> {
                            log("🔒 TLS/QUIC: $line")
                        }
                        lower.contains("hop") || lower.contains("port") -> {
                            log("🔀 PORT-HOP: $line")
                        }
                    }
                }
                val code = zivpnProcess?.waitFor() ?: -1
                log("Process terminé — exit code: $code ${if (code == 0) "✅" else "❌"}")
                if (code != 0) log("→ Exit code $code: total $lineCount lignes lues")
                if (lineCount == 0) log("⚠️ AUCUNE LIGNE LUE — crash avant toute écriture stdout/stderr")
                serverConnected = false
            } catch (e: Exception) {
                log("Exception thread lecteur: ${e.javaClass.simpleName}: ${e.message}")
            }
        }.apply { name = "zivpn-reader"; isDaemon = true; priority = Thread.MAX_PRIORITY }.start()

        // Attendre 100ms et vérifier si le process est déjà mort
        Thread.sleep(100)
        try {
            val earlyExit = zivpnProcess?.exitValue()
            log("⚠️ CRASH IMMÉDIAT après 100ms — exit code: $earlyExit")
            log("⚠️ Causes: SELinux? linker manquant? ABI incompatible?")
            // Vérifier les libs dynamiques nécessaires
            log("Vérification libs système...")
            listOf("/system/bin/linker", "/system/lib/libc.so", "/system/lib/libdl.so").forEach { lib ->
                log("  $lib: ${if (java.io.File(lib).exists()) "✅" else "❌ MANQUANT"}")
            }
        } catch (_: IllegalThreadStateException) {
            log("✅ Process toujours actif après 100ms — bon signe")
        }
    }

    override fun startTun2Socks(fd: Int) {
        try {
            if (vpnService == null) { log("ERREUR: VpnService null ❌"); return }
            log("Démarrage HevTun2Socks fd=$fd socksPort=$socksPort")
            HevTun2Socks.init()
            log("HevTun2Socks.isAvailable=${HevTun2Socks.isAvailable}")
            if (HevTun2Socks.isAvailable) {
                HevTun2Socks.start(context, fd, socksPort, vpnService, mtu = 8500)
                log("HevTun2Socks démarré ✅")
            } else {
                log("ERREUR: HevTun2Socks non disponible — libhev-socks5-tunnel.so manquant? ❌")
            }
        } catch (e: Exception) {
            log("Erreur HevTun2Socks: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    override suspend fun stop() {
        running = false
        serverConnected = false
        log("=== ARRÊT ZIVPN ===")
        try { HevTun2Socks.stop(); log("HevTun2Socks arrêté ✅") }
        catch (e: Exception) { log("Erreur arrêt HevTun2Socks: ${e.message}") }
        try {
            zivpnProcess?.let { p ->
                runCatching { p.inputStream?.close() }
                runCatching { p.errorStream?.close() }
                runCatching { p.outputStream?.close() }
                p.destroyForcibly()
                log("Process ZIVPN détruit ✅")
            }
        } catch (e: Exception) { log("Erreur destruction process: ${e.message}") }
        zivpnProcess = null
        withContext(Dispatchers.IO) { Thread.sleep(300) }
        log("ZIVPN arrêté ✅")
    }

    override suspend fun sendData(data: ByteArray, length: Int) {}
    override suspend fun receiveData(): ByteArray? = null
    override fun isRunning() = running && serverConnected
}
