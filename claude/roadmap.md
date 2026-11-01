# Roadmap / status

Last updated: 2026-10-31 (after first RE pass)

## Status at a glance

| Area | Status |
|---|---|
| USB role (host vs accessory) | **CONFIRMED** — phone is USB *accessory*, head unit is USB *host* |
| USB VID/PID / accessory ID | **CONFIRMED** — AOA accessory match, not VID/PID (manufacturer `Carbit`, model `CarbitDriverOversea`) |
| Handshake sequence | PARTIAL — standard AOA handshake + an RSA-signed auth step; exact byte sequence not extracted (native-only) |
| Packet/frame format | PARTIAL — confirmed a `Packet` abstraction multiplexing logical services; header/length/checksum/crypto layout unresolved |
| Crypto / obfuscation | PARTIAL — RSA auth/signature path confirmed to exist; no byte-level detail yet |
| Video transport | **CONFIRMED** — software H.264 (x264) via bundled FFmpeg, YUV in, Annex-B NAL out (MJPEG/PNG fallback also built in) |
| Touch/control wire format | PARTIAL — Java-level struct confirmed (`EPTouchEventData{type,x,y,slot}`, `EPBtnCode`); on-wire byte encoding unconfirmed |
| Audio handling | PARTIAL — PCM format enum confirmed at Java boundary; PCM-on-wire vs AAC-encode-before-wire unconfirmed |
| Native `.so` protocol logic | **CONFIRMED present and dominant** — `libEPSDK.so` (`CarbitEPSDK::AOATransport`/`Packet`/...) holds essentially all protocol logic; Java is a thin JNI pass-through |
| AA head-unit-server trigger flow | **CONFIRMED mechanism** (via PhoneAuto reference) — it's Google's own first-party AA sink app, head-unit-server mode gated behind `HeadUnitServerAccessibilityService`, not custom automation |
| AA capture (MediaProjection/VirtualDisplay) feasibility | UNKNOWN — no MediaProjection/VirtualDisplay usage found anywhere in PhoneAuto statically; capture mechanism still open |

Every row above is cited to a specific file/class/string in the pass notes
below, per `claude.md`'s rule against fabricating protocol details.

## RE pass 1 findings (2026-10-31)

Target: `CarbitLink-EasyConnection_6.5.1_APKPure.apk` (primary), `PhoneAuto-beta-2027-01-19.apk` (secondary, time-boxed).

1. **USB role — CONFIRMED.** Phone is USB *accessory*, head unit is USB
   *host* — the opposite of a naive "dongle replaces as device" guess.
   Evidence: `AndroidManifest.xml` lines 184–188 declare a
   `USB_ACCESSORY_ATTACHED` intent-filter + `@xml/accessory_filter`
   meta-data on `net.easyconn.carman.MainActivity`; decoded filter resource
   `res/S5h.xml` contains
   `<usb-accessory><manufacturer>Carbit</manufacturer><model>CarbitDriverOversea</model><version>...</version>`.
   Confirmed again in Java: `EPSDK.openAccessory()` calls
   `UsbManager.openAccessory()` and passes the resulting
   `ParcelFileDescriptor`'s raw fd into `native_bindAOATransport(fd)`.

2. **Handshake/negotiation — PARTIAL.** Transport binds via standard Android
   Open Accessory (AOA) protocol (native symbols `AOATransport::buildConnection`,
   `AOATransport::open`). There's an RSA-based auth/signature step:
   `libhud_protocol.so` exports `RSAUtils.getLicensePubKey` returning an
   embedded 1024-bit RSA pubkey (`gPubKey`); `libEPSDK.so` contains symbol
   `AuthRSA` and string `protocol="%ssignature"`. Exact byte sequence/ordering
   not extracted — compiled into native code, needs disassembly or capture.

3. **Packet/frame format — PARTIAL.** C++ `CarbitEPSDK::Packet` type is
   passed through `AOATransport::enqueue(shared_ptr<Packet>)` /
   `sendUp(shared_ptr<Packet>)` with dedicated `inputThread`/`outputThread`
   loops — confirms a framed-packet abstraction multiplexing distinct
   logical services. Header layout, length field, checksum, encryption are
   **not recoverable from strings/symbols alone**.

