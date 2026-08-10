# ADR-0042 INV-T3-SCHEDULE — Architect Acceptance Review

**ID:** adr0042-inv-t3-schedule-acceptance-review-001  
**Date:** 2026-08-10  
**Type:** ACCEPTANCE REVIEW · CONTRACT FREEZE  
**Subject:** `adr0042-inv-t3-schedule-amendment-draft-001.md`  
**Production mutation:** **NOT AUTHORIZED**

---

## Verdict

```text
Step 3C Architect Acceptance Review     ACCEPT

Core direction:
  Fix progress guarantee, NOT delivery guarantee

INV-T3-SCHEDULE normative text          FROZEN (§3.2 of amendment doc)
Non-goals                               FROZEN
Step 4 Implementation Candidate prep    AUTHORIZED (submission doc only)
Production code                         NOT AUTHORIZED
```

---

## 1. Core direction — ACCEPT

The amendment correctly bounds scope:

```text
SEND_FAILED
    ↓
MUST establish bounded progress
    ↓
NOT MUST deliver successfully
```

**Rejected misread:**

```text
SEND_FAILED → retry → must succeed
```

That would invade Transport responsibility, ICE behavior, and signaling reliability — unsupported by field evidence.

---

## 2. Frozen normative text

Authoritative copy lives in amendment doc §3.2. Summary:

```text
INV-T3-SCHEDULE

For a recovery reattach obligation:

When the Recovery owner observes SEND_FAILED,
it MUST establish a bounded progress window
owned by the recovery episode.

Within this progress window:

- Recovery MAY wait for capability restoration.
- Recovery MAY be accelerated by external events.
- Recovery MUST retain an obligation-owned path
  to attempt redispatch before terminal disposition.

Failure to deliver successfully does not violate this invariant.

Failure to establish bounded progress does violate this invariant.
```

---

## 3. Three boundaries — FROZEN

### Boundary 1 — Retry owner

| | |
|---|---|
| **Accept** | `ConferenceEdgeRecoveryController` owns progress window, schedule complement, terminal disposition |
| **Reject** | `TalkbackCoordinator` retry queue / lifecycle ownership |

### Boundary 2 — Trigger model

| | |
|---|---|
| **Accept** | External events **accelerate**; recovery schedule **guarantees** progress path |
| **Reject** | `DIGEST_REFRESH` / `ROUTE_CONVERGED` / `ICE_CHECKING` as **sole** retry trigger |

EP04: absence of these events must not imply indefinite wait.

### Boundary 3 — Failure classification

| Term | Meaning |
|------|---------|
| **`PROGRESS_WINDOW_EXPIRED`** | Bounded progress was established; delivery not achieved before terminal policy |
| **`DELIVERY_FAILED`** | Transport-level send failure for a dispatch attempt |

**Banned:** `retry failed` (conflates progress with delivery).

---

## 4. Acceptance gates

| Gate | Focus | Result |
|------|-------|--------|
| G1 | Ownership | **PASS** |
| G2 | Thread / scope isolation | **PASS** |
| G3 | Lifecycle facts | **PASS** |
| G4 | Progress oracle | **CRITERIA FROZEN** · draft **DEFERRED** to Step 4 |
| G5 | Regression boundary | **PASS** |

### G4 criteria (frozen, not drafted)

When oracle is written in Step 4, it MUST assert:

```text
SEND_FAILED
  → progress window created
  → retry opportunity exists (when gates allow)
  → terminal disposition explicit
```

It MUST NOT use WiFi flap recovery **success rate** as pass condition.

**Rationale:** Oracle obeys contract; contract does not obey oracle.

---

## 5. WiFi investigation — final label

```text
Trigger:           WiFi interruption
Amplifier:         fan-out recovery concurrency
Failure mechanism: SEND_FAILED after reattach dispatch
Missing guarantee: obligation-owned bounded progress
Candidate fix:     INV-T3-SCHEDULE
```

---

## 6. Authorized next step

```text
adr0042-inv-t3-implementation-submission-001.md    SUBMITTED
adr0042-inv-t3-ia-decision-memo-001.md              ACCEPT (boundary)
adr0042-inv-t3-implementation-plan-001.md           SUBMITTED
adr0042-inv-t3-diff-inventory-001.md                gate PASS WITH CONSTRAINTS
adr0042-inv-t3-diff-gate-decision-memo-001.md       COMPLETE
src/main mutation                                   AUTHORIZED (bounded)
```

---

## 7. Explicitly not authorized

```text
Kotlin / runtime changes
G4 test oracle draft (deferred)
RLA / RFA additional soak
ADR-0049 rollback
ADR-0042 §4 merge (pending submission)
```

---

## References

| Doc | Role |
|-----|------|
| `adr0042-inv-t3-schedule-amendment-draft-001.md` | Accepted amendment |
| `recovery-reattach-liveness-schedule-design-001.md` | Step 3B design |
| `recovery-fan-out-reattach-convergence-window-c1-001.md` | EP04/EP05 chain |
