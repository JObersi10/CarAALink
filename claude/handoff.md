# Handoff

## LATEST (2026-11-09) -- see chatgpt-status.md's 2026-11-09 section for full detail

PR #21, an external contribution from alimorgaan (real driver, Samsung A25,
2026 Geely Emgrand/CarbitLink head unit), root-caused and fixed the
AA-drops-after-~1-minute bug: `AV_MEDIA_ACK`'s count field was sending a
running total instead of a per-ack count, and AA kills any session once
that counter passes 4096. Also fixed in the same PR: head-unit-server
liveness check (bind instead of connect -- the old connect-based check
broke the *next* session since AA's dev server never answers again after
a closed connection), a `NetworkOnMainThreadException` silently killing
graceful shutdown, async `MediaCodec` video decode, `NAV_FOCUS_REQUEST`
(maps was losing guidance after 15s unanswered), input `BindingRequest`,
and a sun-position-based night_mode sensor.

Merged #21 into `main`, then merged `aa-post-v1` (PR #19, closed without
merging in favor of this) on top in PR #22, resolving the real overlap
(both branches had independently built a shutdown-handshake fix and a
starvation-check tweak -- kept whichever side's version was actually
better each time, see PR #22's description for specifics). PR #22 is now
merged into `main`.

**New fix found and shipped in the same PR, from a live force-quit
repro:** `AdbTransport.sendZeroKick()` retried 50 times on a dead
accessory fd, then silently returned on total failure. `start()` then
entered `readLoop()` anyway, which blocks forever on
`UsbAccessoryConnection`'s raw `FileInputStream` (no read timeout) --
`stop()` never got called, so `scheduleTransportReconnect()` never ran
again, and the only way out was force-quitting the app. Confirmed via a
live capture: previously the log just stopped dead after "zero kick
attempt 50 failed"; after the fix, it correctly stops and schedules the
next bounded reconnect attempt instead.

**Also settled for real this session (long disputed):** the EIO/ENODEV
give-up loop is confirmed HARDWARE-level, not AA/gearhead and not
anything in either PR. A full `logcat -b all` dump at the exact EIO
moment shows the phone's own USB-C port controller
(`max77775-usbc`) emitting a `remove@.../typec/port0/port0-partner`
uevent -- a physical disconnect at the port, reported directly by the
phone's hardware. Reproduced on bare `main` (PR #21 only, no aa-post-v1
changes) too, so it's independent of both PRs. No code fix applies;
cable/connector/mounting is the next thing to check.

**New lead, not yet actioned:** pulled the car's own on-device logs via
USB stick (BAIC U5 Plus International head unit, Qinggan platform,
`qcom/msm8953_32` SoC, Android 8.1.0). The real EasyConnection/CarbitLink
native daemon (`EasyConn`/`ECSDK`, transport `EC_TRANSPORT_ANDORID_USB_AOA`
-- the same AOA path we use) logs
`[ECHIDTouchDevice]permitSendHIDTouchEvent disabled status` on every
single touch event captured during today's session (299/299). Plausible
cause of the reported erratic touch/swipe-wrong-direction symptom if it's
inconsistently dropping touch-move samples. This is the car's own closed
OEM middleware, not our code -- next step would be decompiling/tracing
what normally flips that flag to enabled for the real dongle.

**Also found, not yet fixed:** the "Exit" button on CarAALink's own home
tile screen doesn't work. `CarDisplay.kt`'s home presentation is wired
`onExit = ::showHome` (line ~199) -- i.e. pressing exit *while already on
home* just re-shows home, a no-op. The doc comment on
`CarHomePresentation`'s own `onExit` param says it should act like the
phone-back action ("same phone-back action as Mirror's button... backs
out on the phone side rather than tearing down the whole USB session"),
same as `AaCarPresentation`/`MirrorPresentation`'s exit buttons do -- but
it's never wired to that. Next step: wire home's exit button to an actual
phone-back call instead of `showHome`.

## Previous (2026-11-06) -- see chatgpt-status.md's 2026-11-06 sections for full detail

Shipped and pushed to `aa-post-v1` this round (all on real CarbitLink-
dongle and real-head-unit hardware): mirror frame overflow no longer
corrupts the H.264 reference chain or replays stale frames
(`CarSession.kt`); mirror encode target raised to 60fps to match the
car's measured ~75-79/s pull rate (repeat-frame ratio measured dropping
from ~56% to ~17% on a real head unit); AA margin clamp to prevent
gearhead's own confirmed `VirtualDisplay width must be positive` crash,
corrected after decompiling `andreknieriem/open-headunit` (real
open-source AA head unit, GPL-3.0) to confirm margin is a total, not
per-side, subtraction; AA audio start-of-playback choppiness fixed
(`AaAudioPlayer.kt` now prebuffers ~120ms before the first `play()`).
Also added `docs/CarbitLink-Protocol.md`, a consolidated,
evidence-sourced writeup of everything confirmed about CarbitLink's wire
protocol so far.

**Two real things still open, next session should pick these up first:**
1. EIO -> ENODEV x50 "give up" loop (owner sees 5 repeated car-side
   splash cycles, then it stops) -- owner believes this is actually
   Android Auto (gearhead) causing it, not a true USB-level drop; not
   settled either way. Needs a logcat capture spanning both
   `car-session` and `GH.`/gearhead tags at the *exact* moment of one of
   these events.
2. The "force-stop the app to get the USB chooser dialog back" bug --
   the known fix for this exact symptom class (`UsbAccessoryConnection`
   closing its own fd) is in place and traced correct on inspection, but
   not proven against a live regression.

**Tooling note:** `adb shell "nohup ... &"` for an unattended on-device
logcat capture was confirmed TWICE to silently die a few minutes in,
well before the event it was meant to catch -- no error, just a stale
file. Use `setsid sh -c '...' </dev/null >/dev/null 2>&1 &` instead, and
verify with a fresh `ps -A | grep logcat` close to the end of a test
session, not just at the start.

Last rewritten: 2026-11-03. PR #18 (v1.0.0) is merged into `main`. PR #19
is open against `main` from `aa-post-v1` (driving-status fix, decoder
diagnostics, video focus handling). **ChatGPT and Claude have been
trading off on this investigation across several passes -- `chatgpt-
status.md` at the repo root is the detailed, cited log of that back-and-
forth (PhoneAuto/LIVI/Open-Headunit source and capture comparisons,
panel-fit resolution change, density analysis) and is the first thing to
read, in full, before continuing.** This file stays the higher-level
summary. See `roadmap.md` for full cited CarbitLink detail and
`aa-receiver-notes.md` for cited AA protocol.

