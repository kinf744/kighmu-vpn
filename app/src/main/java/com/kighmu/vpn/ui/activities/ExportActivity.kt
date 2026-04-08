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

                    // Alternative sûre : Utilisation de Pastebin API (ou similaire)
                    // Pour cet exemple, nous utilisons une méthode POST standard vers un service de stockage de texte
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
            // Utilisation de hastebin.com ou similaire (service public gratuit et anonyme)
            val url = URL("https://hastebin.com/documents")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "text/plain")
            
            conn.outputStream.use { it.write(content.toByteArray()) }
            
            if (conn.responseCode == 200) {
                val response = conn.inputStream.bufferedReader().readText()
                val key = Gson().fromJson(response, Map::class.java)["key"] as String
                "https://hastebin.com/$key"
            } else null
        } catch (e: Exception) {
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
        if (share) {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, json)
            }
            startActivity(Intent.createChooser(intent, "Partager la configuration"))
        } else {
            saveToFile(fileName, json)
        }
    }

    private fun saveToFile(name: String, json: String) {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_TITLE, "$name.kighmu")
        }
        startActivityForResult(intent, 1001)
        tempJson = json
    }

    private var tempJson: String? = null
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1001 && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                contentResolver.openOutputStream(uri)?.use { it.write(tempJson?.toByteArray() ?: return) }
                Toast.makeText(this, "Fichier sauvegardé", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun buildSignature(config: String, hwid: String, expiry: Long, op: String): String {
        val data = config + hwid + expiry + op + "KIGHMU_SECRET_SALT"
        return MessageDigest.getInstance("SHA-256").digest(data.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private fun generateCode(length: Int): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return (1..length).map { chars.random() }.joinToString("")
    }

    private fun copyToClipboard(label: String, text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(this, "$label copié", Toast.LENGTH_SHORT).show()
    }
}
