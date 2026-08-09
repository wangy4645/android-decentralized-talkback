# Successor Recovery Pending — Observation

**Status:** **OBSERVATION ACTIVE** · Q1–Q3 **TRACE COMPLETE** · Q4–Q5 **OPEN** · **no ADR** · **no fix** · **no runtime authorization**  
**Date:** 2026-08-09  
**Classification:** Recovery **obligation lifecycle completeness** observation  
**Naming:** Successor Recovery Pending Residency (observation language — **not** “successor recovery bug”)  
**Not:** ADR-0045 regression · ADR-0044 presentation defect · residency clear gap · WiFi Recovery reopen

**Evidence:** `logs/adr0045-field-20260809-094259/` · session `a73272cb-74c2-4fd6-bf50-918a66806df1` · observer **M02** → peer **M03** · pid `30507`

**Parents / boundaries:**

- [ADR-0045](../adr/0045-post-obligation-failed-media-residency-clear-admission.md) — residency clear; **does not absorb this track**
- [ADR-0044](../adr/0044-user-visible-connectivity-semantics-media-residency.md) — presentation; SYNCING here follows recovering, not DEGRADED
- [ADR-0038](../adr/0038-recovery-completion-admission-contract.md) — completion success (orthogonal; do not reopen casually)
- WiFi Recovery Architecture — **CLOSED** (observation only)

---

## Status board

```text
Checkpoint (2026-08-09) — healthy fork

ADR-0044 Presentation              CLOSED ✅

ADR-0045 Failed Media Clear        ACCEPTED / IMPLEMENTED PARTIAL ✅
  Policy                           PASS ✅
  Phase 1                          MERGED ✅
  Phase 2.0 Field                  partial evidence
  Phase 2.1 Field                  PAUSED ⏸️
  FAILED_MEDIA Q1–Q7               CLOSED ✅

Field #1 (20260809-093047)
  M03→M02 clear                    PASS
  M02→M03 DEGRADED                 Trigger gap (FAILED_MEDIA + closed; ADR-0045)

Field #2 (20260809-094259)
  M03→M02 clear                    PASS (ADR-0045 still holds)
  M02→M03 SYNCING                  THIS TRACK
  Domain                           Recovery obligation lifecycle completeness
  ADR-0045 ClearPolicy             correctly NOT invoked (no GATE)

Successor Recovery Pending
  Observation                      OPEN / ACTIVE ✅
  Q1 Phase owner                   COMPLETE ✅
  Q2 Obligation close              COMPLETE ✅
  Q3 SYNCING semantics             COMPLETE ✅
  Q4 Lifecycle contract            OPEN
  Q5 Close authority               OPEN
  Runtime change                   NONE AUTHORIZED
  ADR                              NONE

Forbidden while this track is open:
  ✗ modify ADR-0045 / add residency clear triggers
  ✗ change SYNCING copy / map RECOVERY_PENDING → DEGRADED
  ✗ ICE_CONNECTED auto-ends obligation / writes phase=CONNECTED
  ✗ UVCP hide SYNCING / ignore obligationOpen
  ✗ resurrect WiFi Recovery Architecture
  ✗ force FAILED_MEDIA Field to “finish” Phase 2.1
```

---

## Observation (Field #2)

```text
mediaUnavailable=false
ice=CONNECTED
receivePathLive=true
phase=RECOVERY_PENDING
obligationOpen=true
recovering=true
finalPresence=SYNCING
```

UI 「一直 Sync」与权威一致：仍有 **open recovery obligation** → ADR-0044 投向 SYNCING，**不是** sticky DEGRADED。

### Why not ADR-0045

ADR-0045 GATE requires:

```text
phase == FAILED_MEDIA_RECOVERY
+ obligationClosed
+ snapshot E4
```

Field #2:

```text
phase = RECOVERY_PENDING
obligationOpen = true
FAILED_MEDIA_RECOVERY count (M02 pid 30507 → M03) = 0
```

```text
RecoveryResidencyClearPolicy
        ^
        |
        X   (no GATE)
```

Clear admission **not involved**. Phase 2.1 entry trigger **not exercised**.

---

## Evidence chain (M02 → M03)

```text
09:45:11  ICE_DISCONNECTED → attempt1 (owner=M03; M02 NON_OWNER_BLOCKED)
09:45:23  attempt1 → RECOVERED (obligation closed)
09:45:24  late REATTACH_INBOUND → SUPERSEDE attempt2
          ICE_RESTART_DISPATCHED · DELIVERY_CONFIRMED
          Completion WAITING (DELIVERY_PENDING → then successor)
09:45:26  ADMIT_SUCCESSOR (evidenceKind=REMOTE_MODULE_RECOVERED)
          obligationGen=2 attempt=3 → RECOVERY_PENDING
          negotiation owner=M03; DEFER_ADMISSION
…
09:47:xx  rprobe: RECOVERY_PENDING · obligationOpen=true · SYNCING
          ice=CONNECTED · receivePathLive=true · mediaUnavailable=false
09:48:06  MEMBERSHIP_LEFT / USER_LEAVE (obligation closed by leave — not completion)
```

