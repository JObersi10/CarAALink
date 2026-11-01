# Roadmap / status

Last updated: 2026-10-31 (after first RE pass)

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
| AA head-unit-server trigger flow | **CONFIRMED mechanism** (via PhoneAuto reference) — it's Google's own first-party AA sink app, head-unit-server mode gated behind `HeadUnitServerAccessibilityService`, not custom automation |
| AA capture (MediaProjection/VirtualDisplay) feasibility | UNKNOWN — no MediaProjection/VirtualDisplay usage found anywhere in PhoneAuto statically; capture mechanism still open |

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