## LATEST (2026-11-03, round 9) -- real CarbitLink app decompiled, confirmed it uses software libx264 not hardware MediaCodec; x264 encode path built and installed, NOT YET TESTED

Full detail, in order, is in `chatgpt-status.md`'s round-9 sections --
this is the condensed version.

**Landed and confirmed good:**
- Margin-aware density (round 8) -- tested live, user confirmed density
  142 "looked way better" than the old 213. Keep.
- Starvation-fallback fix in `CarSession.kt` (`lastSentFrame` instead of
  `lastKeyFrame` on the 80ms no-frame-ready fallback) -- measured
  47%->86% fresh-frame ratio improvement across two real sessions. Real
  fix, but did NOT change the reported blockiness on its own.
- EIO auto-reconnect in `UsbLinkForegroundService.kt`
  (`scheduleTransportReconnect`, capped/backed-off retry on a transport
  I/O failure, never on a deliberate stop). Works, but a live log showed
  the retry's own `openAccessory()` call throws `IllegalArgumentException:
  no accessory attached` every time -- recovery actually comes from
  Android's own `ACTION_USB_ACCESSORY_ATTACHED` broadcast reconnecting,
  not this retry. **Open question, unresolved:** does the app already
  auto-restart the service on that attach broadcast independent of this
  patch? If yes this whole addition may be dead weight and the real
  "have to manually restart" cause is still unexplained -- check the
  manifest/receiver wiring before trusting this fix did anything.
- H.264 level mismatch fix in `CarVideoEncoder.kt` (`levelFor()`,
  computes the right AVC level instead of hardcoding Level 3.1 when
  CarbitLink's own `CONFIG_CAPTURE` requests 1920x720, which needs at
  least Level 4). Confirmed real (CarbitLink requests exactly 1920x720,
  5400 macroblocks, over Level 3.1's 3600 spec max), but user tested and
  confirmed it did NOT fix the blockiness either.

**The actual likely root cause, found this round:** decompiled the real
CarbitLink companion app (`CarbitLink-EasyConnection_6.5.1_APKPure.apk`
at repo root -- NOT `com.tarplay.phonescreen`, that's PhoneAuto itself,
a mixup that cost real time this round, corrected). `strings` on its
`lib/arm64-v8a/libJNICodec.so` shows a custom FFmpeg build with
`--enable-libx264 --enable-encoder='h264,libx264'` and an internal
banner `(knNx264 [%s]:` -- **they run software libx264, not the phone's
hardware MediaCodec encoder, for the Mirror channel.** Zero MediaCodec
references in that library. Software x264 at a given bitrate produces
meaningfully better quality than typical phone hardware H.264 encoders
under bitrate pressure -- the leading explanation for blockiness that
survived both prior fixes.

**Built this round, in the working tree, NOT YET TESTED in the car:**
- Added `dev.ffmpegkit-maintained:ffmpeg-kit-full-gpl:8.1.7` to
  `app/build.gradle.kts` (Maven Central, prebuilt libx264 for arm64-v8a).
  **GPL-3.0 -- user explicitly accepted this makes CarAALink itself GPL
  if ever distributed.** Confirmed resolves and builds; `stripDebugDebugSymbols`
  warns it can't strip `libavcodec.so`/`libffmpegkit.so`/etc., which is
  expected and just means they ship unstripped (bigger APK, not a bug).
- New `app/src/main/kotlin/dev/caraalink/video/VideoEncoder.kt` --
  shared interface (`width`, `height`, `inputSurface`, `start()`,
  `requestKeyFrame()`, `release()`) both encoders now implement.
- New `app/src/main/kotlin/dev/caraalink/video/CarVideoEncoderX264.kt`
  -- FFmpegKit has no live-Surface encode API like MediaCodec's, so this
  owns an `ImageReader` (YUV_420_888), converts each frame to packed
  I420 respecting per-plane row/pixel stride, pipes it into a live
  `ffmpeg -f rawvideo ... -c:v libx264 ... -f h264` process over a named
  FIFO (`Os.mkfifo`), and parses the Annex-B H.264 output back into
  access units (`AnnexBParser`, NAL type 7/8 = config, 5 = key) to call
  the same `onFrame` callback shape `CarVideoEncoder` does.
  `requestKeyFrame()` is a deliberate no-op (no live force-IDR control
  over the pipe; `-g fps` already guarantees a keyframe at least once a
  second, same as `CarVideoEncoder`'s `KEY_I_FRAME_INTERVAL = 1`).
- `CarVideoEncoder.kt` and `CarSession.kt` updated to implement/use the
  shared interface; `CarSession.kt`'s `onCaptureConfig` now picks
  `CarVideoEncoderX264` vs `CarVideoEncoder` based on
  `AppSettings.videoUseX264(context)`.
- New settings toggle, `root_preferences.xml` + `AppSettings.kt`:
  **"Software H.264 encode (x264)"**, key `video_use_x264`, **default
  true** (so it's actually exercised without the user needing to find
  the toggle first) -- flip off to revert to the known-working hardware
  `CarVideoEncoder` path if x264 causes new problems (CPU load, FIFO/
  process issues, phone heat) instead of fixing the old one.
- Build succeeded, installed to the phone. **The actual live test --
  does it fix the blockiness, does the FIFO pipeline hold up, what does
  CPU/heat look like -- has not happened yet.** This is the single most
  important next step for whoever picks this up.

**Biggest risk in the new code, unverified:** the YUV_420_888 ->
packed-I420 conversion in `CarVideoEncoderX264.packI420()`/`copyPlane()`.
Wrong row/pixel stride handling is the single most common cause of
garbled/green video in any Android YUV capture pipeline, and this has
never been run on a real device. If the car screen shows noise/green/
corruption (as opposed to blockiness specifically), this conversion is
the first place to check, not the x264 encode settings.

**Also unverified:** FIFO lifecycle under session teardown/CONFIG_CAPTURE
rebuild (same resolution-change-reopens-encoder path that round 7's
"CONFIG_CAPTURE repeated with no change" fix was protecting). If the
car's head unit keeps re-sending CONFIG_CAPTURE like it does today,
confirm `CarVideoEncoderX264.release()` actually unblocks both FIFO
threads cleanly each time rather than leaking a blocked thread per
rebuild.

## LATEST (2026-11-03, round 8) -- ChatGPT's PhoneAuto/LIVI/OHA comparison, panel-fit resolution, margin-aware density (untested)

Full details and exact code/log citations are in `chatgpt-status.md`
(much longer than what fits here). Short version:

**Landed and pushed to `aa-post-v1` (PR #19), not yet live-verified:**
- Auto resolution now panel-fits: for a known ultrawide car panel
  (`carAspect >= 2.5`), picks `1280x720` + a 240px height margin (1280x480
  visible content) instead of the old default `1920x1080`, matching the
  largest mode PhoneAuto's own decompiled resolution picker offers for a
  1920x720 panel (ChatGPT's decompile of `PhoneAuto-beta-2027-01-19.apk`,
  `MainActivity.f()`/`C0056bq.java`). Custom resolution and the existing
  "Force 720p" switch both still override this.
- Decoder diagnostics (`AaVideoDecoder`/`PerfStats`, landed PR #19) made
  visible-by-default: the `AA decode: recv=... sub=... miss=... exc=...
  rend=...` line now shows after the first access unit even when
  miss/exc are both zero, so "zero" can be told apart from "never ran."
- Video focus handling (`AaSessionProbe`, landed PR #19): `VideoFocusRequest`
  (was completely unhandled) now parsed/logged and answered with the
  protocol-documented echo-back response, not a hardcoded PROJECTED.
  Still NOT wired into the AA watchdog/display behavior -- a NATIVE focus
  handoff means the phone intentionally stops sending video, and that's
  not yet told to the watchdog's stall detector. Natural next step.
- **Margin-aware density (just made, build triggered, NOT YET
  installed/tested as of this entry):** `AaServiceDiscovery.configFromSettings()`'s
  density formula used to be computed from the full negotiated resolution
  (e.g. 1280x720), completely blind to the autocrop margin -- same density
  whether heightMargin was 0 or 240. Now computed from the post-margin
  CONTENT region instead (e.g. 1280x480), same formula/clamp, following
  the same principle Open Headunit's own sizing code uses (derive density
  from the VISIBLE pixel dimensions, not the raw buffer) -- NOT copying
  OHA's actual 730dp number, which is an unrelated portrait-only policy.
  **Important:** `roadmap.md` already has a confirmed-twice-live finding
  that density behaves OPPOSITE the textbook formula in this app's real
  rendering (higher density -> smaller UI on screen). So this change is
  NOT a confirmed fix -- it needs a real before/after comparison on the
  actual 1920x720 car screen (same scene, same session length, log the
  `AA config: resolution=... density=... widthMargin=... heightMargin=...`
  line and a photo/clear note of UI scale each time) before deciding to
  keep it. Revert if it looks worse.

**New diagnostic evidence from Open Headunit (a THIRD reference AA head
unit app, alongside PhoneAuto and LIVI) + a synchronized gearhead logcat,
2026-11-03, phone self-mode only (NOT a car test):**
- Confirms OHA's own sizing method: derive density from
  `visible_px_width * 160 / target_dp_width`, where visible_px_width is
  POST-margin. Its own target (730dp) is a portrait-only internal policy,
  not a protocol value -- do not copy the number, only the method (now
  applied above, margin-awareness only, no borrowed constant).
- Gearhead's own lifecycle log (`GH.ConnLoggerV2`) through a user-exit
  shows clean, fast teardown (`PROJECTION_ENDED_BYEBYE_BY_CAR` ->
  `FRAMER_READ_END_OF_STREAM` -> `SESSION_STOPPED`, all within ~4s) when
  OHA sends a proper BYEBYE first -- a different, cleaner shutdown pattern
  than CarAALink's own EOF-without-BYEBYE cases seen earlier. A low-memory
  kill of gearhead's own `:watchdog` process happened ~4s after that
  SESSION_STOPPED in this capture -- noted as a correlated-in-time
  observation only, not proven causally connected to anything.
- Pixelation measurement from a real CarAALink session log (protocol
  sniffer on): measured H.264 payload bitrate directly from logged
  `MEDIA_DATA` message sizes -- averaged ~1.6-2.3 Mbit/s across three runs,
  with sustained 5-second windows as low as ~0.58-0.93 Mbit/s. Direct
  evidence of genuinely low bitrate stretches in the real received stream
  (not speculation). That same capture also measured the AA frame rate
  setting (60fps) as a second real receiver-settable protocol input
  (distinct from bitrate, which still has no field) worth a controlled
  30-vs-60fps A/B test at the same resolution.

**Open follow-ups, in rough priority order (see `chatgpt-status.md`'s own
"Remaining open items" list for the full version):**
1. Finish testing the margin-aware density change (in progress as of this
   entry -- build was running when this was written).
2. Matched live comparison: new Auto (1280x720) vs. the old 1080p default,
   recording bitrate, decoder miss/exc counters, and visual result.
3. 30fps vs 60fps A/B at the same resolution, same reasoning as above.
4. Wire video-focus state into the AA watchdog so a NATIVE/NATIVE_TRANSIENT
   handoff doesn't get mistaken for a stall.
5. Capture a synchronized CarAALink + gearhead log through a NATURAL EOF
   (not a user exit) to investigate the still-unexplained stale-USB-
   handshake lead from round 7.
6. `AudioTrack: restoreTrack_l` churn -- still never revisited.
7. The `VirtualDisplay width must be positive` gearhead crash -- still
   never cross-referenced against our own log.
8. Steering-wheel voice-command button -- still never successfully
   captured.
9. Confirm live whether round 6/7's `onTaskRemoved`/reconnect-race/grace-
   delay fixes actually eliminate the "manually toggle head unit server"
   complaint for good.

**Build environment note:** the SABRENT external drive (holds the only
JDK and the Android SDK on this Mac) gets unmounted/disconnected between
sessions somewhat often this week -- always `ls /Volumes` before assuming
a local build will work, and ask the owner to reconnect it rather than
guessing it's there.

## LATEST (2026-11-02, round 7) -- driving-status fix + a real gearhead-side lead from ChatGPT

**Fixed:** AA was always in its driving-restricted UI mode ("always on
drive"), never "parked". Root cause: we advertise and ack the
`DrivingStatus` sensor subscription but never actually pushed a
`SensorEventIndication` (message `32771`) event -- AA appears to fail safe
to fully restricted when a sensor it subscribed to never reports in.
CarbitLink's own protocol gives us no real speed/gear/parking-brake data,
so this isn't real detection -- it pushes one `DrivingStatus{status=0}`
(UNRESTRICTED bitmask) event right after acking the subscription, which at
least stops the false "always restricted" default. Confirmed from
mrmees/open-android-auto's `docs/channels/sensor.md` (GPL-3.0, facts only).
Not yet live-tested.

**New lead, not yet actionable** -- ChatGPT analyzed an unfiltered phone-side
logcat (comparing gearhead's behavior with the *PhoneAuto* reference app,
not CarAALink) and found: gearhead processed a `USB_ACCESSORY_HANDSHAKE`
dated ~11m35s stale (matching timestamps from our own earlier capture's very
first handshake) **while simultaneously logging `USB_PORT_DISCONNECTED`** --
and still proceeded through SSL/SDP negotiation and started a real session
off that stale handshake. That session ran ~3m14s then hit the same
`FRAMER_READ_END_OF_STREAM` EOF pattern already documented elsewhere in this
file, followed by several quick (4-15s) failed retries. This is gearhead's
own USB negotiation state machine doing something out-of-order, not
anything CarAALink sent -- not fixable from our side, but a real, concrete
lead for *why* sessions die the way they do.

**Next step for whoever picks this up:** get a capture of CarAALink's own
log (not just gearhead's) spanning the SAME window as a gearhead-side
capture, long enough to catch a natural EOF + the retries after it, so the
two can be correlated minute-for-minute. Every capture so far has had one
side or the other missing the key moment.

## LATEST (2026-11-02, round 6) -- another marathon real-car session, mostly real fixes this time

**Confirmed fixed and live-verified (not just shipped):**
- **Touch misalignment** (round 5's open item #2, "press a bit higher") --
  real root cause found via mrmees/open-android-auto's
  `docs/video-resolution.md`: AA expects touch coordinates already remapped
  into the CONTENT region's own space (`0..contentWidth, 0..contentHeight`),
  not the full margin-padded frame. `AaCarPresentation.kt`'s touch listener
  and `AaServiceDiscovery.kt`'s advertised touch resolution were both
  sending/declaring full-frame coordinates. Fixed both; owner confirmed live
  ("touch is good").
- **Video artifacting/glitching mid-AA-session** -- the CarbitLink dongle
  resends `CONFIG_CAPTURE` on its own periodically (confirmed live, twice in
  one unbroken session, ~9s apart, nothing touched on either end).
  `CarSession.onCaptureConfig` was unconditionally tearing down and
  rebuilding the video encoder/display on every one of those, stomping
  AA's own actively-streaming surface. Now skips the rebuild when nothing
  actually changed. Confirmed via log: `"CONFIG_CAPTURE repeated with no
  change -- not rebuilding encoder"` fired cleanly, no more glitch.
- **AA watchdog restart-hammering loop** -- fixed stall threshold was firing
  faster than gearhead could actually recover (confirmed: 42 straight
  seconds of "attempt 1/8, killed, attempt 1/8, killed" in one log). Now
  backs off exponentially (`AA_WATCHDOG_STALL_MS` doubles per consecutive
  restart, capped at 60s, resets once a frame actually arrives). Confirmed
  via log: one restart, then the probe's own retry reached attempt 2 and
  succeeded instead of being killed again.

**Fixed, shipped, needs more live testing (each targets a real logged event, not a guess):**
- A race where two concurrent `CarSession.openAndroidAuto()` calls could run
  at once -- confirmed from a real capture: one call's accessibility-toggle
  automation tapped "Stop head unit server" on a session another call had
  *just* started a second earlier, killing it. Now guarded with a lock
  (`openingAa`).
- A 2-second grace delay before reconnecting when gearhead's dev-mode
  socket (127.0.0.1:5277) is already listening -- confirmed gearhead's own
  session teardown (releasing automotive projection, disconnecting its
  `GearheadCarClientConnector`, removing virtual displays) takes a couple
  of seconds, and reconnecting faster than that used to race it.
- The accessibility automation that taps AA's own "Start/Stop head unit
  server" now retries the tap up to twice -- confirmed live the overflow
  menu can read stale "on" (session already dead) and the first tap turns
  it fully off instead of starting it; the second tap then correctly finds
  "off" and starts it for real.
- `UsbLinkForegroundService` now overrides `onTaskRemoved()`. There was
  **no explicit "Disconnect"/"Exit" control anywhere in the app** -- the
  only way to end a session was unplugging USB. Swiping the app away from
  recents did nothing: the foreground service (and the whole USB/AA
  session) just kept running invisibly in the background. This is the
  leading suspect for "CarAALink won't close cleanly" / needing the manual
  head-unit-server toggle after swiping away, but not yet confirmed live
  (the one capture done so far was an unplug/replug, a different path that
  already looked fine).

**New settings added (not bugs, feature requests):**
- "Jump straight to Android Auto" (`aa_auto_launch`) -- existed as a dead
  getter, read nowhere. Now actually wired into `CarSession.onCaptureConfig`.
- "Downscale resolution" (`video_downscale_720p`) -- separate from Custom
  resolution; one-tap "send AA 720p instead of 1080p" for when AA's own
  adaptive bitrate makes 1080p blocky. See the bitrate finding below for why
  this is the *only* lever that exists.

**Closed for good, don't re-investigate without new evidence:**
- **AA video bitrate ("blockiness")** -- triple-confirmed from primary
  sources (AA's actual `VideoConfiguration` proto fields, two independent
  AI cross-checks of mrmees/open-android-auto's `channels/video.md`,
  `interactions/04-channel-lifecycle.md`, and `phenotype-flags.md`): there
  is NO bitrate field in the protocol at all, no documented link between
  ACK timing/pacing and encoder rate, and gearhead's internal adaptive-
  encoder flags are explicitly phone-internal/server-side (set by Google,
  cached on the phone), not head-unit-settable or exposed via ADB/Developer
  Mode. Resolution downscale (above) is the only real lever. Do not chase
  "trick gearhead via ACK pacing" or "hidden phone-side bitrate flag" again
  without an actual cited mechanism -- both were proposed by an AI with
  zero citation and don't survive a check against the primary docs.
- USB audio routing -- confirmed not in use anywhere in `AaAudioPlayer.kt`
  (only `TYPE_BLUETOOTH_A2DP`/`TYPE_BUILTIN_SPEAKER` are ever targeted).
- In-app logcat-of-gearhead-on-demand (feature request) -- impossible by
  Android's own design, `READ_LOGS` is signature/system-only; only a
  PC-connected `adb logcat` session can ever see another app's logcat.

**Still open, not touched this round:**
1. `AudioTrack: restoreTrack_l` churn (round 5's lead #3) -- never
   revisited.
2. The gearhead `VirtualDisplay width must be positive` crash (see "biggest
   lead" section below) -- never cross-referenced against our own log.
3. Steering wheel button presses (voice-command button specifically) --
   one capture attempt caught nothing but a routine heartbeat
   (`REQ_HEARTBEAT`/`0x0040`) in the whole window; never retried with a
   cleaner capture window.
4. Whether the `onTaskRemoved()` fix above actually fixes "still have to
   manually toggle head unit server" -- next session's first job.

## LATEST (2026-11-02, later same day) -- marathon real-car session, round 5

See `roadmap.md`'s "Session findings (round 5)" for the full list with
evidence. Current HEAD on `aa-post-v1` at end of this round: `8225d8d`
(plus a docs-only commit after it). Short version of what to pick up next:

**Confirmed fixed, real crash/mystery finally explained:**
- The app-crashing uncaught `InterruptedException` (stop() during retry sleep).
- The long-standing ~30s periodic AA disconnect -- was a self-inflicted
  `maxMessages=2000` cap hitting mid-stream, not a phone/protocol mystery.

**Shipped, NOT yet live-verified (test these first):**
- Margin-based video cropping (`AaCarPresentation`) -- the one test session
  since had `autoCrop=false`, so this literally hasn't run yet.
- `max_unacked=10` for video (was wrongly 1, audio's own value).
- Audio routing fixes (explicit device always picked, never unconstrained).
- Settings-change reconnect grace delay (1.5s before reconnecting).
- Proactive PING_REQUEST heartbeat (kept, doc-supported, but probably not
  the actual fix for the disconnect mystery -- the maxMessages fix is).

**Next things to investigate, no fix attempted yet (owner explicitly asked
to stop guessing without evidence after a few fixes turned out wrong or
incomplete this session):**
1. **AA exit button** -- zero `SHUTDOWN_REQUEST` ever observed, full
   session, every test. Still completely open.
2. **Touch misalignment on the real car** ("press a bit higher than where
   you're tapping") -- confirmed NOT caused by the margin-crop fix (that
   test had margin=0,0). Root cause unknown.
3. **`AudioTrack: restoreTrack_l(...): dead IAudioTrack ... creating a new
   one`** firing ~10x in under a second in one real log -- strong lead for
   audio choppiness, but why the native track keeps dying isn't found yet.
4. **"AA recovery" feature request** (owner's, explicit ask, references
   the PhoneAuto-beta reference app's behavior): exiting the app entirely
   and reopening it should reconnect to AA without needing to manually
   restart head-unit-server first. Not investigated -- likely an app
   process/lifecycle question (does a fresh process detect "head unit
   server already up, try reconnecting"?), not an AA protocol feature.
   Checked `01-transport-setup.md` for a lead -- nothing relevant, that
   doc covers WiFi-based AA setup, not this project's USB path.
5. Video blockiness clears up "after a while" per the owner -- plausible
   vendor hardware-encoder adaptive-bitrate behavior
   (`vq-bitrate-phaseout` seen in a real CCodec log), may not be ours to
   fix from the MediaCodec config side.
6. No-accessibility-permission case: tapping "Start Head Unit Server"
   silently just opens the AA app generically with no explanation --
   worth a clearer prompt, not implemented.

**Research note:** `mrmees/open-android-auto`'s docs (GPL-3.0, cited
throughout this round, never bulk-copied) were the source of two of this
round's real, confirmed fixes (margin cropping, video `max_unacked`) --
worth checking there FIRST before guessing on future AA protocol-shaped
bugs, same pattern, before spending time on log-based speculation.

## BIGGEST LEAD FOR NEXT SESSION: logcat the real gearhead app directly

Raw `adb logcat` on the phone (not our app's own log) caught gearhead
itself crashing, twice, in one short test:
1. `IllegalArgumentException: Virtual display width must be positive` in
   its own `VirtualDisplayConfig$Builder` -- a bad resolution/margin/
   density value WE sent. Need to cross-reference the exact timestamp
   against our own app's log from the same window to find which value.
2. `IllegalStateException: Already connected` in
   `DeveloperHeadUnitNetworkService` -- **very likely the actual reason
   "have to restart head unit server" has been happening all session.**
   This is gearhead's own component crashing when something reconnects
   while it still thinks a session is live, not a timing/race issue the
   1.5s grace delay can fix. Need to find which of our own actions
   triggers this (settings reconnect? watchdog restart? the accessibility
   toggle itself?) and avoid doing that specific thing, rather than adding
   more delay.

Start the next session by logcatting gearhead directly
(`adb logcat -v threadtime`, filter for `GH.` tags afterward) during a
normal test, alongside our own app's log -- this found two real crashes in
minutes that our own log alone never would have surfaced.

## LATEST (2026-11-02, PR #18 in progress) -- headless harness, margin autodetect, bridge roadmap correction

Same PR #18 session, same day. Picked up headless-testing-harness work
(the owner wanted it so margin-autodetect and the decode-thread fix could
be checked without a live car) plus a real finding while re-checking the
"AA-to-CarbitLink bridge" roadmap item.

- **`MirrorStream` interface extracted** (`MirrorChannels.kt`):
  `AdbTransport.Stream` now implements it; `MirrorHost`/
  `AdbTransport.StreamHandler`/`MirrorControlChannel`/`MirrorDataChannel`/
  `PxcChannel`/`CarSession` all take the interface instead of the concrete
  class. No behavior change -- purely the testability seam
  `roadmap.md`'s headless-harness section called for.
- **Headless tests added**, no device/USB needed: `FakeStream`/
  `FakeMirrorHost` + `MirrorControlChannelTest`/`MirrorDataChannelTest`
  (CONFIG_CAPTURE, SCREEN_EVENT touch/key, DATA_NEXT frame pull, unknown-
  type refusal, all real wire bytes through the real channel classes) and
  `AaServiceDiscoveryTest` for the new margin math below. Added
  `mockito-core` as a test-only dep (JVM mocking of `Context` for
  `DiagnosticLogger`, no Robolectric).
- **Margin autodetect shipped**, replacing the old manual "Test
  height_margin" debug slider: `AaServiceDiscovery.autoMargin()` computes
  `width_margin`/`height_margin` from the car's real screen size
  (`CarScreenInfo` now also stores width/height, not just
  portrait/landscape) vs. the chosen AA resolution's aspect ratio.
  Gated by Settings > Android Auto > "Auto-crop to car screen shape"
  (default on). **Still open:** whether AA's margin field is a per-side or
  total inset -- unchanged from before, still needs a live test; see the
  new "SHIPPED" note in `roadmap.md`'s aspect-ratio section for exactly
  what to look for and how to fix it if the direction's wrong.
- **Roadmap correction on the "AA-to-CarbitLink bridge" item**: it was
  filed as "not scoped, not started," which is wrong -- re-reading
  `CarDisplay`/`CarSession` end to end (not just the class names) shows
  AA content already renders onto the *same* virtual display/encoder that
  feeds the real CarbitLink wire to the head unit, same as Home/Mirror
  mode. There's one encoder per car connection, not three. This was true
  since AA support first landed; the roadmap entry was simply never
  updated to say so. See `roadmap.md`'s "Future ideas" section, same
  bullet, for the full correction and what (if anything) is still a real
  unscoped optimization (NAL passthrough, skipping the decode+re-encode
  step -- only worth it if the perf HUD shows it matters).
- Didn't touch the AA bitrate idea from the live-log complaint ("maybe cut
  down video bitrate on AA?") -- checked `AaServiceDiscovery.build()`'s
  `VideoConfiguration` fields and there's no bitrate field AA exposes to
  the head unit at all (only resolution/frame_rate/margins/density), so
  there's no protocol-level knob to add; the existing resolution/frame-rate
  settings are the only real levers, and the decode-thread fix from the
  previous entry is still the main lag fix in flight.
- Pushed to `aa-post-v1`; CI run started (background watch), not confirmed
  green at write-time -- check before telling the owner it's ready to test.

## LATEST (2026-11-02, PR #18 in progress) -- live-log diagnosis, decode-thread fix, aspect-ratio research

PR #17 merged. Picked up PR #18 (`aa-post-v1` branch) with a real WhatsApp-
exported device log (`caraalink-20261102-070408.log`) from the owner's own
test of the merged build, plus continued aspect-ratio research sparked by
`mrmees/open-android-auto`'s docs.

- **Diagnosed the "choppy video + delayed touch feedback" complaint from
  the log, not a guess.** `AaSessionProbe`'s single message-receive thread
  -- the same thread replying to every ping/control message -- was also
  doing `AaVideoDecoder.feed()` and `AaAudioPlayer.feed()` inline,
  synchronously, per frame. Any decode/render stall there delayed every
  other channel too. The same log shows the AA session lasting ~42s before
  `SocketException: Socket closed` from the other end -- consistent with
  (not proven to be caused by) this same stall plausibly missing whatever
  gearhead's own disconnect-on-stall logic watches. **Fixed**: both now run
  on a dedicated `mediaExecutor` thread, decoupled from the protocol loop.
  Also fixed a real leak this surfaced: `touchExecutor`/`mediaExecutor`
  were never shut down, and a fresh `AaSessionProbe` is built on every
  reconnect -- during a drop/reconnect cycle this frequent, that's a
  leaked daemon thread pool every ~42s. Now shut down in `stop()`.
- **Confirmed from the same log: this car's panel is genuinely 1920x720
  (2.67:1)**, straight from a real `CONFIG_CAPTURE`. AA has no resolution
  that shape (only 16:9-family), so "doesn't fill the screen" is real,
  expected, protocol-level behavior -- not fixable by picking a different
  one of the 9 fixed resolutions. Shipped a real choice instead of
  silently accepting one tradeoff: Settings > Android Auto > "Car screen
  fill" -- Fit (default, letterbox/pillarbox, nothing cropped) or Fill
  (scales to cover, clips overflow).
- **Aspect-ratio research, corrected mid-session after reading the
  source directly.** First pass (secondhand) concluded margins
  (`width_margin`/`height_margin`) weren't worth pursuing over app-side
  Fit/Fill since they don't reduce encode bandwidth. Reading
  `open-android-auto`'s `docs/video-resolution.md` directly corrected
  that: it says the head unit "crops/overscans the decoded video to show
  only the content area" margins define -- meaning a correctly-sized
  margin would let this app crop to an **exact native 1:1 region** instead
  of scaling at all (sharper than Fit, and unlike Fill doesn't clip real
  AA content, only AA's own intentional blank margin). **Not implemented**:
  whether the margin value is per-side or total isn't stated by either
  source, and guessing wrong would crop into real content -- added a
  debug-only "Test height_margin" setting instead so the owner can set an
  obviously-oversized value on the next live test and report back exactly
  where the real content lands, which settles the question for real.
  Full writeup in `roadmap.md`'s research notes, including the correction
  itself (left visible, not silently rewritten).
- **Started on PR #18's other tracked items**, modestly scoped:
  - `AaReconnectBus` + a `SettingsActivity` preference listener: changing
    video/orientation/DPI settings now auto-reconnects an active AA
    session (confirmed there's no live renegotiation in the protocol).
  - `CarProfiles`: named save/load snapshots of the settings that vary
    per car (screen shape, resolution, DPI, fill mode, bitrate, audio
    route) -- Settings > Android Auto > "Car profiles". Scoped down from
    a full per-car device registry, since CarbitLink has no concept of
    *which* car is connected to key one off.
  - `MirrorChannelsTest`: start of the headless testing harness --
    pure-JVM tests for `CaptureConfig.parse()` (pinned against this car's
    own live-confirmed 1920x720 capture) and `MirrorDataChannel.frame()`.
    `MirrorControlChannel`/`CarSession` still need a `Stream`-like
    interface extracted from `AdbTransport.Stream` before they're
    headlessly testable -- deliberately not rushed, to avoid refactoring
    confirmed-working production code under time pressure.
  - AA-to-CarbitLink bridge: **not started** -- still just the two-design
    writeup in `roadmap.md`.
- **Build/workflow note for next session**: this machine's Gradle home
  (`~/.gradle` -> `/Volumes/SABRENT/gradle-home`) kept being unmounted
  mid-session, breaking local `./gradlew` builds repeatedly. Switched to
  building via GitHub Actions CI (`gh run watch` as a background task,
  `gh run download` for the artifact, handed to the owner as a file to
  sideload themselves) instead of fighting the local build -- do this by
  default going forward rather than retrying local Gradle.

## Earlier (2026-11-02, later still) -- portrait cars, auto-detect, and the post-"real car tile" feature batch

Picked up right after the live verification pass below, on the same
`aa-receiver` branch (PR #17, still open, heading toward v1.0.0). Owner's
own confirmation photo of a real AA session (Maps + a media app) actually
rendering on their BMW's head unit screen came in during this entry --
genuine proof the real-car-tile fix below is working on real hardware, not
just the phone preview.

- **Portrait car screens.** `AaResolution` only had AA's 5 *landscape*
  `VideoCodecResolutionType` values -- the 4 portrait ones (720x1280 ...
  2160x3840) were documented in `AaServiceDiscovery`'s own doc comment but
  never implemented, so a portrait head unit always got a landscape frame
  forced onto it. Added them, plus a "Car screen shape" setting that
  defaults to **auto** -- `CarScreenInfo`, updated from the head unit's own
  `CONFIG_CAPTURE` width/height in `CarSession.onCaptureConfig` (the car
  reports its real screen shape; no need to ask the user). Also fixed the
  advertised touch size to match the actual negotiated resolution instead
  of a hardcoded 1920x720 (a latent mismatch -- touch events are mapped
  into `config.resolution` space, so advertising a different size was
  always wrong, just never surfaced as a visible bug yet).
- **AA-tile tap no longer force-opens Accessibility Settings.**
  `HeadUnitServerLauncher.start()` used to open Accessibility Settings
  every time the tile was tapped and the service wasn't enabled yet --
  disruptive from the car, and presumptuous about what the user wants. Now
  it just posts a notification telling them to open Android Auto
  themselves (tap it -> opens AA); no settings screen forced open.
- **Phone widgets on the car home screen.** Battery + now-playing
  (title/artist), refreshed every 3s in `buildHomeContent()`. Now-playing
  needs notification access (`MediaListenerService`, a
  `NotificationListenerService` that exists only to hold the permission
  `MediaSessionManager.getActiveSessions` requires -- it never reads
  notification content) -- Settings > Appearance > "Now playing on car
  home" opens that system screen.
- **Performance/latency HUD, on the car *and* the phone.** `PerfStats`
  (fps/kbps/dropped-frames/AA-reconnect-count), wired into both the
  CarbitLink mirror encoder and the AA decoder, gated by the existing (but
  previously unwired) "On-screen debug overlay" setting. Shown in
  `AaCarPresentation`/`MirrorPresentation` (car) and `MainActivity`/
  `AaPreviewActivity` (phone) per the explicit ask that it "lowk show that
  info also on the phone."
- **Protocol sniffer, for real this time.** The `debug_verbose_logging`
  setting existed but did nothing; renamed to "Protocol sniffer" and now
  gates a hex preview on both channels' messages. Found and fixed a real
  perf bug while wiring it up: `AaSessionProbe`'s `"MESSAGE on ..."` log
  was firing unconditionally for *every single* video/audio frame through
  `DiagnosticLogger`'s synchronous file write -- quietly throttling decode
  the whole time. Now off by default, hex dump only when the sniffer's on.
- **AA audio routing, the honest version.** CarbitLink's own wire protocol
  still can't carry audio (its `Packet` audio framing is genuinely
  unconfirmed -- see `roadmap.md`'s RE pass 1 findings -- so nothing was
  invented for it, per `claude.md`'s hard rule, even though it was asked
  for). Shipped instead: `AaAudioPlayer` now explicitly steers output to a
  connected Bluetooth A2DP device when BT-preferred, so AA audio reaches
  the car's speakers over the car's own Bluetooth rather than only the
  phone's own speaker.
- **Local crash marker + AA watchdog.** `CrashMarker` persists a one-shot
  "crashed last run" summary (SharedPreferences, no network -- there's no
  backend, so this was always going to be local-only) surfaced as a toast
  on next launch; the stack trace itself was already in the unified log.
  `CarSession` now polls `AaSessionProbe.lastVideoFrameAt` every 5s and
  restarts a frozen-but-still-connected AA session after a 10s stall --
  covers the case `everEstablished`'s own reconnect logic doesn't (no
  actual socket/TLS error, just nothing arriving anymore).
- **Explicitly deferred to the next PR** (opening as PR #18 off this
  branch once this one's close to merging): mid-session resolution/DPI
  change via automatic AA-session teardown+reconnect (true live
  renegotiation is now CONFIRMED impossible -- see `roadmap.md`'s updated
  research notes, citing `mrmees/open-android-auto`'s own protocol docs),
  multi-profile/multi-car management (the most UI-heavy ask, intentionally
  not rushed), and USB bulk-transfer tuning (checked and **not
  applicable** -- the 16 KB read buffer already matches the accessory
  driver's own confirmed bulk size, no headroom available through the AOA
  stream API this app uses).
- Also elaborated (design notes only, not implemented): the AA-to-
  CarbitLink bridge (decode+re-encode through the existing mirror pipeline
  vs. risky direct NAL passthrough) and a headless JVM-testable harness
  for both protocol state machines, replaying this doc's own confirmed
  capture bytes as golden fixtures. Both written up in full in
  `roadmap.md`'s "Future ideas" section.

## Earlier (2026-11-02, late) -- real on-device verification pass

Finally got a stable connection (USB debugging, owner gave the phone
passcode so the session could unlock/test autonomously) and drove the
actual Car Preview / real AA tile path end to end. Verified live:

- **Real AA session confirmed working through the actual Car Preview /
  AA tile path**: version -> TLS -> discovery -> channel-open ->
  video+audio decode, with Google Maps and Spotify/Apple Music genuinely
  rendering and updating. Touch confirmed landing correctly (found via
  `uiautomator dump` that two earlier taps had simply missed the tile's
  real bounds -- not an app bug, a testing-coordinate bug).
- **AA music audio: strong live evidence it now works.** `dumpsys audio`
  showed our `AudioTrack` active, unmuted, correctly formatted
  (48kHz/stereo), continuously fed with zero decode errors, routed to a
  real output device shared with another app's audio stream. Couldn't
  literally hear it through adb -- owner should confirm by ear next time,
  but every available signal says it's playing.
- **Reopen-after-lock black screen: real bug, found and partially fixed.**
  Root cause confirmed live: on reattach we were replaying cached SPS/PPS
  only, but SPS/PPS alone can't produce a picture -- the new decoder needs
  an actual IDR/keyframe, and the stream's own next one (H.264 GOP
  interval) could be arbitrarily far off. Added a NAL scanner that caches
  the last IDR alongside the codec config and replays both. Result: went
  from "black forever" to "one frame renders, then freezes" -- real
  improvement, not a full fix. Later P-frames apparently don't decode
  cleanly against a keyframe replayed out of the live encoder sequence. A
  full fix likely means accepting a brief (bounded) black gap until the
  next real IDR arrives instead, or isn't fully fixable from the head
  unit's side at all -- there's no "request a keyframe" message exposed
  to a head unit in this protocol as far as confirmed.
- Also found and fixed along the way: `CarAALinkApplication` continuously
  mirrors this process's own logcat into the unified log file (pre-existing
  design, not new this session) -- worth knowing when reading logs, lines
  with a nested real logcat timestamp are that, not a second subsystem.

### Earlier in this entry (2026-11-02, before the live pass above)

The single biggest finding of this session: **the actual "Android Auto"
tile on the car home screen (`CarSession.openAndroidAuto()`) was still
calling the old dead-end `AaTlsReceiver`** -- every bit of AA testing the
previous session did went through a separate debug-only preview path
(`AaPreviewActivity`), never the real car tile. Fixed: `openAndroidAuto()`
now starts the real `AaSessionProbe`, and a new `AaCarPresentation` /
`CarDisplay.Mode.AA` show its decoded video + forward touch on the car's
own screen, at whatever resolution `AaServiceDiscovery.configFromSettings()`
negotiates. Also added: a quick 127.0.0.1:5277 liveness check before
starting (triggers the accessibility-driven head-unit-server auto-start if
nothing's listening, instead of just failing), a DPI setting
(auto-detect/manual, fixed later the same session to replicate
`CarDisplay.attach()`'s own formula instead of the phone's screen
density), and removed the redundant back-button overlay (AA has its own
in-app back/exit UI; kept a long-press-only exit-to-home corner). This is
the fix the live pass above actually verified.

### Owner's live-test feedback, same session (from actually running the
### *previous* build, i.e. before the real-car-tile fix above)

Reported, roughly in the order given, candor about what's real vs.
unconfirmed:

- **"It works really well"** overall, but: felt laggy, bitrate/resolution
  seemed to drop; unclear if that's our encode, gearhead's own developer-
  mode throttling (observed all session: video arrives at ~3fps not the
  requested 30fps even when the protocol-level negotiation is correct --
  never root-caused, see "Known open issue" in the previous entry below),
  or something else. **Not investigated further this session -- no live
  capture correlating it to anything on our side.**
- **AA music/media audio didn't play**, but *other* Bluetooth audio
  (unrelated to AA) did. `AaAudioPlayer` (AAC-LC decode -> AudioTrack,
  added this session) was wired in but never confirmed working live before
  this report came in -- can't tell yet whether it's a real bug in that
  decoder/player or audio focus, or whether it's actually fine now and this
  feedback predates it. Needs a live check with the current build.
- **Touch reportedly stopped working** on a reopen after the AA session
  had been closed and reopened. Could easily be the real-car-tile bug
  above (the tile was pointed at the dead `AaTlsReceiver`, so "touch"
  there was never real to begin with) rather than a regression in the
  working `AaSessionProbe.sendTouch()` path -- needs re-testing now that
  the tile is actually wired correctly.
- **Reopening AA after exiting showed a black screen**, with the phone's
  own notification still saying "connected to your vehicle." Same caveat:
  may well be explained by the dead-receiver bug above. `AaPreviewActivity`
  had an analogous black-screen-on-reopen bug this session, root-caused
  (phone sends SPS/PPS once per TCP session, not per attach) and fixed via
  `AaSessionProbe.attachVideoSink()`/`cachedCodecConfig`; the new
  `AaCarPresentation` path reuses `attachVideoSink()` too, so it may
  already be fixed as a side effect, but this is unverified.
- Resolution: owner unsure if what they saw was actually 1080p or 4K --
  `configFromSettings()` (new this session) should make this explicit and
  controllable now (Settings > Video > Resolution), worth confirming it
  actually reads as expected on a live run.

### Still on the list, not started

- **Phone widgets on the car home screen.** New feature ask, not scoped.
- **Roadmap item for later:** making the phone itself act as a CarPlay/AA
  *server* (simulator) for other devices too -- owner explicitly said this
  is a later idea, just record it here so it isn't lost.
- Piping decoded AA video/audio into the actual CarbitLink video path
  (still separate from the on-phone preview and now also separate from the
  car `AaCarPresentation` path -- three different consumers of the same
  decoder/sink architecture now, which is fine, just worth knowing).

## Infra changes this session (2026-11-02), unrelated to the above

- **Unified logging.** Every `DiagnosticLogger` instance (aa-session,
  car-session, usb-sniff, adb-probe, aa-version, aa-tls, app) used to write
  its own separate timestamped file, needing a merge step to read side by
  side. Now they all write into one shared rotating current file (tag as a
  line prefix instead of a filename), so `DeveloperToolsActivity`'s
  "Export latest log" shares that directly -- no more merge step.
- **Quiet shutdown after USB unplug.** `UsbLinkForegroundService` had no
  `USB_ACCESSORY_DETACHED` detection at all before this -- disconnect was
  only noticed indirectly via transport read/write failures. Added an
  explicit detach receiver: 30s grace (cancelled if a new session starts
  in that window), then a clean stop -- service, notification, and the AA
  session's own reconnect loop all actually end instead of only doing so
  if a transport error happened to fire.

## Earlier entry (2026-11-01), AA video/audio pipeline working

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
- Local build note: this machine's Android SDK (`local.properties`'
  `sdk.dir`) and Gradle home live on the external **Sabrent** drive — if
  local builds suddenly fail with a missing SDK/lock-file error, check
  `ls /Volumes` first (needs to show `SABRENT` mounted). Working local
  build command, confirmed 2026-11-02 (the Android-Studio-JBR path
  previously noted here doesn't actually exist on this machine --
  `/usr/bin/java` alone also fails with "Unable to locate a Java
  Runtime"):
  ```
  JAVA_HOME="/Volumes/SABRENT/gradle-home/jdks/eclipse_adoptium-17-aarch64-os_x/jdk-17.0.20+8/Contents/Home" \
  GRADLE_USER_HOME=/Volumes/SABRENT/gradle-home \
  ./gradlew assembleDebug --console=plain
  ```
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
