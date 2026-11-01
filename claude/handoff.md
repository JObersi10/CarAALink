# Handoff

## Where things stand

This is the first session on this project. The repo was just scaffolded:
Gradle project (`app` + `protocol` modules, Gradle wrapper included), CI
workflow, and this `claude/` doc set. **No protocol findings are baked into
the code yet** — the USB manifest filters, `UsbAttachReceiver`, and
`CarbitLinkProtocol` object are all deliberately empty stubs with TODOs,
per the no-fabrication rule in `claude.md`.

A reverse-engineering pass against `CarbitLink-EasyConnection_6.5.1_APKPure.apk`
(using the REA MCP tools / `/reverse-engineer-anything` skill) was started in
parallel with this scaffolding, covering: USB role + VID/PID, handshake,
frame format, crypto/obfuscation, video/audio/touch transport, and whether
protocol logic lives in native `.so` libraries. It also took a quick,
secondary look at `PhoneAuto-beta-2027-01-19.apk` for how it triggers Android
Auto's head-unit-server developer mode and captures the rendered session.

**Check the chat transcript / session history for that pass's findings before
re-running it** — if it already completed, its report should be reflected
into `roadmap.md`'s status table and this file's "Next steps" section. If it
hasn't completed yet, resume it rather than starting a fresh RE pass.

## Immediate next steps

1. Pull the RE pass's findings into `roadmap.md` (replace UNKNOWN rows with
   CONFIRMED + evidence citation, or a specific reason it's still open —
   e.g. "native .so, needs disassembly" or "needs usbmon capture").
2. For anything the RE pass flagged as needing live USB traffic: ask the
   owner (bgonesh@gmail.com account, repo owner `JObersi10`) to attempt a
   `usbmon` capture on a rooted phone sitting between the real CarbitLink
   app and the head unit (or confirm they have a hardware USB analyzer).
   Don't proceed on guesses for anything still unconfirmed at that point.
3. Once USB role + VID/PID are confirmed: fill in
   `app/src/main/res/xml/usb_device_filter.xml` (or an accessory-filter
   equivalent) and uncomment/adjust the matching intent-filter in
   `AndroidManifest.xml`.
4. Once frame format is confirmed: implement it in `protocol/`, each piece
   cited to its evidence.
5. Separately (not blocked on protocol RE): confirm on the owner's actual
   phone + Android version whether the AA head-unit-server toggle can still
   be automated via `AccessibilityService` the way PhoneAuto/CarCast
   Launcher reportedly do it — this is a feasibility check, not something to
   build speculatively.

## Repo / workflow notes

- GitHub: repo created fresh at `JObersi10/headunit-bridge`, then renamed to
  `JObersi10/CarAALink` once the project name was picked. The owner asked for
  it to be public, but the auto-mode permission classifier blocks visibility
  changes from this session ("Create Public Surface") — it's still private
  as of this note; flip it via `gh repo edit JObersi10/CarAALink --visibility
  public` or the GitHub UI. Work happens on branches + PRs, not direct
  pushes to `main`.
- The two source APKs (and their extracted folders) are gitignored — large,
  third-party binaries, not ours to redistribute. They live locally at the
  repo root for RE tooling to read directly.
- CI (`android-ci.yml`) builds `assembleDebug` on a hosted Ubuntu runner via
  `actions/setup-java` + `android-actions/setup-android` — it does not use
  the hardcoded local macOS JDK path; that's local-build-only.
