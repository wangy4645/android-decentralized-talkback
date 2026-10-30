# ADR-0058 Implementation Profile 03 — Media Runtime

## Status

```text
Profile:                FROZEN — CONTRACT COMPLETE / CLOSED
Grill:                  Q1–Q10 PASS
Parent:                 ADR-0058 ACCEPTED — CONTRACT ONLY
Profile 01:             FROZEN — CONTRACT COMPLETE
Profile 02:             FROZEN — CONTRACT COMPLETE / CLOSED
Underlay:               ADR-0059 ACCEPTED — PRODUCT RISK ASSUMPTION
Implementation:         AUTHORIZED (Profiles 01/02/03; E2b PENDING)
Conference multicast:   NOT IMPLEMENTED
ADR-0056:               RETAINED
```

This Profile maps admitted Conference media into local receive execution:
jitter, decode, PLC, Top-K, mix and playout. It MUST NOT modify Membership,
Owner, Source, key, collision, wire format or Health authority semantics.

## Boundary

```text
INPUT:
  Profile 01 authorized control / Source / key facts
  Profile 02 wire/auth/replay-accepted media packets

OWNS:
  receive runtime
  jitter / reorder / late-media policy
  decode
  PLC
  per-Source ephemeral execution state
  Top-K decode/mix budget
  playout / local output
  bounded runtime failure / degradation

DOES NOT OWN:
  Membership
  generation
  Anchor election / topology
  Source authority
  key authority
  RTP / SRTP wire format
  sender cadence
  routing / QoS
  SRTP replay-window semantics (Profile 02 Q6)
  GROUP PTT / ADR-0057
```

Anti-misread invariants:

```text
CONTRACT PASS ≠ IMPLEMENTATION AUTHORIZED
Runtime execution ≠ control-plane authority
Packet arrival ≠ Source admission
```

## Grill order

```text
Q1  AdmittedMediaSource as the sole authority-bearing runtime input
Q2  per-Source runtime lifecycle (create / retain / destroy)
Q3  jitter / reorder / late-media window
Q4  decode / PLC / warm-up
Q5  Top-K (K, hysteresis, hold)
Q6  mix / playout / limiter
Q7  Android resources (MulticastLock, rebind, caps)
Q8  runtime failure → Health projection boundary
Q9  acceptance matrix / invariants
Q10 sufficiency and implementation-authorization boundary
```

Profile 03 MAY cite Profile 02 `replayWindowPackets = 64` only as a frozen
upstream constraint. It MUST NOT re-own or re-cut SRTP replay admission.

## Q1 — Authority-bearing input to Media Runtime

**ACCEPTED — MODIFIED A**

```text
Packet arrival ≠ Source admission.

Profile 03 MUST NOT originate, infer or repair
Source authority from RTP/SRTP traffic.
```

### AdmittedMediaSource

```text
AdmittedMediaSource
  = an already-authorized binding derived from
    Profile 01 control / Source / key facts
    and usable under Profile 02 gates
```

`AdmittedMediaSource` is the sole authority-bearing input to Media Runtime.

### Packet path

```text
UDP/SRTP packet
  -> Profile 02 wire / auth / replay processing
  -> resolve against authorized Source binding
  -> AdmittedMediaSource + accepted media packet
  -> Profile 03 Media Runtime
```

Profile 02 remains the owning seam for classification, authentication and
replay. Profile 03 consumes only packets that have already resolved to an
`AdmittedMediaSource`.

### Unknown / unbound SSRC

An unknown or unbound SSRC:

```text
MAY be inspected only as required to classify / reject
the packet at the owning Profile 02 seam.

MUST NOT:
  create jitter state
  create decoder state
  create PLC state
  create playout state
  enter Top-K
  enter mixer
  install / update Source binding
  create control-plane authority
```

```text
No admitted binding → no media execution.
```

