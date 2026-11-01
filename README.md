# CarAALink

Native Android app that replaces a CarbitLink wireless-CarPlay/Android-Auto
USB dongle. The phone plugs directly into a car's factory head unit
(CarbitLink-protocol only, no native CarPlay/AA support) and:

1. Starts Android Auto's own built-in head-unit-server developer mode on the
   phone, making the phone itself the AA host/renderer.
2. Captures that rendered AA session (video, audio, touch input).
3. Re-encodes it into CarbitLink's actual wire protocol and serves it over
   USB to the head unit.

This is a personal interoperability / reverse-engineering project — a
from-scratch client for hardware the owner already owns, in the same space
as hobbyist re-implementations of cheap USB CarPlay/AA dongles.

## Status

Early scaffold. The CarbitLink wire protocol is being reverse-engineered
from the official companion app before any of it is implemented — see
[`claude/roadmap.md`](claude/roadmap.md) for current findings and
[`claude/claude.md`](claude/claude.md) for the ground rules (most importantly:
no fabricated protocol details — everything is cited to evidence or marked
unknown).

## Project layout

- `app/` — Android application module (USB attach handling, AA bring-up,
  capture pipeline)
- `protocol/` — isolated module for the reverse-engineered CarbitLink wire
  protocol; empty until confirmed by evidence
- `claude/` — working notes: ground rules, roadmap, handoff
- `.github/workflows/` — CI (Gradle `assembleDebug` on a hosted runner)

## Building

```bash
./gradlew assembleDebug
```

## License

Personal project; no license granted for redistribution yet.