4. **Video transport — CONFIRMED.** `libJNICodec.so` statically bundles
   FFmpeg 4 + x264 (`--enable-encoder='h264,libx264' --enable-encoder=mjpeg
   --enable-encoder=aac --enable-encoder=png` build string present in
   strings dump). JNI surface: `JNICodec.initEncoder/encodeFrame/
   encodeYUVFrame/convertFormat(Neon/Full)/releaseEncoder`. Video is
   software-encoded to raw H.264 (Annex-B NAL units) via x264 from YUV
   frames; MJPEG/PNG fallback path also built. On-wire chunking of NALs into
   `Packet`s is still native-side/unconfirmed.

5. **Touch/control — PARTIAL.** Confirmed Java model:
   `IEPScreenEventHandle.onTouchEvent(EPTypes.EPTouchEventData)` where
   `EPTouchEventData{type, pointX, pointY, slot}` and
   `EPTouchEventType{UP=0, DOWN=1, MOVE=2}` (multi-touch via `slot`). Separate
   physical-button taxonomy `EPBtnCode`/`EPBtnEventType` (volume, music
   transport, nav, talkie, etc). On-wire byte encoding unconfirmed — likely a
   small fixed struct inside a `Packet`, not verified.

6. **Audio — PARTIAL.** `EPTypes.EPAudioInfo{sampleRate, channel, format}`
   with an explicit PCM enum (U8/S8/U16/S16/U24/S24/U32/S32-LE/BE, F32)
   confirms raw PCM is handed into `native_sendAudioData`/
   `native_startAudioRecord`. Whether that gets AAC-encoded before the wire
   (FFmpeg's AAC encoder is enabled) or sent as raw PCM frames is
   **unconfirmed** — both are plausible statically.

7. **Native `.so` / JNI — CONFIRMED, and this is the main blocker.** The
   real protocol engine is `libEPSDK.so` (C++, namespace `CarbitEPSDK`):
   `AOATransport`, `Packet`, `MIServer` (mirror), `FTPServer`, `CMPServer`,
   `PXCService`, `C2PMainService`/`P2CMainService` (phone↔car), `ECHandler`/
   `ECLoop`. Java `EPSDK.java` is a thin pass-through
   (`System.loadLibrary("EPSDK")` + ~25 `native_*` methods) — essentially
   zero protocol logic at the Java/smali level. `libhud_protocol.so` and
   `libusb-lib.so` are red herrings by name: the former is just the
   RSA-license-key stub above; the latter is the open-source `libaums`
   USB-host mass-storage library (likely an unrelated "read a USB flash
   drive" feature, not the head-unit link).

8. **PhoneAuto / AA head-unit-server (secondary, time-boxed) — CONFIRMED
   mechanism, capture path still open.** `PhoneAuto-beta-2027-01-19.apk` is
   a lightly repackaged build of Google's own Android Auto **sink** app:
   package `com.google.android.projection.sink.ui` with real classes
   `HeadUnitServerAccessibilityService`, `HeadUnitServerGuide`,
   `MainActivity`, `PortraitMainActivity` — i.e. head-unit-server mode is a
   first-party AA feature gated behind an `AccessibilityService`-based
   onboarding flow, not custom automation built by PhoneAuto. Added glue
   lives under `com.tarplay.aap` / `com.tarplay.phonescreen` (e.g.
   `PhoneScreenActivity`). **No `MediaProjection`/`VirtualDisplay` usage
   found anywhere in this APK statically** — so the actual frame-capture/
   output mechanism `PhoneScreenActivity` uses is still open; it may render
   directly to a `Surface` passed elsewhere rather than capturing rendered
   UI via projection.

### Next steps from this pass

1. **Live USB capture needed** to pin down items 2/3/5/6 precisely:
   `usbmon` on a rooted phone acting as USB host (or a hardware USB
   analyzer) between a real CarbitLink-paired phone and the head unit —
   capture a full session (handshake, touch, audio start/stop, video
   start). Without this, `Packet` header/length/checksum/crypto fields
   remain unresolved.
2. Disassemble `AOATransport::buildConnection`/`enqueue`/`sendUp` and the
   `Packet` class layout in `libEPSDK.so` (Ghidra/IDA) as a static
   complement to #1 — may resolve framing without waiting for hardware
   access.
3. Decide PCM-vs-AAC-on-wire by correlating `native_sendAudioData` call
   sites with capture or native disassembly.
4. For PhoneAuto: trace `PhoneScreenActivity`'s surface/output path (base
   class is obfuscated) to see how rendered AA frames leave the sink
   process — lower priority, feeds the capture-layer design later.

## Phase 1 — Protocol discovery (current)

- [ ] Static analysis of `CarbitLink-EasyConnection_6.5.1_APKPure.apk`:
  - [ ] `AndroidManifest.xml` — USB intent filters, declared permissions
  - [ ] `accessory_filter.xml` / `usb_device_filter.xml` — VID/PID
  - [ ] Handshake / magic bytes / version strings
  - [ ] Frame format: headers, length fields, checksums
  - [ ] Crypto: `Cipher` usage, XOR/scramble routines, bundled crypto libs
  - [ ] Video transport: `MediaCodec`/`Surface` usage, NAL vs custom container
  - [ ] Touch/control wire format
  - [ ] Audio: sample rate, codec, framing
  - [ ] `lib/*.so` inventory + JNI call surface (protocol logic may be pushed
        here specifically to resist static analysis)
- [ ] If static analysis is inconclusive: live USB capture
  (`usbmon` on a rooted phone acting as host between real CarbitLink app +
  head unit, or hardware USB protocol analyzer). Needs the owner to attempt
  this — flag exactly what's missing first.
- [ ] Quick feasibility look at `PhoneAuto-beta-2027-01-19.apk` for the AA
  head-unit-server bring-up flow (likely AccessibilityService automating the
  "Start head unit server" developer-mode toggle) and its AA capture approach.

## Phase 2 — App skeleton (scaffolded now, logic pending)

- [x] Gradle project skeleton (`app` + `protocol` modules)
- [x] USB attach `BroadcastReceiver` stub (`app/.../usb/UsbAttachReceiver.kt`)
  — does not assume host vs accessory role yet
- [x] Empty `protocol` module boundary (`CarbitLinkProtocol`) for isolating
  reverse-engineered wire-format code
- [x] CI workflow (`.github/workflows/android-ci.yml`) — builds `assembleDebug`
  on a standard JDK 17 + Android SDK runner setup
- [x] USB manifest declarations — accessory role + confirmed `Carbit`/
  `CarbitDriverOversea` accessory filter wired into `AndroidManifest.xml` /
  `res/xml/accessory_filter.xml`
- [x] USB sniffer diagnostic mode (`UsbSnifferDiagnostic` + `DiagnosticLogger`,
  reachable from "Developer tools" on `MainActivity`) — opens the confirmed
  accessory connection and dumps every raw byte the head unit sends to a
  rotating log file (hex+ASCII), exportable via the share sheet. No protocol
  logic — purely a capture tool to unblock Phase 1 without needing a rooted
  phone for `usbmon`. Caveat: since it doesn't speak the real handshake, the
  head unit may retry/give up after a timeout; whatever it sends before that
  is still real evidence. Not yet tried against the owner's actual head unit.
- [x] Foreground service (`UsbLinkForegroundService`) — keeps the USB link
  alive while backgrounded, posts a status notification (phase, byte counts,
  a Stop action), holds a partial wake lock (CPU only — screen staying off
  is fine, AA renders to its own virtual display regardless), and
  auto-reconnects the sniffer with exponential backoff (2s–30s) when
  `auto_reconnect_usb` is on. Currently only drives the sniffer; swap in the
  real protocol transport once Phase 3 lands.
- [x] Connection diagnostics screen (`ConnectionDiagnosticsActivity`) — live
  phase/accessory/byte-counter/error view backed by `ConnectionStateHolder`.
- [x] Onboarding checklist (`OnboardingActivity`) — accessibility service,
  battery-optimization exemption, notification permission (API 33+), each
  with live status and a one-tap fix. Now auto-launches once on first app
  start (`MainActivity`'s `onboarding_shown` flag), still reachable anytime
  via "Setup checklist". Battery-optimization request had a real bug (missing
  `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` permission, silently no-op on some
  OEMs) — fixed, with a fallback to the generic exemption list if the direct
  per-app dialog still refuses.
- [x] One-click "Start head unit server" (`HeadUnitServerAccessibilityService`
  + `HeadUnitServerLauncher`) — automates Settings → Version×10 → ⋮ → Start
  head unit server. Heuristic text/description matching, not a stable API;
  `auto_start_head_unit_server` isn't wired into `UsbAttachReceiver` yet
  (TODO there). Real bug found and fixed on-device: `getLaunchIntentForPackage`
  returned null for `com.google.android.projection.gearhead` (it has no
  standard LAUNCHER activity on the owner's phone, so the app reported
  "Android Auto isn't installed" even though it was) — now falls back to
  resolving any ACTION_MAIN activity the package declares at runtime, then to
  opening its app-info page as a last resort. Still not confirmed whether the
  resolved activity actually lands on a screen where "Version" is visible —
  the accessibility automation itself (Settings navigation) remains
  unverified on real hardware.
- [x] Settings screen (`SettingsActivity` + `root_preferences.xml` +
  `AppSettings`) — resolution override (auto/custom), frame rate, video
  bitrate, audio sample rate/channels, auto-start-head-unit-server,
  auto-reconnect, USB role override (debug only — role itself is confirmed),
  verbose logging, debug overlay, accessibility-service shortcut. Nothing in
  the capture/protocol pipeline reads these yet — wiring happens as that code
  lands in Phase 2/3. **Real crash fixed**: `SettingsActivity` extends
  `AppCompatActivity`, but the app had no `android:theme` at all, so it fell
  back to a plain platform theme — AppCompat throws
  `IllegalStateException: You need to use a Theme.AppCompat theme (or
  descendant)` in that case. Added a real Material theme
  (`Theme.CarAALink` / `themes.xml` + `colors.xml`) and applied it at the
  `<application>` level; also restyled `MainActivity` with it (title, live
  status line, Material buttons) instead of unstyled stacked `Button`s.
- [ ] `MediaProjection` + `VirtualDisplay` capture wired to AA's rendered
  surface, sized from the head unit's real display width/height/density
  (from the CarbitLink handshake — not hardcoded)
- [ ] Touch injection back into the AA session from head-unit input

## Phase 3 — Protocol implementation

- [ ] Implement exactly what Phase 1 confirmed in `protocol/`
- [ ] Wire video/audio/touch pipelines through the confirmed frame format
- [ ] If per-session crypto/auth can't be replicated, document that
  limitation here explicitly rather than stubbing around it

## Phase 4 — Hardening / real-car validation

- [ ] Validate against the owner's actual head unit
- [ ] Handle reconnect/resync, error frames, version negotiation edge cases
- [x] Project name: **CarAALink** (renamed from the `headunit-bridge`
  placeholder)

## Open questions carried over from the original brief

- Does the phone act as USB host or accessory toward the head unit? (Needs
  manifest + behavioral confirmation — don't assume from the dongle's own
  role, since the phone's role could differ.)
- Is there an API for programmatically starting AA's head-unit-server mode,
  or does it require `AccessibilityService` automation of the developer-mode
  toggle (as PhoneAuto/CarCast Launcher/TarPlay Tesla reportedly do)? **No
  public API found** — `HeadUnitServerAccessibilityService` (our own
  implementation, not extracted from PhoneAuto) automates the toggle by text
  matching. Still needs on-device verification against the owner's actual
  Android Auto build; AA's settings copy/layout can change between versions.