Provisional decode, Top-K entry or audible output for unbound traffic is
forbidden even if Membership is never written. That path crosses the security
boundary without creating control-plane authority and is still a Profile 03
violation.

### Ephemeral execution state

```text
Only after successful resolution to an AdmittedMediaSource
MAY Profile 03 create ephemeral execution state for that binding
(jitter / decode / PLC / playout buffers).
```

Unknown SSRC MUST NOT receive a provisional jitter buffer or any other
execution state “in hope of later admission”.

## Q2 — Per-Source runtime lifecycle

**ACCEPTED — MODIFIED A**

Q2 freezes only runtime-state lifecycle. It does not freeze jitter depth,
Top-K policy, Health projection or reclaim timeouts.

### CREATE

```text
Requires a usable AdmittedMediaSource:
  Profile 01 Source binding is authorized
  + matching key/context is executable under Profile 02
  + not superseded / fenced / quarantined / TERMINATED

First packet MAY trigger resource allocation.
First packet MUST NOT establish authority.
Packet arrival alone is insufficient to CREATE.
```

### RETAIN

```text
Runtime MAY remain while its admitted binding remains valid.

The following MUST NOT by themselves revoke Source authority
or force HARD FENCE:
  packet silence / loss
  temporary jitter starvation
  decoder starvation / sustained PLC
  Top-K exclusion
  transient Mesh / underlay disruption
```

```text
silence ≠ destroy authority
temporary media starvation ≠ control-plane invalidation
```

### HARD FENCE vs DESTROY

These are not equivalent.

```text
HARD FENCE
  = execution eligibility terminates immediately

DESTROY
  = ephemeral runtime resources are released
    after execution has already been fenced
```

```text
authoritative invalidation
  -> HARD FENCE
  -> old runtime MUST NOT execute media
  -> physical / resource DESTROY MAY follow asynchronously
```

```text
Fence completion MUST NOT depend on decoder / buffer / resource teardown.
```

HARD FENCE applies to the affected **execution incarnation**, not only to a
Module-level “Source removed” event. An execution incarnation is identified by
at least:

```text
(Source binding, mediaKeyEpoch, execution context)
```

Therefore `mediaKeyEpoch` retirement hard-fences the old epoch's execution
incarnation even when the same authoritative Source continues under a new
epoch:

```text
Source S / KeyEpoch 7
  -> key rotation
Source S / KeyEpoch 8

Epoch-7 execution incarnation:
  MUST NOT accept late Epoch-7 media
  MUST NOT transfer replay / jitter / decode state into Epoch-8
    unless a later explicit contract authorizes inheritance

V1 default: no cross-epoch execution-state inheritance.
```

HARD FENCE triggers include:

```text
Source supersession
REMOVE / binding revocation
key / mediaKeyEpoch retirement
quarantine / collision fence
Conference TERMINATED / Tombstone
loss of AdmittedMediaSource eligibility
```

```text
Late packets MUST NOT resurrect a fenced execution incarnation.

A fenced execution incarnation MUST NOT become executable again
merely because packets resume.

It may become executable only through a new or re-legalized
authoritative binding / execution incarnation.
```

```text
Source authority lost
  -> runtime MUST NOT survive indefinitely as an executable media path
```

### Soft reclaim

While authority remains valid, an implementation MAY reclaim idle ephemeral
resources (jitter / decode / PLC / playout buffers). A later valid packet for
the same admitted binding MAY recreate those resources.

```text
resource re-creation ≠ Source resurrection
```

Soft reclaim is not HARD FENCE and is not DESTROY of Source authority. Exact
idle timeouts remain unfrozen.

### Explicit Q2 exclusions

Q2 does not freeze:

```text
jitter / reorder / late-media numeric windows (Q3)
decode / PLC / warm-up parameters (Q4)
Top-K policy (Q5)
mix / playout / limiter (Q6)
Health projection mapping (Q8)
cross-epoch state-inheritance optimizations beyond the V1 default of none
```

