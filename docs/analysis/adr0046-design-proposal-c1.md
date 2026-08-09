# ADR-0046 Design Proposal (C1' Gate Package)

**Status:** **ACCEPTED — C1' COMPLETE** (2026-08-09) · **DP-ACCEPT** · **Runtime-Auth Q1–Q4 = F1** · **Code AUTHORIZED (Allow)** · **Merge until G1'**
**Parent ADR:** [0046-successor-admission-terminal-convergence-contract.md](../adr/0046-successor-admission-terminal-convergence-contract.md)  
**Proposal entry:** [adr0046-implementation-proposal-entry.md](./adr0046-implementation-proposal-entry.md)  
**Observation:** [mobile-validation-successor-recovery-pending-observation.md](./mobile-validation-successor-recovery-pending-observation.md)

```text
Design Decision stack:
  Q1 M1+M2 ✅
  Q2 B1' + V-desk'/V-field' ✅
  Q3 C1' ✅
  DP-ACCEPT ✅

Runtime Authorization:
  Q1 RA1 ✅
  Q2 S2' ✅
  Q3 G1'+P1'–P3' ✅
  Q4 F1 ✅ — code authorized within Allow; Z1–Z10 forbid
```

---

## 1. Purpose

Satisfy ADR-0046 Decision YES under **B1'**: every `ADMIT_SUCCESSOR` binds a provable terminal convergence contract (M1), and defer/`NEGOTIATION_SETTLING` cannot leave that contract hollow (M2).

This document is the **C1' written design closure**. It does **not** authorize code, PR, timeout numbers, or retry policy.

---

## 2. M1 — Contract binding seam (admission)

### 2.1 Seam location

| Item | Value |
|------|--------|
| Primary function | `ConferenceEdgeRecoveryController.admitSuccessorObligationEpisode(...)` |
| Episode open | `openNewRecoveryObligation(..., phase=RECOVERY_PENDING, trigger=ADMIT_SUCCESSOR_OBLIGATION_EPISODE)` |
| Audit marker (existing) | log `ADMIT_SUCCESSOR_OBLIGATION_EPISODE` (+ `RECOVERY_SUCCESSOR_STARTED`) |
| Authority | Recovery Controller (observation Q1 / B1) |

### 2.2 Binding meaning (normative design intent)

At successful return from `admitSuccessorObligationEpisode`, the new obligation episode **must** be attributable as carrying ADR-0046 terminal convergence contract identity:

```text
ADMIT_SUCCESSOR_OBLIGATION_EPISODE
        |
        v
episode (gen/attempt) exists
        |
        v
contract identity auditable (P1')
        |
        v
non-purely-external convergence obligation class (S1')
```

