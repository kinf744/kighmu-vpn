package com.kighmu.vpn.ui.activities

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.viewModels
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.kighmu.vpn.R
import com.kighmu.vpn.ui.MainViewModel

class MainActivity : AppCompatActivity() {
    val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_main)
            val navHostFragment = supportFragmentManager
                .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
            val navController = navHostFragment.navController
            val navView = findViewById<BottomNavigationView>(R.id.bottom_nav)
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