## Q3 — Jitter / reorder / late-media window

**ACCEPTED — MODIFIED A**

Q3 freezes the receive-side real-time tolerance window and late-packet
disposition. It does not own SRTP replay admission, PLC policy, Top-K or Health.

### Two seams remain distinct

```text
too-old-for-replay
  = Profile 02 Q6
  = SRTP_REPLAY_TOO_OLD
  = security / admission seam

valid-for-SRTP-but-too-late-for-playout
  = Profile 03 Q3
  = LATE_FOR_PLAYOUT
  = real-time usefulness seam
```

```text
Q6: is this packet safe?
Q3: is this packet still in time?
```

Authenticated and replay-valid does **not** imply the packet is still useful for
playout. Q3 consumes only packets already accepted by Profile 02 gates and
resolved to an `AdmittedMediaSource` execution incarnation.

### Fixed bounded per-Source window

Jitter / reorder state is per execution incarnation. It MUST NOT create
cross-Source ordering or authority.

```text
MaxPlayoutDelayMs   = 120
  = authoritative real-time deadline

MaxReorderPackets   = 4
  = bounded reorder capacity / safety cap
  ≠ independent permission to extend the deadline

AdaptiveJitterWindow = OFF
```

`MaxPlayoutDelayMs` is the final time verdict. `MaxReorderPackets` bounds how
many out-of-order packets may be retained. If either bound shows a packet can
no longer meet the current playout deadline, the packet is late-dropped
immediately. The runtime MUST NOT keep waiting merely to fill the four-packet
reorder capacity.

```text
silence / loss / burst / route flap
  MUST NOT dynamically enlarge the window
  MUST NOT rewrite sender Traffic Profile or RTP profile
```

### Disposition rules

```text
within MaxPlayoutDelayMs and within MaxReorderPackets capacity
  -> MAY reorder by RTP media-time
  -> then eligible for decode

a media slot reaches the playout deadline without its packet
  -> slot lost
  -> Q4 owns any PLC decision

packet arrives after its useful playout deadline
  -> LATE_FOR_PLAYOUT
  -> discard
  -> MUST NOT decode
  -> MUST NOT enter Top-K / mixer / playout
```

### LATE_FOR_PLAYOUT class

```text
LATE_FOR_PLAYOUT
  = Profile 03 runtime disposition class
  = accepted upstream, discarded by real-time playout policy

  ≠ SRTP / Q6 replay rejection
  ≠ Source failure or authority loss
  ≠ Health transition by itself
```

A single late packet MUST NOT, by itself, be escalated into a Health failure.
Q8 may later define aggregate degradation signals; Q3 does not.

### Explicit Q3 exclusions

Q3 does not freeze:

```text
SRTP replayWindowPackets semantics
  (owned by Profile 02 Q6; width frozen at 64; not re-cut here)
PLC / warm-up (Q4)
Top-K (Q5)
mix / playout / limiter (Q6)
Health projection (Q8)
```

## Q4 — Decode / PLC / warm-up

**ACCEPTED — MODIFIED A**

Q4 freezes when Opus decode and bounded PLC may run. It does not freeze mix
gain, limiter, Top-K ranking or Health.

### Decode eligibility

```text
ORDER = Top-K selection BEFORE Opus decode
```

Decode is allowed only for:

```text
AdmittedMediaSource
  ∩ (current Top-K ∪ bounded warm-up set)
  ∩ not HARD-FENCED
```

```text
LATE_FOR_PLAYOUT / unbound / non-admitted
  -> MUST NOT decode
```

Non-Top-K Sources MAY retain lightweight Q2/Q3 state. They MUST NOT run a live
Opus decoder except while inside the bounded warm-up set defined with Q5 entry.

### PLC

PLC may run only for a Source that is already decode-eligible.

```text
MaxConsecutivePlcFrames = 5   // 100 ms at 20 ms/frame
```

