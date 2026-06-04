"""
PC Audio Stream — WebRTC edition.

Por que WebRTC: o caminho Web Audio do Chrome no Android adiciona ~200-300 ms
de latencia fixa no AudioContext.destination. WebRTC pula essa pipeline e
usa o caminho de audio nativo (AAudio/OpenSL ES) com codec opus, jitter
buffer adaptativo e clock recovery, alcancando ~30-80 ms total no celular.
"""
import asyncio
import fractions
import json
import logging
import os
import socket
import sys
import threading
import warnings
from typing import Optional

import numpy as np
import uvicorn
from aiortc import RTCPeerConnection, RTCSessionDescription, MediaStreamTrack
from av import AudioFrame
from fastapi import FastAPI, Request
from fastapi.responses import HTMLResponse, JSONResponse

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

warnings.filterwarnings("ignore", message="data discontinuity in recording")
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
logger = logging.getLogger(__name__)
# silencia logs ruidosos do aiortc/aioice
for n in ("aioice", "aiortc", "aiortc.rtcsctptransport", "aiortc.rtcicetransport"):
    logging.getLogger(n).setLevel(logging.WARNING)

HTTP_PORT = 8765
CAPTURE_RATE = 48000
CAPTURE_CHANNELS = 2
FRAME_MS = 20  # opus encoder: 20 ms por frame (compromisso latencia/eficiencia)
FRAME_SAMPLES = CAPTURE_RATE * FRAME_MS // 1000  # 960 samples


# ---------------------------------------------------------------------------
# Captura: thread dedicada empurra frames pra uma fila asyncio
# ---------------------------------------------------------------------------
audio_queue: "asyncio.Queue[np.ndarray]" = None  # type: ignore
main_loop: Optional[asyncio.AbstractEventLoop] = None
capture_running = True
pcs: set = set()  # PeerConnections ativas


def capture_thread():
    """Thread bloqueante de captura WASAPI loopback -> fila."""
    try:
        import soundcard as sc
    except ImportError:
        logger.error("soundcard nao instalado")
        return
    try:
        spk = sc.default_speaker()
        mic = sc.get_microphone(id=str(spk.name), include_loopback=True)
        logger.info("Captura WASAPI loopback: %s (%d Hz, %d ms/frame)",
                    spk.name, CAPTURE_RATE, FRAME_MS)
        with mic.recorder(samplerate=CAPTURE_RATE, channels=CAPTURE_CHANNELS,
                          blocksize=FRAME_SAMPLES) as rec:
            while capture_running:
                data = rec.record(numframes=FRAME_SAMPLES).astype(np.float32)
                if main_loop and audio_queue is not None and pcs:
                    # entrega thread-safe na fila do event loop
                    main_loop.call_soon_threadsafe(_push_frame, data)
    except Exception as e:
        logger.error("Captura falhou: %s", e)


def _push_frame(data: np.ndarray):
    """Roda no event loop. Empurra o frame na fila, descartando antigo se cheia."""
    if audio_queue is None:
        return
    try:
        audio_queue.put_nowait(data)
    except asyncio.QueueFull:
        # descarta o mais antigo (mantem audio fresco em vez de acumular)
        try:
            audio_queue.get_nowait()
            audio_queue.put_nowait(data)
        except Exception:
            pass


# ---------------------------------------------------------------------------
# MediaStreamTrack que entrega frames pro aiortc encodar em opus
# ---------------------------------------------------------------------------
class LoopbackTrack(MediaStreamTrack):
    kind = "audio"

    def __init__(self):
        super().__init__()
        self._pts = 0
        self._tb = fractions.Fraction(1, CAPTURE_RATE)

    async def recv(self) -> AudioFrame:
        # Aguarda o proximo bloco de audio
        data = await audio_queue.get()
        # float32 (frames, ch) -> int16 interleaved -> AudioFrame
        samples = np.clip(data, -1.0, 1.0)
        samples = (samples * 32767.0).astype(np.int16)
        # AudioFrame s16 stereo espera shape (1, frames*channels) interleaved
        interleaved = samples.reshape(1, -1)
        frame = AudioFrame.from_ndarray(interleaved, format="s16", layout="stereo")
        frame.sample_rate = CAPTURE_RATE
        frame.pts = self._pts
        frame.time_base = self._tb
        self._pts += data.shape[0]
        return frame


