# Constellation Glass

**English** · [简体中文](README.zh-CN.md)

The eyewear client for [Constellation](https://github.com/MRziyi/Constellation) — a personal
AI framework for all-day wearable assistance. This is a deliberately thin Android app: it
captures voice and camera frames, ships them to the Mac over a TLS WebSocket, and renders
whatever cards come back on a 480×640 monochrome-green micro-LED panel.

**Almost none of the intelligence is here.** The glasses capture and display; the
[server](https://github.com/MRziyi/Constellation-Server) thinks. That split is the design,
not a limitation to be fixed later — it is what keeps the glasses cool, light, and lasting
a day on their battery.

## Target hardware

Rokid Glasses — JBD4020 monochrome-green micro-LED, 480×640 portrait, right eye only;
YodaOS-Sprite (Android 12 Go, API 32) on Qualcomm 8250 with an NXP RT600 DSP.

The app runs **bare-metal**: a normal Android app installed on the glasses themselves,
using stock `AudioRecord`, system key broadcasts, and a `SYSTEM_ALERT_WINDOW` overlay. It
does **not** link the CXR-L SDK and does not run as a phone-side bridge. The reasoning, and
what that choice costs, is in
[GLASS-CLIENT-DESIGN.md](https://github.com/MRziyi/Constellation/blob/main/docs/glass/GLASS-CLIENT-DESIGN.md).

## What it does

| | |
|---|---|
| **Voice** | Push-to-talk on a physical key, hard-capped. No wake word, no ambient listening — an energy promise, not a feature gap. Raw 16 kHz PCM streams to the server; transcription happens there. |
| **Vision** | CameraX still capture, triggered by a shortcut or a gesture. Frames ride to the server as image blocks. |
| **HUD** | Jetpack Compose rendering into a `SYSTEM_ALERT_WINDOW` overlay, so cards can appear over whatever else is running. |
| **Control** | Single tap approves, double tap kills, long press continues or re-dictates. There is no dismiss — every card reaches a terminal state. |
| **Pairing** | Scan a QR from the web console. It carries the endpoint and the auth cookie; no server address is compiled into the app. |
| **Ring** | Optional gesture input from [Halo Ring](https://github.com/MRziyi/Halo-Ring), through a broadcast plugin protocol. The app is complete without it. |

## Build

```bash
./gradlew :app:assembleGlassDebug
adb -s <glass-serial> install -r app/build/outputs/apk/glass/debug/app-glass-debug.apk
```

Requires JDK 17. `minSdk` 28 · `targetSdk` 32 (YodaOS ships API 32) · `compileSdk` 34.

### Two flavors

| Flavor | Purpose |
|---|---|
| `glass` | The real device. `AudioRecord` with channel mask `0x6000FC`, system key broadcasts received in a foreground service, HUD as an overlay. |
| `phoneDebug` | Any ordinary Android phone. Mono `AudioRecord`, a `SYSTEM_ALERT_WINDOW` overlay, and simulated input through notification action buttons. Lets you verify the protocol, the state machine, and the WSS path without the glasses on your face. |

Use `phoneDebug` for most development. It is a genuine simulator of the client, not a stub —
the HUD Composables are shared between both flavors.

> **Release builds are signed with the debug keystore.** That is intentional for a
> sideloaded prototype and is set in `app/build.gradle.kts`. Swap in a real upload keystore
> before distributing anything.

## Layout

```
app/src/main/kotlin/com/constellation/glass/
  ConstellationService.kt   the foreground service holding the session together
  MainActivity.kt           app UI host · BootReceiver.kt  start on boot
  state/                    State.kt + StateMachine.kt — the client's whole behaviour model
  wss/                      WssClient.kt (OkHttp) + Frames.kt (kotlinx-serialization)
  hud/                      HUD surface, layouts, theme, scroll window, styled-run renderer
    composables/            the actual cards
  audio/                    capture · pipeline · MicGate (the hard cap lives here)
  camera/                   CameraCapture · CameraGate · QrScanner
  auth/CookieStore.kt       session cookie from pairing
  app/                      EndpointStore (DataStore) + the in-app screens
  halo/                     Halo Ring bridge — trigger receiver, overlay, action provider
  input/InputHandler.kt     tap / double-tap / long-press semantics
  net/HttpRetry.kt          retry policy for a link that drops

app/src/glass/              device-specific: audio capture, HUD overlay, system keys
app/src/phoneDebug/         phone simulator: audio, HUD, simulated input
```

`state/StateMachine.kt` is where to start reading. Nearly every behavioural question about
the client is answered there.

## Networking

The link is a TLS WebSocket to a public relay, which forwards to Cortex on the Mac. In
practice it has run over Wi-Fi, over Tailscale, and over Bluetooth PAN via a phone hotspot —
each with different failure modes, documented in
[NETWORK-ALTERNATIVES.md](https://github.com/MRziyi/Constellation/blob/main/docs/glass/NETWORK-ALTERNATIVES.md).

The endpoint is never compiled in. An unpaired device holds an empty endpoint and shows the
pairing prompt.

## Design references

All design documents live in the [Constellation](https://github.com/MRziyi/Constellation) repository:

- [GLASS-CLIENT-DESIGN.md](https://github.com/MRziyi/Constellation/blob/main/docs/glass/GLASS-CLIENT-DESIGN.md) — the client design (v2.1).
- [GLASS-SDK-REFERENCE.md](https://github.com/MRziyi/Constellation/blob/main/docs/glass/GLASS-SDK-REFERENCE.md) — what this hardware actually does with audio, keys, display, foreground services, and the camera. **Read this before writing device code.**
- [UI-UX.md](https://github.com/MRziyi/Constellation/blob/main/docs/glass/UI-UX.md) — HUD visual language for a monochrome-green panel.
- [IN-APP-UI-DESIGN.md](https://github.com/MRziyi/Constellation/blob/main/docs/glass/IN-APP-UI-DESIGN.md) — app screens and the QR pairing flow.
- [INTERFACE-CONTRACTS.md](https://github.com/MRziyi/Constellation/blob/main/docs/server/INTERFACE-CONTRACTS.md) — the Glass↔Cortex wire protocol.
- [PAIRING-AND-AUTH-RECOVERY.md](https://github.com/MRziyi/Constellation/blob/main/docs/glass/PAIRING-AND-AUTH-RECOVERY.md) · [P1.8-MEMORY-ENERGY-PROFILE.md](https://github.com/MRziyi/Constellation/blob/main/docs/glass/P1.8-MEMORY-ENERGY-PROFILE.md)

## Known limitations

- **One device.** The `glass` flavor targets this hardware's specific quirks — its channel mask, its key broadcasts, its AppOps camera behaviour. Other glasses will need real work.
- **Needs the server.** Without a reachable Cortex the app is a pairing screen.
- **UI strings are English only.** Internationalisation is not done, and non-English locale resources are stripped from the build.
- **Not on any app store**, and not built to be — this is sideloaded onto one pair of glasses.

## Related

[Constellation](https://github.com/MRziyi/Constellation) (design and architecture) ·
[Constellation-Server](https://github.com/MRziyi/Constellation-Server) (the Mac runtime)

## License

[Apache License 2.0](LICENSE).
