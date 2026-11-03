# ChatGPT Status: CarAALink findings

Updated: 2026-11-03

## PhoneAuto vs CarAALink video quality

**Finding:** The strongest source-backed explanation for why PhoneAuto looks less blocky is that it offers resolutions sized for the car’s actual display. On the CarbitLink panel reported as 1920×720, PhoneAuto’s largest offered mode is 1280×720. CarAALink’s default offers only 1920×1080.

### PhoneAuto evidence

In the decompiled `MainActivity.f(width, height, fps)` method, PhoneAuto filters its candidate modes against the head unit’s dimensions. For a 1920×720 panel, 1920×1080 is excluded because it is taller than the display; 1280×720 and 800×480 are included.

PhoneAuto then sends the resulting list as repeated `video_configs` entries. Its resolution builder maps a 1280×480 content rectangle to the 1280×720 codec mode and adds a 240-pixel height margin. For the 800×480 mode, the corresponding content rectangle is 800×300, with a 180-pixel height margin.

Relevant decompiled files:

- `PhoneAuto-beta-2027-01-19/sources/com/google/android/projection/sink/ui/MainActivity.java`, method `f`, lines 233–270: candidates are filtered and built from the head-unit dimensions.
- `PhoneAuto-beta-2027-01-19/sources/defpackage/C0056bq.java`, method `e`, lines 17–67: resolution, margins, frame rate, density, and codec fields are set.
- `PhoneAuto-beta-2027-01-19/sources/defpackage/C0085cq.java`, lines 17–32: the response serializes every entry in the video config list.
- APK analyzed: `PhoneAuto-beta-2027-01-19.apk`; JADX output is under `/private/tmp/phoneauto-jadx-20261103`.

**Limit:** This proves PhoneAuto does not offer 1080p for a 1920×720 panel. It does not prove which of its 720p or 480p options Gearhead selects in a particular session; that needs a matching session log or capture.

### CarAALink evidence

CarAALink’s `AaServiceDiscovery.build()` serializes exactly one video config. By default, it is 1920×1080 at 30 fps. The 720p downscale preference defaults to off. Auto-crop defaults on; with the reported 1920×720 panel and FIT mode, CarAALink calculates a 360-pixel height margin, leaving a 1920×720 content region.

That means the default CarAALink content region has **2.25× the pixels** of PhoneAuto’s largest 1280×480 content region for the same screen shape. This is a strong explanation for the visual difference if Gearhead’s available bitrate is similar: it has more active pixels to encode in CarAALink’s default mode. It does not establish a fixed bitrate or a specific Gearhead bitrate algorithm.

Relevant CarAALink files:

- `app/src/main/kotlin/dev/caraalink/aa/AaServiceDiscovery.kt`, lines 181–202: default resolution and frame rate selection.
- Same file, lines 222–230: auto-crop margins and final config.
- Same file, lines 274–282: only one `videoConfig` is serialized.
- `app/src/main/kotlin/dev/caraalink/settings/AppSettings.kt`, lines 125–141: auto-crop defaults on; 720p downscale defaults off.

### Additional receiver-side risk

CarAALink’s `AaVideoDecoder.feed()` waits up to 10 ms for an input buffer, then silently skips the incoming frame if no buffer is available. PhoneAuto queues incoming video buffers and starts dedicated decoder input/output threads. Dropping an H.264 reference frame could cause temporary artifacts, but the source comparison alone does not show this is happening in a real CarAALink session.

Relevant files:

- CarAALink: `app/src/main/kotlin/dev/caraalink/aa/AaVideoDecoder.kt`, lines 32–51.
- PhoneAuto: `sources/defpackage/C0085cq.java`, lines 58–75, and `sources/defpackage/C0303ke.java`, lines 214–230.

## Suggested next work

