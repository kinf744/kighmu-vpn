package chzPsiphonAndV2ray

object ChzPsiphonAndV2ray {
    init {
        System.loadLibrary("gojni")
    }
    @JvmStatic external fun newV2RayPoint(vpnServiceSupports: V2RayVPNServiceSupportsSet, adVpn: Boolean): V2RayPoint
    @JvmStatic external fun touch()
}
