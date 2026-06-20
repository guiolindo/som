package com.pcaudio.stream

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import org.webrtc.PeerConnection

/**
 * Foreground Service que mantém o WebRTC + áudio rodando mesmo com a tela
 * apagada ou o app em background.
 *
 * Também segura WifiLock HIGH_PERF (impede power-save no Wi-Fi do celular,
 * causa #1 de "picota do nada") e PARTIAL_WAKE_LOCK (impede CPU throttle).
 */
class AudioForegroundService : Service() {

    private val binder = LocalBinder()
    inner class LocalBinder : Binder() {
        val service: AudioForegroundService get() = this@AudioForegroundService
    }

    private var client: WebRtcClient? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var focusRequest: AudioFocusRequest? = null
    private var audioManager: AudioManager? = null

    var listener: WebRtcClient.Listener? = null
        set(value) {
            field = value
            client?.listener = value
        }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        startForegroundCompat("Pronto")
        acquireLocks()
        client = WebRtcClient(this).apply { listener = this@AudioForegroundService.listener }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            disconnect()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        // STICKY: se o Android matar por memoria baixa, o sistema reinicia
        return START_STICKY
    }

    override fun onDestroy() {
        abandonAudioFocus()
        client?.release()
        client = null
        releaseLocks()
        super.onDestroy()
    }

    fun connect(serverUrl: String, quality: String, stereo: Boolean) {
        updateNotification("Conectando em $serverUrl...")
        requestAudioFocus()
        client?.connect(serverUrl, quality, stereo)
    }

    fun disconnect() {
        client?.disconnect()
        abandonAudioFocus()
        updateNotification("Pronto")
    }

    fun updateConnectionState(state: PeerConnection.PeerConnectionState) {
        val text = when (state) {
            PeerConnection.PeerConnectionState.CONNECTED    -> "Conectado — tocando áudio"
            PeerConnection.PeerConnectionState.CONNECTING   -> "Conectando..."
            PeerConnection.PeerConnectionState.DISCONNECTED -> "Desconectado"
            PeerConnection.PeerConnectionState.FAILED       -> "Falha de conexão"
            PeerConnection.PeerConnectionState.CLOSED       -> "Encerrado"
            else                                            -> "Pronto"
        }
        updateNotification(text)
    }

    // ── AudioFocus de MIDIA (nao chamada) ───────────────────────────────
    // Sinaliza ao Android que estamos tocando musica, garantindo:
    //   - Roteamento p/ STREAM_MUSIC (volume "Midia", nao "Chamada")
    //   - Audio nao e pausado quando a tela apaga
    //   - Outros apps de audio pausam quando entramos (comportamento musical)
    private fun requestAudioFocus() {
        if (audioManager == null) {
            audioManager = applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        }
        val am = audioManager ?: return
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs)
            .setAcceptsDelayedFocusGain(false)
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener { /* ignora — nao queremos pausar */ }
            .build()
        focusRequest = req
        try { am.requestAudioFocus(req) } catch (_: Exception) {}
    }

    private fun abandonAudioFocus() {
        try { focusRequest?.let { audioManager?.abandonAudioFocusRequest(it) } } catch (_: Exception) {}
        focusRequest = null
    }

    // ── Locks contra power-save (Wi-Fi e CPU) ───────────────────────────
    private fun acquireLocks() {
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wm.createWifiLock(
                WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "PCAudioStream:WifiLock"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {}
        try {
            val pm = applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "PCAudioStream:CpuLock"
            ).apply {
                setReferenceCounted(false)
                acquire(12 * 60 * 60 * 1000L) // 12h max — Android exige timeout
            }
        } catch (_: Exception) {}
    }

    private fun releaseLocks() {
        try { if (wifiLock?.isHeld == true) wifiLock?.release() } catch (_: Exception) {}
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        wifiLock = null
        wakeLock = null
    }

    // ── Notification ────────────────────────────────────────────────────
    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CH_ID) == null) {
                val ch = NotificationChannel(
                    CH_ID,
                    "Reprodução de áudio",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Toca o áudio do PC mesmo com a tela apagada"
                    setShowBadge(false)
                    setSound(null, null)
                }
                nm.createNotificationChannel(ch)
            }
        }
    }

    private fun startForegroundCompat(text: String) {
        val notif = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID, notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = Intent(this, AudioForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPi = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CH_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("PC Audio Stream")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(pi)
            .addAction(android.R.drawable.ic_media_pause, "Parar", stopPi)
            .build()
    }

    companion object {
        const val CH_ID = "pcaudio_stream"
        const val NOTIF_ID = 1
        const val ACTION_STOP = "com.pcaudio.stream.STOP"
    }
}
