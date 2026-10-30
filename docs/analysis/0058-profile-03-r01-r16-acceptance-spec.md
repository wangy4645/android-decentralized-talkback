# ADR-0058 Profile 03 — E2a R01–R16 Acceptance Specification

## Status

```text
E2a:                    PASS
Parent:                 0058-implementation-profile-03-media-runtime.md
Evidence gate:          0058-conformance-evidence-gate.md
E1:                     PASS
Implementation:         AUTHORIZED (E2b PENDING)
```

This document is an **evidence specification / harness shape** only. It does not
authorize product code, does not enlarge the frozen matrix, and does not invent
timeouts, thresholds, Health enums or failure classes.

## E2a PASS definition

```text
E2a PASS
=
R01–R16 each has:
  deterministic setup
  executable stimulus
  observable assertion
  frozen expected result
  explicit forbidden behavior
  identified harness seam

≠ product implementation PASS
≠ runtime field PASS
≠ implementation authorization
```

## Result labels (if a case cannot run yet)

```text
CONTRACT_CONTRADICTION
  frozen requirements cannot define a unique expected outcome
  → stop; explicit REOPEN / AMEND

IMPLEMENTATION_GAP
  contract clear; current product lacks injectable test seam
  → record for Readiness / post-auth work
  → MUST NOT reopen contract
  → does NOT block E2a PASS (spec completeness)
```

## Harness shape (shared)

```text
RuntimeUnderTest
  consumes AdmittedMediaSource + post-P02-admitted media frames
  owns Top-K / decode / PLC / mix / playout / resource caps

Fake / injectable (allowed):
  clock / media-time advance
  admitted Source bindings
  authenticated V/level snapshots
  packet arrival with RTP media-time / packet-index metadata
  decoder PCM output hooks
  AudioTrack / MulticastLock / socket / allocation failure injectors

Forbidden fakes:
  forging Profile 01 Facts
  bypassing AdmittedMediaSource
  inventing Health axes
  expanding TopK / MixMaxSources / MaxLiveDecoders / MaxJitterSources
```

Observables MUST be readable from contract seams or harness counters that
mirror those seams. Specs MUST NOT require new product telemetry APIs solely
to pass E2a.

### Frozen constants asserted across cases

```text
MaxPlayoutDelayMs        = 120
MaxReorderPackets        = 4
AdaptiveJitterWindow     = OFF
TopK                     = 4
TopKHoldMs               = 200
TopKHysteresisLevel      = 3
MaxConsecutivePlcFrames  = 5
DecoderWarmupMs          = 40   (maximum allowance)
MixMaxSources            = 4
OutputPeakAbs            <= 0.89 FS
MaxLiveDecoders          = 5
MaxJitterSources         = 10
HealthAggregationWindowMs = 1000 (evidence window only)
```

---

## R01 — normal Top-K / decode / mix

```text
Purpose:
  Selected Sources decode and mix under normal load.

Frozen clauses:
  Profile 03 Q4 ORDER=Top-K before decode
  Q5 TopK=4; Q6 MixMaxSources=4; OutputPeakAbs<=0.89 FS

Setup:
  2 AdmittedMediaSources S1,S2 executable
  both V=1; levels allow both in Top-K
  in-time packets for each (within MaxPlayoutDelayMs)

Stimulus:
  deliver contiguous admitted frames for S1 and S2

Observable:
  decode invocations per Source
  mix participant count
  peak abs sample of mix output (harness meter)

Expected:
  S1 and S2 decode
  mixed Sources == 2 (<=4)
  OutputPeakAbs <= 0.89 FS

Forbidden:
  decoding non-admitted Source
  mix count > 4
  Membership / Source / key mutation

Harness seam:
  Fake AdmittedMediaSource + frame pump + decode/mix counters + peak meter

Pass criterion:
  Expected holds for deterministic fixture run
```

## R02 — >4 simultaneous V=1

