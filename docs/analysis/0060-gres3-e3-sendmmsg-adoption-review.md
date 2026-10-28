# G-RES-3 E3 — `sendmmsg` Adoption Review

## Status

```text
Track:              G-RES-3 E3 — sendmmsg batching adoption review
Type:               engineering decision track — CLOSED (PASS — ADOPT)
Date:               2026-09-04
Prerequisites:      E3 architecture probe 3× PASS (M03)
Parent:             G-RES-3 E2 execution-model adjudication
                    G-RES-3-C2 Model B1 · Profile 02 A4/A6/A8/A9
ADR-0060:           no change — Profile/C2 refinement (classified at amendment closeout)
H1c:                PASS / CLOSED (E3 `gres3-e3-h1c-20260904-094413`)
3×QUALIFYING:       HOLD — preflight BLOCKED (see 0060-gres3-c4-qualifying-preflight.md)
C3:                 FROZEN (UNCHANGED)

Decision 1–4:       CLOSED
P02-A8 amendment:   ACCEPTED / CLOSED
G-RES-3-C2 amendment: ACCEPTED / CLOSED

E3 execution model: CONTRACT-ADOPTED (SENDMMSG_BATCH)
E3 adoption review: PASS — ADOPT
Production readiness: NOT YET
```

---

## Purpose

E3 probe established a **strong execution-model difference** sufficient to prefer E3
over E1/E0 for capacity evidence — but **not** sufficient to declare E3 the formal
product execution model.

This document is an **adoption review outline only**. It adjudicates whether E3 may
graduate from architecture probe to:

```text
authorized product fan-out submission primitive
  → eligible for E3 H1c re-run
  → eligible for 3×600s qualifying (if C3/C4 gates pass)
```

**Out of scope for this review:**

```text
ADR-0060 appendix update          (deferred until review closes)
C3 threshold changes
H1c / 3×QUALIFYING authorization  (separate gate after review)
E2 NIO/nonblocking comparison     (HOLD — not required given E3 signal)
general native networking framework
```

**Ordering rule:**

```text
E3 adoption review PASS
  → decide ADR-0060 appendix vs C2/implementation amendment only
  → then authorize E3 H1c → 3×600s

Do NOT write probe conclusions into ADR-0060 before review closes.
```

---

## Current posture (frozen)

```text
E0 shared socket sequential:
  REJECTED — capacity-evidence unstable

E1 per-target socket sequential:
  PROBE PASS / NOT SELECTED — insufficient tail margin

E2 NIO/nonblocking:
  CLOSED / NOT REQUIRED

E3 sendmmsg batching:
  CONTRACT-ADOPTED — SENDMMSG_BATCH
  Adoption review PASS — ADOPT

P02-A8 amendment:
  CLOSED → 0058-profile-02-a8-send-not-attempted-amendment.md

G-RES-3-C2 amendment:
  CLOSED → 0060-gres3-c2-submission-primitive-amendment.md

ADR-0060:
  NO CHANGE (Profile/C2 layer sufficient)

H1c:
  AUTHORIZED (E3 execution model)

3×QUALIFYING:
  HOLD — pending E3 H1c PASS

C3:
  FROZEN (UNCHANGED)

Production cutover:
  NOT AUTHORIZED
```

---

## Evidence trigger (why review is authorized)

### E3 probe (M03, hash `06cffc9a…`, `SENDMMSG_BATCH`)

| Run | P99 | MAX | sendFail | deadlineMiss | partialBatch | warmEgress |
|-----|-----|-----|----------|--------------|--------------|------------|
| #1 | 2.765ms | 7.649ms | 0 | 0 | 0 | 9/9 |
| #2 | 2.369ms | 7.345ms | 0 | 0 | 0 | 9/9 |
| #3 | 2.866ms | 8.521ms | 0 | 0 | 0 | 9/9 |

```text
returnedMessages == 9 for every slot (all runs)
minReturnedMessages = 9
partialBatchCount = 0
```

### Contrast — E1 (NOT SELECTED)

