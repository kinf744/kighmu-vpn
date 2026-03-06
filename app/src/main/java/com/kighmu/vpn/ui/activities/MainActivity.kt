package com.kighmu.vpn.ui.activities

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tv = TextView(this)
        tv.text = "KIGHMU VPN fonctionne!"
        tv.textSize = 24f
        setContentView(tv)
    }
    fun requestVpnConnect() {}
    fun requestVpnDisconnect() {}
    fun showMessage(msg: String) {}
}