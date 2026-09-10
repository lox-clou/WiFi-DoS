package com.vanta.wifidos
import java.io.DataOutputStream

object RootShell {
    fun exec(cmd: String): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(process.outputStream)
            os.writeBytes("$cmd\nexit\n")
            os.flush()
            process.waitFor() == 0
        } catch (e: Exception) { false }
    }
    fun enableMonitorMode(iface: String = "wlan0") {
        exec("ip link set $iface down")
        exec("iw dev $iface set type monitor")
        exec("ip link set $iface up")
    }
    fun disableMonitorMode(iface: String = "wlan0") {
        exec("ip link set $iface down")
        exec("iw dev $iface set type managed")
        exec("ip link set $iface up")
    }
}
