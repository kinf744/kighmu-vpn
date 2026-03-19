package chzPsiphonAndV2ray

interface V2RayVPNServiceSupportsSet {
    fun onEmitStatus(l: Long, s: String): Long
    fun prepare(): Long
    fun protect(fd: Long): Boolean
    fun setup(conf: String): Long
    fun shutdown(): Long
}
