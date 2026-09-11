package com.twks.wifi

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class BoostReport(val lines: List<String>, val pingBefore: Long, val pingAfter: Long)

object Booster {
    private val saved = mutableMapOf<String, String>()

    private fun suRaw(cmd: String): String {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            val out = p.inputStream.bufferedReader().readText()
            val err = p.errorStream.bufferedReader().readText()
            p.waitFor()
            (out + " " + err).trim()
        } catch (e: Exception) {
            "EXC:${e.message}"
        }
    }

    // запись через stdin su-шелла: переживает Magisk, KernelSU и суперюзер-варианты
    private fun suShell(cmd: String): String {
        return try {
            val p = Runtime.getRuntime().exec("su")
            p.outputStream.use { os ->
                os.write("$cmd\n".toByteArray())
                os.write("exit\n".toByteArray())
            }
            val out = p.inputStream.bufferedReader().readText()
            val err = p.errorStream.bufferedReader().readText()
            p.waitFor()
            (out + " " + err).trim()
        } catch (e: Exception) {
            "EXC:${e.message}"
        }
    }

    fun rootOk(): Boolean = suRaw("id").contains("uid=0") || suShell("id").contains("uid=0")

    private fun sh(cmd: String): String {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor()
            out.trim()
        } catch (e: Exception) {
            ""
        }
    }

    fun pingMs(host: String): Long {
        val r = sh("ping -c 4 -W 2 $host")
        val m = Regex("=\\s*[\\d.]+/([\\d.]+)").find(r)
        return (m?.groupValues?.get(1)?.toFloatOrNull() ?: -1f).toLong()
    }

    private fun readSys(path: String): String = try { File(path).readText().trim() } catch (e: Exception) { "" }

    private fun writeSys(path: String, value: String): Boolean {
        if (!File(path).exists()) return false
        val before = readSys(path)
        if (before.isNotEmpty() && !saved.containsKey(path)) saved[path] = before
        suShell("echo '$value' > '$path'")
        if (readSys(path) != value) suRaw("echo $value > $path")
        return readSys(path) == value
    }

    suspend fun boost(gateway: String): BoostReport = withContext(Dispatchers.IO) {
        val lines = mutableListOf<String>()
        lines.add(if (rootOk()) "ROOT: uid=0 detected" else "ROOT: NOT GRANTED — writes will fail")
        val before = pingMs(gateway)
        lines.add("PING BEFORE: ${before}ms -> $gateway")

        val psOut = suShell("iw dev wlan0 set power_save off")
        val psNow = sh("iw dev wlan0 get power_save 2>/dev/null")
        lines.add("WIFI POWERSAVE: ${if (psNow.contains("off")) "off (applied)" else if (psOut.isEmpty()) "cmd sent, verify unsupported" else "raw: ${psOut.take(40)}"}")

        val avail = readSys("/proc/sys/net/ipv4/tcp_available_congestion_control")
        if (avail.contains("bbr")) {
            val cc = writeSys("/proc/sys/net/ipv4/tcp_congestion_control", "bbr")
            lines.add("TCP CC: bbr ${if (cc) "applied" else "write refused"}")
        } else {
            lines.add("TCP CC: bbr not in kernel · available: $avail")
        }

        val params = listOf(
            "/proc/sys/net/ipv4/tcp_slow_start_after_idle" to "0",
            "/proc/sys/net/ipv4/tcp_no_metrics_save" to "1",
            "/proc/sys/net/ipv4/tcp_mtu_probing" to "1",
            "/proc/sys/net/ipv4/tcp_syn_retries" to "3",
            "/proc/sys/net/ipv4/tcp_fin_timeout" to "15",
            "/proc/sys/net/ipv4/tcp_keepalive_time" to "120",
            "/proc/sys/net/core/rmem_max" to "4194304",
            "/proc/sys/net/core/wmem_max" to "4194304"
        )
        val okCount = params.count { writeSys(it.first, it.second) }
        lines.add("TCP STACK: $okCount/8 params applied")

        val govPaths = mutableSetOf<String>()
        File("/sys/devices/system/cpu").listFiles()
            ?.filter { it.name.matches(Regex("cpu\\d+")) }
            ?.forEach { govPaths.add("${it.path}/cpufreq/scaling_governor") }
        File("/sys/devices/system/cpu/cpufreq").listFiles()
            ?.filter { it.name.startsWith("policy") }
            ?.forEach { govPaths.add("${it.path}/scaling_governor") }
        val existing = govPaths.filter { File(it).exists() }
        val govOk = existing.count { writeSys(it, "performance") }
        lines.add("CPU GOVERNOR: $govOk/${existing.size} paths -> performance")

        val after = pingMs(gateway)
        val delta = before - after
        lines.add("PING AFTER: ${after}ms · delta ${delta}ms")
        lines.add(if (delta > 2) "BOOST EFFECT: real, -$delta ms" else "BOOST EFFECT: within noise, tweaks stay active")
        BoostReport(lines, before, after)
    }

    suspend fun restore(): List<String> = withContext(Dispatchers.IO) {
        val lines = mutableListOf<String>()
        saved.forEach { (path, value) ->
            suShell("echo '$value' > '$path'")
        }
        lines.add("RESTORED ${saved.size} params")
        suShell("iw dev wlan0 set power_save on")
        saved.clear()
        lines.add("WIFI POWERSAVE ON")
        lines
    }
}
