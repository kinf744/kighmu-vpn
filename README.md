# KIGHMU VPN

**Android VPN App** — Multi-protocol tunneling with advanced network bypass capabilities.

Package: `com.kighmu.vpn` | Min SDK: Android 5.0 (API 21)

---

## Architecture Overview

```
KIGHMU-VPN/
├── app/src/main/
│   ├── java/com/kighmu/vpn/
│   │   ├── ui/
│   │   │   ├── activities/
│   │   │   │   ├── MainActivity.kt          — Main host activity
│   │   │   │   ├── SplashActivity.kt
│   │   │   │   └── SettingsActivity.kt
│   │   │   ├── fragments/
│   │   │   │   ├── HomeFragment.kt          — Connect/disconnect UI
│   │   │   │   ├── ConfigFragment.kt        — All tunnel editors
│   │   │   │   ├── LogsFragment.kt          — Real-time log viewer
│   │   │   │   └── SettingsFragment.kt
│   │   │   └── MainViewModel.kt             — Shared state
│   │   │
│   │   ├── vpn/
│   │   │   └── KighmuVpnService.kt          — Android VpnService core
│   │   │
│   │   ├── engines/
│   │   │   ├── TunnelEngine.kt              — Interface + Factory
│   │   │   ├── SlowDnsEngine.kt             — Mode 1: DNS tunneling
│   │   │   └── AllEngines.kt                — Modes 2-7 (HTTP/SSH/Xray/Hysteria)
│   │   │
│   │   ├── config/
│   │   │   ├── ConfigManager.kt             — Import/export/load/save
│   │   │   └── ConfigEncryption.kt          — AES-256-GCM + scrypt + HWID
│   │   │
│   │   ├── models/
│   │   │   └── TunnelConfig.kt              — All data models
│   │   │
│   │   └── utils/
│   │       ├── KighmuLogger.kt              — Centralized logging
│   │       └── BootReceiver.kt              — Auto-start on boot
│   │
│   ├── cpp/
│   │   ├── CMakeLists.txt
│   │   ├── kighmu_tun.c                     — Native TUN packet handling (JNI)
│   │   └── kighmu_packet.c                  — Packet optimization
│   │
│   └── res/
│       ├── layout/                          — All XML layouts
│       ├── navigation/nav_graph.xml
│       ├── menu/
│       ├── values/ (colors, strings, themes)
│       └── xml/ (network_security, file_paths)
```

---

## Supported Tunnel Modes

| # | Mode | Protocol | Use Case |
|---|------|----------|----------|
| 1 | **SlowDNS** | DNS over UDP/TCP | Bypass via DNS queries |
| 2 | **HTTP Proxy + Payload** | HTTP/WebSocket | Custom payload injection |
| 3 | **SSH WebSocket** | SSH over WS/WSS | SSH through WebSocket |
| 4 | **SSH SSL/TLS** | SSH over TLS | SSH wrapped in SSL |
| 5 | **V2Ray / Xray** | VMESS/VLESS/Trojan/SS | Full Xray-core |
| 6 | **V2Ray + SlowDNS** | Xray over DNS | Combined transport |
| 7 | **Hysteria UDP** | QUIC/UDP | High-speed UDP tunnel |

---

## Setup Guide

### 1. Prerequisites

```bash
# Required tools
- Android Studio Hedgehog (2023.1.1) or newer
- NDK 25+
- CMake 3.22.1+
- JDK 17
- Go 1.21+ (for compiling Xray/Hysteria binaries)
```

### 2. Clone & Open

```bash
git clone https://github.com/yourorg/kighmu-vpn.git
cd kighmu-vpn
# Open in Android Studio
```

### 3. Compile Native Binaries (Xray + Hysteria)

```bash
# Xray-core for Android
cd scripts/
./build_xray.sh        # Outputs to app/src/main/assets/xray/

# Hysteria2 for Android
./build_hysteria.sh    # Outputs to app/src/main/assets/hysteria/
```

**Xray build script (scripts/build_xray.sh):**
```bash
#!/bin/bash
ANDROID_NDK=/path/to/ndk
for ABI in arm64-v8a armeabi-v7a x86_64; do
  GOARCH=$(echo $ABI | sed 's/arm64-v8a/arm64/;s/armeabi-v7a/arm/;s/x86_64/amd64/')
  GOARM=7
  CGO_ENABLED=0 GOOS=android GOARCH=$GOARCH \
    go build -o app/src/main/assets/xray/$ABI/xray \
    github.com/xtls/xray-core/main
done
```

