package com.vanta.wifidos

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!NetworkEngine.isConnectedToWifi(this)) {
            startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
        }
        setContent {
            MaterialTheme(colors = darkColors(background = Color.Black, primary = Color(0xFF00FF41), onBackground = Color(0xFF00FF41))) {
                TerminalApp(this)
            }
        }
    }
}

@Composable
fun TerminalApp(context: ComponentActivity) {
    var screen by remember { mutableStateOf("MENU") }
    var targetIp by remember { mutableStateOf(NetworkEngine.getGatewayIp(context) ?: "192.168.1.1") }
    var targetBssid by remember { mutableStateOf("FF:FF:FF:FF:FF:FF") }
    var logs by remember { mutableStateOf(mutableListOf("[*] VANTA OS v3.1.4 initialized", "[*] Root access: GRANTED", "[*] Native engine: LOADED")) }
    var isAttacking by remember { mutableStateOf(false) }
    var packetCount by remember { mutableStateOf(0L) }
    val scope = rememberCoroutineScope()
    val green = Color(0xFF00FF41)
    val dimGreen = Color(0xFF008F24)
    val red = Color(0xFFFF003C)

    fun addLog(msg: String) { logs.add(msg); if (logs.size > 50) logs.removeAt(0) }

    LaunchedEffect(isAttacking) {
        if (isAttacking) {
            while (isAttacking) { packetCount = NativeEngine.getPacketCount(); kotlinx.coroutines.delay(100) }
        }
    }

    Surface(color = Color.Black, modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(16.dp).statusBarsPadding()) {
            Text("VANTA_TERMINAL // ROOT", color = green, fontSize = 14.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            Divider(color = dimGreen, thickness = 1.dp)
            Spacer(modifier = Modifier.height(16.dp))

            when (screen) {
                "MENU" -> MenuScreen(green, 
                    onSelectTarget = { screen = "TARGETS" },
                    onLaunchAttack = { 
                        if (!NetworkEngine.isConnectedToWifi(context)) {
                            context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                        } else {
                            screen = "ATTACK"
                        }
                    }
                )
                "TARGETS" -> TargetListScreen(context, green, dimGreen, onSelected = { ip, bssid ->
                    targetIp = ip; targetBssid = bssid
                    addLog("[+] Target locked: $bssid ($ip)")
                    screen = "MENU"
                })
                "ATTACK" -> AttackScreen(green, red, dimGreen, targetIp, targetBssid, isAttacking, packetCount, logs,
                    onStart = {
                        isAttacking = true
                        addLog("[!] INITIATING DENIAL OF SERVICE...")
                        addLog("[*] Spawning 16 native threads...")
                        RootShell.enableMonitorMode()
                        scope.launch(Dispatchers.IO) {
                            val bssidBytes = macToBytes(targetBssid)
                            NativeEngine.startAttack(targetIp, 16, true, "wlan0", bssidBytes, bssidBytes)
                        }
                    },
                    onStop = {
                        isAttacking = false
                        NativeEngine.stopAttack()
                        RootShell.disableMonitorMode()
                        addLog("[*] Attack halted. Interface restored.")
                    },
                    onBack = { screen = "MENU" }
                )
            }
        }
    }
}

@Composable
fun MenuScreen(color: Color, onSelectTarget: () -> Unit, onLaunchAttack: () -> Unit) {
    Column {
        Button(onClick = onSelectTarget, colors = ButtonDefaults.buttonColors(backgroundColor = Color.Transparent), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text("[ 01 ] SELECT TARGET", color = color, fontSize = 18.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        }
        Button(onClick = onLaunchAttack, colors = ButtonDefaults.buttonColors(backgroundColor = Color.Transparent), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text("[ 02 ] LAUNCH ATTACK", color = color, fontSize = 18.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun TargetListScreen(context: ComponentActivity, color: Color, dimColor: Color, onSelected: (String, String) -> Unit) {
    val networks = remember { NetworkEngine.scanNetworks(context) }
    Text("SCANNING LOCAL SUBNET...", color = color, fontFamily = FontFamily.Monospace)
    Spacer(modifier = Modifier.height(16.dp))
    LazyColumn {
        items(networks) { net ->
            Button(onClick = { onSelected(NetworkEngine.getGatewayIp(context) ?: "192.168.1.1", net.bssid) }, colors = ButtonDefaults.buttonColors(backgroundColor = Color.Transparent), modifier = Modifier.fillMaxWidth()) {
                Text(">> ${net.ssid} [${net.bssid}]", color = if (net.isConnected) color else dimColor, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
fun AttackScreen(color: Color, red: Color, dimColor: Color, ip: String, bssid: String, attacking: Boolean, packets: Long, logs: List<String>, onStart: () -> Unit, onStop: () -> Unit, onBack: () -> Unit) {
    Column {
        Text("TARGET: $bssid", color = color, fontFamily = FontFamily.Monospace)
        Text("GATEWAY: $ip", color = color, fontFamily = FontFamily.Monospace)
        Spacer(modifier = Modifier.height(16.dp))
        Text("PACKETS SENT: $packets", color = if (attacking) red else dimColor, fontSize = 24.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        Spacer(modifier = Modifier.height(16.dp))
        LazyColumn(modifier = Modifier.weight(1f).background(Color.Black).padding(8.dp)) {
            items(logs) { log -> Text(log, color = dimColor, fontSize = 12.sp, fontFamily = FontFamily.Monospace) }
        }
        Spacer(modifier = Modifier.height(16.dp))
        if (attacking) {
            Button(onClick = onStop, colors = ButtonDefaults.buttonColors(backgroundColor = red), modifier = Modifier.fillMaxWidth()) {
                Text("[ ABORT ATTACK ]", color = Color.Black, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            }
        } else {
            Button(onClick = onStart, colors = ButtonDefaults.buttonColors(backgroundColor = color), modifier = Modifier.fillMaxWidth()) {
                Text("[ EXECUTE DO S ]", color = Color.Black, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = onBack, colors = ButtonDefaults.buttonColors(backgroundColor = Color.Transparent), modifier = Modifier.fillMaxWidth()) {
            Text("[ < BACK ]", color = dimColor, fontFamily = FontFamily.Monospace)
        }
    }
}

fun macToBytes(mac: String): ByteArray {
    return mac.split(":").map { it.toInt(16).toByte() }.toByteArray()
}
