# ADR-0065 Patch-2: Pairwise Edge Obligation Reactivation Contract

## Status

**PATCH-2A / PATCH-2B / PATCH-2C IMPLEMENTED — DESK UNIT PASS** (2026-09-20)

```text
Grill Q1..Q6:                         PASS (2026-09-18)
Patch-2A (lifecycle + reducer):       IMPLEMENTED — unit PASS
Patch-2B (reevaluation wiring):       IMPLEMENTED — unit PASS
Patch-2C (activation authority):      IMPLEMENTED — unit PASS (desk)

ADR-0065 Patch-1:                     IMPLEMENTED
ADR-0065 Patch-1.1:                   IMPLEMENTED
ADR-0064 Patch A:                     IMPLEMENTED
ADR-0064 Patch A.1:                   IMPLEMENTED

Production Meeting policy:            UNCHANGED
M02↔M04 field issue:                  OPEN / NOT FIELD-VERIFIED
Field verification (Appendix B):      REVIEW PASS
                                      ONE M02↔M04 RUN AUTHORIZED
                                      result NOT YET ADJUDICATED
```

**Parents / boundaries:**

- [ADR-0064 group invite delivery satisfied invalidation](./0064-group-invite-delivery-satisfied-invalidation-contract.md) — delivery success fact vs edge incarnation; **GIDR MUST NOT own topology obligation lifecycle**
- [ADR-0057 Track C GIDR](../analysis/adr0057-group-invite-delivery-recovery-narrow-authorization.md) — delivery retry eligibility; orthogonal to obligation SATISFIED
- [ADR-0057 Track C GPLB-LC](../analysis/adr0057-gplb-binding-lifecycle-correction-narrow-authorization.md) — binding incarnation; **MUST NOT create or satisfy pairwise obligation**
- [ADR-0040 obligation convergence](./0040-obligation-convergence.md) — conference recovery execution; **RecoveryController out of scope**
- [ADR-0063 NTI cross-topology](./0063-negotiation-transaction-integrity-cross-topology-contract.md) — fact vs truth owner pattern
- ADR-0065 Patch-1 / Patch-1.1 — invite-plane membership mismatch admission; **MUST NOT expand into obligation lifecycle**

**Field evidence (obligation false-terminal):**

- Log bundle: `talkback/logs/adr0065-sync-stuck-repro-20260918-1705/`
- Symptom: M02↔M04 permanent `DIRECTORY_SYNC` / `BUILDING`; `meshMissingIce=M04` while GIDR `phase=SATISFIED gate=SuppressSatisfied`
- Mechanism: `GROUP_ACCEPT_RECEIVED` → `markMeshLinkCompleted` → `meshCompletedModules` → `signalingSatisfied=true` → `PairwiseMeshAdmissionActivationSupport` `NoAction` while ICE remained `CHECKING`

---

## Summary

Patch-2 fixes **topology obligation reactivation**, not delivery validity (ADR-0064 A.1) and not membership authority (Layer 1).

The core defect is **wrong obligation completion semantics**:

```text
GROUP_ACCEPT / delivery success
        ≠
media edge available
        ≠
topology intent SATISFIED
```

Patch-2 elevates `PairwiseMeshAdmissionObligation` as the **sole topology-intent owner** for required pairwise edges, introduces `PairwiseMeshObligationState`, and routes all lifecycle mutation through a single **ObligationReducer**.

GIDR, GPLB, and `meshCompletedModules` remain important but **MUST NOT** be treated as obligation authority.

---

## Problem statement

**In scope:**

```text
When a required pairwise edge still exists in topology but activation opportunity
was lost after membership churn, cold join, partial edge failure, or
signaling-without-media completion — re-enter activation evaluation safely
for 8–10 participant meshes without offer storms.
```

**Out of scope (Patch-2 contract):**

- Membership authority election / forceAlign continuity (Layer 1)
- GIDR satisfied validity (ADR-0064 Patch A.1 — DONE)
- RecoveryController / WiFi recovery / ICE restart policy
- UDP reliability, timeout budget tuning
- Deleting `meshCompletedModules` in one step
- Delivery terminal semantics ADR (follow-on track; GROUP_ACCEPT timing not rewritten in Patch-2 impl unless separately authorized)

---