A media slot lost at the Q3 deadline MAY be concealed by PLC while the
consecutive PLC count remains within that bound. On exhaustion:

```text
stop synthesized audio for that Source (silence / gap)
= runtime degradation only
≠ HARD FENCE
≠ Source authority loss
```

```text
PLC MUST NOT:
  forge Source authority
  create Membership / binding
  run unbounded synthesized speech
```

### Warm-up

```text
DecoderWarmupMs = 40
  = maximum bounded warm-up allowance (2 frames)
  ≠ mandatory playout delay
```

When a Source newly enters Top-K, the runtime MAY use up to `DecoderWarmupMs`
to produce usable PCM. If the first decoded frame is already usable, it MUST
NOT insert an artificial 40 ms wait before mixer eligibility.

### Explicit Q4 exclusions

Q4 does not freeze mix/limiter (Q6), Top-K ranking numbers (Q5), AEC/NS/AGC
internals or Health aggregation (Q8).

## Q5 — Top-K decode/mix budget

**ACCEPTED — MODIFIED A**

Q5 freezes who consumes the decode/mix budget. Top-K is a local execution
policy, not Floor and not Source authority.

### Order and budget

```text
Top-K selection BEFORE Opus decode

TopK                = 4
TopKHoldMs          = 200
TopKHysteresisLevel = 3

Dynamic K           = OFF
```

Receivers MUST NOT infer Top-K solely by decoding all N−1 streams. Selection
uses authenticated Profile 02 `voiceActive` / `audioLevel` after admission.

### Eligibility and ranking

Per update point, a Source is an active Top-K candidate only if:

```text
AdmittedMediaSource is executable
+ latest authenticated V = 1
+ not HARD-FENCED
```

```text
V = 1  -> eligible active-speaker candidate; lower audioLevel ranks louder
V = 0  -> active-candidate eligibility ends for that Source
```

```text
HARD FENCE
  -> immediate eligibility removal
```

### Hold and hysteresis

`TopKHoldMs` and `TopKHysteresisLevel` protect against rank churn among
**still-eligible V=1** Sources:

```text
among V=1 candidates:
  Hold + hysteresis apply
  a challenger MUST be at least TopKHysteresisLevel louder
  (numerically lower by >= 3) to displace a held incumbent after Hold rules
```

```text
TopKHoldMs MUST NOT force a V=0 Source to occupy a Top-K slot
for the remainder of the hold period.

V=0:
  active-candidate eligibility ends
  a bounded existing playout / PLC tail MAY drain
  MUST NOT block a V=1 challenger merely because Hold remains
```

### Overload and exclusion

If more than `TopK` Sources are eligible, keep only the best-ranked `TopK`.
Others are Top-K excluded:

```text
Top-K exclusion
  -> MUST NOT decode (except bounded exit/warm-down already in progress)
  -> ≠ Source revoke
  -> ≠ HARD FENCE
  -> ≠ Source / runtime destruction
```

Soft reclaim of an excluded decoder remains allowed under Q2 while the binding
is still admitted.

### Tie-break

When rank is otherwise equal:

```text
1. lower audioLevel (louder)
2. already-in-Top-K (stability)
3. deterministic lexical ordering of the frozen Source identity
```

Item 3 is deterministic execution selection only. It carries **no** authority,
priority or fairness semantics and MUST NOT be read as incarnation-age
preference.

### Explicit Q5 exclusions

Q5 does not freeze mix summing, limiter, Android resource caps or Health
projection.

## Q6 — Mix / playout

**ACCEPTED — MODIFIED A**

Q6 freezes how Top-K PCM enters one local mix and the minimum peak-protection
invariant. It does not freeze AEC/NS/AGC algorithms, cosmetic gain tuning or
Health aggregation.

### Output domain

```text
MixMaxSources    = 4          // absolute; equals TopK; MUST NOT exceed TopK
OutputSampleRate = 48_000     // cite Profile 02
OutputChannels   = 1
OutputSampleFmt  = s16
```

