# Handoff

Last rewritten: 2026-11-01, end of a long live-testing session. Everything
below reflects the current state of `main` (through PR #12) plus live
testing done directly on the owner's phone (Samsung Galaxy S25 Ultra,
wireless-debugging adb) against the real car head unit. See
`roadmap.md` for full cited detail — this file is the "what actually
happened and what to do next" summary.

## LATEST (2026-11-01, late) — read this first

**IT WORKS ON THE CAR.** The head unit shows CarAALink's own home screen,
plays live phone-screen mirroring, and takes touch. This is a working
CarbitLink replacement for video + touch. Branch `car-video-live`
(PR open); most of the session's video work is there, newer UI/features
committed on top.

### What's confirmed working on real hardware
- Full handshake: ADB-over-AOA (24-byte zero kick, jadbd-identical CNXN
  reply) -> head unit OPENs tcp:10922/10921/10920 -> PXC CLIENT_INFO ->
  CONFIG_CAPTURE (1920x720, H.264, ~4 Mbps, pull mode wantFps=0).
- H.264 video to the car: `CarVideoEncoder` (Surface -> Annex-B) feeding
  the car's pull requests (0x72). Keyframe-first, codec-config prefixed,
  starvation watchdog + a 1px animating view so the hardware encoder never
  goes silent (the car drops the stream after ~4 s of no frames).
- Stays connected: `PxcChannel` sends the 0x70000000 heartbeat every 1 s
  on the P2C channel (0x20000). Without it the car disconnected after ~5 s.
- Car home screen (`CarHomePresentation`): big clock, date, weather
  (Open-Meteo, no key), custom background (owner picks a photo in the app),
  tiles with real vector icons (Android Auto / Phone screen / Settings).
- Phone mirroring: `MediaProjection` AUTO_MIRROR into a TextureView on the
  car display (NOT a second display, so Samsung DeX never starts). One tap
  on "Phone screen" -> allow once -> mirror opens (double-tap bug fixed via
  `setProjection(showMirrorNow)`). A Back button overlay (top-left): tap =
  phone Back (accessibility GLOBAL_ACTION_BACK), hold = exit to CarAALink
  home. Car taps/swipes replay on the phone as gestures (needs the
  accessibility service ON).
- Car integration (`CarFeatures`): answers the car's GPS query (0x10430)
  with the phone's real location/speed/heading, and its network-capability
  check (0x10750). Formats confirmed from CarbitLink pp/m0, pp/i.
- App launcher icon (adaptive, teal, head-unit + play glyph).

### Chooser / USB fixes (all live-confirmed)
- `MainActivity` is `singleTask` + `onNewIntent`: picking CarAALink in the
  USB chooser starts the session even when the app is already open (this
  was why early taps did nothing).
- No auto-reconnect; `UsbAccessoryConnection.close()` closes the PFD; the
  manifest USB-attach receiver was removed. Together these stopped the app
  from holding the accessory and blocking Android's app chooser.

### HARD LINE — do not cross (owner pushed on this repeatedly; still no)
Android Auto / CarPlay projection to the car is NOT implemented and will
not be faked. Real AA/CarPlay receivers pass a TLS handshake against a
Google/Apple key; PhoneAuto, open-headunit ("self mode" included), and
DiPlay all embed a *stolen* extracted key to clear it. We do NOT embed,
fetch, gitignore-and-BYO, or copy that key or those projects' code — a
self-signed/own-info cert cannot pass the check, so there is no clean
path today. The "Android Auto" tile is a placeholder ("coming soon").
If a legit path appears (Android-for-Cars partner cert), the forwarding
half is already built.

### Next ideas (owner's list, not yet built)
Music now-playing + transport controls and recent notifications on the car
home screen (MediaSessionManager + NotificationListenerService — files were
started then removed; redo cleanly). Phone widgets via AppWidgetHost.
Multi-touch (car reports it supports it). Turn-by-turn nav icons (0x10040).

### Workflow notes
Owner wants terse replies. Commits allowed. Live testing is wireless-adb
whose IP:port churns constantly — ask for a fresh one each time; prefer the
in-app "Export latest log" (now includes `car-session-*.log`) over fighting
adb. `cl-run.txt` (real CarbitLink capture) and `cl4.txt` are gitignored
local logs with the car's IDs.

## The big picture, in one paragraph

