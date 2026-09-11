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
            sector.blocks.forEach { block ->
                out.addAll(block.toList())
            }
        }
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
            return CardDump(uid, atqa, sak, sectors)
        }
    }
}

object CardCloner {
    private val defaultKeys = listOf(
        byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()),
        byteArrayOf(0xA0.toByte(), 0xA1.toByte(), 0xA2.toByte(), 0xA3.toByte(), 0xA4.toByte(), 0xA5.toByte()),
        byteArrayOf(0xB0.toByte(), 0xB1.toByte(), 0xB2.toByte(), 0xB3.toByte(), 0xB4.toByte(), 0xB5.toByte()),
        byteArrayOf(0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte()),
        byteArrayOf(0xD3.toByte(), 0xF7.toByte(), 0xD3.toByte(), 0xF7.toByte(), 0xD3.toByte(), 0xF7.toByte())
    )

    fun readCard(tag: Tag): CardDump? {
        val mifare = MifareClassic.get(tag) ?: run {
            Log.e("CARD", "Not MIFARE Classic")
            return null
        }

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
                    if (mifare.authenticateSectorWithKeyA(sector, key)) {
                        authenticated = true
                        break
                    }
                    if (mifare.authenticateSectorWithKeyB(sector, key)) {
                        authenticated = true
                        break
                    }
                }

                if (!authenticated) {
                    Log.w("CARD", "Sector $sector: no default key")
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
                        Log.e("CARD", "Read block ${firstBlock + i} failed", e)
                        blocks.add(ByteArray(16))
                    }
                }
                sectors.add(SectorData(sector, blocks))
            }

            mifare.close()
            return CardDump(uid, atqa, sak, sectors)
        } catch (e: Exception) {
            Log.e("CARD", "Read failed", e)
            try { mifare.close() } catch (e: Exception) {}
            return null
        }
    }

    fun writeCard(tag: Tag, dump: CardDump): Boolean {
        val mifare = MifareClassic.get(tag) ?: return false

        try {
            mifare.connect()

            // блок 0 для Magic-карт: UID + BCC + SAK + ATQA
            if (mifare.authenticateSectorWithKeyA(0, defaultKeys[0])) {
                val b0 = ByteArray(16)
                val u = dump.uid.copyOfRange(0, minOf(4, dump.uid.size))
                System.arraycopy(u, 0, b0, 0, u.size)
                var bcc: Int = 0
                u.forEach { bcc = bcc xor (it.toInt() and 0xFF) }
                b0[4] = bcc.toByte()
                b0[5] = dump.sak
                b0[6] = dump.atqa.getOrNull(0) ?: 0
                b0[7] = dump.atqa.getOrNull(1) ?: 0
                try {
                    mifare.writeBlock(0, b0)
                    Log.i("CARD", "UID block written (Magic card)")
                } catch (e: Exception) {
                    Log.w("CARD", "UID write failed (not Magic or locked)")
                }
            }

            for (sector in dump.sectors) {
                var authenticated = false
                for (key in defaultKeys) {
                    if (mifare.authenticateSectorWithKeyA(sector.sectorIndex, key)) {
                        authenticated = true
                        break
                    }
                    if (mifare.authenticateSectorWithKeyB(sector.sectorIndex, key)) {
                        authenticated = true
                        break
                    }
                }

                if (!authenticated) {
                    Log.w("CARD", "Sector ${sector.sectorIndex}: skip (no auth)")
                    continue
                }

                val firstBlock = mifare.sectorToBlock(sector.sectorIndex)
                for ((i, block) in sector.blocks.withIndex()) {
                    if (i == 0 && sector.sectorIndex == 0) continue
                    try {
                        mifare.writeBlock(firstBlock + i, block)
                    } catch (e: Exception) {
                        Log.e("CARD", "Write block ${firstBlock + i} failed", e)
                    }
                }
            }

            mifare.close()
            return true
        } catch (e: Exception) {
            Log.e("CARD", "Write failed", e)
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
