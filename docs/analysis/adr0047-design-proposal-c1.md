# ADR-0047 Design Proposal (C1' Gate Package)

**Status:** **ACCEPTED — C1' COMPLETE** (2026-08-09) · **DP-ACCEPT** · **Runtime-Auth Q1–Q4 = F1** · **Code AUTHORIZED (Allow)** · **Merge until G1'**  
**Parent ADR:** [0047-ordinary-recovery-post-defer-evaluability-contract.md](../adr/0047-ordinary-recovery-post-defer-evaluability-contract.md)  
**Proposal entry:** [adr0047-implementation-proposal-entry.md](./adr0047-implementation-proposal-entry.md)  
**Observation:** [vfield-case-b-20260809-112330-media-online-obligation-pending.md](./vfield-case-b-20260809-112330-media-online-obligation-pending.md)

```text
Design Decision stack:
  Q1 C1 = N1 + N2           ✅
  Q2 B1' + V-desk' + V-field' ✅
  Q3 C-Kmin + K5'            ✅
  Q4 C1'                     ✅
  DP-ACCEPT                  ✅

Runtime Authorization:
  Q1 RA1 ✅
  Q2 R2' ✅
  Q3 G1'+P1'–P3' ✅
  Q4 F1 ✅ — code authorized within Allow; Z1–Z11 forbid

Code / PR:                   AUTHORIZED within F1 Allow
Merge:                      blocked until G1' (D1–D7 + P1'–P3')
```

```text
C1' complete  ≠  DP-ACCEPT
DP-ACCEPT     ≠  Runtime Authorization
Runtime Auth  ≠  Code merge
```

---

## 1. Objective / Scope

Satisfy ADR-0047 Decision YES under **B1'**: every **ordinary recovery obligation episode** carries an auditable **post-defer evaluability attribution contract** (A1'–O1').

### In scope

```text
ADR-0047
ordinary recovery
post-defer evaluability attribution
```

| Semantic | Meaning |
|----------|---------|
| **Ordinary recovery** | Non-successor obligation episodes (`RECOVERY_ATTEMPT_OPENED` / `UPSERT_EDGE` class paths) |
| **Post-defer evaluability attribution** | Auditable evaluability owner class bound at open and manifested ≤ defer-exit (B4' / P1') |
| **Anti-hollow floor** | No `obligationOpen` + post-defer + no auditable attribution (S4') |

### Out of scope (explicit non-goals)

```text
✗ terminal convergence (ADR-0046)
✗ completion admission / SUCCESS predicate (ADR-0038)
✗ Sync projection / UVCP / EndpointStatus (ADR-0044)
✗ post-obligation failed-media residency clear (ADR-0045)
✗ media recovery policy redesign
✗ watchdog / timeout / retry numbers
✗ membership lifecycle redesign
✗ “fix Sync duration” as design goal
```

This document is the **C1' written design closure**. It consolidates Design Q1–Q4. It does **not** authorize code, PR, timeout numbers, or retry policy.

---

## 2. N1 / N2 Seam

### 2.1 Semantic lifecycle (B4')

```text
RECOVERY_ATTEMPT_OPENED
        |
        v
evaluability intent attribution          (N1 — B4' open leg)
        |
        v
defer lifecycle
        |
        v
defer-exit category                      (K5' — audit class only)
        |
        v
post-defer attribution manifest          (N2 — B4' deadline leg)
```

| Seam | Leg | Normative rule |
|------|-----|----------------|
| **N1** | B4' open | At ordinary obligation open, episode **must** auditably carry **evaluability class intent** bound to that episode. |
| **N2** | B4' manifest | No later than **defer-exit**, episode **must** auditably carry **post-defer evaluability attribution manifest**. |

```text
intent only   → defer-exit may leave no provable owner (Case B)
manifest only → attribution appears without open intent (B4' violation)
both          → B4' closed; S4' floor satisfiable
```

### 2.2 Seam location (design; not API)

| Item | Value |
|------|--------|
| Primary authority | `ConferenceEdgeRecoveryController` |
| N1 seam | Ordinary obligation open — `openNewRecoveryObligation` / `upsertEdge` class paths producing `RECOVERY_ATTEMPT_OPENED` |
| N2 seam | Defer-exit class — `closeNegotiationIntent` and kin defer-exit transitions on ordinary episodes |
| Episode carrier | `EdgeRecoveryRecord` episode metadata (concept object) |
| Manifest call relationship | `scheduleWatchdog` may participate as **existing** evaluability connection — **not** the contract definition, **not** timeout repair |

### 2.3 What N1/N2 are not

```text
✗ proving SUCCESS / FAILED_MEDIA will occur
✗ choosing defer-exit event priority or FSM branch (K5' is category stamp only)
✗ UVCP / presentation change
✗ amending ADR-0038 / 0045 / 0044
✗ reusing ADR-0046 successor terminal convergence markers as ordinary compliance
✗ MEMBERSHIP_LEFT as attribution substitute (E2')
```

**Ownership:** intent binding and post-defer manifest remain **Controller episode metadata** accountability. No new Policy type. No global recovery scheduler.

---

## 3. K-family Minimum Semantic Object

On each **ordinary recovery episode**, the minimum auditable attribution object (`EdgeRecoveryRecord` concept) carries:

| Id | Semantic leg | Meaning |
|----|--------------|---------|
| **K1'** | **Intent-bound fact** | Evaluability class intent bound at obligation open (N1 / B4' open). |
| **K2'** | **Post-defer manifest fact** | Post-defer evaluability attribution manifested ≤ defer-exit (N2 / B4' deadline). |
| **K3'** | **Attribution identity** | Identifiable **evaluability owner class** — mandatory; not optional debug text. |
| **K4'** | **Episode correlation** | Facts bind to **(edge key, obligationGeneration, recoveryAttemptId)** — existing episode identity. |
| **K5'** | **Defer-exit category** | Manifest records defer-exit **class** (e.g. SUPERSEDE / capability-rise / negotiation-close category) for audit and field analysis. |

