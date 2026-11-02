# CarAALink

A native Android replacement for a CarbitLink wireless-CarPlay/Android-Auto
USB dongle. The phone plugs straight into the car's factory head unit — which
only ever speaks CarbitLink's own protocol, not CarPlay or AA natively — and
the app does the rest.

Two independent pieces live in this one app:

- **Phone-screen mirroring to the car** — the phone mirrors its own screen
  (via `MediaProjection`) onto the car's display through the CarbitLink wire
  protocol, with touch replayed back onto the phone. **Working, tested live
  on hardware.**
- **An Android Auto receiver** — the phone puts itself into Android Auto's
  own head-unit-server developer mode and this app *becomes* the head unit:
  real TLS handshake, service discovery, channel negotiation, and a live
  H.264 video decode of the actual AA session (Maps, Spotify, the lot).
  **Working, verified live** — version exchange, TLS, discovery, channel
  open, media setup, and continuous video streaming are all confirmed against
  a real phone (`gearhead 18.0.664244`, Samsung Galaxy S25 Ultra). An
  on-phone preview (Developer Tools → "Preview AA session") renders and lets
  you interact with the live AA session without the car, for testing.

This is personal interoperability / reverse-engineering work on hardware the
owner already owns — the same category as hobbyist re-implementations of
cheap USB CarPlay/AA dongles. The head unit itself never speaks CarPlay or AA;
only CarbitLink's own protocol.

## Status

- **CarbitLink mirroring**: working end to end on real hardware — handshake,
  video, touch, car home screen, GPS/network passthrough.
- **Android Auto receiver**: working through live H.264 video. Not yet wired:
  piping those decoded frames into the CarbitLink video path (currently a
  separate on-phone preview), and the input/touch channel back to the car.

See [`claude/handoff.md`](claude/handoff.md) for the current state in detail,
[`claude/aa-receiver-notes.md`](claude/aa-receiver-notes.md) for the AA
protocol findings (all cited to source or a live capture — see the ground
rules below), and [`claude/roadmap.md`](claude/roadmap.md) for the CarbitLink
wire protocol findings.

## Ground rules

No protocol detail — CarbitLink's wire format or Android Auto's — is ever
guessed. Every claim traces to a decompiled source reference, a cited
open-source implementation (`opencardev/aasdk`, `opencardev/openauto`), or a
live capture. Anything unconfirmed is marked `UNKNOWN`, not filled in with a
plausible guess. Full rules in [`claude/claude.md`](claude/claude.md).

## Project layout

- `app/` — the Android application: USB handling, the AA receiver
  (`app/src/main/kotlin/dev/caraalink/aa/`), the CarbitLink capture/video
  pipeline, settings, diagnostics
- `protocol/` — isolated module for the reverse-engineered CarbitLink wire
  protocol
- `claude/` — working notes: ground rules, roadmap, handoff, AA findings
- `.github/workflows/` — CI: builds `assembleDebug` on every push/PR, and
  publishes a rolling **dev pre-release** APK on every push to `main` (see
  below)

## Building

```bash
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

## Getting a build

Tagged releases are published manually by the owner. For a build that
always matches the tip of `main` — unsigned, untested, may be broken —
grab the **[`dev` pre-release](../../releases/tag/dev)**, rebuilt
automatically on every push to `main`.

## License

Personal project; no license granted for redistribution yet.
