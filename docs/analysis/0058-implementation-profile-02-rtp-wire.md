# ADR-0058 Implementation Profile 02 — RTP / Wire

## Status

```text
Profile:                FROZEN — CONTRACT COMPLETE / CLOSED
Grill:                  Q1–Q10 PASS
LimitedAmend:           Q6 replayWindowPackets = 64 (ownership + width)
Parent:                 ADR-0058 ACCEPTED — CONTRACT ONLY
Profile 01:             FROZEN — CONTRACT COMPLETE
Underlay:               ADR-0059 ACCEPTED — PRODUCT RISK ASSUMPTION
Implementation:         AUTHORIZED (Profiles 01/02/03; E2b PENDING)
Conference multicast:   NOT IMPLEMENTED
ADR-0056:               RETAINED
```

This Profile maps the ADR-0058 media packet semantics to one fixed V1 Opus,
RTP, header-extension and wire envelope. It MUST NOT modify Membership, Owner,
Source, key, collision or Health authority.

## Current baseline and terminology

Earlier product documents use “Opus 16 kHz mono” as an audio-bandwidth/input
description, while the existing Conference mix specification uses 48 kHz PCM.
Neither value may redefine the Opus RTP clock. RFC 7587 fixes the RTP timestamp
clock at 48,000 Hz for every Opus mode and sampling rate.

```text
PCM processing rate
  = local capture/processing/decoder rate

encoded audio bandwidth
  = acoustic frequency range selected inside Opus

RTP clock rate
  = timestamp units on wire; always 48,000 Hz for Opus
```

Normative external references:

- RFC 7587, RTP Payload Format for Opus: https://www.rfc-editor.org/rfc/rfc7587.html
- RFC 3550, RTP: https://www.rfc-editor.org/rfc/rfc3550.html
- RFC 3551, RTP A/V Profile: https://www.rfc-editor.org/rfc/rfc3551.html

## Q1 — Fixed Opus/RTP baseline

**ACCEPTED — A**

V1 has one non-negotiated media profile:

```text
rtpWireProfileId       = 1
codec                  = Opus
Opus application       = VOIP
Opus signal            = VOICE
channels               = 1 (mono)
PCM processing rate    = 48,000 samples/s
encoded bandwidth      = OPUS_BANDWIDTH_WIDEBAND
RTP version            = 2
RTP payload type       = 111 (fixed dynamic payload type)
RTP clock rate         = 48,000 Hz
packetization          = 20 ms
timestamp increment    = 960 per scheduled 20 ms media-time grid slot
Opus frames/RTP packet = exactly 1
target bitrate         = 24,000 bit/s
rate control           = constrained VBR
Opus payload hard max  = 72 octets
Opus in-band FEC       = OFF
Opus DTX suppression   = OFF
RTP padding            = OFF
```

The committed `TrafficProfile.codec = 1` means this complete Profile, not merely
“some Opus”. V1 does not use SDP offer/answer, runtime codec negotiation or a
HELLO-selected payload type. A device that cannot execute Profile 1 is not
media-ready; it MUST NOT silently substitute another clock, channel count,
packetization or bitrate.

### Continuous packet cadence

Every admitted active Source emits one RTP packet every 20 ms, including during
silence:

```text
50 RTP packets/second/Source
voiceActive = 0 represents non-speech
voiceActive = 1 represents speech under the later frozen level semantics
```

The encoder MAY reduce silence payload size through constrained VBR but MUST NOT
suppress the packet. Sequence-number gaps therefore remain loss/reorder evidence,
not DTX. Continuous cadence provides authenticated level updates, avoids a new
transport start at speech onset and exactly matches the frozen full-source
Traffic Profile; capacity tests MUST NOT enable DTX or VAD packet suppression.

Comfort Noise payloads under RFC 3389 are not used. PLC behavior belongs to the
Media Runtime Profile and MUST NOT cause synthetic RTP emission.

### Packetization and oversize behavior

Each RTP payload contains exactly one Opus packet representing one 20 ms frame.
V1 forbids aggregation of multiple frames and forbids application fragmentation
of an Opus packet across RTP packets.

```text
encoded Opus payload <= 72 octets
```

The encoder receives a 72-octet output bound. If it cannot encode a legal frame
inside the bound, that frame is an explicit local encode failure/drop; the
sender MUST NOT emit an oversized packet, split it, silently lower the RTP
profile or reuse the sequence number for different payload bytes.

The final protected packet budget is reserved now and proven after the Q2
header layout is frozen:

```text
RTP fixed header                12 octets
HE and extension framing       <= 20 octets reserved
Opus payload                   <= 72 octets
AEAD-GCM SRTP authentication   16 octets
------------------------------------------------
complete SRTP packet/UDP body  <= 120 octets
```

IP, UDP, link and PHY headers are outside this 120-octet value and MUST be
reported separately by the final wire/airtime envelope. No later HE decision
may consume more than the reserved 20 octets or enlarge the frozen Gate load
without reopening the affected capacity assumption.

### RTP source initialization and continuity

For every new Q5 Source incarnation:

```text
initial sequence number = cryptographically random 16-bit value
initial RTP timestamp    = cryptographically random 32-bit value
SSRC                     = fresh random value already bound by Source Declaration
CSRC count               = 0; V1 sends no CSRC list
```

The Source owns one continuous 20 ms media-time grid. Its random initial RTP
timestamp fixes that Source incarnation's residue class modulo 960. Every
scheduled frame advances media time by 960, including silence and a frame that
is not emitted because of the explicit encode-failure/drop path above.

Sequence advances only for an RTP packet that is actually emitted. Therefore,
after one or more encode-failed/dropped frames, the next emitted packet has the
next sequence number but may advance timestamp by `960 * k`, where `k >= 1` is
the number of elapsed 20 ms grid slots. Every legally emitted timestamp remains
in the initial modulo-960 residue class. This rule is sender-side media-time
semantics; a receiver observing the same timestamp jump cannot infer encode
failure because intervening RTP packets may instead have been lost in the
network.

Timestamp skipping is allowed only for the explicit encode-failure/drop path.
It MUST NOT implement DTX/VAD packet suppression, Mesh/interface recovery or a
Native media-pipeline rebuild. Normal operation still emits exactly one packet
per 20 ms slot. Mesh/interface recovery preserves the current Source timeline;
a Native sender-pipeline rebuild requires the new Source incarnation and fresh
random RTP state defined below. Legal sequence/timestamp wrap is normal RTP
continuity and does not itself create a Source incarnation.

