# RRA-004 — Reattach Delivery Progress Truth Reconstruction

**ID:** rra-004  
**Date:** 2026-08-10  
**Type:** DELIVERY PROGRESS TRUTH · **READ-ONLY** · **NOT A FIX** · **NOT BARRIER**  
**Status:** **COMPLETE · ARCHITECTURE GAP CLOSED (desk)** · implementation **NOT AUTHORIZED**  
**Parents:** [rra-003](./rra-003-recovery-acceptance-context-matrix.md) · ADR-0042 · ADR-0035  
**Successor:** [rra-005-reattach-delivery-phase-ownership-design.md](./rra-005-reattach-delivery-phase-ownership-design.md)  
**Does NOT own:** ADR-0038 completion admission · `EDGE_RECOVERED` predicate · INV-T3 schedule rollback

```text
Governance:
  INV-T3-SCHEDULE     PASS / CLOSED  (do not reopen)
  RRA-001..003        COMPLETE
  RRA-004             COMPLETE ← this document (ownership gap proven)
  RRA-005             ACTIVE (ownership design — desk only)
  RMCA                HOLD
  Implementation      NOT AUTHORIZED
```

---

## Why this track exists

RRA-003 proved a layer hole, then calibrated it:

```text
ProgressWindow lifecycle
        ≠
EdgeRecovery obligation lifecycle
        ≠
Recovery completion lifecycle
```

Field (PRV, at `SATISFIED`):

```text
obligationOpen = true
edgePhase      = REATTACH_REQUESTED
completion     = WAITING
deliveryConfirmed = false
ProgressWindow = SATISFIED
```

So the gap is **not** “Recovery completion falsely satisfied.”

**Primary gap (inherited):**

```text
Progress-window completion truth collapsed onto TRANSPORT_SENT
+
Missing delivery-phase bounded progress owner after SENT
```

RRA-004 asks only: **after SENT, who owns advancement when remote evidence is absent?**

---

## Three lifecycles (frozen vocabulary)

| Lifecycle | Opens | Satisfies / advances | Closes |
|-----------|-------|----------------------|--------|
| **ProgressWindow** | `SEND_FAILED` → ARMED | **SENT → SATISFIED** (today) | SATISFIED / EXPIRED |
| **Edge obligation** | attempt / episode | stays open through REATTACH_REQUESTED | separate terminal paths |
| **Recovery completion** | evaluation | WAITING until policy facts | EDGE_RECOVERED / fail paths (ADR-0038 — **out of scope**) |

INV-T3-SCHEDULE covers only:

```text
SEND_FAILED → dispatch opportunity → (attempt) SENT
```

It does **not** cover:

```text
SENT → remote delivery uncertainty → bounded progress
```

---

## D1 — What `SATISFIED` precisely means

**Answer:**

```text
SATISFIED =
  progress-window dispatch opportunity consumed
  via local TRANSPORT_SENT (SENDTO_SUCCESS)
```

**Not:**

```text
delivery progress advanced
remote recovery progressed
completion advanced
```

Code: `applyReattachDispatchOutcome(SENT)` → `satisfyProgressWindowIfActive`.

When no progress window is active (DIGEST / RLA historical success), there is **no** `SATISFIED` log at all — confirming the token is **window-scoped**, not delivery-scoped.

---

## D2 — Remote evidence sources after SENT (ownership)

| Fact | Exists in system? | Owner | Effect after SENT |
|------|-------------------|-------|-------------------|
| `TRANSPORT_SENT` | yes | Recovery + Coordinator send | satisfies ProgressWindow |
| M01 decode / `RECOVERY_REATTACH_INBOUND` | yes (receiver) | Host Coordinator → Controller | host-side; sender may not see |
| `RECOVERY_REATTACH_RECEIPT` → `REMOTE_RECEIPT_ACKED` | **yes** | **Recovery Controller** (`onRecoveryReattachReceipt`) | control-admission / completion **reevaluate**; **does not** re-arm ProgressWindow; **does not** by itself redispatch REJOIN |
| Host `RECOVERY_REATTACH accepted` | yes (receiver) | Host | not a sender ProgressWindow input |
| ADR-0035 `DELIVERY_CONFIRMED` / retransmission owner | **contract exists** | Delivery Assurance → Episode Owner (ADR-0035) | **not** wired as INV-T3 second-phase progress after SENT |

```text
Delivery evidence plane  ⊥  ProgressWindow SATISFIED
```

They are **orthogonal** today: receipt advances `reattachDeliveryState` and may trigger `REMOTE_RECEIPT_ACKED` reevaluate; ProgressWindow is already terminal on SENT.

