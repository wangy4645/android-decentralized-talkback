# ADR-0045: Post-obligation Failed Media Residency Clear Admission

## Status

**ACCEPTED** (2026-08-09) · **Grill Q1–Q8 ACCEPTED** · **Review clarifications applied** · **Phase 1 MERGED (PR #131)** · **Phase 2 MERGED (PR #133)** · **Phase 2.1 MERGED (PR #136)** · **Field Phase 2.1 PAUSED (no qualifying case)** · successor observation: [mobile-validation-successor-recovery-pending-observation.md](../analysis/mobile-validation-successor-recovery-pending-observation.md) · run card: [adr0045-field-run-card.md](../analysis/adr0045-field-run-card.md)

**Parents:**

- [ADR-0044](./0044-user-visible-connectivity-semantics-media-residency.md) — presentation for terminal residency (Accepted + Field PASS; does **not** own clear)
- [ADR-0030](./0030-presence-projection-contract.md) — `mediaUnavailable(P)` ⇔ failed-media residency (R30-P-7)
- [ADR-0038](./0038-recovery-completion-admission-contract.md) — completion success (**orthogonal**; must not absorb clear)
- [mobile-validation-failed-media-recovery-q1-ownership.md](../analysis/mobile-validation-failed-media-recovery-q1-ownership.md) — clear owner = Recovery (`markRecovered` / supersede); deadline does not clear residency
- [adr0044-field-verdict.md](../analysis/adr0044-field-verdict.md) — Field PASS; sticky DEGRADED while residency remains

```text
ADR-0045
Decision:              ACCEPTED
Model:                 Post-obligation residency clear admission
Implementation:
  Phase 1 Policy + I1 tests   MERGED (PR #131 · 617d4b8) · PASS
  Phase 2 Trigger             MERGED (PR #133 · 059dfb4) · PARTIAL (Field #1)
  Phase 2.1 Entry trigger     MERGED (PR #136 · 094082b) · Field PAUSED (no FAILED_MEDIA case)
  Field #1 (20260809-093047)  NOT PASS bilateral
    M03→M02                   PASS (deadline → CLEARED)
    M02→M03                   FAIL (FAILED_MEDIA + closed; trigger gap)
  Field #2 (20260809-094259)  NOT ADR-0045 case
    M03→M02                   PASS (clear still holds)
    M02→M03                   SYNCING — successor RECOVERY_PENDING (independent observation)
  Successor observation       Q1–Q3 TRACE COMPLETE
  Next                        governance only (keep / ADR / fix auth); do not force FAILED_MEDIA for 2.1
  Do not                      UVCP-hide SYNCING · absorb into ADR-0045

Primary invariant:
  residency clear ≠ completion success ≠ presentation projection

Does NOT reopen:
  WiFi Recovery Architecture
  ADR-0043 / RNA
  ADR-0038 completion predicate / markRecovered()
  obligation closure semantics
  ADR-0044 EndpointStatus mapping
  membership / control reconciliation
  Directed #5 / WiFi matrix expansion
  Q1–Q4 Policy contract (unchanged by Phase 2.1)
```

---

## Relation to ADR-0044

```text
ADR-0044  Presentation Semantics
  Q: FAILED_MEDIA + !recovering 应向用户表达什么？
  A: DEGRADED（≠ recovery bug / ≠ retry）

ADR-0045  Recovery Authority
  Q: Post-obligation FAILED_MEDIA residency 何时、由谁清除？
  A: RecoveryResidencyClearPolicy；GATE + E4 → CONNECTED
```

```text
Recovery truth
      │
      ▼
ADR-0045 clear admission
      │
      ▼
UVCP reads updated facts
      │
      ▼
ADR-0044 presentation projection
```

**Forbidden inversion:** ADR-0044 must not mutate recovery / residency / phase.

---

## Context

Field (ADR-0044 thin validation, `logs/adr0044-field-20260809-080841`):

```text
phase=FAILED_MEDIA_RECOVERY
recovering=false
ice=CONNECTED
receivePathLive=true
finalPresence=DEGRADED
```

Desk Q1 established clear **write** ownership on Recovery (`markRecovered` / supersede), and that `OBLIGATION_DEADLINE` closes obligation **without** clearing residency. Presentation correctly follows authority (ADR-0044).

Runtime gap: `RecoveryCompletionPolicy.markRecovered()` rejects when `obligationClosedAtMs != null`. After deadline, completion success is unavailable, yet residency can remain while media plane is usable — sticky `mediaUnavailable` → sticky DEGRADED.

```text
Completion success  ≠  Residency clear  ≠  Presentation projection
```

---

## Decision

### Primary invariant

```text
residency clear  ≠  completion success  ≠  presentation projection
```

### DQ1 — Ownership (Grill Q1=B)

**Post-obligation Residency Clear Admission** is owned by the **Recovery authority family**.

```text
Allowed:  Recovery-owned clear admission
Forbidden: UI / UVCP / ICE callback as writer / completion-policy reuse as clear
```

Recovery policies **share an authority boundary**, not a single lifecycle meaning:

```text
✗ RecoveryCompletionPolicy family clears all recovery states
✓ Two policies; shared boundary; distinct contracts
```

### Authority invariant

```text
No component outside RecoveryResidencyClearPolicy
may perform post-obligation failed-media residency clear.
```

Covers Controller, UVCP, UI, ICE callback, and any `if (iceConnected) phase = CONNECTED` shortcut under failed-media residency.

### DQ2 — Admission (Grill Q2=E4)

**GATE** (preconditions, not evidence):

```text
phase == FAILED_MEDIA_RECOVERY
obligationClosed == true
recovering == false
no successor / supersede in flight
same recovery lineage
```

**EVIDENCE** (authorizes Recovery-owned clear only — not auto-disappearance):

```text
iceState(P) == CONNECTED
AND
receivePathLive(P) == true
```

**Evidence = snapshot facts, not events.** Admission evaluates the **current** `iceState` / `receivePathLive` values at decision time. It does **not** require a new recovery-edge rising event (e.g. a fresh `ICE_RESTORED`). Field sticky DEGRADED exists precisely when ICE is **already** CONNECTED at / after deadline with no new edge event.

**Excluded evidence:** ICE alone · receivePath alone · `mediaRestored` alone · membership/control reconciliation · UI state · stable window (V1 deferred).

### DQ3 — Result (Grill Q3=T3)

```text
FAILED_MEDIA_RECOVERY  →  CONNECTED
```

`CONNECTED` here is the **Recovery-owned post-obligation clear result**, not a transport/ICE projection alias.

```text
Preserved:  obligationClosedAtMs, obligationCloseReason (e.g. OBLIGATION_DEADLINE)
Forbidden:  phase → RECOVERED
            RECOVERY_EDGE_RECOVERED
            closeObligation(RECOVERED)
            reopen obligation
            ADR-0038 predicate mutation
```

Audit fact (name may evolve; **not** ownership proof): `FAILED_MEDIA_RESIDENCY_CLEARED` (or equivalent).

### DQ4 — Writer (Grill Q4=P2)

```text
RecoveryCompletionPolicy
  → markRecovered() → RECOVERED   (ADR-0038 completion success)

RecoveryResidencyClearPolicy
  → clearFailedMediaResidencyPostObligation() → CONNECTED
     (post-obligation residency exit only)
```

Same authority family; different semantic contracts. Shared terminal mutation host. ClearPolicy **must not** become a general recovery FSM or health normalizer.

**V1 clear scope:** `FAILED_MEDIA_RECOVERY` only — **not** `FAILED_REQUIRES_USER_ACTION` (explicit reject list).

### DQ5 — Trigger vs admission (Grill Q5=G3)

**Separate layers:**

```text
Lifecycle event
      │
      ▼
Controller.tryAdmitResidencyClear()     ← evaluation trigger only
      │
      ▼
RecoveryResidencyClearPolicy            ← admission decision owner
      │
      ▼
phase mutation (CONNECTED)
```

```text
Lifecycle events trigger evaluation.
Policy decides admission.
Controller is never the decision owner.
```

**Do not write:** “ICE_RESTORED triggers clear.”  
**Do write:** an ICE / deadline / equivalent lifecycle event may **trigger evaluation**; ClearPolicy admits or rejects using GATE + snapshot E4.

`ConferenceEdgeRecoveryController` **orchestrates only** (assemble snapshot facts → invoke policy). **Must not** write phase for this exit.

**Evaluation triggers (V1 + Phase 2.1):**

```text
1. After OBLIGATION_DEADLINE close lifecycle response
   → tryAdmitResidencyClear()
   (MUST NOT call clear from inside closeObligation)

2. ICE restoration / equivalent recovery-edge lifecycle event
   while failed-media + obligationClosed
   → tryAdmitResidencyClear()

3. Phase 2.1 — enter FAILED_MEDIA_RECOVERY while obligation already closed
   → tryAdmitResidencyClear()
   (covers prior RECOVERED + SUPERSEDE → FAILED_MEDIA with no deadline-close
    and no ICE rising-edge; MUST NOT turn deadline into residency GC)
```

No timer polling. No fold into completion reevaluation. `receivePathLive` push seam deferred.
Do **not** make every deadline fire tryAdmit when obligation is already closed.

### DQ6 — Migration (Grill Q6=M1)

**Must migrate:** `onIceConnected` when `obligationClosed ∧ phase==FAILED_MEDIA_RECOVERY` — replace direct `phase=CONNECTED` with `tryAdmitResidencyClear()`.

**Must add:** post-deadline lifecycle → `tryAdmitResidencyClear()`.

**Unchanged:** `markRecovered` · `clearDebouncingSuspicion` · active recovery FSM · `FAILED_REQUIRES_USER_ACTION` auto-clear.

### DQ7 — Invariants (Grill Q7=I1)

**Authority (covers N2):** No component outside `RecoveryResidencyClearPolicy` may perform post-obligation failed-media residency clear.

**MUST FAIL**

| Id | Invariant |
| -- | --------- |
| N1 | ICE alone must not clear residency |
| N2 | Any `FAILED_MEDIA_RECOVERY` exit under this admission **must** originate from `RecoveryResidencyClearPolicy` (writer provenance; log name alone is insufficient) |
| N3 | Must not relax `markRecovered` `obligation_already_closed` guard to clear residency |
| N4 | `FAILED_REQUIRES_USER_ACTION` must not be auto-cleared by E4 |
| N5 | Clear must not emit completion success / mutate closeReason / reopen obligation |
| N6 | Enter `FAILED_MEDIA_RECOVERY` while obligationClosed + ICE connected + `!receivePathLive` → remain residency; no clear |

**MUST HOLD**

| Id | Invariant |
| -- | --------- |
| P1 | GATE ∧ snapshot E4 → ClearPolicy → `CONNECTED` + `mediaUnavailable=false` + closeReason unchanged |
| P1.a | Same admission **must not** produce `RECOVERED` |
| P2 | Deadline close with snapshot E4 already true → clear succeeds (no new ICE rising-edge required) |
| P2.1 | Enter `FAILED_MEDIA_RECOVERY` while obligation already closed + snapshot E4 → clear succeeds (Phase 2.1 entry trigger) |
| P3 | Deadline with E4 false; later lifecycle event triggers evaluation; snapshot E4 true → clear succeeds |

**MUST NOT TOUCH**

| Id | Invariant |
| -- | --------- |
| K1 | Debounce suspicion → `CONNECTED` unchanged |
| K2 | ADR-0038 completion unchanged |
| K3 | ADR-0044 projection unchanged (`mediaUnavailable ∧ !recovering` → DEGRADED until clear) |

**Writer seam:** terminal **phase mutation** writers ∈ `{RecoveryCompletionPolicy, RecoveryResidencyClearPolicy}`.

**Field (when authorized):** observe residency clear then presentation **leaves DEGRADED** — do **not** adjudicate as “recovery success” / require `DEGRADED→ONLINE` as pass criterion. Thin validation; no Directed #5.

---

## Implementation posture (when authorized)

```text
1. RecoveryResidencyClearPolicy + I1 tests
2. Then wire triggers (deadline post-close + ICE path migration)
3. Do not scatter admission into Controller writers first
```

Until Phase 2 review: wire **no** Controller triggers in the same change as Policy+tests.

---

## Non-goals

```text
No change to:
  ADR-0038 completion predicate
  ADR-0038 markRecovered()
  obligation closure semantics
  ADR-0044 EndpointStatus mapping
  membership / control reconciliation

Also out of scope:
✗ Obligation deadline budget enlargement
✗ ICE recovery FSM redesign / new phase enum (RESIDENCY_CLEARED deferred)
✗ UVCP vocabulary redesign
✗ Banner / retry / SUPERSEDE-as-clear / WiFi matrix / Directed #5
✗ Mesh M02↔M03 ICE CHECKING wedge (independent track)
✗ receivePathLive → Recovery push seam (G6 deferred)
✗ Stable-window hysteresis (E6 deferred)
```

---

## Considered options (rejected)

| Topic | Rejected | Why |
| ----- | -------- | --- |
| Clear ≡ completion (A) | Pollutes ADR-0038; forces sticky forever or completion retry | Q1 |
| Second domain writer (C) | Breaks single authority family | Q1 |
| Eternal DEGRADED (D) | Product fallback, not lifecycle default | Q1 |
| ICE / receivePath / mediaRestored alone | Forbidden single-fact authorization | Q2 |
| Post-close rising-edge required (E5) | Healthy-at-deadline never clears | Q2 |
| → RECOVERED (T1/T2) | Completion semantic pollution | Q3 |
| New `RESIDENCY_CLEARED` phase (T4) | FSM expansion without need | Q3 |
| Method on CompletionPolicy (P1) | Attracts markRecovered reuse | Q4 |
| Clear inside `closeObligation` | Mixes obligation owner with phase writer | Q5 |
| Auto-clear USER_ACTION (M4) | Redefines user-action terminal | Q6 |
| Amend 0044 / 0030 / 0038 (D2–D4) | Domain inversion or completion coupling | Q8 |

---

## Consequences

- **Positive:** Sticky post-deadline DEGRADED can lawfully end when media plane is usable, without claiming completion success; ADR-0044 remains presentation-only.
- **Negative:** Two Policy types in the Recovery family; terminal-writer invariant must name both; Controller ICE idle path must split failed-media vs other closed-obligation bookkeeping.
- **Neutral:** `CONNECTED` gains a second provenance (debounce clear vs residency clear); audits use clear fact + closeReason, not phase name alone.

---

## References

- Glossary: `Failed Media Residency`, `Post-obligation Residency Clear Admission` (`talkback/CONTEXT.md`)
- [ADR-0044](./0044-user-visible-connectivity-semantics-media-residency.md)
- [ADR-0030](./0030-presence-projection-contract.md) R30-P-7
- [ADR-0038](./0038-recovery-completion-admission-contract.md)
- [mobile-validation-failed-media-recovery-q1-ownership.md](../analysis/mobile-validation-failed-media-recovery-q1-ownership.md)
- [adr0044-field-verdict.md](../analysis/adr0044-field-verdict.md)
- Field evidence: `logs/adr0044-field-20260809-080841/`