The RTP marker bit is `1` only on the first packet after an authenticated local
`voiceActive` transition from 0 to 1 (start of talkspurt); otherwise it is `0`.
Marker does not grant Source admission, Top-K priority or Membership authority.

Mesh route change, multicast interface rebind, receiver decoder rebuild,
jitter-buffer reset and temporary transport impairment MUST NOT reset the
sender sequence, timestamp or SSRC. A sender media-pipeline restart follows
ADR-0058 Q5/Q11: new Source incarnation, new SSRC and new random RTP initial
state before any packet is emitted.

### Explicit Q1 exclusions

Q1 does not yet freeze:

```text
RTP header-extension profile, IDs or discriminator bits
audioLevel / voiceActive numerical encoding
pre-auth Packet Context Hint layout
RTCP presence or feedback policy
SRTP rollover/replay-window implementation
IP/UDP/PHY overhead and multicast socket behavior
```

Those mechanisms MUST preserve the Q1 cadence, timestamp and 120-octet protected
packet ceiling.

## Q2 — Header-extension container and Packet Context Hint

**ACCEPTED — A**

Every V1 media packet uses the RFC 8285 one-byte RTP header-extension form. The
mapping is fixed by `rtpWireProfileId = 1`; V1 does not negotiate `extmap` or
allow a sender-local extension ID.

```text
RTP X bit                 = 1
defined-by-profile        = 0xBEDE
extension length          = 4 32-bit words
extension element ID      = 1
element length field      = 14 (15 data octets)
extension URI             = urn:talkback:rtp-hdrext:conference-media-context:v1
additional elements       = forbidden in V1
padding octets            = none
```

The fixed encoding occupies exactly the Q1 reservation:

```text
RTP extension profile + length   4 octets
one-byte element header          1 octet
TalkbackMediaContextV1 data     15 octets
-----------------------------------------
total HE overhead               20 octets
```

The 15 data octets are allocated once for V1:

```text
offset 0..7    keyContextHint64          Q2
offset 8..11   sourceDiscriminator32     Q3
offset 12..13  senderDiscriminator16     Q3
offset 14      voiceActive/audioLevel    Q4
```

All multi-octet values use network byte order. No implementation may insert
platform padding, reinterpret them as native-endian integers or change the
length when a field is zero.

### keyContextHint64

The first eight octets are:

```text
RTP_KEY_CONTEXT_HINT_DOMAIN =
  ASCII "TALKBACK-RTP-KEY-CONTEXT-HINT-V1" followed by one NUL octet

keyContextHint64 = first 8 octets of SHA-256(
  RTP_KEY_CONTEXT_HINT_DOMAIN
  || conferenceId              // 16 raw octets
  || conferenceEpoch           // u64 big-endian
  || mediaKeyEpoch             // u64 big-endian
  || membershipKeyContextDigest// 32 raw octets
)
```

It is a lookup discriminator, not a Conference identity, key, Fact digest,
freshness proof or collision-proof namespace. It MUST NOT be derived from an
IP address, SSRC, Shared Secret, RTP arrival order or sender-selected text.

### Pre-authentication lookup

The receiver processes the Hint as untrusted input:

```text
1. validate bounded RTP fixed-header and exact HE structure
2. read keyContextHint64 without creating state
3. look up only already installed local Conference key contexts
4. return zero, one or a bounded ambiguous candidate set
5. perform no Fact fetch, Conference creation, Source admission or Health change
```

```text
zero candidates
  -> UNKNOWN_KEY_CONTEXT_HINT
  -> drop and bounded/rate-limited diagnostic only

one candidate
  -> MAY enter SRTP authentication under that candidate

multiple candidates
  -> AMBIGUOUS_KEY_CONTEXT_HINT
  -> Q5 must freeze any bounded key-overlap attempt policy
  -> until then, no candidate may be selected by arrival, SSRC or level
```

The Hint lookup MUST NOT walk historical keys. An unauthenticated Hint cannot
request a Fact or allocate a pending Source entry.

### Authentication boundary

The complete extension remains plaintext so that the receiver can perform key
context lookup before decrypting the Opus payload. RFC 6904 header-extension
encryption is OFF for this V1 element.

AEAD-SRTP MUST authenticate the exact received RTP header and extension as its
associated authenticated data. Only after SRTP verification succeeds may the
receiver trust any extension value. It then recomputes `keyContextHint64` from
the selected installed context and requires an exact match before continuing
to Q3/Q4 admission.

```text
SRTP auth failure
  -> discard; no alternate Source binding and no state creation

authenticated but recomputed Hint mismatch
  -> KEY_CONTEXT_HINT_MISMATCH
  -> discard; MUST NOT try another Source or rewrite the installed context
```

The remaining Source and level bytes are likewise visible before authentication
and untrusted until this boundary completes.

### Strict parser and metadata exposure

Missing extension, wrong `0xBEDE`, word length other than 4, element ID other
than 1, element length other than 15, extra element, padding, truncation or
trailing extension data is `HE_FORMAT_INVALID`. The packet is dropped before
key lookup where possible and never enters decode/mix.

V1 accepts that an RF/IP observer can see the context pseudonym, short Source
discriminators and activity/level octet. SRTP prevents an outsider from
modifying them undetectably but does not hide traffic-analysis metadata. A
future requirement to conceal speaking activity requires a new privacy/wire
Profile; it cannot encrypt `keyContextHint64` in place because that would remove
the pre-authentication lookup seam.

Normative external references:

- RFC 8285 one-byte RTP header extension: https://www.rfc-editor.org/rfc/rfc8285.html
- RFC 7714 AEAD-AES-GCM SRTP: https://www.rfc-editor.org/rfc/rfc7714.html

## Q3 — Authenticated Source/Sender discriminator

**ACCEPTED — A**

Profile 01 is frozen and its `SOURCE_DECLARATION` / `SOURCE_RESOLUTION`
canonical authority does not contain packet discriminator fields. Profile 02
MUST NOT silently change that schema. V1 derives the wire discriminators
deterministically from already signed Source authority.

### Canonical derivation input

