package com.kighmu.vpn.ui.activities

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.kighmu.vpn.R
import com.kighmu.vpn.config.ConfigEncryption
import com.kighmu.vpn.models.ExportConfig
import com.kighmu.vpn.models.KighmuConfig
import com.kighmu.vpn.ui.MainViewModel
import kotlinx.coroutines.*

class ImportActivity : AppCompatActivity() {

    private val viewModel: MainViewModel by viewModels()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val configManager by lazy { com.kighmu.vpn.config.ConfigManager(this) }

    private val filePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { importFromFile(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_import)

        // Bouton importer fichier
        findViewById<Button>(R.id.btn_import_file).setOnClickListener {
            filePicker.launch("*/*")
        }

        // Bouton importer via code cloud
        findViewById<Button>(R.id.btn_import_cloud).setOnClickListener {
            importFromCloud()
        }

        // Handle intent ACTION_VIEW (.kighmu file)
        if (intent?.action == Intent.ACTION_VIEW) {
            intent.data?.let { importFromFile(it) }
        }
    }

    private fun importFromFile(uri: Uri) {
        try {
            val bytes = contentResolver.openInputStream(uri)?.readBytes() ?: return
            val json = String(bytes, Charsets.UTF_8)
            processImportJson(json)
        } catch (e: Exception) {
            showError("Erreur lecture fichier: ${e.message}")
        }
    }

    private fun importFromCloud() {
        val etCode = findViewById<EditText>(R.id.et_cloud_code)
        val code = etCode.text.toString().trim()
        if (code.isBlank()) {
            showError("Entrez un code d'accès")
            return
        }
        scope.launch {
            try {
                val json = withContext(Dispatchers.IO) { fetchCloudConfig(code) }
                if (json != null) processImportJson(json)
                else showError("Code invalide ou config introuvable")
            } catch (e: Exception) {
                showError("Erreur réseau: ${e.message}")
            }
        }
    }

    private fun processImportJson(json: String) {
        try {
            val type = object : TypeToken<Map<String, Any>>() {}.type
            val map: Map<String, Any> = Gson().fromJson(json, type)

            val configJson = Gson().toJson(map["config"])
            val securityJson = Gson().toJson(map["security"])

            val config = Gson().fromJson(configJson, KighmuConfig::class.java)
            val security = Gson().fromJson(securityJson, ExportConfig::class.java)

            // Vérifier code d'accès si requis
            if (security.accessCode.isNotBlank()) {
                promptAccessCode(config, security)
                return
            }

            validateAndImport(config, security)

        } catch (e: Exception) {
            showError("Format invalide: ${e.message}")
        }
    }

    private fun promptAccessCode(config: KighmuConfig, security: ExportConfig) {
        val input = EditText(this).apply {
            hint = "Code d'accès"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this)
            .setTitle("Code d'accès requis")
            .setView(input)
            .setPositiveButton("Valider") { _, _ ->
                if (input.text.toString() == security.accessCode) {
                    validateAndImport(config, security)
                } else {
                    showError("Code incorrect")
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun validateAndImport(config: KighmuConfig, security: ExportConfig) {
        // Vérifier expiration
        if (security.expiresAt > 0 && System.currentTimeMillis() > security.expiresAt) {
            showError("Cette configuration a expiré")
            return
        }

        // Vérifier device lock
        if (security.lockDeviceId && security.hardwareId.isNotEmpty()) {
            val currentHwId = ConfigEncryption.getHardwareId(this)
            if (security.hardwareId != currentHwId) {
                showError("Config verrouillée sur un autre appareil")
                return
            }
        }

        // Vérifier opérateur
        if (security.lockOperator && security.operatorName.isNotEmpty()) {
            val tm = getSystemService(TELEPHONY_SERVICE) as android.telephony.TelephonyManager
            if (tm.networkOperatorName != security.operatorName) {
                showError("Config verrouillée sur l'opérateur: ${security.operatorName}")
                return
            }
        }

        // Appliquer lockAllConfig
        val finalConfig = if (security.lockAllConfig) {
            config.copy(
                exportConfig = security,
                hardwareId = security.hardwareId
            )
        } else {
            config.copy(exportConfig = security)
        }

        // Burn après import
        if (security.burnAfterImport) {
            showConfirmBurn(finalConfig, security)
            return
        }

        applyImport(finalConfig)
    }

    private fun showConfirmBurn(config: KighmuConfig, security: ExportConfig) {
        AlertDialog.Builder(this)
            .setTitle("⚠️ Config à usage unique")
            .setMessage("Cette configuration sera détruite après importation. Continuer?")
            .setPositiveButton("Importer") { _, _ ->
                applyImport(config)
                // Marquer comme brûlée - ne peut plus être réimportée
                Toast.makeText(this, "Config importée et brûlée", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun applyImport(config: KighmuConfig) {
        // Sauvegarder via ConfigManager pour persister
        configManager.saveCurrentConfig(config)
        // Aussi via viewModel pour mettre à jour l'UI
        viewModel.saveConfig(config)
        Toast.makeText(this, "✓ Configuration importée avec succès!", Toast.LENGTH_LONG).show()
        setResult(RESULT_OK)
        // Relancer MainActivity pour recharger la config
        val intent = android.content.Intent(this, MainActivity::class.java).apply {
            flags = android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(intent)
        finish()
    }

    private fun fetchCloudConfig(code: String): String? {
        // Récupérer depuis paste.rs
        val url = "https://paste.rs/$code"
        return try {
            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            val response = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            if (response.contains("config") && response.contains("security")) response else null
        } catch (_: Exception) { null }
    }

    private fun showError(msg: String) {
        runOnUiThread {
            Toast.makeText(this, "❌ $msg", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