```text
Composite: C-Kmin + K5' = K1' + K2' + K3' + K4' + K5'
```

### 3.1 Rejected substitutes

| Id | Rejection |
|----|-----------|
| **K6'** | `OWNERSHIP_LOST` / `DIAGNOSTIC_ONLY` alone **≠** manifest — diagnostics answer “what happened,” not “who owns evaluability.” |
| **K7'** | `successorTerminalConvergenceContractBound` (0046) **≠** ordinary manifest — sibling lifecycles. |

### 3.2 Explicit fence

```text
K-family in this document:
  audit semantics only

NOT in this document:
  Kotlin field names
  types / getters
  log marker strings
  API surface requirements
```

```text
category (K5') ≠ mechanism
category (K5') ≠ priority rule
category (K5') ≠ timeout trigger
```

### 3.3 Design rule R-N2 (semantic; existing authority only)

> **R-N2:** For every ordinary episode with N1 intent bound, while phase remains actively recovering after defer-exit, **Recovery Controller must retain episode-attributed post-defer evaluability** auditable under K2'–K5', satisfying P1' and S4'.

**C1' does not pick** concrete arming API, field names, or whether manifest attaches at defer-exit vs via `scheduleWatchdog` re-entry. That selection is **Runtime Authorization / implementation detail**, provided it stays inside B1' and satisfies R-N2 without amending 0038/0045/0044/0046.

---

## 4. Blast Radius (B1')

### In scope (when Runtime Authorization granted)

```text
ConferenceEdgeRecoveryController
  ordinary recovery open seam (N1)
  defer-exit manifest seam (N2)
EdgeRecoveryRecord
  episode attribution semantics (K1'–K5')
```

Call relationships into **existing** Controller evaluability seams (`scheduleWatchdog`, completion evaluation, failed-media entry) — no new Policy type.

### Out of scope

```text
✗ new RecoveryPolicy / lifecycle owner type
✗ global recovery rewrite / attribution registry
✗ watchdog redesign / timeout change / retry numbers
✗ UVCP / presentation / Sync UI
✗ ADR-0038 / 0045 / 0044 / 0046 text or gate changes
✗ successor admission path (ADR-0046) — separate B1' on Controller, separate contract
✗ negotiation gate semantics redesign beyond B1' seam needs
```

**B2' upgrade:** only if implementation discovery proves B1' insufficient — explicit re-decision required.

---

## 5. Non-goals and frozen domains

| Domain | Stance |
|--------|--------|
| ADR-0038 completion success predicate | **Frozen** |
| ADR-0045 residency-clear GATE | **Frozen** — do not expand as Sync fix |
| ADR-0044 / UVCP / EndpointStatus | **Frozen** — SYNCING may remain correct while obligation open |
| ADR-0046 successor terminal convergence | **Frozen** — sibling; no field reuse for ordinary compliance |
| Timeout / retry / budget values | **Not defined here** |
| Runtime code / PR | **Not authorized** until Runtime Authorization |

---

## 6. Validation posture

### 6.1 V-desk'

Desk/unit cases prove **attribution presence + obligation integrity**, not forced outcomes.