CarbitLink's wire protocol is **not** a bespoke binary format — it's a real
embedded ADB (Android Debug Bridge) session. Confirmed by live capture (head
unit sends a genuine ADB `CNXN` handshake over the USB accessory link) and
by static analysis of `libEPSDK.so`/`libjadbd.so` inside the real CarbitLink
APK (a complete ADB daemon implementation, `CarbitEPSDK::ADBD`/`AgentStream`,
wrapped by `CarbitEPSDK::jAdbd`, carrying a UUID into a hardware-auth check
tied to the earlier-found RSA licensing path). This reframes Phase 3 from
"reverse-engineer an unknown binary protocol" to "implement the public ADB
protocol far enough, then figure out CarbitLink's named services and its
UUID/auth gate on top of it." See `roadmap.md`'s two RE-pass sections for
full citations.

**Ground rule reaffirmed this session**: understanding CarbitLink's
architecture (confirmed facts, cited to evidence) is fine and is what we're
doing. Porting its actual compiled code/algorithms is not, even temporarily
"for testing" — this was explicitly discussed and declined. Keep building a
clean-room implementation from confirmed facts, not from their binary.

## App state — what's real right now

All of this is on `main`, builds clean (`./gradlew assembleDebug`), and has
been installed/tested on the owner's actual phone:

- **USB accessory role confirmed and wired**: manifest filters, accessory
  match (`Carbit`/`CarbitDriverOversea`).
- **Settings screen**, **onboarding checklist** (battery exemption,
  accessibility service, notifications — all with real intents, bugs found
  and fixed), **connection diagnostics screen**.
- **One-click "Start head unit server"** for Android Auto's hidden dev-mode
  toggle — rewritten once already to match the *real* reference app's
  behavior (confirmed via decompiling `PhoneAuto-beta-2027-01-19.apk` with
  plain `jadx` CLI, since `REA_JADX_MCP_JAR` isn't configured in this
  environment). It completes the overflow→"Start head unit server" tap and
  deliberately does NOT try to automate the "tap Version 10x" step (neither
  does the real reference app — that's manual/guided).
- **`UsbSnifferDiagnostic`**: passive byte-dump diagnostic. Real, working,
  captured the actual ADB handshake bytes from the head unit twice.
- **`AdbHandshakeProbe`**: active diagnostic — completes the ADB CNXN
  handshake (public protocol, not guessed) and logs whatever the head unit
  asks for next (AUTH? OPEN with a service string?). Does not answer AUTH,
  does not OKAY an OPEN, executes nothing it receives. **Not yet confirmed
  to have gotten past CNXN in a clean test** — see "What broke, what's
  fixed" below.
- **`CarAALinkApplication`**: persistent in-app logger, started the moment
  the process starts. Captures this process's own logcat (crashes included)
  to an on-device file independent of whether the adb/wireless-debugging
  connection survives — it kept dying constantly this session, which is
  exactly why this exists. Export via Developer Tools → "Export latest
  log" (shares `app-*.log` + `usb-sniff-*.log` + `adb-probe-*.log` together
  via the share sheet — owner has been sending these over WhatsApp).
