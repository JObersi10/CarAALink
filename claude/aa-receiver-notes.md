# Android Auto receiver — build notes, findings, and where it stops

Written 2026-11-01, at the end of a long live-testing session on the owner's
Samsung S25 Ultra (`SM-S938B`, Android Auto / gearhead `18.0.664244-release`).
This is the handoff for the AA half of CarAALink. The CarbitLink mirroring
half is untouched and still works — see `handoff.md`.

---

## 1. Why this exists

The car home screen, phone mirroring and touch all work. The **"Android Auto"
tile** was a stub that said "coming soon". Making it real means CarAALink has
to *become a head unit*: Android Auto does not render to a phone screen, it
renders to a head unit over a protocol. So the tile opens a TCP/TLS session to
the phone's own gearhead (`127.0.0.1:5277`, the "head unit server" dev mode)
and drives it.

Target the owner stated: tap the tile → real AA UI on the car at **native
resolution** (the CarbitLink link already negotiates 1920×720).

---

## 2. What was built

All new code is in `app/src/main/kotlin/dev/caraalink/aa/`.

| File | Purpose |
|---|---|
| `AaFraming.kt` | AA frame/message codec — header, frame types, size fields, control message ids |
| `AaProtobuf.kt` | Minimal protobuf reader/writer (varints + length-delimited). No codegen, no deps |
| `AaIdentity.kt` | Loads the head-unit cert chain + key, builds `SSLContext` (full chain *and* leaf-only variants) |
| `AaTlsChannel.kt` | Established-channel I/O: frame read/write, message reassembly, encrypt/decrypt via `SSLEngine` |
| `AaSessionProbe.kt` | The working end-to-end probe: version exchange → TLS → wait for control messages |
| `AaVersionProbe.kt` | Rung 1 in isolation (version exchange only) |
| `AaTlsReceiver.kt` | Earlier attempt: raw TLS over the socket. **Kept for the record but superseded — see §4** |

Assets: `app/src/main/assets/aa/{headunit.crt,headunit.key,PROVENANCE.md}`.

### Wiring

- Developer Tools: "Start AA TLS receiver", "Start AA version probe".
- Debug-only headless triggers (refuse to run in a non-debuggable build):

```bash
adb shell am start -n dev.caraalink/.MainActivity --es aa_probe session    # full probe
adb shell am start -n dev.caraalink/.MainActivity --es aa_probe start      # version only
adb shell am start -n dev.caraalink/.MainActivity --es aa_probe stop
adb shell am start -n dev.caraalink/.MainActivity --es aa_tls start        # old raw-TLS attempt
```

- Car home tile → `AaSessionProbe`'s sibling path; status is narrated to the car
  screen via `AaTlsReceiver.statusSink`.
- Logs: `aa-session-*.log`, `aa-version-*.log`, `aa-tls-*.log`. Developer
  Tools → "Export latest log" now writes **one merged** `all-logs-*.log`
  (all tags interleaved by timestamp) instead of five separate attachments.
- Raw on-device: `/storage/emulated/0/Android/data/dev.caraalink/files/logs/`

---

## 3. Confirmed protocol facts (with sources)

Everything below is cited. Nothing here is inferred from a plausibility guess.

### 3.1 Where the identity came from

`PhoneAuto-beta-2027-01-19/classes.dex` contains three PEM constants at
offsets 1735456 / 1736618 / 1737906: an X.509 **v1** leaf
(`O=JVC Kenwood,OU=01`, issued by `O=Google Automotive Link`, serial `0x1B`,
sha256 `1C:0E:0E:F9:…:3C:35`), the self-signed `CA:TRUE` Google Automotive
Link CA (`49:E5:2E:FC:…:D2:7C`), and the matching RSA-2048 private key.

