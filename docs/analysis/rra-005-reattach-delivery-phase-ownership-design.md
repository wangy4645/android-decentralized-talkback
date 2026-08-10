# RRA-005 — Reattach Delivery-Phase Ownership Design

**ID:** rra-005  
**Date:** 2026-08-10  
**Type:** OWNERSHIP CONTRACT · **DESK-ONLY** · **NOT A FIX** · **NOT IMPL**  
**Status:** **COMPLETE · Authorization GRANTED · Phase-2 impl landed**  
**Parents:** [rra-004](./rra-004-reattach-delivery-progress-truth.md) (COMPLETE) · ADR-0042 · ADR-0035  
**Impl:** `ReattachDeliveryProgressFacade` · wired on `TRANSPORT_SENT` / receipt / expire  
**Successor:** Desk tests ✅ · Field EP next

```text
Governance:
  INV-T3-SCHEDULE              PASS / CLOSED
  RRA-001..005 Contract        COMPLETE
  Implementation Candidate     PASS
  Implementation Authorization GRANTED (3 conditions)
  Phase-2 observation          LANDED (no retry / fail / complete)
  Field EP                     PENDING
  RMCA                         HOLD
```

---

## Discipline

```text
Ownership contract only.
No class names / timers / callbacks / fields / diffs.
No RetryManager / backoff / ICE restart design.
```

```text
Having a delivery-progress owner  ≠  Having reliable transport
```

---

## Three-phase protocol (FROZEN)

```text
Phase 1 — Recovery progress (INV-T3)
        ↓
TRANSPORT_SENT
        ↓
Phase 2 — Delivery progress (RRA-005)
        ↓
REMOTE_RECEIPT_ACKED
        ↓
Phase 3 — Recovery completion (existing ADR stack)
        ↓
EDGE_RECOVERED
```

```text
Progress liveness  ≠  Delivery truth  ≠  Recovery completion
```

Architecture relation:

```text
Recovery Controller
    owns Recovery Episode
            │
            │ delegates
            ▼
Delivery Policy / Facade
    owns Delivery Progress Lifecycle
            │
            ▼
Transport / Receipt
    reports delivery evidence
```

---

## Frozen inequalities

```text
PROGRESS_WINDOW_SATISFIED  ≠  DELIVERY / REMOTE_EVIDENCE_OBTAINED
TRANSPORT_SENT             ≠  REMOTE_RECEIPT_ACKED
REMOTE_RECEIPT_ACKED       ≠  EDGE_RECOVERED
DELIVERY_PROGRESS_EXPIRED  ≠  DELIVERY_FAILED
DELIVERY_PROGRESS_EXPIRED  ≠  RETRY_REQUIRED
DELIVERY_CONFIRMED         ≠  RECOVERED
```

```text
NO Global RetryManager
NO retry-count / backoff as design center
NO rollback INV-T3
NO ADR-0049 / ICE / media changes in this track
NO code authorization from Q3–Q5 alone
```

---

## Q1a / Q1b / Q2 (prior)

| ID | Status | Contract |
|----|--------|----------|
| **Q1a** | **ACCEPTED** Option B | Share delivery **invariant**; thin REATTACH facade; offer policy ≠ reattach ownership |
| **Q1b** | **ACCEPTED** | Controller owns episode; DeliveryPolicy owns delivery lifecycle; Episode consumes facts |
| **Q2** | **ACCEPTED** | `DELIVERY_PROGRESS_ARMED` = observation obligation established (∥ `PROGRESS_WINDOW_ARMED`) |

---

## Q3 — Episode boundary · **ACCEPTED**

```text
TRANSPORT_SENT
    ↓
DELIVERY_PROGRESS_ARMED
    ↓
WAITING_REMOTE_EVIDENCE
    ├── REMOTE_EVIDENCE_OBTAINED  → close delivery episode
    └── DELIVERY_PROGRESS_EXPIRED → close delivery episode
```

```text
PROGRESS_WINDOW_SATISFIED  ≠  REMOTE_EVIDENCE_OBTAINED
```

Phase-1 may end its progress window at `SENT`; Phase-2 then owns delivery observation.  
**Does not modify INV-T3 semantics.**

---

## Q4 — Evidence boundary · **ACCEPTED**

```text
TRANSPORT_SENT        = local transport submission fact
REMOTE_RECEIPT_ACKED  = remote delivery evidence
EDGE_RECOVERED        = recovery outcome
```

```text
TRANSPORT_SENT
    ≠ REMOTE_RECEIPT_ACKED
    ≠ EDGE_RECOVERED
```

Phase-2 success **must** come from remote evidence — never re-derived from sender-local transport facts.