```text
Purpose:
  Only Top-4 consume decode/mix budget when >4 speakers active.

Frozen clauses:
  Q5 TopK=4; overload exclusion ≠ revoke / HARD FENCE / destroy

Setup:
  6 AdmittedMediaSources all V=1 with distinct levels
  rank order deterministic (lower level louder)

Stimulus:
  deliver in-time frames for all 6

Observable:
  Top-K set; decode set; mix set; authority stores unchanged

Expected:
  exactly 4 Sources decode and may mix
  2 excluded
  exclusion does not revoke Source bindings

Forbidden:
  decode/mix of excluded Sources (except bounded warm-down already in progress)
  HARD FENCE from exclusion alone
  expanding TopK

Harness seam:
  Fake 6 Sources + V/level snapshots + Top-K/decode counters

Pass criterion:
  Top-K cardinality == 4 and exclusions have no authority side effects
```

## R03 — V=0 replacement

```text
Purpose:
  V=0 ends active-candidate eligibility; Hold must not block a V=1 challenger.

Frozen clauses:
  Q5 Hold among V=1 only; V=0 MUST NOT keep Top-K seat by Hold alone

Setup:
  S_hold in Top-K with V=1; TopKHoldMs not expired
  S_challenger AdmittedMediaSource louder V=1 waiting

Stimulus:
  transition S_hold to V=0; keep delivering V=1 for S_challenger

Observable:
  Top-K membership; decode eligibility

Expected:
  S_hold loses active-candidate eligibility
  S_challenger may enter Top-K without waiting full Hold
  bounded playout/PLC drain of S_hold MAY continue briefly without retaining Top-K eligibility

Forbidden:
  retaining S_hold in Top-K solely because HoldMs remains
  treating V=0 as HARD FENCE / revoke

Harness seam:
  Fake V-bit updates + Top-K observer + optional drain counter

Pass criterion:
  Challenger enters while Hold would still be running under V=1-only rule
```

## R04 — reorder within window

```text
Purpose:
  Reordered in-deadline packets are reordered by media-time then decode-eligible.

Frozen clauses:
  Q3 MaxReorderPackets=4; MaxPlayoutDelayMs=120 authoritative deadline

Setup:
  one AdmittedMediaSource
  emit packet indices / media-times for slots T, T+1, T+2 out of arrival order
  all still within 120ms deadline and <=4 reorder capacity

Stimulus:
  deliver arrivals as T+2, T, T+1 (all in-time)

Observable:
  decode/media-time delivery order; LATE_FOR_PLAYOUT count

Expected:
  packets accepted for reorder
  delivered to decode in media-time order
  no LATE_FOR_PLAYOUT for these packets

Forbidden:
  expanding AdaptiveJitterWindow
  waiting past MaxPlayoutDelayMs to fill reorder capacity

Harness seam:
  Fake clock + controllable arrival order + decode order log

Pass criterion:
  decode order matches media-time order; zero LATE for fixture packets
```

## R05 — late >120ms → LATE_FOR_PLAYOUT

```text
Purpose:
  Past useful deadline: discard; do not decode/Top-K/mix.

Frozen clauses:
  Q3 LATE_FOR_PLAYOUT disposition; ≠ Q6 replay; ≠ Health by itself

Setup:
  AdmittedMediaSource; packet already SRTP/replay-valid in fixture metadata
  arrival after MaxPlayoutDelayMs useful deadline

Stimulus:
  deliver late packet

Observable:
  disposition class; decode/mix entry flags; Health evidence (must not flip alone)

Expected:
  LATE_FOR_PLAYOUT
  MUST NOT decode / enter Top-K / mixer / playout

Forbidden:
  treating as SRTP_REPLAY_TOO_OLD
  Source authority loss
  independent Health transition from this single late packet

Harness seam:
  Fake clock past deadline + admitted packet injector + disposition counter

Pass criterion:
  disposition == LATE_FOR_PLAYOUT and no decode/mix side effects
```

## R06 — PLC 1..5

```text
Purpose:
  Lost slots within bound are concealed by PLC for decode-eligible Source.

Frozen clauses:
  Q4 MaxConsecutivePlcFrames=5; PLC only for decode-eligible Source

Setup:
  Source in Top-K / decode-eligible
  create 3 consecutive deadline-lost slots (no packet)

Stimulus:
  advance media-time through 3 missing slots

Observable:
  PLC invocation count; synthesized frame count; HARD FENCE flag

Expected:
  PLC runs for each missing slot (count == 3)
  Source remains decode-eligible
  no HARD FENCE

Forbidden:
  PLC for non-decode-eligible Source
  authority mutation

Harness seam:
  Fake missing slots + PLC counter on decode-eligible Source

Pass criterion:
  1 <= PLC frames <= 5 for this fixture (exactly 3)
```