| Run | P99 | MAX |
|-----|-----|-----|
| #1 | 4.208ms | 9.815ms |
| #2 | 4.607ms | 9.778ms |
| #3 | 4.691ms | 9.945ms |

```text
Signal interpretation:
  P99: E1 ~4.2–4.7ms  →  E3 ~2.4–2.9ms
  MAX: E1 ~9.8–9.9ms (borderline)  →  E3 ~7.3–8.5ms (material margin)

Magnitude is execution-model difference, not measurement noise.
Sufficient to open adoption review; NOT sufficient to skip it.
```

Logs:

```text
talkback/logs/gres3-e3-probe-1-20260904-091556/
talkback/logs/gres3-e3-probe-2-20260904-092108/
talkback/logs/gres3-e3-probe-3-20260904-092606/
talkback/logs/gres3-e1-probe-{1,2,3}-20260904-*/
```

---

## Frozen non-scope: E3 adoption ≠ RTP/SRTP contract change

E3 changes **fan-out submission primitive only**. The following remain frozen
regardless of adoption outcome:

```text
protect-once per logical slot
byte-identical replication to all 9 legs
one logical RTP timeline
same seq / ROC advance semantics
same 120B worst-case envelope (P02-A5)
50 logical slots/s (P02-A6)
no retry
no catch-up
no multicast assistance in capacity gate
```

```text
E3 does NOT reopen:
  Profile 02 A2/A3 seq/ROC contract
  Profile 02 A4 protect-once
  Profile 02 A6 pacing / late-slot expendability
  G-RES-3-C3 PASS thresholds
```

---

## Review scope — four decisions only

The adoption review MUST close exactly four decision areas. Each area has a binary or
ternary outcome and explicit artifacts required before H1c unlock.

---

### Decision 1 — Partial `sendmmsg` semantics — **CLOSED**

**Verdict:**

```text
Decision 1:
  ACCEPT — PREFIX COMMIT / SUFFIX NOT-ATTEMPTED

partial-batch semantics:
  deterministic

retry:
  PROHIBITED

catch-up:
  PROHIBITED

seq/ROC:
  NO ROLLBACK

A8 compatibility:
  structurally achievable (formal mapping → Decision 2)
```

**Core principle:**

> Partial batch means **prefix committed + suffix not attempted** — not “all 9
> receivers failed.”

Suffix legs `leg[R+1..8]` MUST NOT be labeled `SEND_FAILED`, because the kernel did
not attempt submission for them. Doing so would falsify per-leg outcome truth required
by Profile 02 A8.

---

#### Normative model — `SendmmsgBatchOutcome`

Let `sendmmsg(messages[0..8])` return `R` (requested count = 9).

```text
R == 9:
  leg[0..8] = SEND_SUCCEEDED
  slot fan-out submission complete

0 <= R < 9:
  leg[0..R-1] = SEND_SUCCEEDED
  leg[R]      = SEND_FAILED_AT_BATCH_BOUNDARY
  leg[R+1..8] = SEND_NOT_ATTEMPTED
  current slot ends (no further submission for this slot)

R < 0  (syscall error — no datagram transmitted):
  leg[0]    = SEND_FAILED_AT_BATCH_BOUNDARY
  leg[1..8] = SEND_NOT_ATTEMPTED
  current slot ends
```

**Slot boundary rule:** one `sendmmsg(9)` call per logical slot. Partial return
terminates fan-out submission for that slot. No second syscall.

**Logical emission (A2–A4, A6):** protect-once and seq/ROC advance occur **before**
batch submission and are **not rolled back** by partial batch. A partial batch affects
**per-leg transport outcomes only** — consistent with A8 (“one leg sendto failure does
not mean logical emission did not occur”).

---

#### Frozen prohibitions (partial batch)

```text
partial sendmmsg
  ≠ retry trigger
  ≠ second sendmmsg in same slot
  ≠ fallback to DatagramSocket.send() for suffix legs
  ≠ next-slot catch-up for unsubmitted legs
```

Aligned with P02-A6 late-media-expendable / no catch-up and P02-A8 per-leg isolation.

---

#### Platform verification — prefix commit & errno attribution

**Prefix commit (Q1.1):** ACCEPT per Linux/Android kernel contract.