Exactly one local mixer instance serves one Conference media execution path.

### Mixing

```text
equal-weight linear sum of eligible PCM frames
PeakProtection = REQUIRED
OutputPeakAbs  <= 0.89 FS     // ≈ -1 dBFS ceiling as output invariant
```

The peak-protection algorithm is implementation-defined. Hard clipping above
the frozen ceiling MUST NOT be the normal mixing strategy.

```text
MixMaxSources = 4 is absolute.

A draining V=0 / warm-down tail MAY consume one of the 4 mix slots temporarily.
Concurrent mixed Sources MUST NOT exceed 4.

A V=0 Source MUST NOT retain Top-K eligibility merely to preserve its drain
tail.
```

Decoder/warm-up resource #5 (Q7) MUST NOT create mix source #5.

### Playout failure

AudioTrack failure, underrun or device-busy conditions are runtime degradation
only:

```text
≠ HARD FENCE
≠ Source / Conference authority change
≠ expand TopK / MixMaxSources
≠ bypass AdmittedMediaSource or Profile 02 gates
```

### Explicit Q6 exclusions

AEC/NS/AGC internals, subjective EQ and Health aggregation remain unfrozen.

## Q7 — Android resources

**ACCEPTED — MODIFIED A**

Q7 freezes resource ownership, hard caps and rebind boundaries. It does not
freeze AudioTrack buffer sizes or vendor QoS.

### Hard caps

```text
MaxLiveDecoders  = 5
MaxJitterSources = 10
```

```text
mixed Sources     <= 4
live Opus decoders <= 5
```

Decoder #5 exists only as a bounded transition / warm-up / warm-down execution
resource. It MUST NOT authorize a fifth mixed Source.

Exceeding a cap rejects new allocation. It MUST NOT expand TopK, MixMaxSources
or bypass admission.

### MulticastLock

```text
MulticastLock ownership
  = local Conference media transport execution scope
```

Acquire before this Module maintains executable multicast receive/send for the
Conference MediaGroupEndpoint. Release only when that transport scope ends:

```text
Local Leave
REMOVE self
Conference TERMINATED / Tombstone
or another authoritative transition proving this Module no longer owns an
executable Conference media transport path
```

```text
V=0
empty Top-K
temporary packet silence
decoder soft reclaim
  MUST NOT alone release MulticastLock
```

```text
MulticastLock state ≠ Membership / Source authority
```

Lock acquire/release failure is a runtime resource result, not a Fact.

### Rebind

Socket / interface rebind MAY rebuild the multicast socket and re-join the
bound MediaGroupEndpoint.

```text
rebind MUST NOT:
  create Source admission
  resurrect a fenced Source / execution incarnation
  change Conference generation / epoch
  clear HARD FENCE
  invent Membership events
```

### HARD FENCE and teardown

```text
HARD FENCE terminates execution eligibility immediately (Q2).
Native decoder / AudioTrack / buffer DESTROY MAY be asynchronous.
Fence completion MUST NOT wait for native teardown.
```

### Resource exhaustion

```text
bounded degradation / reject new execution allocation
MUST NOT expand K or caps
MUST NOT bypass AdmittedMediaSource or Profile 02 gates
```

### Explicit Q7 exclusions

Exact AudioTrack buffer milliseconds, OEM power policies and DSCP marking
remain outside Q7.

## Q8 — Runtime evidence → Health projection boundary

**ACCEPTED — MODIFIED A**

Q8 freezes Profile 03 as a **runtime evidence producer**. ADR-0058 Q12 remains
the **Health semantic owner**. Profile 03 MUST NOT redefine a Q12 axis.

### Ownership

```text
Profile 03
  -> produces bounded runtime evidence

ADR Q12
  -> owns Health semantics and axis mapping
```

