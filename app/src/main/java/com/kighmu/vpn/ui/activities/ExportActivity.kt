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
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageReference


class ExportActivity : AppCompatActivity() {
    private val viewModel: MainViewModel by viewModels()
    private var expiresAt = 0L
    private var exportType = "normal"  // normal | burn | expiry
    private lateinit var storageRef: StorageReference


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_export)

        // setupAccessCode()
        setupExpiryDate()
        setupExportButtons()
        setupCloudExport()
        storageRef = FirebaseStorage.getInstance().reference

    }


    private fun setupExpiryDate() {
        val cbExpiry = findViewById<CheckBox>(R.id.cb_set_expiry)
        val layoutExpiry = findViewById<View>(R.id.layout_expiry)
        val tvDate = findViewById<TextView>(R.id.tv_expiry_date)
        val tvTime = findViewById<TextView>(R.id.tv_expiry_time)
        // Calendrier interne pour conserver date + heure sélectionnées
        val expiryCalendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
        }
        cbExpiry.setOnCheckedChangeListener { _, checked ->
            layoutExpiry.visibility = if (checked) View.VISIBLE else View.GONE
            if (!checked) expiresAt = 0L
        }
        // Sélection de la date
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
        // Sélection de l'heure et des minutes
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
                true // Format 24h
            ).show()
        }
    }

    private fun setupCloudExport() {
        val rgCloud = findViewById<android.widget.RadioGroup>(R.id.rg_cloud_type)
        val layoutDuration = findViewById<android.view.View>(R.id.layout_cloud_duration)
        val layoutResult = findViewById<android.view.View>(R.id.layout_cloud_result)

        rgCloud.setOnCheckedChangeListener { _, id ->
            layoutDuration.visibility = if (id == R.id.rb_cloud_limited) 
                android.view.View.VISIBLE else android.view.View.GONE
        }

        findViewById<android.widget.Button>(R.id.btn_export_cloud).setOnClickListener {
            val isLimited = rgCloud.checkedRadioButtonId == R.id.rb_cloud_limited
            val durationMinutes = if (isLimited) {
                findViewById<android.widget.EditText>(R.id.et_cloud_duration)
                    .text.toString().toLongOrNull() ?: 60L
            } else 0L

            val btn = it as android.widget.Button
            btn.isEnabled = false
            btn.text = "Envoi en cours..."

            android.os.Handler(android.os.Looper.getMainLooper()).post {
                Thread {
                    try {
                        // Préparer le JSON avec expiration si limitée
                        val expiresAt = if (durationMinutes > 0) 
                            System.currentTimeMillis() + durationMinutes * 60 * 1000L else 0L
                        
                        val config = viewModel.config.value
                        // Construire ExportConfig complet pour cloud
                        val cloudLockDevice = findViewById<android.widget.CheckBox>(R.id.cb_lock_device_id).isChecked
                        val cloudLockOp = findViewById<android.widget.CheckBox>(R.id.cb_lock_operator).isChecked
                        val cloudSecurity = ExportConfig(
                            expiresAt = expiresAt,
                            exportType = if (isLimited) "expiry" else "normal",
                            appId = packageName,
                            userMessage = findViewById<android.widget.EditText>(R.id.et_user_message).text.toString(),
                            lockAllConfig = findViewById<android.widget.CheckBox>(R.id.cb_lock_all_config).isChecked,
                            accessCode = "",
                            lockDeviceId = cloudLockDevice,
                            hardwareId = if (cloudLockDevice) com.kighmu.vpn.config.ConfigEncryption.getHardwareId(this@ExportActivity).uppercase() else "",
                            lockOperator = cloudLockOp,
                            operatorName = if (cloudLockOp) (getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager).networkOperatorName else "",
                            burnAfterImport = exportType == "burn",
                            securitySignature = buildSignature(config.toString(), "", expiresAt, "")
                        )
                        val exportPackage = mapOf(
                            "config" to config,
                            "security" to cloudSecurity,
                            "exportedAt" to System.currentTimeMillis(),
                            "appVersion" to com.kighmu.vpn.BuildConfig.VERSION_NAME
                        )
                        val json = com.google.gson.Gson().toJson(exportPackage)

                        // Upload via Firebase Storage
                        val code = generateCode(8)
                        val fileName = "$code.json"
                        val configBytes = json.toByteArray(Charsets.UTF_8)

                        val configRef = storageRef.child("configs/$fileName")
                        val uploadTask = configRef.putBytes(configBytes)

                        uploadTask.addOnSuccessListener { taskSnapshot ->
                            taskSnapshot.storage.downloadUrl.addOnSuccessListener { uri ->
                                runOnUiThread {
                                    btn.isEnabled = true
                                    btn.text = "☁️ Exporter vers le cloud"
                                    val kighmuLink = "https://kighmu.link/$code"
                                    findViewById<android.view.View>(R.id.layout_cloud_result).visibility = android.view.View.VISIBLE
                                    findViewById<android.widget.TextView>(R.id.tv_cloud_link_kighmu).text = kighmuLink
                                    findViewById<android.widget.TextView>(R.id.tv_cloud_code_only).text = code

                                    findViewById<android.widget.Button>(R.id.btn_copy_cloud_link).setOnClickListener {
                                        copyToClipboard("Lien", kighmuLink)
                                    }
                                    findViewById<android.widget.Button>(R.id.btn_copy_cloud_code_only).setOnClickListener {
                                        copyToClipboard("Code", code)
                                    }
                                    findViewById<android.widget.Button>(R.id.btn_copy_all_cloud).setOnClickListener {
                                        copyToClipboard("Lien & Code", "Lien: $kighmuLink\nCode: $code")
                                    }

                                    android.widget.Toast.makeText(this@ExportActivity, "✓ Export Cloud Réussi", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }
                        }.addOnFailureListener { exception ->
                            runOnUiThread {
                                btn.isEnabled = true
                                btn.text = "☁️ Exporter vers le cloud"
                                android.widget.Toast.makeText(this@ExportActivity, "Erreur d'upload: ${exception.message}", android.widget.Toast.LENGTH_LONG).show()
                            }
                        }
                    } catch (e: Exception) {
                        runOnUiThread {
                            btn.isEnabled = true
                            btn.text = "☁️ Exporter vers le cloud"
                            android.widget.Toast.makeText(this, "Erreur: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                }.start()
            }
        }

    }

    private fun setupExportButtons() {
        findViewById<Button>(R.id.btn_export_save_file).setOnClickListener {
            showExportTypeDialog(share = false, locked = findViewById<CheckBox>(R.id.cb_lock_all_config).isChecked)
        }
    }

    private fun exportConfig(locked: Boolean, share: Boolean) {
        val fileName = findViewById<EditText>(R.id.et_export_filename).text.toString()
            .ifBlank { "kighmu_config" }
        val accessCode = ""
        val userMessage = findViewById<EditText>(R.id.et_user_message).text.toString()

        val lockOperator = findViewById<CheckBox>(R.id.cb_lock_operator).isChecked
        val operatorName = if (lockOperator) {
            (getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager).networkOperatorName
        } else ""

        val lockDeviceId = findViewById<CheckBox>(R.id.cb_lock_device_id).isChecked
        val androidId = if (lockDeviceId) {
            com.kighmu.vpn.config.ConfigEncryption.getHardwareId(this).uppercase()
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
            lockAllConfig     = locked || findViewById<android.widget.CheckBox>(R.id.cb_lock_all_config).isChecked,
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

    private fun copyToClipboard(label: String, text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(this, "$label copié !", Toast.LENGTH_SHORT).show()
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
