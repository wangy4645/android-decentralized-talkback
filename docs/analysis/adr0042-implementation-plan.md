# ADR-0042 Implementation Plan

**Status:** **PLAN APPROVED** · **IMPLEMENTATION COMPLETE** · **APPROVED FOR MERGE REVIEW** (2026-08-08) — desk verification PASS; Field still PAUSED  
**Date:** 2026-08-08  
**ADR:** [0042-recovery-reattach-transport-delivery-semantics.md](../adr/0042-recovery-reattach-transport-delivery-semantics.md)  
**Parents:** [T1/T2 follow-up](./transport-reattach-send-semantics-followup.md) · evidence `logs/recovery-reattach-delivery-path-20260808-150025/`

```text
ADR-0042        IMPLEMENTATION COMPLETE · APPROVED FOR MERGE REVIEW
This document   PLAN APPROVED
Branch          adr0042-p0-reattach-transport-truth
Desk verify     PASS (red→green + invariant suite)
Runtime         P0 landed on branch (not yet merged)
Field           PAUSED (targeted validation only after merge review)
X1-B / X2       NOT ENTERED / HOLD
```

### Final freeze board (2026-08-08)

```text
Scope:           reattach consumer only
State:           guard-only
Seam:            transport result consumer PRIMARY (gate NOT PRIMARY)
Ownership:       recovery obligation owner (retry A)
Token:           Correct-in-place (SENT == SENDTO_SUCCESS)
Tests:           Sync-scoped + add
Proof:           Red-then-green REQUIRED → PASS
P2:              D-min ONLY
P0 impl review:  APPROVED (2026-08-08)
Rejected:        new state · new coordinator · New-field-migrate · Add-only · Green-only · gate-as-fix-locus · D-strict-in-P2
```

### P0 implementation review (2026-08-08) — PASS

| Gate | Result |
|------|--------|
| 1. Red-then-green evidence chain | **PASS** |
| 2. Correct-in-place does not change ownership | **PASS** (consumer facts; obligation owns eligibility) |
| 3. Failure domain not enlarged | **PASS** (`SEND_FAILED ≠ FAILED_MEDIA`; true SENT keeps `transport_in_flight`) |

**Commits:** `43d6e75` red · `91ff295` fix · `3d7ef75` invariant suite

**Unchanged:** X1-A VERIFIED · X1-B NOT ENTERED · ADR-0035 · recovery admission gate · completion · X2

**Next (not done):** merge review → targeted field validation (narrow: no false SENT / no FAILED_MEDIA / obligation eligible; SENDTO_SUCCESS receipt path unchanged). Do **not** reopen X1-B. Do **not** declare field fix from desk alone.

**Purpose of this plan:** answer only —

> If we implement later, what is the **minimal change boundary**, and how do we prove we did **not** enlarge the failure domain?

**Not:** a “fix recovery” playbook. **Not:** authorization to patch runtime.

**Gate to code:** Plan APPROVED ✅ → **separate** explicit APPROVAL to open implementation branch → then Red-then-green commits.

---

## 1. Scope

### In scope

```text
Recovery Reattach Transport Delivery Semantics
  (INV-T1 .. INV-T4 + failure-state ownership)
```

Only Recovery `CONFERENCE_REJOIN` / reattach control send path.

### Out of scope

| Domain | Why |
|--------|-----|
| X1 admission / reevaluation | Separate layer; not entered on fail path |
| ADR-0035 receipt protocol changes | Receipt contract unchanged |
| Completion / ADR-0038 predicate | Frozen |
| Presence / UVCP / UI | L4 |
| X2 residency | HOLD — see §5 P0 hazard |
| Membership / RCA-0036 | Independent |
| Watchdog budget / timeout tuning | Symptom, not cause |
| Generic reliable-UDP for all signals | Over-scope |

### Touch list (future impl — ceiling)

| May touch | Role |
|-----------|------|
| `UdpSignalingChannel` send-result surface (minimal) | Expose sendto truth **without** changing global throw contract for all callers |
| `TalkbackCoordinator.executeRecoveryReattachSend` | Consume sendto truth; mark SENT only on success |
| `ConferenceEdgeRecoveryController` reattach dispatch / in-flight / SEND_FAILED reaction | INV-T2/T3; forbid false in-flight; forbid transport-fail → media residency |

