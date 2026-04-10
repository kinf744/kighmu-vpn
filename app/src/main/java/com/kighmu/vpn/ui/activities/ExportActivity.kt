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

                    // Utilisation de l'API Pastebin (ou alternative)
                    val pasteUrl = uploadToPasteService(json)

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

    private fun uploadToPasteService(content: String): String? {
        return try {
            // Utilisation de paste.ee comme alternative plus stable
            val url = URL("https://api.paste.ee/v1/pastes")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            
            // Note: Idéalement utiliser une clé API, mais paste.ee permet des pastes anonymes
            val payload = mapOf(
                "sections" to listOf(
                    mapOf(
                        "name" to "KIGHMU Config",
                        "contents" to content
                    )
                ),
                "description" to "KIGHMU VPN Cloud Config"
            )
            
            conn.outputStream.use { it.write(Gson().toJson(payload).toByteArray()) }
            
            if (conn.responseCode == 201 || conn.responseCode == 200) {
                val response = conn.inputStream.bufferedReader().readText()
                val obj = org.json.JSONObject(response)
                if (obj.getBoolean("success")) {
                    obj.getString("link").replace("/p/", "/r/") // Lien raw pour l'import
                } else null
            } else null
        } catch (e: Exception) {
            // Fallback vers hastebin si paste.ee échoue
            try {
                val url = URL("https://hastebin.com/documents")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "text/plain")
                conn.outputStream.use { it.write(content.toByteArray()) }
                if (conn.responseCode == 200) {
                    val response = conn.inputStream.bufferedReader().readText()
                    val key = org.json.JSONObject(response).getString("key")
                    "https://hastebin.com/raw/$key"
                } else null
            } catch (e2: Exception) {
                null
            }
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

    private fun exportConfig(locked: Boolean, share: Boolean) {
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