## Three success domains (frozen)

Patch-2's most important boundary is **semantic separation**:

| Domain | Question | Owner | Example terminal |
|--------|----------|-------|------------------|
| **Delivery success** | Did invite delivery complete? | GIDR overlay | `GROUP_ACCEPT_RECEIVED` |
| **Media availability** | Is current incarnation usable? | GPLB + ICE observation | ICE `CONNECTED`, binding LIVE |
| **Topology intent** | Must this edge exist and is it done? | Obligation lifecycle | `PairwiseMeshObligationState.SATISFIED` |

**Allowed divergence (no longer exceptional):**

```text
GIDR delivery overlay     = SATISFIED (VALID)
Obligation lifecycle      = MEDIA_PENDING
ICE observation           = CHECKING
```

Patch-2 MUST NOT collapse these domains back into one boolean (`signalingSatisfied`, `meshCompletedModules`).

---

## Observation facts vs lifecycle state (frozen)

### Observation facts (read-only inputs)

Facts describe what was observed. **Facts MUST NOT mutate obligation lifecycle directly.**

| Fact | Typical source | May trigger |
|------|----------------|-------------|
| `IceConnectivityObservation` | QoS / WebRTC ICE callback | `REEVALUATION_REQUIRED` |
| `BindingIncarnationObservation` | GPLB fence/release/generation | `REEVALUATION_REQUIRED` |
| `MembershipTopologyObservation` | roster convergence / rollback | `REEVALUATION_REQUIRED` |
| `DeliveryObservation` | GROUP_ACCEPT / GIDR terminal | reducer input only |

Forbidden pattern:

```text
ICE callback → set ACTIVATION_REQUIRED        ❌
meshMissingIce → set ACTIVATION_REQUIRED      ❌
GIDR PermitRetry → set ACTIVATION_REQUIRED    ❌
```

### Lifecycle state (single write surface)

```text
PairwiseMeshObligationState   // on PairwiseMeshAdmissionObligation
```

**Only `ObligationReducer` may mutate lifecycle.**

```text
Facts
  |
  v
ObligationReducer
  |
  v
PairwiseMeshObligationState
```

---

## Q1 — Obligation lifecycle owner

**Decision: ACCEPT** — `PairwiseMeshAdmissionObligation` is the edge-intent owner.

Do **not** introduce a separate `EdgeEstablishmentIntent` object in Patch-2.

### Enum (frozen name)

```kotlin
PairwiseMeshObligationState   // not EdgeState — avoids GPLB confusion
```

### States

| State | Meaning |
|-------|---------|
| `PENDING` | Topology requires edge; no active activation path |
| `ACTIVATION_REQUIRED` | Edge known missing/incomplete; activation evaluation allowed |
| `SIGNALING_IN_FLIGHT` | Offer/answer transaction in flight |
| `MEDIA_PENDING` | Signaling accepted; media edge not yet proven |
| `SATISFIED` | Media edge established per strict definition below |

### Nominal progression

```text
PENDING
  → ACTIVATION_REQUIRED
  → SIGNALING_IN_FLIGHT
  → MEDIA_PENDING
  → SATISFIED
```

### Exception loop (see Q6)

```text
SATISFIED
  → (edge invalid fact + grace/cooldown)
  → ACTIVATION_REQUIRED
```

`signalingSatisfied: Boolean` on `PairwiseMeshAdmissionObligation` is **deprecated**. New code MUST read lifecycle, not this flag.

---

## Q2 — Write ownership

| Component | Mutate lifecycle? | Allowed role |
|-----------|---------------------|--------------|
| **ObligationReducer** | **YES (sole writer)** | Apply facts → lifecycle transitions |
| Membership convergence boundary | NO | Emit `REEVALUATION_REQUIRED` |
| ICE / QoS boundary | NO | Emit `IceConnectivityObservation` |
| GPLB boundary | NO | Emit `BindingIncarnationObservation` |
| GROUP_ACCEPT / invite handlers | NO | Emit `DeliveryObservation` |
| `PairwiseMeshAdmissionActivationSupport` | NO | Read lifecycle; request evaluation |
| GIDR | **NO** | Delivery suppress / permit only |
| GPLB | **NO** | Binding lifecycle only |
| RecoveryController | **NO** | Isolated domain |
| `meshCompletedModules` | **NO** | Compatibility projection only |