# ---------------------------------------------------------------------------
# HTML/JS — RTCPeerConnection no cliente
# ---------------------------------------------------------------------------
HTML = r"""<!DOCTYPE html>
<html lang="pt-BR">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
<title>PC Audio Stream</title>
<style>
*{box-sizing:border-box;margin:0;padding:0}
body{background:#07070f;color:#ddd;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;min-height:100vh;display:flex;align-items:center;justify-content:center;padding:16px}
.card{background:#11111e;border:1px solid #252538;border-radius:22px;padding:26px 22px;width:100%;max-width:430px;box-shadow:0 12px 60px rgba(0,0,0,.6)}
h1{font-size:1.35rem;color:#a78bfa;margin-bottom:3px}
.sub{font-size:.78rem;color:#555;margin-bottom:20px}
.sbar{display:flex;gap:8px;margin-bottom:20px;flex-wrap:wrap}
.badge{display:flex;align-items:center;gap:6px;background:#181828;border:1px solid #252538;border-radius:20px;padding:5px 11px;font-size:.74rem}
.dot{width:8px;height:8px;border-radius:50%;background:#3a3a5c}
.dot.green{background:#4ade80;box-shadow:0 0 8px #4ade8088}
.dot.red{background:#f87171}
.pv{font-weight:700;color:#4ade80;min-width:20px;text-align:right}
.pv.warn{color:#facc15}.pv.bad{color:#f87171}
label{display:block;font-size:.78rem;color:#666;margin-bottom:6px}
.vrow{display:flex;align-items:center;gap:10px;margin-bottom:18px}
.vrow span{font-size:.78rem;color:#555;min-width:34px;text-align:right}
input[type=range]{-webkit-appearance:none;flex:1;height:6px;background:#1e1e2e;border-radius:3px;outline:none}
input[type=range]::-webkit-slider-thumb{-webkit-appearance:none;width:18px;height:18px;background:#a78bfa;border-radius:50%;cursor:pointer}
.pbtn{width:100%;padding:15px;border:none;border-radius:14px;background:linear-gradient(135deg,#7c3aed,#a78bfa);color:#fff;font-size:1.05rem;font-weight:700;cursor:pointer;display:flex;align-items:center;justify-content:center;gap:10px;transition:transform .15s;box-shadow:0 4px 20px #7c3aed44;-webkit-tap-highlight-color:transparent}
.pbtn:active{transform:scale(.97)}
.pbtn.stop{background:linear-gradient(135deg,#b91c1c,#ef4444);box-shadow:0 4px 20px #ef444444}
.viz{height:46px;display:flex;align-items:flex-end;gap:2px;justify-content:center;margin-top:18px}
.bar{width:5px;background:#a78bfa33;border-radius:2px;min-height:2px}
details{margin-top:16px}
summary{font-size:.75rem;color:#555;cursor:pointer;outline:none;user-select:none}
.adv{padding:14px 0 0;display:flex;flex-direction:column;gap:8px}
.kv{display:flex;justify-content:space-between;font-size:.72rem;color:#555}
.kv b{color:#888;font-weight:600}
.hint{font-size:.68rem;color:#444;line-height:1.4;margin-top:6px}
.tag{display:inline-block;background:#7c3aed22;color:#a78bfa;padding:2px 8px;border-radius:10px;font-size:.65rem;font-weight:600;margin-left:6px;vertical-align:middle}
</style>
</head>
<body>
<div class="card">
  <h1>&#127882; PC Audio Stream <span class="tag">WebRTC</span></h1>
  <p class="sub">Áudio do PC no celular &middot; pipeline nativa, baixa latência</p>

  <div class="sbar">
    <div class="badge"><div class="dot" id="cdot"></div><span id="ctxt">Desconectado</span></div>
    <div class="badge">Ping <span class="pv" id="ping">--</span><span style="color:#555">ms</span></div>
    <div class="badge">Jitter <span class="pv" id="jit" style="color:#a78bfa">--</span><span style="color:#555">ms</span></div>
  </div>

  <label>Qualidade (bitrate opus)</label>
  <select id="q" style="width:100%;margin-bottom:16px;background:#181828;border:1px solid #2e2e48;color:#ddd;padding:10px 14px;border-radius:12px;font-size:.88rem;-webkit-appearance:none">
    <option value="ultra" selected>&#9889; Ultra — 192 kbps (estúdio)</option>
    <option value="high">&#127925; Alta — 128 kbps</option>
    <option value="medium">&#127926; Média — 64 kbps</option>
    <option value="low">&#128251; Baixa — 32 kbps (menor banda)</option>
  </select>

  <label>Perfil de latência</label>
  <select id="lp" style="width:100%;margin-bottom:16px;background:#181828;border:1px solid #2e2e48;color:#ddd;padding:10px 14px;border-radius:12px;font-size:.88rem;-webkit-appearance:none">
    <option value="0" selected>&#128293; Ultra-baixa (0 ms hint) — pode glitchar</option>
    <option value="0.04">&#9889; Mínima (40 ms hint)</option>
    <option value="0.1">Balanceada (100 ms hint)</option>
    <option value="0.2">Estável (200 ms hint)</option>
  </select>

  <div style="display:flex;gap:14px;margin-bottom:16px;align-items:center">
    <label style="margin:0;display:flex;align-items:center;gap:6px;cursor:pointer">
      <input type="checkbox" id="stereo" checked style="accent-color:#a78bfa">
      <span style="font-size:.82rem;color:#aaa">Estéreo</span>
    </label>
  </div>

  <label>Volume</label>
  <div class="vrow">
    <span>&#128264;</span>
    <input type="range" id="vol" min="0" max="100" value="90">
    <span id="vpct">90%</span>
    <span>&#128266;</span>
  </div>

  <button class="pbtn" id="pbtn" onclick="toggle()">&#9654; Iniciar Reprodução</button>

  <audio id="aud" autoplay playsinline></audio>
  <div class="viz" id="viz"></div>

  <details>
    <summary>&#9881; Estatísticas WebRTC</summary>
    <div class="adv">
      <div class="kv"><span>Codec</span><b id="codec">--</b></div>
      <div class="kv"><span>Bitrate</span><b id="brate">--</b></div>
      <div class="kv"><span>Jitter buffer (alvo)</span><b id="jbtgt">--</b></div>
      <div class="kv"><span>Jitter buffer (atual)</span><b id="jbcur">--</b></div>
      <div class="kv"><span>Latência de saída (hardware)</span><b id="outlat">--</b></div>
      <div class="kv"><span>Pacotes perdidos</span><b id="lost">0</b></div>
      <p class="hint">
      WebRTC usa o caminho de áudio nativo do Android (não o Web Audio), com
      jitter buffer adaptativo. A latência total real é
      <b>Ping/2 + Jitter buffer + Latência de saída</b>. Em redes boas, fica
      em 50-120 ms total. Sem WebRTC, o piso do Web Audio Android é 200-300 ms.</p>
    </div>
  </details>
</div>

<script>
let pc=null, on=false, statT=null, lastStats=null, audioEl=null;

const viz=document.getElementById('viz');
for(let i=0;i<28;i++){const b=document.createElement('div');b.className='bar';viz.appendChild(b);}
const bars=viz.querySelectorAll('.bar');

let analyser=null, ctxA=null, freqData=null, rafId=null;
function drawViz(){
  if(!analyser){rafId=null;return;}
  analyser.getByteFrequencyData(freqData);
  const step=Math.floor(freqData.length/28);
  for(let i=0;i<28;i++){
    let s=0; for(let j=0;j<step;j++) s+=freqData[i*step+j];
    const h=Math.min(44,(s/step)/255*60);
    bars[i].style.height=Math.max(2,h)+'px';
    bars[i].style.background=h>30?'#c084fc':h>14?'#a78bfa':'#a78bfa55';
  }
  rafId=requestAnimationFrame(drawViz);
}

document.getElementById('vol').oninput=function(){
  document.getElementById('vpct').textContent=this.value+'%';
  if(audioEl) audioEl.volume=this.value/100;
};

// Mudar qualquer config: reconecta com os novos params (rapido, <1s)
function rebootIfOn(){ if(on){ stop(); setTimeout(start, 200); } }
document.getElementById('q').onchange = rebootIfOn;
document.getElementById('stereo').onchange = rebootIfOn;
document.getElementById('lp').onchange = function(){
  // Latency hint pode mudar sem reconectar
  if(pc){
    const hint = parseFloat(this.value);
    try{
      const recv = pc.getReceivers().find(r=>r.track&&r.track.kind==='audio');
      if(recv){ recv.playoutDelayHint=hint; recv.jitterBufferTarget=hint; }
    }catch(_){}
  }
};

function setPing(ms){
  const e=document.getElementById('ping');
  e.textContent=ms==='--'?'--':ms.toFixed(0);
  e.className='pv'+(ms!=='--'?(ms<25?'':ms<70?' warn':' bad'):'');
}
function setConn(okk){
  document.getElementById('cdot').className='dot'+(okk?' green':' red');
  document.getElementById('ctxt').textContent=okk?'Conectado':'Desconectado';
}

async function start(){
  audioEl = document.getElementById('aud');
  audioEl.volume = document.getElementById('vol').value/100;

  pc = new RTCPeerConnection({iceServers:[]});

  const transceiver = pc.addTransceiver('audio', {direction:'recvonly'});

  pc.ontrack = (e) => {
    audioEl.srcObject = e.streams[0];
    // playoutDelayHint: pista pro jitter buffer do WebRTC mirar latencia baixa.
    // 0 = "ultra-baixa, aceito glitches"; 0.2 = "200ms, estavel".
    const hint = parseFloat(document.getElementById('lp').value);
    try{
      const recv = pc.getReceivers().find(r=>r.track&&r.track.kind==='audio');
      if(recv){
        recv.playoutDelayHint = hint;        // padrao W3C (Chrome 100+)
        recv.jitterBufferTarget = hint;      // alternativa em alguns Chromes
      }
    }catch(e){ console.warn('playoutDelayHint nao suportado:', e); }
    // Liga visualizer via Web Audio (so para visual; nao toca audio dele)
    try{
      ctxA = new (window.AudioContext||window.webkitAudioContext)();
      const src = ctxA.createMediaStreamSource(e.streams[0]);
      analyser = ctxA.createAnalyser();
      analyser.fftSize = 128; analyser.smoothingTimeConstant = 0.6;
      freqData = new Uint8Array(analyser.frequencyBinCount);
      src.connect(analyser);
      if(!rafId) rafId = requestAnimationFrame(drawViz);
    }catch(_){}
  };

  pc.onconnectionstatechange = () => {
    if(pc.connectionState==='connected') setConn(true);
    else if(['disconnected','failed','closed'].includes(pc.connectionState)){
      setConn(false);
      if(on) stop();
    }
  };

  // Cria oferta e envia para o servidor
  const offer = await pc.createOffer();
  await pc.setLocalDescription(offer);

  // Espera o ICE gathering completar (collect candidates locais)
  await new Promise(res=>{
    if(pc.iceGatheringState==='complete') return res();
    pc.addEventListener('icegatheringstatechange', ()=>{
      if(pc.iceGatheringState==='complete') res();
    });
  });

  const resp = await fetch('/offer', {
    method:'POST',
    headers:{'Content-Type':'application/json'},
    body: JSON.stringify({
      sdp: pc.localDescription.sdp,
      type: pc.localDescription.type,
      quality: document.getElementById('q').value,
      stereo: document.getElementById('stereo').checked,
    })
  });
  const ans = await resp.json();
  await pc.setRemoteDescription(ans);

  on = true;
  statT = setInterval(updateStats, 500);
  const b = document.getElementById('pbtn');
  b.innerHTML='&#9646;&#9646; Parar'; b.classList.add('stop');
}

async function updateStats(){
  if(!pc) return;
  const stats = await pc.getStats();
  let inbound=null, candPair=null;
  stats.forEach(r=>{
    if(r.type==='inbound-rtp' && r.kind==='audio') inbound=r;
    if(r.type==='candidate-pair' && r.state==='succeeded' && r.nominated) candPair=r;
  });
  if(inbound){
    document.getElementById('jit').textContent=(inbound.jitter*1000).toFixed(1);
    document.getElementById('lost').textContent=inbound.packetsLost||0;
    if(inbound.codecId){
      const cd=stats.get(inbound.codecId);
      if(cd) document.getElementById('codec').textContent=
        (cd.mimeType||'').replace('audio/','')+' @ '+(cd.clockRate||0)+' Hz';
    }
    if(lastStats && lastStats.bytesReceived){
      const dB=(inbound.bytesReceived-lastStats.bytesReceived);
      const dT=(inbound.timestamp-lastStats.timestamp)/1000;
      if(dT>0) document.getElementById('brate').textContent=((dB*8/dT)/1000).toFixed(0)+' kbps';
    }
    if(inbound.jitterBufferTarget!=null)
      document.getElementById('jbtgt').textContent=(inbound.jitterBufferTarget*1000).toFixed(0)+' ms';
    if(inbound.jitterBufferDelay!=null && inbound.jitterBufferEmittedCount){
      const cur=inbound.jitterBufferDelay/inbound.jitterBufferEmittedCount*1000;
      document.getElementById('jbcur').textContent=cur.toFixed(0)+' ms';
    }
    lastStats=inbound;
  }
  if(candPair && candPair.currentRoundTripTime!=null)
    setPing(candPair.currentRoundTripTime*1000/2);
  if(ctxA){
    const ol=((ctxA.outputLatency||ctxA.baseLatency||0)*1000);
    document.getElementById('outlat').textContent=ol.toFixed(0)+' ms';
  }
}

function stop(){
  on=false;
  clearInterval(statT); statT=null;
  if(rafId){ cancelAnimationFrame(rafId); rafId=null; }
  if(analyser){ analyser=null; }
  if(ctxA){ try{ctxA.close();}catch(_){} ctxA=null; }
  if(pc){ try{pc.close();}catch(_){} pc=null; }
  if(audioEl) audioEl.srcObject=null;
  setConn(false); setPing('--');
  document.getElementById('jit').textContent='--';
  bars.forEach(b=>{b.style.height='2px';b.style.background='#a78bfa33';});
  const b=document.getElementById('pbtn');
  b.innerHTML='&#9654; Iniciar Reprodução'; b.classList.remove('stop');
}

function toggle(){ on?stop():start(); }

document.getElementById('pbtn').addEventListener('click',async()=>{
  try{ if('wakeLock' in navigator) await navigator.wakeLock.request('screen'); }catch(_){}
});
</script>
</body>
</html>"""


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------
def get_local_ip() -> str:
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return "127.0.0.1"


