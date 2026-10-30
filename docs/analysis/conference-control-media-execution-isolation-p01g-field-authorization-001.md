# P0.1g Field Authorization-001

**ID:** conference-control-media-execution-isolation-p01g-field-authorization-001  
**Date:** 2026-08-21  
**Type:** Field Authorization · Bounded  
**Parent:** [conference-control-media-execution-isolation.md](./conference-control-media-execution-isolation.md)

**Prerequisites (all PASS):**

| Gate | Status | Evidence |
|------|--------|----------|
| Decision Note Option B | ACCEPTED | [conference-initial-anchor-semantics-decision-note.md](./conference-initial-anchor-semantics-decision-note.md) |
| IA-001 implementation | PASS | IA-1 / IA-2 / IA-3 |
| Desk 4p baseline | PASS | `logs/conference-initial-anchor-desk-4p-20260821-102308/` · session `c92153d5-2b63-4147-aadc-556d45360d29` |

---

## Status

```text
Conference Initial Anchor Semantics     ACCEPTED — Option B
IA-001 (IA-1 / IA-2 / IA-3)             PASS
Desk 4p baseline                        PASS
P0.1g SRD native blocking isolation     AUTHORIZED — field only
```

**Implementation:** already authorized (P0.1c / P0.1d / mutex / SRD observation — no new product behavior in this authorization)

**Field:** **allowed**

**Next:** Acceptance 1 execution

---

## Authorization goal (narrow)

Answer one question only:

> With Anchor fixed at M01, does **one edge's** SRD native blocking contaminate **other edges'** control / media execution?

**Not the goal:**

> Fix M03 SRD hang

If M03 ends in `SRD_TIMEOUT` / `EDGE_FAILED` but M02 and M04 complete normally, P0.1g is **PASS**.

---

## Out of scope (do not re-verify or patch in this run)

```text
Anchor election
Initial Anchor policy
Admission semantics
Ready
Recovery
Completion predicate
Audio policy
Topology / failover
```

---

## Authorized scope

### 1. Fixed topology (from baseline PASS)

```text
HOST=M01
INITIAL_ANCHOR=M01
CURRENT_ANCHOR=M01
```

Pre-run gate: if `anchor != M01` → **INVALID RUN** (stop; do not analyze SRD)

### 2. SRD isolation scenario

Star edges from M01:

```text
M01 → M02
M01 → M03
M01 → M04
```

### 3. Reuse existing mechanisms (no new impl)

```text
sdpApplyMutex
SRD observation (SRD_ENTER / SRD BEFORE / SRD AFTER / SRD_TIMEOUT)
P0.1d timeout
CONFERENCE_MEDIA_EDGE_FAILED attribution
```

---

## Required acceptance

### Acceptance 1 — single-edge SRD block isolation

**Inject:** M01→M03 — `SRD_ENTER` → native block (fault injection)

**Must observe:**

```text
M01→M02   MEMBER_ACCEPTED · SRD SUCCESS · ICE CONNECTED
M01→M04   MEMBER_ACCEPTED · SRD SUCCESS · ICE CONNECTED
M01→M03   SRD_TIMEOUT · EDGE_FAILED
```

**Pass interpretation:** M03 failure is **local to that edge**; M02/M04 unaffected.

---

### Acceptance 2 — no topology contamination

**Must NOT appear** in this run:

```text
MESH_TO_ANCHOR          (after baseline anchor established)
ANCHOR_FAILOVER
RECOVERY_REALIZATION
```

P0.1g does not test topology or recovery evolution.

---

### Acceptance 3 — failure attribution

M03 failure must remain:

```text
CONFERENCE_MEDIA_EDGE_FAILED
reason=SRD_TIMEOUT
```

**Forbidden mis-attribution:**

```text
CALL_REJECT
PENDING_TIMEOUT
NOT_ONLINE
RECOVERY_TRIGGER
```

---

## Stop conditions

| Observation | Action |
|-------------|--------|
| `anchor != M01` | **INVALID RUN** — do not analyze SRD |
| Admission pending / stuck | **INVALID RUN** — do not analyze media |
| Ready anomaly | Stop — **no fix in this run** |
| Recovery intervenes | Stop — re-domain |
| M03 `SRD_TIMEOUT`, M02/M04 OK | **P0.1g PASS** → RCA for M03 edge only (separate track) |
| M02 or M04 blocked by M03 SRD | **P0.1g FAIL** — continue native isolation work |

---

## Pre-run checklist

```text
[ ] APK includes IA-1/2 + P0.1c/d SRD path
[ ] No stale conference / GROUP / recovering state
[ ] conferenceSessionCount=0 before start
[ ] M01 creates conference; 4 members; baseline gate on M01 log:
      ANCHOR_DECISION phase=CREATE admitted=M01 reason=INITIAL_ANCHOR_POLICY
      CONFERENCE_TOPOLOGY_PUBLISHED anchor=M01
[ ] Then inject M01→M03 SRD block only
```

---

## Evidence capture

Suggested directory:

```text
talkback/logs/p01g-srd-mutex-<YYYYMMDD-HHMMSS>/
```

Minimum: M01 log (anchor gate + all three edges SRD/ICE); corroboration from M02/M04 if USB allows.

Adjudication: isolation outcome + Acceptance 2/3 guards — **not** “M03 SRD fixed”.

---

## Posture after authorization

```text
WiFi recovery main chain:     CLOSED — do not attach
Initial Anchor / IA-001:      CLOSED — do not reopen on P0.1g field
P0.1g field:                  AUTHORIZED
P0.1g PASS:                   closes native blocking isolation question for this scenario
P0.1g FAIL (cross-edge):      route to P0.1c/e native isolation — not Recovery / Anchor / Ready
```

---

## State diagram (frozen)

```text
Conference Initial Anchor Semantics
        |
        v
ACCEPTED — Option B

Implementation Authorization IA-001
        |
        v
PASS
  IA-1 InitialAnchorPolicy
  IA-2 ANCHOR_DECISION
  IA-3 baseline gate

Desk 4p baseline
        |
        v
PASS

P0.1g SRD native blocking isolation
        |
        v
FIELD AUTHORIZED  ← this document
        |
        v
Acceptance 1 execution (next)
```
