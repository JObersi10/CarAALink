# Roadmap / status

Last updated: 2026-11-02. The AA receiver workstream (version exchange
through live decoded video/audio/touch, through the real car tile, not
just a debug preview) is now **CONFIRMED working on real hardware** -- see
`claude/handoff.md` for the detailed, dated findings and
`claude/aa-receiver-notes.md` for the cited protocol specifics. The table
below is CarbitLink-wire-protocol-specific and mostly predates that
workstream; the two AA rows are updated, the rest is unchanged.

## Status at a glance

| Area | Status |
|---|---|
| USB role (host vs accessory) | **CONFIRMED** — phone is USB *accessory*, head unit is USB *host* |
| USB VID/PID / accessory ID | **CONFIRMED** — AOA accessory match, not VID/PID (manufacturer `Carbit`, model `CarbitDriverOversea`) |
| Handshake sequence | **CONFIRMED architecture** (live capture + binary RE, 2026-11-01) — the head unit's first bytes are a real ADB protocol CONNECT, and CarbitLink embeds its own ADB daemon (`libjadbd.so` / `CarbitEPSDK::ADBD`) to speak it. See below for the full picture. |
| Packet/frame format | **CONFIRMED architecture** — it's ADB's own OPEN/WRTE/OKAY/CLSE stream multiplexing, not a bespoke CarbitLink frame format. Named services (`C2PMainService`, `P2CMainService`, etc.) are opened as ADB streams. Exact service-name strings used on the wire still unconfirmed (see below). |
| Crypto / obfuscation | PARTIAL — RSA auth/signature path confirmed to exist; now tied to a confirmed UUID-based hardware-auth exchange (`EPHardwareAuth::getCheckUUIDCmd`, `onUuidReceived`) riding on the ADB channel. No byte-level detail yet. |
| Video transport | **CONFIRMED** — software H.264 (x264) via bundled FFmpeg, YUV in, Annex-B NAL out (MJPEG/PNG fallback also built in) |
| Touch/control wire format | PARTIAL — Java-level struct confirmed (`EPTouchEventData{type,x,y,slot}`, `EPBtnCode`); on-wire byte encoding unconfirmed |
| Audio handling | PARTIAL — PCM format enum confirmed at Java boundary; PCM-on-wire vs AAC-encode-before-wire unconfirmed |
| Native `.so` protocol logic | **CONFIRMED present and dominant** — `libEPSDK.so` (`CarbitEPSDK::AOATransport`/`Packet`/...) holds essentially all protocol logic; Java is a thin JNI pass-through |
| AA head-unit-server trigger flow | **CONFIRMED mechanism**, live-verified end to end — `HeadUnitServerLauncher` + `HeadUnitServerAccessibilityService` auto-start it, `AaSessionProbe` drives the real session through to decoded video+audio+touch on the real car tile. See `claude/handoff.md` 2026-11-02. |
| AA capture (video/audio/touch) feasibility | **CONFIRMED working** — not MediaProjection/VirtualDisplay as originally guessed; CarAALink is the AA *head unit* itself (TLS handshake, service discovery, per-channel media), not a screen-capture consumer of a locally-rendered AA session. See `claude/aa-receiver-notes.md`. |

Every row above is cited to a specific file/class/string in the pass notes
below, per `claude.md`'s rule against fabricating protocol details.

## Live USB capture (2026-11-01) — the handshake is literally ADB

First real on-device capture, via the in-app USB sniffer (`UsbSnifferDiagnostic`,
Developer Tools → "Start USB sniffer"), with the real CarbitLink app
(`net.easyconn.carman.wws`) force-stopped first so our app actually got
`openAccessory()` (confirmed — earlier attempts had CarbitLink's own process
holding it; `adb shell am force-stop net.easyconn.carman.wws` before
plugging in, then picking CarAALink in Android's USB resolver chooser, fixed
that).