def get_all_ips() -> list:
    ips = []
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ip = info[4][0]
            if not ip.startswith("127.") and ip not in ips:
                ips.append(ip)
    except Exception:
        pass
    primary = get_local_ip()
    if primary in ips:
        ips.remove(primary)
    ips.insert(0, primary)
    return ips


# ---------------------------------------------------------------------------
# Opus low-latency tuning: forca o encoder ao modo mais agressivo de latencia
# ---------------------------------------------------------------------------
def patch_opus_for_lowlatency():
    """Reduz frame opus se possivel; configura encoder pra latencia minima."""
    try:
        # aiortc encoder padrao usa 20ms frames; ja eh bom.
        # Forca aplication=VOIP+RESTRICTED_LOWDELAY se a versao expoe.
        from aiortc.codecs.opus import OpusEncoder  # type: ignore
        orig_init = OpusEncoder.__init__

        def patched(self, *a, **kw):
            orig_init(self, *a, **kw)
            try:
                import opuslib
                # OPUS_APPLICATION_RESTRICTED_LOWDELAY = 2051
                self.encoder.application = 2051  # type: ignore
            except Exception:
                pass

        OpusEncoder.__init__ = patched
    except Exception:
        pass


# ---------------------------------------------------------------------------
# App FastAPI + WebRTC signaling
# ---------------------------------------------------------------------------
app = FastAPI()