| ID | Case | Pass criteria |
|----|------|---------------|
| **D1** | Ordinary `RECOVERY_ATTEMPT_OPENED` | Auditable N1 intent binding for gen/attempt (K1' + K4') |
| **D2** | Open-time attribution identity | Owner class identifiable at open (K3') — not anonymous episode |
| **D3** | Defer to settling / defer-exit class | Post-defer manifest auditable ≤ defer-exit (K2' + K5'); episode not S4'-hollow |
| **D4** | Case-B-shaped negative | Field #2-class defer without external leave must **not** accept steady state: open obligation + no auditable post-defer attribution |
| **D5** | 0046 orthogonality | `successorTerminalConvergenceContractBound` does **not** satisfy ordinary K2' manifest |
| **D6** | Diagnostic substitute negative | `DIAGNOSTIC_ONLY` / `OWNERSHIP_LOST` alone does **not** pass D3 |
| **D7** | Orthogonality smoke | No UVCP change required; no ADR-0045 clear required; no forced SUCCESS |

**V-desk' does not verify:**

```text
✗ SUCCESS outcome
✗ FAILED_MEDIA outcome
✗ Sync duration / UI ONLINE timing
✗ watchdog timeout values
```

### 6.2 V-field'

After runtime lands (separate auth):

| Observe | Allow |
|---------|-------|
| Natural ordinary recovery episodes | Contract markers + non-hollow post-defer attribution |
| Case-B-class behavior | Pre-declared disposition replay / corroboration |

**V-field' does not allow:**

```text
✗ directed fault injection for maturity upgrade
✗ manufactured WiFi flap / FAILED_MEDIA for evidence farming
✗ Directed #5
✗ “Sync fixed” as field pass criterion
```

---

## 7. ADR-0046 separation

```text
ADR-0046:
  successor admission
        |
        v
  terminal convergence contract
  (M1 bind @ ADMIT_SUCCESSOR; M2 defer non-hollow)


ADR-0047:
  ordinary recovery
        |
        v
  post-defer evaluability attribution
  (N1 intent @ open; N2 manifest ≤ defer-exit)
```

| Property | Ruling |
|----------|--------|
| Relationship | **Sibling** — same Controller, distinct lifecycles |
| Contract objects | **Independent** — no substitution |
| Field reuse | **Rejected** (K7') |
| Cross-compliance | 0046 implementation does **not** satisfy 0047 ordinary path |
| Same symptom | `finalPresence=SYNCING` may appear in both — **different route** |

```text
Same UI symptom ≠ same lifecycle
No merge · no mutual amend · no shared implementation authorization
```

---

## 8. C1' checklist

| # | Requirement | This doc |
|---|-------------|----------|
| 1 | Objective / scope (ordinary post-defer attribution) | §1 |
| 2 | N1/N2 seam + B4' lifecycle | §2 |
| 3 | K1'–K5' minimum semantic object | §3 |
| 4 | Blast radius B1' | §4 |
| 5 | Non-goals / frozen domains | §5 |
| 6 | V-desk' + V-field' posture | §6 |
| 7 | ADR-0046 separation | §7 |
| — | Code / PR / timeout numbers / schema / API | **Excluded** ✅ |

---

## 9. Gate status

```text
Design Q1–Q4: SEALED ✅
C1' package:  ACCEPTED (DP-ACCEPT 2026-08-09) ✅
Runtime Auth: Q1–Q4 = F1 ✅ — code authorized within Allow
        |
        v
Implementation under B1' + R-N2 (merge blocked until G1')
        |
        v
V-field' post-merge observation (separate track)
```

**Adjudication:** **F1** — Runtime code change authorized within Allow; merge until G1'.

### DP-ACCEPT review record (2026-08-09)

| Check | Result |
|-------|--------|
| §1–§7 covers Q1–Q4 | ✅ PASS |
| N1/N2 closes B4' | ✅ PASS |
| K1'–K5' consistent; K6'/K7' rejected | ✅ PASS |
| B1' blast radius | ✅ PASS |
| V-desk'/V-field' boundaries | ✅ PASS |
| ADR-0046 orthogonality | ✅ PASS |
| No runtime authorization in package | ✅ PASS |

**Frozen confirmations:**

```text
scheduleWatchdog = existing seam reference only
                 ≠ timeout repair / recovery redesign / convergence mechanism

K1'–K5' = audit semantic object
        ≠ schema / API / marker spec / new state machine

Case B = S4' violation (obligationOpen + post-defer + no attribution)
       ≠ ICE / UI / completion / ADR-0046 failure

V-desk'/V-field' prove intent + manifest + auditable attribution
                do not require SUCCESS / FAILED_MEDIA / shorter Sync
```
