package com.pcaudio.stream

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import org.webrtc.PeerConnection

class MainActivity : ComponentActivity() {

    private var service: AudioForegroundService? = null
    private var bound = false

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val b = binder as AudioForegroundService.LocalBinder
            service = b.service
            bound = true
            // Repassa o listener configurado na UI assim que o servico estiver disponivel
            pendingListener?.let { service?.listener = it }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
            service = null
        }
    }

    private var pendingListener: WebRtcClient.Listener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        am.mode = AudioManager.MODE_NORMAL

        val prefs = getSharedPreferences("pcaudio", Context.MODE_PRIVATE)
        val savedIp = prefs.getString("ip", "192.168.0.100") ?: "192.168.0.100"

        // Garante que o servico esteja em pe (necessario p/ bindar)
        val svcIntent = Intent(this, AudioForegroundService::class.java)
        ContextCompat.startForegroundService(this, svcIntent)
        bindService(svcIntent, conn, Context.BIND_AUTO_CREATE)

        setContent { AppUI(savedIp, prefs) }
    }

    override fun onDestroy() {
        if (bound) {
            try { unbindService(conn) } catch (_: Exception) {}
            bound = false
        }
        super.onDestroy()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun AppUI(savedIp: String, prefs: android.content.SharedPreferences) {
        var ip by remember { mutableStateOf(savedIp) }
        var port by remember { mutableStateOf("8765") }
        var quality by remember { mutableStateOf("ultra") }
        var stereo by remember { mutableStateOf(true) }
        var qExpanded by remember { mutableStateOf(false) }
        var connState by remember { mutableStateOf(PeerConnection.PeerConnectionState.NEW) }
        var stats by remember { mutableStateOf(WebRtcClient.StreamStats()) }
        var errorMsg by remember { mutableStateOf<String?>(null) }

        val isConnected = connState == PeerConnection.PeerConnectionState.CONNECTED
        val isConnecting = connState == PeerConnection.PeerConnectionState.CONNECTING
        val isPlaying = isConnected || isConnecting

        // Pede permissao de notificacao no Android 13+ (foreground service exige)
        val notifPermLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { /* ignora resultado — servico funciona mesmo se o usuario negar */ }

        LaunchedEffect(Unit) {
            if (Build.VERSION.SDK_INT >= 33) {
                if (ContextCompat.checkSelfPermission(
                        this@MainActivity, Manifest.permission.POST_NOTIFICATIONS
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }

        DisposableEffect(Unit) {
            val listener = object : WebRtcClient.Listener {
                override fun onConnectionStateChanged(state: PeerConnection.PeerConnectionState) {
                    connState = state
                    service?.updateConnectionState(state)
                    if (state == PeerConnection.PeerConnectionState.FAILED ||
                        state == PeerConnection.PeerConnectionState.DISCONNECTED) {
                        errorMsg = "Conexão perdida — verifique o servidor e a rede"
                    }
                }
                override fun onStatsUpdate(s: WebRtcClient.StreamStats) { stats = s }
                override fun onError(message: String) {
                    errorMsg = message
                    connState = PeerConnection.PeerConnectionState.FAILED
                }
            }
            pendingListener = listener
            service?.listener = listener
            onDispose {
                if (service?.listener === listener) service?.listener = null
                pendingListener = null
            }
        }

        val bg = Color(0xFF07070F)
        val card = Color(0xFF11111E)
        val border = Color(0xFF252538)
        val accent = Color(0xFFA78BFA)
        val danger = Color(0xFFEF4444)

        MaterialTheme(
            colorScheme = darkColorScheme(
                primary = accent,
                background = bg,
                surface = card,
                onSurface = Color(0xFFDDDDDD),
            )
        ) {
            Box(Modifier.fillMaxSize().background(bg)) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Spacer(Modifier.height(32.dp))

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = card),
                        shape = RoundedCornerShape(22.dp),
                    ) {
                        Column(Modifier.padding(22.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("🎉 PC Audio Stream",
                                    color = accent, fontSize = 22.sp,
                                    fontWeight = FontWeight.Bold)
                                Spacer(Modifier.width(8.dp))
                                Box(Modifier
                                    .background(Color(0x447C3AED), RoundedCornerShape(10.dp))
                                    .padding(horizontal = 8.dp, vertical = 2.dp)) {
                                    Text("NATIVO", color = accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                            Text("WebRTC + AAudio · Foreground Service",
                                color = Color(0xFF555555), fontSize = 12.sp)
                            Spacer(Modifier.height(20.dp))

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                StatusBadge(
                                    text = when (connState) {
                                        PeerConnection.PeerConnectionState.CONNECTED -> "Conectado"
                                        PeerConnection.PeerConnectionState.CONNECTING -> "Conectando..."
                                        PeerConnection.PeerConnectionState.FAILED -> "Falhou"
                                        PeerConnection.PeerConnectionState.DISCONNECTED -> "Desconectado"
                                        else -> "Desconectado"
                                    },
                                    color = when (connState) {
                                        PeerConnection.PeerConnectionState.CONNECTED -> Color(0xFF4ADE80)
                                        PeerConnection.PeerConnectionState.CONNECTING -> Color(0xFFFACC15)
                                        PeerConnection.PeerConnectionState.FAILED -> danger
                                        else -> Color(0xFF3A3A5C)
                                    },
                                    border = border,
                                )
                                if (isConnected) {
                                    StatusBadge("${"%.0f".format(stats.rttMs)} ms RTT",
                                        accent, border)
                                }
                            }
                            Spacer(Modifier.height(20.dp))

                            Text("Endereço do servidor",
                                color = Color(0xFF666666), fontSize = 12.sp)
                            Spacer(Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = ip,
                                    onValueChange = { ip = it; prefs.edit { putString("ip", it) } },
                                    label = { Text("IP do PC") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                                    enabled = !isPlaying,
                                    modifier = Modifier.weight(2f),
                                )
                                OutlinedTextField(
                                    value = port,
                                    onValueChange = { port = it.filter { c -> c.isDigit() } },
                                    label = { Text("Porta") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    enabled = !isPlaying,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            Spacer(Modifier.height(16.dp))

                            Text("Qualidade (bitrate opus)",
                                color = Color(0xFF666666), fontSize = 12.sp)
                            Spacer(Modifier.height(6.dp))
                            ExposedDropdownMenuBox(
                                expanded = qExpanded,
                                onExpandedChange = { qExpanded = !qExpanded && !isPlaying },
                            ) {
                                OutlinedTextField(
                                    value = qualityLabel(quality),
                                    onValueChange = {},
                                    readOnly = true,
                                    enabled = !isPlaying,
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = qExpanded) },
                                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                                )
                                ExposedDropdownMenu(
                                    expanded = qExpanded,
                                    onDismissRequest = { qExpanded = false },
                                ) {
                                    listOf("ultra", "high", "medium", "low").forEach { q ->
                                        DropdownMenuItem(
                                            text = { Text(qualityLabel(q)) },
                                            onClick = { quality = q; qExpanded = false },
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(16.dp))

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Switch(
                                    checked = stereo,
                                    onCheckedChange = { stereo = it },
                                    enabled = !isPlaying,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("Estéreo", color = Color(0xFFAAAAAA), fontSize = 14.sp)
                            }
                            Spacer(Modifier.height(20.dp))

                            Button(
                                onClick = {
                                    errorMsg = null
                                    if (isPlaying) {
                                        service?.disconnect()
                                        connState = PeerConnection.PeerConnectionState.NEW
                                    } else {
                                        val url = "http://${ip.trim()}:${port.trim()}"
                                        service?.connect(url, quality, stereo)
                                        connState = PeerConnection.PeerConnectionState.CONNECTING
                                    }
                                },
                                enabled = ip.isNotBlank() && port.isNotBlank() && service != null,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isPlaying) danger else accent
                                ),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.fillMaxWidth().height(56.dp),
                            ) {
                                Icon(
                                    if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                                    contentDescription = null,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    if (isPlaying) "Parar" else "Iniciar Reprodução",
                                    fontSize = 16.sp, fontWeight = FontWeight.Bold,
                                )
                            }

                            errorMsg?.let {
                                Spacer(Modifier.height(12.dp))
                                Text("⚠ $it", color = danger, fontSize = 12.sp)
                            }

                            if (isConnected) {
                                Spacer(Modifier.height(20.dp))
                                Divider(color = border)
                                Spacer(Modifier.height(16.dp))
                                Row {
                                    Icon(Icons.Default.Bolt, contentDescription = null,
                                        tint = accent, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Estatísticas WebRTC",
                                        color = Color(0xFF888888), fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold)
                                }
                                Spacer(Modifier.height(10.dp))
                                StatRow("Codec", stats.codec.ifEmpty { "..." })
                                StatRow("Bitrate", "${stats.bitrateKbps} kbps")
                                StatRow("RTT (rede)", "${"%.0f".format(stats.rttMs)} ms")
                                StatRow("Jitter", "${"%.1f".format(stats.jitterMs)} ms")
                                StatRow("Pacotes perdidos", "${stats.packetsLost}")
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    Text("Foreground · WifiLock HIGH_PERF · WakeLock CPU",
                        color = Color(0xFF444444), fontSize = 10.sp)
                }
            }
        }
    }

    private fun qualityLabel(q: String) = when (q) {
        "ultra"  -> "⚡ Ultra — 192 kbps (estúdio)"
        "high"   -> "🎵 Alta — 128 kbps"
        "medium" -> "🎶 Média — 64 kbps"
        "low"    -> "📻 Baixa — 32 kbps"
        else     -> q
    }

    @Composable
    private fun StatusBadge(text: String, color: Color, border: Color) {
        Box(
            Modifier
                .background(Color(0xFF181828), RoundedCornerShape(20.dp))
                .clip(RoundedCornerShape(20.dp))
                .padding(horizontal = 11.dp, vertical = 5.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(color))
                Spacer(Modifier.width(6.dp))
                Text(text, color = Color(0xFFDDDDDD), fontSize = 11.sp)
            }
        }
    }

    @Composable
    private fun StatRow(label: String, value: String) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, color = Color(0xFF555555), fontSize = 12.sp)
            Text(value, color = Color(0xFF888888), fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}