```text
sendmmsg() return value = number of msgvec elements successfully updated
0 <= R < vlen  →  messages[0..R-1] transmitted; failure at index R; suffix not attempted
```

Sources: Linux `sendmmsg(2)`; Android kernel commit 728ffb8 (“only return error if no
datagrams were sent”; partial success returns count, not -1).

**Errno at failure boundary (leg[R]):** NOT RELIABLY ATTRIBUTABLE on partial success.

```text
Linux sendmmsg(2) BUGS section:
  "If an error occurs after at least one message has been sent, the call succeeds,
   and returns the number of messages sent. The error code is lost."

Therefore for 0 <= R < 9:
  leg[R].failureReason = UNKNOWN   (or UNAVAILABLE)
  do NOT invent errno from syscall return
  do NOT guess per-leg cause from batch-level errno

For R < 0 (zero datagrams sent):
  errno MAY be recorded if returned by syscall
  leg[0].failureReason = errno if available, else UNKNOWN
```

Product/harness observability SHOULD record `returnedMessages`, `requestedMessages`,
and `failureReason` separately. errno is **best-effort diagnostic only** when
`R < 0`; it is **not contract truth** when `0 <= R < 9`.

---

#### Resolved open questions (Decision 1 scope)

| ID | Resolution |
|----|------------|
| Q1.1 Prefix commit | **YES** — legs `0..R-1` committed; failure at `R`; suffix not attempted |
| Q1.2 sendFailCount | **Deferred to Decision 2** — depends on A8 mapping of `SEND_NOT_ATTEMPTED` vs `SEND_FAILED` |
| Q1.3 Runtime policy | Partial batch is a **tolerated runtime outcome** with frozen no-retry; not harness-only fiction |
| Q1.4 fanoutCompletionDuration | **Deferred to Decision 3 (C2)** — batch syscall return ends submission phase for slot |

---

#### Exit artifact — `SendmmsgBatchOutcome` mapping table

| `R` | Leg range | `SendmmsgLegSubmissionOutcome` | Notes |
|-----|-----------|--------------------------------|-------|
| `9` | `0..8` | `SEND_SUCCEEDED` | Complete batch |
| `0..8` | `0..R-1` | `SEND_SUCCEEDED` | Committed prefix (empty if `R=0`) |
| `0..8` | `R` | `SEND_FAILED_AT_BATCH_BOUNDARY` | First failed / blocking leg |
| `0..8` | `R+1..8` | `SEND_NOT_ATTEMPTED` | Kernel did not attempt |
| `<0` | `0` | `SEND_FAILED_AT_BATCH_BOUNDARY` | Zero datagrams sent |
| `<0` | `1..8` | `SEND_NOT_ATTEMPTED` | |

`SendmmsgLegSubmissionOutcome` is the **batch-submission-layer** outcome (E3-specific).
Mapping to frozen `PerLegDeliveryOutcome` → **Decision 2 CLOSED** (adds
`SEND_NOT_ATTEMPTED`; maps `SEND_SUCCEEDED`→`SENT`, boundary→`SEND_FAILED`).

Probe harness today: treats `returnedMessages < 9` as probe send failure (no retry).
Product path MUST implement the table above; evidence MUST expose per-leg outcomes after
Decision 2 mapping.

---

### Decision 2 — Per-leg failure isolation (P02-A8) — **CLOSED**

**Verdict:**

```text
Decision 2:
  ACCEPT — mapping defined

A8 tri-state today:
  SENT | DROPPED_LOCAL | SEND_FAILED

SEND_NOT_ATTEMPTED reuse of DROPPED_LOCAL:
  REJECTED (semantic mismatch)

minimal P02-A8 amendment:
  REQUIRED — add SEND_NOT_ATTEMPTED to PerLegDeliveryOutcome

A8 isolation rules (R1–R5):
  UNCHANGED

sendFailCount inflation guard:
  CLOSED (see counting rules below)

Decision 3 (C2):
  READY
```

**Core principle:**

> **“没尝试”不是“尝试失败”。** `SEND_NOT_ATTEMPTED` MUST NOT be mapped to
> `SEND_FAILED` or `DROPPED_LOCAL`.

