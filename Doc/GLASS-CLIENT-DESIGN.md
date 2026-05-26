# Glass Client — Design v2 (Phase 3b)

**Status**: design **APPROVED** (Zack 2026-05-26) — ready to implement
**Target hardware**: Rokid Glasses (R08-series; JBD4020 monochrome-green micro-LED right-eye display; YodaOS-Sprite on Android 12 / Qualcomm)
**Companion devices**: Halo Ring (**optional** — adds gesture input when paired; the system works voice-only without it)
**Companion server**: existing `wss://edge.example.com/ws/glass` on Linux Edge → Cortex on Mac mini (Tailscale-routed). Glasses connect to the **public TLS endpoint** (no Tailscale on the glasses themselves in v1 — same path the web Console uses).
**Phase plan**: 3b.1 Skeleton+WSS · 3b.2 State+HUD · 3b.3 Voice+InstructSdk · 3b.4 STT pipeline · 3b.5 Device profiling

> Sources read for this design (cite for the next agent):
> - `R08-dev/research/rokid-docs/cxr-l/api-reference.md` — CXR-L (glasses-native) SDK
> - `R08-dev/research/rokid-docs/cxr-l/decompiled/` + `refs/sdks/rokid/CXR-L SDK/cxrlsample101/` — sample app + JSON schema
> - `Rokid/glass-docs` (official): `2-sdk/3-voice-sdk/InstructSdk/InstructSdk.md` — offline voice commands
> - `Rokid/RokidVoiceAISDK` (official): the cloud `VoiceAI` SDK (we considered, didn't pick — see §2.4)
> - `Rokid/speech-python-demo` — Rokid cloud ASR protocol (`wss://apigwws.open.rokid.com/api`, protobuf, MD5-signed auth)
> - Local ASR benchmark (`/tmp/asr-bench/results.json`) — whisper.cpp local vs OpenAI Whisper API vs Rokid-cloud-latency
> - `Constellation/halo-ring-plugin-protocol.md` — Halo Ring profile-push spec (already deployed)

---

## 1. Non-negotiables (locked by Zack 2026-05-26)

1. **Glasses are a thin input/output surface.** Real work runs on the Mac. App job: capture voice, render HUD, forward decisions.
2. **Voice-driven entry.** On wake, the user either makes a fresh ask, OR says "continue / 接着 / 上次那个… ". Session-continuation logic lives **in the existing cortex classifier**, not on the glasses.
3. **HUD is non-stacked, update-in-place.** The console stacks ActivityPill rows; the glass HUD has one content area that is **rewritten** each tick. Vertical space is precious.
4. **HUD region carves around the ring's HUD.** Constellation takes the upper band of the right-eye canvas; the lower-right is reserved for the Halo Ring pip (when paired).
5. **Card-receipt opens the mic automatically.** Decision can be made by voice ("好/同意/改/停/取消") or — if Halo Ring is paired — by ring gesture.
6. **Long cards never truncate.** Body scrolls (6-line view-port) via voice "下一段 / 上一段" OR ring SWIPE_UP/DOWN.
7. **Strict energy efficiency.** App idles near-zero. No polling. Display + mic OFF in IDLE state. Single persistent WSS over public TLS to `edge.example.com` (no Tailscale on glass in v1).
8. **Markdown is rendered as actual bold / italic / code, never as raw `**` characters.** Parsing happens server-side.
9. **Insight Engine is first-class on glasses** (per Zack — "最能让人觉得 wow"). Proactive HUD pushes accepted in IDLE; auto-close after 8 s.
10. **No card stacks.** HUD shows one thing at a time. New content replaces, never queues.
11. **No disconnect button.** WSS drop = error state. HUD shows red "offline · reconnecting" until link restored.
12. **Halo Ring is optional.** Voice ("好/停/改") is the primary decision path; ring gestures are an additional convenience when paired.

---

## 2. SDK constraints (verified via 3-source cross-check)

### 2.1 CustomView is the only HUD path

CXR-L (`com.rokid.cxr.link.CXRLink`) — third-party app's only sanctioned render API:

```kotlin
cxrLink.openCustomView(jsonString)       // open HUD overlay
cxrLink.updateCustomView(jsonString)     // mutate in place (cheap diff)
cxrLink.closeCustomView()                // close (HUD dark)
cxrLink.startAudioStream(codecType = 1)  // → IAudioStreamCbk.onAudioReceived(byte[], 16000, 1)
cxrLink.stopAudioStream()
cxrLink.takePhoto(...)                   // not used in v1
```

JSON schema is a recursive layout tree: `LinearLayout / RelativeLayout / TextView / ImageView / LottieAnimation`. **Colours auto-downsample to green channel** — UI is monochrome green. `updateCustomView` diffs the tree and mutates only changed nodes (= our "update-in-place" channel for §1.3).

### 2.2 No third-party temple-arm gesture access — confirmed across 3 sources

- Rokid system Sprite assistant consumes touchpad events internally (RokidTouchManager, not exported)
- CXR-L `ICustomViewCbk` has no `onTouch()` / `onGesture()` callbacks
- No system broadcast for gestures
- AccessibilityService can't reach hardware-input layer
- `/dev/rt600_spidev` is SELinux-locked to system_device

**Resolution**: gestures come from either (a) Halo Ring via existing plugin protocol, or (b) **voice commands via InstructSdk** (§2.3). Both equivalent for our purposes.

### 2.3 InstructSdk gives us offline voice commands (no key, no cloud)

`com.rokid.ai.glass:instructsdk:1.1.4` (Jcenter).

- Register Service / Activity in AndroidManifest with `com.rokid.ai.skill.local.*` meta-data
- Add commands at runtime: `VoiceInstruction.getInstance().addGlobalInstruct(InstructEntity().setName("好").setPinYin("hao").setCallback(...))`
- System Sprite assistant listens, fires the callback **directly** when matched, OFFLINE, sub-100ms
- Requires registering a Skill ID at `developer.rokid.com` (Zack has credentials at `.env`)

We use this for the **fixed decision vocabulary** in CARD state:
- "好 / 同意 / 确认 / yes / approve" → Approve
- "停 / 算了 / 取消 / kill / cancel" → Kill
- "改 / modify / 改一下" → Modify (opens full STT for follow-up)
- "下一段 / 上一段 / next / previous" → scroll
- "继续 / continue" → resume long-running task

### 2.4 STT for open-ended speech: Mac/whisper.cpp local (benchmarked)

Three options measured (2026-05-26):

| Backend | Mean latency | Mean error | Privacy | Network |
|---|---|---|---|---|
| **Mac/whisper.cpp `small` via edge-relay** ← chosen | **~1.3s** (glass → Linux ~50-100 ms → Mac via Tailscale 3 ms → whisper 1.2 s) | ~10% (Chinese-fixed via prompt) | TLS-encrypted; audio bytes traverse the existing public WSS the Console already uses | Tailscale Linux→Mac RTT 3 ms; glass→Linux depends on glass network |
| OpenAI Whisper API direct from glasses | ~3s | 6.4% | utterances → OpenAI | TLS handshake ~830ms |
| Rokid cloud (`wss://apigwws.open.rokid.com/api`) | ~1.5-3s + **20% packet loss** | unknown | utterances → Rokid | Mac→cloud ping 312ms |

**Decision**: Mac/whisper.cpp `small` on the Mac mini. M-series GPU + Metal = fast. No fallback to OpenAI (per Zack); WSS drop = error state.

Server-side details:
- Cortex spawns `whisper-cli -m ggml-small.bin -l zh -nt -np --prompt "请用简体中文输出" -f stream.wav` (Chinese)
  OR with `-l en` (English) — language auto-detected by a first-frame heuristic OR fixed per-session
- Transcript injected into the existing classifier path as `user_invoke.text` or `user_decision.feedback_text`

### 2.5 Lifecycle: ForegroundService + CustomView mode

- App has NO visible Activity. Only a ForegroundService with a low-priority persistent notification (mandated by Android).
- `ENTRY_TYPE_CUSTOM_VIEW` mode: HUD rendered through CXR-L's bound system service, not by us.
- Survives Android 12 Doze + background-execution limits.

### 2.6 CustomView ownership semantics (important)

- The right-eye **JBD4020 panel is transparent AR micro-LED**. When our CustomView is open, we own all the panel's pixels — but unlit pixels = transparent = the world shows through. There is no "occluded background" concept; we simply choose which pixels to illuminate green.
- **Only ONE app can hold an open CustomView at a time** (Rokid system-level invariant). When the user invokes Sprite assistant ("Hi Rokid") or another CustomView app, the system **closes ours** and we get `ICustomViewCbk.onCustomViewClosed()`. We transition to IDLE; the next wake gesture (voice or ring) re-opens.
- **Halo Ring's HUD pip is NOT a CustomView**. Halo Ring renders its pip via Android's standard `WindowManager.TYPE_APPLICATION_OVERLAY` (a transparent system overlay), so it draws **on top of** our CustomView, not as content inside it. Coexistence is automatic; we just leave the lower-right region of our JSON layout unlit so the pip has a dark backdrop.

---

## 3. Architecture

```
┌───────────────── Rokid Glasses (R08, YodaOS-Sprite / Android 12) ─────────────────┐
│                                                                                   │
│  ConstellationService (ForegroundService)                                         │
│  ├── CXRLink (single instance — bind to com.rokid.sprite.aiapp service)           │
│  ├── WSS client (OkHttp WebSocket → wss://edge.example.com/ws/glass)           │
│  ├── State machine (§5) — IDLE / LISTENING / THINKING / CARD / INSIGHT / OFFLINE  │
│  └── Receivers + Provider                                                         │
│                                                                                   │
│  Modules:                                                                         │
│  ─ HudRenderer            cxrLink.open/update/close ; builds JSON layouts         │
│  ─ AudioPipeline          startAudioStream(1) → 16 kHz mono PCM → WSS chunker     │
│  ─ VoiceCommands          InstructSdk registration + callbacks for                │
│                            好/停/改/取消/下一段/上一段                            │
│  ─ HaloRingBridge         optional — HaloActionsProvider + TriggerReceiver +      │
│                            profile-push helpers (see §7)                          │
│  ─ MarkdownRunsRenderer   server gives [{text, style}]; we map to nested TextViews│
│  ─ WssClient              auto-reconnect, ping every 15 min                       │
│                                                                                   │
└───────────────────────────────────────────────────────────────────────────────────┘
                                       │
                          Wi-Fi · public TLS WSS (no VPN on glass)
                                       ▼
              ┌─────────────────────────────────────────────────┐
              │  Linux Edge @ edge.example.com                │
              │   cookie-authed WSS proxy → <mac-host> (Mac) │
              └─────────────────────────────────────────────────┘
                                       │
                                       ▼
              ┌─────────────────────────────────────────────────┐
              │  Cortex (Mac mini @ <mac-host>)              │
              │   ─ handle_glass() — existing                   │
              │   ─ + NEW: audio_chunk / audio_end handlers     │
              │   ─ + NEW: WhisperPipeline (whisper-cli wrapper)│
              │   ─ + NEW: classify_intent gets "continue?" bit │
              │   ─ + NEW: markdown→runs parser at card emit    │
              └─────────────────────────────────────────────────┘
```

---

## 4. Server-side additions (Cortex)

**Additive only.** The existing WSS event schema (`user_invoke / user_decision / progress / preview_action / hud_show`) is untouched. The Glass client also speaks these.

New event kinds (Glass → Cortex):

| `kind` | payload | when |
|---|---|---|
| `audio_chunk` | `{stream_id, seq, b64_pcm, sample_rate, channels}` | every ~250 ms during LISTENING |
| `audio_end` | `{stream_id, duration_ms, lang_hint?}` | when user finishes (voice cmd "完了" / InstructSdk / silence) |
| `decision_voice` | `{cmd_id, command}` where command ∈ approve/modify/kill/scroll_up/scroll_down/continue | when InstructSdk fires |

New command kinds (Cortex → Glass):

| `kind` | payload | when |
|---|---|---|
| `mic_open` | `{stream_id, lang_hint?}` | server tells glass to open mic (e.g. after CARD) |
| `mic_close` | `{stream_id}` | server explicit close (after STT done) |
| `hud_state` | `{stage, icon, detail_runs:[{text,style}]}` | THINKING state — single-line replace-in-place |
| `card` | `{title_runs, body_runs, scroll_total_lines, options:["approve","modify","kill"]}` | preview_action equivalent, optimized for glass |
| `insight` | `{title_runs, body_runs, kind, ttl_ms=8000}` | hud_show equivalent — IDLE auto-fade |
| `offline_hint` | (no payload) | sent before disconnect when planned |

`*_runs` is a styled-runs array (§4.2). Old client (Console) ignores unknown kinds; Glass declares `accept: ["hud_state","card","insight","mic_open","mic_close"]` in the connect handshake.

### 4.1 STT pipeline on Mac

```python
# cortex/cortex/whisper_pipeline.py (NEW)
class WhisperPipeline:
    def __init__(self, model="small", binary="/opt/homebrew/bin/whisper-cli"):
        self.model_path = f"~/Code/Projects/Constellation-Server/models/ggml-{model}.bin"

    async def transcribe(self, pcm_bytes, sample_rate=16000, lang="auto"):
        # Write to temp WAV, call whisper-cli, parse stdout
        with tempfile.NamedTemporaryFile(suffix=".wav") as f:
            write_pcm_wav(f, pcm_bytes, sample_rate)
            args = [self.binary, "-m", self.model_path, "-nt", "-np", "-f", f.name]
            if lang == "zh":
                args += ["-l", "zh", "--prompt", "请用简体中文输出"]
            elif lang == "en":
                args += ["-l", "en"]
            # else: auto-detect
            result = await asyncio.create_subprocess_exec(*args, stdout=PIPE)
            stdout, _ = await result.communicate()
            return parse_transcript(stdout.decode())
```

Stream assembly: `audio_chunk` frames keyed by `stream_id` append to a `bytearray`; `audio_end` triggers WhisperPipeline.transcribe; the resulting text becomes either:
- a `user_invoke` event (if from IDLE / fresh ask)
- a `user_decision.feedback_text` for Modify (if from CARD)

### 4.2 Styled-runs markdown parser (server-side)

Markdown source → array of styled-runs. Example:

```
"**Reminder** at 9pm tonight"
```

becomes

```json
"body_runs": [
  { "text": "Reminder", "style": "bold" },
  { "text": " at 9pm tonight", "style": "normal" }
]
```

Supported styles: `normal`, `bold`, `italic`, `bold_italic`, `code`, `dim`. Heuristic mapping:
- `**x**` / `__x__` → bold
- `*x*` / `_x_` → italic
- `` `x` `` → code (renders as monospace if available, else just dim)
- bullet `- x` / `* x` → prefix `• ` + normal

Mistune or markdown-it-py walked into an AST → flatten into runs. Drop tables / images (HUD can't render).

### 4.3 Continue-session classifier extension

`cortex.classifier.classify_intent()` gets a third output bit:

```python
{
  "complex": bool,
  "continue_session": str | None,   # ← NEW: session_id to attach to, OR null
  "why": str,
}
```

Prompt addition (~20 words):
> If the user's words clearly reference a recent conversation ("接着之前 / 上次那个 / continue the email draft"), include `continue_session` with the session_id you'd resume; else null. You see a list of the user's 3 most recent active sessions with their titles.

Server passes the top-3 active session titles + ids into the classifier prompt. On a hit, `_handle_user_invoke` reuses that session_id instead of minting fresh.

---

## 5. State machine (Glass side)

```
                ┌─────────────────────────┐
                │       IDLE              │  HUD off, mic off, WSS alive
                │  InstructSdk:           │  ← entry: closeCustomView()
                │   voice_wake registered │
                └─────────┬───────────────┘
       voice_wake / ring  │
       voice gesture      ▼
                ┌─────────────────────────┐
                │      LISTENING          │  HUD: "🎤 listening…" (one row)
                │   mic OPEN; PCM → WSS   │  ← entry: openCustomView(listen.json)
                │   InstructSdk:          │           + startAudioStream(1)
                │     "完了/done" → end   │
                └─────────┬───────────────┘
        "done" / silence  │
        VAD-end           ▼
                ┌─────────────────────────┐
                │      THINKING           │  HUD: live single-line (replace
                │   mic OFF              │   every <=4 Hz via updateCustomView)
                │   WSS feeds hud_state   │
                │   InstructSdk:          │
                │     "停/kill" → kill    │
                └─────────┬───────────────┘
       `card` arrives     │
                          ▼
                ┌─────────────────────────┐
                │       CARD              │  HUD: title + scrollable body
                │   mic AUTO-OPEN         │  ← entry: openCustomView(card.json)
                │   InstructSdk:          │           + startAudioStream(1)
                │     好→approve          │           + register card cmds
                │     停→kill             │
                │     改→Modify+keep mic  │
                │     上/下→scroll        │
                │   PCM during "改" → WSS │
                └─────────┬───────────────┘
       any decision       │
                          ▼
                       back to IDLE
                       (or THINKING on Modify)

  Cross-cutting:
   ─ INSIGHT  : transient sub-state from IDLE; renders an `insight` HUD with
                ttl_ms auto-close; gesture/voice in INSIGHT → goto LISTENING
                with prior_insight as classifier context.
   ─ OFFLINE  : entered whenever WSS dropped >5s. Renders red "offline ·
                reconnecting…" replacing the current HUD content. Auto-exits
                to prior state when WSS reconnects.
```

State invariants (energy-critical):
- CustomView OPEN only in LISTENING / THINKING / CARD / INSIGHT / OFFLINE. **Closed in IDLE.**
- Mic OPEN only in LISTENING and CARD-auto-open phase (capped 30 s if unused). **Closed otherwise.**
- InstructSdk callback registration: lifecycle-scoped — only the relevant commands for the current state are active.
- Halo Ring profile push: only pushed when ring paired AND state requires it.

---

## 6. HUD layout (640×480 monochrome green)

(Unchanged from v1 §6 — see `Doc/glass-ui-mockup.html` for the visual mock.)

Key constraints:
- **Top half = us** (Constellation HUD), **bottom-right = Halo Ring pip** (if paired)
- Single content area, never stacked
- Update rate ≤ 4 Hz in THINKING (thermal-fps protection)
- Body styled via runs (§4.2) → multiple TextViews stacked in a vertical LinearLayout, each with its own `textStyle` (bold/italic/normal). No raw `*` characters ever shown.

New in v2:
- **OFFLINE state** renders a red-tinted (greener-than-bright per JBD4020) banner "● offline · reconnecting…"
- **INSIGHT state** renders title + 2-line body, plus a "tap to engage" footnote. Auto-close timer shown as a thin progress bar at bottom.

---

## 7. Halo Ring integration (optional)

If `com.halo.ring` is installed AND paired:
- Push a state-specific gesture profile on state entry; pop on exit (existing protocol in `halo-ring-plugin-protocol.md`)
- Profiles per state:
  - **IDLE**: SWIPE_UP_HOLD = wake (alt to voice_wake)
  - **LISTENING**: TAP = "done", DOUBLE_TAP = cancel
  - **THINKING**: DOUBLE_TAP = kill
  - **CARD**: TAP = approve, LONG_PRESS = modify, DOUBLE_TAP = kill, SWIPE_UP/DOWN = scroll
  - **INSIGHT**: TAP = engage (transition to LISTENING with prior_insight in context)

If `com.halo.ring` is NOT installed:
- Skip profile push silently; voice (InstructSdk) IS the input
- All UI affordances reference voice commands instead of ring gestures
- HUD shows "🎙 tap-and-speak: 好/停/改" instead of "tap to approve / long-press to modify"

---

## 8. Power budget

| Component | When ON | Bound |
|---|---|---|
| Wi-Fi radio | Always (1 persistent WSS) | Tailscale keepalive 15 min |
| CustomView (HUD) | LISTENING / THINKING / CARD / INSIGHT / OFFLINE only | Closed in IDLE |
| Mic capture | LISTENING + CARD-auto-open (30s cap) only | Always paired open/close |
| HUD update rate | ≤ 4 Hz in THINKING | Below thermal-fps downshift threshold |
| InstructSdk listener | Always registered (system handles wake) | Lightweight broadcast receiver, no polling |
| BroadcastReceivers (Halo Ring + boot) | Always registered | Stateless, dispatched-on-demand |
| Foreground notification | Always (Android mandate) | LOW priority, no sound, minimal icon |
| Background polling | NEVER | All events arrive via WSS push |

---

## 9. Repo + module layout (`Constellation-Glass`)

```
Constellation-Glass/
├── README.md
├── build.gradle.kts                        (root)
├── settings.gradle.kts
├── gradle.properties
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── kotlin/com/constellation/glass/
│       │   ├── ConstellationService.kt        # ForegroundService root
│       │   ├── BootReceiver.kt                # auto-start on device boot
│       │   ├── state/
│       │   │   ├── State.kt                   # enum + base class
│       │   │   ├── StateMachine.kt
│       │   │   └── transitions/
│       │   │       ├── ToListening.kt
│       │   │       ├── ToThinking.kt
│       │   │       ├── ToCard.kt
│       │   │       ├── ToInsight.kt
│       │   │       └── ToOffline.kt
│       │   ├── hud/
│       │   │   ├── HudRenderer.kt             # cxrLink.* wrapper
│       │   │   ├── HudLayouts.kt              # JSON builders per state
│       │   │   ├── StyledRunsRenderer.kt      # [{text,style}] → TextView tree
│       │   │   └── ScrollWindow.kt            # body 6-line view-port
│       │   ├── audio/
│       │   │   ├── AudioPipeline.kt           # startAudioStream → chunked PCM
│       │   │   └── ChunkEncoder.kt            # PCM → base64 → audio_chunk frames
│       │   ├── voice/
│       │   │   ├── InstructHost.kt            # InstructSdk registration / lifecycle
│       │   │   └── CommandRegistry.kt         # state-scoped command sets
│       │   ├── halo/
│       │   │   ├── HaloActionsProvider.kt     # ContentProvider for ring discovery
│       │   │   ├── HaloTriggerReceiver.kt     # broadcast receiver for ring triggers
│       │   │   ├── HaloProfilePush.kt         # state→profile mapping
│       │   │   └── HaloPresenceCheck.kt       # detect "is ring installed?"
│       │   ├── wss/
│       │   │   ├── WssClient.kt               # OkHttp WebSocket
│       │   │   ├── Frames.kt                  # event/command serde
│       │   │   ├── HandshakeCapabilities.kt   # accept: [...]
│       │   │   └── ReconnectPolicy.kt
│       │   └── util/
│       │       ├── Logging.kt
│       │       ├── BatteryGuard.kt            # rate-limit + back-off helpers
│       │       └── Time.kt
│       └── res/
│           ├── values/strings.xml
│           ├── drawable/ic_notification.xml
│           └── ...
├── Doc/
│   ├── GLASS-CLIENT-DESIGN.md (this, copy)
│   ├── glass-ui-mockup.html
│   └── state-machine.svg
└── .github/workflows/build.yml
```

Build target: **minSdk = 28 (Android 9 — CXR-L floor), targetSdk = 32 (Android 12L)**.

---

## 10. Phase plan

### Phase 3b.1 — Skeleton + WSS (3-4 days)
- Android Studio project, gradle setup, CXR-L AAR + InstructSdk AAR
- AndroidManifest with FGS, halo plugin meta-data, instructsdk meta-data
- ConstellationService skeleton (start, notification, WSS connect/reconnect)
- WssClient with capability handshake
- Hard-coded "hello world" CustomView open/close on a debug intent
- Test against current Cortex (which already speaks the existing event schema)

### Phase 3b.2 — State machine + HUD (2-3 days)
- State + StateMachine + transition functions
- HudRenderer + HudLayouts.kt (Idle / Listening / Thinking / Card / Insight / Offline)
- StyledRunsRenderer for body
- Wire server `hud_state` + `card` + `insight` events to state transitions
- Test on emulator with mock CXRLink

### Phase 3b.3 — Voice (InstructSdk) + Halo Ring (2-3 days)
- Register skill at developer.rokid.com using the credentials in `.env`
- InstructHost + CommandRegistry (per-state command sets)
- HaloActionsProvider + HaloTriggerReceiver
- Halo profile push/pop on state transitions (if ring detected)
- End-to-end voice decision: "好" → user_decision approve → server receives

### Phase 3b.4 — STT pipeline (3-4 days)
**Server side** (Cortex):
- New event kinds in `cortex/cortex/schema.py`: audio_chunk, audio_end, decision_voice, mic_open, mic_close, hud_state, card, insight
- `cortex/cortex/whisper_pipeline.py`: whisper-cli wrapper
- `cortex/cortex/audio_buffer.py`: stream assembly
- `cortex/cortex/markdown_runs.py`: server-side markdown → styled runs parser
- `_handle_user_invoke` extension: continue-session classifier hits resume an existing session_id
- New event handlers: handle_audio_chunk, handle_audio_end, handle_decision_voice
- New emitters: emit_mic_open, emit_card (styled-runs flavored)

**Glass side**:
- AudioPipeline: startAudioStream → 250 ms chunks → base64 → WSS frames
- Voice "改" command in CARD state opens mic + transitions through LISTENING with `is_modify=true` flag

### Phase 3b.5 — On-device deploy + power profiling (2-3 days)
- Side-load APK to physical R08
- Drive a full session manually
- Measure idle drain over 1 hour, active drain over a 5-min interaction
- Tune update rate / Wi-Fi keepalive / buffer sizes

**Total: ~2-3 weeks for a working build.**

---

## 11. Risks + unknowns

| Risk | Mitigation |
|---|---|
| InstructSdk requires skill registration at developer.rokid.com — IDs propagation latency | Register early in Phase 3b.3; cache skill ID in code |
| Whisper-cli model loading is slow on first call (~3 s) | Pre-warm: cortex spawns one dummy transcription on boot |
| Public-internet round-trip dominates audio-stream latency | Persistent WSS keeps TLS warm; 16 kHz PCM at ~256 kbps is well within any internet; +50-100 ms is acceptable vs whisper 1.2 s |
| Network reachability of Cortex from glasses Wi-Fi | OFFLINE state handles graceful degradation; exponential back-off; if cellular fails, we just sit in OFFLINE until Wi-Fi returns |
| Sprite assistant preempts our CustomView (wake word, voice menu) | `onCustomViewClosed()` handler transitions to IDLE; next wake gesture reopens; not an error |
| CustomView JSON schema undocumented edge cases | Cross-check against `cxrlsample101`; emit diagnostic logs for SDK errors |
| Halo Ring profile-push protocol — confirm with Halo Ring agent before 3b.3 | Fall back to voice-only if not ready |
| 16 kHz PCM bandwidth — ~256 kbps over public WSS | Trivial for any modern internet; if glasses' Wi-Fi sleeps mid-stream, hold a partial wakelock during LISTENING |

### 11.1 Deferred to Phase 3b.5+ (post-MVP)

- **Tailscale on glass** (direct LAN path to Mac, ~3 ms RTT) — depends on whether YodaOS-Sprite permits the VPN service to run; battery overhead unknown. Smart-endpoint switching (Tailscale when reachable, edge-relay otherwise) would be the long-term win.
- **Voice-overlap with system Sprite assistant** — if the user uses both "Hi Rokid" (system) and "Hi Cortex" (us) in rapid succession, what's the UX? Probably fine since each wake closes the other's CustomView, but worth profiling on device.

---

## 12. What we are NOT building in v1

- On-device STT (we use Mac/whisper.cpp; Rokid VoiceAI cloud is dropped — see §2.4)
- Card-stack queueing (only one HUD content at a time, per §1.10)
- Multi-checkpoint visualization (checkpoint cards arrive serially as normal new cards)
- TTS playback on glasses (Cortex emits text only; glasses render visually)
- Camera capture (CXR-L supports it but no use case yet)
- Custom wake word (use system Sprite assistant's default wake — Zack already has it configured)

---

**Implementation starts now (Phase 3b.1).** All resolved questions are locked above. Tracking progress in `~/Code/Projects/Constellation-Glass/` once the repo is created.
