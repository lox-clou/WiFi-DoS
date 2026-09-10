package com.twks.wifi

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager

data class WifiNetwork(val ssid: String, val bssid: String, val isConnected: Boolean)

object NetworkEngine {
    fun getGatewayIp(context: Context): String? {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
        val dhcp = wm.dhcpInfo ?: return null
        val ip = dhcp.gateway
        if (ip == 0) return null
        return String.format("%d.%d.%d.%d", ip and 0xff, (ip shr 8) and 0xff, (ip shr 16) and 0xff, (ip shr 24) and 0xff)
    }

    fun isConnectedToWifi(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    fun scanNetworks(context: Context): List<WifiNetwork> {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return emptyList()
        wm.startScan()
        val currentBssid = wm.connectionInfo?.bssid
        return wm.scanResults?.map {
            WifiNetwork(it.SSID ?: "Unknown", it.BSSID ?: "00:00:00:00:00:00", it.BSSID == currentBssid)
        }?.filter { it.ssid.isNotEmpty() } ?: emptyList()
    }
}