---

#### A8 tri-state audit (D2.1)

Frozen P02-A8 `PerLegDeliveryOutcome` today:

```text
SENT            datagram accepted by local transport for this leg
DROPPED_LOCAL   local policy dropped before send (evidence only)
SEND_FAILED     sendto / socket error / bounded backpressure drop for this leg
```

A8 is **not** success/failure binary. However, **none** of the three frozen values
correctly express Decision 1 `SEND_NOT_ATTEMPTED`:

| Candidate | Verdict | Reason |
|-----------|---------|--------|
| `SENT` | REJECT | Kernel did not submit datagram |
| `SEND_FAILED` | REJECT | No send attempt occurred — falsifies transport truth |
| `DROPPED_LOCAL` | REJECT | Means **sender policy** chose to drop before send; not batch-boundary suffix suppression |

**Conclusion:** A8 **semantic amendment required** — add one enum value. This is a
**minimal extension**, not a reopening of F1/F2/F3 or isolation rules.

---

#### Required P02-A8 amendment (draft scope — not yet formal)

```text
PerLegDeliveryOutcome (amended):

  SENT
  DROPPED_LOCAL
  SEND_FAILED
  SEND_NOT_ATTEMPTED    ← NEW

SEND_NOT_ATTEMPTED:
  fan-out submission for this leg was not reached in the current slot
  (e.g. sendmmsg batch stopped at boundary; kernel did not attempt this message index)
  transport evidence only
  MUST NOT be counted as SEND_FAILED
  MUST NOT be labeled DROPPED_LOCAL
  MUST NOT roll back RTP/SRTP timeline (same as other per-leg outcomes)
  MUST NOT trigger retry / catch-up (R3 unchanged)
  Profile 03 liveness policy on sustained SEND_NOT_ATTEMPTED → Profile 03 scope (not A8)
```

Formal amendment document: **CLOSED**
→ `docs/analysis/0058-profile-02-a8-send-not-attempted-amendment.md`

---

#### Normative mapping — `SendmmsgLegSubmissionOutcome` → `PerLegDeliveryOutcome`

| `SendmmsgLegSubmissionOutcome` | `PerLegDeliveryOutcome` | Meaning |
|--------------------------------|-------------------------|---------|
| `SEND_SUCCEEDED` | `SENT` | Datagram accepted by local transport for this leg |
| `SEND_FAILED_AT_BATCH_BOUNDARY` | `SEND_FAILED` | Send attempted at batch boundary; failed (`failureReason` UNKNOWN unless `R<0` errno) |
| `SEND_NOT_ATTEMPTED` | `SEND_NOT_ATTEMPTED` | Submission never reached this leg index in the slot |

```text
LogicalMediaEmissionOutcome:
  EMITTED regardless of per-leg mix (R1 unchanged)

Anti-patterns (rejected):
  SEND_NOT_ATTEMPTED → SEND_FAILED
  SEND_NOT_ATTEMPTED → DROPPED_LOCAL
  partial batch → whole-slot FAILED (F2)
  collapse to batch-level boolean only
```

---

#### C3 / evidence counting rules (D2.2 — CLOSED)

```text
sendFailCount (per slot / run aggregate):
  increment +1 ONLY for legs with PerLegDeliveryOutcome = SEND_FAILED
  i.e. SEND_FAILED_AT_BATCH_BOUNDARY at leg[R] only (typically one leg per partial batch)

notAttemptedCount (E3 evidence field — new):
  increment +1 for each leg with PerLegDeliveryOutcome = SEND_NOT_ATTEMPTED
  i.e. legs[R+1..8] on partial batch, or legs[1..8] on R<0 whole-batch error

SEND_SUCCEEDED (→ SENT):
  neither sendFailCount nor notAttemptedCount

Rationale:
  Treating SEND_NOT_ATTEMPTED as SEND_FAILED would inflate one boundary failure
  into multiple send failures and poison C3 capacity evidence.
```

**C2 observation alignment (closed in Decision 3):**