## R07 — PLC >5

```text
Purpose:
  PLC exhaustion stops synthesis; runtime degradation only.

Frozen clauses:
  Q4 exhaustion → silence/gap; ≠ HARD FENCE; ≠ authority loss; ≠ independent Health flip

Setup:
  decode-eligible Source
  6 consecutive deadline-lost slots

Stimulus:
  advance through 6 missing slots

Observable:
  PLC count; synthesis after frame 5; fence/authority flags; Health evidence

Expected:
  PLC for first 5 only
  after exhaustion: stop synthesized audio (silence/gap)
  no HARD FENCE / Source revoke
  no independent Health flip from PLC exhaustion alone

Forbidden:
  unbounded PLC
  forging Source authority via PLC

Harness seam:
  Fake 6 missing slots + PLC/synthesis counters

Pass criterion:
  PLC invocations == 5; synthesis stops; authority unchanged
```

## R08 — warm-up <=40ms allowance

```text
Purpose:
  New Top-K entry may use at most DecoderWarmupMs; usable first frame needs no forced wait.

Frozen clauses:
  Q4 DecoderWarmupMs=40 maximum allowance ≠ mandatory delay

Setup:
  Source newly enters Top-K
  Case A: decoder first PCM frame immediately usable
  Case B: decoder needs warm-up frames within 40ms

Stimulus:
  admit Source into Top-K; observe mixer eligibility timing

Observable:
  time-to-mixer-eligible; forced idle gap

Expected:
  Case A: once usable PCM is available, mixer eligibility MUST NOT be
          delayed solely to consume DecoderWarmupMs; no mandatory 40ms wait
  Case B: warm-up completes within <=40ms allowance

Forbidden:
  mandatory 40ms playout delay when PCM already usable
  warm-up creating authority

Harness seam:
  Fake decoder with controllable first-usable-frame latency + mixer gate observer

Pass criterion:
  Case A: no artificial mandatory warm-up wait after usable PCM
  Case B: warm-up completes within DecoderWarmupMs
```

## R09 — HARD FENCE + late packets

```text
Purpose:
  Fenced execution incarnation is immediately non-executable; late packets do not resurrect.

Frozen clauses:
  Q2 HARD FENCE; late packets MUST NOT resurrect fenced incarnation

Setup:
  Source execution incarnation active then HARD FENCED (e.g. REMOVE / supersession injector)
  late or on-time packets for the fenced incarnation arrive after fence

Stimulus:
  inject HARD FENCE; then deliver packets for old incarnation

Observable:
  execution eligibility; decode/mix; fence timestamp vs teardown completion

Expected:
  eligibility terminates immediately at fence
  packets after fence do not decode/mix
  fence completion does not wait for native teardown

Forbidden:
  resurrection because packets resume
  treating soft reclaim as fence

Harness seam:
  Authority fence injector + packet pump + eligibility flag (not waiting on fake teardown)

Pass criterion:
  post-fence packet count decoded == 0; fence observed before teardown completion
```

## R10 — key epoch fence

```text
Purpose:
  Old mediaKeyEpoch execution incarnation is fenced; state does not default-inherit.
  Already-admitted / already-queued work cannot execute after HARD FENCE.

Frozen clauses:
  Q2 (Source, mediaKeyEpoch, context) incarnation; V1 no cross-epoch state inheritance
  Profile 02 RETIRED_FENCE owns newly arriving old-epoch packets

Setup:
  Source S active under Epoch 7 with jitter/decode state
  Epoch-7 frame F7 already admitted by Profile 02 and queued toward
  Profile 03 BEFORE retirement

Stimulus A — execution fence:
  Before F7 executes, inject Epoch-7 retirement / HARD FENCE
  Then attempt to execute the already-queued F7

Stimulus B — new epoch:
  create fresh Epoch-8 AdmittedMediaSource
  deliver fresh post-P02-admitted Epoch-8 frame

Out of harness input:
  a post-retirement newly arriving Epoch-7 packet is owned by
  Profile 02 RETIRED_FENCE and MUST NOT be injected as
  post-P02-admitted Profile 03 media

Observable:
  Epoch-7 eligibility; whether queued F7 decodes after fence;
  Epoch-8 eligibility; whether Epoch-7 jitter/decode state is reused

Expected:
  A: queued stale F7 MUST NOT decode/mix after fence
  B: Epoch-8 executes with fresh execution incarnation;
     Epoch-7 jitter/decode state is not inherited

Forbidden:
  injecting post-retirement Epoch-7 wire arrivals as P03 harness input
  dual-epoch executable mix for same Conference beyond frozen rules
  resurrection of Epoch-7 runtime

Harness seam:
  Dual-epoch AdmittedMediaSource fixtures + pre-queued frame + state-identity asserts

Pass criterion:
  A: F7 decode after fence == 0
  B: Epoch-8 uses fresh execution state object
```

