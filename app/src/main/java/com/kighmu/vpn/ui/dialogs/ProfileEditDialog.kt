package com.kighmu.vpn.ui.dialogs

import android.app.AlertDialog
import android.content.Context
import android.widget.*
import com.kighmu.vpn.profiles.SlowDnsProfile

object ProfileEditDialog {

    fun show(
        context: Context,
        profile: SlowDnsProfile? = null,
        onSave: (SlowDnsProfile) -> Unit
    ) {
        val isEdit = profile != null
        val p = profile ?: SlowDnsProfile()

        val scroll = android.widget.ScrollView(context)
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 16)
        }
        scroll.addView(layout)

        fun section(title: String) = TextView(context).apply {
            text = title
            setTextColor(0xFF4FC3F7.toInt())
            textSize = 12f
            setPadding(0, 16, 0, 4)
            layoutParams = LinearLayout.LayoutParams(-1, -2)
            layout.addView(this)
        }

        fun field(hint: String, value: String, numeric: Boolean = false) = EditText(context).apply {
            this.hint = hint
            setText(value)
            setTextColor(0xFF000000.toInt())
            setHintTextColor(0xFF888888.toInt())
            if (numeric) inputType = android.text.InputType.TYPE_CLASS_NUMBER
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = 8 }
            layout.addView(this)
        }

        section("PROFILE")
        val etName = field("Profile Name", p.profileName)

        section("SSH CONFIGURATION")
        val etSshHost = field("SSH Host", p.sshHost)
        val etSshPort = field("SSH Port", p.sshPort.toString(), true)
        val etSshUser = field("Username", p.sshUser)
        val etSshPass = field("Password", p.sshPass).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }

        section("SLOWDNS CONFIGURATION")
        val etDns    = field("DNS Server", p.dnsServer)
        val etNs     = field("Nameserver", p.nameserver)
        val etPubKey = field("Public Key", p.publicKey)

        section("TUNNELS PARALLÈLES")
        val tvTunnelCount = TextView(context).apply {
            text = "Flux simultanés : ${p.tunnelCount}"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 13f
            setPadding(0, 8, 0, 4)
            layoutParams = LinearLayout.LayoutParams(-1, -2)
            layout.addView(this)
        }
        val seekTunnel = SeekBar(context).apply {
            max = 3  // 1..4 → 0..3
            progress = (p.tunnelCount.coerceIn(1, 4)) - 1
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = 4 }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, v: Int, u: Boolean) {
                    tvTunnelCount.text = "Flux simultanés : ${v + 1}"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
            layout.addView(this)
        }
        val tvTunnelHint = TextView(context).apply {
            text = "⚡ 1 flux = stable  |  2-3 flux = débit × N  |  4 flux = max"
            setTextColor(0xFF888888.toInt())
            textSize = 11f
            setPadding(0, 2, 0, 8)
            layoutParams = LinearLayout.LayoutParams(-1, -2)
            layout.addView(this)
        }

        AlertDialog.Builder(context)
            .setTitle(if (isEdit) "Modifier profil" else "Nouveau profil")
            .setView(scroll)
            .setPositiveButton("Sauvegarder") { _, _ ->
                val updated = p.copy(
                    profileName  = etName.text.toString().ifEmpty { "Profil" },
                    sshHost      = etSshHost.text.toString(),
                    sshPort      = etSshPort.text.toString().toIntOrNull() ?: 22,
                    sshUser      = etSshUser.text.toString(),
                    sshPass      = etSshPass.text.toString(),
                    dnsServer    = etDns.text.toString().ifEmpty { "8.8.8.8" },
                    nameserver   = etNs.text.toString(),
                    publicKey    = etPubKey.text.toString(),
                    tunnelCount  = seekTunnel.progress + 1
                )
                onSave(updated)
            }
            .setNegativeButton("Annuler", null)
            .show()
    }
}
