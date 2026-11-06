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

## Follow-up implementation: panel-fit Auto and decoder counter visibility

Implemented locally after the PR #19 diagnostic/focus commit:

- `AaServiceDiscovery.configFromSettings()` now auto-selects 1280x720 when a known landscape panel has aspect ratio >=2.5. For the reported 1920x720 panel, the existing `autoMargin()` derives a 240px height margin, yielding a 1280x480 content region. Custom resolution still overrides Auto. The existing force-720p preference also remains an explicit override. Other panels retain the prior 1080p Auto default unless the force-720p switch is enabled.
- Settings text now describes the Auto behavior and labels the existing switch `Force 720p`.
- `PerfStats.summary()` now displays decoder counters after the first received access unit even when misses/exceptions are zero. `AaVideoDecoder` emits the same `AA decode: recv=... sub=... miss=... exc=... rend=...` line every ~150 frames, so zeroes can be distinguished from missing instrumentation.

### Live verification still needed

ADB was explicitly deferred by the owner. A check attempt found no attached ADB device; the local Android SDK path in `local.properties` is `/Volumes/SABRENT/Applications/AndroidSDK`, and Java is not available in this shell, so no build or device run was performed. The available source/working-tree checks were `git diff --check` (passed) and manual review.

When the owner next runs a blocky session, enable Settings > Debug > On-screen debug overlay and inspect `AA decode: recv=... sub=... miss=... exc=... rend=...`. To capture only these counters later from a connected development host, run:

```sh
adb logcat -v threadtime -s AaVideoDecoder:I > aa-decode.log
```

Stop the capture with Ctrl+C. The app preference turns the HUD on/off; logcat capture itself is started/stopped by the command/Ctrl+C. A zero `miss` and `exc` count over a sustained visibly blocky run would clear these particular decoder input/error paths, but would not by itself prove resolution is the only possible cause.

### Remaining open items

1. Build/install and do a matched live 1920x720-panel comparison: new Auto (1280x720) versus Custom 1920x1080; record active bytes/FPS and decoder counters for the same phone, scene, cable, and duration.
2. Run the on-device decoder diagnostic during visible blockiness and determine whether `miss` or `exc` increments.
3. Connect video focus changes to watchdog/display behavior only after reviewing real focus transitions and the intended NATIVE handoff behavior.
4. Capture a synchronized CarAALink log plus Gearhead logcat through natural EOF/retries to investigate the stale USB handshake and session teardown.
5. Revisit `AudioTrack: restoreTrack_l` churn against an audio-choppiness capture.
6. Correlate Gearhead's `VirtualDisplay width must be positive` crash with CarAALink's actual resolution, margins, and density at the same timestamp.
7. Capture the steering-wheel voice-command button with a narrowly timed log window.
8. Confirm live whether the landed task-removal/reconnect/clean-close changes eliminate the need to toggle Gearhead's head-unit server manually.

The only local app changes in this follow-up are the resolution policy/settings text and diagnostics visibility/log-line cleanup described above. The PR #19 focus handling and unrelated reconnect change were not modified here.

## 2026-11-03 live capture: AA layout vs phone-screen mirror

Files inspected:

- Full phone logcat buffer copied locally to `logcat-live.txt` (captured around 13:50–13:51 local time).
- CarAALink's detailed app log pulled from the phone to `/private/tmp/caraalink-20261103-134740.log` (session events 13:47:40–13:49:34).

### What worked in the CarbitLink 1920x720 transport path

- At 13:47:51.227 the car sent `CONFIG_CAPTURE width=1920 height=720`; CarAALink created the `CarAALink` virtual display at 1920x720, configured the H.264 encoder at 1920x720 / 6 Mbps, produced codec config + IDR, and opened the mirror data channel.
- Data requests and sends continued through 13:49:34.082: final app summary says `requests=8011 sent=8011 encoded=4318`. This establishes that the phone-side CarbitLink encoder and pull-send path were active and returned a frame for every request. It does not establish what the head unit actually rendered or whether the content source was the phone screen.
- The session ended at 13:49:34.083 with `Transport stopped: read failed: EIO (I/O error)`, followed by `UsbLinkForegroundService: session ended`. That is a USB/link teardown, after the mirror data path had been running; it is not an encoder failure.

### Two different meanings of “mirror” in this capture

- **Android Auto picture on the 1920x720 car canvas:** CarAALink logged AA config `resolution=1280x720`, `widthMargin=0`, `heightMargin=0`, `autoCrop=false`, `fillMode=FIT`, `carScreen=1920x720` at 13:48:01, 13:48:33, 13:49:13, and 13:49:33. FIT scales 1280x720 to 1280x720 on a 1920x720 canvas, centered: 320px side bars on each side. This is a concrete explanation if “doesn't work full-screen” means the AA picture no longer fills the ultrawide panel. Settings > Android Auto > Auto-crop to car screen shape is off in the captured config; enabling it while retaining FIT should request a 1280x480 content region (240px total height margin) that is intended to scale to 1920x720. The app's comment still marks per-side-vs-total margin semantics as not fully confirmed, so verify the picture live. FILL would also cover the width but crops vertically. No source edit has been made for this.
- **Phone-screen MediaProjection mirror:** the app log has no `Screen capture granted` event, system log has no `CarAALink-mirror` virtual display, and a current `dumpsys media_projection` reports null. This capture therefore does not show an active phone-screen projection; the 1920x720 encoder may have been sending the current CarAALink home/AA presentation instead. To exercise that path, choose the car's `Phone screen` tile and accept Android's `Start now` prompt. If that prompt was accepted during this capture, the log does not show the projection being delivered to the service.

### Other observations / limits

- Three `AA video settings changed -- reconnecting session` events occurred at 13:48:29.618, 13:49:10.107, and 13:49:30.374. AA watchdogs then reported the session ending on its own at 13:48:31.807, 13:49:13.131, and 13:49:33.611. These explain repeated AA restarts, but are separate from the 1920x720 CarbitLink encoder setup.
- The phone's active CarAALink version used 1280x720 with `autoCrop=false`; the local panel-fit source changes described above were not built/installed in this capture. Do not attribute this session's layout to that uninstalled code.
- No `VirtualDisplay width must be positive` string appears in the available phone logcat. The latest run did not provide evidence for that Gearhead crash.
- No decoder `AA decode: ...` summary appears in this app log. The final AA decoder was created at 13:49:33.91 and the transport ended ~0.17s later, too soon for the 150-frame log interval. Earlier decoder sessions also lack those counters in this capture; likely the installed APK predates the local counter-visibility change, so it cannot clear the decoder path.

### Practical next check

If the complaint is AA letterboxing, turn on **Auto-crop to car screen shape** and keep **Fit**, reconnect, and confirm the log reports `widthMargin=0 heightMargin=240` for 1280x720; visually verify the crop due the margin-semantics uncertainty. If the complaint is the separate phone-screen mirror, grant screen-capture permission from the car's `Phone screen` tile and capture the CarAALink log through that action; the present capture never proves that path was entered. Preserve the distinction: the encoder/transport worked at 1920x720, but Gearhead's decoded AA content was configured to occupy only a centered 1280x720 region.

## Pixelation follow-up: measured H.264 sizes and 60-fps setting

The detailed app log has Protocol Sniffer enabled, so unlike the LIVI capture it includes AA video message lengths. Parsed `MEDIA_DATA` messages on `MEDIA_SINK_VIDEO` from `/private/tmp/caraalink-20261103-134740.log`:

- Three AA video runs had 1,207 frames / 22.34s, 1,029 / 26.97s, and 544 / 10.26s. Payload rates averaged approximately 2.26, 1.68, and 1.62 Mbit/s across those runs (subtracting the 8-byte timestamp from each logged message body).
- In the second and third runs, most later 1-second windows were around 0.7–1.1 Mbit/s; several 5-second windows were about 0.58–0.93 Mbit/s. Large keyframe bursts inflate the full-run average. This provides direct evidence that low-detail-per-frame periods were present in the H.264 stream received from Gearhead, compatible with visible pixelation. It does not prove the user-visible blockiness happened at those exact timestamps, and receiver-side decoder drops were not measured in this capture.
- CarAALink's logged AA config says `1280x720@60fps`; the AA protocol enum is `_60 = 1`, `_30 = 2`. Current preference values allow 30 or 60, and the phone produced roughly 38–54 data messages/s during these runs. At a bitrate-limited interval, requesting 30 fps can leave more bits per frame and is a worthwhile controlled A/B test at the same 1280x720 resolution. It can trade motion smoothness for per-frame detail; Gearhead may also adapt total bitrate, so improvement must be measured. This is a distinct receiver-settable protocol input, not a bitrate pin and not proof that frame-rate selection controls Gearhead's adaptation algorithm.

### Auto-crop preference clarification

The code and preference XML default `aa_auto_crop` to true. The captured device config says `autoCrop=false`, which indicates that this install has a saved false preference. Auto-crop is geometrically possible for this 1920x720 panel and the selected 1280x720 mode: current `autoMargin()` computes `heightMargin=240`. Re-enable the preference on the device; FIT plus that crop should fill the panel if the protocol's margin semantics behave as assumed. Auto-crop changes composition/layout, not encoded H.264 quality, so it should not be expected to remove pixelation.

### Revised conclusion and next experiment

Resolution is not literally the only receiver-side configuration input: AA's advertised `frame_rate` (30/60) is another real protocol setting. There is still no receiver-settable bitrate field, and no evidence ACK timing pins or stabilizes Gearhead's bitrate. Keep auto-crop on for the ultra-wide layout, then compare 1280x720 at 60 fps versus 30 fps using the same phone, scene, duration, and decoder counters. Record rolling 5-second H.264 payload rate, frame cadence, `miss`, `exc`, and rendered count. If 30fps improves detail while keeping acceptable motion, use it for this panel; if not, retain 60fps. Do not attribute any improvement to cropping.

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

## Open Headunit comparison and phone log findings (2026-11-03)

### Session path and lifecycle

- Open Headunit (OHA) detected Android Auto 17.4+ Self Mode and logged a
  direct connection to Gearhead's local Head Unit Server at
  `127.0.0.1:5277`. This is the same endpoint CarAALink uses, so the capture
  is useful for comparing the local-server path.
- In the captured runs, OHA repeatedly reached projection after connecting.
  Most disconnects are explicitly `user_exit`; at 14:50:25 it logged
  `link_lost`, followed by a reconnect and successful projection at 14:50:28.
  These short manual sessions do not reproduce CarAALink's recurring EOF
  loop, but do show that Gearhead accepted several rapid reconnects in this
  capture.
- OHA's own log shows `VIDEO_FOCUS_NATIVE` immediately before one
  `user_exit`. This is consistent with its user-exit path; it does not
  establish that Native focus caused an unsolicited disconnect.
- Yes, the capture includes the exit flow. At 14:49:35 OHA logs
  `VIDEO_FOCUS_NATIVE`, explicitly says “User clicked Exit in Android
  Auto,” then logs `ExitAction: Disconnecting projection session` and a
  `USER_SELECTION` BYEBYE that was successfully written. At 14:50:24,
  Android records OHA's `AapProjectionActivity.showExitDialog`; at 14:50:25
  OHA logs “User ended the session and asked to stay ready.” A second exit
  dialog appears at 14:50:32, followed by the next user exit. So the log
  shows both Android Auto's Exit action and OHA's own exit dialog / “stay
  ready” action; it does not include a screenshot or the dialog's rendered
  text/buttons.
- The synchronized Gearhead log records `PROJECTION_ENDED_BYEBYE_BY_CAR`
  at 14:50:33.843, then framer write/read EOF and USB FD close. Gearhead
  recorded `SESSION_STOPPED` at 14:50:37.692. OHA's service was stopped at
  14:50:35.189. At 14:50:42.004 Android's low-memory killer reclaimed
  Gearhead's `:watchdog` process for low memory, with binder-death messages
  shortly after; ConnLogger emitted another `SESSION_STOPPED` at 14:51:37.
  This is evidence of delayed/internal Gearhead cleanup and a separate LMK
  event, but timing alone does not prove the LMK caused the later session
  event or explain prior CarAALink EOFs.

### DPI and screen sizing from OHA's log

- **Important scope limit:** OHA was running Self Mode on the phone itself.
  Its logged raw/usable display was `1080x2340` portrait. These values do
  not describe the car's `1920x720` panel and cannot be copied as that
  panel's configuration.
- For the phone's `1080x2340` panel, OHA announced primary video
  `1080x1920` at 60 fps, with margins `194x0`, density `194`, and square
  pixel aspect ratio (`10000`). It logged the resulting AA canvas as
  `730x1583 dp`; the requested density was 287, but OHA clamped it to 194
  to honor its 730 dp minimum portrait content-width policy.
- The geometry is internally consistent after margins: visible width is
  `1080 - 194 = 886 px`; at density 194, that is about 731 dp. OHA thus
  computes announced density from the visible content width and a minimum
  logical width, rather than blindly announcing the phone's requested
  density. The display's Android raw resolution and the AA video buffer
  are separate values (`1080x2340` vs `1080x1920`).
- OHA also offered `_720x1280` as a fallback with margins `129x0` and
  density 129, preserving approximately the same 730 dp content width.
  The H.264 SPS log says `1080x1920 (negotiated 1080x1920)`, confirming
  Gearhead selected the primary configuration in this run; it did not use
  the fallback.
- This is evidence for OHA's portrait sizing policy only. It does not
  establish that the same fixed 730 dp rule is appropriate for CarAALink's
  landscape automotive panel. For CarAALink, calculate density from the
  intended visible canvas after margins and verify the result on the
  actual `1920x720` display; don't substitute the phone's Android DPI.

## Claude's analysis: OHA geometry vs. CarAALink's actual 1920x720 canvas (2026-11-03)

Analysis only -- no code, build, install, commit, or push in this pass, per
instruction. Read current source (`AaServiceDiscovery.kt`,
`AppSettings.kt`) and the two new diagnostics files plus `logcat-live.txt`
before writing this; neither new OHA log file (`session-sizing-and-
lifecycle.log`, `gearhead-lifecycle.log`) added anything beyond what's
already summarized above -- both match the existing write-up exactly
(same session, same timestamps).

### OHA's actual method, stated precisely

OHA doesn't apply a fixed density. It derives density FROM a target
logical (dp) width and the VISIBLE (post-margin) pixel width, then
margins + density together produce that target:

```
visible_px_width = raw_px_width - margin_px        (1080 - 194 = 886)
density = visible_px_width * 160 / target_dp_width  (886 * 160 / 730 ≈ 194)
```

The 730 is OHA's own chosen portrait minimum-content-width policy -- not
a protocol constant, not something to copy numerically into a landscape
automotive panel. The portable part is the METHOD: compute density from
the visible content pixels, not the raw buffer, not the phone's own
screen density.

### What CarAALink currently computes, exactly, with the numbers

