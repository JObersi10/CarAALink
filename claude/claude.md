# claude.md — working agreement for this repo

## What this project is

A native Android replacement for a CarbitLink wireless-CarPlay/Android-Auto
USB dongle. The phone plugs directly into the car's factory head unit (which
only speaks CarbitLink's proprietary USB protocol) and the app:

1. Puts the phone into Android Auto's own head-unit-server developer mode
   (phone becomes the AA host/renderer).
2. Captures that rendered AA session (video, audio, touch-in) via
   `MediaProjection`/`VirtualDisplay` or an equivalent on-device capture path.
3. Re-encodes that output into CarbitLink's actual wire protocol and serves
   it over USB to the head unit — replacing the dongle + phone-app pair.

This is interoperability reverse engineering on hardware and an app the owner
owns, for personal use, same category as hobbyist re-implementations of cheap
Chinese CarPlay/AA dongles. The head unit never speaks CarPlay or AA natively,
only CarbitLink's own protocol.

## Hard rule: no fabricated protocol details

Nothing about the CarbitLink wire protocol (step 3 above) may be invented,
assumed, or written as "plausible-looking" code. Every protocol claim —
USB role, handshake bytes, frame format, crypto, video/audio/touch encoding —
must trace to one of:

- A specific reference in the decompiled/disassembled CarbitLink APK
  (class, method, smali, string, resource file), or
- A specific capture artifact (usbmon trace, protocol analyzer dump).

If something isn't confirmed by one of those, say so explicitly in
`roadmap.md` / `docs/protocol/` as UNKNOWN or PARTIAL, rather than stubbing in
a guess. The `protocol/` Gradle module exists specifically to isolate this:
if the real protocol has per-session crypto/auth we can't fully replicate,
that limitation stays visible there, not silently papered over elsewhere.

## Source material

- `CarbitLink-EasyConnection_6.5.1_APKPure.apk` (+ extracted folder) — the
  real CarbitLink companion app; primary RE target for the wire protocol.
  Gitignored (large, third-party binary — not ours to redistribute).
- `PhoneAuto-beta-2027-01-19.apk` (+ extracted folder) — closed-source
  reference proving "phone as its own AA head unit" is viable on stock
  Android (same category as tarplay-public / CarCast Launcher). No public
  source, so it's evidence of feasibility only, never a code reference.
  Also gitignored.

## Workflow

- Work via PRs, not direct pushes to `main` — this is a reverse-engineering
  project, protocol understanding will revise as new evidence comes in.
- Use the `/reverse-engineer-anything` skill (REA MCP tools) for any new
  static-analysis pass on either APK. Cite Evidence IDs / file paths in
  `roadmap.md` when a finding lands. Note: the REA Android tools
  (`inspect_android_package`, `search_android_classes`, etc.) need
  `REA_JADX_MCP_JAR` pointed at a jadx-headless-mcp JAR, which isn't
  configured in this environment. When that's the blocker, `jadx` itself
  *is* installed (`brew`), so fall back to the plain CLI:
  `jadx -d <output-dir> <path-to-apk>`, then read/grep the decompiled
  `sources/` tree directly — no MCP server needed for this.
- If static analysis can't resolve something (native `.so` logic, obfuscation,
  per-session crypto), say so and switch to a live USB capture
  (`usbmon` on a rooted phone acting as USB host between the real CarbitLink
  app and the head unit, or a hardware USB analyzer) rather than guessing.

See `roadmap.md` for current status and `handoff.md` for the next concrete
steps a new session should pick up.