## R11 — decoder #5 transition only; mix <=4

```text
Purpose:
  MaxLiveDecoders=5 allows transition resource; must not create mix source #5.

Frozen clauses:
  Q7 MaxLiveDecoders=5; Q6 MixMaxSources=4 absolute

Setup:
  4 Sources already in mix/decode
  5th Source enters warm-up / warm-down transition

Stimulus:
  allocate 5th live decoder for transition while 4 remain mixed

Observable:
  live decoder count; mix participant count

Expected:
  live decoders <= 5
  mixed Sources <= 4
  5th decoder does not add mix source #5

Forbidden:
  MixMaxSources becoming 5
  expanding TopK because decoder #5 exists

Harness seam:
  Decoder allocator counters + mix set observer

Pass criterion:
  decoders==5 and mix<=4 simultaneously in transition fixture
```

## R12 — jitter cap 10

```text
Purpose:
  11th jitter-source allocation rejected/reclaimed; no cap expansion.

Frozen clauses:
  Q7 MaxJitterSources=10; exhaustion ≠ expand caps / bypass admission

Setup:
  10 AdmittedMediaSources already holding jitter ephemeral state

Stimulus:
  attempt to allocate jitter state for Source #11

Observable:
  allocation result; MaxJitterSources; TopK unchanged

Expected:
  11th allocation rejected OR reclaim-existing-then-allocate
  Allowed outcomes:
    REJECT_NEW
    OR RECLAIM_EXISTING_THEN_ALLOCATE
  Invariant in either case: active jitter states <= 10
  no admission bypass

Forbidden:
  silently raising MaxJitterSources
  creating unbound jitter for non-AdmittedMediaSource

Harness seam:
  Jitter-state allocator with hard cap injector

Pass criterion:
  active jitter states <= 10 after stimulus; one of the allowed outcomes observed
```

## R13 — MulticastLock lifecycle

```text
Purpose:
  Lock owned by Conference media transport scope; silence/V=0/empty Top-K alone must not release.

Frozen clauses:
  Q7 MulticastLock ownership; release only on transport-scope end

Setup:
  Module in executable Conference media transport scope; lock held
  Top-K becomes empty; all V=0; temporary packet silence; soft reclaim decoders

Stimulus:
  A: silence / V=0 / empty Top-K / soft reclaim
  B: Local Leave / REMOVE self / TERMINATED / Tombstone (authoritative transport end)

Observable:
  lock held/released; FakeMulticastLock release invocation

Expected:
  A: lock remains held
  B: transport-scope end MUST initiate release of the Conference-owned
     MulticastLock; for FakeMulticastLock, release invocation MUST be observed
     (system-internal completion latency of real Android release() is not frozen)

Forbidden:
  lock state mutating Membership/Source authority
  releasing on A alone

Harness seam:
  Fake MulticastLock with acquire/release spy + transport-scope end injector

Pass criterion:
  A keeps lock; B observes release invocation on transport-scope end
```

## R14 — interface rebind

