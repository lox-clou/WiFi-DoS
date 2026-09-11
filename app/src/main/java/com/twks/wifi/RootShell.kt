package com.twks.wifi

import java.io.DataOutputStream
import kotlin.random.Random

object RootShell {
    fun exec(cmd: String): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(process.outputStream)
            os.writeBytes("$cmd\nexit\n")
            os.flush()
            process.waitFor() == 0
        } catch (e: Exception) {
            false
        }
    }

    fun randomizeMac(iface: String = "wlan0"): Boolean {
        val random = Random
        val mac = String.format("02:%02x:%02x:%02x:%02x:%02x", 
            random.nextInt(256), random.nextInt(256), 
            random.nextInt(256), random.nextInt(256), random.nextInt(256))
        return exec("ip link set $iface down") &&
               exec("ip link set $iface address $mac") &&
               exec("ip link set $iface up")
    }
    
    fun restoreMac(iface: String = "wlan0") {
        exec("ip link set $iface down")
        exec("iw dev $iface set type managed")
        exec("ip link set $iface up")
    }
}