```text
perLegSendAttemptCount = count(SENT) + count(SEND_FAILED) per slot
  = 9 when all legs attempted (complete fan-out submission)

notAttemptedCount = count(SEND_NOT_ATTEMPTED)

perLegSendFailureCount = count(SEND_FAILED) only
```

---

#### Evidence bundle fields (D2.3)

E3 runs MUST expose receiver-level outcomes (not batch boolean only):

```text
perLegOutcomes[0..8]     PerLegDeliveryOutcome per admitted receiver
perLegSubmissionLayer[0..8]   optional: SendmmsgLegSubmissionOutcome (diagnostic)
sendmmsgBatch:
  requestedMessages
  returnedMessages
  boundaryLegIndex          R when 0 <= R < 9; else null
  failureReason             UNKNOWN on partial; errno best-effort when R < 0
counters:
  sendFailCount             SEND_FAILED legs only
  notAttemptedCount         SEND_NOT_ATTEMPTED legs only
```

Probe harness today collapses partial batch to slot-level failure; product/evidence
path MUST emit per-leg array per table above after A8 amendment lands.

---

#### A8 compatibility statement (D2.4)

```text
F1 per-leg isolation model:     COMPATIBLE (unchanged)
R1 timeline immunity:           COMPATIBLE (unchanged)
R2 cross-leg non-blocking:      COMPATIBLE (batch suffix is not "blocked by failure")
R3 no catch-up:                 COMPATIBLE (unchanged)
R4 multicast independence:      COMPATIBLE (unchanged)
R5 no whole-slot retry:         COMPATIBLE (unchanged)

Contract change required:       YES — minimal enum extension only
  add SEND_NOT_ATTEMPTED to PerLegDeliveryOutcome
  do NOT relabel existing SENT / DROPPED_LOCAL / SEND_FAILED semantics
```

---

### Decision 3 — C2 benchmark seam — **CLOSED**

**Verdict:**

```text
Decision 3:
  ACCEPT — READING A

C2 capacity semantics:
  UNCHANGED (9 real UDP datagram submission attempts per slot)

Reading A (datagram attempts, syscall-agnostic):
  ACCEPT

Reading B (mandatory 9× independent send() syscalls):
  REJECT

C2 textual amendment:
  REQUIRED — minimal execution-model generalization (wording only)

C3 thresholds:
  NO CHANGE

E3 qualifying eligibility:
  YES — after C2 amendment + Decision 4 closeout

harness/runtime parity:
  REQUIRED
```

**Core principle:**

> C2 measures **9 real UDP datagram submission attempts per logical slot** — not
> mandatory syscall count. `sendmmsg(9)` is one syscall carrying 9 independent receiver
> submissions; the 9-leg workload MUST NOT be collapsed to 1.

---

#### Reading adjudication (T3.1–T3.3)

**Reading A — ACCEPT**

```text
G3-C2-I4 intent:  real UDP send operations for all active legs
Model B1 workload: 9 distinct admitted targets × 50 slots/s
E3 sendmmsg(9):   one syscall, nine kernel datagram submissions (when R=9)

sendmmsg satisfies C2 capacity semantics IF:
  harness execution model = intended production runtime model
  per-leg outcomes recorded per Decision 2
  qualifying gates on perLegSendAttemptCount / notAttemptedCount met
```

**Reading B — REJECT**

```text
C2 adjudication text names send() literally in execution path and I6 timing
That wording reflects E0/E1 probe era — not normative capacity intent
Rejecting Reading B does NOT relax the 9-leg workload or allow 1-datagram shortcut
```

**A8 "sendto per leg" (T3.2):** Compatible under Reading A. Each leg still has an
independent `PerLegDeliveryOutcome`; batch syscall is submission transport, not outcome
collapse.

---

#### Frozen observation definitions (E3 / Reading A)

**Per-leg attempt counting:**

```text
perLegSendAttemptCount (per run):
  = Σ_slots count(legs with outcome SENT or SEND_FAILED)
  = count(SENT) + count(SEND_FAILED)

notAttemptedCount (per run):
  = Σ_slots count(legs with outcome SEND_NOT_ATTEMPTED)

expectedLegSendAttempts (formal qualifying, 600s × 4500 slots):
  = logicalSlotsEmitted × 9
  = 30,000 × 9
  = 270,000
```

