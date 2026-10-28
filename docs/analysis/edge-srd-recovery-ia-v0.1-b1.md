# Edge SRD Recovery IA v0.1 — B1 Control Plane

**ID:** edge-srd-recovery-ia-v0.1-b1  
**Date:** 2026-08-25  
**Version:** v0.1  
**Type:** Implementation Authorization  
**Status:** **AUTHORIZED (B1 only)** · B2 **NOT AUTHORIZED** · field **NOT AUTHORIZED**

**Wave:** B1 — Edge failure + recovery intent (control plane only)  
**Out of wave:** B2 safe re-realization · C domain reset · D quarantine policy · any native/runtime recovery

**Prerequisites (frozen):**

| Gate | Artifact |
|------|----------|
| Primary defect | [gct-ia001-clean-start-adjudication-20260825.md](./gct-ia001-clean-start-adjudication-20260825.md) |
| Architecture decision | [native-srd-recovery-decision-review-20260825.md](./native-srd-recovery-decision-review-20260825.md) — B selected |
| Failure taxonomy | [conference-failure-domain-contract.md](./conference-failure-domain-contract.md) |
| Phase A projection | [conference-native-failure-containment-phase-a-authorization.md](./conference-native-failure-containment-phase-a-authorization.md) — Scenario E + D |
| Execution boundary | [coordinator-media-execution-boundary.md](../architecture/coordinator-media-execution-boundary.md) |

```text
Decision Review (B1/B2 split)
        ↓
This IA v0.1 — B1 ONLY     ← authorized here
        ↓
Implementation PR(s)
        ↓
Desk / unit gate
        ↓
Field (separate run card — NOT authorized by this IA)
        ↓
B2 IA (future — blocked until B1 pass)
```

---

## Authorization statement (single sentence)

> **Authorize implementation of B1 only:** persist a per-edge **RecoveryIntent** control-plane state machine, triggered exclusively from existing Scenario E **`EDGE_FAILED`** facts on the failure edge, that waits for safe native admission signals without clearing lease, retrying SRD, resetting native runtime, or upgrading recovery intent into Conference availability decisions.

**No authorization for:** immediate SRD retry · re-realization · force-release · PC/domain reset · Factory partition · D-β/γ · B2.

---

## Goal (narrow)

When **Shared Native SRD Fault** completes Phase A classification:

```text
SRD native hang (failure edge)
    ↓
EDGE_FAILED (Scenario E — existing)
    ↓
RecoveryIntent(edgeKey)
    ↓
WAITING_FOR_SAFE_ADMISSION
```

**Not the goal:**

```text
EDGE_FAILED → immediate re-SRD → re-enter shared-factory lease
EDGE_FAILED → fault edge media restored in same session slice
Conference DEGRADED / availability decided by recovery intent alone
Fix Shared Native SRD Fault at JNI layer
```

B1 success = **control-plane correctness under fault**, not **media recovery**.

---

## Target flow (normative)

```text
                    ┌─────────────────────────────────────┐
                    │  Existing Phase A path (unchanged)   │
                    │  SRD_TIMEOUT / watchdog → classify   │
                    │  → CONFERENCE_FAILURE_L1 EDGE_FAILED│
                    └─────────────────┬───────────────────┘
                                      │
                                      ▼
                    ┌─────────────────────────────────────┐
                    │  B1: RecoveryIntentService (new)    │
                    │  IDLE → RECOVERY_REQUESTED          │
                    │       → WAITING_FOR_SAFE_ADMISSION  │
                    │  (or TERMINAL_FAILED / SUPPRESSED)  │
                    └─────────────────┬───────────────────┘
                                      │
              reads only ─────────────┼──────────── no writes
                                      ▼
                    ┌─────────────────────────────────────┐
                    │  Existing domain / lease facts      │
                    │  NATIVE_DOMAIN_EXECUTION_ACTIVE     │
                    │  LEASE_HELD · DOMAIN_BLOCKED (L2)   │
                    │  EngineOwnershipGate · admission      │
                    └─────────────────────────────────────┘
                                      │
                                      ▼
                              (B2 — NOT IN v0.1)
                         safe re-realization when idle
```

---

## AUTH-B1-1 — RecoveryIntent record

Each intent MUST bind:

```text
RecoveryIntent {
  conferenceSessionId
  edgeKey
  remoteModuleId
  meshGeneration
  conferenceGeneration          // if edge-scoped in session
  pcGeneration                  // at EDGE_FAILED observation
  offerLineageId                // if present on failure fact
  realizationAttemptId          // if present
  runtimeDomainRef              // e.g. shared-factory
  cause                         // from EDGE_FAILED input tuple
  causeFact                     // e.g. SRD_TIMEOUT · HANGING_OBSERVED
  requestedAtMs
  intentGeneration              // monotonic per edgeKey scope
}
```

**Scope key for idempotency:** `(conferenceSessionId, edgeKey, meshGeneration, conferenceGeneration)` — use the same generation tuple already carried on `CONFERENCE_FAILURE_CAUSE_INPUT` / edge failure facts.

