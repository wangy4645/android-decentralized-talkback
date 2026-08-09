# ADR-0047 Implementation Proposal Entry

**Status:** **OPEN** (2026-08-09) · **DP-ACCEPT** · **Runtime-Auth Q1–Q4 = F1** · **Code AUTHORIZED (Allow)** · **Merge until G1'**  
**Parent:** [0047-ordinary-recovery-post-defer-evaluability-contract.md](../adr/0047-ordinary-recovery-post-defer-evaluability-contract.md)  
**Observation:** [vfield-case-b-20260809-112330-media-online-obligation-pending.md](./vfield-case-b-20260809-112330-media-online-obligation-pending.md)

```text
Impl-Auth Q1 = I1 (owner explicit)
        |
        v
Planning / Design Proposal  ✅ AUTHORIZED
        |
        v
Design Decision Q1          ✅ C1 (N1+N2)
        |
        v
Design Decision Q2          ✅ B1' + V-desk' + V-field'
        |
        v
Design Decision Q3          ✅ C-Kmin + K5'
        |
        v
Design Decision Q4          ✅ C1'
        |
        v
DP-ACCEPT review            ✅ PASS
        |
        v
Runtime-Auth Q1 = RA1 ✅
Runtime-Auth Q2 = R2' ✅
Runtime-Auth Q3 = G1'+P1'–P3' ✅
Runtime-Auth Q4 = F1 ✅
        |
        v
Implementation (F1 Allow)   ✅ AUTHORIZED — merge until G1'
```

---

## Authorization triad (satisfied)