**Formal qualifying gates (E3, unchanged intent):**

```text
perLegSendAttemptCount == 270,000
notAttemptedCount == 0
sendFailCount == 0
```

Partial batch (`R < 9`) fails qualifying via `notAttemptedCount > 0` and/or
`perLegSendAttemptCount < 270,000` — without inflating `sendFailCount`.

---

#### `fanoutCompletionDuration` (G3-C2-I6 — amended definition)

**Complete fan-out (`R == 9` — all legs `SENT`):**

```text
t0 = immediately before sendmmsg()
t1 = immediately after sendmmsg() returns

fanoutCompletionDuration = t1 - t0
fanoutCompletion       = COMPLETE
```

**Incomplete fan-out (`R < 9` or `R < 0`):**

```text
batchSyscallDuration = t1 - t0        (diagnostic only)
fanoutCompletion     = INCOMPLETE
fanoutCompletionDuration:
  MUST NOT be scored as C3 fanoutCompletionP99/Max
  MUST NOT use partial-return syscall time as "completion duration"
```

C3 frozen thresholds **unchanged** and apply only to **COMPLETE** slots:

```text
fanoutCompletionP99Us <= 5000
fanoutCompletionMaxUs  <= 10000
```

E3 probe runs (3/3, `partialBatchCount=0`) scored all slots COMPLETE under this rule.

---

#### Required C2 textual amendment (draft scope — not yet formal)

C2 **capacity semantics unchanged**; amend syscall-specific wording to generalize execution
primitive while preserving harness/runtime parity.

**Replace syscall-literal execution path with:**

```text
Each logical slot SHALL perform a real UDP submission for every configured
unicast target using the selected production sender execution model.

Fan-out completion is the point at which all target datagrams for that slot
have been successfully submitted by that execution model.
```

**Add / clarify:**

```text
Harness execution model MUST equal intended production runtime execution model.

Benchmark MUST NOT use a submission primitive absent from the production
fan-out path (no harness-only batching shortcut).

Observation definitions:
  perLegSendAttemptCount — legs with SENT or SEND_FAILED (attempted)
  notAttemptedCount      — legs with SEND_NOT_ATTEMPTED
  fanoutCompletionDuration — defined only when fanoutCompletion = COMPLETE
  batchSyscallDuration   — optional diagnostic; not a C3 PASS input when INCOMPLETE
```

**Sections requiring wording update in C2 adjudication doc:**

```text
G3-C2-I4 execution path bullet (send() → submission primitive)
G3-C2-I6 fanoutCompletionDuration definition
Anti-batching / runtime-parity clause (explicit harness = production model)
Raw observations table (notAttemptedCount; complete vs incomplete fan-out)
```

Formal C2 amendment document: **CLOSED**
→ `docs/analysis/0060-gres3-c2-submission-primitive-amendment.md`

---

#### C2 disposition matrix (exit artifact)

| Item | Disposition |
|------|-------------|
| C2 capacity semantics (9-leg workload) | **UNCHANGED** |
| Reading A | **ACCEPT** |
| Reading B | **REJECT** |
| C2 textual amendment | **REQUIRED** (minimal) |
| C3 thresholds | **NO CHANGE** |
| E3 qualifying admissibility | **YES** after A8 + C2 amendments land |
| Harness-only E3 shortcut | **REJECT** |
| `benchmarkConfigHash` | Re-baseline on E3 formal profile |

---

### Decision 4 — JNI / native production cost — **CLOSED**

**Verdict:**

```text
Decision 4:
  ACCEPT — BOUNDED NATIVE TRANSPORT PRIMITIVE

JNI/native production cost:
  ACCEPTABLE — bounded and justified

scope:
  sendmmsg fan-out submission only

native networking framework:
  NOT AUTHORIZED

production cost:
  BOUNDED / JUSTIFIED

production hardening:
  REQUIRED BEFORE CUTOVER

E3 adoption blocker:
  NONE

production readiness:
  NOT YET
```

