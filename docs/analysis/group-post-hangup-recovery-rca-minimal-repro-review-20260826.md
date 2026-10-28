# GROUP Post-Hangup Recovery — RCA / Minimal Repro Review

## Status

**REVIEW** (2026-08-26)

**Implementation authorization:** **NONE**

**Track:** C — independent of Track A (Primary Native SRD) and Track B (MESH LIVE presentation)

**Classification:** **PRODUCT BUG** (not “field environment dirty”)

---

## Frozen track posture

```text
Track A — Primary Native SRD
  second SRD → native mutex deadlock
  R3 = NOT CAPTURED
  mutex closure = OPEN

Track B — MESH LIVE presentation
  Q1 = NO · CLOSED · no amendment

Track C — Post-Hangup GROUP Recovery (this review)
  Conference end / abort → GROUP
  mesh ICE not re-bootstrapped
  transmitReady=false → subsequent Meeting fails
  = PRODUCT BUG
```

**Do not merge Track C into Track A** because both can present as “cannot start meeting after hangup.”

---

## Problem statement

After Conference hangup (especially abortive), M01 may return to GROUP without recovering group media readiness:

```text
meshMissingIce=M02,M03
bootstrapAttemptCount=0
transmitReady=false (persistent)
channelReadiness=CONNECTING
subsequent MEETING_START → declaration_not_frozen / admission blocked
```

This is **not** the frozen MEETING_END T3 timeout class. Observed sessions show `transitionState=IDLE` · `terminalReady=true` while mesh recovery remains broken.

**Parent domains (reference only — no fix scope in this review):**

- ADR-0016 / ADR-0027 — `MEETING_END` · convergence window · bootstrap
- [media-engine-ownership-hangup-gate.md](../architecture/media-engine-ownership-hangup-gate.md) — Conference → GROUP release ordering
- Track B review — 3p MESH CONNECTING UI is contract-consistent; **does not explain transmitReady=false**

---

## Minimal RCA chain (field evidence 2026-08-26 ~17:32, M01)

**Observed sequence (single session — not multi-run aggregate):**

```text
GROUP not ready at admission time
    ↓
declaration_not_frozen · declarationFrozen=false
    ↓
MEETING_START transition T4 entered (SESSION_CREATED)
    ↓
early hangup (~4s) while predicate still unsatisfied
    ↓
CONFERENCE → GROUP release (Conference channel released for GROUP PTT)
    ↓
meshMissingIce=M02,M03
bootstrapAttemptCount=0
transmitReady=false (persistent ≥ minutes)
    ↓
next Meeting cannot start
```

**Distinguishing fact (vs Track A / vs T3 fix):**

```text
transitionState=IDLE · terminalReady=true   // T3 timeout line CLOSED for this observation
meshMissingIce + bootstrapAttemptCount=0    // Track C failure surface
```

**Field log anchors (M01 `HTUBB21B09220661`, pid epoch 28184):**

| Time | Marker |
|------|--------|
| 17:32:41 | `GATE_DECISION op=MEETING_JOIN result=ALLOW` · `TRANSITION_BEGIN id=T4 trigger=MEETING_START` |
| 17:32:41 | `TRANSITION_PREDICATE_EVAL … reason=declaration_not_frozen declarationFrozen=false` |
| 17:32:45 | `MEETING_LEAVE_UI_ACCEPTED` · `LOCAL_HANGUP` · `Conference channel released for GROUP PTT` |
| 17:32:45 | `MEETING_END_BEGIN` · `CONVERGENCE_WINDOW_BEGIN baselineMembers=M01,M02,M03` |
| 17:32:45+ | `meshMissingIce=M02,M03` · `bootstrapAttemptCount=0` · `transmitReady=false` |
| 17:34+ | `transitionState=IDLE` · `channelReadiness=CONNECTING` (still) |

---

## Review questions (C1–C3)

Sign-off required before any implementation IA.

### C1 — Normal hangup recovery

**Question:** After a **clean** Meeting that completes normally (peers joined, host hangup without abort), does GROUP automatically return to `transmitReady=true` within product-acceptable time?

| Outcome | Meaning |
|---------|---------|
| **YES** | Bug is **abort-path only**; narrow IA to aborted transition |
| **NO** | Broader GROUP recovery defect; Case A alone may authorize IA |
| **INCONCLUSIVE** | Extend observation window / repeat on clean roster |

**Decision:** `________`

**Evidence:** `________`

---

### C2 — Abort-path stability

**Question:** When GROUP is **explicitly NOT READY** (`declaration_not_frozen`) and host starts Meeting then **hangs up before transition completes**, does the system **stably** land in `bootstrapAttemptCount=0` + persistent `meshMissingIce` + `transmitReady=false`?

| Outcome | Meaning |
|---------|---------|
| **YES (stable repro)** | Define bug: **aborted GROUP→CONFERENCE transition leaves GROUP media recovery unbootstrapped** |
| **NO** | 17:32 may be compounded state; refine repro |
| **INTERMITTENT** | Capture N≥3 on clean devices before IA |

**Decision:** `________`

**Evidence:** `________`

---

### C3 — Responsibility point

**Question:** Where should recovery be owned?