### Must not touch

```text
ControlAdmissionPredicate / X1 admission graph
ADR-0035 receipt emit/ack protocol
ADR-0038 completion predicate
watchdog budget / timeout constants
membership / RNA-5/6
generic SignalType send paths beyond reattach consumer
UI / banner / presence
```

---

## 2. Current violation mapping

| ADR invariant | Current violation | Implementation target |
|---------------|-------------------|------------------------|
| **INV-T1** | `WRITE_ACCEPTED` / non-throwing `send()` → `transportResult=SENT` | `SENT` only from sendto success (`SIGNAL_DATAGRAM_SENT`) |
| **INV-T2** | `SEND_FAILED` leaves in-flight | Failure clears transmission ownership |
| **INV-T3** | Obligation open + network restored still blocked | Restore → **retry eligible** (not “must fire now”) |
| **INV-T4** | Media restore can close / mask control intent | Keep layer isolation; media ≠ control recovered |

Field chain (frozen):

```text
WRITE_ACCEPTED → false SENT → ENETUNREACH → no retry
  → restore DISPATCH blocked (transport_in_flight)
  → media restores → control intent closed
```

### Desk anchors (code facts for boundary — not an impl license)

| Fact | Where |
|------|--------|
| `WRITE_ACCEPTED` logged **before** `sendto`; failure → `SIGNAL_DATAGRAM_SEND_FAILED` only; **no throw** | `UdpSignalingChannel.sendInternal` |
| Non-throwing `send()` → `transportResult=SENT` / `ReattachDispatchOutcome.SENT` | `TalkbackCoordinator.executeRecoveryReattachSend` |
| `SENT` → `phase=REATTACH_REQUESTED` + `reattachDeliveryState=TRANSPORT_SENT` | `ConferenceEdgeRecoveryController.applyReattachDispatchOutcome` |
| Later `DISPATCH_REATTACH` rejected when `phase == REATTACH_REQUESTED` | `rejectReason=transport_in_flight` |
| Today’s `ReattachDispatchOutcome.SEND_FAILED` path calls `enterFailedMediaResidency(... reattach_send_failed)` | **Hazard:** truthful A alone would enter this path (X2 / FAILED_MEDIA) — forbidden by INV-T2 |
| `REATTACH_MEDIA_ALREADY_LIVE` uses `hasReattachDeliveryEvidence` (`TRANSPORT_SENT` **or** `REMOTE_RECEIPT_ACKED`) | Case C secondary compound on **false** `TRANSPORT_SENT` |

---

## 3. Proposed ownership boundary

### Transport layer

**Owns:**

```text
send attempt
send result truth
in-flight lifecycle (transmission instance)
```

**Does not own:**

```text
retry policy / schedule
recovery completion
obligation close
```

### Recovery layer

**Owns:**

```text
obligation lifecycle
retry eligibility
deadline
reaction to transport SEND_FAILED (must keep obligation open; clear instance ownership)
```

**Does not own:**

```text
deciding whether the socket actually emitted bytes
```

(Recovery consumes transport result facts; it must not invent `SENT`.)

### Media layer

**Owns:**

```text
ICE / media availability
```

**Must not:**

```text
close control obligation as proof of control delivery
```

---

## 4. Minimal implementation seams

Seams only — **no code** in this plan.

### Seam A — Send result propagation

**Current:**

```text
send()
   |
   v
no exception
   |
   v
SENT
```

**Target:**

```text
reattach send path
   |
   +-- SENDTO_SUCCESS  → may mark SENT / TRANSPORT_SENT
   |
   +-- SEND_FAILED     → must NOT mark SENT
```

`SENT` = successful **local datagram submission** only (INV-T1).

**API discipline (failure-domain fence):**

- Prefer a **result-bearing** surface consumed by the reattach path (e.g. `Result` / boolean / dedicated send helper).
- Do **not** flip global `send()` to throw for all `SignalType` callers.
- Do **not** expand reliable-UDP / retry to non-reattach signals.

### Seam B — In-flight lifecycle

**Current:**

```text
false SENT → phase=REATTACH_REQUESTED
    |
restore wants DISPATCH
    |
rejectReason=transport_in_flight
```