No new receipt type required on present evidence; evidence vocabulary **not expanded**.

Non-evidence for OBTAINED (retained): HELLO/HB alone, local `BIDIRECTIONAL_READY`, `PROGRESS_WINDOW_SATISFIED`, ICE/media ONLINE.

---

## Q5 — Policy boundary · **ACCEPTED**

Delivery Policy **may** own:

```text
window · deadline · waiting · evidence consumption · expiration
```

Delivery Policy **must not** own:

```text
retry count · backoff · global scheduling · terminal failure · recovery completion
```

```text
Delivery Progress Policy
        ≠ Retry Framework
        ≠ Recovery Completion Policy
```

```text
DELIVERY_PROGRESS_EXPIRED  ≠  RETRY_REQUIRED
```

Expiration closes the observation lifecycle; Episode policy decides any next recovery action.

---

## Q6 — Authorization boundary · **ACCEPTED** (morphology B)

### Sole question

> How to attach existing **ADR-0035 delivery invariant semantics** to REATTACH **without** changing Recovery Episode ownership, Completion ownership, or inventing Retry ownership?

### Core freeze (write this into the contract)

> **REATTACH should consume ADR-0035 delivery invariant, not inherit ADR-0035 offer lifecycle ownership.**

中文：

> **REATTACH 应复用 ADR-0035 的 delivery truth 语义，而不是继承 offer delivery 生命周期。**

```text
Reuse:   delivery observation / deadline / evidence semantics
Do NOT:  migrate OfferPolicy ownership onto REATTACH
```

---

### Option A — Direct reuse `RecoveryOfferDeliveryPolicy` · **REJECT**

```text
Status: REJECT (architecture risk high)
```

Not because code reuse is bad — because **semantic boundary mismatches**.

Offer-implied context:

```text
Offer lifecycle → Offer delivery obligation → Offer evidence → Offer resolution
```

REATTACH context:

```text
Existing conference episode → Reattach attempt → Delivery evidence
  → Existing recovery completion path
```

| Shared | Not shared |
|--------|------------|
| delivery observation semantics | episode ownership |
| deadline semantics | completion ownership |
| evidence semantics | retry ownership |
| | terminal meaning |

Risk of direct Policy reuse:

```text
REATTACH → OfferPolicy → mistakenly gains Offer lifecycle ownership
        → Delivery truth pollutes Recovery completion
```

```text
A may still inform implementation details later.
A MUST NOT be the ownership model.
```

---

### Option B — Shared Delivery Invariant + REATTACH Thin Facade · **ACCEPTED**

```text
Status: ACCEPTED (morphology)
```

Preserves three-phase protocol:

```text
Phase 1  Recovery progress        → TRANSPORT_SENT
Phase 2  Delivery observation     → REMOTE_RECEIPT_ACKED
Phase 3  Completion evaluation    → EDGE_RECOVERED
```

Delivery layer answers only:

```text
whether remote delivery evidence was obtained
```

Does **not** answer:

```text
whether to retry · whether recovery succeeded · whether to end episode
```

| Layer | Owner |
|-------|-------|
| Episode | `ConferenceEdgeRecoveryController` |
| Progress window | Recovery progress (INV-T3) |
| Delivery observation | Delivery policy / facade |
| Completion | Recovery completion policy |

---

### Q6.1 — Minimal Delivery Invariant Set · **ACCEPTED**

**Goal:** Extract the **minimum delivery truth invariant** from ADR-0035 — not the full delivery policy.

```text
DeliveryObservationEpisode

    CREATED
       ↓
    WAITING_REMOTE_EVIDENCE
       ↓
    EVIDENCE_OBTAINED
       or
    EXPIRED
```

#### Required invariants

| ID | Invariant | Why |
|----|-----------|-----|
| **I1** | Delivery observation has explicit ownership | Today: `TRANSPORT_SENT` → nobody owns waiting → no bounded progress |
| **I2** | Evidence source is external to submission | `TRANSPORT_SENT` ≠ delivery evidence; allow `REMOTE_RECEIPT_ACKED` |
| **I3** | Evidence lifecycle has bounded end | WAITING must end in OBTAINED or EXPIRED — no forever hang |
| **I4** | Expiry is not failure | `EXPIRED ≠ DELIVERY_FAILED`; no retry/episode/completion decision here |

```text
ACCEPTED — Minimal Delivery Invariant:

CREATED → WAITING_REMOTE_EVIDENCE → EVIDENCE_OBTAINED | EXPIRED

with:
  TRANSPORT_SENT ≠ DELIVERY_EVIDENCE
  EXPIRED ≠ FAILURE
```

Maps to prior vocabulary:

```text
DELIVERY_PROGRESS_ARMED          ≈ CREATED (observation obligation exists)
WAITING_REMOTE_EVIDENCE
REMOTE_EVIDENCE_OBTAINED         ≈ EVIDENCE_OBTAINED
DELIVERY_PROGRESS_EXPIRED        ≈ EXPIRED
```

**Explicitly excluded from the invariant:**

| Item | Reason |
|------|--------|
| retry count | retry ownership |
| backoff | scheduler ownership |
| FAILED | terminal semantics |
| completion | recovery completion |
| EDGE_RECOVERED | episode outcome |

---

### Q6.2 — Thin Facade Boundary · **ACCEPTED**

**Principle:** Facade is a **truth adapter**, not a recovery controller.

#### Allowed inputs

```text
REATTACH delivery intent
  + episode identity
  + transport submission fact     // arms observation only — not a result
  + evidence stream
```

#### Internal responsibility (allowed)

```text
create observation obligation
observe evidence
track bounded waiting
emit evidence state transition
```

#### Allowed outputs (only)

```text
DELIVERY_PROGRESS_OBTAINED
DELIVERY_PROGRESS_EXPIRED
```

#### Forbidden outputs / effects

| Forbidden | Owner that retains it |
|-----------|------------------------|
| `retry()` | execution / Episode policy |
| `fail()` | recovery policy |
| `complete()` | completion evaluator |

```text
ACCEPTED — Facade boundary:

Facade owns:     delivery observation lifecycle
Facade does not: episode lifecycle · retry lifecycle · completion lifecycle
```

---

## Exit Criterion / Governance Invariant · **ACCEPTED**

Do not expand design detail further. Nail ownership:

```text
DeliveryProgressOwner may own REATTACH Phase-2 delivery observation lifecycle.

It MUST NOT acquire ownership of:
  - Recovery Episode lifecycle
  - Retry scheduling / execution lifecycle
  - Recovery Completion lifecycle
```

中文：

> `DeliveryProgressOwner` 可以成为 REATTACH Phase-2 的 delivery truth owner，但其状态迁移不得改变 Episode、Retry、Completion 三者的 ownership。

### Ownership Matrix · **FROZEN**

| Domain | Owner | Phase-2 may touch? |
|--------|-------|--------------------|
| Recovery Episode | `ConferenceEdgeRecoveryController` | ❌ no transfer |
| Progress Window | INV-T3 Progress owner | ❌ no expand |
| Delivery Evidence Observation | `DeliveryProgressOwner` | ✅ new responsibility |
| Retry decision | existing retry / recovery policy | ❌ not absorbed |
| Completion evaluation | Recovery Completion Policy | ❌ not absorbed |

### Parallel chains (must not merge)

```text
Phase 1 (unchanged INV-T3):
  SEND_FAILED → ProgressWindow → TRANSPORT_SENT → SATISFIED

Phase 2 (additive):
  TRANSPORT_SENT → WAITING_REMOTE_EVIDENCE
    → REMOTE_RECEIPT_ACKED → (input to) Completion reevaluation
    or EXPIRED
```

```text
Progress truth → Delivery truth → Completion truth

SATISFIED ≠ RECEIPT ≠ EDGE_RECOVERED
```

### Forbidden evolutions

| # | Forbidden | Why |
|---|-----------|-----|
| 1 | `DeliveryProgressOwner → retry()` | Retry ownership leak; INV-T3 bloat; Global RetryManager risk |
| 2 | `EXPIRED → FAILED_MEDIA` | EXPIRED = evidence absent in window ≠ system failure |
| 3 | `REMOTE_RECEIPT_ACKED → EDGE_RECOVERED` | Receipt is completion **input** only |

---

## Exit Artifact checklist

| # | Item | Status |
|---|------|--------|
| 1 | Delivery lifecycle owner | **ACCEPTED** |
| 2 | Shared invariant vocabulary | **ACCEPTED (Q6.1)** |
| 3 | Episode boundary | **ACCEPTED (Q3)** |
| 4 | Evidence boundary | **ACCEPTED (Q4)** |
| 5 | Policy / facade boundary | **ACCEPTED (Q5 + Q6.2)** |
| 6 | Authorization morphology | A **REJECT**; B **ACCEPTED** |
| 7 | Exit governance invariant | **ACCEPTED** |

---

## Status stamp

```text
INV-T3-SCHEDULE                PASS / CLOSED
RRA-001..005                   COMPLETE
Implementation Authorization   GRANTED
Phase-2 facade                 LANDED
Unit tests                     PASS (Phase-2 + INV-T3)
Field EP                       PENDING
```

---

## One-line gate

> Phase-2 delivery observation is additive after SENT — field EP next; still no retry/completion ownership change.
