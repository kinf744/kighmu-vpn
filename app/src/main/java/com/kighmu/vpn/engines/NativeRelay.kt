package com.kighmu.vpn.engines

object NativeRelay {
    init {
        try {
            System.loadLibrary("kighmu_relay")
        } catch (_: Exception) {}
    }

    @JvmStatic
    external fun relay(fd1: Int, fd2: Int)

    val isAvailable: Boolean get() = try {
        NativeRelay // trigger init
        true
    } catch (_: Exception) { false }
}