`AaServiceDiscovery.configFromSettings()` (current `aa-post-v1` HEAD) for
the real 1920x720 panel: `carAspect = 1920/720 ≈ 2.667 >= ULTRAWIDE_PANEL_ASPECT (2.5)`
→ `ultrawidePanel = true` → `defaultRes = R1280X720`. `autoMargin(R1280X720, 1920, 720)`:
`resAspect = 1280/720 ≈ 1.778 < carAspect` → crops height →
`desiredHeight = 1280 / 2.667 = 480` → `heightMargin = 720 - 480 = 240`,
`widthMargin = 0`. So the negotiated buffer is 1280x720, the visible
CONTENT region is **1280 wide x 480 tall** (width uncropped, height
cropped by 240px) -- this matches `chatgpt-status.md`'s earlier figures
exactly, confirms reading the code right.

Density: `density = videoDpi(context, resolution.width, resolution.height)`
passes **1280, 720 -- the full negotiated buffer, not the 1280x480 content
region**
(`app/src/main/kotlin/dev/caraalink/settings/AppSettings.kt:66-71`):

```kotlin
fun videoDpi(context: Context, width: Int, height: Int): Int {
    if (videoDpiAuto(context)) {
        return (minOf(width, height) / 720f * 213).toInt().coerceIn(120, 320)
    }
    return manualVideoDpi(context)
}
```

`minOf(1280, 720) = 720` → `720 / 720 * 213 = 213` (density, unclamped,
already inside [120,320]). **This formula never sees the margin at all --
it's computed from the raw negotiated resolution's shorter side, before
any crop is applied.** That's the concrete, code-level version of exactly
the gap OHA's method closes: OHA computes density from the post-margin
visible width; CarAALink computes it from the pre-margin buffer's shorter
side, unconditionally.

### What that arithmetically implies for the visible canvas

Using the standard `dp = px * 160 / density` relationship at the computed
density=213, for the actual 1280x480 visible content region:

```
dp_width  = 1280 * 160 / 213 ≈ 962 dp   (width is NOT cropped here, full negotiated width)
dp_height =  480 * 160 / 213 ≈ 361 dp   (height IS cropped, this is the visible content height)
```

**Important caveat, not a prediction:** `claude/roadmap.md` already records
a confirmed-twice-live finding that the density-to-visual-size relationship
in this app's real AA rendering runs OPPOSITE the textbook `px=dp*density/160`
assumption (a HIGHER declared density made the on-car UI look SMALLER, not
bigger, in two separate real tests). So the 962x361dp figure above is only
the arithmetic the formula produces -- it is NOT a trustworthy prediction
of what will actually render larger or smaller on the car screen. Treat it
as "this is the number currently being sent," not "this is what you'll
see."

### What this analysis does and doesn't establish

- **Does establish, from code alone:** CarAALink's density formula is
  structurally blind to the autocrop margin -- it would compute the exact
  same density=213 whether `heightMargin` is 0 or 240 or any other value,
  because the margin never enters the calculation. This is a real,
  verifiable gap, not a guess.
- **Does NOT establish:** that this gap is the cause of any specific
  visual complaint (too-small UI, misaligned touch targets, etc.), or that
  closing the gap would make things look "correct" given the confirmed
  density-direction anomaly above. Those need a live, visual check.

### One reversible experiment, not a proposed fix

Change `videoDpi()`'s *input* only -- pass the post-margin content
dimensions instead of the full negotiated resolution, keeping the same
`/720*213` convention and clamp:

```kotlin
// at the call site in configFromSettings(), after widthMargin/heightMargin are known:
val contentW = resolution.width - widthMargin
val contentH = resolution.height - heightMargin
val density = AppSettings.videoDpi(context, contentW, contentH)
```

For the real panel this changes the density input from `(1280, 720)` to
`(1280, 480)`: `minOf(1280, 480) = 480` → `480/720*213 ≈ 142` (density).
This is a single, isolated, fully reversible change (one call site, one
line) -- but given the confirmed density-direction anomaly, do NOT ship it
on arithmetic confidence alone. The log evidence needed before/after:

1. Log the computed density AND the resolution/margin it was computed from
   in the same line (the existing `"AA config: resolution=... density=...
   widthMargin=... heightMargin=..."` log in `CarSession.kt` already does
   this -- just diff the before/after value of `density` for the same
   panel).
2. Take a real car-screen photo or clear visual note (text size relative
   to the screen, whether UI elements are cut off/overflowing) at the
   CURRENT density=213 with heightMargin=240, then rebuild with the
   content-aware change and repeat at density≈142, same session length,
   same screen.
3. Only keep the change if the second photo/observation is clearly closer
   to "correctly scaled" than the first -- given the anomaly, "arithmetic
   says density should go down" is not sufficient justification by itself.

Not implemented. No file was edited for this section.

### Other implementation comparison leads

- OHA source configures low-latency TCP options (`TCP_NODELAY` and 512 KiB
  send/receive buffers). CarAALink's AA socket setup did not appear to set
  equivalent options when reviewed. This is a measurable transport
  difference worth an isolated A/B test, but there is no evidence from
  this capture that it fixes Gearhead EOFs or video blockiness.
- OHA's AAP control handler responds to peer Ping Requests; its source did
  not show a periodic Ping Request sender. CarAALink sends proactive pings
  every five seconds. Treat this as another controlled A/B test candidate,
  not an established cause.
- OHA's `UnresponsivePeerPolicy` describes the local `127.0.0.1:5277`
  listener as a serial accept path and applies increasing retry backoff
  after accepted-but-silent connections. That is OHA's documented defensive
  behavior, not proof that CarAALink needs the same delay. Compare it when
  handling TCP-connected sessions that never produce the first AA version
  response.

### Attached filtered logs

- [OHA session, negotiated sizing, and lifecycle excerpt](diagnostics/openheadunit-2026-11-03/session-sizing-and-lifecycle.log)
- [Gearhead session and process lifecycle excerpt](diagnostics/openheadunit-2026-11-03/gearhead-lifecycle.log)

The full OHA exports and 43 MB device logcat remain in `/private/tmp`; only
curated excerpts are attached here to avoid carrying unrelated system and
personal device logs into the repo.

### Claude handoff prompt

```text
Please continue the CarAALink investigation from chatgpt-status.md, using
the new Open Headunit comparison section and its attached log excerpts:

- diagnostics/openheadunit-2026-11-03/session-sizing-and-lifecycle.log
- diagnostics/openheadunit-2026-11-03/gearhead-lifecycle.log

Read those logs and the current CarAALink source before changing anything.
Preserve all existing working-tree edits. Do not claim the OHA portrait
1080x2340 / 730dp sizing values apply to the car's 1920x720 display: they
are from Open Headunit Self Mode on the phone. The capture shows OHA
announcing 1080x1920, margins 194x0, density 194, square pixels, and a
720x1280 fallback; Gearhead selected the primary stream. It also shows
OHA's explicit Android Auto Exit handling: VIDEO_FOCUS_NATIVE ->
USER_SELECTION BYEBYE (sent), and a separate OHA exit dialog followed by
“User ended the session and asked to stay ready.”

Assess how OHA's geometry and density policy should inform CarAALink's
1920x720 configuration. Derive candidate margins/density from the actual
CarAALink visible canvas and current panel-fit/autocrop behavior; explain
the calculations and uncertainty before editing. Do not transplant OHA's
portrait-only 730dp floor to landscape without protocol/source evidence.

Review the two bounded implementation comparison leads from the status
doc: OHA sets TCP_NODELAY and 512 KiB socket buffers, while CarAALink may
not; and OHA responds to peer pings while CarAALink sends proactive
5-second pings. Treat both as hypotheses only. If proposing a change,
make one isolated, reversible change at a time, explain the expected
observable effect, and avoid bundling unrelated protocol changes.
Consider OHA's retry/backoff behavior only for TCP-connected but
AA-version-silent sessions.

For the Gearhead lifecycle excerpt, distinguish the BYEBYE/user exit,
Gearhead's 14:50:37 SESSION_STOPPED, the 14:50:42 low-memory kill of the
:watchdog process, and the later 14:51:37 SESSION_STOPPED. Do not infer
causation from timing alone. Do not present any of these manual OHA exits
as the cause of CarAALink's earlier recurring EOF loop.

Please update chatgpt-status.md with any new verified result, list exact
files changed, and include a focused test/measurement plan. Do not build,
install, commit, or push unless I ask. Do not add tests unless requested.
```

