package com.twks.wifi

object NativeEngine {
    init { System.loadLibrary("twks_dos") }
    external fun startAttack(targetIp: String, threads: Int)
    external fun stopAttack()
    external fun getPacketCount(): Long
}