**Ownership:** binding is owned by Recovery Controller at admission. CompletionPolicy family remains the writer seam for SUCCESS (`markRecovered`); failed-media residency entry remains Controller-owned (`enterFailedMediaResidency` and kin). No new Policy type (B1').

### 2.3 What M1 is not

```text
✗ proving SUCCESS will occur
✗ UVCP / presentation change
✗ amending ADR-0038 predicate text
✗ SUCCESSOR_REPLACED satisfying prior episode (X1')
```

---

## 3. M2 — Non-hollow obligation under defer / settling

### 3.1 Field-shaped gap (design input, not mechanism)

Observed (Field #2 class):

```text
admitSuccessor (often ICE_RESTART_ONLY path)
  → resolveMediaActionOwner / issueBoundedIceRestart
  → gate DEFER (NEGOTIATION_SETTLING)  [INV-NEG-004: phase/watchdog unchanged]
  → INV-REC-023: deferred must not schedule attempt clock at defer
  → neither markRecovered nor FAILED_MEDIA path runs
  → only LEAVE/CANCEL observed as close
```

That is hollow relative to S1': contract/episode open, but no self-driven evaluable exit retained.

### 3.2 Design rule R-M2 (semantic; existing authority only)

> **R-M2:** For every successor episode bound under M1, while phase remains actively recovering, **Recovery Controller must retain episode-attributed evaluability** toward a non-purely-external terminal in the referenced set (SUCCESS / FAILED_MEDIA / FAILED_TERMINAL), including across `DeferredReason.NEGOTIATION_SETTLING`.

Evaluability means one of the following **existing** Controller-owned capabilities remains attributed to that gen/attempt:

| Existing capability (inventory) | Role |
|---------------------------------|------|
| Completion evaluation → `RecoveryCompletionPolicy.markRecovered` | SUCCESS-class exit |
| Controller `enterFailedMediaResidency` (and existing failed-media triggers) | FAILED_MEDIA-class exit |
| Existing final-evaluation / attempt-lifecycle seams already used on non-deferred beginRecovery-class paths | keep episode evaluable under Controller |

**Wakeup / drain** (`NEGOTIATION_CAN_EXECUTE`, deferred intent) may participate as **re-entry into** Controller evaluation; they are **not** terminal writers and must not become the sole “hope” without a Controller-owned evaluable outcome class.

### 3.3 Design constraint vs INV-REC-023 / INV-NEG-004

```text
Must respect:
  INV-NEG-004 — DEFER does not fake dispatch success / must not pretend media action executed
  INV-REC-023 — deferred path must not falsely claim post-dispatch attempt clock

Must achieve:
  R-M2 — episode still has Controller-attributed evaluability (S1' non-hollow)
```

**C1' does not pick** the concrete arming API, budget numbers, or whether evaluability is attached at admit-time vs at a defined post-defer Controller seam. That selection is **Runtime Authorization / implementation detail**, provided it stays inside B1' and satisfies R-M2 without amending 0038/0045/0044.

### 3.4 Explicit rejection (still)

```text
✗ ICE callback writes phase / closes obligation
✗ UVCP hides SYNCING / forces ONLINE
✗ LEAVE/CANCEL as sole satisfiers of S1'
✗ SUCCESSOR_REPLACED as prior S1' satisfaction (X1')
✗ new SuccessorPolicy / new lifecycle type (B3)
```

---

## 4. Non-goals and frozen domains

| Domain | Stance |
|--------|--------|
| ADR-0038 completion success predicate | **Frozen** — do not amend |
| ADR-0045 residency-clear GATE | **Frozen** — do not expand as Sync fix |
| ADR-0044 / UVCP / EndpointStatus | **Frozen** — SYNCING may remain correct while recovering |
| M03→M01 RECONNECTING | **Isolated** — not in this proposal |
| Timeout / retry / budget values | **Not defined here** (C1' exclusion) |
| Runtime code / PR | **Not authorized** until Runtime Authorization |

---

## 5. Blast radius (B1')

**In scope for a future runtime change (when authorized):**

- `ConferenceEdgeRecoveryController` — `admitSuccessorObligationEpisode` and its immediate attempt-lifecycle / defer-evaluability connection seams
- Call relationships into existing `RecoveryCompletionPolicy` / Controller failed-media entry (no new Policy type)

**Out of scope:**

- New Policy / lifecycle types
- UVCP / presentation
- ADR-0038/0045/0044 text or gate changes
- Negotiation gate semantics redesign beyond what B1' seam requires for R-M2

**B2 upgrade:** only if implementation discovery proves B1' insufficient — explicit re-decision required.

---

## 6. V-desk' case list (required by C1')

Desk/unit cases prove **contract presence + obligation integrity**, not forced SUCCESS everywhere.

| ID | Case | Pass criteria |
|----|------|---------------|
| **D1** | `ADMIT_SUCCESSOR` opens new gen/attempt | Auditable admit markers present (`ADMIT_SUCCESSOR_OBLIGATION_EPISODE` / equivalent); episode actively recovering |
| **D2** | Admit-time contract identity | After admit, test can assert ADR-0046 binding attribution for that gen/attempt (P1' — exact assertion shape chosen at Runtime Auth; must be auditable) |
| **D3** | Defer to `NEGOTIATION_SETTLING` after successor admit | Episode remains under Recovery ownership; **not** solely waiting on LEAVE/CANCEL; R-M2 evaluability attributed (assertion shape at Runtime Auth) |
| **D4** | Hollow-residency negative | Construct Field #2-shaped defer without external leave; design-compliant behavior must **not** leave “open forever with only external exits” as the accepted steady state |
| **D5** | X1' regression | Supersede / later `ADMIT_SUCCESSOR` does not mark prior episode as S1'-satisfied solely by replacement |
| **D6** | Orthogonality smoke | No UVCP mapping change required for pass; no ADR-0045 clear invocation required for pass |

Optional (not C1' gate): sequences/diagrams (former C2).

---

## 7. V-field' (reminder; not a design gate)

After runtime lands (separate auth): authorized field may observe non-purely-external terminal-related evidence **or** non-hollow binding on a real episode. No manufactured FAILED_MEDIA / forced WiFi for evidence farming.

---

## 8. C1' checklist

| # | Requirement | This doc |
|---|-------------|----------|
| 1 | M1 binding seam + ownership | §2 |
| 2 | M2 defer/settling non-hollow under existing authority | §3 (R-M2) |
| 3 | Non-goals / frozen domains | §4–§5 |
| 4 | V-desk' case list | §6 |
| — | Code / PR / timeout numbers | **Excluded** ✅ |

---

## 9. Gate status

```text
C1' package: ACCEPTED (DP-ACCEPT 2026-08-09)
        |
        v
Runtime Authorization grill  ← next
        |
        v
(if authorized) implementation under B1' + R-M2
```

**Adjudication:** **DP-ACCEPT** — Design Proposal satisfies C1'; Runtime still not authorized.