```text
SourceDiscriminatorInputV1 =
  conferenceId                    // 16 raw octets
  conferenceEpoch                 // u64 big-endian
  membershipVersion               // u64 big-endian
  mediaKeyEpoch                   // u64 big-endian
  moduleIdLength                  // u8, 1..32
  senderModuleId                  // exact canonical ASCII octets
  sourceInstanceId                // 16 raw octets
  SSRC                            // u32 big-endian
  mediaGroupDescriptorDigest      // 32 raw octets
```

The input has no delimiters, JSON, platform strings or native-endian values.
`moduleIdLength` makes the sole variable-length field unambiguous. Every value
comes from one accepted Source Declaration/Resolution plus its accepted
Conference context.

Exact domains and truncation:

```text
RTP_SENDER_DISCRIMINATOR_DOMAIN =
  ASCII "TALKBACK-RTP-SENDER-DISCRIMINATOR-V1" + NUL

RTP_SOURCE_DISCRIMINATOR_DOMAIN =
  ASCII "TALKBACK-RTP-SOURCE-DISCRIMINATOR-V1" + NUL

senderDiscriminator16 = first 2 octets of SHA-256(
  RTP_SENDER_DISCRIMINATOR_DOMAIN || SourceDiscriminatorInputV1)

sourceDiscriminator32 = first 4 octets of SHA-256(
  RTP_SOURCE_DISCRIMINATOR_DOMAIN || SourceDiscriminatorInputV1)

sourceAdmissionKey48 =
  senderDiscriminator16 || sourceDiscriminator32
```

“First” means the leading digest octets as transmitted in network byte order.
Every 16-bit/32-bit result, including all-zero, is a legal component. There is
no reserved “unknown” value; unknown means no installed mapping.

The pair is one 48-bit admission discriminator. Neither component, nor SSRC,
is a standalone Source identity or selection key.

### Installing the admission map

Only accepting a verified Source Declaration or Source Resolution may install:

```text
(sourceAdmissionKey48, SSRC)
  -> senderModuleId
  -> sourceInstanceId
  -> Source authority factDigest
  -> current membershipVersion / mediaKeyEpoch / Descriptor digest
```

The receiver MUST recompute the discriminator from accepted authority. It MUST
NOT accept a discriminator supplied by HELLO, RTP, UI, Owner metadata or an
unsigned cache as the map value.

Semantic duplicates recompute the same mapping idempotently. A Source Fact that
is pending, superseded, fenced, removed, terminated or quarantined MUST NOT
install an executable mapping.

### Authenticated packet admission

After Q2 key-context selection and successful SRTP authentication:

```text
1. read authenticated sourceDiscriminator32 and senderDiscriminator16
2. form sourceAdmissionKey48
3. look up the current key-epoch admission map
4. require tuple + RTP SSRC to select exactly one accepted Source
5. recompute both discriminators from that Source authority
6. require sender, incarnation, Membership, key epoch and Descriptor all current
7. only then continue to Q4 level semantics and Top-K
```

Any mismatch is `SOURCE_PACKET_IDENTITY_MISMATCH`. The receiver MUST NOT try a
second Source, bind by SSRC alone or reinterpret one component as another map.

An SRTP-authenticated packet with no installed mapping is dropped. It MAY cause
a bounded request for the exact `(sourceAdmissionKey48, SSRC)` binding after
group authentication, but the media packet is not cached and the requester
MUST NOT infer `senderModuleId` from the short values.

### Collision, retirement and convergence

The full 48-bit pair is the collision domain:

```text
same sourceAdmissionKey48
+ different Source authority/incarnation
  -> PACKET_DISCRIMINATOR_COLLISION
  -> quarantine every conflicting Source
  -> BURN that 48-bit value for this (conferenceId, mediaKeyEpoch)
```

The 16-bit or 32-bit component MAY coincide across different full pairs. That
alone is diagnostic, not ambiguity; implementations MUST index/compare the full
pair. They MUST NOT quarantine or pick a winner using only one component.

Every previously executed full pair is retired when its Source is fenced. A
retired or BURNED pair MUST NOT be reassigned in the same media key epoch, even
if the old SSRC appears idle. The Q8 persisted Source-discriminator evidence
must be sufficient to enforce this after restart.

Collision resolution remains ADR-0058 Q6:

```text
sender originates a legal new Source Resolution/Declaration
  -> new sourceInstanceId
  -> new SSRC
  -> new derived sourceAdmissionKey48
  -> pair must be unused and not BURNED
```

Owner does not select a winner. A sender that has not moved has no executable
Source. Late packets and timeout cannot unburn a value.

### Threat boundary

The derived pair detects accidental short-ID/SSRC misbinding and prevents stale
incarnation resurrection. It does not authenticate a physical Module against a
malicious current group member: any member holding the shared group key can
forge RTP header values, and Profile 01's accepted CM-AUTH-3 limitation remains.

## Q4 — voiceActive and audioLevel wire semantics

**ACCEPTED — A**

The final `TalkbackMediaContextV1` octet follows the RFC 6464 client-to-mixer
audio-level bit meaning, embedded inside the fixed Q2 element rather than
creating a second RTP extension element:

```text
bit 7      voiceActive (V)
bits 6..0  audioLevel, unsigned 0..127 in -dBov
```

```text
V = 1      sender VAD says this exact 20 ms frame contains voice
V = 0      non-voice, silence or no valid speech decision

level = 0       0 dBov (loudest representable RMS)
level = 1      -1 dBov
...
level = 127  -127 dBov / digital silence
```

Lower numerical level is louder. The V bit is always in use in Wire Profile 1;
there is no `vad=off` negotiation.

### Measurement frame and point

The reported level describes the same PCM samples encoded into that packet:

```text
frame samples          = 960
sample rate            = 48,000 Hz
channels               = mono
sample representation  = signed 16-bit linear PCM
measurement point      = after AEC / NS / AGC, immediately before Opus encode
```

It does not describe capture input before processing, a previous packet, Opus
FEC, PLC, padding, decoder output or a long-term speaker average.

For signed sample `s[i]`:

```text
x[i] = s[i] / 32768.0

rms = sqrt(sum(x[i]^2 for i = 0..959) / 960)

if rms == 0:
  audioLevel = 127
else:
  attenuation = -20 * log10(rms)
  audioLevel = clamp(0, 127, floor(attenuation + 0.5))
```