**Target:**

```text
SEND_FAILED
    |
clear transmission ownership / in-flight
(do not remain in REATTACH_REQUESTED as if a datagram were outstanding)
```

`SEND_FAILED` is terminal for **this transmission instance**, not for the recovery obligation (INV-T2).

### Seam C — Retry eligibility

**Current:**

```text
obligation open + network restored + blocked (false in-flight)
```

**Target:**

```text
obligation open
+ prior transport SEND_FAILED (or equivalent)
+ network / route available again
=
retry eligible
```

**Not:**

```text
retry immediately
new ADR-0032 eligibility plane
retry storm / mandatory backoff policy in P0
```

```text
eligible ≠ scheduled
```

**P0 sufficiency note:** Field restore already *wants* `DISPATCH_REATTACH` on `ROUTE_CONVERGED`; it was blocked by false in-flight. If P0 clears instance ownership **and** does not terminalize the obligation on transport fail, existing restore-time dispatch may already exercise eligibility — P1 then only adds an **explicit** eligibility marker / schedule if desk proves the existing path is still insufficient. Do not invent a new scheduler in P0.

### Seam D — Media isolation (guardrail)

Ensure media-ready / `REATTACH_MEDIA_ALREADY_LIVE` paths do not invent delivery/receipt facts or close control solely because media is live (INV-T4).

**P2 = D-min ONLY (APPROVED 2026-08-08):** After INV-T1, `ENETUNREACH` must not produce `TRANSPORT_SENT`, so `hasReattachDeliveryEvidence` must not fire on the false-SENT compound. Desk/unit assert this for Case T4. Aligns ADR-0042 duty: false SENT ≠ successful delivery.

**D-strict (DEFERRED):** Narrow control-plane boundary so media live + local `TRANSPORT_SENT` alone cannot close control without ADR-0035 receipt facts. Deferred because it approaches receipt / X1 / completion authority (scope creep). **Re-open only** if after P0 field/desk still observes `TRANSPORT_SENT → CONTROL_BOUNDARY` bypass; then a **separate** D-strict review — not this plan’s P2.

---

## 5. Suggested slice order (plan only)

| Slice | Seams | Status | Proves |
|-------|-------|--------|--------|
| **P0** | A + B + **SEND_FAILED reaction alignment** | **FULLY FROZEN** (incl. Red-then-green) | INV-T1 + INV-T2; **no** `FAILED_MEDIA` solely from transport send fail |
| **P1** | C (only if needed after P0) | conditional | INV-T3 eligibility after restore |
| **P2** | **D-min ONLY** | **APPROVED** | INV-T4 false-SENT shortcut guard |
| — | D-strict | **DEFERRED** | residual Gate #3 only |

### P0 inseparability (critical)

```text
A alone (truthful SEND_FAILED)
  → today's applyReattachDispatchOutcome(SEND_FAILED)
  → enterFailedMediaResidency(reattach_send_failed)
  → NEW failure domain (FAILED_MEDIA / X2-adjacent)
  → REJECT: enlarges failure domain vs field Case A
```

Therefore P0 **must** include Recovery reaction to reattach `SEND_FAILED` via **existing transition guard correction ONLY** (no new recovery phase / completion state):

```text
clear in-flight / do not stay REATTACH_REQUESTED
keep obligation open (transport fail ≠ recovery fail)
leave restore-time DISPATCH eligible
MUST NOT enterFailedMediaResidency solely because sendto failed
```

**P0 allow / forbid (frozen 2026-08-08):**

```text
Allow:  transition guard correction · in-flight cleanup · send-failure classification · retry eligibility preserved
Forbid: new recovery phase · new completion state · X1 predicate change · receipt contract change
```

**Pass bar (desk/unit):**

```text
Case A: SEND_FAILED → NOT FAILED_MEDIA → obligation OPEN → retry eligible
Case B: SENDTO_SUCCESS → existing delivery flow unchanged
Case C: MEDIA_READY ≠ CONTROL_RECOVERED (unchanged; P2=D-min)
```

P0 alone removes the false-`SENT` / `transport_in_flight` compound **without** converting Case A into FAILED_MEDIA residency. P1 only if eligibility still missing. P2 is D-min only.

