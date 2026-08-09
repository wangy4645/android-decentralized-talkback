# ADR-0048 — Runtime Verification Run Card 001

**Status:** **CLOSED** · **RV-001…005 PASS** · **PR creation ALLOWED**  
**Date:** 2026-08-10  
**ADR:** [0048-post-recovered-inbound-reattach-ownership-contract.md](../adr/0048-post-recovered-inbound-reattach-ownership-contract.md) (**ACCEPTED**)  
**Upstream:** [Code Allow Gate](./adr0048-code-allow-gate.md) (**CODE ALLOWED**) · [Implementation proposal](./adr0048-implementation-proposal.md)  
**Seam audit:** [conference-recovery-post-close-inbound-reattach-seam-audit-001.md](./conference-recovery-post-close-inbound-reattach-seam-audit-001.md)

```text
Governance chain:

ADR-0048 ACCEPTED
        ↓
Runtime Auth F1 / Q2 A/A/A
        ↓
Code Allow
        ↓
Implementation ✅
        ↓
Runtime Verification ← this card (CLOSED)
        ↓
PR Review
        ↓
Merge
        ↓
Post-merge observation
```

---

## 0. Objective

Verify that **runtime / contract behavior** matches ADR-0048 — not merely that code compiles.

**Question:**

```text
post-RECOVERED
+
valid inbound REATTACH_ACCEPTED
+
CONVERGING
```

does it produce:

```text
explicit convergence ownership episode
```

**And confirm:**

```text
UVCP projection unchanged
Syncing predicate unchanged
timeout/budget unchanged
ADR-0047 untouched
```

**Not in scope:** fix SYNCING UI · WiFi flap · W5 field re-run · soak · UI observation.

ADR-0048 acceptance evidence is **lifecycle contract**, not field recovery latency.

---

## 1. Verification mode

| Layer | Method | Artifact |
|-------|--------|----------|
| Contract runtime | Unit contract tests C1–C4 | `Adr0048PostRecoveredInboundReattachContractTest` |
| Boundary audit | Implementation diff scope | Controller + models + contract tests only |
| Field | **NOT REQUIRED** for this gate | — |

**Execute:**

```powershell
cd talkback
.\gradlew :android-board-talkback:testDebugUnitTest `
  --tests "com.talkback.core.session.Adr0048PostRecoveredInboundReattachContractTest"
```

**Evidence run:** 2026-08-10 — `BUILD SUCCESSFUL` (4/4 tests).

---

## 2. Implementation review note (for PR)

> `needsNewObligationEpisode(existing)` guards the post-RECOVERED transition and avoids creating a second ownership episode for normal inbound reattach paths.

ADR-0048 fixes the **post-RECOVERED ownership transition gap** (`POST_OBLIGATION_CLOSE_INBOUND_REATTACH_SUPERSEDE`), not all `REATTACH_ACCEPTED` lifecycle semantics.

---

## 3. RV-001 — Ownership Admission

**Maps to contract test:** `c1_convergingAdmission_createsOwnershipEpisode`

### Input

```text
RECOVERED (obligation CLOSED)
        ↓
inbound REATTACH_ACCEPTED
        ↓
disposition = CONVERGING
```

### Observe

```text
POST_RECOVERED_INBOUND_REATTACH
RECOVERY_OBLIGATION_OPENED
edgeObligationOpen() == true
phase == REATTACH_ACCEPTED
obligationGeneration incremented
```

### Pass criteria

```text
owner exists (obligation OPEN)
episode reason / trigger correct
```

**Verdict:** **PASS** (2026-08-10)

---

## 4. RV-002 — Non-Converging Fence

**Maps to contract test:** `c2_nonConverging_doesNotCreateOwnershipEpisode`

### Input

```text
post-RECOVERED
        ↓
REATTACH_ACCEPTED admission
        ↓