@app.get("/")
async def index():
    return HTMLResponse(HTML)


def _set_opus_params(sdp: str, bitrate: int, stereo: bool) -> str:
    """Injeta parametros opus na SDP: bitrate alvo e modo mono/stereo."""
    lines = sdp.split("\r\n")
    out = []
    # Acha o payload type do opus
    pt = None
    for line in lines:
        if line.startswith("a=rtpmap:") and "opus/" in line.lower():
            pt = line.split(":")[1].split(" ")[0]
            break
    for line in lines:
        if pt and line.startswith(f"a=fmtp:{pt}"):
            parts = line.split(" ", 1)
            extras = [
                f"maxaveragebitrate={bitrate}",
                f"stereo={1 if stereo else 0}",
                f"sprop-stereo={1 if stereo else 0}",
                "useinbandfec=1",
                "usedtx=0",
                "minptime=10",
                "maxptime=20",
            ]
            line = parts[0] + " " + ";".join(extras)
        out.append(line)
    return "\r\n".join(out)


@app.post("/offer")
async def offer(request: Request):
    params = await request.json()
    # Quality presets -> bitrate (bps) opus
    quality = params.get("quality", "ultra")
    stereo = params.get("stereo", True)
    bitrates = {"low": 32000, "medium": 64000, "high": 128000, "ultra": 192000}
    bitrate = bitrates.get(quality, 128000)

    offer_sdp = RTCSessionDescription(sdp=params["sdp"], type=params["type"])
    pc = RTCPeerConnection()
    pcs.add(pc)

    @pc.on("connectionstatechange")
    async def _on_state():
        logger.info("PC %s -> %s (q=%s, %d bps, %s)",
                    id(pc), pc.connectionState, quality, bitrate,
                    "stereo" if stereo else "mono")
        if pc.connectionState in ("failed", "closed", "disconnected"):
            await pc.close()
            pcs.discard(pc)

    pc.addTrack(LoopbackTrack())
    await pc.setRemoteDescription(offer_sdp)
    answer = await pc.createAnswer()

    # Aplica params opus na SDP local antes de setar
    answer = RTCSessionDescription(
        sdp=_set_opus_params(answer.sdp, bitrate, stereo),
        type=answer.type,
    )
    await pc.setLocalDescription(answer)

    # Tambem ajusta encoder ao vivo se possivel
    try:
        for sender in pc.getSenders():
            enc = getattr(sender, "encoder", None)
            if enc is not None:
                try: enc.bitrate = bitrate
                except Exception: pass
    except Exception:
        pass

    return JSONResponse({
        "sdp": pc.localDescription.sdp,
        "type": pc.localDescription.type,
        "bitrate": bitrate,
    })


