# V2Picker for Android

Android port of the desktop V2Picker (PySide6 + sing-box). Same brain, phone body:

| Desktop                         | Android                                             |
|---------------------------------|-----------------------------------------------------|
| sing-box.exe subprocess         | libbox (sing-box as a library) inside the app       |
| Windows system proxy / TUN      | Android VpnService (no root needed)                 |
| TUN "apps" by .exe name         | "Only chosen apps" picker (per-app VPN)             |
| `sing-box check` bisect         | `Libbox.checkConfig` bisect                         |
| clash API delay/select/traffic  | same, on 127.0.0.1:19090                            |
| kill switch                     | Android Always-on VPN + "Block connections w/o VPN" |

Kept 1:1: link parser (vmess / vless+reality / trojan / ss / hy2 / tuic / base64 subs), dedupe,
3 subscription slots (stale configs dropped, favorites kept), 16-way parallel scan, stale-result
re-test of the best 60 before connecting, auto pool = best 20 (urltest), Iran bypass (.ir +
Chocolate4U geosite/geoip rule-sets, falls back to .ir only if the lists break), leak guard
(DNS hijack, STUN/DoT/IPv6 reject), live delay sparkline + min/avg/max/loss, up/down speed.

## Get an APK without installing anything (recommended)
1. Push this folder to a GitHub repo.
2. Actions tab → **Build APK** → Run workflow.
3. ~10 min later download **V2Picker-apk** from the run, install on the phone.

The workflow compiles sing-box's `libbox.aar` from source (pinned to `SING_BOX_VERSION`) and then the app.

## Build locally (Android Studio)
1. Build or grab `libbox.aar` for sing-box **1.12.x** and drop it in `app/libs/`:
   ```
   git clone -b v1.12.0 https://github.com/SagerNet/sing-box && cd sing-box
   make lib_install && go run ./cmd/internal/build_libbox -target android
   ```
   (needs Go 1.24 + Android NDK; or just let the GitHub workflow do it)
2. Open the folder in Android Studio, let it sync, Run.

## If the build fails on Platform.kt
That file implements libbox's `PlatformInterface`, whose method list changes between sing-box
versions. Either keep `SING_BOX_VERSION` at 1.12.x, or copy the method signatures from
`sing-box-for-android/app/src/main/java/io/nekohasekai/sfa/bg/PlatformInterfaceWrapper.kt` at the
tag you use. Nothing else in the app touches libbox internals.

## Files
- `Parser.kt`  ← parser.py
- `Config.kt`  ← build_config / validation / subscription / Iran rules (from app.py)
- `Clash.kt`   ← SingBox delay/current/select/stream_traffic
- `Core.kt`    ← MainWindow logic (import, subs, scan, connect, monitor)
- `BoxVpnService.kt`, `Platform.kt` ← Android VPN glue
- `MainActivity.kt` ← Compose UI (dark glass look, same colors)
