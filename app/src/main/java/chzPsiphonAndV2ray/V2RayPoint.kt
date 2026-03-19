package chzPsiphonAndV2ray

class V2RayPoint(private val refnum: Int) {
    external fun runLoop(testOnly: Boolean)
    external fun stopLoop()
}