That leaf+key pair is **byte-identical** to `cert/headunit.crt` /
`cert/headunit.key` in [`opencardev/aasdk`](https://github.com/opencardev/aasdk),
and aasdk's `cert/README.md` calls it "a publicly available certificate used in
Android Auto head units … not a secret". So this is public interop material,
**not** CarbitLink's crypto — `claude.md`'s rule about not porting CarbitLink's
RSA path is not violated. Full caveats in `assets/aa/PROVENANCE.md`.

Corroborated from the reference app's own runtime log:

```
SslWrapper: init: rootCert length=1285 clientCert length=1159 clientKey length=1675
SslUtils: readPrivateKey: isPkcs1=true
```

1285 = our CA cert, 1159 = our leaf, 1675 = our PKCS#1 key. Same material.

### 3.2 Frame format — `aasdk/src/Messenger/*`

```
frame  = [channelId:1] [flags:1] [size] [payload]
flags  = ENCRYPTED(0x08) | CONTROL(0x04) | frameType
frameType: MIDDLE=0x00 FIRST=0x01 LAST=0x02 BULK=0x03 (FIRST|LAST)
size   = SHORT: uint16 BE payload length
         EXTENDED (FIRST frames only): uint16 BE frame length + uint32 BE total length
```

- `ChannelId.hpp` — CONTROL=0, SENSOR=1, MEDIA_SINK=2, MEDIA_SINK_VIDEO=3,
  INPUT_SOURCE=8, …
- `EncryptionType.hpp` — `PLAIN=0`, `ENCRYPTED=1<<3`.
- **Header and size stay plaintext even on encrypted frames.**
  `MessageOutStream::compoundFrame` writes header+size, then
  `Cryptor::encrypt()` appends only the wrapped *payload*.
- `MessageId.cpp` — message id is `uint16` big-endian, first 2 bytes of the
  control payload.

### 3.3 Message flow — `ControlServiceChannel.cpp`

- Version request body: message id `1`, then major/minor as two `uint16` BE.
  aasdk advertises 1.6 (`include/aasdk/Version.hpp`).
- **TLS runs *inside* PLAIN control messages**, id `3` = `MESSAGE_ENCAPSULATED_SSL`.
  This is why raw TLS on the socket can never work.
- **Direction (important, got this wrong at first):** the *phone* sends
  `SERVICE_DISCOVERY_REQUEST` (id 5); the **head unit** answers with
  `SERVICE_DISCOVERY_RESPONSE` (id 8), ENCRYPTED. aasdk only ever
  `handleServiceDiscoveryRequest` / `sendServiceDiscoveryResponse`.
- `MESSAGE_AUTH_COMPLETE` (id 4) is sent **PLAIN**.

### 3.4 Control message ids — `ControlMessageType.proto`

```
1 VERSION_REQUEST   2 VERSION_RESPONSE   3 ENCAPSULATED_SSL   4 AUTH_COMPLETE
5 SERVICE_DISCOVERY_REQUEST   6 SERVICE_DISCOVERY_RESPONSE
7 CHANNEL_OPEN_REQUEST        8 CHANNEL_OPEN_RESPONSE
11 PING_REQUEST              12 PING_RESPONSE      15/16 BYEBYE
```

---

## 4. Live results

### 4.1 Rung 1 — version exchange: **WORKS**

```
TX 00 03 00 06 00 01 00 01 00 06          (control, BULK, PLAIN, msg 1, ver 1.6)
RX 00 02 00 01 00 07 00 00                (VERSION_RESPONSE, 1.7, status 0)
```

Replied in ~108 ms. Reproduced on every clean attempt.

### 4.2 Rung 2 — TLS encapsulated in AA frames: **WORKS**

```
TX ENCAPSULATED_SSL 138B  (ClientHello)          ← matches reference app exactly
RX ENCAPSULATED_SSL 2347B (ServerHello..Done)
TX ENCAPSULATED_SSL 1191B (cert + CKE + Finished) ← matches reference app exactly
RX ENCAPSULATED_SSL 51B
TLS ESTABLISHED TLSv1.2 TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256
  local[0] 'OU=01,O=JVC Kenwood,...'                     accepted, no alert
  peer[0]  'O=CarService,L=Mountain View,ST=California'
  peer[1]  'O=Google Automotive Link'  = THE SAME CA WE CARRY
```

The phone's `CarService` cert chains to the same Google Automotive Link CA we
ship, so both ends sit in the same PKI. The X.509 v1 encoding that OpenSSL 3
rejects (`format error in certificate's notBefore`) is **fine on Conscrypt** —
no alert, handshake completes.

### 4.3 Rung 3 — service discovery: **PHONE ASKS, WE GET REJECTED**

Originally the phone sent **nothing** after the handshake (15 s read timeout,
zero frames). That was not a protocol rejection — we were missing a step.

**`AUTH_COMPLETE` was the unlock.** openauto's
`AndroidAutoEntity::onHandshake()` sends, the moment the handshake completes:

```cpp
aap_protobuf::service::control::message::AuthResponse authCompleteIndication;
authCompleteIndication.set_status(aap_protobuf::shared::MessageStatus::STATUS_SUCCESS);
controlServiceChannel_->sendAuthComplete(authCompleteIndication, ...);
```

`ControlServiceChannel` never *handles* `MESSAGE_AUTH_COMPLETE`, so it is
head-unit → phone only. We weren't sending it, so the phone had no reason to
proceed. Sending it as **PLAIN, msg id 4, `AuthResponse{status=0}`**
(`0x08 0x00`) immediately after the handshake immediately produced:

```
RX MESSAGE SERVICE_DISCOVERY_REQUEST
  icon field 1 (152B)  icon field 2 (240B)  icon field 3 (411B)
  label_text='Android'  device_name='samsung SM-S938B'  phone_info (38B)
```

So rung 3 inbound works. **What fails is our response:** the phone closes the
socket ~0.82 s later, every time.

```
TX SERVICE_DISCOVERY_RESPONSE 223B (video=1920x1080@30fps h264bp,
                                   touch=1920x720, name='CarAALink')
failed: EOFException
```

Gearhead logs `GEARHEAD_ENDPOINTS_FAILED` just before teardown. Our response was
decoded field-by-field and is structurally valid protobuf (correct field
numbers, wire types, nesting) — so this is a **content** rejection, not a
malformed frame. Fixes already applied that did *not* resolve it:

- `MediaSinkService.available_type` corrected from `MEDIA_CODEC_AUDIO_PCM` to
  `MEDIA_CODEC_VIDEO_H264_BP` on the video sink, plus
  `available_while_in_call=true` (matches openauto's
  `VideoMediaSinkService::fillFeatures`).
- Added a media audio sink (AAC-LC, `AUDIO_STREAM_MEDIA`, 48 kHz/16-bit/stereo)
  and the rest of the services openauto registers (sensor, media source,
  media playback, phone status, media browser, vendor extension, generic
  notification, wifi projection). Skipped only the ones with required fields we
  have no value for: bluetooth (`car_address`) and navigation_status
  (interval + cluster type).
- `connection_configuration.ping_configuration` (3000/1000/200/5) and
  `headunit_info` added, copied from openauto.

Next move is a byte-level diff against a known-good response, or bisecting the
remaining fields — see §7.

---

## 5. The reference-app comparison (the useful part)

`PhoneAuto` works against this same phone. Device-side logcat capture of a
successful PhoneAuto session gave us the ground truth:

```
17:05:13  AHU.TCP_CONNECTION: Connecting to head unit server 127.0.0.1:5277
          AHU.TCP_CONNECTION: Connected to head unit server 127.0.0.1:5277
          AHU.MAIN_ACTIVITY: Server socket created, listen on port: 5288
          AHU.MAIN_ACTIVITY: launchWirelessAAP: sent broadcast to 127.0.0.1:5288
          AHU.MAIN_ACTIVITY: mDNS registered: SM-S938B TarPlay
          SslWrapper: SSLEngine initialized, enabled=[TLSv1.2]
          p5: Starting link. Requested protocol version: 4.1

  (first session dies: "Received unrecoverable error STATUS_FRAMING_ERROR",
   "Reconnecting in 2000 ms (failed sessions: 1)")

17:05:29  p5: Phone reported protocol version 6.1
          p5: startAuthentication: calling handshake(null)
          SslWrapper: handshake: NEED_UNWRAP but no input, returning 138 bytes
          p5: startAuthentication: got 138 bytes, sending as ENCAPSULATED_SSL
          p5: Sending 1191 bytes of SSL data, finished=false
          SslWrapper: handshake: processing 51 bytes from peer (round 3)
          SslWrapper: handshake: complete, status=NOT_HANDSHAKING protocol=TLSv1.2
          p5: Peer verified successfully, enabling encryption
          p5: Discovery reply: services=14 driverPos=DRIVER_POSITION_LEFT
              probeOnly=false serializedSize=768
```

and on the gearhead side, for that session:

```
GH.DHUService: Network server running on port 5277
GH.DHUService: Head unit connected
```

PhoneAuto reuses the same cert (1285/1159/1675), the same TLS role, and the
same `SSLEngine` approach. Two concrete deltas were found and **fixed** in our
code:

1. **TLS version** — they pin `enabled=[TLSv1.2]`; we offered 1.2+1.3, which
   made our ClientHello 233 B vs their 138 B. Now pinned to TLSv1.2.
2. **Client chain** — they send the **leaf only** (`clientCert length=1159`,
   flight = 1191 B). We were sending leaf+CA (flight 2103 B). Now
   `sslContextLeafOnly`. The CA is still the trust anchor, just not sent.

After both fixes our flight is **1191 B**, byte-for-byte the same size as theirs.
The phone still does not send discovery. So the remaining difference is
somewhere else.

### Candidate remaining differences (unproven, in order of suspicion)

1. **The version exchange numbers.** PhoneAuto requests a version it logs as
   `4.1` and the phone answers `6.1`; we request 1.6 (aasdk's value) and the
   phone answers `1.7`. If the phone gates post-TLS behaviour on the negotiated
   protocol version, requesting a legacy version could put it in a mode where
   it does not drive discovery. **This is the top suspect.** It is also possible
   that `4.1`/`6.1` are PhoneAuto's own link-layer numbers rather than AA's —
   that needs resolving before acting on it.
2. **`GH.DHUService: Head unit connected`** — gearhead logged this for
   PhoneAuto. We have not yet captured gearhead's log during one of *our*
   sessions (the first capture window ended before our run, and the second run
   got no version reply at all). Confirming whether gearhead logs the same for
   us is the cheapest next experiment and would immediately say whether we
   fail at connection level or later.
3. PhoneAuto also runs a **second server on 5288**, broadcasts to
   `127.0.0.1:5288`, and registers an **mDNS service**. Possibly irrelevant to
   the wired path, possibly not.

---

## 6. Operational gotcha: the head unit server is fragile

Reproduced repeatedly:

- Gearhead's `5277` listener will accept a TCP connection into its **backlog
  and never `accept()`** it. Symptom: `ss -ltn | grep 5277` shows
  `LISTEN  N  0` with N climbing as we connect; we get a connected socket and
  zero bytes back. A **restart of the head unit server fixes it instantly**
  (backlog drains to 0 and the version response comes in ~100 ms).
- After a session dies uncleanly the server goes deaf for the next connection or
  two, then briefly drops the listener entirely (`ECONNREFUSED`), then rebinds.
- Therefore: **toggle the head unit server before each test, and prefer one
  clean attempt over a retry storm.** `AaSessionProbe` retries 3× / 2 s as a
  hedge, but the retries are what walks it into the deaf window.
- Useful diagnostic:
  `adb shell "ss -ltn 2>/dev/null | grep 5277"` — non-zero Recv-Q means a
  stale listener, restart the server.

---

## 7. Where to pick up

1. **Get the discovery response accepted — this is the only real blocker.**
   The phone asks us, then rejects our answer and closes after ~0.82 s with
   `GEARHEAD_ENDPOINTS_FAILED`. Our protobuf is valid, so bisect content:
   dump our bytes (the probe already logs `TXBODY` hex), decode them, and
   compare against a known-good head unit. Best oracle: a byte-level diff
   against openauto's `fillFeatures()` output, or a capture of a working head
   unit on this same phone. Candidates to bisect: the service set/ids, video
   and audio config values, `driver_position`, `display_id`, `ui_config`.
2. Once accepted, the phone sends `CHANNEL_OPEN_REQUEST` per channel. Answer
   with `CHANNEL_OPEN_RESPONSE` (msg id 8) on **each service's own channel**
   (not the control channel).
3. Video: send the video config on the video channel, request video focus, and
   H.264 frames start arriving. AA already sends H.264, so this is a
   **pass-through** into the CarbitLink video path — not `CarVideoEncoder`,
   which re-encodes from a Surface and is for the mirroring path.
4. Touch after that: map car touch events onto the input channel.
5. Optional, later: audio channels (AAC) exist in the response already but are
   unimplemented on the receive side.

---

## 8. Bugs found and fixed along the way (worth not re-introducing)

1. **Discarded leftover TLS bytes.** After `unwrap`, the remaining buffered
   bytes were thrown away and the code blocked waiting for a new frame — the
   phone had already sent them. Deadlocked at ServerHello. Fixed by keeping a
   `pending` buffer and feeding it back before reading more. This is the single
   bug that cost the most time.
2. **Message completion compared plaintext to ciphertext length.** A message
   ends at its LAST frame; comparing accumulated decrypted size against
   `totalSize` (which counts ciphertext) meant encrypted messages could never
   complete. Fixed: `if (frame.isLast) break`.
3. **Wrong TLS role assumption.** Raw TLS as server, then as client, both timed
   out for 15–106 s — because TLS is encapsulated in AA frames, not on the
   socket. `AaTlsReceiver` is that dead end, kept only for reference.
4. **Wrong service-discovery direction.** We sent a discovery *request*; the
   head unit is supposed to send the *response*. Corrected.

---

## 8b. Resolution — the owner's question, answered

AA has **no 1920x720 mode**. `VideoCodecResolutionType` is:

```
VIDEO_800x480=1  VIDEO_1280x720=2  VIDEO_1920x1080=3  VIDEO_2560x1440=4
VIDEO_3840x2160=5  + portrait 720x1280 / 1080x1920 / 1440x2560 / 2160x3840
```

And AA does **not** render at the panel size. openauto simply does
`videoWidget_->setGeometry(screenGeometry)` (`QtVideoOutput.cpp`) and the video
is scaled into the widget. Its `width_margin`/`height_margin` are user settings,
not an auto-fit — their exact AA semantics are **unconfirmed**.

Conclusion: request **1920x1080 @30 fps H.264 Baseline (16:9)** — the default in
`AaServiceDiscovery.Config` — and let the CarbitLink side fit that into the car's
1920x720. Don't chase a native 1920x720 AA mode; it does not exist.

## 9. Honest status

- **Working and reproducible:** framing, version exchange, TLS handshake with
  the real head-unit identity, matching the reference app's handshake profile
  byte-for-byte in size.
- **Working:** the phone treats us as a real head unit — it asks us for service
  discovery, which is the gate everything else sits behind.
- **Not working:** the phone rejects our discovery response and drops the link
  after ~0.82 s. No channel opens, no video, no touch. The AA tile starts the
  receiver and ends at that rejection rather than showing AA on the car.
- **Effort remaining:** substantial but bounded. Get the discovery response
  accepted → handle `CHANNEL_OPEN_REQUEST` → video config + focus → pass H.264
  NALs into the CarbitLink video path → map touch onto the input channel. This
  document exists so that work starts from verified ground truth instead of
  repeating the dead ends above.
