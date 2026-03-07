package com.kighmu.vpn.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.kighmu.vpn.R
import com.kighmu.vpn.models.SshCredentials
import com.kighmu.vpn.ui.MainViewModel

class ConfigFragment : Fragment() {
    private val viewModel: MainViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_config, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val etHost = view.findViewById<EditText>(R.id.et_ssh_host)
        val etPort = view.findViewById<EditText>(R.id.et_ssh_port)
        val etUser = view.findViewById<EditText>(R.id.et_ssh_user)
        val etPass = view.findViewById<EditText>(R.id.et_ssh_pass)
        val etName = view.findViewById<EditText>(R.id.et_config_name)
        val btnSave = view.findViewById<Button>(R.id.btn_save_config)

        val config = viewModel.config.value
        etName.setText(config.configName)
        etHost.setText(config.sshCredentials.host)
        etPort.setText(config.sshCredentials.port.toString())
        etUser.setText(config.sshCredentials.username)
        etPass.setText(config.sshCredentials.password)

        btnSave.setOnClickListener {
            val ssh = SshCredentials(
                host = etHost.text.toString(),
                port = etPort.text.toString().toIntOrNull() ?: 22,
                username = etUser.text.toString(),
                password = etPass.text.toString()
            )
            viewModel.updateSshCredentials(ssh)
            android.widget.Toast.makeText(requireContext(), "Config saved!", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}