The rounding rule is part of V1 and MUST be covered by Q9 boundary vectors.
Sender implementations MUST NOT apply smoothing, hysteresis, noise-floor
normalization or device-specific offsets to the wire value. Profile 03 may use
history/hysteresis for Top-K selection without rewriting received packet data.

### VAD consistency

The VAD algorithm and hangover policy belong to Profile 03, but each emitted
bit refers to the same 20 ms frame and obeys:

```text
V=1, level=0..126   legal voice-active frame
V=0, level=0..127   legal non-voice frame; loud non-speech noise is possible
V=1, level=127      HE_SEMANTIC_INVALID
digital silence     MUST encode V=0, level=127
```

An authenticated `V=1, level=127` packet is dropped before Top-K, decode and
mix. A receiver MUST NOT repair it by clamping, clearing V or copying a previous
level.

### Receiver and Top-K meaning

Before SRTP verification, this octet is an untrusted visible hint and MUST NOT
allocate/warm a decoder or affect Top-K. After Q2/Q3 authentication and Source
admission:

```text
V=1  -> Source is eligible for Top-K; lower level ranks louder
V=0  -> Source is not an active-speaker candidate for this frame
```

Ties, minimum hold, attack/release smoothing, decoder warm-up and K are Profile
03 decisions. Absence of any legal V=1 Source is ordinary conference silence,
not transport failure.

### RTP marker relation

The sender sets Marker=1 only when its preceding emitted frame had V=0 and the
current frame has V=1. All other packets use Marker=0. Packet loss and reordering
can hide the transition from a receiver, so a receiver MUST NOT treat an
observed Marker/V history discrepancy as Source identity conflict or discard a
packet that otherwise authenticates. Marker remains talkspurt/jitter metadata,
not admission authority.

### Threat boundary

SRTP authenticates the octet against outsiders. It does not stop a malicious
current group member from claiming false VAD or exaggerated level values. Q4
therefore supplies an efficient local-mix selection signal, not physical Module
attestation or fairness enforcement.

Normative external reference:

- RFC 6464 audio level and V-bit semantics: https://www.rfc-editor.org/rfc/rfc6464.html

## Q5 — SRTP context selection and media-key epoch transition

**ACCEPTED — A**

Wire Profile 1 uses the Profile 01 master material with one fixed SRTP policy:

```text
SRTP protection profile  = AEAD_AES_128_GCM
authentication tag       = 16 octets
SRTP master key          = Profile 01 derived 16 octets
SRTP master salt         = Profile 01 derived 12 octets
key derivation rate      = 0
MKI                      = absent (zero octets)
EKT                      = OFF
RTP payload              = encrypted
RTP fixed header + Q2 HE = authenticated associated data
```

The Q2 Hint is not an MKI and never becomes key material. The complete protected
packet remains inside Q1's 120-octet ceiling.

Normative external reference:

- RFC 7714 AEAD-AES-GCM SRTP: https://www.rfc-editor.org/rfc/rfc7714.html

### Installed key-context model

Every locally known media key context binds:

```text
conferenceId, conferenceEpoch
membershipVersion, membershipFactDigest
mediaKeyEpoch, membershipKeyContextDigest
keyContextHint64
committed MediaGroupEndpoint
SRTP master key/salt (only while executable)
state
```

States are disjoint:

```text
ENCRYPTED_STAGED
  verified Package ciphertext waiting for its authoritative Fact
  not decrypted, not in Hint index, cannot authenticate media

CURRENT_ACTIVE
  Fact accepted and matching Package activated
  only state allowed in Hint index and SRTP execution

RETIRED_FENCE
  retained epoch/context/hint/replay evidence only
  no executable key and never an authentication candidate
```

`MEDIA_KEY_PENDING`, Local Leave/Remove, Termination, restoration failure and
collision quarantine install no `CURRENT_ACTIVE` media context.

### No same-Conference key overlap

One Conference has at most one locally executable `mediaKeyEpoch`. Accepting a
new Membership Fact atomically advances the control authority and fences media:

```text
accept new Membership Fact
  -> old mediaKeyEpoch becomes RETIRED_FENCE immediately
  -> remove old context from Hint index
  -> fence old Source/SSRC/discriminator execution
  -> zeroize old runtime SRTP key material

matching new Package already valid/staged
  -> activate one new CURRENT_ACTIVE context

matching new Package unavailable/unusable
  -> MEDIA_KEY_PENDING
  -> no CURRENT_ACTIVE context
```

The sender stops old-epoch RTP at local Fact acceptance. It may send under the
new epoch only after the new key is ACTIVE and a legal new Source incarnation,
SSRC and Declaration are accepted. Receivers drop old-key in-flight packets.

V1 forbids:

```text
old-key receive grace
new/old mediaKeyEpoch dual authentication for one Conference
fallback to retired key after new-key failure
RTP arrival order selecting an epoch
staged Package entering pre-auth Hint lookup
```

This deliberate fence may create a short media gap during key distribution; it
MUST NOT roll Membership back or label the route failed.

### Candidate lookup scope and bound

Pre-auth lookup is scoped by:

```text
(locally observed committed destination MediaGroupEndpoint, keyContextHint64)
```

The destination binding comes from the local multicast socket/pktinfo and the
committed Descriptor. Remote source IP/MAC and packet arrival order are not
selection inputs.

```text
zero CURRENT_ACTIVE candidates
  -> UNKNOWN_KEY_CONTEXT_HINT; no AEAD attempt

one candidate
  -> at most one AEAD verification

two candidates (truncated-Hint collision across current contexts)
  -> sort by unsigned lexicographic membershipKeyContextDigest
  -> at most one AEAD verification per candidate
  -> accept only if exactly one verifies

more than two candidates
  -> KEY_CONTEXT_HINT_FANOUT_EXCEEDED
  -> perform no AEAD verification
```

The two-candidate rule handles different current contexts that collide on the
64-bit lookup value. It is not same-Conference old/new epoch overlap.

```text
zero successful verification  -> SRTP_AUTH_FAILED
exactly one successful         -> continue Q2 recompute + Q3/Q4 admission
two successful verifications  -> KEY_CONTEXT_CRYPTO_COLLISION
                                  gate both contexts locally
```