1. Compare a live CarAALink session with PhoneAuto and record the selected video config, actual frame dimensions, frame sizes/intervals, and decoder input-buffer misses.
2. Consider making CarAALink’s automatic resolution selection respect the reported panel size. For 1920×720, the PhoneAuto-like maximum is 1280×720 with a 240-pixel height margin. Alternatively, investigate offering multiple panel-appropriate configs and handling the selected index; CarAALink currently advertises only one.
3. Instrument decoder input-buffer misses before changing the decoder. If misses occur, add a bounded queue or suitable wait/backpressure behavior and log drops so they can be correlated with visible artifacts.
4. Keep the no-bitrate-field conclusion. PhoneAuto also does not set a constant bitrate in `VideoConfiguration`; the supported difference found here is the resolution options it offers.

## Working tree status at time of writing

- No video source files have been edited in this investigation.
- `app/src/main/kotlin/dev/caraalink/aa/AaSessionProbe.kt` is already modified in the working tree for unrelated post-session reconnect backoff; preserve that change.
- No tests or build were run for this comparison.

## LIVI capture and repository comparison

Capture inspected: `/Users/jobersi10/Downloads/LIVI-log.log` (239 lines, about 22 KB). LIVI repository checked at commit `b81397d9` (shallow clone in `/private/tmp/LIVI-src`).

### What the capture establishes

- The log is from the macOS LIVI app receiving an Android Auto session from the Samsung SM-S938B over USB. The session reaches TLS/protocol 6.1 and remains in `RUNNING` through the end of the capture at 07:37:53.958. There is no USB detach, TLS EOF, or session teardown in this short excerpt.
- LIVI reports `display 1280x720 -> AA tier 1280x720 @112dpi` at 07:37:10.779. Its GStreamer log then reports H.264 decoded by macOS VideoToolbox (`vtdec (hw)`) as NV12 1280x720 at 07:37:11.712, and again at 07:37:39.910. This is direct evidence that this LIVI session is displaying a 720p stream.
- The AV setup on channel 3 selects H.264. The logged `configIdx=0` is the codec selection index in LIVI's code; do not read that log entry as independent proof of a resolution index. The 1280x720 resolution is established by LIVI's config summary and decoded-format log.
- The capture contains no encoded frame byte counts, measured bitrate, or sustained FPS statistics. It cannot establish the phone encoder's bitrate or show whether the bitrate adapts over time.
- LIVI logs repeated `VideoChannel` stop/start events while retaining session ID 5. At 07:37:28.358 and 07:37:53.958, Android Auto requests NATIVE focus; LIVI reports it is responding PROJECTED and raises “host UI requested.” The stream restarts at 07:37:39.538 and 07:37:51.037. These could be display/focus interruptions, but the capture does not prove causation. They are distinct from compression blockiness.
- At startup AppKit warns that `LIVIVideoHost` is an unknown subview. It is not fatal in this capture: LIVI creates the video pipeline and decodes frames afterward. Treat as a separate UI-host warning, not evidence of bad H.264 quality.

### What LIVI's source establishes

- `native/crates/livi-core-proto/src/config.rs` defaults projection to 1280x720 at 60 fps, with DPI auto-selected (`projection_dpi = 0`). The capture confirms 1280x720 and 112 dpi; it does not print the active FPS, so 60 fps is the repository default, not a capture-confirmed runtime value.
- `native/crates/livi-aa-stack/src/config.rs` selects an AA resolution tier from the configured display size. `from_livi()` logs the chosen tier and DPI. `native/crates/livi-aa-stack/src/discovery.rs` places resolution, frame rate, margins, and density in `VideoConfiguration`; it has no bitrate field set there.
- LIVI builds one video resolution configuration per offered codec. In this capture only H.264 is offered. This is not an adaptive bitrate setting.
- LIVI's macOS receive pipeline is `appsrc -> h264parse -> queue (up to 2 s) -> vtdec -> queue (2 decoded buffers, leaky downstream) -> GL upload/shader -> glimagesink`. `vtdec` is the host Mac's hardware decoder; it does not encode or improve the phone's source bitrate. The input queue and leaky decoded-frame queue are relevant receiver-side differences from CarAALink's direct MediaCodec feed and may affect frame loss/latency, but this capture has no drop counters to prove a visual effect.
- `native/crates/livi-aa-stack/src/protocol.rs` sends `max_unacked_frames = 1` for AV media setup, including video. This differs from CarAALink's video value of 10. It is flow control, not a bitrate value, and there is no evidence here that it causes or fixes compression. Keep CarAALink's documented/spec-derived video value unless protocol evidence says otherwise; do not copy LIVI's value as a quality fix.
- `native/crates/livi-aa-stack/src/channels/video.rs` responds PROJECTED even to NATIVE/NATIVE_TRANSIENT focus requests while separately emitting `HostUiRequested`. The log shows this behavior. It may be intentional (a unit test expects it), but should be reviewed as a separate focus/display lifecycle question if LIVI's UI or video restarts are a problem.