# ---------------------------------------------------------------------------
# Boot
# ---------------------------------------------------------------------------
def print_banner(ips):
    line = "-" * 54
    print(f"\n  +{line}+")
    print(f"  |{'PC Audio Stream  (WebRTC, baixa latencia)':^54}|")
    print(f"  +{line}+")
    print("  |  Abra no celular (mesma rede WiFi):".ljust(55) + " |")
    print("  |".ljust(55) + " |")
    for ip in ips:
        print(f"  |       http://{ip}:{HTTP_PORT}".ljust(55) + " |")
    print("  |".ljust(55) + " |")
    print("  |  Aceita o aviso se aparecer.  Ctrl+C para parar.".ljust(55) + " |")
    print(f"  +{line}+\n", flush=True)


async def main():
    global main_loop, audio_queue, capture_running
    main_loop = asyncio.get_running_loop()
    # fila com cap pequeno: jitter buffer fica no cliente (WebRTC), nao no servidor.
    audio_queue = asyncio.Queue(maxsize=5)

    patch_opus_for_lowlatency()
    threading.Thread(target=capture_thread, daemon=True).start()

    ips = get_all_ips()
    print_banner(ips)

    cfg = uvicorn.Config(app, host="0.0.0.0", port=HTTP_PORT,
                         log_level="warning")
    server = uvicorn.Server(cfg)
    server.install_signal_handlers = lambda: None
    try:
        await server.serve()
    finally:
        capture_running = False
        for pc in list(pcs):
            try:
                await pc.close()
            except Exception:
                pass
        pcs.clear()


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("\n  Servidor encerrado.")