- **Auto-start, fixed twice**: first attempt wired the ADB probe auto-start
  to `UsbAttachReceiver` (the broadcast), which fires on *every* attach
  regardless of which app the chooser picks — this actively interfered with
  normal CarbitLink usage (reverted, PR #10 closed as net-zero). Second,
  correct attempt wires it to `MainActivity.onCreate()`, which only
  receives the attach intent when the user actually picks **CarAALink** in
  Android's resolver chooser — no race with CarbitLink. This is merged
  (PR #11) and is what the owner should be testing against now.

## What broke, what's fixed, what to verify next

1. **Two real crashes**, both confirmed via on-device logcat, both fixed:
   `UsbManager.openAccessory()` throwing instead of returning null on a
   stale accessory reference, and `startForeground(connectedDevice)`
   throwing `SecurityException` once the USB permission grant lapsed. See
   `roadmap.md` "Real crashes found and fixed" for full stack traces.
2. **A real usage gap found but not yet fixed**: switching from the sniffer
   to the ADB probe on the *same* physical USB connection (without
   unplugging) made `openAccessory()` return `null` three times in a row —
   the permission grant seems tied to the specific attach session, and
   doesn't survive a close+reopen by a different diagnostic mode without a
   fresh attach. Practical workaround communicated to the owner: pick one
   mode, test it, physically unplug/replug before switching modes. **Not
   yet fixed in code** — a real fix would mean checking
   `UsbManager.hasPermission(accessory)` and calling
   `requestPermission(accessory, pendingIntent)` if it's false, inside
   `UsbAccessoryConnection.open()`, before giving up. Worth doing if this
   keeps being a problem.
3. **adb/wireless-debugging connection reliability was the single biggest
   time-sink this session** — it died constantly, sometimes mid-pull,
   sometimes within seconds of starting a live `logcat` stream. Lessons
   banked for next time: (a) don't hold a live streaming `adb logcat`
   connection open and expect it to survive — it won't; (b) a device-side
   `nohup logcat -v threadtime > /data/local/tmp/sessionN.log &` started via
   a *one-shot* `adb shell` command survives disconnects (it's detached,
   PPID 1), but **you must `adb logcat -c` to actually clear the kernel ring
   buffer** before starting a new one, or the new "session" file replays old
   buffered entries from before the clear and looks like a fresh capture
   when it isn't (this bit us at least twice); (c) the far more reliable
   path by the end of the session was the **in-app `CarAALinkApplication`
   logger** exported via the share sheet (WhatsApp) — prefer this over
   fighting the adb connection.
4. **Not yet verified**: whether `AdbHandshakeProbe`'s CNXN reply actually
   gets a response from the head unit now that the mode-switching confusion
   (#2) and the auto-start race (first attempt) are both resolved. The
   owner's last test used the *sniffer*, not the probe, and showed the same
   timeout-after-CNXN as every previous capture. **Next concrete step**:
   fresh plug-in, pick CarAALink in the chooser (which now auto-starts the
   probe correctly), let it run uninterrupted, export the log. If the head
   unit responds with anything past CNXN (an `OPEN` with a service string
   would be huge), that's the next real finding.

## Repo / workflow notes

- Public GitHub repo: `JObersi10/CarAALink` (renamed from the
  `headunit-bridge` placeholder). Owner asked for it to be public; the
  auto-mode classifier blocked that from this session — still may need
  manual `gh repo edit JObersi10/CarAALink --visibility public` if not
  already done.
- Work happens on branches + PRs, auto-merged by this session once CI
  (`android-ci.yml`, builds `assembleDebug` on a hosted Ubuntu runner) is
  green. By the end of this session that's PR #1 through #12 (PR #10
  closed as a reverted no-op).
- **Shared debug keystore** (`app/debug.keystore`, committed on purpose,
  see `.gitignore`'s explicit exception) — local builds and CI now sign
  identically, so an APK built on this Mac installs over one built by CI
  (or vice versa) without needing to uninstall first. This was a real,
  recurring problem earlier in the session before the fix landed.
- CI now uploads the debug APK as a build artifact (`android-ci.yml`,
  `actions/upload-artifact`) — useful for grabbing an installable APK
  without local SDK access.
- Local build note: this machine's Android SDK + Gradle home
  (`~/.gradle` → symlinked to `/Volumes/SABRENT/gradle-home`) lives on an
  external drive that gets unmounted sometimes — if local builds suddenly
  fail with a missing SDK/lock-file error, check `ls /Volumes` first.
  `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
  ./gradlew assembleDebug --no-daemon` is the working local build command.
- The two source APKs (and extracted folders) are gitignored — large,
  third-party binaries. `jadx` (plain CLI, not the REA/MCP integration —
  `REA_JADX_MCP_JAR` isn't configured here) decompiles them fine for
  further reading if needed; output goes in the scratchpad dir, not the repo.
- The owner's phone connects via **wireless debugging**, whose IP:port
  changes almost every time it reconnects — always run `adb devices -l`
  first and ask for a fresh address if nothing shows up, rather than
  assuming the last one still works.

## Immediate next steps (in order)

1. Confirm `AdbHandshakeProbe` actually gets a response past CNXN on a
   clean, uninterrupted test (see "what to verify next" #4 above).
2. If it does: that response (likely an `OPEN` with a service-name string)
   is the next major unlock — compare against the named services already
   found in `libEPSDK.so` (`C2PMainService`, `P2CMainService`, `F2PService`,
   `P2FService`, `D2PService`, etc., see `roadmap.md`).
3. If `openAccessory()` keeps returning null on mode switches: implement
   the `hasPermission`/`requestPermission` fallback described above.
4. Longer-term, once enough of the ADB-based handshake is understood:
   decide whether to extend `AdbHandshakeProbe` into a real (minimal) ADB
   device-role implementation that can OKAY an OPEN and see what data
   flows — bigger step, needs the owner's explicit go-ahead first since it
   starts actually participating in a session rather than just observing.