**Hysteria2 build script (scripts/build_hysteria.sh):**
```bash
#!/bin/bash
for ABI in arm64-v8a armeabi-v7a x86_64; do
  GOARCH=$(echo $ABI | sed 's/arm64-v8a/arm64/;s/armeabi-v7a/arm/;s/x86_64/amd64/')
  CGO_ENABLED=0 GOOS=android GOARCH=$GOARCH \
    go build -o app/src/main/assets/hysteria/$ABI/hysteria2 \
    github.com/apernet/hysteria/app/cmd
done
```

### 4. Build APK

```bash
./gradlew assembleRelease
# Output: app/build/outputs/apk/release/app-release.apk
```

---

## Configuration File Format (.kighmu)

Config files use the `.kighmu` extension and are:
- **Encrypted**: AES-256-GCM
- **Compressed**: DEFLATE
- **Key derivation**: scrypt(password + salt)
- **Optional**: Hardware ID lock, expiry date, signature

### Create a locked config programmatically:
```kotlin
val manager = ConfigManager(context)
val locked = manager.createLockedConfig(
    base = currentConfig,
    expiresInDays = 30,
    lockToCurrentDevice = true,
    creator = "YourName",
    signingKey = "secret_key"
)
manager.exportConfig(locked, outputUri)
```

### Import a .kighmu file:
```kotlin
val result = manager.importFromUri(fileUri)
when (result) {
    is ImportResult.Success -> { /* Use result.config */ }
    is ImportResult.Expired -> { /* Show expiry message */ }
    is ImportResult.WrongDevice -> { /* Device mismatch */ }
}
```

---

## JSON Config for Xray

Paste any valid Xray/V2Ray JSON config in the Xray tab:

```json
{
  "log": { "loglevel": "warning" },
  "inbounds": [{
    "port": 10808,
    "protocol": "socks",
    "settings": { "udp": true }
  }],
  "outbounds": [{
    "protocol": "vless",
    "settings": {
      "vnext": [{
        "address": "your-server.com",
        "port": 443,
        "users": [{ "id": "uuid-here", "encryption": "none" }]
      }]
    },
    "streamSettings": {
      "network": "ws",
      "security": "tls",
      "wsSettings": { "path": "/vless" },
      "tlsSettings": { "serverName": "your-server.com" }
    }
  }]
}
```

---

## HTTP Payload Templates

### WebSocket Upgrade
```
GET / HTTP/1.1[crlf]
Host: [host][crlf]
Connection: Upgrade[crlf]
Upgrade: websocket[crlf]
[crlf]
```

### HTTP CONNECT
```
CONNECT [host]:[port] HTTP/1.1[crlf]
Host: [host][crlf]
[crlf]
```

### Payload Placeholders
| Placeholder | Replaced with |
|-------------|--------------|
| `[host]` | Destination host |
| `[port]` | Destination port |
| `[crlf]` | `\r\n` |
| `[cr]` | `\r` |
| `[lf]` | `\n` |

---

## Security Features

| Feature | Implementation |
|---------|---------------|
| Config encryption | AES-256-GCM + scrypt KDF |
| Hardware binding | SHA-256(ANDROID_ID + fingerprint) |
| Expiry dates | Unix timestamp validation |
| Signature | SHA-256 HMAC |
| Anti-reverse | ProGuard + aggressive obfuscation |
| DNS leak protection | Route all DNS through VPN |
| Kill switch | Block traffic if VPN drops |

---

## Adding a New Tunnel Engine

1. Implement `TunnelEngine` interface:
```kotlin
class MyEngine(config: KighmuConfig, context: Context) : TunnelEngine {
    override suspend fun start(): Int { /* start, return local port */ }
    override suspend fun stop() { /* cleanup */ }
    override suspend fun sendData(data: ByteArray, length: Int) {}
    override suspend fun receiveData(): ByteArray? = null
    override fun isRunning(): Boolean = running
}
```

2. Add to `TunnelMode` enum in `TunnelConfig.kt`

3. Register in `TunnelEngineFactory.create()`

4. Add UI entry in `fragment_config.xml` and `ConfigFragment.kt`

---

## Dependencies

| Library | Purpose |
|---------|---------|
| JSch (mwiede) | SSH client |
| OkHttp | WebSocket client |
| BouncyCastle | Crypto (scrypt, AES) |
| Gson | JSON serialization |
| Sora Editor | JSON syntax highlighting |
| MPAndroidChart | Traffic stats charts |
| Kotlin Coroutines | Async tunnel management |
| Room | Config persistence |

---

## License

MIT License — KIGHMU VPN © 2024
