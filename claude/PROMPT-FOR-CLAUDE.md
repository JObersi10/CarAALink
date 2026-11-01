# Continuation prompt for Claude — Android Auto receiver in CarAALink

Copy everything below the line into a fresh Claude session working in
`/Users/jobersi10/Documents/Claude/Projects/carbitlink-aa`.

---

## Your task

Finish the **Android Auto receiver** in the CarAALink app. The CarbitLink
mirroring half already works on the car and must not regress — see
`claude/handoff.md` and `claude/roadmap.md`. The AA half is new and currently
stops at one specific, well-characterised point (below).

Read these first, in this order:

1. `claude/aa-receiver-notes.md` — the AA workstream in full: confirmed
   protocol facts with citations, live results, dead ends, next steps.
2. `claude/handoff.md` — "LATEST — Android Auto receiver" at the top.
3. `claude/claude.md` — the project's hard rule: **no fabricated protocol
   details.** Every claim must trace to aasdk source, the reference app, or a
   capture. If it isn't confirmed, write UNKNOWN, don't guess.

## Product goal (owner's words)

The car shows CarAALink's own home screen. Tapping the **"Android Auto"** tile
should open a real Android Auto session on the car screen at the car's
resolution. Phone-screen mirroring already works via the "Phone screen" tile.

## What already works (verified on the owner's S25 Ultra, AA 18.0.664244)

All of this is implemented and reproducible. Do not re-derive it.