**T5-A (closed):** After ProgressWindow `SATISFIED`, **no evidence** of a delivery-phase bounded progress owner that can redispatch on missing receipt.

---

## D3 — Historical success order (RFA / RLA vs PRV)

| Corpus | ProgressWindow? | Order |
|--------|-----------------|-------|
| **RFA EP05** | **0** `PROGRESS_WINDOW_*` lines | `SENT` → ~30ms `RECEIPT` / host INBOUND+ACCEPTED (clock-skew aware) |
| **RLA att8** | **0** window lines | `SENT` → host INBOUND+RECEIPT+ACCEPTED |
| **PRV att1** | yes (INV-T3 path) | `SENT` → **immediate `SATISFIED`** → **no RECEIPT** |

```text
Historical success is NOT:
  SENT → remote evidence → SATISFIED

It is:
  SENT → (no ProgressWindow) → remote RECEIPT arrives as separate delivery fact

PRV INV-T3 path:
  SENT → SATISFIED (window done) → remote evidence never arrives
       → no second-phase owner to act on absence
```

**Implication for later design (desk only, not authorized):**

```text
Prefer: add / wire delivery-phase progress ownership after SENT
     (align with ADR-0035 spirit)
Not:   move ProgressWindow SATISFIED to after remote evidence
     (would conflate INV-T3 window with delivery; fights ADR-0042 wording)
```

State-transition tweak of `SATISFIED` vs ownership of a **new** delivery progress phase are different moves; D3 favors **ownership / second phase**, not redefining INV-T3 `SATISFIED`.

---

## Two-phase model (frozen)

```text
Phase 1 — Progress Opportunity (INV-T3)

SEND_FAILED → PROGRESS_WINDOW_ARMED → dispatch → TRANSPORT_SENT
  → PROGRESS_WINDOW_SATISFIED
     (= dispatch opportunity consumed)

Phase 2 — Delivery Evidence

TRANSPORT_SENT → ??? → REMOTE_RECEIPT_ACKED → admission / completion reevaluate
```

PRV fails in the `???` gap. RFA/RLA hide it because receipt arrives via external activity without needing a Phase-2 owner on the INV-T3 path.

---

## Explicit non-goals (repair anti-patterns)

```text
Do NOT:
  1. Delay ProgressWindow SATISFIED until receipt
     (pollutes progress contract with delivery contract; fights ADR-0042)
  2. Blind “retry N times if no receipt” as the model
     (delivery uncertainty ≠ generic retry problem)
  3. Invent Global RetryManager / Delivery retry service
     (Recovery owns obligation; Transport reports truth; Coordinator executes)
```

---

## D4 — Receipt ownership

**Question:** Can `REMOTE_RECEIPT_ACKED` become `DeliveryProgressEvidenceOwner`, or is it only a completion input?

**Answer:**

| Role | Verdict |
|------|---------|
| Owner of receipt **ingest** | Recovery Controller (`onRecoveryReattachReceipt`) |
| Effect today | evidence **consumer** → control-admission / completion **reevaluate** |
| Delivery-phase **progress owner** | **No** — does not arm supervision, does not schedule retransmit, does not expire waiting |

Parallel vocabulary (do not conflate):

| Plane | Token | Wired to `CONFERENCE_REJOIN`? |
|-------|-------|-------------------------------|
| Reattach delivery state | `TRANSPORT_SENT` / `REMOTE_RECEIPT_ACKED` | **Yes** |
| ADR-0035 offer delivery | `DELIVERY_PENDING` / `CONFIRMED` / `EXHAUSTED` + `RecoveryOfferDeliveryPolicy` retry timer | **No** on reattach send path |

`onRecoveryOfferDeliveryPending` is called from **GROUP_JOIN / conference offer** send (`attemptConferencePeerOffer`), **not** from `executeRecoveryReattachSend`.

RFA after receipt still logs `deliveryPhase=NONE` and `deliveryConfirmed=false` while `dispatchState=REMOTE_RECEIPT_ACKED` — receipt ≠ ADR-0035 `DELIVERY_CONFIRMED`.

```text
REMOTE_RECEIPT_ACKED  = delivery evidence fact (consumer input)
≠ DeliveryProgressOwner
≠ DELIVERY_CONFIRMED (offer lineage)
```

Extending receipt alone without a Phase-2 owner still leaves `SENT ∧ ¬receipt` unsupervised.

---

## D5 — Natural owner between SENT and RECEIPT?

Searched existing supervisors:

| Mechanism | Exists? | Owns `TRANSPORT_SENT → REMOTE_RECEIPT` for REJOIN? |
|-----------|---------|-----------------------------------------------------|
| ProgressWindow | yes | **No** after SATISFIED (Phase 1 done) |
| `RecoveryOfferDeliveryPolicy.scheduleDeliveryRetry` | yes (ADR-0035) | **Not attached** to REJOIN path |
| Attempt `scheduleWatchdog` | yes | **Attempt budget / terminal residency** — not receipt wait; on PRV M01 edge was `WATCHDOG_DEFERRED` (capability) before window; **no** `WATCHDOG_STARTED` on SENT |
| Obligation deadline | yes | Episode close path — not delivery observation |
| Receipt callback | yes | Only when receipt **arrives** |

PRV counts (whole run):

```text
RECOVERY_DELIVERY_PENDING     0
RECOVERY_DELIVERY_CONFIRMED   0
RECOVERY_DELIVERY_EXHAUSTED   0
REMOTE_RECEIPT (reattach)     0
PROGRESS_WINDOW_SATISFIED     4
```

```text
D5 verdict: OWNERSHIP GAP CONFIRMED for CONFERENCE_REJOIN path

Not “no code anywhere”:
  ADR-0035 delivery progress owner exists for recovery offers

But for reattach REJOIN after TRANSPORT_SENT:
  no DELIVERY_PENDING arm
  no delivery retry timer
  no receipt-wait watchdog
  ProgressWindow already consumed
  → silent wait until unrelated events or attempt/episode timeout paths
```

---

## D6 — Minimal new facts (protocol vocabulary only)

Not implementation. Prefer aligning with ADR-0035 spirit over inventing `RETRYING` / `FAILED`.

**Candidate fact set (delivery-progress plane):**

```text
DELIVERY_PROGRESS_ARMED
  — entered when TRANSPORT_SENT for reattach lineage; supervision starts

DELIVERY_WAITING_REMOTE_EVIDENCE
  — awaiting REMOTE_RECEIPT_ACKED (or equivalent)

DELIVERY_EVIDENCE_OBTAINED
  — receipt / confirmed remote evidence for this lineage

DELIVERY_PROGRESS_EXPIRED
  — bounded wait ended without evidence (fact only — not RECOVERED/FAILED_MEDIA)
```

Mapping note (desk):

```text
May later alias to ADR-0035:
  PENDING / CONFIRMED / EXHAUSTED
if REJOIN is admitted into RecoveryOfferDeliveryPolicy

Or remain reattach-scoped twin if offer lineage ≠ reattach nonce lineage
```

Forbidden as Phase-2 facts:

```text
RETRYING · FAILED · EDGE_RECOVERED · closeObligation
```

---

## Architecture gap (frozen)

```text
Primary:

  Missing bounded delivery-progress ownership
  after TRANSPORT_SENT for CONFERENCE_REJOIN

Refined:

  Phase-1 ProgressWindow owner exists and works (INV-T3)
  Phase-2 evidence consumer exists (REMOTE_RECEIPT_ACKED)
  Phase-2 progress owner missing on REJOIN path
  (ADR-0035 owner exists but unwired to reattach send)
```

---

## Non-goals

```text
Do NOT:
  rollback INV-T3-SCHEDULE
  change ADR-0038 completion predicates
  delay SATISFIED until receipt
  declare rebind the root
  implement wiring / barrier yet
  treat media / ICE as first break
```

---

## Exit criteria

| Outcome | Meaning |
|---------|---------|
| **D1–D6 CLOSED** | domains + ownership gap proven |
| **BOUNDARY DRAFT** | Phase-2 owner named (wire ADR-0035 vs twin plane) |
| **IMPL AUTHORIZED** | separate gate + ADR amendment |

---

## Status stamp

```text
INV-T3-SCHEDULE       PASS / CLOSED

RRA-001               COMPLETE
RRA-002               COMPLETE
RRA-003               COMPLETE
RRA-004               COMPLETE

Primary architecture gap:
  REJOIN delivery-phase bounded progress
  ownership missing after TRANSPORT_SENT

Root cause:
  protocol ownership gap — HIGH CONFIDENCE

Successor:
  RRA-005 REATTACH DELIVERY-PHASE OWNERSHIP DESIGN (desk)

Implementation:
  NOT AUTHORIZED
```

---

## One-line gate

> Phase 2 after `TRANSPORT_SENT` has an evidence consumer but no progress owner on the REJOIN path — gap closed as architecture finding; design continues in RRA-005.
