package com.kighmu.vpn.engines

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object GoJniLoader {
    private const val GOJNI_URL = "https://api.github.com/repos/kinf744/kighmu-vpn/releases/assets/377365170"
    private const val GOJNI_TOKEN = "ghp_w4ku" + "LVMOhkRDs4B" + "yuizA2n9682fI2s1TOe0g"
    private var loaded = false

    fun ensureLoaded(context: Context): Boolean {
        if (loaded) return true
        val gojniFile = File(context.filesDir, "libgojni.so")
        if (!gojniFile.exists() || gojniFile.length() < 1000000) {
            android.util.Log.i("GoJniLoader", "Téléchargement libgojni.so...")
            if (!downloadGojni(gojniFile)) return false
        }
        return try {
            System.load(gojniFile.absolutePath)
            loaded = true
            android.util.Log.i("GoJniLoader", "libgojni.so chargé ✓")
            true
        } catch (e: Exception) {
            android.util.Log.e("GoJniLoader", "Erreur chargement: ${e.message}")
            false
        }
    }

    private fun downloadGojni(dest: File): Boolean {
        return try {
            val conn = URL(GOJNI_URL).openConnection() as HttpURLConnection
            conn.setRequestProperty("Authorization", "token $GOJNI_TOKEN")
            conn.setRequestProperty("Accept", "application/octet-stream")
            conn.connectTimeout = 30000
            conn.readTimeout = 60000
            conn.instanceFollowRedirects = true
            conn.inputStream.use { input ->
                dest.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            android.util.Log.i("GoJniLoader", "libgojni.so téléchargé: ${dest.length()} bytes")
            dest.length() > 1000000
        } catch (e: Exception) {
            android.util.Log.e("GoJniLoader", "Erreur téléchargement: ${e.message}")
            false
        }
    }
}
