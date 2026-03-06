package com.kighmu.vpn.ui.activities

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.TextView
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SplashActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            super.onCreate(savedInstanceState)
            val layout = LinearLayout(this)
            layout.setBackgroundColor(Color.parseColor("#0D1117"))
            val text = TextView(this)
            text.text = "KIGHMU VPN"
            text.setTextColor(Color.WHITE)
            text.textSize = 24f
            layout.addView(text)
            setContentView(layout)
            lifecycleScope.launch {
                delay(1500)
                startActivity(Intent(this@SplashActivity, MainActivity::class.java))
                finish()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