### Updated blockiness comparison

The strongest evidence remains resolution: this LIVI capture decodes 1280x720, while CarAALink's default discovery config offers 1920x1080. CarAALink's existing 720p downscale setting should make its codec resolution comparable; if the picture still differs with CarAALink at 1280x720, compare actual encoded frame sizes, frame cadence, and decoder input/output drops in matched runs. No source inspected here shows LIVI pinning a constant bitrate, and this short log does not measure bitrate.

### Durable source references

- LIVI config tier and DPI logic: `native/crates/livi-aa-stack/src/config.rs`, especially `fitting_tier()` (105–125) and `from_livi()` (180–232).
- LIVI protocol video config: `native/crates/livi-aa-stack/src/discovery.rs` (214–260).
- LIVI decoder pipeline: `native/crates/livi-video/src/codec.rs` (170–183).
- LIVI flow-control and focus handling: `native/crates/livi-aa-stack/src/protocol.rs` (976–981) and `native/crates/livi-aa-stack/src/channels/video.rs` (73–96).
- Capture: `/Users/jobersi10/Downloads/LIVI-log.log`, especially lines 79–95, 131–155, 162–170, 206–239.

## Additional LIVI findings and Linux-port note

### Resolution policy caveat

LIVI's logged `display 1280x720` is its configured AA projection display; the log separately reports the Mac host's physical display as 4560x2964 pixels. Do not mistake the projection setting for an automatic measurement of the host monitor or of the CarbitLink panel.

LIVI's `fitting_tier()` unit tests explicitly return 1920x1080 for a configured 1920x720 display when using H.264. It calculates the 16:9 tier's active content as 1920x720 and accepts that tier without upscaling. Therefore LIVI's 1280x720 capture supports the fact that this session was configured to 1280x720; it does not establish that LIVI would choose 720p for the CarbitLink's 1920x720 panel. This differs from PhoneAuto's height-based candidate filtering described above.

Reference: `native/crates/livi-aa-stack/src/config.rs`, `fitting_tier()` and its tests (105–125, 368–373).

### Protocol behaviors worth tracking, not copying as quality fixes

- LIVI returns `PROJECTED` on NATIVE and NATIVE_TRANSIENT video-focus requests, but also raises the Host UI requested event. Its unit test expects this response. The capture shows that behavior and later stream stop/start events, but not causation. If CarAALink ever sees equivalent focus requests, log them alongside stream start/stop before deciding whether its focus response should change.
- LIVI sets `max_unacked_frames=1` in its media config response, including video. CarAALink uses 10 for video based on its protocol-source finding. The LIVI log does not prove that 1 improves quality; keep these as separate protocol implementations and do not change CarAALink's 10 based on this comparison.

### Could CarAALink run on Linux?

Technically yes, but this would be a Linux implementation/port rather than running the current Android APK unchanged. The portable portion is the AA framing/protobuf/session behavior. The present app also depends on Android USB APIs, foreground services, accessibility automation for Gearhead's developer toggle, `MediaCodec`, Android `Presentation`/display APIs, and Android-specific audio/UI. Those pieces need Linux equivalents—typically a USB-host transport, a Linux UI/display layer, and GStreamer or another hardware-decoding path. The CarbitLink accessory bridge also needs a Linux USB implementation.

A practical first step would be to separate the AA protocol/session core from Android platform classes, then build a Linux receiver target around it. LIVI already implements a native Linux Android Auto head unit, so a Linux CarAALink target would need to preserve CarAALink's CarbitLink-specific bridge and workflow to add value beyond LIVI. LIVI is GPL-3.0-or-later; reusing its implementation would require respecting that license. No Linux-port code has been started.

