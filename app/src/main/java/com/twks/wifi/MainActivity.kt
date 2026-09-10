package com.twks.wifi

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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val locationPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted -> if (!isGranted) finish() }

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
    var isAttacking by remember { mutableStateOf(false) }
    var packetCount by remember { mutableStateOf(0L) }
    var pps by remember { mutableStateOf(0L) }
    var lastCount by remember { mutableStateOf(0L) }
    var lastTime by remember { mutableStateOf(System.currentTimeMillis()) }
    val scope = rememberCoroutineScope()
    
    val green = Color(0xFF00FF41)
    val dimGreen = Color(0xFF008F24)
    val red = Color(0xFFFF003C)

    LaunchedEffect(isAttacking) {
        if (isAttacking) {
            while (isAttacking) {
                val currentCount = NativeEngine.getPacketCount()
                val currentTime = System.currentTimeMillis()
                val timeDiff = (currentTime - lastTime) / 1000.0
                if (timeDiff >= 1.0) {
                    pps = ((currentCount - lastCount) / timeDiff).toLong()
                    lastCount = currentCount
                    lastTime = currentTime
                }
                packetCount = currentCount
                delay(100)
            }
        } else {
            pps = 0
        }
    }

    Surface(color = Color.Black, modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(16.dp).statusBarsPadding()) {
            Text("TWKS_WIFI // ROOT", color = green, fontSize = 16.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
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
                "TARGETS" -> TargetListScreen(context, green, dimGreen, onSelected = { ip, _ ->
                    targetIp = ip
                    screen = "MENU"
                })
                "ATTACK" -> AttackScreen(green, red, dimGreen, targetIp, isAttacking, packetCount, pps,
                    onStart = {
                        isAttacking = true
                        lastCount = 0
                        lastTime = System.currentTimeMillis()
                        scope.launch(Dispatchers.IO) {
                            NativeEngine.startAttack(targetIp, 64)
                        }
                    },
                    onStop = {
                        isAttacking = false
                        NativeEngine.stopAttack()
                    },
                    onBack = { 
                        isAttacking = false
                        NativeEngine.stopAttack()
                        screen = "MENU" 
                    }
                )
            }
        }
    }
}

@Composable
fun MenuScreen(color: Color, onSelectTarget: () -> Unit, onLaunchAttack: () -> Unit) {
    Column {
        MenuButton("[ 01 ] SELECT TARGET", color, onSelectTarget)
        MenuButton("[ 02 ] LAUNCH ATTACK", color, onLaunchAttack)
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
    LazyColumn {
        items(networks) { net ->
            Button(onClick = { onSelected(NetworkEngine.getGatewayIp(context) ?: "192.168.1.1", net.bssid) }, colors = ButtonDefaults.buttonColors(backgroundColor = Color.Transparent), modifier = Modifier.fillMaxWidth()) {
                Text(">> ${net.ssid} [${net.bssid}] ${if (net.isConnected) "(CONNECTED)" else ""}", color = if (net.isConnected) color else dimColor, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
fun AttackScreen(color: Color, red: Color, dimColor: Color, ip: String, attacking: Boolean, packets: Long, currentPps: Long, onStart: () -> Unit, onStop: () -> Unit, onBack: () -> Unit) {
    Column {
        Text("TARGET: $ip", color = color, fontFamily = FontFamily.Monospace)
        Spacer(modifier = Modifier.height(16.dp))
        Text("TOTAL PACKETS: $packets", color = dimColor, fontSize = 16.sp, fontFamily = FontFamily.Monospace)
        Text("THROUGHPUT: $currentPps PPS", color = if (attacking) red else dimColor, fontSize = 32.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        Spacer(modifier = Modifier.weight(1f))
        
        if (attacking) {
            Button(onClick = onStop, colors = ButtonDefaults.buttonColors(backgroundColor = red), modifier = Modifier.fillMaxWidth()) {
                Text("[ ABORT ATTACK ]", color = Color.Black, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            }
        } else {
            Button(onClick = onStart, colors = ButtonDefaults.buttonColors(backgroundColor = color), modifier = Modifier.fillMaxWidth()) {
                Text("[ EXECUTE FLOOD ]", color = Color.Black, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = onBack, colors = ButtonDefaults.buttonColors(backgroundColor = Color.Transparent), modifier = Modifier.fillMaxWidth()) {
            Text("[ < BACK ]", color = dimColor, fontFamily = FontFamily.Monospace)
        }
    }
}