```text
runtime observations MAY feed read-only Health / degradation projection
MUST NOT mutate Membership
MUST NOT mutate Source / key authority
MUST NOT alone trigger generation / epoch change
MUST NOT bypass Profile 01 / 02
```

### What Profile 03 may and must not assert

Profile 03 MAY project runtime evidence onto ADR Q12 axes **only where Q12
already defines the mapping**. It MUST NOT invent or redefine that mapping.

```text
V=1 / V=0 / silence
  = runtime activity observation only

MUST NOT by itself establish:
  Source ACTIVE
  NO_REMOTE_SOURCE
  Source availability / unavailability
```

```text
LATE_FOR_PLAYOUT
PLC frames within or beyond MaxConsecutivePlcFrames
single underrun
Top-K empty / exclusion
  MUST NOT independently establish Source authority / readiness
  MUST NOT alone flip Transport or Source Health
```

HARD FENCE / quarantine consequences on Source Readiness remain ADR Q12's
existing mapping. Profile 03 consumes that mapping; it does not create it.

Bounded runtime evidence Profile 03 MAY produce includes:

```text
playout / AudioTrack failure
MulticastLock unavailable for transport execution
rebind disruption
resource exhaustion / allocation rejection
```

### Aggregation window

```text
HealthAggregationWindowMs = 1000

= bounded window for aggregating runtime evidence
≠ authority timeout
≠ repair timer
≠ by itself a threshold that creates IMPAIRED / UNAVAILABLE semantics
```

Q12 decides how aggregated evidence becomes a Health state. Profile 03 MUST NOT
treat “condition lasted 1000 ms” as an independent license to write
`UNAVAILABLE`.

### Clearing evidence

```text
runtime condition clears
  -> runtime evidence MAY clear automatically

ADR Q12 Health projection MAY consequently recover
  according to its frozen semantics

automatic evidence clear ≠ authority repair
Profile 03 MUST NOT run a private Health → HEALTHY state machine
that overrides Q12
```

### Explicit Q8 exclusions

Q8 does not freeze new Health enums, Gate airtime evidence or UI copy.

## Q9 — Acceptance matrix

**ACCEPTED — A**

Q9 freezes a minimum contract-proving matrix. It does not invent Health classes,
failure classes or new parameters.

```text
Acceptance proves the frozen contract.
Failing tests MUST NOT silently dilute the frozen contract.
```

```text
acceptance failure
  -> fix implementation / fixture
  OR explicit REOPEN / AMEND
```

Profile 03 unit/acceptance tests MUST NOT require proving a specific Q12 Health
enum unless that enum mapping is already explicitly frozen by ADR Q12.

### Minimum matrix R01–R16

```text
R01  normal Top-K / decode / mix
     selected Sources decode; mixed <= 4; OutputPeakAbs <= 0.89 FS

R02  >4 simultaneous V=1
     decode/mix only Top 4; exclusion ≠ revoke

R03  V=0 replacement
     V=0 yields seat; V=1 challenger may enter; Hold does not block

R04  reorder within MaxReorderPackets and MaxPlayoutDelayMs
     reorder then decode

R05  late > MaxPlayoutDelayMs
     LATE_FOR_PLAYOUT; no decode; no independent Health flip

R06  PLC 1..5
     bounded conceal

R07  PLC >5
     stop synthesis; ≠ HARD FENCE; no independent Health flip

R08  warm-up
     <= DecoderWarmupMs allowance; usable first frame needs no forced wait

R09  Source HARD FENCE + late packets
     immediately non-executable; late packets do not resurrect

R10  key epoch fence
     old-epoch runtime fenced; no default state inheritance

R11  decoder #5
     transition resource only; mixed Sources still <= 4

R12  jitter sources at MaxJitterSources
     11th rejected / reclaimed; no cap expansion

R13  MulticastLock lifecycle
     silence / V=0 / empty Top-K alone MUST NOT release
     Leave / Term / transport-scope end MAY release

R14  interface rebind
     socket MAY rebuild; ≠ admission / resurrection / epoch change

R15  AudioTrack / playout failure
     bounded runtime degradation evidence
     no authority mutation

R16  resource exhaustion
     bounded degradation / rejection evidence
     no K expansion
     no admission bypass
```

