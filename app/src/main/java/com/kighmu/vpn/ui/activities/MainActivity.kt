package com.kighmu.vpn.ui.activities

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.viewModels
import androidx.navigation.findNavController
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.kighmu.vpn.R
import com.kighmu.vpn.databinding.ActivityMainBinding
import com.kighmu.vpn.ui.MainViewModel

class MainActivity : AppCompatActivity() {
    val viewModel: MainViewModel by viewModels()
    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)
            setSupportActionBar(binding.toolbar)
            val navView: BottomNavigationView = binding.bottomNav
            val navController = findNavController(R.id.nav_host_fragment)
            setupActionBarWithNavController(navController)
            navView.setupWithNavController(navController)
            Toast.makeText(this, "Navigation OK", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "ERREUR: " + e.javaClass.simpleName + ": " + e.message, Toast.LENGTH_LONG).show()
        }
    }
    fun requestVpnConnect() {}
    fun requestVpnDisconnect() {}
    fun showMessage(msg: String) {}
}