Markers to keep:

| Marker | Role |
|--------|------|
| `RECOVERY_EDGE_RECOVERED` / `reason=RECOVERED` | attempt1 terminal |
| `RECOVERY_ATTEMPT_SUPERSEDED` / `REATTACH_INBOUND` | post-RECOVERED late reattach |
| `ADMIT_SUCCESSOR_OBLIGATION_EPISODE` | new gen; leaves `RECOVERY_PENDING` |
| `NEGOTIATION_NON_OWNER_BLOCKED` / `DEFER_ADMISSION` | local cannot drive media action |
| rprobe `finalPresence=SYNCING` + `obligationOpen=true` | presentation follows recovering |

---

## Scope

```text
Goal:
  understand RECOVERY_PENDING lifetime after successor admission

In scope:
  phase ownership of RECOVERY_PENDING
  what closes obligationOpen after ADMIT_SUCCESSOR
  why healthy media still yields SYNCING (recovering / successor pending)

Not in scope:
  ADR-0045 residency clear / Phase 2.1 field
  ADR-0044 EndpointStatus remapping
  presentation “bugfix”
  forcing FAILED_MEDIA to mix variables
  WiFi matrix / Directed #5
  casual ADR-0038 / membership reopen
```

---

## Q1–Q3 TRACE (observation only — no fix)

### Q1 — Phase owner of `RECOVERY_PENDING`

**Answer (trace):**

```text
Authority family: ConferenceEdgeRecoveryController (Recovery)
Writer of successor phase:
  admitSuccessorObligationEpisode()
    → openNewRecoveryObligation(phase=RECOVERY_PENDING)
```

| Role | Field #2 (M02→M03) | Owner |
|------|-------------------|--------|
| Successor admit writer | `ADMIT_SUCCESSOR_OBLIGATION_EPISODE` | Controller / Recovery |
| Phase mutation | `RECOVERY_PENDING` via `openNewRecoveryObligation` | Controller |
| Negotiation owner | `selectedOwner=M03` (local=M02) | Negotiation owner resolve |
| Media-action claim | `HOST_RESTART` then `NEGOTIATION_SETTLING` defer | Media action + gate |
| Attempt clock | **no** `RECOVERY_WATCHDOG_STARTED` for attempt=3 | **absent** after defer |

**Who may leave `RECOVERY_PENDING` (code paths):**

```text
markRecovered()           → RECOVERED          (CompletionPolicy; needs open obligation + canClose)
enterFailedMediaResidency → FAILED_MEDIA_*     (typically watchdog ATTEMPT_TIMEOUT)
supersedeAttempt / ADMIT_SUCCESSOR → replace attempt/gen (still recovering until terminal)
cancelEdge / MEMBERSHIP_LEFT / leave → close + remove
```

There is **no** “ICE already CONNECTED ⇒ leave RECOVERY_PENDING” writer. ICE alone is not phase owner.

**Field nuance (precondition for successor):**

```text
attempt1 RECOVERED (obligationClosed)
  → late REATTACH SUPERSEDE attempt2 (phase→ICE_RESTARTING; closed stamp preserved)
  → REMOTE_MODULE_RECOVERED evidence while phase≠RECOVERED ∧ obligationClosed
  → ADMIT_SUCCESSOR replaces edge (gen+1, RECOVERY_PENDING, obligation reopened)
```

`isFreshRemoteModuleRecoveredEvidence` explicitly rejects `phase==RECOVERED`, but accepts mid-flight `ICE_RESTARTING` if the prior close stamp remains — Field #2 hit that seam.

---

### Q2 — What closes `obligationOpen` on successor path?

**Answer (trace):**

```text
closeObligation / markRecovered are CompletionPolicy writers.
OBLIGATION_DEADLINE is failed-media residency path — NOT the successor default.
```

| Terminal candidate | Required machinery | Field #2 |
|--------------------|--------------------|----------|
| Completion → `RECOVERED` | `markRecovered` after completion eval (ICE alone insufficient; control/delivery facts apply) | **never** |
| Attempt timeout → `FAILED_MEDIA_*` | `scheduleWatchdog` → `ATTEMPT_TIMEOUT` | **never** (attempt=3 watchdog count = 0) |
| Leave / cancel | `MEMBERSHIP_LEFT` / session cancel | **only close observed** (09:48:06 USER_LEAVE) |

**Why attempt2/3 never timed out to FAILED_MEDIA:**

