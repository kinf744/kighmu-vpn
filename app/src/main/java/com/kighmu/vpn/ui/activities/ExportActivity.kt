package com.kighmu.vpn.ui.activities

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.telephony.TelephonyManager
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.viewModels
import com.google.gson.Gson
import com.kighmu.vpn.R
import com.kighmu.vpn.models.ExportConfig
import com.kighmu.vpn.ui.MainViewModel
import java.security.MessageDigest
import java.util.Calendar

class ExportActivity : AppCompatActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_export)

        val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        val fingerprint = android.os.Build.FINGERPRINT
        val myHardwareId = "$androidId|$fingerprint".take(64)

        findViewById<Button>(R.id.btn_use_my_id).setOnClickListener {
            findViewById<EditText>(R.id.et_hardware_id).setText(myHardwareId)
        }

        findViewById<Button>(R.id.btn_export).setOnClickListener {
            val fileName = findViewById<EditText>(R.id.et_export_filename).text.toString()
                .ifBlank { "kighmu_config" }
            val year = findViewById<EditText>(R.id.et_exp_year).text.toString().toIntOrNull()
            val month = findViewById<EditText>(R.id.et_exp_month).text.toString().toIntOrNull()
            val hour = findViewById<EditText>(R.id.et_exp_hour).text.toString().toIntOrNull()
            val minute = findViewById<EditText>(R.id.et_exp_minute).text.toString().toIntOrNull()
            val hardwareId = findViewById<EditText>(R.id.et_hardware_id).text.toString()
            val lockOperator = findViewById<Switch>(R.id.switch_lock_operator).isChecked
            val blockRoot = findViewById<Switch>(R.id.switch_block_root).isChecked

            // Calculate expiry timestamp
            var expiresAt = 0L
            if (year != null && month != null) {
                val cal = Calendar.getInstance()
                cal.set(Calendar.YEAR, year)
                cal.set(Calendar.MONTH, month - 1)
                cal.set(Calendar.HOUR_OF_DAY, hour ?: 23)
                cal.set(Calendar.MINUTE, minute ?: 59)
                expiresAt = cal.timeInMillis
            }

            // Get operator name if needed
            val operatorName = if (lockOperator) {
                val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                tm.networkOperatorName
            } else ""

            // Create signature
            val config = viewModel.config.value
            val configJson = Gson().toJson(config)
            val signatureInput = "$configJson$hardwareId$expiresAt$operatorName"
            val signature = MessageDigest.getInstance("SHA-256")
                .digest(signatureInput.toByteArray())
                .joinToString("") { "%02x".format(it) }

            val exportConfig = ExportConfig(
                fileName = fileName,
                expiresAt = expiresAt,
                hardwareId = hardwareId,
                lockOperator = lockOperator,
                operatorName = operatorName,
                blockRoot = blockRoot,
                securitySignature = signature
            )

            // Build final export package
            val exportPackage = mapOf(
                "config" to config,
                "security" to exportConfig,
                "exportedAt" to System.currentTimeMillis(),
                "appVersion" to com.kighmu.vpn.BuildConfig.VERSION_NAME
            )

            val json = Gson().toJson(exportPackage)

            // Share the file
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/octet-stream"
                putExtra(Intent.EXTRA_TITLE, "$fileName.kighmu")
            }
            exportJson = json
            startActivityForResult(intent, REQUEST_EXPORT)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_EXPORT && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                contentResolver.openOutputStream(uri)?.use { stream ->
                    stream.write(exportJson.toByteArray())
                }
                Toast.makeText(this, "Config exportée!", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    companion object {
        const val REQUEST_EXPORT = 1001
        var exportJson = ""
    }
}