**Frozen invariants:**

```text
GIDR MUST NOT set obligation SATISFIED.
GPLB MUST NOT create or satisfy obligation.
meshCompletedModules is a compatibility projection, not an admission authority.
Only ObligationReducer may mutate PairwiseMeshObligationState.
```

---

## Q3 — GROUP_ACCEPT and delivery semantics

`GROUP_ACCEPT_RECEIVED` is a **delivery fact** (GIDR domain). It MUST NOT directly produce obligation `SATISFIED`.

### Event → lifecycle mapping (obligation reducer)

| Event | Obligation lifecycle | Notes |
|-------|---------------------|-------|
| Invite dispatched (offerer) | `SIGNALING_IN_FLIGHT` | Per-edge serialization |
| `GROUP_ACCEPT_RECEIVED` | **`MEDIA_PENDING`** | **Not** `SATISFIED` |
| ICE `CONNECTED` + valid binding + no fence | **`SATISFIED`** | Strict definition below |
| ICE lost / binding invalid (post-grace) | `ACTIVATION_REQUIRED` | Via Q6 downgrade |

GIDR may still record delivery satisfied on `GROUP_ACCEPT`. That overlay MUST NOT be interpreted as obligation completion.

---

## SATISFIED strict definition (frozen)

Obligation `SATISFIED` requires **all** of:

```text
1. obligation exists for required edge
2. signaling completed for current activation episode
3. current media binding is valid on authoritative incarnation
4. ICE connectivity observation == CONNECTED (or COMPLETED)
5. no active fence/release on authoritative binding
```

If any condition fails, obligation MUST NOT be `SATISFIED`.

`GROUP_ACCEPT_RECEIVED` alone MUST produce **`MEDIA_PENDING` only**.

---

## Q4 — Reevaluation model

`REEVALUATION_REQUIRED` is an **event**, not a lifecycle state.

```text
EdgeObservationChanged
       |
       v
REEVALUATION_REQUIRED (event)
       |
       v
ObligationReducer.scheduleEvaluate(edge)
       |
       v
(maybe) ACTIVATION_REQUIRED   // only if Q5 constraints pass
```

**Forbidden shortcuts:**

```text
Observation → Execution          ❌
meshMissingIce → IssueInvite     ❌
REEVALUATION_REQUIRED → offer    ❌
```

### `meshMissingIce` role

`meshMissingIce` is a **topology health projection** (diagnostic / scheduling input).

```text
meshMissingIce=true
  → MAY emit REEVALUATION_REQUIRED
  → MUST NOT write ACTIVATION_REQUIRED directly
  → MUST NOT write lifecycle state directly
```

---

## Q5 — 8–10 participant storm constraints

These are **contract requirements**, not implementation hints.

### Per-edge serialization

At most **one** activation transaction per `PairwiseMeshEdgeKey` at a time.

### Offer execution ownership

```text
lexicographically smallest ModuleId on edge = offerer (execution)
```

This is **execution ownership**, not obligation ownership. Both endpoints track the same required edge locally.

### CHECKING grace

```text
ICE CHECKING alone
  → MUST NOT downgrade SATISFIED
  → MUST NOT enter ACTIVATION_REQUIRED
  → MUST NOT IssueInvite
```

Minimum grace window required before CHECKING may contribute to downgrade (contract constant TBD at impl plan; field anchor ≥ existing negotiation timeout discourse).

### FAILED / DISCONNECTED

```text
ICE FAILED or DISCONNECTED
  → eligible for REEVALUATION_REQUIRED immediately
  → ACTIVATION_REQUIRED only after cooldown elapsed
```

### Membership churn window

During roster transition / non-converged membership:

```text
MAY queue REEVALUATION_REQUIRED
MUST NOT enter ACTIVATION_REQUIRED
MUST NOT IssueInvite
```

### Per-tick budget

On each planner tick per session:

```text
max edges entering ACTIVATION_REQUIRED: 1–2 (contract default)
```

Prevents 45-edge thundering herd in 10-node mesh.

---

## Q6 — SATISFIED → ACTIVATION_REQUIRED downgrade owner

**Dedicated section — MUST NOT be merged into Q2/Q5 prose.**

### Owner

