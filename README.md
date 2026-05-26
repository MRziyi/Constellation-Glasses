# Constellation Glass · 镜片

Glass-side client for the [Constellation](https://github.com/MRziyi/Constellation) personal-AI system.

**Target hardware**: Rokid R08 series (JBD4020 monochrome-green right-eye micro-LED, YodaOS-Sprite on Android 12).

**Architecture** — see the canonical design doc and visual mockup in the docs repo:
- [`GLASS-CLIENT-DESIGN.md`](https://github.com/MRziyi/Constellation/blob/main/GLASS-CLIENT-DESIGN.md)
- [`Doc/ui-mockup.html`](https://github.com/MRziyi/Constellation/blob/main/Doc/ui-mockup.html)

## What this is

A thin Android client that:
1. Captures voice via Rokid CXR-L `startAudioStream` (16 kHz PCM)
2. Streams audio over **TLS WSS** to `edge.example.com/ws/glass` (the existing Console relay)
3. Renders Cortex's HUD frames (state/card/insight) through CXR-L `openCustomView` (JSON layout)
4. Routes decision-voice commands via Rokid **InstructSdk** offline keyword matching
5. Bridges to Halo Ring's gesture plugin protocol when paired

**99% of the work happens on the Mac mini.** Glass is intentionally dumb.

## Repos

| Repo | Contents |
|---|---|
| [MRziyi/Constellation](https://github.com/MRziyi/Constellation) | Design docs |
| [MRziyi/Constellation-Server](https://github.com/MRziyi/Constellation-Server) | Cortex + Tool Agent (runs on Mac mini) |
| [MRziyi/Constellation-Console](https://github.com/MRziyi/Constellation-Console) | Web console (Edge + SPA) |
| **MRziyi/Constellation-Glass** (this) | Android client for Rokid Glasses |

## Build target

- **minSdk** = 28 (CXR-L floor)
- **targetSdk** = 32 (Android 12L — R08 ships on API 32)
- Kotlin · Gradle KTS · no Compose (CustomView JSON is the render surface)

## Build

```bash
./gradlew :app:assembleDebug
adb -s <glass-serial> install -r app/build/outputs/apk/debug/app-debug.apk
```

## Phase status

See [TODO.md](TODO.md) — currently in Phase 3b.1 (skeleton + WSS).