Q9 does not enlarge this matrix in the Profile freeze. Additional fixtures may
be added later only as conforming evidence under the same frozen rules.

## Q10 — Sufficiency and implementation-authorization boundary

**ACCEPTED — A**

Q10 freezes only what Profile 03 has proven and what it has not authorized. It
introduces no new runtime, Android, Health or wire parameters.

### Four-layer verdict

```text
1. Contract Sufficiency
   Q1–Q9 are sufficient to freeze the V1 Media Runtime contract
   Profile 03 = FROZEN — CONTRACT COMPLETE / CLOSED

2. Implementation Sufficiency
   NOT ESTABLISHED
   Profile 03 PASS ≠ Android / media-path ready
   ≠ R01–R16 executed or field-proven

3. Authorization
   NOT AUTHORIZED
   Profile 03 PASS alone MUST NOT start or modify product multicast media code

4. Remaining prerequisites before implementation authorization
   1. Cross-Profile consistency review PASS (01 ↔ 02 ↔ 03)
   2. Profile 02 Q9 hex corpus generated and verification PASS
   3. Profile 03 R01–R16 acceptance fixtures/tests prepared and executable
      against the frozen matrix
   4. Explicit Implementation Readiness Review PASS
   5. Explicit implementation authorization
```

Anti-misread invariants:

```text
CONTRACT PASS ≠ IMPLEMENTATION AUTHORIZED
Fixture sufficiency ≠ runtime / media-path sufficiency
```

### Evidence positioning

R01–R16 fixtures/tests and the Profile 02 Q9 hex corpus are conforming evidence
/ deliverables of already frozen contracts. They are **not** Profile 03 closure
conditions.

```text
#2 / #3 FAIL
  ≠ Profile automatically becomes OPEN
  -> implementation authorization remains CLOSED
  -> diagnose fixture / implementation defect
  -> only a proven contract contradiction may trigger
     explicit REOPEN / AMEND
  -> MUST NOT silently dilute frozen Q1–Q9
```

### CLOSED and reopen rule

Profile 03 itself is CLOSED. Later conflicts require explicit reopen / amend.
Silent rewrite of frozen Profile 03 text is forbidden. No Profile 04 and no
further design Grill are authorized by this closure.

### What Profile 03 PASS MUST NOT authorize

```text
product multicast media implementation or modification
ADR-0056 removal
Anchor path bypass
GROUP PTT modification
ADR-0057 modification
```

This verdict changes only Profile 03:

```text
Profile 01:       FROZEN — CONTRACT COMPLETE
Profile 02:       FROZEN — CONTRACT COMPLETE / CLOSED
Profile 03:       FROZEN — CONTRACT COMPLETE / CLOSED
Implementation:  AUTHORIZED (post-readiness; E2b PENDING)
ADR-0056:         RETAINED
```

Authorization recorded in `0058-implementation-authorization.md`.
The next formal gate is E2b Implementation Conformance.

## Decisions to freeze

```text
Q1  AdmittedMediaSource as sole authority-bearing runtime input
Q2  per-Source runtime lifecycle (create / retain / destroy)
Q3  jitter / reorder / late-media window
Q4  decode / PLC / warm-up
Q5  Top-K (K, hysteresis, hold)
Q6  mix / playout / limiter
Q7  Android resources (MulticastLock, rebind, caps)
Q8  runtime failure → Health projection boundary
Q9  acceptance matrix / invariants
Q10 sufficiency and implementation-authorization boundary
```

Only one question is decided at a time. This Profile is an execution consumer,
not a second Membership or Source-discovery system.
