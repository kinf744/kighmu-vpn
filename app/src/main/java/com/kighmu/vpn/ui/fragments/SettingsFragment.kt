package com.kighmu.vpn.ui.fragments

import android.os.Bundle
import android.view.*
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.kighmu.vpn.BuildConfig
import com.kighmu.vpn.config.ConfigEncryption
import com.kighmu.vpn.databinding.FragmentSettingsBinding
import com.kighmu.vpn.ui.MainViewModel

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadSettings()
        setupListeners()
        showDeviceInfo()
    }

    private fun loadSettings() {
        val cfg = viewModel.config.value
        binding.switchKillSwitch.isChecked = cfg.killSwitch
        binding.switchAutoReconnect.isChecked = cfg.autoReconnect
        binding.switchDnsLeak.isChecked = cfg.dnsLeakProtection

        val prefs = requireContext().getSharedPreferences("kighmu_prefs", android.content.Context.MODE_PRIVATE)
        binding.switchAutoStartBoot.isChecked = prefs.getBoolean("auto_start_on_boot", false)
        binding.switchDarkMode.isChecked = prefs.getBoolean("dark_mode", true)
    }

    private fun setupListeners() {
        binding.switchKillSwitch.setOnCheckedChangeListener { _, checked ->
            viewModel.saveConfig(viewModel.config.value.copy(killSwitch = checked))
        }

        binding.switchAutoReconnect.setOnCheckedChangeListener { _, checked ->
            viewModel.saveConfig(viewModel.config.value.copy(autoReconnect = checked))
        }

        binding.switchDnsLeak.setOnCheckedChangeListener { _, checked ->
            viewModel.saveConfig(viewModel.config.value.copy(dnsLeakProtection = checked))
        }

        binding.switchAutoStartBoot.setOnCheckedChangeListener { _, checked ->
            requireContext().getSharedPreferences("kighmu_prefs", android.content.Context.MODE_PRIVATE)
                .edit().putBoolean("auto_start_on_boot", checked).apply()
        }

        binding.switchDarkMode.setOnCheckedChangeListener { _, checked ->
            requireContext().getSharedPreferences("kighmu_prefs", android.content.Context.MODE_PRIVATE)
                .edit().putBoolean("dark_mode", checked).apply()
            // Apply theme
            val mode = if (checked) android.app.UiModeManager.MODE_NIGHT_YES
            else android.app.UiModeManager.MODE_NIGHT_NO
            androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
                if (checked) androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
                else androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
            )
        }
    }

    private fun showDeviceInfo() {
        val hwId = ConfigEncryption.getHardwareId(requireContext())
        binding.tvDeviceId.text = "Device ID: $hwId"
        binding.tvAppVersion.text = "Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
        binding.tvPackage.text = "Package: ${requireContext().packageName}"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