Intent is **control-plane only**. It MUST NOT mutate media engines, PeerConnections, lease holders, or topology.

---

## AUTH-B1-2 — Recovery state machine

Per `(conferenceSessionId, edgeKey)` active intent:

| State | Meaning |
|-------|---------|
| `IDLE` | No active recovery intent for this edge scope |
| `RECOVERY_REQUESTED` | Intent created from EDGE_FAILED; not yet parked on domain wait |
| `WAITING_FOR_SAFE_ADMISSION` | Intent parked; domain/lease facts indicate native admission unsafe or occupied |
| `TERMINAL_FAILED` | Intent closed without recovery path in B1 (terminal for this intent generation) |

**Allowed transitions:**

```text
IDLE
  → RECOVERY_REQUESTED          (on EDGE_FAILED trigger)

RECOVERY_REQUESTED
  → WAITING_FOR_SAFE_ADMISSION  (domain occupied / lease retained / admission blocked)
  → TERMINAL_FAILED             (suppression rule fired)

WAITING_FOR_SAFE_ADMISSION
  → TERMINAL_FAILED             (suppression / session end / explicit terminal)
  → (no B2 transition in v0.1)
```

**Forbidden transitions in v0.1:**

```text
any state → immediate SRD / createOffer / provision / release
WAITING → RECOVERY_REQUESTED    (duplicate intent without new generation)
RECOVERY_* → CONNECTING / PENDING media projection
```

---

## AUTH-B1-3 — Recovery trigger (single source)

Recovery intent MUST be created **only** from the existing Scenario E terminal path:

```text
CONFERENCE_FAILURE_L1_CLASSIFIED terminal=EDGE_FAILED
  on self edge (cause edge)
```

**Acceptable upstream facts (already in Phase A):**

```text
CONFERENCE_MEDIA_EDGE_FAILED reason=SRD_TIMEOUT
SRD_WATCHDOG_GAP → classified EDGE_FAILED
CONFERENCE_FAILURE_CHAIN_VALIDATED scenario=E
```

**Forbidden triggers:**

```text
DOMAIN_BLOCKED on impact edge
UI / roster / topology inference
Coordinator timeout unrelated to L1 self failure
Manual/debug hooks in production path
Duplicate EDGE_FAILED for same generation (must idempotize — see AUTH-B1-5)
```

Impact edges (`DOMAIN_BLOCKED`) MUST NOT receive a B1 recovery intent in v0.1.

---

## AUTH-B1-4 — Safe-admission handoff (read-only)

While in `RECOVERY_REQUESTED` or `WAITING_FOR_SAFE_ADMISSION`, the intent layer MAY **read**:

```text
NATIVE_DOMAIN_EXECUTION_ACTIVE + holderEdgeKey
LEASE_HELD_BY / LEASE_BUSY / LEASE_RETAINED facts
EngineOwnershipGate state (read-only)
Existing admission / DOMAIN_BLOCKED attribution tuple
```

It MUST **NOT**:

```text
force-clear lease
preempt hung native execution
reset PeerConnection / factory / domain
bypass EngineOwnershipGate
schedule SRD_APPLY / createOffer / provisionNew
convert DOMAIN_BLOCKED → PENDING / CONNECTING
```

When domain facts show occupied / retained lease on the failure edge's domain:

```text
RECOVERY_REQUESTED → WAITING_FOR_SAFE_ADMISSION
emit RECOVERY_WAITING_FOR_DOMAIN
```

**`safe admission` is NOT defined in B1.** B1 only **waits**. Definition and action belong to **B2 IA**.

---

## AUTH-B1-5 — Idempotency

At most **one active** recovery intent per scope key:

```text
(conferenceSessionId, edgeKey, meshGeneration, conferenceGeneration)
```

Rules:

```text
Duplicate EDGE_FAILED same scope → no second intent; log RECOVERY_SUPPRESSED reason=DUPLICATE_ACTIVE
Session end / conference teardown → intent → TERMINAL_FAILED reason=SESSION_END
Explicit suppression policy (TBD in impl) → TERMINAL_FAILED reason=POLICY
New meshGeneration / conferenceGeneration → new scope; prior intent terminal
```

Recovery intent MUST NOT block creation of intents on **other** edgeKeys in the same conference.

---

## AUTH-B1-6 — Observability (required)

Minimum log / trace events:

| Event | When |
|-------|------|
| `RECOVERY_REQUESTED` | Intent created from EDGE_FAILED |
| `RECOVERY_WAITING_FOR_DOMAIN` | Enter `WAITING_FOR_SAFE_ADMISSION` |
| `RECOVERY_SUPPRESSED` | Duplicate, policy, or illegal trigger |
| `RECOVERY_TERMINAL` | Enter `TERMINAL_FAILED` with reason |

Each event MUST include:

```text
conferenceSessionId edgeKey remoteModuleId
meshGeneration conferenceGeneration pcGeneration
runtimeDomainRef cause causeFact
intentGeneration recoveryState
offerLineageId realizationAttemptId   (when known)
```