### What to do next in Open Headunit

1. Repeat the same connection while watching the 1920x720 car display, and
   record whether OHA's Self Mode on that phone is still a phone-only
   projection. Its current log is portrait self-projection, so it cannot
   answer car-panel sizing questions directly.
2. If OHA can be used as the receiver on the car display, capture its
   service-discovery lines and record the actual announced resolution,
   margins, density, pixel aspect ratio, selected config index, and H.264
   SPS dimensions. Compare those values with CarAALink on the same panel.
3. Capture one deliberate Android Auto Exit and one cable/app disconnect
   separately. Keep the OHA app log and Gearhead logcat running across
   both, and note which UI action you took. This separates the explicit
   `VIDEO_FOCUS_NATIVE`/BYEBYE exit from an ungraceful EOF.
4. To inspect the dialog itself, take a screenshot when OHA's exit prompt
   appears; the current logs prove `showExitDialog` was called but don't
   show the rendered message or button labels.
5. Repeat a session long enough to reproduce the actual failure without
   tapping Exit. Save OHA's app log and synchronized Gearhead logcat, and
   note the wall-clock time of the first unexpected disconnect. The current
   capture's user exits are not a substitute for that failure reproduction.

## Claude: OHA exit/reconnect architecture and a density-override finding (2026-11-03, round 9)

### Scope note
`OHU_Log_20261103_172309_748.txt` is another OHA **Self Mode** capture
(phone-to-phone, portrait 1080x2340) — every exit event in it logs
`SelfMode: stopping Self Mode`. This is still phone-only evidence, same
boundary as the earlier OHA capture: it shows how OHA's own app is
structured, not a reproduction of CarAALink's car-side (1920x720) problem.
Treat the architecture pattern below as a lead to adapt, not a fix to copy.

### Finding 1: the AA Exit button maps directly to VideoFocusRequest(NATIVE)
Confirmed from the log, one clean example (line ~28227-28228):
```
AapControlMedia.execute | Video Focus NATIVE received. User clicked Exit in Android Auto.
AapControlMedia.execute | ExitAction: Disconnecting projection session
```
This is the car-side "Exit Android Auto" button. OHA treats a
`VideoFocusRequest` with `focus_mode=NATIVE` (2) as the car telling it
the user exited, and responds by calling its own disconnect action —
not by waiting for a timeout or socket error. This is exactly the wiring
CarAALink is still missing: `AaSessionProbe.kt` now logs and ACKs
`VideoFocusRequest` (round 8), but nothing downstream acts on
`focus_mode`. A `NATIVE` or `NATIVE_TRANSIENT` focus_mode should be
treated as "car took back the screen on purpose," not as a stalled
session for the watchdog to restart.

### Finding 2: OHA never restarts its whole service to reconnect
The exit sequence, in order, every single time (line ~2351-2400,
~13012-13051, ~19400-19455, ~25007-25080, ~28233-28258 — five
repeated, identical exits in this one capture):
```
AapProjectionActivity: Finishing because state isUserExit=true, isClean=false, killOnDisconnect=false
AapTransport.stop | AapTransport stopping and sending byebye (USER_SELECTION)
AapTransport: ByeBye write SENT
RX: CONTROL Byebye Response
AapTransport.quit | AapTransport quitting (clean=false)
AapAudio.releaseAllFocus
VideoDecoder.outputThreadLoop / feedThreadLoop: Output/Feed thread stopped
CCodec: state->set(STOPPING) -> ALLOCATED -> RELEASING -> RELEASED (clean MediaCodec teardown, no leaked decoder)
AapService.onDisconnected: Stopping GpsLocationService and NightModeManager
AapService.releaseWifiLock (x2)
AapService.onDisconnected: User exit cooldown active for 5000ms
AapService.scheduleReconnectIfNeeded: Self Mode disconnected. Not restarting.
```
Key structural point: `killOnDisconnect=false` is explicit in the very
first line — the Activity (UI/projection session) and the
`AapTransport`/`VideoDecoder` are torn down and released, but the
long-lived `AapService` is **never killed or restarted**. It just logs
a 5-second "exit cooldown" and sits ready for the next connection event.
Only the lightweight session objects (transport socket, SSL context,
decoder, projection UI) get recreated per-connection; the service
process, its wifi lock bookkeeping, and its wireless-launch state
machine persist across many exits (this one log shows 5 clean
exit→idle cycles with no process restart).