| Rung | State | Evidence |
|---|---|---|
| Version exchange | **works** | TX `00 03 00 06 00 01 00 01 00 06` → RX `00 02 00 01 00 07 00 00` = VERSION_RESPONSE 1.7, status 0, ~100 ms |
| TLS inside AA frames | **works** | `ENCAPSULATED_SSL` (control msg id 3); TLSv1.2 only; leaf-only client cert; flights 138 B / 1191 B / 51 B, identical to the reference app |
| Mutual PKI | **works** | phone's `CarService` cert chains to the same Google Automotive Link CA (`49:E5:2E:FC:…`) that we ship in `assets/aa/headunit.crt` |
| AUTH_COMPLETE | **works — this was the unlock** | PLAIN, msg id 4, `AuthResponse{status=0}`, sent immediately after the handshake (mirrors openauto's `AndroidAutoEntity::onHandshake`) |
| Inbound service discovery | **works** | phone then sends `SERVICE_DISCOVERY_REQUEST` with `label_text='Android'`, `device_name='samsung SM-S938B'`, `phone_info` (38 B) |

## Where it stops (the actual task)

The phone receives our `SERVICE_DISCOVERY_RESPONSE`, then closes the socket
**~0.82 s later**, consistently:

```
TX SERVICE_DISCOVERY_RESPONSE 223B
failed: EOFException
```

Gearhead logs `GEARHEAD_ENDPOINTS_FAILED` right before the teardown. Our
protobuf has been decoded field-by-field and is structurally valid (correct
field numbers, wire types and nesting), so this is **not** a malformed message —
the phone is rejecting the *content*: something it needs in order to start its
endpoints is absent or wrong.

**Next step: stop guessing, get the reference.** Either
- diff our response against openauto's `fillFeatures()` output byte-for-byte
  (still the highest-value move — a second known-good head unit, or a capture
  of one, gives ground truth), or
- iteratively bisect: the services we currently emit, the video/audio config
  values, `driver_position`, `display_id`, `ui_config`.

## Code map — everything is in `app/src/main/kotlin/dev/caraalink/aa/`

| File | Role |
|---|---|
| `AaFraming.kt` | Frame/message codec. Header = 2 bytes (`channelId`, `ENCRYPTED|CONTROL|frameType`); size = `uint16` BE, or `uint16`+`uint32` for FIRST frames. Message id = `uint16` BE. |
| `AaProtobuf.kt` | Minimal protobuf reader/writer (varint + length-delimited). No deps. |
| `AaIdentity.kt` | Loads head-unit cert + key; exposes `sslContext` (full chain) and `sslContextLeafOnly`. |
| `AaTlsChannel.kt` | Established channel: frame I/O, message reassembly, encrypt/decrypt via `SSLEngine`. |
| `AaSessionProbe.kt` | The working end-to-end probe: version → TLS → AUTH_COMPLETE → receive loop. **This is where you work.** |
| `AaServiceDiscovery.kt` | Builds our `SERVICE_DISCOVERY_RESPONSE`. **This is what the phone rejects.** |
| `AaVersionProbe.kt` | Version exchange in isolation. |
| `AaTlsReceiver.kt` | Dead end (raw TLS on the socket). Kept for reference only — do not revive. |

Assets: `app/src/main/assets/aa/{headunit.crt,headunit.key,PROVENANCE.md}`.
Tests: `app/src/test/kotlin/dev/caraalink/aa/` — 16 tests, all green. They pin
the codec bytes and the discovery-response structure; keep them green.

Wire-up: Car home "Android Auto" tile → `AaSessionProbe`; Developer Tools
buttons; adb triggers below. Logs go to `aa-session-*.log`; Developer Tools →
"Export latest log" writes one merged `all-logs-*.log`.

## Protocol facts you must not re-derive (all cited in the notes)

- The AA socket **always** carries AA frames. TLS is payload, never the
  transport — the handshake is `MESSAGE_ENCAPSULATED_SSL` (id 3) messages.
- Frame header and size stay **plaintext** on encrypted frames; only the
  payload is TLS-wrapped.
- The head unit is the TLS **client** (`TLS_client_method()` +
  `SSL_set_connect_state()`); the phone is the server and does not verify us at
  TLS level (`SSL_VERIFY_NONE`).
- The **phone** sends `SERVICE_DISCOVERY_REQUEST`; the **head unit** answers
  with `SERVICE_DISCOVERY_RESPONSE` (ENCRYPTED, msg id 8).
- `AUTH_COMPLETE` is head-unit → phone only, and is PLAIN.
- Control ids: 1 VERSION_REQUEST, 2 VERSION_RESPONSE, 3 ENCAPSULATED_SSL,
  4 AUTH_COMPLETE, 5/6 SERVICE_DISCOVERY_REQUEST/RESPONSE, 7/8 CHANNEL_OPEN, 11/12 PING.
- Reference sources: `opencardev/aasdk` branch `newdev` (`src/Messenger/*`,
  `src/Channel/Control/ControlServiceChannel.cpp`, `protobuf/aap_protobuf/**`)
  and `opencardev/openauto` (`src/autoapp/Service/**`).

## Resolution — already settled, don't reopen it

AA has **no 1920x720 mode**. Its `VideoCodecResolutionType` is
800x480 / 1280x720 / 1920x1080 / 2560x1440 / 3840x2160 (+ portrait), and it
never renders at the panel size: openauto just does
`videoWidget_->setGeometry(screenGeometry)` and scales. So we request
**1920x1080 @30 fps H.264 Baseline (16:9)** and the CarbitLink side fits it into
the car's 1920x720. `width_margin`/`height_margin` exist but their exact
semantics are unconfirmed — leave both at 0 unless you find evidence.

## Testing on hardware — read this or you will waste an hour

The owner's phone connects over **wireless debugging**, and the port changes on
every reconnect. Always ask for a fresh `IP:port`, then:

```bash
adb connect <ip>:<port>
./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk

# debug-only headless triggers
adb shell am start -n dev.caraalink/.MainActivity --es aa_probe session     # full probe
adb shell am start -n dev.caraalink/.MainActivity --es aa_probe stop
```

**The head unit server is fragile — this is the single biggest time sink.**
Gearhead binds `5277`, fills its accept backlog and never calls `accept()`: you
connect and get silence forever. Check with

```bash
adb shell "ss -ltn | grep 5277"
```

A non-zero `Recv-Q` means stale. The only fix is the UI toggle: Android Auto →
Settings → ⋮ → **Stop head unit server**, then ⋮ → **Start head unit server**.
(These can be driven over adb: launch
`com.google.android.projection.gearhead/.companion.settings.DefaultSettingsActivity`,
tap the overflow, tap the toggle — the item is 3rd in the menu either way.)

**One clean attempt after a fresh toggle beats any retry loop.** A failed
attempt puts the server in a deaf window for the next connection or two, and
`AaSessionProbe`'s built-in 3× retry actively walks into it.

Read results with:

```bash
adb shell "cat /storage/emulated/0/Android/data/dev.caraalink/files/logs/aa-session-*.log"
```

For the phone's side of a session, start a device-side capture *immediately
before* the test (adb-tethered captures die silently, and one already cost a
session):

```bash
adb shell "logcat -c"
adb shell "nohup logcat -v threadtime > /data/local/tmp/x.log 2>&1 &"
# ...run the test...
adb shell "grep -E 'DHUService|ConnLoggerV2|CAR\.' /data/local/tmp/x.log"
```

`GH.DHUService: Head unit connected` confirms the phone accepted us;
`GEARHEAD_ENDPOINTS_FAILED` is the event to dig into next.

## Constraints

- **No fabricated protocol details.** Quote aasdk / openauto / a capture, or
  mark it UNKNOWN. This rule is the whole point of the project.
- Don't touch the CarbitLink mirroring path — it works.
- The head-unit identity is public interop material (`opencardev/aasdk`'s
  `cert/headunit.crt|key`), not CarbitLink's crypto. Provenance and caveats are
  in `assets/aa/PROVENANCE.md`. Don't add CarbitLink's own keys or algorithms.
- Keep `./gradlew testDebugUnitTest` and `./gradlew assembleDebug` green.
- Work on a branch + PR, not directly on `main`.

## Definition of done for this task

Phone sends `SERVICE_DISCOVERY_REQUEST` → we send a response it accepts →
phone sends `CHANNEL_OPEN_REQUEST` for the video channel → we answer
`CHANNEL_OPEN_RESPONSE` → we request video focus and send the video config
(1920x1080) → H.264 frames arrive → they get handed to the CarbitLink video
path and appear on the car. Touch comes after that via the input channel.

Reaching `CHANNEL_OPEN_REQUEST` is the immediate milestone: it proves the
discovery response is accepted and tells us the phone's channel plan.
