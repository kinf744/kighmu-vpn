package hev.htproxy

import android.util.Log

object TProxyService {
    private var loaded = false
    const val TAG = "TProxyService"

    init {
        try {
            System.loadLibrary("tun2socks")
            loaded = true
            Log.i(TAG, "hev chargé ✅")
        } catch (e: Throwable) {
            Log.e(TAG, "Load failed: ${e.message}")
        }
    }

    val isAvailable get() = loaded

    external fun TProxyStartService(configPath: String, fd: Int)
    external fun TProxyStopService()
    external fun TProxyGetStats(): LongArray?
}
