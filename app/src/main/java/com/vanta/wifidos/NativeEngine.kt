package com.vanta.wifidos

object NativeEngine {
    init { System.loadLibrary("vanta_dos") }
    external fun startAttack(targetIp: String, threads: Int, useDeauth: Boolean, iface: String?, targetMac: ByteArray?, apMac: ByteArray?)
    external fun stopAttack()
    external fun getPacketCount(): Long
}