**Rationale:**

JNI is not zero-cost. The cost is **justified by measured capacity benefit**:

```text
E1 MAX:  ~9.8–9.9ms  (borderline — insufficient margin)
E3 MAX:  ~7.3–8.5ms  (material margin away from C3 10ms boundary)
E3 P99:  ~2.4–2.9ms  (vs E1 ~4.2–4.7ms)
```

Benefit targets the current G-RES-3 tail-risk directly. Decision 4 judges whether the
cost is **bounded with a clear closure path** — not whether all hardening is complete
today.

---

#### Product boundary (frozen — narrow scope)

```text
Kotlin/Java OWNS:
  pacing / slot scheduler
  RTP/SRTP artifact (protect-once)
  seq / ROC advance
  target set / admission enumeration
  policy (no retry, no catch-up)
  metrics / adjudication semantics
  PerLegDeliveryOutcome mapping (Decision 2)

JNI/native OWNS ONLY:
  sendmmsg(fd, messages...)
  returned message count
  errno when available (R < 0; partial → UNKNOWN per Decision 1)

FORBIDDEN in native:
  RTP / SRTP
  scheduler / pacing
  fallback policy
  liveness / receiver state
  internal retry on partial batch
  second socket lifecycle hidden from transport owner
```

Native is a **batching primitive**, not a networking framework.

---

#### Hardening conditions (required before production cutover)

Decision 4 **does not require** full implementation during adoption review. These are
**mandatory closure items** before production cutover / ProvenNMax claim:

| # | Condition | Requirement |
|---|-----------|-------------|
| H4.1 | **FD ownership** | Socket lifecycle owned by explicit transport owner; native does not create or long-hold a parallel socket lifecycle |
| H4.2 | **close/send race** | Deterministic rule on teardown vs in-flight `sendmmsg`; no fd reuse sending to wrong socket |
| H4.3 | **ABI / package** | Ship only product Android ABIs; missing native lib → **fail closed**; MUST NOT silently fall back to E1 while claiming E3 capacity |
| H4.4 | **error boundary** | JNI/native errors → typed `BatchResult`; no exception leakage into undecidable state |
| H4.5 | **partial semantics** | Decision 1 PREFIX COMMIT / SUFFIX NOT-ATTEMPTED; **no native internal retry** |
| H4.6 | **parity** | Formal harness and production runtime call the **same E3 submission implementation/seam**; no separate "benchmark JNI" |
| H4.7 | **tests** | Minimum matrix: full 9/9; partial batch; zero/-1 errno; close race; target order mapping; `SEND_NOT_ATTEMPTED` per-leg outcomes |

---

#### Checklist adjudication

| Area | Verdict | Notes |
|------|---------|-------|
| Android ABI | ACCEPT | minSdk 26+ bionic; arm64-v8a / armeabi-v7a per product matrix |
| Crash boundary | MITIGATE | Thin JNI; hardening H4.2/H4.4 before cutover |
| errno mapping | ACCEPT | Typed result; partial errno UNKNOWN (Decision 1) |
| FD ownership | MITIGATE | H4.1/H4.2 required before cutover |
| Lifecycle races | MITIGATE | H4.2 close/send rule required |
| Testability | MITIGATE | H4.7 matrix required; JVM limits acknowledged |
| Build footprint | ACCEPT | Single-purpose `.so`; not a framework |
| Maintenance | ACCEPT | Scoped to sendmmsg seam only |

---

#### Probe → product seam notes

```text
Current:
  gres3_sendmmsg_probe.cpp + Gres3SendmmsgNative.kt
  FanOutSenderFactory → SendmmsgBatchFanOutSender

Product path:
  Promote same seam to production packaging (H4.6)
  Re-baseline benchmarkConfigHash on E3 formal profile
  Do NOT maintain harness-only native fork
```

---

## Adoption review closeout

**All four decisions CLOSED. Review result:**