This matches a standing CarAALink complaint ("have to manually toggle
the head unit server") and is a concrete, scoped lead: our own
watchdog/reconnect path (`CarSession.kt`'s `openAndroidAuto()` restart
flow) should be checked against this — specifically, whether our
"session" teardown is accidentally also tearing down or blocking the
foreground service/USB accessory layer, rather than just the AA
transport+decoder+probe the way OHA's is scoped. **Not yet verified
against our code** — this is OHA's pattern stated from its own log,
not a diff of `UsbLinkForegroundService`/`CarSession` against it. The
next step, if this is worth pursuing, is reading our own reconnect path
side-by-side with this sequence and naming exactly what CarAALink tears
down that OHA doesn't.

### Finding 3: OHA's own density floor, restated
Same self-mode portrait session, new numbers (line ~13925-13927):
```
[ServiceDiscovery] Density is: 287 (dp canvas 1304x602, requested 287, within the HUIG floor)
[ServiceDiscovery] Offering a 1280x720 fallback, margins 0x0, density 191
```
and `HeadUnitScreenConfig.recalculate`: `scaleFactor: 1.21875,
portraitScaled: false, shape=PAR, margins: w=0, h=0`. This is a second,
different portrait-session density value from OHA (287, vs. the earlier
capture's 194) — consistent with OHA computing density per-session from
whatever dp canvas and "HUIG floor" (a minimum-width guideline it
enforces) apply that time, not a fixed constant. Doesn't change the
prior analysis; just confirms OHA's number moves around by session,
so no single OHA density value should be treated as a target to match.

### Side finding: the density test we tried to run didn't test anything
Pulled a CarAALink in-app log from this same test window
(`CarAALinkApplication` build `5f887af3a7`). The `AA config:` line read
`density=250`, not the ~142 the new margin-aware formula predicts. Root
cause, confirmed by reading `AppSettings.kt`: `videoDpi()` only runs the
auto formula when `video_dpi_auto` is true; otherwise it returns
`manualVideoDpi()`, a saved pref (`video_dpi_manual`, apparently left at
"250" from earlier manual calibration). The margin-aware code path
(`AaServiceDiscovery.kt`'s new `contentWidth`/`contentHeight`) never ran.
**The before/after density test still hasn't actually happened.** To run
it: open Settings, confirm/enable Auto DPI (clears the manual override),
reconnect, and re-pull the log. Decoder health in that same log was
clean regardless (`recv=` and `sub=` matched exactly through 2100+
access units, `miss=0 exc=0` throughout), and the one
`VIDEO_FOCUS_REQUEST` seen (`mode=1 reason=0` = PROJECTED/unknown) got
ACKed correctly.

## Claude's round-9 work: starvation fix, EIO auto-reconnect, H.264 level fix -- and why the artifacting likely survives all three (2026-11-03)

### Density test, confirmed good
Auto DPI was off with a manual override of 250 saved from old calibration
-- that's why the first density test showed no change. Turned on, a fresh
pull showed `density=142` exactly as predicted, and the user confirmed it
"looked way better" on the real car screen. This one's done; the
margin-aware density change in `AaServiceDiscovery.kt` is keeping it.

### Starvation-fallback fix (landed, real improvement, not the root cause)
`CarSession.kt`'s `onFrameRequested` blocks until the encoder has a frame;
if nothing arrives within 80ms it used to resend `lastKeyFrame`, however
old that specific keyframe was relative to anything sent since. A real
log showed this firing on 62% of all responses in one session
(`requests=7303 sent=7303 encoded=2777`). Changed the fallback to resend
`lastSentFrame` (the actual most recent thing already sent, key or not)
instead, clearing it on stream reopen so a new session can't inherit a
non-keyframe from the last one. Verified improvement from two later
sessions: 47% fresh before a comparable fix vs 86% fresh after
(`requests=11576 sent=11576 encoded=9935`). Real, measured fix -- but the
user confirmed afterward the blockiness was unchanged ("same damn
issue"), so stale-frame repetition was never the dominant cause of the
visual artifact, just its own separate (now smaller) problem.

### EIO auto-reconnect (landed, works, but exposed what EIO actually is)
Every captured session eventually died with `Transport stopped: read
failed: EIO (I/O error)`, at a different request count each time (5088,
7303, 11576, 642, 2133 -- no fixed threshold), and previously
`UsbLinkForegroundService` just called `stopSelfCleanly()` on any session-
end reason, full stop, no retry -- confirmed as the actual mechanism
behind the long-standing "have to manually toggle head unit server"
complaint. Added a capped, backed-off retry (`scheduleTransportReconnect`,
4 attempts, 2s/4s/8s/16s) that only fires for a transport I/O failure,
never for a deliberate stop (`ACTION_STOP`, `onTaskRemoved`, or the
existing 30s-grace physical-detach path, all now set a `stopping` flag
that short-circuits it).

**What a live log revealed once this was in place:** the very first retry
attempt throws immediately --
```
UsbAccessoryConnection: openAccessory threw -- treating as not attached
UsbAccessoryConnection: java.lang.IllegalArgumentException: no accessory attached
```
-- meaning Android had already dropped the accessory by the time the
2-second retry fired. This is a **real USB-level detach/reattach**, not a
transport read stalling while the physical connection stays up. The
session does recover a few seconds later, but from a fresh
`ACTION_USB_ACCESSORY_ATTACHED` broadcast Android generates on its own,
not from this retry (which fails every time it's tried so far). Net
effect: the fix is harmless and the reconnect does happen, but credit
belongs to Android's existing re-attach handling, not the new retry path.
**Open question, not yet answered:** does the already-existing attach
broadcast receiver actually re-launch `UsbLinkForegroundService` on that
new attach event reliably on its own? If yes, this whole retry addition
may be redundant with a pre-existing recovery path and the real "have to
manually restart" complaint has a different cause than what this patch
addresses. Needs checking against the attach-intent-filter/manifest and
a verification pass, not assumed.

This also means the EIO is coming from CarbitLink (or the physical USB
link) itself resetting, not from anything in our framing/encoding code --
and it's getting *more* frequent across repeated reconnect cycles in the
same test run (11791 -> 642 -> 2133 requests between failures), which
looks more like compounding instability than a one-off glitch.

### H.264 level mismatch fix (correct per spec, did not fix the blockiness)
`CarVideoEncoder.kt` hardcoded `AVCProfileBaseline` / `AVCLevel31`
regardless of actual resolution. A real log showed CarbitLink's own
`CONFIG_CAPTURE` requesting **1920x720** for the Mirror channel -- 5400
macroblocks (1920/16 x 720/16). H.264 Level 3.1's spec MaxFS (ITU-T
H.264 Table A-1) is 3600 macroblocks: every Mirror frame was ~50% over
the level we told the encoder it was. Replaced the hardcoded level with
`levelFor(width, height, fps)`, which picks the lowest standard level
whose table MaxFS/MaxMBPS actually covers the requested resolution.
This is a genuine, confirmed spec violation and worth keeping regardless
-- but the user tested it and reported the blockiness was unchanged
("same damn issue"). So a level mismatch, if it mattered at all, was not
the dominant cause either.

### The bigger architectural finding: CarAALink may be the only one doing a double transcode
Decompiled the full `PhoneAuto-beta-2027-01-19.apk` with jadx (1792 files)
specifically looking for its video-encoder setup, to compare directly
against the one app known to look clean on the same CarbitLink hardware.
Result: **zero** `MediaCodec.createEncoderByType` calls anywhere in the
app, zero `MediaRecorder` usage, zero encoder `MediaFormat` configuration
of any kind. The only `video/avc` codec reference in the entire
decompiled source is `C0303ke.java`'s `MediaCodec.createDecoderByType`
-- a **decoder**, matching our `AaVideoDecoder.kt`'s role (receiving and
decoding the real phone's AA video), with no corresponding encoder
anywhere to re-send that decoded video onward to CarbitLink.

This means the "PhoneAuto doesn't show jpeg artifacting" comparison may
not be comparing like for like. CarAALink's pipeline is a full double
transcode: real phone's AA H.264 -> **our decode** -> render -> **our
re-encode** -> CarbitLink -> car's decode. If PhoneAuto has no re-encode
step at all, it's either (a) relying on a separate, CarbitLink-provided
driver/companion app to do system-level screen capture and encoding
(built by people with first-party knowledge of the dongle's actual
expected wire format, not reverse-engineered), or (b) doing something
with the decoded surface that doesn't require an app-level MediaCodec
encoder. Either way, a second full H.264 encode/decode generation is
inherently lossier than one, independent of any specific bug -- it's
possible part of what's left of the remaining blockiness, after the two
fixes above, is this structural cost of CarAALink's own architecture
rather than a single fixable defect.

**UPDATE, same session, corrected:** the "PhoneAuto doesn't artifact"
comparison was based on a misunderstanding -- the user was mirroring
through **CarbitLink's own official companion app**
(`CarbitLink-EasyConnection_6.5.1_APKPure.apk`, package
`com.tarplay.phonescreen` is actually a different app, PhoneAuto itself,
not CarbitLink's -- confirmed by the user directly), with PhoneAuto just
open as content being displayed. Pulled and inspected CarbitLink's real
app (100MB, vs. PhoneAuto's ~2MB) and found the actual answer:

**CarbitLink's own app does not use the phone's hardware H.264 encoder
at all.** `strings` on its `lib/arm64-v8a/libJNICodec.so` shows a full
custom-compiled FFmpeg build (`--enable-libx264 --enable-encoder='h264,
libx264'`, GPL build flags, x264 source file paths like
`x264/encoder/ratecontrol.c`, `x264/encoder/analyse.c`) and an internal
banner string `(knNx264 [%s]:`. Zero references to `MediaCodec` or any
hardware-encoder path anywhere in that library. They ship and run a
**software libx264 encoder** entirely on the CPU, not
`MediaCodec.createEncoderByType` like `CarVideoEncoder.kt` does.

This is a far better explanation for the blockiness than anything found
so far: software x264 at a given bitrate produces meaningfully better
quality than typical phone hardware H.264 encoders, which are fast but
make much worse rate-distortion tradeoffs under bitrate pressure --
exactly what visible macroblock/"blocky" artifacts look like. It's
content-independent (a structural quality gap, not a bug), consistent
with the Level fix not closing it (a wrong level could cause outright
corruption, but a *correct* level on a weaker hardware encoder still
just means weaker quality, not a fix), and consistent with the home
screen and AA content both showing it.

**The real fix, if pursued:** bundle a software x264 encoder into
CarAALink (an NDK x264 wrapper, or FFmpeg similarly configured) and
encode the Mirror channel's output through that instead of
`CarVideoEncoder.kt`'s `MediaCodec`-based path, trading CPU usage for
real quality. This is a legitimate, substantial feature (new native
dependency, new encode path, almost certainly worth keeping
`CarVideoEncoder.kt`'s MediaCodec path as a fallback for low-end
devices) -- not a quick patch, and not something to start without the
user explicitly signing off on the scope.

**CORRECTION, same night, after x264 testing:** EIO is NOT dongle
hardware instability as concluded above -- the user confirmed
CarbitLink's own first-party app stays stable on the same dongle/cable
over a comparable session, while CarAALink keeps hitting
`read failed: EIO` (confirmed independent of the x264 on/off toggle, so
not an encoder-side issue either). Also observed the same night: the AA
*receive* side (separate channel from Mirror) was also restarting.
Likely real cause: something in our own `AdbTransport.kt`'s ADB-over-AOA
implementation (ACK timing, window/`maxData` negotiation, a periodic
keep-alive CarbitLink expects that we're not answering) that
CarbitLink's own `libjadbd.so`-based stack handles correctly and ours
doesn't. Needs a real side-by-side protocol-timing comparison (our
`AdbTransport` reads/writes vs. a capture of CarbitLink's own app doing
the same session) as its own investigation -- not yet started.

`libJNICodec.so` is unstripped enough that `strings` alone got this far
without needing real disassembly/Ghidra work. Deeper inspection of the
same library (and `libhud_protocol.so`, only ~5KB, likely a thin
wrapper/stub rather than the real protocol logic) could still reveal
their exact x264 parameters (CRF/bitrate mode, preset, GOP structure) if
that level of parity ever becomes worth chasing.

### Open items carried forward
- Verify whether `UsbLinkForegroundService`'s existing USB-attach
  broadcast handling already auto-restarts the session on a fresh
  accessory-attach event, independent of the new `scheduleTransportReconnect`
  path -- if it already did, the actual "manual restart" root cause is
  still unexplained.
- Find and decompile CarbitLink's own first-party Android app/driver, if
  one exists, as a true like-for-like comparison instead of PhoneAuto.
- Reproduce one full EIO cycle with `logcat` plus the in-app log pulled
  in the same window, to see if the kernel-level USB UEVENT disconnect
  event lines up with the moment the read fails (confirms vs. just
  infers the real hardware-reset theory).
- The starvation-fix and level-fix are both real, confirmed-correct
  changes worth keeping even though neither alone explains the remaining
  blockiness -- don't revert them chasing a different lead.

## External repo review for the EIO lead (2026-11-04)

Reviewed the repos the user linked, looking for anything bearing on
`AdbTransport.kt`'s `read failed: EIO` (now confirmed app-side, not
dongle hardware -- CarbitLink's own app stays stable on the same
dongle/cable). x264 encoder path removed from the working tree per
user instruction (reverted, not pursued further -- blockiness fix
unconfirmed and EIO proven independent of it).

**Directly useful: [ingvaror/BLECarbitlink](https://github.com/ingvaror/BLECarbitlink)**
(no license file -- don't copy code, facts only). `docs/protocol.md`:
- AOA identity: `manufacturer="Carbit"`, `model="CarbitDriverOversea"`,
  `version="1.0"` -- matches our own observed
  `accessory=Carbit/CarbitDriverOversea` exactly, same device family.
- `A_CNXN` sent as two bulk writes (24-byte header, then data); banner
  must be exactly `host::<uuid>`.
- `max_payload` cap is **128 KiB**; jadbd FORTIFY-aborts above that.
  `AdbTransport.kt:99` defaults `maxData = 4096` and updates to the
  peer's own `arg1` on `CNXN` (`AdbTransport.kt:168`) -- this is inside
  their cap, not a likely EIO cause on its own, but confirms our
  default is conservative/safe, not reckless.
- Header: 24 bytes LE, `command/arg0/arg1/data_length/data_check/magic`,
  `data_check` = sum of payload bytes (not a real CRC), `magic` =
  `command ^ 0xFFFFFFFF` -- matches `AdbTransport.kt`'s own `sendPacket`
  (`command xor -1`) exactly; no mismatch found here.
- **Most relevant find:** "On Linux, a redundant `set_configuration()`
  on an already-configured accessory makes the first bulk write fail
  with `EIO`." This is their own Linux-host emulator's behavior (they
  are emulating the head-unit/host side), not directly our code --
  in AOA the phone is the accessory, CarbitLink is the host, so a
  redundant `set_configuration()` call would originate from
  CarbitLink's own USB-host stack, not anything in
  `UsbAccessoryConnection.kt`/`AdbTransport.kt`. But it's the same
  *signature* (first bulk write after a reopen fails with EIO) as what
  we've captured repeatedly, which is a real structural analogy worth
  stating as a hypothesis: **if CarbitLink's host-side stack re-issues
  `set_configuration()` when our `AdbTransport`/session reconnects or
  behaves in a way their stack doesn't expect (e.g. our own
  `scheduleTransportReconnect` retry calling `openAccessory()` again
  while CarbitLink still considers the old session live), that could be
  what triggers it on their end** -- consistent with CarbitLink's own
  app staying stable (it presumably never puts their host stack in that
  state) while ours does. **Unverified** -- would need a capture of
  CarbitLink's own USB traffic (their `tools/usbmon_extract.py` or
  `aoa_probe.py` pattern) around our EIO moment to confirm, not just
  inferred from this doc.
- `tools/` (`adb_decode.py`, `aoa_probe.py`, `usbmon_extract.py`,
  `carbit_capture.py`) are usbmon-based capture/decode scripts for
  exactly this kind of comparison -- worth running structurally (not
  copying) against our own session, if a synchronized capture is
  pursued next.
- ECP (their term for the app-layer protocol over ADB streams) payloads
  are JSON in the clear; `CONFIG_CAPTURE`/mirror video framing is
  explicitly NOT documented in their doc either -- no new info there.

**Background only / unrelated (CarPlay dongle hardware, not Android
Auto, not CarbitLink, not inspected further given budget -- classified
by README description only, per the brief's own guidance that these are
likely background unless a specific transferable detail turns up):**
- [ludwig-v/wireless-carplay-dongle-reverse-engineering](https://github.com/ludwig-v/wireless-carplay-dongle-reverse-engineering)
  (GPL-3.0) -- CPlay2Air/Carlinkit wireless CarPlay RE, different
  dongle family, wireless not USB/AOA.
- [catplay-labs/catplay](https://github.com/catplay-labs/catplay)
  (no license) -- Rust CarPlay protocol implementation, CarPlay not AA.
- [segfly/carlinkit-modding](https://github.com/segfly/carlinkit-modding)
  (no license) -- Carlinkit CarPlay adapter modding notes.
- [Henkru/cplay2air-wifi-passphrase-patch](https://github.com/Henkru/cplay2air-wifi-passphrase-patch)
  (MIT) -- patches a default WiFi password at runtime, wireless CarPlay
  only, no transport/reconnect content.
- [Quikeramos1/Unbrik-Carlinkit-V3](https://github.com/Quikeramos1/Unbrik-Carlinkit-V3)
  (no license) -- bricked-device recovery for Carlinkit V3 hardware.
- `lvalen91/CPC200-CCPA-Firmware-Dump` redirects to
  [lvalen91/CPC200-CCPA_resources](https://github.com/lvalen91/CPC200-CCPA_resources)
  (not fetched in detail this pass) -- Carlinkit CPC200-CCPA firmware
  resources, a different adapter model than CarbitLink's
  `CarbitDriverOversea`. Note: the same author's
  [lvalen91/carlink](https://github.com/lvalen91/carlink) is "an
  Android app that enables phone projection via the Carlinkit CPC200-CCPA
  adapter" -- structurally the closest analogue to CarAALink of
  anything found this pass (an app replacing a dongle companion app via
  USB), but it wasn't in the requested list and hasn't been inspected --
  flagging as a candidate for a focused look if the BLECarbitlink lead
  doesn't pan out.

**No code changes made this pass** (none of the above rose to a
specific, source-backed fix -- the `set_configuration()` hypothesis is
a structural analogy, not confirmed against CarbitLink's own traffic).
Next real step, if pursued: a synchronized usbmon capture (host machine
as USB monitor between the phone and CarbitLink, using BLECarbitlink's
own `tools/` scripts as a reference for how) during one of our EIO
events, to see whether CarbitLink actually re-issues configuration at
that moment.

## 2026-11-04: latest head-unit retest

Log: `CarAALink-caraalink-20261104-022215.log.txt` (user test; current build
includes the state-aware server recovery changes).

**Head-unit server/reconnect remains unreliable.** At 02:24:05 the
accessibility recovery flow tapped Stop for an enabled-but-unresponsive
server, then Start when it observed the server off. The AA session ended
around 02:24:10. Later connection attempts reset twice (02:24:28 and
02:24:30); subsequent attempts completed TLS and service discovery, and
the stream ran until about 02:25:04. This confirms the recovery logic
acted, but did not make reconnect dependable; user still had to intervene
repeatedly.

**Confirmed teardown bug:** when that final session stopped, it attempted
`SHUTDOWN_REQUEST`, then logged `NetworkOnMainThreadException` at
02:25:04.392. `AaTlsChannel.sendControlEncrypted()` performs socket I/O,
so the graceful shutdown send is being attempted from the Android main
thread and fails. This means the intended clean disconnect/acknowledgment
path is not actually happening. Fix by moving the send and response wait
off the main thread while preserving bounded teardown; then verify a
disconnect/reconnect without manually toggling the server.

**TV animation looping:** user observed animations replaying several times
before settling, while preferring the current less-pixelated image over the
earlier pixelation. `CarSession` currently repeats `lastSentFrame` (or the
last key frame) when encoder output starves (`CarSession.kt`, around line
615). Replaying cached frames is a plausible contributor to the perceived
loop/stutter, but this log/user report does not prove causation. Inspect
frame timestamps and repeat behavior before changing it; preserve the
visual-quality improvement while reducing repeated animation frames.

No source code or APK was changed for this test report. The next focused
implementation work is the main-thread shutdown-send bug; separately
instrument or constrain cached-frame repeats to diagnose the animation
loop. The user is done testing for now, so do not request another test
round before documenting these findings.

### Follow-up implementation (2026-11-04)

Implemented the two focused fixes prompted by this capture:

- `AaSessionProbe.stop()` now sends `SHUTDOWN_REQUEST` and waits up to the
  existing acknowledgment timeout on a dedicated teardown thread. The probe
  remains active to receive `SHUTDOWN_RESPONSE` until that bounded wait ends;
  only then does teardown close the socket and interrupt the worker. This
  addresses the observed `NetworkOnMainThreadException` and avoids blocking
  the UI thread.
- `CarSession` now sends at most one starvation fallback replay until a
  newly selected frame is sent. Merely encoding frames queued for pull mode
  does not re-enable repeats. This
  reduces repeated stale-frame playback when the encoder stalls; it does not
  yet prove the reported TV animation loop came from this path.

`git diff --check` passes. `assembleDebug` could not complete: Android Gradle
Plugin failed while applying because the configured `/Volumes/SABRENT` volume
is unavailable in this session. No APK was produced, so device installation
and runtime verification remain outstanding.

## 2026-11-06: real-headunit evidence + reference-chain corruption fix

User got hands-on access to a real Jancar/AutoChips head unit and pulled
`net.easyconn` (the real CarbitLink IVI app), the other Jancar/AutoChips/
MediaTek system APKs, and logcat/shell dumps captured while CarbitLink was
actually mirroring on that hardware (`Reverse engineering Claude/`,
`diagnostics/openheadunit-2026-11-03/`). Confirmed from
`Logcat car but bigger.txt`: the real IVI's own Mirror surface is
`getPropertyMirrorWidth/Height` = 1024x600 (its native panel), separate
from whatever resolution CONFIG_CAPTURE negotiates with the phone --
useful ground truth, but not what was driving today's symptom.

**Symptom this round:** no longer "compressed JPEG mess" (resolved by the
round-9 H.264 level fix) -- now "TV glitches" (block corruption) and
"sometimes repeats motion".

**Root cause found and fixed, `CarSession.kt` `onEncodedFrame`:** on pull-
queue/push-backlog overflow, the old code dropped only the *one* frame
that overflowed and asked for a keyframe, then re-checked fullness on the
next frame. If the car drained a slot before that keyframe actually
arrived, the next non-key frame was re-enqueued and forwarded anyway --
but MediaCodec's internal reference chain still treats the frame we
silently dropped as the immediately-prior picture, so that forwarded
P-frame references a picture the car's decoder never received. That
reference mismatch is exactly what shows up as corrupted macroblocks
("TV glitches") until the next IDR happens to land. Separately, once the
8-deep pull queue backed up, the car would drain all 8 stale buffered
frames before seeing anything current -- i.e. it visibly plays back old
motion first, which is the "repeats motion" symptom.

Fix: added `discardingUntilKeyframe`, set on any overflow-drop and only
cleared when an actual keyframe arrives, so every non-key frame in
between is dropped too (not just the first). Overflow also now clears the
whole stale backlog immediately instead of letting the car drain it,
so the car blocks briefly on its next pull instead of replaying old
motion. Applied symmetrically to both the pull-queue and push-backlog
paths. `discardingUntilKeyframe` is also reset on stream reopen alongside
the other per-stream state.

Not yet device-tested -- built via CI (`aa-post-v1`, commit `b4a4756`),
no adb device was attached this session to install it. Needs a real
on-car run to confirm the glitch/repeat is actually gone.

## 2026-11-06 (cont'd): real pull-rate measurement + 60fps fix

New on-car log (`CarAALink-caraalink-20261106-070521.log.txt`) after the
overflow/reference-chain fix above. That fix never actually triggered
this run -- `pullQueue` stayed at 0-1 the whole session, never hit
`MAX_PULL_QUEUE`. So it's neither confirmed nor disproven by this
capture; just wasn't the active mechanism here.

What the log did show: `data stream closed; requests=9899 sent=9899
encoded=4373` over a ~134s session -- every pull got answered, but more
than half of everything sent (5526/9899) was a starvation-fallback
*repeat* of already-sent bytes, not a fresh encode. The car was pulling
at a measured ~75-79/s; the Mirror encoder was hardcoded to a 30fps
target in pull mode (`CarSession.kt` `onCaptureConfig`, `val fps = ...
else 30`). That's the real, log-confirmed mechanism behind "TV lag /
repeats motion" -- not corruption, a pacing mismatch where the car sees
the same picture repeated far more often than a new one.

Fix: raised the pull-mode encode target to 60fps so more pulls land on
genuinely fresh content instead of a repeat. Also decompiled the real
`net.easyconn` head-unit app (`EasyConnection.apk`, pulled off a real
Jancar/AutoChips head unit) and wrote up everything confirmed from it
plus prior captures into `docs/CarbitLink-Protocol.md` -- see that file
for the full writeup (PXC JSON control channel, Mirror negotiation,
touch/transport enums, and an explicit list of what's still unknown).

Session closed cleanly, then the dongle went fully unresponsive (`zero
kick attempt 1..50 failed: write failed: ENODEV`) ~2s later -- same
unresolved hardware-level dropout documented earlier, unrelated to this
fix.

Not yet device-tested (built via CI, `aa-post-v1` commit `25a6559`). User
said they won't have head-unit logcat access again soon -- next on-car
test should specifically watch whether the repeat ratio (not just
whether it connects) actually drops.

## 2026-11-06 (cont'd): AA dropping every ~60s -- gearhead crash lead revisited

User reported AA disconnecting "after a minute" with no fresh log this
time. `AA_WATCHDOG_STARTUP_MS = 60_000L` (`CarSession.kt`) is the exact
match: if `AaSessionProbe.hasReceivedVideo` never flips true within 60s
of a probe starting, the watchdog tears it down and restarts via
`openAndroidAuto()` -- if the underlying cause repeats every time, that's
an indefinite ~60s boot loop, matching the symptom.

Went back to the real, already-confirmed (not re-derived) finding in
`claude/roadmap.md` (2026-11-02, "gearhead itself crashes"): logcatting
gearhead directly caught `IllegalArgumentException: Virtual display
width must be positive` in gearhead's own `VirtualDisplayConfig.Builder`,
built from the `width_margin`/`height_margin` values *we* send in
`ServiceDiscoveryResponse`. If gearhead crashes building its display, no
video frame can ever arrive -- exactly what would trip the 60s
startup-grace watchdog repeatedly. That finding was left unresolved
("not yet cross-referenced against our own log... per-side-vs-total
question still open").

Fix (not a re-derivation of the crash itself, which was already real and
confirmed -- this is closing the gap defensively): `autoMargin()` in
`AaServiceDiscovery.kt` now caps each margin to under half its dimension,
so gearhead can't compute a non-positive VirtualDisplay size whether it
treats width_margin/height_margin as total or per-side removal. Does not
resolve which interpretation is correct -- just makes either one safe.

**Not confirmed as THE fix** -- no fresh gearhead-side log for this
exact session to prove this margin path is what's crashing right now
(the user won't have head-unit logcat access again soon). If AA still
drops on roughly the same ~60s cadence after this, the next real step is
logcatting `com.google.android.projection.gearhead` directly again
during one of these drops, same technique as the original finding.

## 2026-11-06 (cont'd): real head unit retest -- confirmed improvement on both fixes

New log (`CarAALink-caraalink-20261106-134948.log.txt`), this time from
the owner's actual Jancar head unit (`CONFIG_CAPTURE ... width=1024,
height=600` -- matches the real panel size confirmed earlier from
decompiling `net.easyconn`, not the 1920x720 test dongle).

**Mirror pacing fix (60fps target):** `data stream closed;
requests=18626 sent=18626 encoded=15382` over a ~302s session -- repeat
ratio dropped from ~56% (previous log, 30fps target) to ~17.4%. Owner's
own words: "way better than before... still like some tv lag". Real,
measured improvement; the residual ~17% is likely just normal encode-
timing jitter at this point rather than a gross pacing mismatch -- not
chasing further without a more specific symptom.

**AA margin-crash-prevention fix:** one transient `AA startup handshake
failed (SocketTimeoutException)` right after the probe started, which
triggered one normal recovery restart -- then AA ran clean for the next
~4 minutes with no further restarts before the capture ended. No sign of
the repeating ~60s boot loop this time. Owner couldn't test AA
specifically for long/well this round, so this is encouraging but not a
confirmed fix yet -- next test should specifically watch AA over a
longer stretch.

## 2026-11-06 (cont'd): live car session -- audio fix, EIO/ENODEV loop still unresolved, logcat-capture lesson

Live session at the car, with logcat running throughout.

**Fixed and shipped (commit `906f460`):** AA audio choppy for about a
second at the start of playback. Root cause: `AaAudioPlayer.feed()`
called `track.play()` on the very first decoded PCM chunk with nothing
else buffered, so playback started fully empty and had to race the
decoder until it happened to build a cushion -- matches "choppy for a
sec unless I pause so it catches up" exactly. Now holds back ~120ms of
PCM before the first `play()`. Not yet confirmed live by the owner this
session (installed, not retested before compaction).

**Audio-over-CarbitLink-wire: confirmed a non-issue, not a gap.** The
owner ran the real CarbitLink app live and it nagged "turn on your
Bluetooth" -- direct first-party confirmation that CarbitLink's own
audio path is Bluetooth A2DP, not anything carried in the USB wire
protocol. Our own `AaAudioPlayer.kt` already explicitly prefers
Bluetooth A2DP for AA's audio output (`AppSettings.aaAudioRoute` =
BT_PREFERRED, with a speaker fallback) -- already matches the real
system's architecture. No wire-protocol audio work needed.

**Head-unit-server auto-recovery: confirmed working in one capture.**
`HeadUnitServerLauncher.recover()` (a controlled Stop-then-Start cycle,
triggered automatically when an AA handshake fails) already exists from
an earlier round and worked cleanly in one live capture this session --
one handshake failure, one automatic recovery, then the session ran
clean. This was NOT built this session; just confirmed functioning once.

**Still unresolved: the EIO -> ENODEV x50 "give up" loop, reported by
the owner as 5 repeated "headunit server running -> CONNECTED TO YOUR
VEHICLE" splash cycles on the car, then stopping.** Real sequence from a
live capture:
```
18:42:00.203  Transport stopped: read failed: EIO (I/O error)
18:42:02.269  Opened accessory Carbit/CarbitDriverOversea
18:42:02.584-18:42:03.623  zero kick attempt 1..50 failed: write failed: ENODEV
```
The owner is confident the dongle itself does NOT physically disconnect
during this -- their read is that this is Android Auto (gearhead)
causing it, not a real USB-level drop. That's a genuinely open question,
not dismissed: the EIO/ENODEV happens on CarAALink's OWN accessory file
descriptor (`AdbTransport`/`UsbAccessoryConnection`, talking to the
dongle), a different channel than AA's local TCP socket to gearhead, so
there's no obvious *direct* shared resource -- but an indirect one (a
USB-gadget-level reset triggered by something gearhead does, even
without a physical unplug) hasn't been ruled out either. Needs a
logcat capture that spans both `car-session` and `GH.`/gearhead tags at
the *exact* moment of one of these EIO events to settle it either way --
not yet obtained.

**Capture-tooling lesson, important for next session:** `adb shell
"nohup sh -c '...' > /dev/null 2>&1 &"` did NOT reliably survive --
confirmed twice live: the capture process silently died a few minutes in
(before the actual event happened) with no error, leaving a stale file
that looked fine until checked against wall-clock time. Switched to
`setsid sh -c '...' </dev/null >/dev/null 2>&1 &` instead, confirmed
alive via `ps -A | grep logcat` (state `S`, not a zombie) after a 5s
wait and a reconnect cycle. Use `setsid`, not bare `nohup`, for any
future unattended on-device logcat capture, and verify it's still
running with a fresh `ps` check close to end of a test, not just at
the start.