No result originates a Membership/key Fact, chooses a collision winner or
rewrites a Hint. Even after a unique key verifies, the receiver MUST recompute
the Hint and pass Source, SSRC, Membership, Descriptor and HE semantic checks.

### Recovery and terminal fences

On restart, only Profile 01 `RECOVERY_UNSEAL` for the current accepted Package
may reconstruct `CURRENT_ACTIVE`. Retired recipient keys and retired media
epochs are not trial-unwrapped. A missing current key remains `KEY_PENDING`.

An accepted Conference Tombstone prevents installation of every context for
that Creation. Route/interface change may rebuild socket binding but MUST NOT
change or reactivate a key context.

Q5 does not freeze ROC/replay mechanics (Q6) or SRTCP/RTCP policy (Q7).

## Q6 — Sequence, timestamp, ROC and replay boundaries

**ACCEPTED — MODIFIED A**

**LIMITED AMEND — replayWindowPackets ownership + width**

Q1 defines sender sequence and timestamp production. Receiver replay protection
uses the SRTP packet index and rollover counter defined by RFC 3711; this Profile
does not introduce a second ROC estimation algorithm.

```text
replayWindowPackets = 64
```

```text
Ownership:
  replay-window width belongs to Profile 02 Q6
  because Q6 owns SRTP replay admission semantics.

Semantics:
  replayWindowPackets is a security / admission parameter only.

  It MUST NOT:
    extend MaxPlayoutDelayMs
    authorize late playout
    size Profile 03 jitter buffering
    alter sender cadence
    create recovery / Health authority

Profile 03:
  consumes only packets already admitted by Q6
  does not own or reinterpret replay-window width
```

At nominal 50 pps, 64 packets span about 1.28 s of sequence space. That span
MUST NOT be read as a 1.28 s media cache or late-playout allowance. Playout
usefulness remains Profile 03 `MaxPlayoutDelayMs = 120`.

### Sender continuity

For one Source incarnation:

```text
sequence number
  advances by exactly one for each packet actually emitted

RTP timestamp
  follows the Q1 20 ms media-time grid
  remains in the initial timestamp modulo-960 residue class
  advances by 960 * k between packets in sender packet-index order, k >= 1
```

The timestamp relation is evaluated in sender packet-index order, not receiver
arrival order. A reordered packet may arrive with an earlier timestamp and MUST
NOT be rejected merely for that arrival-order comparison. The receiver MUST NOT
classify an observed timestamp gap as local encode failure; loss and reorder are
receiver-observable alternatives.

DTX/VAD suppression, route reconstruction and socket/interface rebind MUST NOT
create intentional timestamp gaps. A Native sender-pipeline rebuild does not
continue the old grid: it follows Q1/Q5 and ADR-0058 Q5/Q11 by originating a new
Source incarnation with a new SSRC and new random RTP initial state.

### Candidate-scoped replay evaluation

Replay processing starts only after Q5 Hint lookup has returned one or two
bounded `CURRENT_ACTIVE` candidates. `ENCRYPTED_STAGED`, `RETIRED_FENCE` and an
unknown Hint are not equivalent contexts and MUST NOT enter ROC/replay
evaluation or modify replay state.

For each Q5 candidate, in Q5's frozen candidate order, the receiver performs:

```text
1. locate already installed replay state for that candidate and RTP SSRC;
   unauthenticated input MUST NOT create Source or replay state
2. estimate ROC and SRTP packet index normatively under RFC 3711
3. query the candidate replay window without mutating highest index or bitmap
4. if the packet is potentially admissible, perform AEAD verification under
   that candidate; otherwise record DUPLICATE or TOO_OLD for that candidate
5. record the candidate result only; do not commit replay state yet
```

An AEAD failure never changes ROC, highest received index, bitmap, Source state,
Conference Health or candidate order. The precheck is candidate-specific; its
result MUST NOT be copied to another candidate that happens to share the
truncated Hint or SSRC.

After every permitted Q5 candidate attempt has completed, selection and replay
commit are one atomic decision:

```text
exactly one CURRENT_ACTIVE candidate authenticates
and its packet is replay-admissible
  -> atomically commit only that candidate's ROC / highest index / bitmap
  -> then continue Q2 Hint recomputation and Q3/Q4 semantic admission

zero candidates authenticate with a replay-admissible packet
  -> do not modify any replay state

two candidates authenticate with replay-admissible packets
  -> KEY_CONTEXT_CRYPTO_COLLISION
  -> gate both contexts as required by Q5
  -> do not modify either replay state

the unique authenticated context classifies the packet as already seen
  -> SRTP_REPLAY_DUPLICATE
  -> do not modify replay state and do not decode/mix

the unique authenticated context classifies the packet as outside the old edge
of the replay window
  -> SRTP_REPLAY_TOO_OLD
  -> do not modify replay state and do not decode/mix
```

The implementation MUST NOT update the first candidate's bitmap immediately
after its AEAD succeeds: a second candidate may also verify, in which case Q5
requires `KEY_CONTEXT_CRYPTO_COLLISION` without a replay-state rollback. No
trial update followed by rollback is allowed.

### Duplicate, reorder and restart boundaries

A newly authenticated out-of-order packet inside the replay window may be
accepted once and sets only its corresponding bitmap position. Repeated Mesh
delivery of the same protected RTP packet is `SRTP_REPLAY_DUPLICATE`; it is a
normal media-plane discard and MUST NOT be promoted to Source conflict,
Conference conflict, route failure or Membership repair.

Sequence wrap and ROC continuity do not create a new Source incarnation. An
RTP sequence/packet-index reset under the same Source is forbidden; a legitimate
sender reset requires the new incarnation/SSRC boundary already frozen by Q1
and ADR-0058.

Restart obeys Profile 01 L5 replay persistence together with this Profile's
frozen `replayWindowPackets = 64`. A receiver MUST NOT resume an old media key
plus old SSRC with an empty replay window. If the required fence/checkpoint
cannot be restored, the affected Source/context remains quarantined or
non-executable; incoming RTP cannot reconstruct the missing fence.

Q6 does not freeze jitter-buffer delay, PLC, maximum reorder waiting,
checkpoint storage format/persistence cadence or SRTCP. Those remain outside
this width freeze as identified by Profile 01 L5 / Q7 / Profile 03.

Normative external reference:

- RFC 3711, SRTP packet index, ROC estimation and replay protection:
  https://www.rfc-editor.org/rfc/rfc3711.html