Do **not** merge P0 with X1, receipt protocol, or watchdog work.

### P0 seam freeze — consumer-primary (2026-08-08)

```text
PRIMARY:     transport result consumer
NOT PRIMARY: reattach recovery transition gate (do not fix locus)
```

Root cause is missing transport truth into recovery — not admission ignorance of SEND_FAILED.

**Consumer does only:**

1. SENT classification = sendto success only  
2. SEND_FAILED → clear in-flight / close this send instance / obligation remains OPEN  
3. Suppress `enterFailedMediaResidency(reattach_send_failed)` (transport fail ≠ media recovery fail)

**Consumer does not:** weaken or special-case `transport_in_flight` DISPATCH admission (gate semantics stay: protects a live send instance, not a historical failure).

```text
P0 untouched: recovery admission gate · X1 predicate · receipt contract · completion
```

### Retry ownership freeze — A (2026-08-08)

```text
Transport consumer:     produces send attempt result + in-flight lifecycle (facts only)
Recovery obligation:    owns retry eligibility + redispatch decision
eligible ≠ scheduled
New retry coordinator:  REJECTED
Consumer-scheduled retry (B): REJECTED
```

ADR-0042 answers “what was the send result?”, not “when to retry”. Obligation owner already exists; it was fed wrong transport state.

### SENT token freeze — Correct-in-place (2026-08-08)

```text
APPROVED:  Correct-in-place — SENT / TRANSPORT_SENT == SENDTO_SUCCESS only (INV-T1)
KEEP:      WRITE_ACCEPTED as observation only (not a delivery claim)
REJECTED:  New-field-migrate / parallel lasting fact field (two-truths problem)
```

**Scope ceiling (not a global log scrub):**

```text
Allowed:   reattach transport consumer path · remove false SENT emission
Not:       unrelated UDP/send paths · global logging migration · protocol changes
```

### Test strategy freeze — Sync-scoped + add (2026-08-08)

```text
APPROVED:  Sync-scoped + add
REJECTED:  Add-only (would leave Runtime truth ≠ Test truth)
```

**Modify (scoped):** reattach recovery transport path assertions — `SENT` implies SENDTO_SUCCESS evidence; `WRITE_ACCEPTED ≠ SENT`.

**Add:** ADR-0042 invariant regressions for Cases T1–T4 + field Case A (no FAILED_MEDIA on transport fail) / B (true in-flight gate remains) / C (restore → obligation owner may redispatch). Aligns §6 evidence plan.

**Not:** full-repo test migration · global log-contract scrub · ADR-0035 receipt rewrite · X1/X2 regression expansion.

### Merge proof freeze — Red-then-green (2026-08-08)

```text
APPROVED:  Red-then-green
REJECTED:  Green-only
```

Closes desk evidence chain: field RCA → runtime violation → failing assertion → fix → green. Green-only cannot distinguish “caught old violation” from “covered a new design”.

**Minimal red (one reattach consumer regression) on baseline must expose either:**

```text
SEND_FAILED → transportResult=SENT
  OR equivalent
SEND_FAILED → FAILED_MEDIA_RESIDENCY
```

**Not required for red:** full T1–T4 first-red · field re-run · X1-B · X2.

**After branch authorized:**

```text
Commit 1: minimal regression reproduction — expected RED on baseline
Commit 2: Correct-in-place fix — test GREEN
Follow-up: ADR-0042 invariant suite (Sync-scoped + add / T1–T4)
```

**Not:** red-test migration project · transport framework rewrite · global SENT audit · X1/X2 coupling.

Design grilling **closed**. No further design expansion required before branch authorization.

---

## 5a. Guard-only discipline (frozen)

Evidence shows a **transition bug** (wrong consumption / wrong transition allowed), not a state-model gap. Target semantics:

```text
obligation OPEN + transport attempt CLOSED + eligible for future dispatch
```

are expressible by separating obligation lifecycle from transmission-instance lifecycle (ADR-0042). New phases would force re-proof against completion / watchdog / X1 / ADR-0040 — **not justified**.

---

## 6. Regression evidence plan

Contract scenarios — not test names.

### Case T1 — Success send

```text
sendto success → SENT
```

### Case T2 — Unreachable

