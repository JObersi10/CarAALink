# Roadmap / status

Last updated: 2026-10-31

## Status at a glance

| Area | Status |
|---|---|
| USB role (host vs accessory) | UNKNOWN — pending RE pass |
| USB VID/PID | UNKNOWN |
| Handshake sequence | UNKNOWN |
| Packet/frame format | UNKNOWN |
| Crypto / obfuscation | UNKNOWN |
| Video transport | UNKNOWN |
| Touch/control wire format | UNKNOWN |
| Audio handling | UNKNOWN |
| Native `.so` protocol logic | UNKNOWN whether present |
| AA head-unit-server trigger flow | UNKNOWN (reference: PhoneAuto, closed-source) |
| AA capture (MediaProjection/VirtualDisplay) feasibility on current Android | UNKNOWN, needs confirming on target API level |

This table starts fully unresolved by design — see `claude.md`'s rule against
fabricating protocol details. A background reverse-engineering pass against
`CarbitLink-EasyConnection_6.5.1_APKPure.apk` was kicked off alongside this
scaffold; once it reports back, update this table row by row with either a
CONFIRMED value + evidence citation, or a note on why it stayed UNKNOWN
(e.g. "logic lives in lib/*.so, needs native disassembly" or "needs usbmon
capture").

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
- [x] One-click "Start head unit server" (`HeadUnitServerAccessibilityService`
  + `HeadUnitServerLauncher`) — automates Settings → Version×10 → ⋮ → Start
  head unit server. Heuristic text/description matching, not a stable API;
  needs on-device testing against the owner's actual Android Auto version,
  and `auto_start_head_unit_server` isn't wired into `UsbAttachReceiver` yet
  (TODO there)
- [x] Settings screen (`SettingsActivity` + `root_preferences.xml` +
  `AppSettings`) — resolution override (auto/custom), frame rate, video
  bitrate, audio sample rate/channels, auto-start-head-unit-server,
  auto-reconnect, USB role override (debug only — role itself is confirmed),
  verbose logging, debug overlay, accessibility-service shortcut. Nothing in
  the capture/protocol pipeline reads these yet — wiring happens as that code
  lands in Phase 2/3
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
