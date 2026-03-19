package chzPsiphonAndV2ray

object ChzPsiphonAndV2ray {
    var isLoaded = false

    fun tryLoad(): Boolean {
        if (isLoaded) return true
        return try {
            System.loadLibrary("gojni")
            isLoaded = true
            true
        } catch (e: Throwable) {
            android.util.Log.e("ChzPsiphonAndV2ray", "libgojni load failed: ${e.message}")
            false
        }
    }

    @JvmStatic external fun newV2RayPoint(
        vpnServiceSupports: V2RayVPNServiceSupportsSet, 
        adVpn: Boolean
    ): V2RayPoint

    @JvmStatic external fun touch()
}
