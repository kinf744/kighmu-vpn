package com.kighmu.vpn.ui.activities

import android.os.Bundle
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.findNavController
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.kighmu.vpn.R
import com.kighmu.vpn.databinding.ActivityMainBinding
import com.kighmu.vpn.ui.MainViewModel

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            super.onCreate(savedInstanceState)
            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)
            setSupportActionBar(binding.toolbar)
            val navView: BottomNavigationView = binding.bottomNav
            val navController = findNavController(R.id.nav_host_fragment)
            setupActionBarWithNavController(navController)
            navView.setupWithNavController(navController)
        } catch (e: Exception) {
            Toast.makeText(this, "ERREUR: " + e.message, Toast.LENGTH_LONG).show()
            e.printStackTrace()
        }
    }

    fun requestVpnConnect() {
        try {
            val permIntent = viewModel.startVpn(this)
            if (permIntent != null) {
                startActivityForResult(permIntent, 1)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "VPN Error: " + e.message, Toast.LENGTH_LONG).show()
        }
    }

    fun requestVpnDisconnect() {
        viewModel.stopVpn(this)
    }

    fun showMessage(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}