**Only `ObligationReducer` may downgrade lifecycle.**

No ICE callback, GPLB handler, GIDR reconcile, or `meshCompletedModules` mutation may directly set `ACTIVATION_REQUIRED` or clear obligation completion.

Forbidden anti-pattern:

```text
onIceDisconnected()
  → meshCompletedModules.remove(peer)
  → some handler resets obligation ad hoc
```

This recreates a **second authority** parallel to `ObligationReducer`.

### Downgrade inputs (facts only)

| Fact | Condition | Downgrade after grace/cooldown? |
|------|-----------|-------------------------------|
| `IceConnectivityObservation` | `DISCONNECTED` / `FAILED` sustained | YES |
| `BindingIncarnationObservation` | binding `FENCED` / `RELEASED` or incarnation mismatch | YES |
| `MembershipTopologyObservation` | rollback requires edge re-establishment | YES |
| `meshMissingIce` projection | true | NO (reevaluation only) |
| `IceConnectivityObservation` | `CHECKING` / `NEW` within grace | NO |
| GIDR `STALE_EDGE` / `PermitRetry` | delivery eligibility opened | NO (parallel plane) |

GIDR opening delivery eligibility **does not** substitute for obligation downgrade. Both may be true simultaneously.

### Downgrade flow (17:02 class closure)

```text
(pre-Patch-2 bug path)
GROUP_ACCEPT → meshCompletedModules → signalingSatisfied → NoAction

(Patch-2 target path)
GROUP_ACCEPT → MEDIA_PENDING
CHECKING beyond grace OR ICE lost
  → REEVALUATION_REQUIRED
  → ObligationReducer
  → ACTIVATION_REQUIRED
  → ActivationSupport.evaluate()
  → (offerer) IssueInvite subject to GIDR + serialization
ICE CONNECTED + valid binding
  → SATISFIED
```

**Q6 frozen sentence:**

> **`SATISFIED → ACTIVATION_REQUIRED` downgrade is performed only by ObligationReducer from ICE/GPLB/Membership observation facts; meshMissingIce and GIDR MUST NOT directly downgrade obligation lifecycle.**

---

## Q4 legacy — `meshCompletedModules`

**Frozen:**

```text
meshCompletedModules is a compatibility projection, not an admission authority.
```

### Transition plan (contract only)

| Phase | Rule |
|-------|------|
| Patch-2A | Introduce lifecycle as authoritative; keep writing projection for compat |
| Patch-2B+ | New reads use lifecycle; `isSignalingSatisfied()` maps from lifecycle ≥ `MEDIA_PENDING` temporarily |
| Follow-on | Stop using projection as admission input; optional removal later |

**Mapping guidance:**

| Lifecycle | `meshCompletedModules` |
|-----------|------------------------|
| `PENDING` / `ACTIVATION_REQUIRED` | absent |
| `SIGNALING_IN_FLIGHT` | absent |
| `MEDIA_PENDING` | **MUST NOT** set completed solely from GROUP_ACCEPT |
| `SATISFIED` | may mirror present (read-only compat) |

---

## Architecture (frozen)

```text
                 Membership
                     |
                     v
             ObligationReducer  ← sole lifecycle writer
                     |
                     v
Topology intent --> PairwiseMeshAdmissionObligation
                     |
        +------------+------------+
        |                         |
        v                         v
 ActivationSupport          GIDR (ADR-0064)
 (read lifecycle)           (delivery gate)
        |                         |
        +------------+------------+
                     |
                     v
                   GIE
                     |
                     v
              GPLB + ICE
                     |
                     v
         obligation SATISFIED (strict)
```

RecoveryController remains isolated.

---

## Acceptance cases (frozen)

| ID | Scenario | Expect |
|----|----------|--------|
| **P2-E1** | invite → accept → ICE CONNECTED | lifecycle → `SATISFIED` |
| **P2-E2** | GROUP_ACCEPT + ICE CHECKING | lifecycle = **`MEDIA_PENDING`**, **not** `SATISFIED` |
| **P2-E3** | SATISFIED → membership churn → edge lost | lifecycle → `ACTIVATION_REQUIRED` |
| **P2-E4** | ICE CHECKING 5s (within grace) | **no** activation / **no** downgrade |
| **P2-E5** | CHECKING > grace OR FAILED | `REEVALUATION_REQUIRED` → `ACTIVATION_REQUIRED` |
| **P2-E6** | 10-node mesh, one edge failed | only affected edge reevaluates; budget respected |