```text
admitSuccessor (ICE_RESTART_ONLY path):
  resolveMediaActionOwner → issueBoundedIceRestart
  gate blocked OFFER_AWAITING_ANSWER
  → DEFER (INV-NEG-004: phase/watchdog unchanged)
  → return without scheduleWatchdog

Comment at admitSuccessor:
  "watchdog only after dispatch; deferred must not (INV-REC-023)"
```

Field:

```text
09:45:26  ICE_RESTART_GATE_BLOCKED … OFFER_AWAITING_ANSWER
09:45:26  RECOVERY_MEDIA_ACTION_DEFERRED … NEGOTIATION_SETTLING
09:45:36  intent STALE_DISCARD / NEGOTIATION_BUDGET_EXHAUSTED
          (no WAKEUP drain → no new dispatch → no watchdog)
```

So successor obligation stayed open because **neither completion nor attempt-timeout terminal ran**; ICE/receivePath healthy did **not** close it.

---

### Q3 — Why `finalPresence=SYNCING`?

**Answer (trace): projection is consistent — classify as stuck active recovery, not UVCP bug.**

```text
UserVisibleConnectivityProjection.deriveAxes:
  recovering || controlSyncPending → ControlSyncState.SYNCING
  MEDIA_OK + SYNCING → UserVisibleConnectivityState.SYNCING  (= ONLINE? no)
```

```text
recovering ⇐ isEdgeRecovering ⇐ phase.isActivelyRecovering()
RECOVERY_PENDING ∈ isActivelyRecovering()
mediaUnavailable=false ∧ receivePathLive=true → MEDIA_OK
```

| Option | Meaning | Field #2 |
|--------|---------|----------|
| **A** active recovery | `recovering=true` by phase contract | **true** (phase still `RECOVERY_PENDING`) |
| **B** stuck recovery | no terminal after defer; obligation never converges | **also true** (Q2) |

UI identity is the same; architecture meaning: **B under A** — Sync is the correct projection of an obligation that never left active recovering. Not ADR-0044 miss; not ADR-0045 (GATE never held).

---

## Trace verdict (still observation)

```text
Exposed layer:
  Recovery obligation lifecycle completeness
  (not media residency clear; not presentation)

Core question (refined):
  Does a successor recovery obligation under NEGOTIATION_SETTLING
  possess a final convergence / terminal-admission mechanism?

Field evidence shape:
  RECOVERY_PENDING
    + success / failed-media / supersede / leave   ← exist in FSM
    − timeout / admission expiry after defer       ← missing in Field #2 path

Not:
  "Why did residency clear fail?"
  "Why is UVCP wrong?"
```

```text
Provenance hotspot (not a fix authorization):
  ADMIT_SUCCESSOR + ICE_RESTART defer without attempt watchdog
  → RECOVERY_PENDING can persist while media plane is healthy
  → SYNCING until leave/cancel
```

```text
Quadrants (do not mix):
  ADR-0044  mediaUnavailable ∧ !recovering → DEGRADED
  Field #2  mediaAvailable ∧ recovering     → SYNCING  (correct)
  ADR-0045  FAILED_MEDIA ∧ obligationClosed ∧ E4 → clear (GATE absent here)
```

---

## Next (observation-only; not yet authorized as a grill)

### Q4 — Successor obligation lifecycle contract

> What is the lifecycle contract for a successor obligation?

Answer **only** whether each state **must** have an exit (yes/no/conditional).  
**Do not** discuss timeout numeric budgets.

| State | Must have an exit? |
| ----- | ------------------ |
| `RECOVERY_PENDING` | ? |
| `NEGOTIATION_SETTLING` (deferred media action) | ? |
| `OFFER_AWAITING_ANSWER` (ICE restart gate block) | ? |

### Q5 — Close authority for successor obligation

> Who owns closing a successor obligation?

Candidates (pick / refine; **do not** add watchdog yet):

```text
Recovery Controller
RecoveryCompletionPolicy
Successor Admission Policy (new? or existing family)
```

---

## Discipline

```text
PASS criteria for this observation track:
  Q1–Q3 answered with code+log provenance     ✅
  boundary held: not absorbed by ADR-0045/0044 ✅
  Q4–Q5 only if observation continues         OPEN

FAIL / out of process:
  “fix Sync by clear residency”
  “Phase 2.1 field until FAILED_MEDIA”
  reopen WiFi Recovery Architecture on single sticky Sync
  UVCP hide SYNCING / force CONNECTED / ignore obligationOpen
  ICE_CONNECTED auto phase=CONNECTED / auto close obligation
  map RECOVERY_PENDING → DEGRADED
```

---

## References

- Field #2 logs: `talkback/logs/adr0045-field-20260809-094259/`
- Field #1 (ADR-0045 trigger gap): `talkback/logs/adr0045-field-20260809-093047/`
- ADR-0045 run card: [adr0045-field-run-card.md](./adr0045-field-run-card.md)
- Related (independent): ADR-0039 owner-conflict track — **not triggered** by this note alone