```text
sendto ENETUNREACH
→ SEND_FAILED
→ no SENT
→ no inFlight leak
→ obligation still open (not FAILED_MEDIA solely from transport fail)
```

### Case T3 — After restore

```text
SEND_FAILED
+ obligation open
+ network restored
→ retry eligible
```

(Does **not** require asserting immediate send in the first evidence bar.)

### Case T4 — Media shortcut guard

```text
MEDIA_READY ≠ CONTROL_RECOVERED
false TRANSPORT_SENT must not unlock REATTACH_MEDIA_ALREADY_LIVE
```

### Failure-domain non-enlargement matrix (must hold)

| Must still be true / must not newly appear | Why |
|--------------------------------------------|-----|
| Happy-path reattach still one truthful `SENT` on sendto OK | No success regression |
| Non-reattach `SignalType` send behavior unchanged | Scope ceiling |
| Transport `SEND_FAILED` ≠ auto `FAILED_MEDIA_RECOVERY` / X2 entry | INV-T2; X2 HOLD |
| Obligation not permanently consumed by one failed instance | INV-T3 |
| No mandatory immediate retry / retry storm in P0 | `eligible ≠ scheduled` |
| No ADR-0035 receipt protocol delta | Review Gate #1 |
| No X1 admission predicate delta | Review Gate #2 |
| Media restore alone does not prove control delivery | Review Gate #3 |

Field validation remains **PAUSED** until an explicit field gate is opened; desk/unit evidence for T1–T4 + matrix + Red-then-green is the default bar for P0/P1.

---

## 7. Explicit non-goals

```text
No:
- X1 admission changes
- receipt protocol changes (ADR-0035)
- watchdog tuning
- residency cleanup (X2)
- membership repair
- UI / presence
- expanding to all SignalTypes
- converting transport SEND_FAILED into FAILED_MEDIA residency
```

---

## 8. Review Gate (must all be NO)

Implementation Plan review asks **only** three questions:

| # | Question | Required answer | Plan mapping |
|---|----------|-----------------|--------------|
| 1 | Does this change ADR-0035 receipt contract? | **NO** | Touch list excludes receipt protocol; D-strict DEFERRED |
| 2 | Does this change X1 admission predicate? | **NO** | `ControlAdmissionPredicate` / X1 graph out of touch list |
| 3 | Does this let media recovery close control obligation early? | **NO** | **P2 = D-min APPROVED**; never authorize media-as-control-proof; D-strict DEFERRED |

If any answer is not NO → plan REJECT / revise; do not open impl branch.

---

## 9. Authorization ladder

```text
ADR-0042 ACCEPTED FOR IMPLEMENTATION REVIEW     ✅
Design grilling / boundary freeze               ✅ CLOSED
This plan                                       ✅ PLAN APPROVED
Implementation                                  ✅ COMPLETE on adr0042-p0-reattach-transport-truth
P0 implementation review                        ✅ APPROVED FOR MERGE REVIEW (2026-08-08)
Desk verification                               ✅ PASS (red→green + invariant suite)
Merge                                           ← NEXT (explicit)
Targeted field validation                       ← after merge; PAUSED until gate
D-strict                                        DEFERRED
X1-B                                            NOT ENTERED
```

**Branch forbid list (still):** X1/X1-B · ADR-0035 · receipt · completion · watchdog · X2 · UI/presence · global send() rewrite · retry coordinator.

**Impl review gates:** (1) red reproduces old violation ✅ (2) Correct-in-place changes truth not ownership ✅ (3) no failure-domain enlargement ✅

**Field acceptance (when opened — narrow only):**

```text
SEND_FAILED path:    no false SENT · no FAILED_MEDIA · obligation remains eligible
SENDTO_SUCCESS path: normal receipt path unchanged
```

Do not reopen X1-B. Do not announce field fix from desk alone.
---

## 10. One-line plan statement

> Minimal future work: make reattach send results truthful (Correct-in-place), clear in-flight on failure **without** routing transport fail into FAILED_MEDIA (consumer-primary, guard-only), leave retry eligibility with the obligation owner, prove with Red-then-green + Sync-scoped tests, and keep media from proving control delivery (P2=D-min) — without touching X1, receipt protocol, or watchdog.