| Candidate | Hypothesis |
|-----------|------------|
| **Conference → GROUP transition** | Release ordering leaves mesh planner without bootstrap trigger |
| **Mesh bootstrap** | `bootstrapAttemptCount=0` — bootstrap never scheduled after release |
| **Channel readiness predicate** | Readiness stuck CONNECTING despite recoverable mesh facts |

**Decision (primary owner):** `________`

**Rationale:** `________`

**Explicitly out of scope for C3 routing:** PNSRD mutex · GCT async · lease · B1 · hangup barrier extension · force-release · PC reset

---

## Minimal repro (clean field — two cases only)

**Do not** use devices with prior multi-run field pollution without clean restart below.

### Clean preflight (both cases)

```text
M04 = powered off
Roster = M01 + M02 + M03 only
M01 = host (inst or prod OK for Track C — PNSRD_INST not required)
M02/M03 = online · com.talkback.appprod

Clean restart (allowed for repro — not during R3 hang capture):
  force-stop Talkback on M01, M02, M03
  relaunch · wait on Talk page

M01 log capture:
  adb -s HTUBB21B09220661 logcat -v threadtime -s Talkback:I
```

**Pass markers for “GROUP ready” before Case A/B Meeting click:**

```text
transmitReady=true
  OR meshIceConnectedPeers includes M02,M03
  AND NOT persistent meshMissingIce=M02,M03 with bootstrapAttemptCount=0 > 2 min
```

---

### Case A — Normal completion path

```text
1. Confirm GROUP ready (markers above)
2. M01 start Meeting · M02 ACCEPT · (optional M03 ACCEPT)
3. Let session run ≥ 30s (media need not show LIVE pill — Track B)
4. M01 normal hangup
5. Wait 2 min — do not force-stop
6. Observe GROUP recovery markers
7. Attempt second Meeting
```

**Record:**

| Check | Result |
|-------|--------|
| Post-hangup `transmitReady` | true / false @ T+30s / T+2m |
| `bootstrapAttemptCount` after hangup | |
| `meshMissingIce` after hangup | |
| Second Meeting admission | success / blocked |
| `declaration_not_frozen` on retry | yes / no |

---

### Case B — Abort-path (primary suspect)

```text
1. Clean preflight
2. If GROUP already ready, optionally wait for cold NOT READY window
   OR proceed when log shows declaration_not_frozen on first MEETING_START attempt
3. M01 click Meeting while GROUP NOT READY
4. Hangup immediately (≤ 5s) — before transition predicate satisfied
5. Wait 2 min
6. Observe meshMissingIce · bootstrapAttemptCount · transmitReady
7. Attempt second Meeting
```

**Record:** same table as Case A.

**Bug definition (only if C2 = YES):**

> Aborted GROUP→CONFERENCE transition leaves GROUP media recovery unbootstrapped.

---

## Adjudication outcomes (review only)

| ID | Condition | Verdict | Next |
|----|-----------|---------|------|
| **C-1** | Case A fails (normal hangup does not restore GROUP) | **BROAD RECOVERY BUG** | Minimal fix IA · separate from Track A |
| **C-2** | Case B stable · Case A passes | **ABORT-PATH BUG** | Narrow IA · repro card + owner from C3 |
| **C-3** | Neither case stable on clean devices | **INCONCLUSIVE** | More clean captures · no code |
| **C-4** | Case A passes · Case B passes | **NOT REPRODUCED** | Close Track C at review level · retain 17:32 as anecdote |

Write results to: `talkback/logs/group-post-hangup-recovery-<YYYYMMDD-HHMMSS>/ADJUDICATION.txt`

---

## Explicit non-scope

```text
Primary Native SRD / mutex / R3 capture procedure
Track B MESH LIVE / L4 / Resolver
GCT async realization
Lease semantics · B1 recovery intent
Hangup barrier extension
Force-release · PC reset · Factory partition
Inst AAR / PNSRD_INST telemetry changes
```

Track C field work **must not** block Track A R3. Schedule Case A/B on a **separate clean session** or after R3 capture.

---

## Triple-track status (frozen 2026-08-26)

```text
Track A — Primary Native SRD
  R3 = NOT CAPTURED
  mutex closure = OPEN
  native fix = BLOCKED (Outcome 1)

Track B — MESH LIVE
  CLOSED · Q1=NO · no amendment

Track C — Post-Hangup GROUP Recovery
  classification = PRODUCT BUG
  C1/C2/C3 = OPEN (this review)
  Case A/B = NOT RUN (clean)
  implementation = NOT AUTHORIZED
```

---

## Related artifacts

| Doc | Role |
|-----|------|
| [conference-mesh-live-presentation-contract-review-20260826.md](./conference-mesh-live-presentation-contract-review-20260826.md) | Track B CLOSED · UI non-authoritative |
| [primary-native-srd-fault-production-hang-stack-capture-runbook-20260825.md](./primary-native-srd-fault-production-hang-stack-capture-runbook-20260825.md) | Track A R3 §4a (do not merge Track C fix here) |
| [media-engine-ownership-hangup-gate.md](../architecture/media-engine-ownership-hangup-gate.md) | Hangup / GROUP release architecture |
| ADR-0027 | MEETING_END · bootstrap convergence vocabulary |
