package com.pcaudio.stream

import android.content.Context
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.concurrent.TimeUnit

/**
 * Cliente WebRTC. Conecta no servidor /offer, recebe audio opus pelo caminho
 * nativo do Android (AAudio LOW_LATENCY quando o dispositivo suporta).
 *
 * Latencia tipica em rede LAN: 30-60 ms total (vs 250+ ms via Chrome web).
 */
class WebRtcClient(private val context: Context) {

    interface Listener {
        fun onConnectionStateChanged(state: PeerConnection.PeerConnectionState)
        fun onStatsUpdate(stats: StreamStats)
        fun onError(message: String)
    }

    data class StreamStats(
        val bitrateKbps: Int = 0,
        val jitterMs: Double = 0.0,
        val packetsLost: Int = 0,
        val rttMs: Double = 0.0,
        val codec: String = "",
    )

    var listener: Listener? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var factory: PeerConnectionFactory? = null
    private var pc: PeerConnection? = null
    private var statsJob: Job? = null

    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private var lastBytesReceived = 0L
    private var lastStatsTime = 0L

    fun connect(serverUrl: String, quality: String = "ultra", stereo: Boolean = true) {
        scope.launch {
            try {
                initFactory()
                doConnect(serverUrl, quality, stereo)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    listener?.onError("Falha ao conectar: ${e.message}")
                }
            }
        }
    }

    fun disconnect() {
        statsJob?.cancel()
        statsJob = null
        pc?.close()
        pc = null
    }

    fun release() {
        disconnect()
        scope.cancel()
        factory?.dispose()
        factory = null
        PeerConnectionFactory.stopInternalTracingCapture()
        PeerConnectionFactory.shutdownInternalTracer()
    }

    private fun initFactory() {
        if (factory != null) return

        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )

        // ADM (AudioDeviceModule) — chave da baixa latencia.
        // useLowLatency=true habilita caminho AAudio quando o device suporta.
        // Desligamos AEC/NS/AGC porque queremos audio musical, nao voz.
        val adm = JavaAudioDeviceModule.builder(context)
            .setUseLowLatency(true)
            .setUseHardwareAcousticEchoCanceler(false)
            .setUseHardwareNoiseSuppressor(false)
            .createAudioDeviceModule()

        factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(adm)
            .setOptions(PeerConnectionFactory.Options())
            .createPeerConnectionFactory()
    }

    private suspend fun doConnect(serverUrl: String, quality: String, stereo: Boolean) {
        val cfg = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
        }

        pc = factory!!.createPeerConnection(cfg, object : PeerConnection.Observer {
            override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {}
            override fun onIceConnectionReceivingChange(b: Boolean) {}
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidate(c: IceCandidate?) {}
            override fun onIceCandidatesRemoved(c: Array<out IceCandidate>?) {}
            override fun onAddStream(s: MediaStream?) {}
            override fun onRemoveStream(s: MediaStream?) {}
            override fun onDataChannel(d: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onTrack(transceiver: RtpTransceiver?) {}
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}

            override fun onConnectionChange(s: PeerConnection.PeerConnectionState?) {
                s?.let { state ->
                    scope.launch(Dispatchers.Main) { listener?.onConnectionStateChanged(state) }
                    if (state == PeerConnection.PeerConnectionState.CONNECTED) startStatsCollection()
                }
            }
        }) ?: throw IllegalStateException("Falhou ao criar PeerConnection")

        // So queremos receber audio
        pc!!.addTransceiver(
            MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
        )

        // Cria oferta
        val offer = createOffer()
        setLocalDescription(offer)

        // Envia /offer ao servidor com os parametros de qualidade
        val body = JSONObject().apply {
            put("sdp", offer.description)
            put("type", "offer")
            put("quality", quality)
            put("stereo", stereo)
        }.toString()

        val req = Request.Builder()
            .url(serverUrl.trimEnd('/') + "/offer")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        val response = withContext(Dispatchers.IO) { http.newCall(req).execute() }
        if (!response.isSuccessful) throw RuntimeException("HTTP ${response.code}")
        val json = JSONObject(response.body!!.string())
        val answer = SessionDescription(SessionDescription.Type.ANSWER, json.getString("sdp"))
        setRemoteDescription(answer)
    }

    private suspend fun createOffer(): SessionDescription = suspendCancellableCoroutine { cont ->
        val opts = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        }
        pc!!.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sd: SessionDescription) { cont.resume(sd) {} }
            override fun onCreateFailure(err: String?) { cont.resumeWith(Result.failure(RuntimeException("createOffer: $err"))) }
            override fun onSetSuccess() {}
            override fun onSetFailure(err: String?) {}
        }, opts)
    }

    private suspend fun setLocalDescription(sd: SessionDescription) = suspendCancellableCoroutine<Unit> { cont ->
        pc!!.setLocalDescription(object : SdpObserver {
            override fun onCreateSuccess(sd: SessionDescription) {}
            override fun onCreateFailure(err: String?) {}
            override fun onSetSuccess() { cont.resume(Unit) {} }
            override fun onSetFailure(err: String?) { cont.resumeWith(Result.failure(RuntimeException("setLocal: $err"))) }
        }, sd)
    }

    private suspend fun setRemoteDescription(sd: SessionDescription) = suspendCancellableCoroutine<Unit> { cont ->
        pc!!.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(sd: SessionDescription) {}
            override fun onCreateFailure(err: String?) {}
            override fun onSetSuccess() { cont.resume(Unit) {} }
            override fun onSetFailure(err: String?) { cont.resumeWith(Result.failure(RuntimeException("setRemote: $err"))) }
        }, sd)
    }

    private fun startStatsCollection() {
        statsJob?.cancel()
        statsJob = scope.launch {
            while (isActive) {
                pc?.getStats { report ->
                    var bitrate = 0
                    var jitter = 0.0
                    var lost = 0
                    var rtt = 0.0
                    var codec = ""

                    for (stat in report.statsMap.values) {
                        when (stat.type) {
                            "inbound-rtp" -> {
                                val bytesNow = (stat.members["bytesReceived"] as? Number)?.toLong() ?: 0
                                val now = stat.timestampUs / 1000
                                if (lastBytesReceived > 0 && now > lastStatsTime) {
                                    bitrate = (((bytesNow - lastBytesReceived) * 8) / (now - lastStatsTime) / 1000 * 1000).toInt()
                                }
                                lastBytesReceived = bytesNow
                                lastStatsTime = now
                                jitter = ((stat.members["jitter"] as? Number)?.toDouble() ?: 0.0) * 1000
                                lost = (stat.members["packetsLost"] as? Number)?.toInt() ?: 0
                            }
                            "candidate-pair" -> {
                                if (stat.members["state"] == "succeeded") {
                                    rtt = ((stat.members["currentRoundTripTime"] as? Number)?.toDouble() ?: 0.0) * 1000
                                }
                            }
                            "codec" -> {
                                val mt = (stat.members["mimeType"] as? String).orEmpty()
                                if (mt.startsWith("audio/")) codec = mt.removePrefix("audio/")
                            }
                        }
                    }
                    val s = StreamStats(bitrate, jitter, lost, rtt, codec)
                    scope.launch(Dispatchers.Main) { listener?.onStatsUpdate(s) }
                }
                delay(500)
            }
        }
    }
}
