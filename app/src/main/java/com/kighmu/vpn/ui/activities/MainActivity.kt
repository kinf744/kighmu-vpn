package com.kighmu.vpn.ui.activities

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.viewModels
import com.kighmu.vpn.ui.MainViewModel

class MainActivity : AppCompatActivity() {
    val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            viewModel
            Toast.makeText(this, "ViewModel OK", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "ERREUR: " + e.javaClass.simpleName + ": " + e.message, Toast.LENGTH_LONG).show()
        }
    }
    fun requestVpnConnect() {}
    fun requestVpnDisconnect() {}
    fun showMessage(msg: String) {}
}