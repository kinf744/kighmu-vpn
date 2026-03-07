package com.kighmu.vpn.ui.fragments

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.kighmu.vpn.BuildConfig
import com.kighmu.vpn.R
import com.kighmu.vpn.ui.MainViewModel

class SettingsFragment : Fragment() {
    private val viewModel: MainViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val prefs = requireContext().getSharedPreferences("kighmu_prefs", Context.MODE_PRIVATE)

        // Hardware ID
        val androidId = Settings.Secure.getString(requireContext().contentResolver, Settings.Secure.ANDROID_ID)
        val fingerprint = android.os.Build.FINGERPRINT
        val hardwareId = "$androidId|$fingerprint".take(64)
        view.findViewById<TextView>(R.id.tv_hardware_id).text = hardwareId

        view.findViewById<Button>(R.id.btn_copy_hwid).setOnClickListener {
            val cb = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cb.setPrimaryClip(ClipData.newPlainText("hardware_id", hardwareId))
            Toast.makeText(requireContext(), "Hardware ID copié!", Toast.LENGTH_SHORT).show()
        }

        // Dark mode
        val switchDark = view.findViewById<Switch>(R.id.switch_dark_mode)
        switchDark.isChecked = prefs.getBoolean("dark_mode", true)
        switchDark.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("dark_mode", checked).apply()
            AppCompatDelegate.setDefaultNightMode(
                if (checked) AppCompatDelegate.MODE_NIGHT_YES
                else AppCompatDelegate.MODE_NIGHT_NO
            )
        }

        // Notifications
        val switchNotif = view.findViewById<Switch>(R.id.switch_notifications)
        switchNotif.isChecked = prefs.getBoolean("notifications", true)
        switchNotif.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("notifications", checked).apply()
        }

        // Auto reconnect
        val switchReconnect = view.findViewById<Switch>(R.id.switch_auto_reconnect)
        switchReconnect.isChecked = prefs.getBoolean("auto_reconnect", true)
        switchReconnect.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("auto_reconnect", checked).apply()
        }

        // Kill switch
        val switchKill = view.findViewById<Switch>(R.id.switch_kill_switch)
        switchKill.isChecked = prefs.getBoolean("kill_switch", false)
        switchKill.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("kill_switch", checked).apply()
            Toast.makeText(requireContext(),
                if (checked) "Kill Switch activé" else "Kill Switch désactivé",
                Toast.LENGTH_SHORT).show()
        }

        // DNS protection
        val switchDns = view.findViewById<Switch>(R.id.switch_dns_protection)
        switchDns.isChecked = prefs.getBoolean("dns_protection", true)
        switchDns.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("dns_protection", checked).apply()
        }

        // Version
        view.findViewById<TextView>(R.id.tv_app_version).text =
            "KIGHMU VPN v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
    }
}