package com.twks.wifi

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import java.io.File

class CardEmulatorService : HostApduService() {
    private var dump: CardDump? = null
    private var linear: ByteArray = ByteArray(0)

    private val SW_OK = byteArrayOf(0x90.toByte(), 0x00.toByte())
    private fun ok(payload: ByteArray = ByteArray(0)): ByteArray = payload + SW_OK
    private fun err(a: Int, b: Int): ByteArray = byteArrayOf(a.toByte(), b.toByte())

    private fun ensureDump() {
        if (dump == null) {
            dump = try { CardCloner.loadDump(File(filesDir, "card_dump.bin")) } catch (e: Exception) { null }
            dump?.let { d ->
                val out = mutableListOf<Byte>()
                d.sectors.forEach { s -> s.blocks.forEach { b -> out.addAll(b.toList()) } }
                linear = out.toByteArray()
            }
        }
    }

    private fun log(apdu: ByteArray) {
        try {
            File(filesDir, "apdu_log.txt").appendText(
                System.currentTimeMillis().toString() + " " + apdu.joinToString("") { "%02X".format(it) } + "\n"
            )
        } catch (e: Exception) {}
    }

    override fun processCommandApdu(commandApdu: ByteArray, extras: Bundle?): ByteArray? {
        log(commandApdu)
        ensureDump()
        if (dump == null || linear.isEmpty()) return err(0x6A, 0x82)
        if (commandApdu.size < 4) return err(0x67, 0x00)

        val ins = commandApdu[1].toInt() and 0xFF
        val p1 = commandApdu[2].toInt() and 0xFF
        val p2 = commandApdu[3].toInt() and 0xFF

        return when (ins) {
            0xA4 -> ok()
            0xB0 -> {
                val off = (p1 shl 8) or p2
                val le = if (commandApdu.size >= 5) commandApdu[4].toInt() and 0xFF else 16
                if (off < linear.size) ok(linear.copyOfRange(off, minOf(off + le, linear.size))) else err(0x6B, 0x00)
            }
            0xB2, 0xCA -> ok(linear.copyOfRange(0, minOf(16, linear.size)))
            else -> ok()
        }
    }

    override fun onDeactivated(reason: Int) {}
}