Additional guard (aligns with ADR-0064 A.1 Case E):

| ID | Scenario | Expect |
|----|----------|--------|
| **P2-E7** | obligation `MEDIA_PENDING` + GIDR VALID + CHECKING + `meshMissingIce=true` | obligation stays `MEDIA_PENDING`; no storm activation until Q5 gates pass |

---

## Implementation boundary

Patch-2A / Patch-2B / Patch-2C are **IMPLEMENTED**; desk unit adjudication **PASS** (2026-09-20). Patch-2C detail: [0065-patch-2c-activation-authority-contract.md](./0065-patch-2c-activation-authority-contract.md).

| Patch | Scope | Status |
|-------|-------|--------|
| **Patch-2A** | `PairwiseMeshObligationState`, `PairwiseMeshObligationReducer`, observations, `legacyLifecycleProjection`, tests P2-E1/E2/E7 | **IMPLEMENTED — unit PASS** |
| **Patch-2B** | Observation → reevaluation wiring; downgrade facts; tests P2-E3/E4/E5 | **IMPLEMENTED — unit PASS** |
| **Patch-2C** | Activation reads lifecycle; GIDR AND; C1 drain; tests P2C-A1..A8 + P2C-I2a | **IMPLEMENTED — unit PASS (desk)** |

**Remaining verification:**

```text
Field (Appendix B):     REVIEW PASS — ONE M02↔M04 RUN AUTHORIZED
M02↔M04 field run:      OPEN / NOT FIELD-VERIFIED (result pending)
Production Meeting:     UNCHANGED
8–10 / soak / Layer 1:  NOT AUTHORIZED
```

---

## Relationship to ADR-0064 Patch A.1

| Layer | Patch A.1 (DONE) | Patch-2 (this ADR) |
|-------|------------------|---------------------|
| Question | Is GIDR suppress still valid for this binding/incarnation? | Is topology edge intent complete? |
| Output | `PermitRetry` vs `SuppressSatisfied` | `PairwiseMeshObligationState` |
| CHECKING | Does not invalidate delivery suppress | Does not activate/degrade within grace |
| Execution | Does not dispatch offers | Schedules activation evaluation only |

Patch-2 closes the gap where **delivery suppress and obligation false-SATISFIED** both block recovery.

---

## Grill adjudication (Q1–Q6)

| Gate | Result | Frozen decision |
|------|--------|-----------------|
| **Q1** | PASS | `PairwiseMeshAdmissionObligation` + `PairwiseMeshObligationState` |
| **Q2** | PASS | Sole writer = `ObligationReducer`; GIDR/GPLB/meshCompleted excluded |
| **Q3** | PASS | `GROUP_ACCEPT` → `MEDIA_PENDING`; strict `SATISFIED` definition |
| **Q4** | PASS | `REEVALUATION_REQUIRED` event; meshMissingIce observation-only |
| **Q5** | PASS | Per-edge serialization, offerer ownership, grace, cooldown, budget |
| **Q6** | PASS | Downgrade owner = ObligationReducer; facts-only inputs |

---

## Follow-on tracks (not Patch-2)

- **Delivery terminal semantics ADR** — whether `markMeshLinkCompleted` timing should require media confirmation (contract-only until authorized)
- **Layer 1 membership authority continuity** — reduces churn; does not replace Patch-2
- **ADR-0064 Patch B** — SatisfiedInvalidationFact emitters (audit push); orthogonal

---

## References

- Field: `talkback/logs/adr0065-sync-stuck-repro-20260918-1705/`
- Code (pre-Patch-2): `PairwiseMeshAdmissionSupport.isSignalingSatisfied` → `meshCompletedModules`
- Code (pre-Patch-2): `PairwiseMeshAdmissionActivationSupport.evaluate` → `NoAction` when `signalingSatisfied`
- Tests (Patch-1): `Adr0065Patch1InviteAdmissionSupportTest`, `Adr0065Patch11AdmissionDecisionTest`
- Tests (Patch A.1): `Adr0064PatchA1GidrScopeTest`
