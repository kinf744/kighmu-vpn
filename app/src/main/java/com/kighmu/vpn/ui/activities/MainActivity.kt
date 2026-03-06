package com.kighmu.vpn.ui.activities

import android.os.Bundle
import android.widget.TextView
import android.graphics.Color
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this)
        layout.setBackgroundColor(Color.parseColor("#0D1117"))
        val text = TextView(this)
        text.text = "MainActivity OK"
        text.setTextColor(Color.WHITE)
        text.textSize = 20f
        layout.addView(text)
        setContentView(layout)
    }

    fun requestVpnConnect() {}
    fun requestVpnDisconnect() {}
    fun showMessage(msg: String) {}
}