```text
Purpose:
  Socket/interface rebind may rebuild transport; must not invent authority.

Frozen clauses:
  Q7 rebind ≠ admission / resurrection / generation-epoch / clear HARD FENCE

Setup:
  Conference JOINED with AdmittedMediaSources; one Source HARD FENCED
  transport path flapping

Stimulus:
  inject interface rebind / socket rebuild / re-join Endpoint

Observable:
  socket rebuild flag; Source set; fence flags; conferenceEpoch; Membership

Expected:
  socket MAY rebuild and re-join bound MediaGroupEndpoint
  fenced Source stays fenced
  no new Source admission from rebind
  conferenceEpoch unchanged
  no invented Membership events

Forbidden:
  using rebind as Source resurrection
  clearing HARD FENCE because socket is new

Harness seam:
  Fake socket/rebind injector + authority snapshot compare

Pass criterion:
  authority snapshot equal except transport handle; fence preserved
```

## R15 — AudioTrack / playout failure

```text
Purpose:
  Playout failure is bounded runtime degradation evidence; no authority mutation.

Frozen clauses:
  Q6 playout failure = runtime degradation only
  Q8 evidence producer; MUST NOT require asserting a specific Q12 enum unless Q12 frozen mapping demands it

Setup:
  normal mix running

Stimulus:
  inject AudioTrack write failure / underrun / device busy

Observable:
  runtime degradation evidence flag; Membership/Source/key stores; TopK/Mix caps

Expected:
  bounded runtime degradation evidence recorded
  no Membership / Source / key mutation
  no TopK/Mix expansion
  no HARD FENCE solely from one underrun

Forbidden:
  treating playout failure as Source revoke
  inventing new failure class names beyond frozen disposition/evidence vocabulary

Harness seam:
  Fake AudioTrack failure injector + evidence sink + authority snapshot

Pass criterion:
  evidence observed; authority snapshot unchanged
```

## R16 — resource exhaustion

```text
Purpose:
  Exhaustion degrades/rejects allocation; never expands K/caps or bypasses admission.

Frozen clauses:
  Q7 resource exhaustion; Q5/Q6/Q7 caps immutable under load

Setup:
  at MaxLiveDecoders and/or MaxJitterSources

Stimulus:
  request additional decoder/jitter allocation under exhaustion

Observable:
  reject/degrade evidence; TopK; MixMaxSources; MaxLiveDecoders; MaxJitterSources
  admission decisions

Expected:
  bounded degradation / rejection evidence
  caps unchanged
  no AdmittedMediaSource bypass

Forbidden:
  expanding TopK / MixMaxSources / MaxLiveDecoders / MaxJitterSources
  admitting unbound Sources to relieve pressure

Harness seam:
  Allocation reject injector + cap asserts + evidence sink

Pass criterion:
  rejection/degrade evidence present; all frozen caps unchanged
```

---

## Completeness checklist (for E2a review)

```text
[x] R01–R16 each has Purpose / Frozen clauses / Setup / Stimulus /
    Observable / Expected / Forbidden / Harness seam / Pass criterion
[x] No new timeout / threshold / failure class / Health enum
[x] Observables are seam-readable without new product telemetry requirements
[x] Harness fakes listed; core behavior not replaced by fake success
[x] E2a PASS ≠ product PASS stated explicitly
[x] CONTRACT_CONTRADICTION = NONE
[x] IMPLEMENTATION_GAP items listed only as post-auth work (do not block E2a)
[x] R08 wording: no artificial mandatory warm-up (no ≈0 timing threshold)
[x] R10 fixture: queued-pre-fence Epoch-7; no post-retirement P03 inject
[x] R13 wording: transport-scope end MUST invoke release
```

## Completeness / executability review result

```text
1. Observable / seam readability      PASS
2. Frozen expected / no new semantics PASS
3. Harness executability              PASS
     (R10 FIXTURE_DEFECT corrected)
4. Gap classification                 PASS

CONTRACT_CONTRADICTION                 NONE
IMPLEMENTATION_GAP                    IG-01 / IG-02, non-blocking

E2a — PASS
```

## Initial IMPLEMENTATION_GAP register (non-blocking for E2a)

```text
IG-01  Product may lack injectable MulticastLock/AudioTrack/rebind seams today
       → needed for E2b after authorization; contract expectations above remain clear

IG-02  Product may lack pure unit harness for Top-K without full Native stack
       → Readiness work item; does not reopen Q5
```

No CONTRACT_CONTRADICTION identified. R10 post-retirement inject wording was a
FIXTURE_DEFECT and has been corrected without reopening Profile 03.
