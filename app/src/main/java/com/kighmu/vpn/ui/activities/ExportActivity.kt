package com.kighmu.vpn.ui.activities

import android.app.AlertDialog
import android.app.DatePickerDialog
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

class ExportActivity : AppCompatActivity() {
    private val viewModel: MainViewModel by viewModels()
    private var expiresAt = 0L
    private var exportType = "normal"  // normal | burn | expiry

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_export)

        setupAccessCode()
        setupExpiryDate()
        setupExportButtons()
    }

    private fun setupAccessCode() {
        // Générer code aléatoire
        findViewById<Button>(R.id.btn_generate_code).setOnClickListener {
            val code = generateCode(8)
            findViewById<EditText>(R.id.et_access_code).setText(code)
        }
        // Copier code
        findViewById<Button>(R.id.btn_copy_code).setOnClickListener {
            val code = findViewById<EditText>(R.id.et_access_code).text.toString()
            if (code.isNotEmpty()) {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("code", code))
                Toast.makeText(this, "Code copié!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupExpiryDate() {
        val cbExpiry = findViewById<CheckBox>(R.id.cb_set_expiry)
        val layoutExpiry = findViewById<View>(R.id.layout_expiry)
        val tvDate = findViewById<TextView>(R.id.tv_expiry_date)

        cbExpiry.setOnCheckedChangeListener { _, checked ->
            layoutExpiry.visibility = if (checked) View.VISIBLE else View.GONE
            if (!checked) expiresAt = 0L
        }

        findViewById<Button>(R.id.btn_pick_date).setOnClickListener {
            val cal = Calendar.getInstance()
            DatePickerDialog(this,
                { _, year, month, day ->
                    cal.set(year, month, day, 23, 59, 59)
                    expiresAt = cal.timeInMillis
                    val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
                    tvDate.text = sdf.format(cal.time)
                    tvDate.setTextColor(0xFF009688.toInt())
                },
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH),
                cal.get(Calendar.DAY_OF_MONTH)
            ).show()
        }
    }

    private fun setupExportButtons() {
        findViewById<Button>(R.id.btn_export_save).setOnClickListener {
            showExportTypeDialog(share = false)
        }
        findViewById<Button>(R.id.btn_export_locked_unlocked).setOnClickListener {
            showExportTypeDialog(share = false, locked = true)
        }
        findViewById<Button>(R.id.btn_export_share).setOnClickListener {
            exportConfig(locked = false, share = true)
        }
        findViewById<Button>(R.id.btn_export_share2).setOnClickListener {
            exportConfig(locked = false, share = true)
        }
    }

    private fun exportConfig(locked: Boolean, share: Boolean) {
        val fileName = findViewById<EditText>(R.id.et_export_filename).text.toString()
            .ifBlank { "kighmu_config" }
        val accessCode = findViewById<EditText>(R.id.et_access_code).text.toString()
        val userMessage = findViewById<EditText>(R.id.et_user_message).text.toString()

        val lockOperator = findViewById<CheckBox>(R.id.cb_lock_operator).isChecked
        val operatorName = if (lockOperator) {
            (getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager).networkOperatorName
        } else ""

        val lockDeviceId = findViewById<CheckBox>(R.id.cb_lock_device_id).isChecked
        val androidId = if (lockDeviceId) {
            Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        } else ""

        val config = viewModel.config.value
        val isBurn = exportType == "burn"
        val exportConfig = ExportConfig(
            fileName          = fileName,
            accessCode        = accessCode,
            expiresAt         = if (exportType == "expiry") expiresAt else 0L,
            hardwareId        = androidId,
            lockOperator      = lockOperator,
            operatorName      = operatorName,
            blockRoot         = findViewById<CheckBox>(R.id.cb_block_root).isChecked,
            mobileDataOnly    = findViewById<CheckBox>(R.id.cb_mobile_data_only).isChecked,
            lockDeviceId      = lockDeviceId,
            playStoreOnly     = findViewById<CheckBox>(R.id.cb_playstore_only).isChecked,
            disableOverride   = findViewById<CheckBox>(R.id.cb_disable_override).isChecked,
            blockTorrent      = findViewById<CheckBox>(R.id.cb_block_torrent).isChecked,
            gameMode          = findViewById<CheckBox>(R.id.cb_game_mode).isChecked,
            userMessage       = userMessage,
            exportType        = exportType,
            burnAfterImport   = isBurn,
            burnToken         = if (isBurn) UUID.randomUUID().toString() else "",
            lockAllConfig     = locked,
            appId             = packageName,
            securitySignature = buildSignature(config.toString(), androidId, expiresAt, operatorName)
        )

        val exportPackage = mapOf(
            "config"     to config,
            "security"   to exportConfig,
            "exportedAt" to System.currentTimeMillis(),
            "appVersion" to com.kighmu.vpn.BuildConfig.VERSION_NAME
        )

        exportJson = Gson().toJson(exportPackage)

        if (share) {
            // Partager directement
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/octet-stream"
                putExtra(Intent.EXTRA_TEXT, exportJson)
                putExtra(Intent.EXTRA_SUBJECT, fileName)
            }
            startActivity(Intent.createChooser(shareIntent, "Partager config"))
        } else {
            // Sauvegarder fichier
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/octet-stream"
                putExtra(Intent.EXTRA_TITLE, if (locked) "${fileName}_locked.kighmu" else "$fileName.kighmu")
            }
            startActivityForResult(intent, REQUEST_EXPORT)
        }
    }

    private fun showExportTypeDialog(share: Boolean, locked: Boolean = false) {
        val options = arrayOf("Normal", "Brûler après importation", "Définir une date d'expiration")
        var selected = 0
        AlertDialog.Builder(this)
            .setTitle("Type de configuration")
            .setSingleChoiceItems(options, 0) { _, which -> selected = which }
            .setPositiveButton("Exporter la configuration") { _, _ ->
                exportType = when (selected) {
                    1 -> "burn"
                    2 -> "expiry"
                    else -> "normal"
                }
                exportConfig(locked = locked, share = share)
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun buildSignature(config: String, deviceId: String, expiry: Long, operator: String): String {
        val input = "$config$deviceId$expiry$operator"
        return MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private fun generateCode(length: Int): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        return (1..length).map { chars.random() }.joinToString("")
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_EXPORT && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                contentResolver.openOutputStream(uri)?.use { it.write(exportJson.toByteArray()) }
                Toast.makeText(this, "Config exportée avec succès!", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    companion object {
        const val REQUEST_EXPORT = 1001
        var exportJson = ""
    }
}