## Implementation brief for Claude: next changes supported by the evidence

This is a research-based implementation proposal, not a bitrate-control fix. Keep the changes small enough that a matched CarAALink/PhoneAuto run can tell us which one mattered.

### Priority 1: offer a panel-appropriate video mode

For the known 1920x720 CarbitLink panel, CarAALink currently advertises 1920x1080 by default and adds a 360px height margin in FIT mode. PhoneAuto's decompiled resolution picker instead offers 1280x720 with a 240px height margin as its largest option. That reduces the active AA picture from 1920x720 to 1280x480, or 2.25x fewer active pixels. This is the best-supported explanation for the perceived difference, but it is not proof of Gearhead's bitrate choice.

Suggested small change: add a clear `Auto (panel-fit)` resolution policy using `CarScreenInfo.width/height`, preserving explicit Custom and current manual choices. For 1920x720, make the default comparable to PhoneAuto (1280x720 plus a 240px height margin); keep the existing 720p switch as an explicit override if changing the default is undesirable. Report the selected codec resolution and margins in the session log. Do not advertise several configs until the selected config index is parsed and verified: `AaSessionProbe` currently ignores MEDIA_START field 2, while the lifecycle docs describe it as the chosen config index. Keep one MEDIA_SETUP response; do not send a second response later to change the selection.