Desk tests MUST assert state transitions without requiring field reproduction of JNI hang.

---

## AUTH-B1-7 — Coordinator liveness

B1 MUST preserve:

```text
Coordinator thread not blocked on hung SRD (existing edge executor model unchanged)
Failure edge does not synchronously re-enter native from recovery layer
Other edges (including DOMAIN_BLOCKED impact edges) not gated on recovery intent completion
Recovery intent is NOT input to Conference availability / DEGRADED adjudication in v0.1
```

Recovery intent storage and transitions run on control-plane paths already used for failure classification / projection — not on edge executor / native threads.

---

## Explicitly NOT authorized (v0.1)

```text
B2 safe re-realization
C native execution domain reset
D domain quarantine implementation (unless already read-only fact exists — no new policy)
force-release / lease force-clear
PC hard reset / per-PC recovery
native reset / Factory partition / D-β / D-γ
immediate SRD retry / rollback
bypass EngineOwnershipGate
mask DOMAIN_BLOCKED as PENDING
recovery intent → Conference availability decision
lifecycle de-dup · double createOffer · pending drain optimization
SRD timeout budget change
Phase A E+D regression · L3 DOMAIN_FAILED · topology changes
field capture (separate run card)
```

---

## Relationship to Phase A (no regression)

| Phase A (frozen) | B1 relationship |
|------------------|-----------------|
| Scenario E `EDGE_FAILED` | **Trigger only** — classification unchanged |
| Scenario D `DOMAIN_BLOCKED` | Unchanged; impact edges **no** B1 intent |
| L-participant projection | Unchanged; recovery state **not** projected as CONNECTING |
| Cause → impact telemetry | B1 adds parallel recovery telemetry; does not replace |

**Forbidden:** Using recovery intent to hide `VISIBLE_EDGE_FAILED` or `VISIBLE_DOMAIN_BLOCKED`.

---

## Pass criteria (B1 — desk + targeted replay)

**Not:** failure edge media reconnects in session.

**Yes:**

```text
1. Failure edge EDGE_FAILED
       → exactly one RECOVERY_REQUESTED for scope

2. Coordinator continues processing (heartbeat / other edges / classification)

3. Failure edge does NOT synchronously re-enter native SRD from recovery layer

4. Domain occupied / lease retained
       → RECOVERY_WAITING_FOR_DOMAIN
       → state WAITING_FOR_SAFE_ADMISSION

5. Impact edge DOMAIN_BLOCKED (e.g. M04 when M02 hangs)
       → no recovery intent on impact edge
       → M02/M04 not blocked on recovery intent mutex

6. Duplicate EDGE_FAILED same generation
       → RECOVERY_SUPPRESSED · no duplicate active intent

7. Session end
       → RECOVERY_TERMINAL
```

Reference replay (expected shapes, not necessarily re-run for desk pass):

| Run | Failure edge | 1st SRD | 2nd SRD | Impact |
|-----|--------------|---------|---------|--------|
| Clean-start | M02 | M03 PASS | M02 HANG | M04 DOMAIN_BLOCKED |
| IA-001 dirty | M03 | M02 PASS | M03 HANG | M04 DOMAIN_BLOCKED |

---

## Implementation boundary (suggested seam)

```text
Control Plane
  ConferenceFailureClassifier / projection  (existing)
  RecoveryIntentService                     (new — B1)
        ↑ subscribe: EDGE_FAILED facts only
        ↓ read-only: domain / lease / gate facts
Media Execution Plane                       (unchanged in B1)
```

No new media tasks. No new native entry points.

---

## Field gate (NOT authorized here)

Field run card required after desk pass. Minimum grep:

```text
RECOVERY_REQUESTED
RECOVERY_WAITING_FOR_DOMAIN
RECOVERY_SUPPRESSED
RECOVERY_TERMINAL
CONFERENCE_FAILURE_L1_CLASSIFIED terminal=EDGE_FAILED
```

Field pass = B1 pass criteria on live Shared Native SRD Fault shape. Field does **not** authorize B2.

---

## Next steps (after B1)

```text
B1 impl + desk pass
        ↓
B1 field (run card)
        ↓
B2 IA — safe admission definition + edge re-realization  (NOT STARTED)
        ↓
Optional C IA — domain reset escape hatch
```

---

## Frozen wording

```text
B1 = recovery intent + wait (control plane)
B2 = re-realization after safe admission (NOT v0.1)
Shared Native SRD Fault = primary defect (unchanged)
failure edge = recovery unit
DOMAIN_BLOCKED impact edge ≠ recovery intent subject in B1

Do NOT write:
  Recovery IA fixes native hang
  EDGE_FAILED → immediate re-SRD
  recovery intent restores media
  PC reset / force-release as B1
```

---

## Changelog

| Date | Version | Change |
|------|---------|--------|
| 2026-08-25 | v0.1 | Initial B1-only authorization |