## Q7 — RTCP, feedback, retransmission and congestion policy

**ACCEPTED — MODIFIED A**

V1 Conference multicast media is a closed 8–10 Module audio-only profile. The
benefit of RTCP does not justify feedback storms, extra state machines or an
expanded interop surface. This Profile therefore freezes a non-generic RTP
media plane: RTP/SRTP only, with no RTCP feedback loop.

### Non-generic RTP interop declaration

```text
V1 does not claim to be a generic RTCP-capable RTP endpoint.
V1 does not support:
  cross-source wall-clock sync via RTCP
  A/V lip-sync
  RTT measurement
  CNAME / SDES interop

Local audio mix depends only on:
  each Source's RTP timestamp timeline
  + the local jitter buffer (Profile 03)
```

### Media-plane feedback prohibition

```text
V1 media plane:
  send only the SRTP RTP packets defined by Q1/Q2/Q5/Q6
  do not send RTCP or SRTCP
  do not create an SRTCP context
  do not parse RTCP semantics
  do not establish NACK / PLI / FIR / TMMBR / REMB / TWCC
  do not perform RTP retransmission

Control-plane Fact holder re-provide remains Profile 01 and is not Q7 media
feedback.
```

Sender cadence remains the Q1 fixed Traffic Profile (50 pps / 20 ms). Local
Leave, REMOVE, Termination, key fence, Source fence or an unsafe local
execution state MAY stop sending entirely. Stopping send for those reasons is
not congestion control and MUST NOT be implemented as loss/RTCP-driven
parameter adaptation.

### Inbound RTCP disposal

Q8 freezes port/demux identification. When that layer classifies a datagram as
RTCP:

```text
discard it before Q2 Hint lookup, Q5 AEAD and Q6 replay evaluation
allocate no Source, Conference or feedback state
classify as RTCP_PACKET_DISCARDED
```

`RTCP_PACKET_DISCARDED` is an explicit wire-ingress disposal result. It does
not imply that RTCP semantic handling occurred.

### Retransmission is a local sender invariant

```text
RTP_RETRANSMISSION_FORBIDDEN
  = local sender implementation / configuration invariant
  = MUST NOT emit a second protected RTP packet that reuses a prior
    packet index under the same Source / SRTP context
```

A receiver that sees an already-processed packet index classifies only under
Q6:

```text
SRTP_REPLAY_DUPLICATE
SRTP_REPLAY_TOO_OLD
```

The receiver MUST NOT infer whether a remote peer “intended retransmission”.
Mesh short-term duplicate delivery remains ordinary media-plane discard.

### Local post-admission statistics remain lawful

Prohibiting an RTCP closed feedback loop does not prohibit local observation:

```text
post-authentication / post-replay-admission RTP
  local loss / jitter / arrival statistics

MAY drive:
  Profile 03 jitter / PLC
  read-only Transport Health (ADR-0058 Q12)

MUST NOT:
  modify Membership, Key or Source authority
  change any sender Traffic Profile parameter
  (bitrate, ptime, DTX, cadence or packetization)
```

Packets that fail outer demux, Hint lookup, AEAD or replay admission MUST NOT
be counted into those Transport Health / playout statistics as authenticated
media.

### Congestion and underlay boundary

```text
forbidden:
  continuous sender adaptation driven by loss, RTCP or equivalent media-plane
  feedback

still allowed:
  complete stop of send under Leave / REMOVE / Termination
  key fence / Source fence
  local inability to execute safely

underlay link retransmission, QoS and routing control
  are outside the RTP feedback loop
  MUST NOT be reclassified as media-plane congestion control

Top-K
  controls only local decode / mix budget
  is not network congestion control
```

### Failure classifications introduced by Q7

```text
RTCP_PACKET_DISCARDED
  inbound RTCP identified by Q8 demux and discarded before media crypto/replay

RTP_RETRANSMISSION_FORBIDDEN
  local sender/config invariant against RTP retransmission

FEEDBACK_LOOP_FORBIDDEN
  any media-plane closed feedback path that would adapt Membership, Key,
  Source or sender Traffic Profile parameters
```

### Explicit Q7 exclusions

Q7 does not freeze:

```text
Q8 IP/UDP ports, SafeUdpPayloadBytes, pps/burst envelope or RTCP demux placement
Profile 03 jitter / PLC / replay-window width
Profile 01 control-plane Fact retransmission
DSCP / underlay priority mapping and Gate capacity evidence
```

## Q8 — Final IP/UDP/MTU/pps/burst wire envelope

**ACCEPTED — MODIFIED A′ (principles) + MODIFIED A (numerics / seam)**

Q8 freezes the application wire envelope for Conference media. It does not
control Mesh airtime, path convergence or underlay QoS. Protocol hard bounds,
the V1 fixed send budget and underlay dependencies remain three distinct layers.

### Transport and addressing

```text
transport                 = UDP only
destination               = Creation / Descriptor-bound MediaGroupEndpoint
                            .multicastAddress (+ media port from that Endpoint)
Profile 02 MUST NOT invent overlay routing
```

A Conference / `MediaGroupEndpoint` incarnation MUST bind exactly one IP family:

```text
one media-group incarnation = one IP family
IPv4 and IPv6 endpoints MUST NOT coexist in the same media-group incarnation
family is a Creation / Descriptor fact, not a sender-runtime choice
```

V1 MAY deploy IPv4 or IPv6 Conferences. Mixing families inside one incarnation
is forbidden.

### Bounded datagram (no intentional IP fragmentation)

Q1 already freezes the protected RTP/SRTP UDP body:

```text
ProtectedRtpUdpBodyBytes <= 120
```

Q8 binds the V1 Profile 02 safe envelope to that same whole-UDP-payload size:

```text
SafeUdpPayloadBytes = 120

EmittedProtectedRtpUdpBodyBytes
  = the complete protected RTP/SRTP datagram carried as the UDP payload
  MUST be <= SafeUdpPayloadBytes
```

`SafeUdpPayloadBytes` is the entire UDP payload on the wire, not the Opus media
payload alone. V1 Profile 02 fixes this value at 120. A larger deployment path
MTU or underlay encapsulation budget MUST NOT authorize a larger Conference
media datagram.