Files: `AaServiceDiscovery.configFromSettings()` and `.build()`; `CarScreenInfo`; the video resolution settings/UI; `AaSessionProbe` MEDIA_START parsing if offering multiple config entries. Existing AA docs describe resolution/margins as receiver-advertised display configuration and selected config in MEDIA_START, but do not describe a bitrate field or ACK-based bitrate control. [Video channel docs](https://github.com/mrmees/open-android-auto/blob/main/docs/channels/video.md), [channel lifecycle docs](https://github.com/mrmees/open-android-auto/blob/main/docs/interactions/04-channel-lifecycle.md).

### Priority 2: add decoder-path counters before changing decoder behavior

`AaVideoDecoder.feed()` calls `dequeueInputBuffer(10_000)` once. If it returns a negative index, this access unit is silently not submitted to MediaCodec. Current `PerfStats` counts received AA bytes and frames, but not submitted frames, unavailable input buffers, decode exceptions, or rendered output frames. Add counters and low-rate diagnostic logging for these outcomes, plus timestamp/frame-size cadence. Surface them in the debug HUD or a periodic summary. Compare with the phone's received bytes and the rendered cadence in a matched run.

If input-buffer misses are observed during blockiness, address them with a bounded, measured queue/decoder feed strategy and preserve H.264 reference-frame order. Do not simply discard arbitrary compressed access units when overloaded; that can make artifacts worse until the next IDR. Avoid a speculative decoder rewrite before the counters show misses or stalls.

Files: `AaVideoDecoder.feed()` and `PerfStats` (and its existing debug HUD). `AaSessionProbe` already ACKs each incoming video frame before decode work, so ACK pacing is not a supported image-quality knob. The protocol docs describe `max_unacked` as a flow-control window (typical video 10/audio 1), not an encoder-rate control.

### Priority 3: handle focus requests as focus, and log them against stream lifecycle

`AaSessionProbe` currently sends an unsolicited PROJECTED indication immediately after video setup, but the receive loop has no branch for `MSG_MEDIA_VIDEO_FOCUS_REQUEST`. The protocol docs define focus request mode in field 2 and distinguish PROJECTED, NATIVE, and NATIVE_TRANSIENT. LIVI's behavior (answering PROJECTED even to native requests) is observed in its source/log, but is not evidence that CarAALink should copy it. Add parsing/logging first; then implement the documented response and UI/focus behavior so the phone and head unit agree about who owns the display. Correlate focus transitions with MEDIA_START/STOP and visible black/blank intervals. This is a separate stream lifecycle correctness issue; current evidence does not connect it to compression blockiness.

Files: receive dispatch around `AaSessionProbe` lines 480–590; `AaFraming` message constants; likely `AaCarPresentation` visibility/input state. Protocol definitions are in the [video channel docs](https://github.com/mrmees/open-android-auto/blob/main/docs/channels/video.md).

### Priority 4: define a Linux port as a platform project

Do not present the current Android app or its `:protocol` module as Linux-ready. The AA framing/protobuf code is the easiest portable seam; TLS is Java-based, while USB, service discovery context, MediaCodec, Surface/Presentation, audio, accessibility, and the CarbitLink USB bridge all depend on platform-specific APIs. A Linux target needs a JVM/native transport and UI/audio/video adapters (GStreamer is a proven option in LIVI). Before implementation, write a boundary plan that separates AA session logic from Android transport/display/audio and validates CarbitLink accessory support on Linux. LIVI is an existing native Linux AA head unit and is useful as a behavioral reference, not a drop-in CarAALink backend; code reuse must account for its GPL-3.0-or-later license. [LIVI repository](https://github.com/f-io/LIVI).

### Recommended order and evidence to collect

1. Make the phone's chosen AA config, codec dimensions, margins, and focus state visible in logs.
2. Run matched sessions at CarAALink 1920x1080 and 1280x720, with the same phone, panel, cable, scene, and duration. Record incoming H.264 bytes/frame, frame intervals, decoder input misses, decode errors, and output cadence. Do not infer bitrate from an isolated frame or from LIVI's short log (it has no encoded-byte/FPS trend).
3. If 1280x720 consistently looks better while decoder counters are clean, keep/ship the panel-fit default or clearly recommend it. If counters show input misses or output stalls, fix that path and repeat the same comparison.
4. Review focus handling independently if logs show focus changes aligned with stream stops or blank intervals. Do not change ACK pacing or copy LIVI's `max_unacked=1` as a quality experiment.

No implementation or tests were run for this follow-up; these are proposed, source-grounded changes for Claude to implement and validate.

## Claude's implementation pass (2026-11-03)

Implemented Priority 2 (decoder diagnostics) and Priority 3 (video focus
handling) from the brief above. Did NOT touch resolution policy (Priority
1) or ACK/max_unacked, per the explicit instruction not to change those in
this patch. Did NOT start the Linux-port work (Priority 4) -- out of scope
for a code patch, it's a platform-design question.

### Priority 2: decoder diagnostics

Files: `app/src/main/kotlin/dev/caraalink/aa/AaVideoDecoder.kt`,
`app/src/main/kotlin/dev/caraalink/diagnostics/PerfStats.kt`.

- `PerfStats` gained five counters: `aaAccessUnitsReceived`, `aaSubmitted`,
  `aaInputBufferUnavailable`, `aaDecodeExceptions`, `aaFramesRendered`, with
  one `record*()` function each. `summary()` (already used by the debug
  HUD and the AA preview/log) now appends `"AA decode: recv=... sub=...
  miss=... exc=... rend=..."` whenever a miss or exception has happened --
  absent when everything's clean, so it doesn't clutter the normal case.
- `AaVideoDecoder.feed()`: the previously-silent `inIndex < 0` branch
  (input buffer unavailable -- the access unit was simply never submitted
  to MediaCodec, with zero visibility anywhere) now increments
  `aaInputBufferUnavailable`. The `getInputBuffer(inIndex) ?: return` early
  return (valid index, null buffer -- rarer, also previously silent, and
  ALSO used to skip draining any pending output buffers that same call) is
  now a logged, counted path instead of a bare `return`, and no longer
  skips the output-drain loop. A successful `queueInputBuffer` call
  increments `aaSubmitted`; each `releaseOutputBuffer` call increments
  `aaFramesRendered`; the existing catch-all `catch (e: Exception)`
  increments `aaDecodeExceptions`. Nothing about decode order or dropping
  behavior changed -- access units are still submitted in the order they
  arrive, none are dropped on purpose; this only makes the ALREADY-occurring
  silent misses visible. Also added a throttled `Log.i` summary every 150
  `feed()` calls (~5s at 30fps) so the counts show up in logcat even with
  the debug HUD off.
- Not yet observed live: whether `aaInputBufferUnavailable` or
  `aaDecodeExceptions` actually increment during a real blocky session.
  That's the next concrete step -- if they stay at 0 during a session that
  still looks blocky, decoder-path misses are ruled out and the resolution
  explanation (Priority 1) stands uncontested; if they climb, that's a real
  second contributor worth fixing before touching resolution defaults.

### Priority 3: video focus handling

File: `app/src/main/kotlin/dev/caraalink/aa/AaSessionProbe.kt`.

Fetched the real protocol spec directly (mrmees/open-android-auto's
`docs/channels/video.md`, GPL-3.0, facts only) rather than inferring from
PhoneAuto/LIVI behavior:
- `VideoFocusRequest` (message `32775`, already a named constant,
  previously unhandled -- no branch existed for it at all) travels
  phone->HU, fields: `focus_mode` (field 2: 1=PROJECTED, 2=NATIVE,
  3=NATIVE_TRANSIENT, 4=PROJECTED_NO_INPUT_FOCUS) and `focus_reason`
  (field 3: 1=PHONE_SCREEN_OFF, 2=LAUNCH_NATIVE, 3=LAST_MODE,
  4=USER_SELECTION).
- Added a branch that parses both fields, logs them
  (`"RX VIDEO_FOCUS_REQUEST on <channel> mode=... reason=..."`, same
  logger/style as the existing MEDIA_START log it sits next to), and
  replies with `VideoFocusIndication` (message `32776`, the existing
  `MSG_MEDIA_VIDEO_FOCUS_NOTIFICATION` constant -- the doc's own
  terminology correction from "VideoFocusNotification" doesn't change the
  wire id) **echoing back the same `focus_mode` the phone requested**,
  `unrequested=false` -- the doc's own sequence examples and sample handler
  do exactly this, not a fixed PROJECTED response regardless of what was
  asked (that's LIVI's shortcut, explicitly flagged as not to copy).
- **Deliberately did not** wire this into `CarDisplay`/`CarSession`
  display or watchdog behavior. Reasoning: a `NATIVE`/`NATIVE_TRANSIENT`
  request means the phone legitimately stops sending video on purpose --
  if that's not somehow told to the AA watchdog (`CarSession.kt`'s stale-
  frame stall detector), the watchdog would likely treat the resulting
  silence as a hang and restart the session, fighting against an
  intentional, protocol-correct focus handoff. Wiring that correctly needs
  either a callback from `AaSessionProbe` up to `CarSession` or a shared
  state flag, and I didn't want to guess at that wiring and ship an
  unverified behavior change in the same patch as the diagnostics work.
  **This is the natural next step**, not done here: expose something like
  `AaSessionProbe.active?.currentVideoFocusMode` or a change callback, and
  have the watchdog skip its stall check (or extend its grace period)
  while focus is `NATIVE`/`NATIVE_TRANSIENT`.
- Not yet observed live: whether real gearhead sessions ever actually send
  this message in this app's real usage pattern (it may be rare/never for
  a pure-driving-screen use case, more common when something like a phone
  call or native dialer takes over). The new log line will show this on
  the next live test, alongside the existing MEDIA_START logs.

### Build status

**Could not run a local build this pass** -- the Android SDK and the only
JDK on this machine both live on the external SABRENT drive
(`sdk.dir=/Volumes/SABRENT/Applications/AndroidSDK` in `local.properties`,
JDK at `/Volumes/SABRENT/gradle-home/jdks/...`), and it wasn't mounted
(`ls /Volumes` doesn't show it). No other JDK is installed
(`/usr/libexec/java_home -V` finds none). Pushed to `aa-post-v1` for CI
(`.github/workflows/android-ci.yml`, builds `assembleDebug` on a hosted
Ubuntu runner) to verify instead -- check the Actions tab / `gh pr checks`
for the actual build result before trusting this compiles clean.

### Files changed this pass

- `app/src/main/kotlin/dev/caraalink/aa/AaVideoDecoder.kt`
- `app/src/main/kotlin/dev/caraalink/diagnostics/PerfStats.kt`
- `app/src/main/kotlin/dev/caraalink/aa/AaSessionProbe.kt` (also contains
  the pre-existing, unrelated post-session reconnect backoff edit that was
  already in the working tree before this pass -- preserved, not touched)
