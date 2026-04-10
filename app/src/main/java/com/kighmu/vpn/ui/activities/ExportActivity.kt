package com.kighmu.vpn.ui.activities

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.telephony.TelephonyManager
import android.view.View
import android.widget.*
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.google.gson.Gson
import com.kighmu.vpn.R
import com.kighmu.vpn.models.ExportConfig
import com.kighmu.vpn.ui.MainViewModel
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import java.util.UUID
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class ExportActivity : AppCompatActivity() {
    private val viewModel: MainViewModel by viewModels()
    private var expiresAt = 0L
    private var exportType = "normal"  // normal | burn | expiry

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_export)

        setupExpiryDate()
        setupExportButtons()
        setupCloudExport()
        loadPersistedData()
    }

    private fun loadPersistedData() {
        val prefs = getSharedPreferences("kighmu_export_prefs", Context.MODE_PRIVATE)
        findViewById<EditText>(R.id.et_github_token).setText(prefs.getString("github_token", ""))
        findViewById<EditText>(R.id.et_user_message).setText(prefs.getString("user_message", ""))
    }

    private fun savePersistedData() {
        val prefs = getSharedPreferences("kighmu_export_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("github_token", findViewById<EditText>(R.id.et_github_token).text.toString().trim())
            .putString("user_message", findViewById<EditText>(R.id.et_user_message).text.toString())
            .apply()
    }

    private fun setupExpiryDate() {
        val cbExpiry = findViewById<CheckBox>(R.id.cb_set_expiry)
        val layoutExpiry = findViewById<View>(R.id.layout_expiry)
        val tvDate = findViewById<TextView>(R.id.tv_expiry_date)
        val tvTime = findViewById<TextView>(R.id.tv_expiry_time)
        
        val expiryCalendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
        }
        
        cbExpiry.setOnCheckedChangeListener { _, checked ->
            layoutExpiry.visibility = if (checked) View.VISIBLE else View.GONE
            if (!checked) expiresAt = 0L
        }
        
        findViewById<Button>(R.id.btn_pick_date).setOnClickListener {
            DatePickerDialog(this,
                { _, year, month, day ->
                    expiryCalendar.set(Calendar.YEAR, year)
                    expiryCalendar.set(Calendar.MONTH, month)
                    expiryCalendar.set(Calendar.DAY_OF_MONTH, day)
                    expiresAt = expiryCalendar.timeInMillis
                    val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
                    tvDate.text = sdf.format(expiryCalendar.time)
                    tvDate.setTextColor(0xFF009688.toInt())
                },
                expiryCalendar.get(Calendar.YEAR),
                expiryCalendar.get(Calendar.MONTH),
                expiryCalendar.get(Calendar.DAY_OF_MONTH)
            ).show()
        }
        
        findViewById<Button>(R.id.btn_pick_time).setOnClickListener {
            TimePickerDialog(this,
                { _, hourOfDay, minute ->
                    expiryCalendar.set(Calendar.HOUR_OF_DAY, hourOfDay)
                    expiryCalendar.set(Calendar.MINUTE, minute)
                    expiryCalendar.set(Calendar.SECOND, 0)
                    expiresAt = expiryCalendar.timeInMillis
                    tvTime.text = String.format("Heure : %02d:%02d", hourOfDay, minute)
                    tvTime.setTextColor(0xFF009688.toInt())
                },
                expiryCalendar.get(Calendar.HOUR_OF_DAY),
                expiryCalendar.get(Calendar.MINUTE),
                true
            ).show()
        }
    }

    private fun setupCloudExport() {
        val rgCloud = findViewById<RadioGroup>(R.id.rg_cloud_type)
        val layoutDuration = findViewById<View>(R.id.layout_cloud_duration)
        
        rgCloud.setOnCheckedChangeListener { _, id ->
            layoutDuration.visibility = if (id == R.id.rb_cloud_limited) View.VISIBLE else View.GONE
        }

        findViewById<Button>(R.id.btn_export_cloud).setOnClickListener {
            val isLimited = rgCloud.checkedRadioButtonId == R.id.rb_cloud_limited
            val durationMinutes = if (isLimited) {
                findViewById<EditText>(R.id.et_cloud_duration).text.toString().toLongOrNull() ?: 60L
            } else 0L

            val btn = it as Button
            btn.isEnabled = false
            btn.text = "Génération du lien..."

            Thread {
                try {
                    val cloudExpiresAt = if (durationMinutes > 0) 
                        System.currentTimeMillis() + durationMinutes * 60 * 1000L else 0L
                    
                    val config = viewModel.config.value
                    val cloudLockDevice = findViewById<CheckBox>(R.id.cb_lock_device_id).isChecked
                    val cloudLockOp = findViewById<CheckBox>(R.id.cb_lock_operator).isChecked
                    
                    val cloudSecurity = ExportConfig(
                        expiresAt = cloudExpiresAt,
                        exportType = if (isLimited) "expiry" else "normal",
                        appId = packageName,
                        userMessage = findViewById<EditText>(R.id.et_user_message).text.toString(),
                        lockAllConfig = findViewById<CheckBox>(R.id.cb_lock_all_config).isChecked,
                        accessCode = "",
                        lockDeviceId = cloudLockDevice,
                        hardwareId = if (cloudLockDevice) com.kighmu.vpn.config.ConfigEncryption.getHardwareId(this).uppercase() else "",
                        lockOperator = cloudLockOp,
                        operatorName = if (cloudLockOp) (getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager).networkOperatorName else "",
                        burnAfterImport = exportType == "burn",
                        securitySignature = buildSignature(config.toString(), "", cloudExpiresAt, "")
                    )
                    
                    val exportPackage = mapOf(
                        "config" to config,
                        "security" to cloudSecurity,
                        "exportedAt" to System.currentTimeMillis(),
                        "appVersion" to com.kighmu.vpn.BuildConfig.VERSION_NAME
                    )
                    val json = Gson().toJson(exportPackage)

                    // Sauvegarder les données persistantes
                    savePersistedData()

                    // Methode principale : GitHub Gist (si token fourni), sinon paste.ee
                    val githubToken = findViewById<EditText>(R.id.et_github_token).text.toString().trim()
                    val pasteUrl = uploadToCloud(json, githubToken)

                    runOnUiThread {
                        btn.isEnabled = true
                        btn.text = "☁️ Exporter vers le cloud"
                        if (pasteUrl != null) {
                            val code = pasteUrl.substringAfterLast("/")
                            findViewById<View>(R.id.layout_cloud_result).visibility = View.VISIBLE
                            findViewById<TextView>(R.id.tv_cloud_link_kighmu).text = pasteUrl
                            findViewById<TextView>(R.id.tv_cloud_code_only).text = code

                            findViewById<Button>(R.id.btn_copy_cloud_link).setOnClickListener { copyToClipboard("Lien", pasteUrl) }
                            findViewById<Button>(R.id.btn_copy_cloud_code_only).setOnClickListener { copyToClipboard("Code", code) }
                            findViewById<Button>(R.id.btn_copy_all_cloud).setOnClickListener { copyToClipboard("Lien & Code", "Lien: $pasteUrl\nCode: $code") }
                            Toast.makeText(this, "✓ Export Cloud Réussi", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this, "Erreur lors de l'exportation", Toast.LENGTH_LONG).show()
                        }
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        btn.isEnabled = true
                        btn.text = "☁️ Exporter vers le cloud"
                        Toast.makeText(this, "Erreur: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }.start()
        }
    }

    /**
     * uploadToCloud — Methode principale d'upload.
     *
     * 1. Si un token GitHub est fourni -> GitHub Gist (API REST, fiable, raw URL stable)
     * 2. Sinon -> paste.ee (anonyme)
     * 3. Fallback final -> hastebin
     */
    private fun uploadToCloud(content: String, githubToken: String): String? {
        // === METHODE 1 : GitHub Gist (recommande) ===
        if (githubToken.isNotBlank()) {
            try {
                val url = URL("https://api.github.com/gists")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 15000
                conn.readTimeout = 20000
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                conn.setRequestProperty("Authorization", "Bearer $githubToken")
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")

                val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault()).format(java.util.Date())
                val gistPayload = org.json.JSONObject().apply {
                    put("description", "KIGHMU VPN Config $ts")
                    put("public", false)
                    put("files", org.json.JSONObject().apply {
                        put("kighmu_config.json", org.json.JSONObject().apply {
                            put("content", content)
                        })
                    })
                }

                conn.outputStream.use { it.write(gistPayload.toString().toByteArray()) }

                if (conn.responseCode == 201) {
                    val response = conn.inputStream.bufferedReader().readText()
                    val obj = org.json.JSONObject(response)
                    // Utiliser raw_url de la réponse API (format correct avec username)
                    val rawUrl = obj
                        .optJSONObject("files")
                        ?.optJSONObject("kighmu_config.json")
                        ?.optString("raw_url", "")
                    if (!rawUrl.isNullOrBlank()) {
                        return rawUrl
                    }
                    // Fallback: construire l'URL à partir de l'owner et du gistId
                    val gistId = obj.getString("id")
                    val owner = obj.optJSONObject("owner")?.optString("login", "") ?: ""
                    return if (owner.isNotBlank()) {
                        "https://gist.githubusercontent.com/$owner/$gistId/raw/kighmu_config.json"
                    } else {
                        "https://gist.github.com/$gistId"
                    }
                } else {
                    val err = conn.errorStream?.bufferedReader()?.readText() ?: "code=${conn.responseCode}"
                    android.util.Log.e("ExportActivity", "GitHub Gist erreur: $err")
                }
            } catch (e: Exception) {
                android.util.Log.e("ExportActivity", "GitHub Gist exception: ${e.message}")
            }
        }

        // === METHODE 2 : paste.ee (anonyme) ===
        try {
            val url = URL("https://api.paste.ee/v1/pastes")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 15000
            conn.readTimeout = 20000
            conn.setRequestProperty("Content-Type", "application/json")
            val payload = org.json.JSONObject().apply {
                put("description", "KIGHMU VPN Cloud Config")
                put("sections", org.json.JSONArray().apply {
                    put(org.json.JSONObject().apply {
                        put("name", "KIGHMU Config")
                        put("contents", content)
                    })
                })
            }
            conn.outputStream.use { it.write(payload.toString().toByteArray()) }
            if (conn.responseCode == 201 || conn.responseCode == 200) {
                val response = conn.inputStream.bufferedReader().readText()
                val obj = org.json.JSONObject(response)
                if (obj.optBoolean("success", false)) {
                    return obj.getString("link").replace("/p/", "/r/")
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("ExportActivity", "paste.ee exception: ${e.message}")
        }

        // === METHODE 3 : hastebin (fallback final) ===
        return try {
            val url = URL("https://hastebin.com/documents")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 15000
            conn.readTimeout = 20000
            conn.setRequestProperty("Content-Type", "text/plain")
            conn.outputStream.use { it.write(content.toByteArray()) }
            if (conn.responseCode == 200) {
                val response = conn.inputStream.bufferedReader().readText()
                val key = org.json.JSONObject(response).getString("key")
                "https://hastebin.com/raw/$key"
            } else null
        } catch (e: Exception) {
            android.util.Log.e("ExportActivity", "hastebin exception: ${e.message}")
            null
        }
    }

    private fun setupExportButtons() {
        findViewById<Button>(R.id.btn_export_save_file).setOnClickListener {
            showExportTypeDialog(share = false, locked = findViewById<CheckBox>(R.id.cb_lock_all_config).isChecked)
        }
    }

    private fun showExportTypeDialog(share: Boolean, locked: Boolean) {
        val options = arrayOf("Normal", "Usage unique (Burn)", "Avec expiration")
        AlertDialog.Builder(this)
            .setTitle("Type d'exportation")
            .setItems(options) { _, which ->
                exportType = when(which) {
                    1 -> "burn"
                    2 -> "expiry"
                    else -> "normal"
                }
                exportConfig(locked, share)
            }
            .show()
    }

    private fun exportConfig(locked: Boolean, @Suppress("UNUSED_PARAMETER") share: Boolean) {
        savePersistedData()
        val fileName = findViewById<EditText>(R.id.et_export_filename).text.toString().ifBlank { "kighmu_config" }
        val userMessage = findViewById<EditText>(R.id.et_user_message).text.toString()
        val lockOperator = findViewById<CheckBox>(R.id.cb_lock_operator).isChecked
        val operatorName = if (lockOperator) (getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager).networkOperatorName else ""
        val lockDeviceId = findViewById<CheckBox>(R.id.cb_lock_device_id).isChecked
        val androidId = if (lockDeviceId) com.kighmu.vpn.config.ConfigEncryption.getHardwareId(this).uppercase() else ""

        val config = viewModel.config.value
        val isBurn = exportType == "burn"
        val exportConfig = ExportConfig(
            fileName = fileName,
            expiresAt = if (exportType == "expiry") expiresAt else 0L,
            hardwareId = androidId,
            lockOperator = lockOperator,
            operatorName = operatorName,
            blockRoot = findViewById<CheckBox>(R.id.cb_block_root).isChecked,
            mobileDataOnly = findViewById<CheckBox>(R.id.cb_mobile_data_only).isChecked,
            lockDeviceId = lockDeviceId,
            playStoreOnly = findViewById<CheckBox>(R.id.cb_playstore_only).isChecked,
            disableOverride = findViewById<CheckBox>(R.id.cb_disable_override).isChecked,
            blockTorrent = findViewById<CheckBox>(R.id.cb_block_torrent).isChecked,
            gameMode = findViewById<CheckBox>(R.id.cb_game_mode).isChecked,
            userMessage = userMessage,
            exportType = exportType,
            burnAfterImport = isBurn,
            burnToken = if (isBurn) UUID.randomUUID().toString() else "",
            lockAllConfig = locked || findViewById<CheckBox>(R.id.cb_lock_all_config).isChecked,
            appId = packageName,
            securitySignature = buildSignature(config.toString(), androidId, expiresAt, operatorName)
        )

        val exportPackage = mapOf(
            "config" to config,
            "security" to exportConfig,
            "exportedAt" to System.currentTimeMillis(),
            "appVersion" to com.kighmu.vpn.BuildConfig.VERSION_NAME
        )

        val json = Gson().toJson(exportPackage)
        val finalFileName = if (fileName.endsWith(".kighmu")) fileName else "$fileName.kighmu"
        
        // Sauvegarder le fichier localement
        try {
            openFileOutput(finalFileName, Context.MODE_PRIVATE).use { 
                it.write(json.toByteArray()) 
            }
            Toast.makeText(this, "Config sauvegardée: $finalFileName", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Erreur: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun buildSignature(config: String, hwid: String, expiry: Long, operator: String): String {
        val salt = "KIGHMU_SECURE_SALT_2026"
        val input = "$config|$hwid|$expiry|$operator|$salt"
        return MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private fun copyToClipboard(label: String, text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(this, "$label copié !", Toast.LENGTH_SHORT).show()
    }
}
