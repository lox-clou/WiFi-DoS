package com.twks.wifi

import android.nfc.Tag
import android.nfc.tech.MifareClassic
import android.nfc.tech.NfcA
import android.util.Log
import java.io.File

data class SectorData(
    val sectorIndex: Int,
    val blocks: List<ByteArray>
)

data class CardDump(
    val uid: ByteArray,
    val atqa: ByteArray,
    val sak: Byte,
    val sectors: List<SectorData>,
    val type: Int = 0,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toBytes(): ByteArray {
        val out = mutableListOf<Byte>()
        out.addAll(uid.toList())
        out.addAll(atqa.toList())
        out.add(sak)
        out.add(sectors.size.toByte())
        sectors.forEach { sector ->
            out.add(sector.sectorIndex.toByte())
            out.add(sector.blocks.size.toByte())
            sector.blocks.forEach { block -> out.addAll(block.toList()) }
        }
        out.add(type.toByte())
        return out.toByteArray()
    }

    companion object {
        fun fromBytes(data: ByteArray): CardDump {
            var pos = 0
            val uid = data.copyOfRange(pos, pos + 4); pos += 4
            val atqa = data.copyOfRange(pos, pos + 2); pos += 2
            val sak = data[pos]; pos += 1
            val sectorCount = data[pos].toInt(); pos += 1
            val sectors = mutableListOf<SectorData>()
            repeat(sectorCount) {
                val sectorIndex = data[pos].toInt(); pos += 1
                val blockCount = data[pos].toInt(); pos += 1
                val blocks = mutableListOf<ByteArray>()
                repeat(blockCount) {
                    blocks.add(data.copyOfRange(pos, pos + 16)); pos += 16
                }
                sectors.add(SectorData(sectorIndex, blocks))
            }
            val type = if (pos < data.size) data[pos].toInt() else 0
            return CardDump(uid, atqa, sak, sectors, type)
        }
    }
}

object CardCloner {
    private fun k(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    private val defaultKeys = listOf(
        k(0xFF,0xFF,0xFF,0xFF,0xFF,0xFF), k(0xA0,0xA1,0xA2,0xA3,0xA4,0xA5),
        k(0xB0,0xB1,0xB2,0xB3,0xB4,0xB5), k(0x00,0x00,0x00,0x00,0x00,0x00),
        k(0xD3,0xF7,0xD3,0xF7,0xD3,0xF7), k(0x4D,0x3A,0x99,0xC3,0x51,0xDD),
        k(0x1A,0x98,0x2A,0x7E,0x35,0x6F), k(0xAA,0xBB,0xCC,0xDD,0xEE,0xFF),
        k(0xAB,0xCD,0xEF,0x01,0x23,0x45), k(0x01,0x02,0x03,0x04,0x05,0x06),
        k(0x42,0x42,0x42,0x42,0x42,0x42), k(0x47,0x52,0x4F,0x55,0x50,0x41),
        k(0x54,0x48,0x45,0x6F,0x65,0x6E), k(0x59,0x4F,0x55,0x43,0x41,0x4E),
        k(0x77,0x2B,0xDC,0x89,0x65,0xF1), k(0xA6,0x39,0x93,0x90,0x70,0x57),
        k(0xC9,0x3F,0x4F,0x61,0x84,0x72), k(0xE4,0xD2,0x77,0x0A,0x89,0xBE),
        k(0x11,0x22,0x33,0x44,0x55,0x66), k(0x99,0x88,0x77,0x66,0x55,0x44)
    )

    fun openedSectors(dump: CardDump): Int = dump.sectors.count { it.blocks.isNotEmpty() }

    fun readCard(tag: Tag): CardDump? {
        val mifare = MifareClassic.get(tag) ?: return null
        try {
            mifare.connect()
            val uid = tag.id
            val nfcA = NfcA.get(tag)
            val atqa = nfcA?.atqa ?: byteArrayOf(0, 0)
            val sak: Byte = (nfcA?.sak?.toInt() ?: 0).toByte()

            val sectors = mutableListOf<SectorData>()
            for (sector in 0 until mifare.sectorCount) {
                var authenticated = false
                for (key in defaultKeys) {
                    if (mifare.authenticateSectorWithKeyA(sector, key) || mifare.authenticateSectorWithKeyB(sector, key)) {
                        authenticated = true
                        break
                    }
                }
                if (!authenticated) {
                    sectors.add(SectorData(sector, emptyList()))
                    continue
                }
                val blocks = mutableListOf<ByteArray>()
                val firstBlock = mifare.sectorToBlock(sector)
                val blockCount = mifare.getBlockCountInSector(sector)
                for (i in 0 until blockCount) {
                    try {
                        blocks.add(mifare.readBlock(firstBlock + i))
                    } catch (e: Exception) {
                        blocks.add(ByteArray(16))
                    }
                }
                sectors.add(SectorData(sector, blocks))
            }
            mifare.close()
            return CardDump(uid, atqa, sak, sectors, 0)
        } catch (e: Exception) {
            Log.e("CARD", "Classic read failed", e)
            try { mifare.close() } catch (e: Exception) {}
            return null
        }
    }

    fun writeCard(tag: Tag, dump: CardDump): Boolean {
        val mifare = MifareClassic.get(tag) ?: return false
        try {
            mifare.connect()

            if (mifare.authenticateSectorWithKeyA(0, defaultKeys[0])) {
                val b0 = ByteArray(16)
                val u = dump.uid.copyOfRange(0, minOf(4, dump.uid.size))
                System.arraycopy(u, 0, b0, 0, u.size)
                var bcc = 0
                u.forEach { bcc = bcc xor (it.toInt() and 0xFF) }
                b0[4] = bcc.toByte()
                b0[5] = dump.sak
                b0[6] = dump.atqa.getOrNull(0) ?: 0
                b0[7] = dump.atqa.getOrNull(1) ?: 0
                try {
                    mifare.writeBlock(0, b0)
                    Log.i("CARD", "block0 written")
                } catch (e: Exception) {
                    Log.w("CARD", "block0 write failed (not Magic or locked)")
                }
            }

            for (sector in dump.sectors) {
                var authenticated = false
                for (key in defaultKeys) {
                    if (mifare.authenticateSectorWithKeyA(sector.sectorIndex, key) || mifare.authenticateSectorWithKeyB(sector.sectorIndex, key)) {
                        authenticated = true
                        break
                    }
                }
                if (!authenticated) continue
                val firstBlock = mifare.sectorToBlock(sector.sectorIndex)
                for ((i, block) in sector.blocks.withIndex()) {
                    if (i == 0 && sector.sectorIndex == 0) continue
                    try {
                        mifare.writeBlock(firstBlock + i, block)
                    } catch (e: Exception) {}
                }
            }
            mifare.close()
            return true
        } catch (e: Exception) {
            Log.e("CARD", "Classic write failed", e)
            try { mifare.close() } catch (e: Exception) {}
            return false
        }
    }

    fun saveDump(file: File, dump: CardDump) {
        file.writeBytes(dump.toBytes())
    }

    fun loadDump(file: File): CardDump {
        return CardDump.fromBytes(file.readBytes())
    }
}