| Entry | Value |
|-------|--------|
| **Owner** | Explicit (session grant 2026-08-09 — Impl-Auth authorized) |
| **Proposal entry** | This document |
| **Validation entry** | Post-design: ordinary-recovery-path compliance — auditable **intent @ open** + **post-defer attribution manifest ≤ defer-exit** (B4' / P1'); **not** hollow residency (S4'); Case B class replay optional corroboration — **not** manufactured Sync fix proof |

---

## Goal (compliance, not “fix Sync”)

Make every **ordinary recovery obligation episode** carry an auditable **post-defer evaluability attribution contract** per ADR-0047 (A1'–O1').

```text
Goal:
  ordinary recovery obligation
    → evaluability class intent @ open (B4')
    → auditable post-defer attribution <= defer-exit (B4' / P1')
    → no post-defer hollow residency (S4')

Not goal:
  UVCP / SYNCING copy change
  ADR-0045 clear expansion
  ADR-0038 predicate change
  ADR-0046 successor contract merge
  “Recovery broken” narrative
  membership lifecycle redesign
  absorb M03→M01 side observation
```

---

## Frozen fences (unchanged)

```text
✗ amend ADR-0038 / 0045 / 0044 / 0046
✗ UVCP / EndpointStatus rewrite
✗ ICE auto-terminal as phase owner
✗ new RecoveryPolicy as default without Design Decision
✗ watchdog / timeout / retry numbers before Design Decision
✗ runtime patch before Design Decision accepted
✗ treat MEMBERSHIP_LEFT as attribution substitute (E2')
✗ DIAGNOSTIC_ONLY as provable attribution (P1')
```

**Producer boundary (default):** evaluability attribution binding/manifest remains **ConferenceEdgeRecoveryController** ordinary-recovery obligation seams — **unless** Design Decision explicitly re-opens ownership (default: do not). **Do not** reuse ADR-0046 successor-only markers as ordinary-path compliance without Design Decision.

**Sibling fence:** ADR-0046 implementation (#146) does **not** satisfy ADR-0047 ordinary-path compliance by itself.

---

## Design Decision Q1 — SEALED: Realization dimensions (N-family)

**Adjudication:** **C1 = N1 + N2**

```text
N1 ✅  RECOVERY_ATTEMPT_OPENED
           → auditable evaluability class intent

N2 ✅  defer-exit 前
           → auditable post-defer attribution manifest

=> B4' + S4' design closure
```

| Boundary confirm | Ruling |
|------------------|--------|
| Attribution owner | **Controller episode metadata** |
| New scheduler / global owner | **Rejected** |
| Manifest timing | **defer-exit class** constraint; event priority not chosen here |
| Reuse ADR-0046 contract | **Rejected** — sibling objects |
| New Recovery family / Policy | **Rejected** |

```text
intent exists + post-defer attribution manifests in time
        ⇒
no hollow residency (S4')

≠ terminal convergence guarantee
≠ SUCCESS / FAILED_MEDIA proof
≠ watchdog completion semantics
≠ timeout policy
```

### Q1 adjudication record

| Item | Result |
|------|--------|
| Design Q1 choice | **C1** |
| N1 | **Accepted** — intent binding @ ordinary obligation open |
| N2 | **Accepted** — post-defer manifest ≤ defer-exit |
| Rejected | **C2**, **C3**, **N3**, **N4** |
| Attribution owner | Controller episode metadata |

```text
Design Q1 sealed (C1) → Design Q2 OPEN
```

---

## Design Decision Q2 — SEALED: Boundary / validation (B-family + V')

**Adjudication:** **B1' + V-desk' + V-field'**

```text
ConferenceEdgeRecoveryController
        |
        +-- ordinary recovery open seam (N1)
        +-- defer-exit manifest seam (N2)
        +-- EdgeRecoveryRecord episode attribution metadata
```

| Item | Ruling |
|------|--------|
| **B1'** | **Accepted** — minimal Controller + record seams |
| **B2'** | Fallback upgrade only |
| **B3'** | **Rejected** |
| **V-desk'** | **Accepted** — intent @ open, manifest @ defer-exit, non-hollow; no SUCCESS/Sync/timeout |
| **V-field'** | **Accepted** — natural Case-B-class; post-defer ≠ attribution-less residency |
| New Recovery owner / Policy / global defer state | **Rejected** |
| Successor lifecycle in B1' | **Out of scope** |

### `scheduleWatchdog` (semantic fence)

```text
scheduleWatchdog  =  allowed manifest seam call relationship
                   ≠  ADR-0047 evaluator contract
                   ≠  timeout repair / faster recovery goal

Allowed:   carry attribution · trigger existing evaluability connection
Forbidden: timeout numbers as ADR goal · watchdog lifecycle redefine
```

### ADR-0046 isolation (confirmed)

```text
0046: ADMIT_SUCCESSOR → terminal convergence contract
0047: RECOVERY_ATTEMPT_OPENED → ordinary evaluability attribution

sibling · distinct markers · distinct contract semantics · no cross-compliance
```

### Q2 adjudication record

| Item | Result |
|------|--------|
| B-family | **B1'** |
| V-family | **V-desk' + V-field'** |
| Rejected | **B3'**, **V-skip** |
| B2' posture | Escalate only if B1' insufficient |

```text
Design Q2 sealed → Design Q3 OPEN
```

---

## Design Decision Q3 — SEALED: Episode attribution minimum semantics (K-family)

**Adjudication:** **C-Kmin + K5'**

```text
ordinary recovery episode
        |
        +-- K1' intent fact        (obligation open: evaluability class bound)
        +-- K3' attribution identity (owner class — mandatory)
        +-- K4' episode correlation  (edge, obligationGen, attemptId)
        +-- K2' post-defer manifest  (attribution exists <= defer-exit)
        +-- K5' defer-exit category    (audit class only; ≠ mechanism)

K6' ❌  DIAGNOSTIC_ONLY ≠ manifest
K7' ❌  0046 field reuse rejected
```

| Leg | Ruling |
|-----|--------|
| K1' + K2' | **Both required** — intent continuity + post-defer manifest closes B4' |
| K3' | **Mandatory** — owner class identity (not new authority / scheduler) |
| K4' | **Accepted** — existing episode keys; no global registry |
| K5' | **In minimum set** — category for audit; ≠ priority / FSM / timeout |
| K6', K7' | **Rejected** |

```text
category (K5') ≠ mechanism
```

### Q3 adjudication record

| Item | Result |
|------|--------|
| Design Q3 choice | **C-Kmin + K5'** |
| K-legs | **K1'–K5'** accepted |
| Rejected | **K6'**, **K7'**, **C-Klite**, **C-Kdiag** |

```text
Design Q3 sealed → Design Q4 OPEN
```

---

## Design Decision Q4 — SEALED: Design Proposal completion gate (C1' family)

**Adjudication:** **C1'** — Design Proposal completion gate **met**.

```text
Q1  C1 = N1 + N2              ✅
Q2  B1' + V-desk' + V-field' ✅
Q3  C-Kmin + K5'               ✅
        |
        v
C1' written package authorized
        |
        v
DP-ACCEPT review (not auto-pass)
```

| Item | Result |
|------|--------|
| Design Q4 choice | **C1'** |
| Supplemental packages | **None required** (C2'/C3'/C4' rejected) |
| DP-ACCEPT draft | **Authorized** — [adr0047-design-proposal-c1.md](./adr0047-design-proposal-c1.md) |
| Rejected | **C2'**, **C3'**, **C4'**, **C5'** |

```text
C1' complete  ≠  DP-ACCEPT
DP-ACCEPT     ≠  Runtime Authorization
Runtime Auth  ≠  Code merge
```

```text
Design Q4 sealed → DP-ACCEPT review → Runtime Authorization grill
```

**C1' package:** [adr0047-design-proposal-c1.md](./adr0047-design-proposal-c1.md) — **DP-ACCEPT** (C1' complete). Next: **Runtime Authorization grill**.

---

## DP-ACCEPT — sealed (2026-08-09)

| Check | Result |
|-------|--------|
| §1–§7 covers Q1–Q4 | ✅ PASS |
| N1/N2 closes B4' | ✅ PASS |
| K1'–K5'; K6'/K7' rejected | ✅ PASS |
| B1' blast radius | ✅ PASS |
| V-desk'/V-field' boundaries | ✅ PASS |
| ADR-0046 orthogonality | ✅ PASS |
| No runtime/code/PR/timeout auth | ✅ PASS |

```text
DP-ACCEPT PASS
        |
        v
Runtime Authorization grill OPEN
        |
        +-- still ≠ code / PR / merge
```

---

## Runtime Authorization grill

**Role:** Pin runtime implementation **phase**, **scope**, **validation gate**, and **code Allow** — in order.  
**RA1 does not authorize:** code · PR · merge · watchdog · timeout · Sync · UVCP · ADR-0044/0038/0045 changes.

### Locked prerequisites

```text
ADR-0047 ACCEPTED ✅
Design Q1–Q4 + C1' ✅
DP-ACCEPT ✅
Runtime-Auth Q1 = RA1 ✅
Runtime-Auth Q2 = R2' ✅
Runtime-Auth Q3 = G1'+P1'–P3' ✅
```

### Frozen boundary (continues through Q4)

```text
May continue to adjudicate:
  ordinary recovery runtime seam
  N1 intent binding
  N2 defer-exit manifest
  EdgeRecoveryRecord semantic carrier
  V-desk' / V-field' gate

Still forbidden:
  ADR-0046 successor merge
  ADR-0044 SYNCING projection change
  ADR-0038 completion predicate change
  ADR-0045 clear gate change
  new Policy / FSM / Recovery owner
  watchdog / timeout product-semantics redefine
  ICE / UVCP as terminal writer
```

---

### Runtime-Auth Q1 — SEALED (2026-08-09)

**Adjudication:** **RA1 — Authorize**

> Enter runtime implementation **phase**; scope / validation / change boundary pinned by Q2+ before code.

```text
RA1 = Runtime Authorization OPEN (implementation adjudication flow)
    ≠ Code authorized
    ≠ PR authorized
    ≠ Merge authorized
```

| Item | Result |
|------|--------|
| Runtime-Auth Q1 | **RA1** |
| Rejected | **RA2**, **RA3** |

```text
Runtime-Auth Q1 sealed → Q2 scope
```

---

### Runtime-Auth Q2 — SEALED (2026-08-09)

**Adjudication:** **R2' — Ordinary open + defer-exit manifest**

```text
R2' = N1 open seam + N2 defer-exit manifest seam
    under ConferenceEdgeRecoveryController (ordinary paths only)
    + EdgeRecoveryRecord K1'–K5'
    + scheduleWatchdog as existing call relationship only
```

| Rationale | |
|-----------|--|
| R1 insufficient | N1-only leaves Case B post-defer hollow (S4' gap) |
| R2' aligns design | Closes B4' intent @ open + manifest ≤ defer-exit |
| R3 rejected | Exceeds B1' blast radius |

**R2' allows:**

```text
ConferenceEdgeRecoveryController
  ordinary recovery open seam (N1)
  defer-exit manifest seam (N2)
EdgeRecoveryRecord
  K1' K2' K3' K4' K5'
scheduleWatchdog
  existing connection point / call relationship only
```

**R2' forbids:**

```text
✗ watchdog timeout semantics change
✗ new recovery policy / FSM / lifecycle owner
✗ successor path reuse / ADR-0046 CONTRACT_BOUND reuse
✗ UVCP / Sync projection change
✗ completion predicate change
```

| Item | Result |
|------|--------|
| Runtime-Auth Q2 | **R2'** |
| Rejected | **R1**, **R3** |

```text
Runtime-Auth Q2 sealed → Q3 validation / merge gate OPEN
```

---

### Runtime-Auth Q3 — SEALED (2026-08-09)

**Adjudication:** **G1' + P1'–P3'**

#### G1' — merge gate

```text
Merge blocked until:
  R2' scope implementation
        +
  V-desk' D1–D7 PASS
        +
  PR boundary check (P1'–P3')

V-field' = post-merge observation track — NOT merge blocker
```

| Rejected | Reason |
|----------|--------|
| **G2'** | Field as merge gate → manufactured stimulus; conflates ADR-0047 with Sync fix |
| **G3'** | Weak gate → no proof of K1'–K5'; breaks B4'/S4' |

**D1–D7 prove:**

```text
RECOVERY_ATTEMPT_OPENED → intent attribution
        → defer → defer-exit → post-defer manifest attribution
```

**D1–D7 do not require:** SUCCESS · FAILED_MEDIA · Sync duration · timeout tuning

#### P1'–P3' — PR acceptance

| Id | Ruling |
|----|--------|
| **P1'** | PR explains N1+N2+K1'–K5'+B4'/S4' closure; diff **only R2'** seams |
| **P2'** | No ADR-0046 successor · no 0044 SYNCING · no 0038 completion · no 0045 clear · no UVCP |
| **P3'** | `scheduleWatchdog` touch = existing call relationship + evaluability attribution only — not timeout fix / retry policy / global watchdog redesign |

| Item | Result |
|------|--------|
| Runtime-Auth Q3 | **G1' + P1'–P3'** |
| Merge gate | **G1'** |
| Rejected | **G2'**, **G3'** |

```text
Runtime-Auth Q3 sealed → Q4 final Allow/Forbid OPEN
```

---

### Runtime-Auth Q4 — SEALED (2026-08-09)

**Adjudication:** **F1 — Accept Allow/Forbid list**

> Authorize runtime code change **within F1 Allow**; merge blocked until **G1'**.

```text
Code change:  AUTHORIZED within Allow ✅
Merge:        blocked until G1' (D1–D7 + P1'–P3') ⏳
V-field':     post-merge observation track
```

| Item | Result |
|------|--------|
| Runtime-Auth Q4 | **F1** |
| Code Allow | **AUTHORIZED within Allow** |
| Rejected | **F2**, **F3** |

**Not authorized:** Sync UI fix · timeout repair · recovery policy redesign · merge without G1'

```text
Runtime-Auth grill COMPLETE (Q1–Q4 = F1)
```

---

### Runtime-Auth adjudication record

| Item | Result |
|------|--------|
| Runtime-Auth Q1 | **RA1** ✅ |
| Runtime-Auth Q2 | **R2'** ✅ |
| Runtime-Auth Q3 | **G1' + P1'–P3'** ✅ |
| Runtime-Auth Q4 | **F1** ✅ |
| Merge gate | **G1'** — R2' + D1–D7 + P1'–P3' |
| Direct code / PR | **AUTHORIZED within F1 Allow** (merge blocked until G1') |

```text
Runtime-Auth Q4 sealed → code within Allow; V-field' post-merge
```

---

## Design Decision — historical archives

> How shall B4' intent + manifest and S4' anti-hollow floor be **realized** on ordinary recovery edges without vacuous obligation residency?

**Superseded for status by Design Q1 OPEN above.**

```text
Still NOT authorized until Design stack + Runtime Auth:
  concrete mechanism
  budgets / watchdog numbers
  runtime code / PR
```

---

## Validation sketch (for later Design Q2+)

| Track | Intent |
|-------|--------|
| **V-desk'** | Unit/desk proof: after `RECOVERY_ATTEMPT_OPENED`, intent binding auditable; after defer-exit, post-defer attribution manifest auditable; defer/settling does not yield S4' hollow residency. **Not** forced SUCCESS; **not** Sync UI proof. |
| **V-field'** | Passive or natural ordinary-recovery episode with contract markers + non-hollow post-defer attribution **or** bounded Case-B-class replay with pre-declared disposition. **Not** Directed #5; **not** WiFi flap for maturity upgrade. |

---

## Stop / escalate

```text
Stop if design requires amending 0038 / 0045 / 0044 / 0046
Stop if design makes ICE/UVCP a evaluability attribution writer
Stop if design treats external close as S4' satisfaction (E2' violation)
Stop if design merges successor + ordinary contract objects
Escalate B1' → B2' only with explicit decision
```

---

## Design Decision record (in progress)

| Stage | Status |
|-------|--------|
| Design Q1 | **C1 = N1 + N2** ✅ |
| Design Q2 | **B1' + V-desk' + V-field'** ✅ |
| Design Q3 | **C-Kmin + K5'** ✅ |
| Design Q4 | **C1'** ✅ |
| C1' package | [adr0047-design-proposal-c1.md](./adr0047-design-proposal-c1.md) — **DP-ACCEPT** ✅ |
| Runtime-Auth Q1 | **RA1** ✅ |
| Runtime-Auth Q2 | **R2'** ✅ |
| Runtime-Auth Q3 | **G1' + P1'–P3'** ✅ |
| Runtime-Auth Q4 | **F1** ✅ |
| Merge gate | **G1'** — R2' + D1–D7 + P1'–P3' |
| Direct code / PR | **AUTHORIZED within F1 Allow** (merge blocked until G1') |
