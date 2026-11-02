# Handoff

Last rewritten: 2026-11-01, end of a very long live-testing session on
branch `aa-receiver` (PR #17, not yet merged). See `roadmap.md` for full
cited CarbitLink detail and `aa-receiver-notes.md` for cited AA protocol
detail — this file is the "what actually happened and what to do next"
summary.

## LATEST (this session, after the H.264-flowing milestone below)

Built on top of the working AA video pipeline: touch/input channel, mic
passthrough, **AA audio playback** (media-sink audio channel was negotiating
and streaming fine but nothing ever played it back -- that was the real
"no AA audio" cause, not Bluetooth routing), auto-reconnect, and a
`configFromSettings()` path so Settings > Video resolution/frame rate
actually apply to the AA session instead of always using the 1920x1080
default. Fixed a reopen-preview bug (phone sends SPS/PPS once per TCP
session, not per attach -- now cached and replayed). `AaPreviewActivity`
("Car Preview") now opens CarAALink's own home screen first and only starts
the AA session when its tile is tapped, reusing the same view-building code
as the real car `Presentation` (`buildHomeContent()`). UI modernization pass
across Main/Settings/Onboarding/DeveloperTools/ConnectionDiagnostics; dev
tools trimmed and moved into Settings; onboarding got a one-time intro
screen; README got a banner + CI now publishes a rolling `dev` pre-release.

**Verified live tonight:** version→TLS→discovery→channel-open→video+audio
decode→touch, all working on hardware (gearhead 18.0.664244, S25 Ultra),
including the owner confirming touch control working in the on-phone
preview. **NOT yet re-verified on hardware** (built clean, untested on
device as of this writing): the reopen-black-screen fix, audio playback,
resolution-setting wiring end to end, landscape resize/rotation, and the
new home-first preview flow. Do a device pass on these before anything else.

**Known open issue, unconfirmed:** the AA session appears to drop and
reconnect roughly every minute even while foregrounded. Checked the
ping-echo logic (PingRequest/PingResponse timestamp field) against aasdk
source -- it's structurally correct, so this isn't a fabricated-protocol
issue, but the actual cause is still unknown. Needs a live capture
correlating gearhead's own disconnect/reconnect log line with what we were
doing at that moment, not more guessing from source alone.

**Also not done yet:** decoded AA video/audio aren't piped into the actual
CarbitLink video path (still only the on-phone preview) -- that's the next
real integration step once the open items above are confirmed stable.

## Earlier state (through the H.264-flowing milestone)

**Two separate workstreams live here now:**
1. **CarbitLink mirroring** — works on the car (below).
2. **Android Auto receiver** — working through live H.264 video (see LATEST
   above for what's built on top of it since).

## LATEST — Android Auto receiver: H.264 VIDEO IS FLOWING (2026-11-01)

Live on gearhead 18.0.664244 / S25 Ultra: version -> TLS -> AUTH_COMPLETE ->
discovery accepted -> audio focus -> all 8 service channels opened -> video
MEDIA_SETUP/CONFIG + VIDEO_FOCUS_NOTIFICATION -> sensor started -> **real
H.264 NAL data arriving continuously on MEDIA_SINK_VIDEO (msg#0 =
MEDIA_MESSAGE_DATA), sustained 30+s, no drops**. This was three separate bugs,
found by diffing byte-for-byte against openauto/aasdk source (cloned to
scratchpad, not committed):

1. `SensorSourceService` in our discovery response was empty; openauto's
   advertises 3 real sensors. Fixed, didn't matter much on its own.
2. **The real blocker:** `MediaSourceService` (microphone, channel 9) had no
   `audio_config` -- phone logged `CAR.SERVICE: Critical error 2 detail: 24
   msg: No audio/mic` and dropped right after discovery. Gave it a real
   16kHz/16-bit/mono config and discovery was accepted.
3. `AaSessionProbe.maxMessages` defaulted to 10 -- exactly consumed by
   discovery + audio-focus-request + 8x channel-open, so the probe's own loop
   exited and closed the socket in `finally` right as the real session began.
   Looked identical to a protocol rejection (`gearhead` logged
   `FRAMER_READ_END_OF_STREAM` at that instant) but was 100% us. Raised to
   2000.

Also newly handled: `MESSAGE_AUDIO_FOCUS_REQUEST` (control id 18, not
previously handled at all -- phone was asking for it right after discovery),
per-channel `CHANNEL_OPEN_REQUEST`/`RESPONSE` (`MessageType::CONTROL` flag,
not `SPECIFIC` -- different frame flag from every other message we send),
`MEDIA_MESSAGE_SETUP`/`CONFIG`/`VIDEO_FOCUS_NOTIFICATION` on the video+audio
sink channels, `SENSOR_MESSAGE_REQUEST`/`RESPONSE` on the sensor channel.

**Not done yet:** we parse but discard the H.264 bytes. Next: feed
`MEDIA_MESSAGE_DATA` payloads (first byte(s) are a timestamp per
`MediaChannelMessage`/`AVChannelMessage` framing -- not yet decoded, check
aasdk's `AVMediaChannelMessage`) into the CarbitLink video path instead of
logging them, then do the same for the input/touch channel.

## Earlier notes below (service discovery was the blocker; now fixed, kept for the citations)

The "Android Auto" tile is no longer a stub: it drives a real AA receiver.
Full detail, citations and dead ends are in
[`aa-receiver-notes.md`](aa-receiver-notes.md). Summary:

**Working, reproducible on hardware:**
- Version exchange: TX `00 03 00 06 00 01 00 01 00 06` → RX
  `00 02 00 01 00 07 00 00` (VERSION_RESPONSE 1.7, status 0).
- TLS handshake carried **inside** AA frames as `ENCAPSULATED_SSL` (msg id 3).
  Pinned to TLSv1.2 with a leaf-only client cert so the flight sizes match the
  reference app exactly (ClientHello 138 B, cert flight 1191 B, final 51 B).
  The phone's `CarService` cert chains to the same Google Automotive Link CA
  we ship, so both ends are in one PKI.
- **`AUTH_COMPLETE` (PLAIN, msg id 4, `AuthResponse{status=0}`) immediately
  after the handshake** — this was the step that unblocked everything. Without
  it the phone stays silent forever. With it, the phone sends
  `SERVICE_DISCOVERY_REQUEST` (`label_text='Android'`,
  `device_name='samsung SM-S938B'`, `phone_info`).

**Blocked:** the phone rejects our `SERVICE_DISCOVERY_RESPONSE` and closes the
socket ~0.82 s later (`EOFException`; gearhead logs `GEARHEAD_ENDPOINTS_FAILED`).
Our protobuf decodes cleanly field-by-field, so it is not malformed — something
the phone needs is missing. Next step is to diff against openauto's exact
response rather than keep guessing.

**Resolution question — settled:** AA has **no 1920x720 mode** (only
800x480 / 1280x720 / 1920x1080 / 2560x1440 / 3840x2160 + portrait) and never
renders at the panel size. openauto just does
`videoWidget_->setGeometry(screenGeometry)` and scales. So we keep
**1920x1080 @30 fps H.264 Baseline (16:9)** and let the CarbitLink side fit it
into the car's 1920x720. `width_margin`/`height_margin` exist but their exact
semantics are unconfirmed; both default to 0.

**Operational trap (cost hours):** gearhead's head-unit server binds 5277,
fills its accept backlog and never calls `accept()`. You connect, get silence
forever. `adb shell "ss -ltn | grep 5277"` with a non-zero `Recv-Q` means
stale — the only fix is the UI toggle (AA Settings → ⋮ → Stop, then ⋮ → Start).
Retrying makes it worse; one clean attempt after a fresh toggle is the winning
pattern.

## CarbitLink mirroring (2026-11-01, late) — read this second

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

### AA projection — status

Superseded by the "Android Auto receiver" section at the top and
[`aa-receiver-notes.md`](aa-receiver-notes.md). Short version: rungs 1-3 of the
AA protocol work on hardware through the phone's `SERVICE_DISCOVERY_REQUEST`;
the phone then rejects our `SERVICE_DISCOVERY_RESPONSE` and drops the link. No
video/touch is wired to the car yet.

The tiles on the car home screen are unchanged: "Phone screen" and "Settings"
work, "Android Auto" launches the AA receiver (and currently ends in the
rejection above rather than showing AA).

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

5. **Head-unit-server automation, low priority (owner said leave for later)**:
   PR #14 made the accessibility service tap whichever of "Start"/"Stop
   head unit server" the overflow menu shows, then reopen CarAALink. The
   reopen works on-device. But the owner wants *detection*, not a toggle:
   if the server is already running, leave it running (just close the menu
   and return to CarAALink). Right now it taps "Stop" and turns it off. The
   fix is in `HeadUnitServerAccessibilityService.onAccessibilityEvent`:
   when `stopNode` is found, press back and `returnToCarAALink()` instead of
   clicking it.

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