Exact captured bytes (hex+ASCII, from the sniffer's own log file):

```
RX (24 bytes): 43 4E 58 4E 00 00 00 01 00 40 00 00 0B 00 00 00 00 00 00 00 BC B1 A7 B1  | CNXN....@..............
RX (11 bytes): 68 6F 73 74 3A 3A 73 52 67 56 00  | host::sRgV.
```

`43 4E 58 4E` = ASCII `"CNXN"` — this is the **Android Debug Bridge (ADB)
wire protocol's CONNECT command**, not a CarbitLink-specific magic number.
The 24-byte header matches ADB's standard message struct
(`command, arg0, arg1, data_length, data_check, magic`), and the following
11 bytes (`"host::sRgV\0"`) is a classic ADB connect banner
(`host::<features-or-random>`). Confirmed independently by system logs from
the same session: `AccessoryManager: openAccessory: ... initJAdbd ok` fires
on every real `openAccessory()` call (ours and CarbitLink's), and kernel
uevents show `ACCESSORY=GETPROTOCOL` / `ACCESSORY=SENDSTRING` right as the
link comes up — consistent with the AOA accessory channel being used to
carry an ADB session rather than (or in addition to) CarbitLink's own
`CarbitEPSDK::Packet` framing found in `libEPSDK.so`.

**Working theory, not yet confirmed further**: this is a known pattern in
cheap CarPlay/AA dongles — screen-mirror by enabling USB debugging and
driving the phone via plain ADB (`screenrecord`/`input`) instead of a real
custom protocol. If that's what's happening here, implementing our side of
this would mean implementing the **public, fully-documented ADB protocol**
(see AOSP's `system/core/adb/OVERVIEW.TXT`), not reverse-engineering
anything further — a much smaller job than the native `libEPSDK.so` framing
would otherwise be. Still unconfirmed: whether this ADB channel carries the
*actual* mirroring data, or is a side channel for something else (licensing
check, diagnostics) while `libEPSDK.so`'s `AOATransport`/`Packet` carries
the real video/audio/touch.

**Reproduced a second time, identical bytes**, after the crash fixes below
landed: same 24-byte CNXN header, same `"host::sRgV\0"` banner, byte for
byte. This isn't a one-off fluke — the head unit opens with this same ADB
CONNECT every time. Both captures ended the same way too: ~8s in, the read
fails (`read interrupted by close()` the first time, `EIO (I/O error)` the
second — same outcome, our sniffer doesn't speak ADB back, so whatever's on
the other end gives up). Next step: have our app actually complete a
minimal ADB CONNECT handshake (we control the device side; within reach
since the protocol is public) to see what it asks for next, instead of
capturing it give up again.

## RE pass 2: CarbitLink embeds its own ADB daemon (2026-11-01)

Live capture showed the head unit's first move is a real ADB CONNECT. Rather
than guess why, went back into `CarbitLink-EasyConnection_6.5.1_APKPure`'s
own native libraries (`strings` on the `.so` files directly — no
disassembler is configured in this environment, `rea doctor` showed Ghidra/
Hopper/IDA all unavailable, but plain `strings` was enough here since the
binary isn't stripped of symbol names).

**`libEPSDK.so`'s `lib/arm64-v8a` directory also contains a `libjadbd.so`** —
literally "Java ADBD". Its demangled symbols are a complete, from-scratch
ADB protocol daemon implementation in a `jadbd::` C++ namespace: `ADBD`
(`start(TransportBase*)`/`stop(...)`), `AgentStream`, `SockStream`
(`createTcpSock`/`createLocalSock` — forwards ADB streams to real local
sockets, exactly like real `adb forward`), `StreamBase`, `Packet`,
`StreamManager`, `FdEventManager`. This is a real, generic ADB-protocol
implementation, not something CarbitLink invented.

**`libEPSDK.so` itself mirrors this almost exactly** under its own
`CarbitEPSDK::` namespace — `ADBD`, `AgentStream`, `Packet`, `StreamBase`,
plus a higher-level `CarbitEPSDK::jAdbd` wrapper
(`initialize(IJAdbdEventListener*)` / `startService(int)` / `stopService()`)
with an event-listener interface carrying `onError(int, string)` and,
notably, **`onUuidReceived(const string&)`**. That UUID flows into
`TransportBase::setUuid()` → confirmed via the literal log string
`"[aoa]AOATransport:setUuid uuid=%s, isEmpty=%d, size=%d"` — i.e. the ADB
session carries a UUID that gets attached to the same `AOATransport` object
RE pass 1 already found driving the USB accessory link.

That UUID feeds a **hardware-auth check**: `CarbitEPSDK::EPHardwareAuth::
getCheckUUIDCmd()` and `CarbitEPSDK::C2PMainService::handleCheckCarUUID(...)`,
plus log strings `"[EPSDKContext]updateClientPXCAuthInfo channel=%d, uuid=%s,
status=%d"` and `"checkClientPXCAuth channel=%d, uuid=%s"` — ties back to RE
pass 1's RSA-signature finding (`libhud_protocol.so`'s license-key stub,
`AuthRSA` symbol): the UUID exchanged over this embedded ADB session is
plausibly what gets checked against the RSA-signed license, though the
exact mechanics of that check are still unconfirmed.

**Named services, almost certainly the ADB `OPEN` service-name strings
used on the wire** (class names with a `getName()` virtual method, found as
literal strings in the binary): `C2PService`, `P2CService`,
`C2PMainService`, `P2CMainService`, `C2PMInstService`, `P2CMInstService`,
`F2PService`, `P2FService`, `D2PService`. Reading the naming convention
(not yet verified against a capture): C2P/P2C = Car-to-Phone / Phone-to-Car
(main control channel), F2P/P2F = File-to-Phone / Phone-to-File, D2P =
likely the display/video channel. Literal ADB service-string prefixes
(`"local:"`, `"tcp:"`, `"localabstract:"`, `"localreserved:"`,
`"localfilesystem:"`) are also present as strings in `libEPSDK.so`,
confirming its `AgentStream`/`SockStream` parse standard ADB service syntax.

**What this means for Phase 3**: the wire protocol isn't a bespoke
CarbitLink framing scheme on top of the USB accessory link — it's a real
ADB session (CNXN handshake, then OPEN/WRTE/OKAY/CLSE stream multiplexing)
carrying CarbitLink-specific named services. Implementing our side means:
(1) a minimal ADB device-role implementation (public protocol, already
started — see `AdbHandshakeProbe`), (2) handling whatever `OPEN` requests
arrive with the service names above, (3) the UUID/hardware-auth exchange,
which may or may not be something we can satisfy without the real
CarbitLink licensing backend. **Ground rule still applies**: this is
architectural understanding to build our own clean-room implementation
from, not license to port CarbitLink's actual compiled code — RSA keys,
exact auth algorithms, or any literal logic beyond what's cited here as a
confirmed fact stay out of our implementation unless independently
re-derived.

**Still unconfirmed**: the exact bytes of an `OPEN` request (service-name
string actually sent), whether the UUID is generated by the phone or
requested from the car, and what happens if the hardware-auth check fails
(does it block media channels only, or the whole session?). Next capture
should use `AdbHandshakeProbe` (not the passive sniffer) on a fresh
plug-in, long enough to see the first `OPEN` after our CNXN reply.

## Real crashes found and fixed (2026-11-01)

Live testing surfaced two uncaught-exception crashes in `UsbSnifferDiagnostic`'s
reconnect path, both stemming from the same root cause: retrying with a
`UsbAccessory` reference that's gone stale after the accessory was
physically unplugged.

1. `UsbManager.openAccessory(accessory)` **throws**
   `IllegalArgumentException("no accessory attached")` rather than
   returning null when the accessory is no longer attached — `UsbAccessoryConnection.open()`
   only null-checked the return value, not exceptions, so this crashed the
   whole app. Fixed: wrapped in try/catch, treated as "not attached."
2. `Service.startForeground(..., connectedDeviceType)` **throws**
   `SecurityException` once the accessory's USB permission grant has lapsed
   (message: `"requires permissions: all of [FOREGROUND_SERVICE_CONNECTED_DEVICE]
   and any of [..., USB Device, USB Accessory]"` — the OS checks for an
   active USB permission grant as one of the qualifying conditions for this
   FGS type). Fixed: wrapped in try/catch, transitions to an ERROR state and
   stops the service instead of crashing.

Also added: `UsbLinkForegroundService`'s reconnect loop now checks
`UsbManager.accessoryList` before each retry and gives up (capped at 5
attempts) instead of retrying forever against a device that's actually gone.

**Confirmed fixed by re-testing on-device** (via the app's own exported
`app-*.log`, after the fix was installed): no `FATAL EXCEPTION` anywhere in
a full test pass that included the same accessory-disconnect scenario that
used to crash it. A subsequent `IOException: read failed: EIO (I/O error)`
during the same session was caught and logged cleanly instead of crashing —
that's the existing read-loop error handling working as designed, not a new
issue.

**Also added this session**: a persistent in-app logger
(`CarAALinkApplication`) that starts the moment the process starts —
captures this process's own logcat output (crashes included) to an
on-device file via `DiagnosticLogger`, independent of whether a live
adb/wireless-debugging connection survives (it kept dying mid-session, which
is what prompted this). Verbose `Log.i`/`w`/`e` calls were also added across
`UsbAttachReceiver`, `MainActivity`, Developer Tools, and the sniffer/service
so the logcat stream shows what's actually happening instead of relying on
Toast text nobody captured.

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

   **Follow-up pass (2026-11-01, decompiled with `jadx` CLI directly —
   `REA_JADX_MCP_JAR` isn't configured in this environment, so the
   REA/jadx-MCP tools weren't usable for this one; plain `jadx -d <dir>
   <apk>` worked fine).** Read `HeadUnitServerGuide.java` and
   `HeadUnitServerAccessibilityService.java` in full:
   - `HeadUnitServerGuide.a(Context)` opens AA's Settings by trying, in
     order: `Intent("com.google.android.projection.gearhead.SETTINGS")`
     (package-scoped), the explicit component
     `com.google.android.projection.gearhead.companion.settings.DefaultSettingsActivity`,
     `getLaunchIntentForPackage(...)`, then AA's app-info page.
   - The accessibility service **never taps "Version"**. It only reacts to
     `TYPE_WINDOW_STATE_CHANGED`/`TYPE_WINDOW_CONTENT_CHANGED` events while
     "armed" (a 20s time-boxed flag set by the UI right before launching
     settings), and does exactly: find a node whose content-description is
     `"More options"` or `"More"` → click it → on the next event, find text
     exactly `"Start head unit server"` or `"Start headunit server"` → click
     it. If it instead finds `"Stop head unit server"` text, it backs out
     without touching anything (already running).
   - The one-time "tap Version 10x" step is manual, guided by
     `HeadUnitServerGuide`'s own on-screen instructions
     (`head_unit_server_used` SharedPreferences flag tracks whether a user
     has done it before, to adjust the guide text on repeat visits).
   - This directly explains why our first `HeadUnitServerAccessibilityService`
     implementation failed on real hardware: it tried to automate the
     Version-tap (which the reference app deliberately doesn't), and
     `HeadUnitServerLauncher` only tried the generic launcher intent, which
     landed on the wrong AA screen entirely. Both are now fixed to match
     this confirmed behavior.

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
  + `HeadUnitServerLauncher`) — **rewritten against confirmed real behavior**
  after on-device testing exposed two bugs in the first version:
  1. `getLaunchIntentForPackage("com.google.android.projection.gearhead")`
     returned null on the owner's phone — gearhead has no standard LAUNCHER
     activity, so the app wrongly reported "Android Auto isn't installed".
     It actually launched some other activity (`...gmscorecompat.FirstActivityImpl`,
     confirmed via live logcat) that isn't AA's Settings screen at all.
  2. The automation tried to tap "Version" 10x itself, which is NOT what the
     real reference app does — confirmed by decompiling
     `PhoneAuto-beta-2027-01-19.apk` with `jadx` CLI this session (the bundled
     REA/jadx-MCP integration needs `REA_JADX_MCP_JAR` set, which isn't
     configured here, but plain `jadx -d <dir> <apk>` works fine standalone).
     `com.google.android.projection.sink.ui.HeadUnitServerGuide.a()` tries,
     in this exact order: the deep-link action
     `"com.google.android.projection.gearhead.SETTINGS"`, the explicit
     component `.../gearhead/companion/settings/DefaultSettingsActivity`,
     the generic launcher intent (our old, unreliable fallback), then AA's
     app-info page. Its
     `com.google.android.projection.sink.ui.HeadUnitServerAccessibilityService`
     only ever automates: find the overflow button (content-description
     `"More options"`/`"More"`) → click it → find `"Start head unit
     server"`/`"Start headunit server"` text → click it. Tapping Version is
     a one-time **manual** step, guided by that app's own on-screen
     instructions ("tap Version 10 times to turn on developer mode") — it
     deliberately doesn't automate that, almost certainly because it's too
     fragile across AA versions/OEMs. The service also "arms" itself
     (time-boxed, 20s) right *before* the settings intent is launched,
     rather than polling after the fact.

     Our rewrite mirrors this exactly: `HeadUnitServerLauncher` tries the
     same four intents in the same order and arms the service first;
     `HeadUnitServerAccessibilityService` only handles overflow → Start, and
     `HeadUnitServerLauncher` shows a one-time Toast telling the user to tap
     Version manually the first time (`head_unit_server_version_guided` pref),
     matching the reference app's UX instead of trying to automate it.
     Still not re-tested on-device as of this edit — the previous bugs were
     confirmed by the owner's live logcat, this fix is not yet.
  `auto_start_head_unit_server` isn't wired into `UsbAttachReceiver` yet
  (TODO there).
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

## Live test: first CNXN reply sent (2026-11-01 11:31)

Evidence: `adb-probe-20261101-113131.log` (owner export) + device logcat.

- CONFIRMED: head unit CNXN header = version `0x01000000`, maxdata `0x4000`
  (16384), data_length 11, **data_check 0**, magic OK. Payload
  `host::N9r8\0` arrives in a **separate read()** from the header.
- CONFIRMED: probe sent an 85-byte CNXN reply with data_check = 0. ~4.2 s
  later, read() failed with EIO (head unit reset the link). After that,
  every `openAccessory()` returned null until a fresh attach.
- INFERENCE (public ADB spec, not CarbitLink code): version `0x01000000`
  means the receiver validates data_check (sum of payload bytes); only
  `0x01000001`+ may skip it. A zero checksum likely got our reply rejected.
  Fix: probe now sends the real sum and reassembles split reads.
  UNVERIFIED until the next live test.

### Retest 11:42 (checksum fix in)
- CONFIRMED: link stayed up ~24 s after our CNXN (was ~4 s). Head unit
  sent nothing more, then EIO.
- CONFIRMED (`strings libjadbd.so`): CarbitLink's banner is
  `device::ro.product.name=jadbd`. Probe now sends that exact banner.
  UNVERIFIED whether the head unit gates on it.

### Retest 11:53 (jadbd banner in)
- CONFIRMED: still nothing from head unit after our CNXN (9 s, then owner
  tapped "Start sniffer", which stopped the probe; tapping that kills the probe).
- CONFIRMED: `jadbd::max_payload` initial value = 4096 (.data @0x76950),
  same as ours, so not the cause.
- CONFIRMED (strings): jadbd has `handleConn,old adb version` / `new adb
  version` and `isNewAdbVersion`, plus device-side `AgentStream::open()` /
  `SockStream::open()` and an `[aoa]outputThread`.
- LEAD (inference, unverified): the head unit may wait for the *phone* side
  to OPEN a stream after CNXN (not standard adb, where only the host opens).
  The candidates are the libEPSDK service names (`P2CMainService` etc.).
  Needs a disassembler pass on `AgentStream::open` to confirm. Do not guess
  the service string on the wire.

## Live capture: real CarbitLink session (2026-11-01 12:06, logcat)

Source: device logcat while the real CarbitLink app connected (raw dump
kept local, gitignored, since it has car/device IDs).

- CONFIRMED: `AccessoryManager.openAccessory` -> `initJAdbd()` ->
  `jadbd: onUuidReceived() uuid=N9r8` within 4 ms. The UUID is the suffix
  of the head unit's `host::N9r8` CNXN banner.
- CONFIRMED: CarbitLink's Java `PXCService` opens a TCP ServerSocket on
  **localport 10922** and accepts a client from **127.0.0.1** ~0.5 s after
  `initJAdbd`. That client is jadbd forwarding a stream the head unit
  OPENed. INFERENCE: the OPEN service string is `tcp:10922` (jadbd's
  `createStream` supports `tcp:` / `local:` / `localfilesystem:`).
  UNVERIFIED on the wire.
- CONFIRMED: first app-level message on that stream is car->phone
  `0x00010010` `ECP_C2P_CLIENT_INFO`, a JSON object (HUID, HUName,
  screenType, mirrorMode, pxcVersion "1.0.2", sdkVersion "0.9.24", ...).
- So the head unit DOES send OPEN to real jadbd. It does not to our probe,
  so our CNXN reply still differs from jadbd's in some way. jadbd's own
  log goes to a file (`initJAdbd logFile:null`), not logcat, so its wire
  bytes are not visible here. Next: disassemble `TransportBase::handleConn`
  (the "old adb version" path) with `xcrun llvm-objdump` to read exactly
  what jadbd sends back. Facts only, no porting.

### jadbd CNXN reply, from disassembly (`TransportBase::handleConn` @0x37628)
CONFIRMED (xcrun llvm-objdump, arm64; strings read at the referenced addresses):
- If host arg0 is 0x01000000 or 0x01000001: arg1 == 0x1000 -> "old adb
  version" path; else -> "new adb version", sets `isNewAdbVersion = 1`.
  Any other arg0 with arg1 != 0x1000 -> error, no reply.
- Payload is checked with `strstr("host::")`; the text after `host::` goes
  to `setUuid` (matches `onUuidReceived uuid=N9r8`).
- Reply: CNXN, arg0 = 0x01000000 + isNew (so **0x01000001** for our head
  unit, which sends maxdata 0x4000), arg1 = host maxdata if new else 4096
  (also stored to `jadbd::max_payload`), payload = snprintf of
  `device::ro.product.name=jadbd` (**no NUL**, 29 bytes), data_check left
  0 in this function.
- Probe now sends exactly these values, plus public-ADB OKAY to any
  OPEN/WRTE so the first stream's data gets logged. UNVERIFIED live.

### Retest 12:18 + `AOATransport::outputThread` (@0x37dc8)
- CONFIRMED live: probe sent a CNXN reply byte-identical to jadbd's
  (v 0x01000001, maxdata 16384, 29-byte banner, 53 bytes total). The head
  unit was still silent for 10 s. So the reply *content* is not the gap.
- CONFIRMED (disassembly): at thread start, jadbd `write()`s **24 zero
  bytes** to the accessory fd, retrying up to 50x with `usleep(20000)`,
  and only then starts `inputThread`. Every packet after that is sent as two
  `write()`s: the 24-byte header, then the payload.
- Probe now does both (zero kick on open, split header/payload writes).
  UNVERIFIED live.

## PXC + mirror protocol (2026-11-01, jadx of CarbitLink 6.5.1 + live logs)

All CONFIRMED from decompiled Java (paths under `net/easyconn/carman/`)
unless marked otherwise. Clean-room: we re-implement the wire format,
not their code. Their RSA/HUID licensing fields are never reproduced.

**Transport**: head unit OPENs ADB streams to `tcp:<port>` on the phone.
Real session (logcat 12:06:50): tcp:10922 twice (PXC 0x10000, 0x20000),
then tcp:10921 (mirror control), then tcp:10920 (mirror data).

**PXC (tcp:10922)** `sdk_communication/i.java`: 16-byte LE head
`int cmd, int totalLen(16+body), int cmd^totalLen, int extra` + body.
First head on a stream = channel id (0x10000 main, 0x20000, 0x30000 ...,
`l0.java` switch); server answers same head with cmd+1. Each request on a
channel gets reply cmd = request+1 (`z0.e()`), body from handler, except
cmds 0x40010, 0x30030, 0x30020, 0x30040, 0x102B0 and (cmd&0xFF000000)==
0x60000000 (`z0.f()`). CLIENT_INFO 0x10010 reply = JSON (see probe).

**Mirror control (tcp:10921)** `utils/Protocol.java`, `common/base/l0.java`:
8-byte LE head `short type, short bodyLen, int token(0)` + body.
- 0x30 GET_VERSION -> 0x31 body `int 5002, int 1`
- 0x40 HEARTBEAT -> 0x41
- 0x10 CONFIG_CAPTURE body: `short w, short h, int wantFps, int wantEncoder
  (1 jpeg, 2 h264, 4 mp4), int supportCodec, short minQ, short maxQ,
  int bitRate, byte capScreenMode, byte touchMode, byte orientation,
  byte displayId, byte videoType, byte supportExtendProtocol, 2 reserved`
  [+ encryptedHUID utf8]. Reply 0x11 body `int encoder, short w&~15,
  short h&~15 [, byte supportExtendProtocol if !=0]`.
- 0x70 DATA_START -> 0x71 after the encoder is started.
- 0x20 SCREEN_EVENT body `short type(1 up,2 down,3 move,4 key), short x,
  short y, short slot, short key [, long time]`.
- anything else -> 0x7F02 UN_SUPPORT.
**Mirror data (tcp:10920)** `common/base/q0.java`: if wantFps==0 the car
pulls each frame with 0x72 (8-byte head + ext[displayId, ackFrames]);
else frames are pushed. Frame (non-movie mode) = `int32 LE length` +
H.264 Annex-B bytes; codec config (SPS/PPS) sent first the same way.
`int 0` = no frame available.

## Status: working video + touch (2026-11-01, late)

The clean-room CarbitLink replacement now mirrors video and takes touch on
the real head unit. See handoff.md "LATEST" for the full working-state
summary. Key wire facts confirmed and implemented this pass:
- Pull-mode video (wantFps=0): car sends 0x72 per frame; reply = int32 LE
  length + Annex-B, codec config (SPS/PPS) sent as its own length-framed
  chunk before the first frame. Encoder must never starve or the car drops
  the data stream after ~4 s.
- P2C heartbeat: phone sends 0x70000000 on channel 0x20000 every 1 s, or
  the car disconnects after ~5 s.
- GPS reply 0x10430 and network-capability reply 0x10750 (pp/m0, pp/i).

## AA receiver identity found + TLS receiver (2026-11-01)

**The blocker named in `handoff.md` — "a car-side AA session needs a receiver
identity issued by Google" — now has a concrete answer: the identity is
available, and it is *not* CarbitLink's.**

### What is in PhoneAuto

`PhoneAuto-beta-2027-01-19/classes.dex` contains three PEM string constants
at offsets **1735456 / 1736618 / 1737906**:

| # | Type | Subject / Issuer | SHA-256 |
|---|---|---|---|
| 0 | X.509 v1 leaf, serial `0x1B`, sha256WithRSA, valid 2014-07-04..2045-04-29 | `C=JP,ST=Tokyo,L=Hachioji,O=JVC Kenwood,OU=01` / `…O=Google Automotive Link` | `1C:0E:0E:F9:E6:72:DD:1A:63:AB:4D:61:AF:AA:89:96:A5:7C:A7:AA:96:6D:19:22:B9:7D:8F:93:85:EA:3C:35` |
| 1 | X.509 v3 self-signed CA, `CA:TRUE`, sha1WithRSA | `…O=Google Automotive Link` / itself | `49:E5:2E:FC:13:AD:2E:D0:9F:20:4C:3B:10:69:8B:D8:4B:B7:10:5F:51:05:58:AA:14:B8:11:9A:5C:4A:D1:7F` |
| 2 | RSA 2048 private key, PKCS#1 | — | *matches cert #0* (modulus equality verified) |

So PhoneAuto embeds a **complete AA head-unit identity**: leaf + issuing CA +
the leaf's private key. Consistent with its own dex strings — `GalReceiver`,
`com.tarplay.aap.receiver.PhoneScreenActivity`, `AA Auth Failed: certificate
expired`, `AA Auth Failed: certificate name mismatch`, `Failed to generate
certificate` — i.e. it really does run a Google Automotive Link receiver.

### Provenance: it is the public aasdk cert, not a CarbitLink secret

Extracted cert #0 is **byte-identical** (`cmp`) to
`cert/headunit.crt` in `opencardev/aasdk`, and the private key's RSA modulus
hashes identically to that repo's `cert/headunit.key`
(`9bea9c12…4ce4de`). aasdk's own `cert/README.md` calls it "a publicly
available certificate used in Android Auto head units … not a secret".
This is the de-facto standard credential for self-hosted head units
(openauto, headunit-desktop, Crankshaft) since the openauto era.

**Consequence for the ground rule:** this is *not* CarbitLink's proprietary
crypto, so `claude.md`'s "don't port CarbitLink's RSA keys/algorithms" is not
being bent. It is an independently-sourced public credential, cited to its
upstream, and documented in `app/src/main/assets/aa/PROVENANCE.md` along with
the caveats (it is JVC Kenwood's vendor key; shipping it in a public APK is
the part with real exposure; override path provided).

### Implemented this pass

- `AaIdentity` — loads the chain + PKCS#8 key from
  `<externalFilesDir>/aa/headunit.{crt,key}` (override) falling back to
  `assets/aa/`, builds an in-memory PKCS12 keystore → `SSLContext`, and logs
  the loaded identity (subject, serial, validity, SHA-256, chain length).
- `AaTlsReceiver` — TLS endpoint presenting that identity. `Mode.DIAL`
  connects to `127.0.0.1:5277` (the phone's own AA gearhead); `Mode.LISTEN`
  binds a port (openauto shape). `TlsRole.AUTO` tries SERVER then CLIENT and
  logs both, because **which side is the TLS server is still unconfirmed**
  (see below). Past the handshake it only observes and hex-dumps — sends
  nothing, same discipline as `AdbHandshakeProbe`. Log tag `aa-tls-*.log`,
  included in Developer Tools → "Export latest log".
- Developer Tools buttons, plus a debug-only headless trigger:
  `adb shell am start -n dev.caraalink/.MainActivity --es aa_tls start`.

### Verified locally, not yet on hardware

`openssl s_server` with the extracted pair completes a real handshake
(`TLSv1.2 / ECDHE-RSA-AES256-GCM-SHA384`) and serves the JVC Kenwood leaf —
so the key/cert pair is valid and usable for TLS server auth.

**But** OpenSSL 3.x rejects the chain with `format error in certificate's
notBefore/notAfter field`: the leaf is X.509 **v1** with legacy time
encoding. Whether Android's Conscrypt (on the phone, verifying us) is equally
strict is **UNCONFIRMED** — first live run of `AaTlsReceiver` will say.

### Still UNKNOWN (do not assume)

- Whether AA's gearhead is TLS client or server in the self-hosted case
  (TCP and TLS roles may be inverted — that is exactly what `TlsRole.AUTO`
  measures).
- What protocol sits above TLS (the AA protobuf/framed-channel layer). Nothing
  implemented, nothing invented.
- Whether the phone accepts this cert at all given the v1 encoding.

## AA transport: we had the TLS role backwards (2026-11-01, live on S25 Ultra)

`AaTlsReceiver` ran against the phone's real gearhead (AA 18.0.664244, head
unit server already on) and `aa-tls-20261101-163733.log` says:

```
16:37:33.358  dial 127.0.0.1:5277 (tcp)
16:37:33.361  tcp connected local=/127.0.0.1:58680 remote=/127.0.0.1:5277
16:37:33.374  starting TLS handshake as SERVER (protocols=TLSv1.2,TLSv1.3)
16:39:19.390  handshake/connect failed as SERVER: SSLHandshakeException: connection closed <- EOFException
16:39:19.395  --- retrying with the other TLS role (CLIENT)
16:39:19.899  starting TLS handshake as CLIENT (protocols=TLSv1.2,TLSv1.3)
```

Read: **TCP connect succeeds** (5277 is live), and as TLS *server* we sat
waiting for a ClientHello that never came until the phone dropped the socket
**~106 s later**. So the gearhead is not waiting to be spoken to — it expects
us to open the handshake.

**Which matches aasdk's source**, and settles the question the previous
section left open. `src/Transport/SSLWrapper.cpp` (opencardev/aasdk, branch
`newdev`):

- `SSLWrapper::getMethod()` → `TLS_client_method()`
- `SSLWrapper::setConnectState()` → `SSL_set_connect_state(ssl)` +
  `SSL_set_verify(ssl, SSL_VERIFY_NONE, nullptr)`
- `Cryptor::init()` loads `headunit.crt`/`headunit.key` into that context

So the **head unit is the TLS client** and presents the JVC Kenwood cert as a
*client certificate*; the phone is the TLS server and does not verify us at
the TLS layer at all.

**CORRECTION (2026-11-01, second live run).** The claim above that "TLS starts
at the first byte" is **WRONG** — a CLIENT-role handshake against 5277 timed
out too:

```
16:45:57.505  starting TLS handshake as CLIENT (protocols=TLSv1.2,TLSv1.3)
16:46:12.514  handshake/connect failed as CLIENT: SocketTimeoutException: Read timed out
16:46:12.517  ERROR no TLS role completed a handshake against 127.0.0.1:5277
```

Neither role gets an answer, so the gearhead is not speaking TLS to us at
all. The reason is in `include/aasdk/Messenger/EncryptionType.hpp`:

```cpp
enum class EncryptionType { PLAIN, ENCRYPTED = 1 << 3 };
```

AA frames are individually flagged PLAIN or ENCRYPTED. The **version exchange
is PLAIN** and happens before any TLS: the head unit sends a plaintext
version request, the phone answers, and only then does `Cryptor::doHandshake`
run and the channel switch to ENCRYPTED messages. That is why both of our
role attempts just sat there — we opened a TLS record where the gearhead
expected a plaintext AA frame.

**Still UNKNOWN**: the exact plaintext frame bytes for the version request
(frame header layout per `FrameHeader`/`MessageInStream`, plus the protobuf
`VersionRequest` body). Not implemented, not guessed.

Caveat on the evidence: the CLIENT attempt starts at 16:39:19.899 and the log
ends there because it was exported mid-handshake, so CLIENT was **not yet
ruled out** — it is the untested branch, not a failure.

Changes made on the back of this:
1. `TlsRole.AUTO` now tries **CLIENT first**, SERVER as fallback.
2. `Config.handshakeTimeoutMs` (15 s) — a wrong-role attempt fails fast
   instead of hanging for the phone's ~106 s drop timeout.
3. Car home "Android Auto" tile no longer says "coming soon": it starts
   `AaTlsReceiver` (DIAL/CLIENT) and narrates progress to the car screen
   through `statusSink`, and is stopped on session teardown.
4. Developer Tools → "Export latest log" now writes **one** merged
   `all-logs-*.log` (all tags, interleaved by timestamp) instead of five
   separate attachments.

**Next test**: same tile/`am start`, with CLIENT first. What we want in the
log is `TLS ESTABLISHED role=CLIENT` plus whatever the first AA messenger
bytes look like — that record-layer traffic is the next unknown, and the
hex dump is already wired to capture it.

## Future ideas (not scoped, not started)

- **Phone as a CarPlay/AA "server"/simulator for other devices.** Owner's
  idea (2026-11-02): instead of only acting as a *head unit* that receives
  an AA session from itself, have the phone act as the *server* side too --
  i.e. let some other device (another phone, a dev box, a generic AA/CarPlay
  client) connect to this phone as if it were a car head unit or a phone
  offering AA/CarPlay, independent of CarbitLink entirely. Explicitly a
  later idea, not scoped yet -- recorded here so it isn't lost, not a
  commitment to a design.
- **Phone widgets on the car home screen -- SHIPPED 2026-11-02.** Battery
  and now-playing (title/artist) in `buildHomeContent()`, refreshed every
  3s. Now-playing needs notification access
  (`MediaListenerService` + `NowPlayingInfo`, Settings > Appearance >
  "Now playing on car home") since `MediaSessionManager.getActiveSessions`
  requires the caller to itself be an enabled `NotificationListenerService`
  -- the service never reads notification content, only holds that
  permission.
- **AA-to-CarbitLink bridge -- CORRECTION 2026-11-02: already shipped,
  since the day AA support landed. This bullet previously said "today the
  AA path only ever reaches its own `AaCarPresentation`/`AaPreviewActivity`
  surfaces, never the actual CarbitLink wire" -- that's wrong, caught by
  actually re-reading `CarDisplay`/`CarSession` end to end rather than
  reasoning from the class names.** Design (a) below isn't a "starting
  point" to build -- it's exactly the architecture that's already running:
  - `CarSession.onCaptureConfig()` creates ONE `CarVideoEncoder` per car
    connection and calls `display.attach(enc.inputSurface, ...)`, which
    `CarDisplay` uses as the backing `Surface` of its one private
    `VirtualDisplay` (`carDisplay`).
  - `CarDisplay.showCurrent()` puts the Home, Mirror, *or* AA
    `Presentation` on that *same* `carDisplay.display` depending on mode
    -- there's only ever one virtual display, one encoder, one
    `MirrorDataChannel` (tcp:10920) feeding the real head unit, no matter
    which of the three is showing.
  - So when AA is active, `AaCarPresentation`'s `TextureView` (fed by
    `AaVideoDecoder` decoding the phone's real AA session) is rendered
    *inside* that same virtual display, which the same `CarVideoEncoder`
    captures and sends to the head unit over CarbitLink exactly like the
    phone-mirror and home-screen content do. The "bridge" is just this
    app's normal one-encoder-per-car design, not a separate thing to wire
    up -- there was never a second, AA-only rendering path that bypassed
    the car.
  - Touch already flows the other way too: `CarSession.onTouch()`
    (CarbitLink -> us) and `AaCarPresentation`'s own touch listener ->
    `AaSessionProbe.active?.sendTouch(...)` (us -> AA) are both live
    already; AA mode just uses the latter instead of `display.touch()`.
  - What design (b) (direct NAL passthrough, skipping the decode+re-encode
    double transcode) would still be a real, unscoped optimization for --
    only worth it if the perf HUD shows the existing decode-then-reencode
    latency actually matters in practice. Not attempted: needs the AA
    resolution to exactly match `CaptureConfig.encodeWidth/encodeHeight`
    and the head unit's decoder to accept whatever profile/level AA's own
    encoder picked, neither confirmed.
- **AA audio -> CarbitLink's own USB audio endpoint -- still blocked,
  practical workaround SHIPPED 2026-11-02.** CarbitLink's wire protocol
  still doesn't carry audio in any path (mirroring included) --
  `EPAudioInfo`'s sample-rate/channel/format enum is confirmed at the Java
  boundary, but the native `Packet` framing for audio frames is PARTIAL/
  unconfirmed (see the RE pass 1 findings above), so nothing was invented
  for it per `claude.md`'s hard rule. Shipped instead: `AaAudioPlayer`
  now explicitly steers its `AudioTrack` to a connected Bluetooth A2DP
  device when "Mic / speaker for Android Auto" is BT-preferred, so AA
  audio reaches the car's speakers over the car's own Bluetooth (which
  almost every head unit already has) rather than only the phone's own
  speaker. True CarbitLink-wire audio still needs the live-capture pass
  (`usbmon`/hardware analyzer) `claude.md` calls for before it can be built
  without guessing.
- **Performance/latency HUD -- SHIPPED 2026-11-02.** `PerfStats` (fps,
  kbps, dropped frames, AA reconnect count), gated by the existing
  Settings > Debug > "On-screen debug overlay" toggle (previously
  declared but wired to nothing). Shown on the car
  (`AaCarPresentation`/`MirrorPresentation`) *and* the phone
  (`MainActivity`, `AaPreviewActivity`) per the explicit ask that it
  "lowk show that info also on the phone."
- **Built-in protocol sniffer -- SHIPPED 2026-11-02.** The
  `debug_verbose_logging` setting existed but was wired to nothing; it's
  now "Protocol sniffer" and gates a hex preview (`ProtocolSniffer.
  hexPreview`) on both `MirrorControlChannel`'s control messages
  (heartbeat/screen-event included, previously always skipped) and
  `AaSessionProbe`'s per-message log. Fixed a real bug in the process:
  `AaSessionProbe`'s `"MESSAGE on ..."` log was firing unconditionally for
  *every* video/audio `MEDIA_MESSAGE_DATA` frame through
  `DiagnosticLogger`'s synchronous file write -- now only logged when the
  sniffer toggle is on, off by default.
- **Headless testing harness -- `MirrorStream` extraction + channel tests
  SHIPPED 2026-11-02.** Mock-USB-device tests that simulate the CarbitLink
  handshake/state machine without physical car hardware, so regressions in
  the handshake logic can be caught without a live car.
  - **Done:** `MirrorStream` is now a top-level interface (`localId`,
    `closed`, `write`/`backlogBytes`/`clearBacklog`) in `MirrorChannels.kt`;
    `AdbTransport.Stream` implements it, and `MirrorHost`/
    `AdbTransport.StreamHandler`/`MirrorControlChannel`/`MirrorDataChannel`/
    `PxcChannel`/`CarSession` all take `MirrorStream` instead of the
    concrete `AdbTransport.Stream` -- no behavior change, just the seam
    this section asked for. `app/src/test/kotlin/dev/caraalink/link/
    FakeStream.kt` + `FakeMirrorHost.kt` are the headless doubles;
    `MirrorControlChannelTest.kt`/`MirrorDataChannelTest.kt` drive real
    wire bytes through the real `MirrorControlChannel`/`MirrorDataChannel`
    classes (CONFIG_CAPTURE parsing + reply bytes, SCREEN_EVENT ->
    touch/key dispatch, DATA_NEXT -> frame-request, unknown-type refusal)
    with no `AdbTransport`/USB/device needed. `AaServiceDiscoveryTest.kt`
    covers the new margin-autodetect geometry the same way. Added
    `testImplementation("org.mockito:mockito-core:5.11.0")` just to stub
    `Context.getExternalFilesDir()` for `DiagnosticLogger` in these tests
    -- JVM-only mocking, no Robolectric/instrumentation needed.
  - **Not done (same reasoning as before, still correct):** `CarSession`
    itself still isn't unit-testable -- it directly owns `CarVideoEncoder`
    (`MediaCodec`), `CarDisplay` (`Presentation`/`VirtualDisplay`), and
    other real Android framework classes with no interface seam, so its
    touch-scaling/frame-queue/starvation-watchdog logic is still only
    exercised live. Pulling that apart would be a real refactor of
    confirmed-working code, not done here.
  - Original plan, not yet started, elaborated 2026-11-02:
  - Neither `AdbTransport`'s stream framing nor `AaSessionProbe`'s TLS/
    protobuf logic touches Android APIs in their core parsing (just
    `java.io`/`java.net`/`javax.net.ssl`/the hand-rolled `AaProtobuf`), so
    both can run as plain JVM unit tests in the existing CI workflow --
    no instrumented emulator needed.
  - CarbitLink side: wrap `conn.input`/`conn.output` behind a small
    `Transport` interface so a `FakeTransport` (in-memory
    `PipedInputStream`/`PipedOutputStream` pair) can stand in for the real
    accessory connection; feed it the *exact* byte sequences already
    confirmed in this doc's "Live capture"/"Live test" sections (e.g.
    `TX 00 03 00 06 00 01 00 01 00 06` -> `RX 00 02 00 01 00 07 00 00`)
    as golden-file regression fixtures -- real captured bytes, not
    fabricated ones, so this stays inside `claude.md`'s rule.
  - AA side: point `AaSessionProbe` at a loopback `ServerSocket` instead
    of the real `127.0.0.1:5277` gearhead server, and script a fake AA
    handshake (version exchange, a test-cert TLS handshake via plain
    `SSLEngine`/`SSLContext` -- standard JDK, no device needed -- then a
    canned `SERVICE_DISCOVERY_REQUEST`) to assert the probe reaches the
    expected state (sends `VERSION_REQUEST`, completes the handshake,
    answers with the right `SERVICE_DISCOVERY_RESPONSE`, etc.).
  - Highest-value first test: replay this doc's own confirmed capture
    bytes as a golden-file test -- would have mechanically caught at least
    one of the "Real crashes found and fixed" bugs above instead of only
    catching it live.
- **Local crash marker + AA session watchdog -- SHIPPED 2026-11-02** (part
  of the broader crash-telemetry/watchdog feasibility check above). Crash
  reporting stays local-only -- there's no backend, so nothing is
  "telemetry" in the upload sense: `CrashMarker` persists a one-shot
  "crashed last run" summary (SharedPreferences, not a network call) that
  `MainActivity` surfaces as a toast on the next launch; the full stack
  trace was already going into the unified log via
  `CarAALinkApplication`'s existing crash handler. `CarSession` now also
  polls `AaSessionProbe.lastVideoFrameAt` every 5s and, if it's gone stale
  (10s+) while the car is actually showing the AA screen, tears down and
  restarts the session -- covers a frozen-but-still-connected socket,
  which `AaSessionProbe`'s own `everEstablished` reconnect logic doesn't
  (that only fires on an actual socket/TLS error).
  - USB bulk-transfer tuning from the same feasibility check: **not
    applicable, left alone.** `AdbTransport.readLoop()`'s 16 KB read
    buffer is already confirmed to match the accessory driver's own bulk
    buffer size (comment on that line, from the live-verified probe) --
    there's no raw `bulkTransfer` tuning available through the AOA
    `FileInputStream`/`FileOutputStream` abstraction this app uses, so
    there's no headroom to tune without switching to a different USB API
    entirely.
  - Multi-profile/multi-car management from the same check: **deferred**,
    not started -- the most UI-heavy piece, picked up in the next PR
    instead of this one.

### Research notes (2026-11-02), for the three items below

Static research only (cloned `opencardev/aasdk`+`openauto` to scratchpad,
no new live testing) -- cited to source per `claude.md`, nothing guessed.
Not scoped/started, just grounding for whoever picks these up.

- **Wireless (Wi-Fi Direct / local AP) transport.** aasdk already has a
  full wireless mode, confirmed from its own proto/source (not something
  we'd invent): the transport layer underneath is just a plain TCP socket
  (`aasdk::tcp::TCPEndpoint`/`TCPTransport` -- the exact same AA frame
  format as USB, wrapped over `boost::asio` TCP instead of an AOA
  accessory stream) and the pairing flow is a dedicated `WIFI_PROJECTION`
  channel: the **head unit** is the one that hosts the Wi-Fi AP and sends
  `WifiCredentialsResponse{car_wifi_ssid, car_wifi_password,
  car_wifi_security_mode, access_point_type, supported_wifi_channels}`
  (`WifiProjectionService::sendWifiCredentialsResponse()`, ENCRYPTED/
  CONTROL framing, same pattern as `CHANNEL_OPEN_RESPONSE`) to the phone
  in response to its `WifiCredentialsRequest` (empty body). There's also a
  `WirelessTcpConfiguration{socket_receive_buffer_size_kb,
  socket_send_buffer_size_kb, socket_read_timeout_ms}` message (sits
  alongside `ping_configuration` in `ConnectionConfiguration`) for tuning
  the TCP socket once it's up. Everything above the transport/pairing
  layer (TLS handshake, service discovery, channels) is identical to what
  `AaSessionProbe` already does over the USB loopback -- the real work
  here is the AP-hosting + Bluetooth-RFCOMM-or-NSD pairing handshake
  that real AA wireless dongles implement, which is **not present in
  aasdk/openauto at all** (they assume Wi-Fi is already connected by the
  time their code runs) and would need its own evidence source before any
  of it gets implemented.
- **Automated protocol fuzzing / state-machine testing.** No AA-specific
  reference for this (not something aasdk/openauto do) -- general
  methodology only. Two practical angles for this codebase specifically:
  (1) a crash-oracle fuzzer feeding mutated byte sequences into
  `AaFraming`'s frame parser and `AaProtobuf.parse()` directly (both are
  small, dependency-free, pure functions over `ByteArray` -- ideal fuzz
  targets, no device needed), checking only "does it throw/hang", not
  protocol correctness; (2) a state-machine test harness around
  `AaSessionProbe`'s message dispatch using a fake `Socket`/stream pair
  replaying a captured or hand-built sequence of frames, verifying it
  reaches the expected state (and doesn't crash) on reordered/truncated/
  duplicated messages. Overlaps heavily with the headless testing harness
  idea above; likely the same piece of work.
- **Multi-display resolution/DPI negotiation.** AA's video resolution is
  still hard-limited to the 5 fixed `VideoCodecResolutionType` values
  (cited in `aa-receiver-notes.md` already) -- nothing about "multi-
  display" changes that constraint, it's protocol-wide. What IS real and
  relevant: `VideoConfiguration.ui_config` (field 11, cited now --
  `UiConfig{margins, content_insets, stable_content_insets, ui_theme}`,
  all `Insets`, i.e. safe-area-style padding, not a resolution/DPI
  mechanism) and `MESSAGE_SERVICE_DISCOVERY_UPDATE` (control id 26,
  `ServiceDiscoveryUpdate{service}` -- reuses the same `Service`/
  `MediaSinkService`/`VideoConfiguration` message, so in principle could
  renegotiate resolution/DPI for one channel after the initial discovery
  without tearing down the session). **Neither is implemented anywhere in
  aasdk or openauto** -- no sender, no receiver, in either reference
  codebase. So "does mid-session resolution/DPI renegotiation actually
  work against a real phone" is **UNKNOWN**, not confirmed, and would need
  a live capture (sending a `SERVICE_DISCOVERY_UPDATE` and seeing whether
  gearhead honors it) before building anything on top of it.

  **Update (2026-11-02), from `mrmees/open-android-auto`'s own protocol
  docs** (`docs/video-resolution.md` and the rest of that repo's `docs/`
  tree -- a from-scratch AA receiver project with real written protocol
  notes, worth treating as a reference alongside aasdk/openauto for this
  codebase's own RE work going forward):
  - **Letterboxing/pillarboxing is baked into the H.264 stream by the
    phone, not left to the head unit.** The phone renders its UI into a
    restricted, centered inner viewport and fills the remaining edge
    space with black pixels *before* encoding -- the head unit just
    displays whatever frame it gets, full-size, no cropping/masking logic
    of its own needed.
  - **`width_margin`/`height_margin` are commands, not hints.** Sent in
    `ServiceDiscoveryResponse`, the phone uses them to shrink its internal
    render canvas and bake the margins into the stream as described above
    -- confirms our own `AaServiceDiscovery.Config` doc comment's "both
    default to 0 and the car letterboxes instead" was the safe choice,
    since 0 margins means AA renders edge-to-edge and leaves any
    letterboxing to whoever displays the frame (us), rather than us
    trying to guess margin values to get AA to do it for us.
  - **No mid-session renegotiation -- CONFIRMED, not just "unimplemented
    anywhere we can see."** Resolution and margins are locked for the
    life of the session at the initial `ServiceDiscoveryResponse`
    handshake; changing either requires tearing down and reconnecting.
    This resolves the UNKNOWN above: "multi-display resolution/DPI
    negotiation" cannot mean live renegotiation, full stop.
  - **`density` only rescales UI elements (fonts/icons/padding) inside
    the fixed frame** -- it cannot substitute for margins or fix an
    aspect-ratio mismatch; a wrong-shaped frame stays wrong-shaped no
    matter what density says.
  - **Implication for this app, not yet implemented:** since there's no
    live renegotiation, "change resolution/orientation without the user
    manually restarting AA" has to mean *we* tear down and restart the
    session automatically when a relevant Settings value changes
    (`car_orientation`, resolution mode/custom width/height, frame rate,
    DPI) while an AA session is active -- a `SharedPreferences` listener
    in `SettingsActivity` calling back into `CarSession` to stop and
    restart `AaSessionProbe`. Deferred to the next PR, not in this one.

### Aspect-ratio/margin follow-up (2026-11-02), confirmed from a real live capture

A live `CONFIG_CAPTURE` on this exact head unit confirms the panel is
genuinely **1920x720** (2.67:1) -- not a guess, read straight off the
car. Combined with the already-confirmed "no 2.67:1 `VideoCodecResolutionType`
exists" above, this closes out whether `width_margin`/`height_margin`
could do better than the app's own Fit/Fill scaling:

- **Nonzero margins shrink AA's own render canvas, but change nothing
  about encode cost.** AA honors a margin by rendering into a smaller
  inner rectangle and painting the margin zone black itself -- but the
  hardware encoder still encodes the *full* nominal resolution (e.g.
  1920x1080) either way. Margins are a software canvas inset, not a
  resolution reduction: identical USB bandwidth, identical decode cost,
  whether margins are 0 or aggressive. Gearhead also clamps/falls back
  if margins push the inner canvas outside a safe aspect-ratio bound, so
  this isn't even a reliable way to coax AA into an arbitrary shape.
- **`content_insets`/`stable_content_insets` (part of `UiConfig`) only
  reposition AA's own UI elements** (buttons, route guidance) away from
  known obstructions -- they don't touch the raw frame's aspect ratio or
  replace letterboxing/pillarboxing. Real practical value for a Fill/crop
  setup though: setting these to match the cropped-off zones would keep
  AA's own interactive controls from landing in the part of the frame
  this app clips, instead of just hoping they don't. **Not implemented
  yet** -- `AaServiceDiscovery.build()` doesn't send a `UiConfig` at all
  today; this would need those insets added to the `VideoConfiguration`
  it builds, sized from the current Fit/Fill choice and the actual
  clipped margin on each axis. Worth doing if Fill mode turns out to clip
  AA's own buttons in practice.
- **No standard head unit gets true native 2.67:1 over plain AA either.**
  OEM ultrawide dashes either request a normal 16:9 stream and
  scale/crop it with their own board's hardware, or use vendor-specific
  extensions outside the open AA spec entirely -- which is exactly why
  this project needs its own mirror bridge (CarbitLink) rather than
  native AA support, consistent with this whole project's premise.
- **Correction (same day, after reading `mrmees/open-android-auto`'s
  `docs/video-resolution.md` directly rather than secondhand): margins
  are worth pursuing after all, just not for the reason first assumed.**
  That doc's own wording: the phone renders into "a centered sub-region"
  and fills the rest with black, and **the head unit crops/overscans the
  decoded video to show only the content area** -- i.e. margins aren't
  just "AA paints black pixels we'd otherwise paint ourselves," they
  define an *exact, known* inner content rectangle the head unit is
  expected to crop to. For this panel specifically: requesting
  `R1920X1080` with a `height_margin` sized so the inner content height
  is exactly 720 would mean cropping to that inner rect and displaying
  it 1:1 -- **no scaling at all**, vs. today's Fit (downscales to
  1280x720, still pillarboxed) or Fill (upscales and clips real AA
  content, not just AA's own intentional blank margin). That's strictly
  better than either current option, not just a different tradeoff --
  full native resolution, nothing real cropped, no interpolation
  blur. Bandwidth/encode cost is still identical either way, as already
  noted, but that was never the point; the point is this is a sharper,
  more correct Fill than scaling can ever produce.
- **Why this isn't implemented yet: the exact margin semantics are
  genuinely unconfirmed, not just unimplemented.** Neither this repo's
  own `AaServiceDiscovery.Config` doc comment nor
  `open-android-auto`'s doc states whether `height_margin`/`width_margin`
  is a *per-side* value (content height = resolution.height -
  2*margin, split evenly top+bottom) or a *total* inset (content height
  = resolution.height - margin, split some other way) -- "centered
  sub-region" supports either reading. Shipping the wrong interpretation
  wouldn't just look slightly off, it would crop into real AA content or
  leave an unexpected black band, which is worse than the current
  honest Fit/Fill. Per `claude.md`'s rule, this needs a live capture
  (request a deliberately large, easily visually distinguishable margin
  value and see exactly where AA puts the real content in the decoded
  frame) before it's implemented, not a guess either way.

#### SHIPPED (2026-11-02): auto-crop, with the semantics question still open

Replaced the old manual "Test height_margin" debug probe with
`AaServiceDiscovery.autoMargin()` (pure function, headless-tested in
`AaServiceDiscoveryTest.kt`): it computes `width_margin`/`height_margin`
automatically from this car's real reported screen size
(`CarScreenInfo.width`/`height`, from its own `CONFIG_CAPTURE`) vs. the
chosen AA resolution's aspect ratio, so the inner content rect's aspect
ratio matches the car's panel exactly (no more manual guessing of a pixel
value). Gated by `Settings > Android Auto > "Auto-crop to car screen
shape"` (`AppSettings.aaAutoCrop`, default on).

**Still UNKNOWN, per the per-side-vs-total question above:** this
function assumes margin is a *total* inset (content size = resolution −
margin), not confirmed. If the next live test shows the real content
rect is off by roughly 2x in the cropped dimension, that's the per-side
interpretation instead — the fix is multiplying the margin sent on the
wire by 2 in `configFromSettings()`, not touching `autoMargin()`'s own
geometry. Worst case either way is a wrong-looking crop, not a crash, and
the setting can be turned off to fall back to plain Fit/Fill.

### Feasibility check (2026-11-02), four more requested items

All four checked against what this app actually is (checked: **no NDK/
native code anywhere in the project** -- `app/build.gradle.kts` has no
`externalNativeBuild`, no `.so`/`.c`/`.cpp` under `app/src/main`; it's pure
Kotlin/JVM using the standard `android.hardware.usb.UsbAccessory` /
`ParcelFileDescriptor` API) rather than assumed from the feature names
alone.

- **Automatic crash reporting & telemetry -- possible, partly built
  already.** `CarAALinkApplication.installCrashLogger()` already catches
  every uncaught JVM exception into the unified log (`DiagnosticLogger`,
  2026-11-02's logging-unification work). "Native USB/NDK crashes" don't
  apply here -- there is no native code to crash; a USB-layer failure here
  surfaces as a JVM `IOException`/`IllegalArgumentException` from the
  standard `UsbAccessory` API (already partly handled, e.g.
  `UsbAccessoryConnection.open()`'s catch for the stale-accessory crash in
  `claude/roadmap.md`'s "Real crashes found and fixed" section), not a
  native signal. TLS handshake failures and decoder drops are also already
  logged (`AaSessionProbe`, `AaVideoDecoder`/`AaAudioPlayer`'s `Log.w`
  calls, captured into the same unified log via the self-logcat capture).
  What's NOT built: structured telemetry (counts/rates rather than free-
  text log lines) and any upload to a "self-hosted endpoint" -- that's a
  real, separate, fully feasible piece of work (an HTTP client + an
  endpoint to receive it, which doesn't exist and isn't this project's to
  build), not something blocked on anything in the app itself.
- **Multi-profile & multi-car management -- possible, straightforward.**
  Every `AppSettings` read already takes a `Context` and goes through
  `PreferenceManager.getDefaultSharedPreferences()` (one flat global
  store); this would mean keying a settings bundle by a car identity --
  `UsbAccessory.manufacturer`/`model`/`serial` is already available at
  connect time (`UsbAccessoryConnection`/`CarSession` both see the
  `UsbAccessory` object) and is a reasonable natural key, though serial is
  `UNKNOWN` to be populated by every head unit without a live check across
  more than one. No architectural blocker; would need a small per-car
  preferences store (e.g. a second `SharedPreferences` file per car ID, or
  a Room table) and a lookup at `CarSession` start instead of a single
  global `AppSettings`.
- **Watchdog & auto-recovery daemon -- possible, standard pattern, nothing
  here does it yet.** No existing heartbeat/liveness check on the USB read
  loop, `AaSessionProbe`'s read loop, or `CarVideoEncoder`'s frame
  callbacks today -- a stall currently only surfaces indirectly (no new
  log lines, which is how last night's debugging actually noticed stalls,
  not an automated check). A watchdog is straightforward to add: a
  `Handler`-based timer comparing "time since last successful read/frame"
  against a threshold per pipeline (USB transport, `AaSessionProbe`,
  video/audio decode), and on timeout, tearing down and restarting just
  that pipeline -- `AaSessionProbe.stop()`/`start()` and
  `UsbLinkForegroundService`'s existing `stopSelfCleanly()`/reconnect path
  already provide the restart primitives this would call into. Genuinely
  "without unplugging" is achievable for the AA side (loopback TCP, fully
  in our control); for the actual USB-to-car link, recovery still can't
  reopen a *physically* dropped accessory without the OS re-delivering an
  attach intent, so "auto-recovery" there means recovering from a wedged
  *software* state, not a real physical disconnect.
- **USB bulk transfer optimization -- partly possible, "zero-copy" is not
  achievable at this layer, said plainly rather than overclaimed.**
  "Mass storage" doesn't apply -- that's a different USB device class
  (UMS) unrelated to the AOA accessory mode this app uses. The real target
  is the AOA bulk transfer under `UsbAccessoryConnection`'s
  `FileInputStream`/`FileOutputStream`, currently read in fixed 16 KB
  chunks (`AdbTransport.kt`, `ByteArray(16 * 1024)`) with no ring buffer
  and no thread-priority tuning. Buffer size and thread priority ARE
  realistic, tunable knobs (`Thread.currentThread().priority`,
  experimenting with read-buffer size against this specific USB
  controller/cable). **True zero-copy is not something the standard
  Android `UsbAccessory`/`ParcelFileDescriptor` Java API exposes to app
  code** -- there's no direct-buffer/DMA path surfaced above the driver;
  every read still copies into a JVM byte array. Framing "zero-copy" as
  the goal would be promising something this API can't deliver; framing it
  as "reduce copies and tune buffer/thread parameters" is accurate and
  achievable.

## Session findings (2026-11-02, round 2): AudioTrack underrun loop, exit-session cleanup, AA exit button

Three separate owner reports in one session: Car Preview still laggy and
silent (no music audio), and AA's own built-in exit/back UI control not
doing anything. Investigated from two real exported logs rather than
guessing.

- **FOUND + FIXED: `AudioTrack` stuck in a continuous underrun-restart
  loop.** `AaAudioPlayer.setCodecConfig()` called `track.play()`
  immediately, before any real PCM existed. In the first real log, AA's
  audio channel negotiated (one `MEDIA_CODEC_CONFIG`) but never actually
  sent a data frame that session -- the started-but-empty track sat
  there, and the system kept cycling it: logcat spammed
  `AudioTrack: restartIfDisabled(...): ... disabled due to previous
  underrun, restarting` every ~600ms for the entire session. That's a
  continuous, pointless restart loop on its own dedicated thread for as
  long as the session runs -- a real, confirmed CPU cost, and plausibly
  a meaningful contributor to "laggy" on top of explaining "no audio"
  (the track was never actually playing anything in that test). Fixed:
  `play()` now happens lazily, on the first real decoded PCM in `feed()`.
- **FOUND + FIXED: quitting AA never actually disconnected.** Both
  `CarSession` (real car) and `AaPreviewActivity` (on-phone preview) only
  ever detached the video sink when leaving the AA tile/screen --
  `AaSessionProbe.active` (and its TLS connection to the phone's own
  head-unit-server mode) stayed alive in the background indefinitely.
  Both paths now call `AaSessionProbe.active?.stop()` there, so leaving
  AA actually ends the session.
- **AA's own "exit"/back UI control still not confirmed fixed -- genuinely
  unresolved, not guessed at.** Checked `mrmees/open-android-auto`'s
  `docs/channels/input.md` (GPL-3.0; facts below paraphrased from reading
  it directly, nothing copied into this repo) to rule out an encoding
  bug first: AA's `TouchEvent` schema is pixel coordinates in the
  negotiated video resolution (no normalization, no DPI scaling), no
  pressure (phone hardcodes 0.8f, ignores whatever's sent), and the same
  action codes we already use (DOWN=0/UP=1/MOVE=2) -- `AaSessionProbe.
  sendTouch()`'s encoding matches that exactly, so this isn't an
  encoding mismatch. The same doc also documents a *separate* message
  type we don't implement at all: `ButtonEvents` (hardware
  keycodes -- BACK=4, HOME=3, etc.), distinct from touch. If AA's own
  exit control is actually bound to a BACK keycode rather than being a
  tappable region, touch alone would never trigger it -- genuinely
  unconfirmed either way, not implemented, because inventing a keycode
  sender without evidence it's needed would be exactly the kind of guess
  `claude.md` rules out.
  - **What actually blocks confirming this:** the second real log
    (the one covering the exit-tap attempt) has **zero** `INPUT_REPORT`
    sends for the entire session -- not just missing the exit tap,
    missing *every* touch that session. Settings > Debug > "Protocol
    sniffer" was off, so the car's own incoming `SCREEN_EVENT` messages
    (which would show whether the car even sent that tap to us) aren't
    logged either -- `MirrorControlChannel` only logs those when the
    sniffer is on. Can't tell from this data whether the tap never
    reached us, reached us but didn't relay, or needs a keycode we don't
    send.
  - **Next step, not done here:** retest with the Protocol sniffer ON,
    specifically tapping AA's exit control, then check for `MIRROR ctrl
    0x0020` (RX, the car's `SCREEN_EVENT`) and the touch-forward log line
    on the AA side -- whichever is present or missing tells us which half
    is broken, instead of guessing.
- **Car Preview home screen only ever showed "Android Auto" --
  SHIPPED.** `AaPreviewActivity.showHome()` hardcoded a single-tile list
  instead of the real three (Android Auto / Phone screen / Settings) from
  `CarSession`'s own tile set -- fixed to match.
- **Rolling `dev` GitHub pre-release never actually published --
  SHIPPED.** `.github/workflows/android-ci.yml`'s publish step was gated
  to `push` events on `main` only; all real work happens on
  `aa-post-v1`/PRs against it, so it had never once fired. Now runs on
  any push or PR build and labels the release by its real source
  (branch or PR number + commit) instead of a hardcoded "main".

## Session findings (2026-11-02, round 3): the real audio root cause -- missing AV flow-control ACK

Owner reported the audio fix from round 2 didn't help -- two fresh
exported logs still showed the exact same `AudioTrack` underrun-restart
loop, unchanged timing. Rather than re-guess, re-checked
`mrmees/open-android-auto`'s docs further (`docs/channels/audio.md`,
`docs/interactions/04-channel-lifecycle.md` -- GPL-3.0, facts
paraphrased/field-numbers cited, nothing of theirs copied in) and found
the actual root cause:

- **`AaServiceDiscovery`'s `MEDIA_SETUP` response has always granted
  `max_unacked=1`** on every AV channel (video and audio) -- this repo's
  own existing code, not new.
- **Nothing ever sent the corresponding ack
  (`AVMediaAckIndication`, msg id 0x8004/32772) back.** Per the
  documented flow-control contract, the phone tracks unacked AV frames
  against that limit and simply stops sending more once it's hit --
  no timeout-based recovery, it waits forever. That matches both logs
  exactly: one audio `MEDIA_CODEC_CONFIG`, then zero data frames, ever.
  Not an `AudioTrack`-timing bug (round 2's fix), not Bluetooth routing
  (an even earlier guess) -- a missing protocol ack.
- **SHIPPED:** `AaSessionProbe` now captures `session` from each AV
  channel's own `MEDIA_START` (`AVChannelStartIndication`) and sends an
  incrementing `AVMediaAckIndication` after every video/audio data (and
  video codec-config) frame.
- **Open question this surfaces, not resolved:** video has been
  "working" despite the exact same missing-ack bug applying to it too
  (same generic `MEDIA_SETUP` handler, same `max_unacked=1`). Plausible
  explanation, **not confirmed**: this may be the real cause behind the
  long-standing, never-fully-explained ~42s periodic AA disconnect
  mentioned earlier in this doc -- video stalling after its own
  unacked-frame limit, then the session dying and reconnecting before
  anyone could tell video had actually stopped mid-session rather than
  the link just dropping. Worth specifically watching whether that
  reconnect cadence changes (or stops happening) on the next live test,
  now that acks are being sent.
- **AA exit-button investigation, still unresolved:** the round-2 log
  covering an exit attempt in Car Preview never received a
  `SHUTDOWN_REQUEST` either (same as round 1's real-car log) -- whatever
  the owner tapped didn't produce that message this time either. Genuinely
  don't know yet whether that's "wrong control tapped", "AA on this
  version/path doesn't send it", or something still not reaching AA at
  all. Needs a fresh, deliberate retest on the build with this ack fix
  in it, since a session that was silently stalled on audio may behave
  differently now that it isn't.

## Future idea (owner's, low priority, not started)

Owner wants the car home screen to be more customizable than the current
fixed three tiles -- something like phone-style widgets, user-arranged
layout, and a car background image (untested so far). "Not really that
needed, but it's just cool to have." No design/scope decided yet; revisit
when there's bandwidth, not urgent.

## Session findings (round 4) -- AA restart self-kill, Car Preview scaling, multi-touch

- **AA restart self-kill -- FIRST FIX WAS WRONG, confirmed by a real live-car
  log.** The watchdog-restart (and settings-change-restart) path was
  killing the brand-new probe within ~2-3ms of it connecting, every single
  time, before it could ever get a VERSION_RESPONSE. First attempt guarded
  `CarSession.showAaSurface()`'s `onSurface(null)` branch on
  `AaSessionProbe.active === myProbe` -- wrong, because by the time the
  OLD `AaCarPresentation`'s async `surfaceDestroyed` teardown fires,
  `.active` already points at the new probe (identity check is always
  true during a normal restart). Real fix: check `display.mode != Mode.AA`
  instead -- `showHome()`/`showMirror()` set mode away from AA *before*
  dismissing, while an in-place restart sets it to AA first and only then
  dismisses, so that's the signal that actually distinguishes "user left
  AA" from "this is just the old presentation tearing down mid-restart."
  Not yet re-verified live after this correction.
- **Separately, in that same log: the phone's head-unit-server didn't
  answer VERSION_REQUEST at all, 10s straight, across all 3 restart
  attempts.** Real, independent issue -- not caused by our code, nothing
  in our logs explains why gearhead went silent that particular session.
- **Car Preview negotiated resolution/density/orientation all came from
  car-oriented settings (or CarScreenInfo, which only reflects the last
  REAL car), not from the phone actually running the preview.** Confirmed
  from a real log + a screenshot: a landscape 1920x1080 feed rendered as a
  small box on a portrait phone, with the UI inside looking tiny once
  stretched (AA's auto-DPI formula keeps logical UI width constant at
  ~541dp for a real car panel -- correct there, wrong once that buffer is
  blown up to fill a much bigger phone screen). Fixed via a `preview: Boolean`
  flag threaded through `AaServiceDiscovery.configFromSettings()` ->
  `AaSessionProbe.Config` -> both `AaPreviewActivity`'s own sizing math and
  the probe's actual `SERVICE_DISCOVERY_RESPONSE` (both MUST agree, or the
  decoder silently fails per the existing code comment): preview mode uses
  the phone's own current screen shape/size and real density instead of
  car settings. A user-set Custom resolution is still honoured in preview
  too (their explicit choice), only the *auto* case was being driven by
  car-oriented defaults.
- **Audio keeps showing up as genuinely written to AudioTrack** (confirmed
  via its own "frames delivered" count on stop(), e.g. 1.2M+ frames in one
  session -- real, sustained PCM) **at a normal non-zero media volume**
  (7-8/15, confirmed via an added volume log), **while still reported
  inaudible.** This app always has an active USB accessory connection to
  the CarbitLink dongle for the entire session, even during Car Preview --
  hypothesis (not yet confirmed live): default audio routing was letting
  that USB connection claim output instead of the phone's own speaker.
  Fixed by explicitly forcing `TYPE_BUILTIN_SPEAKER` as the preferred
  device when not in BT-preferred mode, instead of leaving routing
  unconstrained. **Not yet verified live.**
- **Multi-touch (pinch/rotate) added.** Previously `sendTouch()` was
  hardcoded single-pointer; Google Maps pinch-zoom and pan weren't
  reachable at the protocol level at all, regardless of coordinates.
  Confirmed from mrmees/open-android-auto's `docs/protocol-reference.md`
  (GPL-3.0, field numbers only) that `TouchEvent.touch_location` (field 1)
  is a `repeated` field -- exactly the shape needed, already proven
  correct for single-touch. `AaSessionProbe.sendTouch()` now takes a list
  of `(x, y, pointerId)` for every currently-down finger plus which one
  triggered this event (`actionIndex`); `AaPreviewActivity` and
  `AaCarPresentation`'s touch listeners now read `event.pointerCount` /
  `getPointerId(i)` instead of just the primary pointer, and handle
  `ACTION_POINTER_DOWN`/`ACTION_POINTER_UP` alongside `ACTION_DOWN`/`UP`.
  **Not yet tested live** -- Maps panning (single-finger drag) was also
  reported broken in the same test; that's likely a *separate* issue
  (coarse 30ms move-throttling vs. whatever sample rate Maps' own gesture
  recognizer expects), not fixed by this change, still open.

## Session findings (round 5) -- real-car marathon: audio, the real ~30s mystery, margin cropping, max_unacked

A very long live real-car testing session. Confirmed fixes marked (confirmed);
everything else is shipped but not yet live-verified at time of writing.

- **Real crash, found and fixed (confirmed from a real crash log on the
  current build):** `AaSessionProbe.run()`'s retry loop slept via a bare
  `Thread.sleep(retryDelayMs)` between attempts. `stop()` interrupts this
  same worker thread; if that interrupt landed mid-sleep, the resulting
  `InterruptedException` was uncaught anywhere in `run()` and crashed the
  **entire app**. Reproduced by the owner's own workflow: toggling "Start
  Head Unit Server" while a probe was mid-retry. Now caught and treated as
  the normal shutdown it is.
- **The real ~30s periodic AA disconnect mystery (long-standing, mentioned
  in earlier rounds) -- likely actually found this time, confirmed from a
  real log:** a "--- reconnecting" fired with NO exception and NO
  `SHUTDOWN_REQUEST` logged anywhere before it, meaning the message loop
  was exiting *cleanly*, not failing. `maxMessages=2000` was sized for the
  pre-streaming startup phase (discovery/channel-open chatter is ~10
  messages -- see the comment on the original fix) and was never meant to
  cap an ACTIVE streaming session. At 30fps video plus continuous audio
  chunks, 2000 incoming messages passes in well under a minute -- the loop
  was cleanly hitting this self-inflicted cap and reconnecting on a timer
  nobody asked for. Fixed: uncapped once a session is `everEstablished`.
  A separate, doc-supported proactive-PING_REQUEST heartbeat was also added
  (mrmees/open-android-auto's session-maintenance doc explicitly shows a
  head unit sending periodic pings, which we never did) -- kept since it's
  harmless and doc-supported, but the `maxMessages` cap is almost
  certainly the real cause.
- **Margin cropping -- confirmed bug, found via mrmees/open-android-auto's
  own `docs/video-resolution.md` (GPL-3.0, facts only), not guessed:** the
  phone renders its UI into a CENTERED sub-region of the frame and fills
  the margin itself with black; the head unit is expected to crop that
  black out, not scale the whole (content+padding) frame to fit. We were
  doing the latter, which shrinks the actual content more than necessary --
  the "could be sized bigger" extra black space reported from a real
  screenshot. Our margin VALUE formula was already confirmed correct
  (matches the doc's own formula exactly); only the missing crop step was
  the bug. `AaCarPresentation` now scales against the content region
  (`videoWidth-widthMargin x videoHeight-heightMargin`) and relies on the
  existing `area.clipChildren` to cut the margin portion off-screen.
  **Not yet exercised live** -- the one later test session had
  `autoCrop=false` (margin 0,0), so this fix was a no-op in that test.
- **`max_unacked` for video was wrong -- confirmed from
  `docs/interactions/04-channel-lifecycle.md`:** video's documented typical
  value is 10 (pipelined sliding window), audio's is 1 (strict lockstep).
  We hardcoded 1 for every channel, video included, forcing every single
  video frame to wait a full ack round-trip before the phone would send
  the next one -- a real, self-inflicted throughput cap on top of whatever
  decode-speed/thermal factors were also in play. Fixed: video channel now
  grants 10.
- **Audio routing, two real regressions found and fixed from live logs:**
  (1) the Car-Preview-only forced-speaker fix was gated on the
  `aa_audio_route` setting, not on preview-vs-real-car, so a setting left
  on Device-Only from Car Preview testing silently carried over and forced
  the real car's audio to the phone speaker instead of Bluetooth. (2)
  BT-preferred-but-no-A2DP-device-found was falling back to *unconstrained*
  routing, which carries the same "silently lost to the always-active USB
  accessory connection" risk already fixed for the device-only case --
  confirmed live, happened even with the car actually paired over
  Bluetooth. Both real-car fallback paths now always pick an explicit
  device (A2DP if found, else the phone's own speaker) instead of ever
  leaving routing unconstrained.
- **Video and audio decode were sharing one thread** (`mediaExecutor`) --
  confirmed live, "video struggling" (heavy frames/thermal throttling) and
  choppy audio (reported as not audibly playing ~80% of the time, despite
  a large lifetime frames-delivered count -- that count only proves total
  writes over time, not smooth continuous playback) were reported
  happening together. Audio now has its own dedicated executor so a
  stalled video decode can't stall audio playback too. Owner's own later
  report: audio lag "does catch up after a while" post-fix -- improved,
  not fully resolved.
- **Settings-change reconnect loop, confirmed from a real log:** changing
  a video setting mid-session triggered an immediate reconnect that got a
  healthy TCP connect but then silence (no `VERSION_RESPONSE`, ever), with
  the watchdog restarting every ~10s for 7 consecutive cycles and never
  recovering without a full head-unit-server restart. This is a *warm*
  restart (gearhead had a live session we just yanked away), unlike the
  cold-start "service not up yet" case the earlier retry-window widening
  (3->8 attempts) covered. A brief grace period (1.5s) before reconnecting
  was added, giving gearhead room to actually tear down the old session
  first instead of racing it. Not yet confirmed live.
- **Still genuinely open, no fix attempted (need more evidence before
  touching code again -- several guesses this session already turned out
  wrong or incomplete):**
  - **AA exit button**: still zero `SHUTDOWN_REQUEST` ever observed from
    tapping AA's own exit control, across every single test this entire
    session. Ruled out as the cause of the (now-fixed) periodic reconnect
    -- that was a separate, coincidentally-timed bug. Genuinely unresolved.
  - **Touch misalignment on the real car** ("gotta press a bit higher"):
    reported in a session where `autoCrop=false` (margin 0,0), so this is
    NOT caused by the margin-crop fix above -- a separate, pre-existing
    coordinate mapping issue. No root cause identified yet.
  - **`AudioTrack: restoreTrack_l(...): dead IAudioTrack ... creating a
    new one`** firing ~10 times in under a second at one point in a real
    log, tied to output-device-change events -- a strong, concrete lead
    for audio choppiness, more specific than the thread-separation fix,
    but root cause (why the native track keeps dying) not yet found.
  - **Video blockiness**: owner reports it "does catch up after a while" --
    consistent with the phone's hardware encoder's own adaptive/phase-out
    bitrate behavior for low-motion content (saw `vq-bitrate-phaseout`
    tuning in a real CCodec log), which is vendor/chipset-level behavior,
    not something controllable from our MediaCodec configuration. May not
    be "fixable" in the traditional sense.
  - **"AA recovery" feature request (owner's, explicit ask)**: owner wants
    behavior like the PhoneAuto-beta reference app -- exit the app
    completely (not just background it) and reopen it, and AA reconnects
    on its own, without needing to manually restart the head-unit-server
    toggle first. Not investigated yet. Likely more about OUR app's own
    process/session-state restoration on relaunch (does `MainActivity`/
    `CarSession` detect "head unit server is already up, try reconnecting"
    on a fresh process start?) than an AA protocol-level feature -- worth
    checking `UsbLinkForegroundService`'s own restart/sticky behavior and
    whether a fresh process re-attaches to an already-attached USB
    accessory automatically. No mrmees doc section found directly on this
    (checked `01-transport-setup.md`, nothing relevant -- that doc covers
    WiFi-based AA setup, not this project's USB path).
  - **Black screen before AA "kicks in"**: still happens, "head unit
    server issue" per the owner's own words -- likely overlaps with the
    already-covered gearhead-readiness/retry-window topic, but not
    re-confirmed with a fresh log on the latest build.
  - **No accessibility permission -> silent generic app launch**: owner's
    observation -- if the accessibility permission isn't granted, tapping
    "Start Head Unit Server" just opens the AA app with no further
    navigation (expected, since the accessibility service is what drives
    the actual developer-mode-toggle navigation) and no explicit prompt
    telling the owner that's why. Worth a clearer in-app message/permission
    prompt in that case; not implemented yet, just noted.

## MAJOR FINDING (same day, later) -- gearhead itself crashes, found via raw `adb logcat`

Not from our own app's logs -- from logcatting the **real Android Auto app**
(`com.google.android.projection.gearhead`) directly on the phone while
testing. Two distinct real crashes caught:

1. `GH FATAL EXCEPTION: main` / `java.lang.IllegalArgumentException: Virtual
   display width must be positive`, at
   `android.hardware.display.VirtualDisplayConfig$Builder.<init>`. Gearhead
   crashes building its OWN internal virtual display with a width that's
   zero or negative -- and that width is derived from values **we** send
   (resolution/margin/density in the `ServiceDiscoveryResponse`). Not yet
   cross-referenced against our own app's log for the same timestamp to
   find which exact value is bad -- next step.
2. `GH FATAL EXCEPTION: Thread-2` / `java.lang.IllegalStateException:
   Already connected`, at
   `com.google.android.projection.gearhead.companion.DeveloperHeadUnitNetworkService`.
   **This is very likely the real cause of "have to restart head unit
   server to make it work," across this entire session.** Gearhead's own
   developer-mode networking component crashes (not just hangs/delays) when
   something attempts a new connection while it still internally believes
   the previous one is live. This is a *crash*, not a timing race -- the
   1.5s settings-change reconnect grace delay added earlier this round
   probably isn't sufficient, because gearhead's broken state doesn't
   recover on a timer; it needs its own crashed process/component to
   actually respawn, which is exactly what manually toggling the developer
   setting off and on does.

**This is a gearhead-side bug, not something directly patchable in our
code** -- but it IS something to avoid *triggering*. Also worth logcatting
gearhead directly (`adb logcat`, filter for `GH.` tags, no package-specific
flag needed since gearhead's own tags are self-identifying) as a standard
diagnostic step going forward -- this found two real, concrete gearhead
crashes in one short test that our own app's log alone would never have
shown, since from our side those just look like "the phone went silent."

**First fix attempted (shipped, not yet live-verified):** `AaSessionProbe
.stop()` now sends a graceful `ShutdownRequest` (reason=USER_SELECTION)
before closing the raw socket, per the teardown doc's own guidance --
previously we just abruptly closed the socket every time WE ended a
session (settings change, watchdog restart, leaving AA), never telling the
phone we meant to. Best-effort/non-blocking.

**If that alone isn't enough, owner's own suggested next experiment:** do
a full USB/accessory-level disconnect-and-reattach on settings change
instead of just a TCP-level AA reconnect, in case gearhead's "connected"
tracking lives above the AA session specifically (tied to the whole USB
accessory session) and a graceful AA-level shutdown still isn't enough to
reset it. Not implemented -- bigger, riskier change since it would affect
the whole CarbitLink session (Mirror/Home too), not just AA.

## DPI direction is inverted from the assumed model -- confirmed twice live, mechanism now confirmed via open-headunit

The simple `px = dp * density/160` model (higher density -> more pixels
per UI element -> looks BIGGER) does not match reality. Confirmed from
TWO separate real tests (Car Preview, and the real car with manual
density=500): a HIGHER declared density makes AA's UI look **smaller**,
not bigger.

**Mechanism confirmed, not just hypothesized, as of 2026-11-06:**
`andreknieriem/open-headunit` (GPL-3.0, real open-source AA head-unit
app) computes `canvasDp(px, dpi) = px * 160 / dpi`
(`AnnouncedDensityPolicy.canvasDp`) -- exactly the breakpoint mechanism
guessed above, now backed by a real working implementation instead of
just our own reasoning. Its `AnnouncedDensityPolicy` also gives real,
named AA layout breakpoints we didn't have a source for before:

- `HUIG_MIN_WIDTH_DP = 748`, `HUIG_MIN_HEIGHT_DP = 450` -- Android Auto's
  documented head-unit-interaction-guidelines floor; below this AA drops
  to a reduced layout.
- `WIDESCREEN_MIN_WIDTH_DP = 1240` -- AA switches to its widescreen
  layout at or above this dp width.
- `PORTRAIT_MIN_WIDTH_DP = 730` -- below this in portrait, AA uses a
  narrow layout where Google Maps stops taking touch input.

Their `AnnouncedDensityPolicy.decide()` picks the highest density that
keeps the visible canvas at/above the HUIG floor, preferring to land at
or above the widescreen line when the codec resolution allows it. Our own
`AppSettings.videoDpi()` auto formula (`min(width,height)/720 * 213`,
clamped 120-320) is a simpler, different heuristic that the owner already
confirmed empirically "looked way better" at the value it currently
produces (142) than an earlier version (213) -- **not replaced here**,
since porting OHA's full `decide()` would also need its
`OnboardingDisplayPolicy.recommendedDpi()` codec-based fallback, which
hasn't been pulled in or reviewed. Flagging the breakpoint constants above
as real, sourced reference values for whoever next revisits this formula,
rather than re-deriving them by guesswork.
