# PC Audio Stream — Android nativo

App Android nativo (Kotlin + Jetpack Compose) que conecta no `servidor.py`
via WebRTC, mas usando o caminho de áudio **AAudio LOW_LATENCY** nativo
em vez do Web Audio do Chrome.

## Por que vale a pena
| Caminho | Latência total típica |
|---|---|
| Browser Chrome no Android (WebRTC) | 50-120 ms |
| **Este app (AAudio nativo)** | **15-50 ms** |

Em dispositivos modernos (Pixel, Samsung Galaxy S20+, OnePlus 8+) com fones
com fio, fica em torno de **20-30 ms** — equivalente a uma placa de áudio
profissional via USB.

## Como compilar (uma vez só)

### 1. Instalar Android Studio
- Baixe em <https://developer.android.com/studio> (grátis, ~1 GB)
- Instala com defaults. Quando abrir pela primeira vez, deixa baixar o
  SDK (mais ~1 GB).

### 2. Abrir o projeto
- Abre Android Studio → **Open**
- Aponta pra esta pasta `android/`
- Espera o Gradle sincronizar (~3-5 min na primeira vez)

### 3. Compilar APK
- Menu **Build → Build Bundle(s) / APK(s) → Build APK(s)**
- Quando terminar, clica em **locate** no popup
- APK estará em `app/build/outputs/apk/release/app-release.apk`
- (Ou debug: `app/build/outputs/apk/debug/app-debug.apk`)

### 4. Instalar no celular
- Liga o cabo USB, ativa **Depuração USB** no celular
- No Android Studio, clica no botão Run (ícone do play) — instala e abre
- OU copia o `.apk` pro celular e instala manualmente (permite "fontes
  desconhecidas" nas configurações)

## Como usar
1. Roda o `iniciar.bat` no PC (servidor Python já tá rodando)
2. Abre o app no celular
3. Digita o IP do PC (ex: `192.168.100.149`)
4. Escolhe qualidade
5. **Iniciar Reprodução**

O IP fica salvo, não precisa digitar de novo.

## Latência real
Após conectar, o painel inferior mostra:
- **RTT (rede)**: ida e volta — geralmente 2-8 ms na LAN
- **Jitter**: variação dos pacotes — abaixo de 5 ms é ideal
- **Bitrate**: bps reais do opus
- **Pacotes perdidos**: deve ficar em 0

A latência **total** percebida = RTT/2 + jitter buffer (~20-40 ms) +
saída AAudio (~10-30 ms dependendo do dispositivo).

## Dicas de latência mínima
- **Modo avião + Wi-Fi 5GHz** — sem interferência de 4G/Bluetooth
- **Fone com fio** ou USB-C — sem codec BT
- **Desliga "som ambiente" / "modo eco"** nas configs do celular
- **Modo jogador / desempenho** se o celular tiver