```text
sender MUST NOT intentionally rely on IP fragmentation
a packet that cannot be emitted inside the envelope
  -> local drop / WIRE_ENVELOPE_OVERSIZE
  -> MUST NOT fragment as recovery behavior
```

Profile 02 freezes `SafeUdpPayloadBytes`, not a claim about Ethernet/Wi-Fi L2
MTU, IP MTU or radio airtime.

### Cadence and WirePpsCeiling

```text
NominalPps
  = derived from Q1 packetization only
  = 1000 / ptime_ms
  = 50 RTP packets/s/Source for V1 (ptime = 20 ms)
  = not independently configurable

WirePpsCeiling
  = 60 packets/s/Source
  = implementation validation / safety anomaly threshold only
  ≠ Traffic Profile
  ≠ pacing target
  ≠ rate limiter
  ≠ congestion-control parameter
```

Q8 MUST NOT introduce an independent adaptive pps / ptime controller and MUST
NOT freeze a second business maxPps that can contradict Q1.

Crossing `WirePpsCeiling` proves only that a sender implementation may be
violating the Q1 cadence contract. It MUST NOT authorize packet suppression as
congestion control, ptime change, bitrate change, DTX change or cadence
adaptation. Detection-window semantics (one-second, rolling or counter) are
implementation-defined and are **not** frozen by Q8.

### Burst and current-slot send rule

```text
forbidden:
  RTP retransmission bursts
  RTCP / feedback-generated bursts
  historical-slot catch-up after scheduler delay

allowed:
  bounded scheduler / socket jitter on an otherwise Q1 cadence
```

Late media is expendable. No historical-slot catch-up is permitted:

```text
scheduler falls behind
  -> discard expired media slots
  -> emit at most the packet belonging to the current eligible send slot

old / expired slot  -> DROP
current eligible slot -> MAY SEND (at most that slot's packet)
```

A sender MUST NOT emit older slots merely to preserve packet count.

### DSCP / QoS

Profile 02 does not freeze a DSCP codepoint. DSCP / QoS marking is a
deployment / Gate policy and MUST NOT alter:

```text
RTP Traffic Profile
cadence
packet size
retransmission behavior
Membership / Key / Source semantics
```

### Ingress seam and RTCP classification

The Q8 transport seam is the bound UDP socket for the media-group Endpoint.
Profile 02 therefore does not freeze a `WIRE_NON_UDP` class.

If RTP and RTCP share that UDP ingress, an RTP/RTCP classification seam MUST
execute before Q2 / Q5 / Q6 processing:

```text
UDP datagram
  -> RTP/RTCP classification seam
       RTP candidate  -> continue Q2 / Q5 / Q6
       RTCP candidate -> RTCP_PACKET_DISCARDED
                         allocate no Source / Conference / feedback state
                         STOP
```

The exact classifier algorithm is implementation-defined and is **not** frozen
by Q8. Length heuristics, PT heuristics or a dedicated RTCP parser may all
satisfy the seam contract so long as classification precedes media crypto and
Source processing and RTCP never enters Q2/Q5/Q6.

### Failure classification ownership

Failure facts belong to the earliest authoritative rejecting seam. Q8 freezes
exactly one wire-envelope class:

```text
WIRE_ENVELOPE_OVERSIZE
  iff UDP payload length > SafeUdpPayloadBytes (120)
```

Q8 does **not** add umbrella classes such as `WIRE_NON_UDP` or
`WIRE_INGRESS_REJECTED`. Later rejects remain owned by their existing seams:

```text
RTCP              -> Q7 RTCP_PACKET_DISCARDED
malformed RTP/HE  -> Q2 classes
auth / key miss   -> Q5 classes
replay            -> Q6 classes
```

### Underlay boundary

```text
route repair, L2 retransmission, QoS and Mesh forwarding
  remain underlay behavior
  MUST NOT alter the RTP wire contract

Q8 defines the application wire envelope
  not physical-radio airtime guarantees
  not path-convergence guarantees
```

### Explicit Q8 exclusions

Q8 does not freeze:

```text
WirePpsCeiling detection-window algorithm
DSCP / underlay priority codepoints
exact RTP/RTCP classifier algorithm
Gate / airtime evidence methodology
Profile 03 jitter / PLC / replay-window width
Profile 01 control-plane Fact retransmission
```

## Q9 — Wire golden vectors and negative parser corpus

**ACCEPTED — MODIFIED A**

Q9 freezes protocol verifiability, not a parser implementation. Golden vectors
prove that legal wire images are stable. The negative corpus proves that
illegal or boundary inputs are rejected by the earliest authoritative seam.
Internal functions, exception types and call graphs are out of contract.

### Fixture schema

Every golden or negative fixture is:

```text
GoldenOrNegativeVector {
  context
  datagramBytes
  expectedOutcome
}
```

```text
context
  = external facts required to reproduce the frozen wire verdict
    (key material, SSRC/Source binding, ROC/replay state,
     MediaGroupEndpoint, Profile constants, …)

datagramBytes
  = byte-exact normative fixture bytes

expectedOutcome
  = ACCEPT
    | REJECT @ earliest-authoritative-seam
      [+ frozen failure class only if that class already exists]
```

A fixture that stores only opaque hex without `context` is non-conforming.

### Q9.1 — Fixture normative strength

**ACCEPTED — A**

V1 uses fully byte-exact normative fixtures:

```text
Fixture bytes are normative and byte-exact.

Given the same frozen context, an implementation MUST reproduce and/or
accept the exact fixture bytes with the exact frozen verdict.
```

Q9 does **not** introduce semantic-equivalent golden acceptance. Opus
constrained-VBR length variation is covered by multiple distinct legal golden
vectors, not by relaxing fixture equality. Runtime product traffic MAY emit
other legal VBR lengths; that does not weaken the byte-exact fixture rule.

### Ownership and determinism

```text
Q9 MUST NOT create a failure-class namespace.

If a negative vector lands on a seam without a frozen class name:
  expectedOutcome = REJECT @ Qx
  and nothing more
```

Boundary ownership remains with the earliest authoritative rejecting seam:

```text
Q8 oversize          -> WIRE_ENVELOPE_OVERSIZE
Q7 RTCP              -> RTCP_PACKET_DISCARDED
malformed RTP/HE     -> Q2
auth/tag/key failure -> Q5
replay duplicate /
  too-old            -> Q6
```

