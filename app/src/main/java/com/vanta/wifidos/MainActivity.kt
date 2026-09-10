package com.vanta.wifidos

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val locationPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            locationPermissionRequest.launch(Manifest.permission.ACCESS_FINE_LOCATION)
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
    var logs by remember { mutableStateOf(mutableListOf("[*] VANTA OS v3.2.0 initialized", "[*] Root access: CHECKING...", "[*] Native engine: LOADED")) }
    var isAttacking by remember { mutableStateOf(false) }
    var packetCount by remember { mutableStateOf(0L) }
    var isReleaseMode by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    
    val green = Color(0xFF00FF41)
    val dimGreen = Color(0xFF008F24)
    val red = Color(0xFFFF003C)
    val gold = Color(0xFFFFD700)

    fun addLog(msg: String) { 
        if (!isReleaseMode) {
            logs.add(msg)
            if (logs.size > 50) logs.removeAt(0) 
        }
    }

    LaunchedEffect(isAttacking) {
        if (isAttacking) {
            while (isAttacking) { 
                packetCount = NativeEngine.getPacketCount()
                kotlinx.coroutines.delay(50)
            }
        }
    }

    Surface(color = Color.Black, modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(16.dp).statusBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("VANTA_TERMINAL // ROOT", color = if (isReleaseMode) gold else green, fontSize = 14.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.weight(1f))
                Text(if (isReleaseMode) "[RELEASE MODE]" else "[DEV MODE]", color = if (isReleaseMode) gold else dimGreen, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Divider(color = dimGreen, thickness = 1.dp)
            Spacer(modifier = Modifier.height(16.dp))

            when (screen) {
                "MENU" -> MenuScreen(green, gold, isReleaseMode,
                    onSelectTarget = { screen = "TARGETS" },
                    onLaunchAttack = { 
                        if (!NetworkEngine.isConnectedToWifi(context)) {
                            context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                        } else {
                            screen = "ATTACK"
                        }
                    },
                    onToggleRelease = { isReleaseMode = !isReleaseMode }
                )
                "TARGETS" -> TargetListScreen(context, green, dimGreen, onSelected = { ip, bssid ->
                    targetIp = ip; targetBssid = bssid
                    addLog("[+] Target locked: $bssid ($ip)")
                    screen = "MENU"
                })
                "ATTACK" -> AttackScreen(green, red, dimColor = dimGreen, targetIp, targetBssid, isAttacking, packetCount, logs, isReleaseMode,
                    onStart = {
                        isAttacking = true
                        addLog("[!] INITIATING DENIAL OF SERVICE...")
                        val threads = if (isReleaseMode) 32 else 16
                        addLog("[*] Spawning $threads native threads...")
                        RootShell.enableMonitorMode()
                        scope.launch(Dispatchers.IO) {
                            val bssidBytes = NetworkEngine.macToBytesSafe(targetBssid) ?: ByteArray(6)
                            NativeEngine.startAttack(targetIp, threads, true, "wlan0", bssidBytes, bssidBytes)
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
fun MenuScreen(color: Color, gold: Color, isReleaseMode: Boolean, onSelectTarget: () -> Unit, onLaunchAttack: () -> Unit, onToggleRelease: () -> Unit) {
    Column {
        MenuButton("[ 01 ] SELECT TARGET", color, onSelectTarget)
        MenuButton("[ 02 ] LAUNCH ATTACK", color, onLaunchAttack)
        MenuButton(if (isReleaseMode) "[ 03 ] DISABLE RELEASE MODE" else "[ 03 ] ENABLE RELEASE MODE", if (isReleaseMode) gold else color, onToggleRelease)
    }
}

@Composable
fun MenuButton(text: String, color: Color, onClick: () -> Unit) {
    Button(onClick = onClick, colors = ButtonDefaults.buttonColors(backgroundColor = Color.Transparent), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(text, color = color, fontSize = 18.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun TargetListScreen(context: ComponentActivity, color: Color, dimColor: Color, onSelected: (String, String) -> Unit) {
    val networks = remember { NetworkEngine.scanNetworks(context) }
    Text("SCANNING LOCAL SUBNET...", color = color, fontFamily = FontFamily.Monospace)
    Spacer(modifier = Modifier.height(16.dp))
    if (networks.isEmpty()) {
        Text("[-] No networks found. Check Location permission.", color = Color(0xFFFF003C), fontFamily = FontFamily.Monospace)
    }
    LazyColumn {
        items(networks) { net ->
            Button(onClick = { onSelected(NetworkEngine.getGatewayIp(context) ?: "192.168.1.1", net.bssid) }, colors = ButtonDefaults.buttonColors(backgroundColor = Color.Transparent), modifier = Modifier.fillMaxWidth()) {
                Text(">> ${net.ssid} [${net.bssid}] ${if (net.isConnected) "(CONNECTED)" else ""}", color = if (net.isConnected) color else dimColor, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
fun AttackScreen(color: Color, red: Color, dimColor: Color, ip: String, bssid: String, attacking: Boolean, packets: Long, logs: List<String>, isReleaseMode: Boolean, onStart: () -> Unit, onStop: () -> Unit, onBack: () -> Unit) {
    Column {
        if (!isReleaseMode) {
            Text("TARGET: $bssid", color = color, fontFamily = FontFamily.Monospace)
            Text("GATEWAY: $ip", color = color, fontFamily = FontFamily.Monospace)
            Spacer(modifier = Modifier.height(16.dp))
        }
        Text("PACKETS SENT: $packets", color = if (attacking) red else dimColor, fontSize = 28.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        Spacer(modifier = Modifier.height(16.dp))
        
        if (!isReleaseMode) {
            LazyColumn(modifier = Modifier.weight(1f).background(Color.Black).padding(8.dp)) {
                items(logs) { log -> Text(log, color = dimColor, fontSize = 12.sp, fontFamily = FontFamily.Monospace) }
            }
        } else {
            Spacer(modifier = Modifier.weight(1f))
            Text("[ STEALTH MODE ACTIVE ]", color = Color(0xFFFFD700), fontSize = 16.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.align(Alignment.CenterHorizontally))
            Spacer(modifier = Modifier.weight(1f))
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
        if (!isReleaseMode) {
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = onBack, colors = ButtonDefaults.buttonColors(backgroundColor = Color.Transparent), modifier = Modifier.fillMaxWidth()) {
                Text("[ < BACK ]", color = dimColor, fontFamily = FontFamily.Monospace)
            }
        }
    }
}