disposition = NON_CONVERGING_REATTACH
```

### Pass criteria

```text
no ownership episode
no implicit actively-recovering residency
phase remains RECOVERED
explicit exception only (INV-048-005)
```

### Observe

```text
NON_CONVERGING_REATTACH log
no RECOVERY_OBLIGATION_OPENED
edgeObligationOpen() == false
```

**Verdict:** **PASS** (2026-08-10)

---

## 5. RV-003 — ICE Live Fence (W5 regression class)

**Maps to contract test:** `c3_iceAlreadyLive_ownershipRemainsOpenUntilControlReconciliation`

### Input

```text
post-RECOVERED
        ↓
inbound REATTACH_ACCEPTED + CONVERGING
        ↓
ICE already CONNECTED
```

### Must observe

```text
ownership remains OPEN
```

### Forbidden

```text
ICE_CONNECTED
      ↓
RECOVERY_EDGE_RECOVERED   (shortcut clear)
```

**Invariant protected:** `ICE_CONNECTED != CONVERGED`

**Verdict:** **PASS** (2026-08-10)

---

## 6. RV-004 — Clear Path

**Maps to contract test:** `c4_controlReconciliation_clearsOwnership`

### Input

```text
post-RECOVERED inbound CONVERGING (obligation OPEN)
        ↓
transport recovered (ICE / media route)
        +
membership + control reconciled
```

### Observe

```text
RECOVERY_EDGE_RECOVERED
edgeObligationOpen() == false
obligationCloseReason == RECOVERED
```

**Clear authority:** existing `controlReconciliationCompleted` + completion stack (Q2-003=A).

**Verdict:** **PASS** (2026-08-10)

---

## 7. RV-005 — Boundary Verification

Audit of implementation diff — **no forbidden domain invasion**.

| Domain | Expected | Observed |
|--------|----------|----------|
| ADR-0048 ownership transition | changed | **YES** — `onRecoveryReattachAccepted` + `ReattachDisposition` |
| ADR-0044 UVCP | unchanged | **YES** — no UVCP / projection files in diff |
| Syncing predicate | unchanged | **YES** — no UVCP / UI predicate files |
| timeout / budget | unchanged | **YES** — no budget constant changes |
| ADR-0047 | untouched | **YES** — no 0047 files |
| New recovery FSM | none | **YES** — reuses `openNewRecoveryObligation` family |

**Files touched (implementation commit scope):**

```text
android-board-talkback/.../ConferenceEdgeRecoveryController.kt
android-board-talkback/.../EdgeRecoveryModels.kt
android-board-talkback/.../Adr0048PostRecoveredInboundReattachContractTest.kt
```

**Verdict:** **PASS** (2026-08-10)

---

## 8. Field requirement

**NOT required** for this gate:

| Excluded | Reason |
|----------|--------|
| WiFi flap | lifecycle contract gate |
| W5 re-run | seam class covered by RV-003 contract |
| soak | not acceptance criterion for ADR-0048 |
| UI / SYNCING observation | ADR-0044 boundary — consequence only |

Post-merge **Appendix B passive observation** remains the normal evolution path after merge.

---

## 9. Exit criteria

| Gate | Status |
|------|--------|
| RV-001 Ownership Admission | **PASS** |
| RV-002 Non-Converging Fence | **PASS** |
| RV-003 ICE Live Fence | **PASS** |
| RV-004 Clear Path | **PASS** |
| RV-005 Boundary Verification | **PASS** |

```text
Runtime Verification CLOSED
        ↓
PR creation ALLOWED
```

**PR title guidance (not "fix Sync"):**

> Implement ADR-0048 post-recovered inbound reattach ownership transition

**PR must cite:** this run card · RV-001…005 · `needsNewObligationEpisode` guard rationale.

---

## 10. Links

| Doc | Path |
|-----|------|
| ADR-0048 | `docs/adr/0048-post-recovered-inbound-reattach-ownership-contract.md` |
| Code Allow | `docs/analysis/adr0048-code-allow-gate.md` |
| Seam audit (W5) | `docs/analysis/conference-recovery-post-close-inbound-reattach-seam-audit-001.md` |
| Contract tests | `android-board-talkback/.../Adr0048PostRecoveredInboundReattachContractTest.kt` |