```text
same datagramBytes + same frozen context
  -> same ACCEPT / REJECT classification

wall clock, scheduler and network route
  MUST NOT affect the parser / seam verdict
```

### Mutation rule

Negative fixtures SHOULD preferably be produced by one controlled mutation of
a known-good golden vector. The mutation reason MUST be recorded so the corpus
does not become a set of mystery hex blobs.

### Scope of this Q9 contract pass

Q9 currently freezes:

```text
fixture schema
normative strength (Q9.1)
ownership rule
determinism rule
mutation rule
minimum corpus categories (Q9.2)
```

Q9 does **not** generate hex fixture files in this contract pass. Concrete
`GoldenOrNegativeVector` attachments are produced after Q9.2 under the frozen
schema. Q9 does not freeze fuzz engines, parser libraries, exception types,
call graphs, performance benchmarks or packet-capture formats.

### Q9.2 — Minimum corpus coverage

**ACCEPTED — MODIFIED A**

Every listed fixture, including positives G1/G2/G3+, MUST carry a complete
`context`. Positive status does not authorize omitting key material, ROC/replay
state, Source/SSRC binding, MediaGroupEndpoint or Profile constants.

Minimum coverage:

```text
G1   valid normal
       ACCEPT

G2   valid exact 120-byte boundary
       ACCEPT

G3+  >= 2 valid distinct Opus/VBR lengths
       ACCEPT

N1   121-byte oversize
       REJECT @ Q8 / WIRE_ENVELOPE_OVERSIZE

N2   truncated RTP header
       REJECT @ Q2

N3   invalid RTP version
       REJECT @ Q2

N4   malformed CSRC/extension structure
       REJECT @ Q2

N5   structural length inconsistency
       REJECT @ earliest authoritative seam capable of proving it
       (do not pre-assign Q2 unless that seam alone proves the fault)

N6   RTCP-shaped shared-ingress datagram
       REJECT @ Q7 / RTCP_PACKET_DISCARDED

N7   SRTP auth/tag corruption
       REJECT @ Q5

N8a  wrong SRTP key
       REJECT @ Q5

N8b  wrong Source/SSRC binding
       REJECT @ owning frozen seam
       (V1 post-auth binding mismatch is owned by Q3;
        do not collapse N8b into N8a)

N9   replay duplicate
       REJECT @ Q6 / SRTP_REPLAY_DUPLICATE

N10  replay too-old
       REJECT @ Q6 / SRTP_REPLAY_TOO_OLD

N11  finite fuzz-derived regression cases
       each sample MUST carry its individually frozen expectedOutcome
       malformed samples -> REJECT @ earliest seam
       accidentally-valid samples MAY be retained as ACCEPT regression fixtures
```

N11 is a regression set, not a rule that every fuzz mutation must reject.

Q9.2 freezes only this coverage contract. It does not invent failure-class names
and does not ship hex corpora.

## Q10 — Sufficiency and implementation-authorization boundary

**ACCEPTED — A**

Q10 freezes only what Profile 02 has proven and what it has not authorized. It
introduces no new wire semantics.

### Four-layer verdict

```text
1. Contract Sufficiency
   Q1–Q9 are sufficient to freeze the V1 RTP/SRTP wire contract
   Profile 02 = FROZEN — CONTRACT COMPLETE / CLOSED

2. Implementation Sufficiency
   NOT ESTABLISHED
   Profile 02 PASS ≠ media implementation ready
   ≠ Android / SRTP runtime / Top-K / jitter proven

3. Authorization
   NOT AUTHORIZED
   Profile 02 PASS alone MUST NOT start or modify product multicast media code

4. Remaining prerequisites before implementation authorization
   1. Profile 03 FROZEN
   2. Cross-Profile consistency review PASS
   3. Q9 hex corpus generated from the frozen schema and verification PASS
   4. Explicit implementation authorization
```

Anti-misread invariants:

```text
CONTRACT PASS ≠ IMPLEMENTATION AUTHORIZED
Fixture sufficiency ≠ runtime / media-path sufficiency
```

### Hex corpus positioning

The Q9 hex corpus is conforming evidence / deliverable of the already frozen
contract. It is **not** a Profile 02 closure condition.

```text
corpus generation / verification FAIL
  -> implementation authorization remains CLOSED
  -> determine:
       fixture defect
       OR
       frozen-contract contradiction
  -> if contract contradiction
       -> explicit Profile 02 REOPEN / AMEND
       -> MUST NOT silently retune tests around a broken contract
```

### CLOSED and reopen rule

Profile 02 itself is CLOSED. If Profile 03 or a Cross-Profile consistency review
discovers a conflict with Q1–Q9, the response is an explicit reopen / amend of
Profile 02. Silent rewrite of frozen Profile 02 text is forbidden.

### What Profile 02 PASS MUST NOT authorize

```text
product multicast media implementation or modification
ADR-0056 removal
Anchor path bypass
GROUP PTT modification
ADR-0057 modification
```

Profile 02 proves that the RTP/SRTP wire contract is sufficiently definite. It
does not prove that the whole Conference media architecture is ready to
implement.

This verdict changes only Profile 02:

```text
Profile 01:       FROZEN — CONTRACT COMPLETE
Profile 02:       FROZEN — CONTRACT COMPLETE / CLOSED
Profile 03:       FROZEN — CONTRACT COMPLETE / CLOSED
Implementation:  AUTHORIZED (post-readiness; E2b PENDING)
ADR-0056:         RETAINED
```

Authorization recorded in `0058-implementation-authorization.md`.
The next formal gate is E2b Implementation Conformance, not Profile reopen.

## Decisions to freeze

```text
Q1  Fixed Opus/RTP baseline and protected packet budget
Q2  RTP header-extension container and Packet Context Hint
Q3  Authenticated Source discriminator and collision encoding
Q4  voiceActive / audioLevel wire semantics
Q5  SRTP packet/key-context selection and epoch overlap
Q6  Sequence, timestamp, ROC and duplicate/reorder boundaries
Q7  RTCP, feedback, retransmission and congestion policy
Q8  Final IP/UDP/MTU/pps/burst wire envelope
Q9  Wire golden vectors and negative parser corpus
Q10 Sufficiency and implementation-authorization boundary
```

Only one question is decided at a time. This Profile cannot use media packets
to originate or repair control-plane Facts.