```text
E3 ADOPTION REVIEW:
  PASS — ADOPT

Selected sender execution model:
  SENDMMSG_BATCH (Gres3SenderExecutionModel)

E0:  REJECTED
E1:  NOT SELECTED
E2:  CLOSED / NOT REQUIRED
E3:  ADOPTED (probe + review)

Required amendments:
  P02-A8 SEND_NOT_ATTEMPTED          CLOSED
  G-RES-3-C2 submission primitive    CLOSED

C3:
  UNCHANGED

ADR-0060:
  NO CHANGE — execution-model refinement at Profile/C2 layer only

Production readiness:
  NOT YET — H4.1–H4.7 hardening before cutover

H1c:
  PASS / CLOSED (E3 `gres3-e3-h1c-20260904-094413`)

3×QUALIFYING:
  HOLD — preflight BLOCKED before Run #1
  blockers: runClass wiring · instrument timeout · sink duration · evidence field gaps
```

---

## Authorized execution sequence (current position)

```text
✓ 1. P02-A8 amendment — CLOSED
✓ 2. G-RES-3-C2 amendment — CLOSED
✓ 3. ADR-0060 classification — NO CHANGE required
→ 4. E3 H1c lightweight re-run — PASS / CLOSED (`gres3-e3-h1c-20260904-094413`)
→ 5. 3×600s QUALIFYING — HOLD (preflight BLOCKED; fix harness wiring before Run #1)
  6. Production hardening H4.1–H4.7 before cutover (may parallel qualifying)
```

```text
FORBIDDEN sequence:
  H1c before A8 + C2 amendments
  3×QUALIFYING before H1c PASS
  production cutover before hardening + qualifying
  harness-only E3 evidence for ProvenNMax claim
```

This is **formal acceptance of the selected model**, not continued architecture exploration.

---

## Review process

```text
Phase A — Contract read-through:     CLOSED (Decisions 1–3)
Phase B — Engineering cost:          CLOSED (Decision 4)
Phase C — Adoption closeout:         PASS — ADOPT

Next track: amendment authoring → H1c → qualifying
```

**Review participants / sign-off:** (TBD — engineering + contract owner)

---

## Closeout outcomes and downstream routing

### Active path — `ADOPT_E3_PRODUCT_PATH` (selected)

```text
✓ P02-A8 + G-RES-3-C2 amendments CLOSED
→ E3 H1c lightweight re-run — AUTHORIZED
  H1c PASS → 3×600s QUALIFYING
  Hardening H4.1–H4.7 before production cutover (may proceed in parallel with H1c)
```

### If amendments delayed

```text
A8 + C2 amendments REQUIRED before H1c unlock (both)
Capacity semantics unchanged
E3 remains ADOPTED execution model
```

### If `REJECT_E3` (not selected — archived for reference)

```text
E3 archived as probe-only evidence
Re-open E2 NIO review OR reassess C3 vs Android/WLAN runtime fit (ADR track only)
Do NOT silently relax C3
```

---

## What this review explicitly does NOT decide

```text
C3 numeric thresholds (P99≤5ms, MAX<10ms, slot<20ms) — FROZEN
MeasurementDuration (600s × 3)
Device matrix / topology approval
Multicast / Profile 03 liveness
WiFi recovery / RNA tracks (out of scope)
Production cutover / ProvenNMax claim (separate gate after qualifying)
Full production hardening implementation (required before cutover, not before review close)
```

---

## References

- `docs/analysis/0060-gres3-e2-sender-execution-model-adjudication.md`
- `docs/analysis/0060-gres3-c2-sender-capacity-test-contract-adjudication.md`
- `docs/analysis/0060-gres3-c3-pass-metrics-adjudication.md`
- `docs/analysis/0058-profile-02-a8-send-failure-isolation-adjudication.md`
- `docs/analysis/0058-profile-02-a9-fanout-envelope-adjudication.md`
- `docs/analysis/0058-profile-02-a8-send-not-attempted-amendment.md`
- `docs/analysis/0060-gres3-c2-submission-primitive-amendment.md`
- `docs/adr/0060-conference-media-transport-resilience-contract.md` (no change — Profile/C2 layer)
- Probe harness: `SendmmsgBatchFanOutSender.kt`, `Gres3SendmmsgNative.kt`, `gres3_sendmmsg_probe.cpp`
