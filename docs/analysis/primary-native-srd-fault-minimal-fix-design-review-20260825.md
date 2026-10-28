# Primary Native SRD Fault — Minimal Fix Design Review

**ID:** primary-native-srd-fault-minimal-fix-design-review-20260825  
**Date:** 2026-08-25  
**Type:** Design Review · Fix Candidate Selection  
**Status:** **CONDITIONALLY ACCEPTED** · Seam investigation **AUTHORIZED** · Implementation IA **BLOCKED**

**Prerequisites (frozen):**

| Artifact | Status |
|----------|--------|
| [primary-native-srd-fault-1a1b-adjudication-20260825.md](./primary-native-srd-fault-1a1b-adjudication-20260825.md) | **ADJUDICATED** |
| [primary-native-srd-fault-rca-20260825.md](./primary-native-srd-fault-rca-20260825.md) | Field archaeology **STOPPED** |
| [coordinator-media-execution-boundary.md](../architecture/coordinator-media-execution-boundary.md) | **FROZEN** |
| B1 recovery intent | **FIELD PASS** · **KEEP** |

**Evidence logs:**

- Clean 1a: `talkback/logs/pnsrd-1a-20260825-120721/` · `815ca018…`
- Clean 1b: `talkback/logs/pnsrd-1b-20260825-123029/` · `bfc987ae…`

---

## 1. Frozen RCA

### Primary defect (normative wording)

```text
Shared-factory sequential SRD execution has an ordinal-dependent native non-return
on the second host SRD (setRemoteDescription answer).

shared-factory
    + sequential host SRD (ordinal 1 PASS, ordinal 2 TIER_JNI hang)
    → failure edge tracks accept order, not fixed peer identity
```

**Do not write:** M03 hang · Recovery broken · fix recovery timeout · stale PC as primary.

### Clean 1a/1b facts (frozen)

| Run | SRD#1 | After SRD#1 | SRD#2 | Hang tier |
|-----|-------|-------------|-------|-----------|
| 1a (M02→M03) | M02 PASS ~70ms | **DOMAIN_IDLE_E3** | M03 HANG | **TIER_JNI** (`ENTER`, no `EXIT`) |
| 1b (M03→M02) | M03 PASS ~116ms | **DOMAIN_IDLE_E3** | M02 HANG | **TIER_JNI** |

**Refuted as primary cause:**

```text
C — post-SRD#1 domain not idle (Java lease/E3 complete before every ordinal-2 hang)
B — fixed peer / PC identity (same peer PASS when first, HANG when second)
```

**Primary hypothesis promoted:**

```text
A — ordinal / shared-factory cumulative runtime state after first successful SRD
```

### What we still do not know

```text
Exact native instruction / object / lock site inside setRemoteDescription
Whether hang is WebRTC factory-global vs per-attached-PC interaction
Whether re-entering JNI with E3-cleared Java lease still uses poisoned native context
```

Field archaeology on order variants is **closed**. Further diagnosis moves to **controlled fix candidates + falsifying field**.

---

## 2. Failure mechanism model

### Observed execution pattern (both clean runs)

```text
Ordinal 1 (any peer)
    APPLY_REMOTE_ANSWER  MATCH
    LEASE_GRANT → EXECUTION_ENTER
    SRD_NATIVE_CALL_ENTER → EXIT (~70–116ms)
    EXECUTION_EXIT → LEASE_RELEASE(released=true) → holder=NONE
    DOMAIN_IDLE_E3

Ordinal 2 (other peer)
    APPLY_REMOTE_ANSWER  MATCH
    LEASE_GRANT → EXECUTION_ENTER          ← Java domain idle was true
    SRD_NATIVE_CALL_ENTER
    (no SRD_NATIVE_CALL_EXIT within watchdog)
    lease retained → ordinal 3 DOMAIN_BLOCKED (when M04 applies)
```

### Layer split

| Layer | Ordinal 1 | Ordinal 2 |
|-------|-----------|-----------|
| Java lease / E3 | Complete | Grant succeeds **after** prior E3 |
| JNI SRD | Returns | **Non-return** (TIER_JNI) |
| Product | Edge CONNECTED possible | Media dead; blast radius |

### Mechanism statement (testable, not proven)

> After the **first successful** host SRD on `shared-factory`, some **native negotiation execution context** remains in a state that the **second** sequential `setRemoteDescription(answer)` cannot safely enter — even when Java reports **E3 idle**.

**E3 measures Java lease bookkeeping, not full native factory readiness for the next SRD.**

This is the design pivot:

```text
NOT:  “SRD#2 started too early — wait longer for E3”     (already falsified)
ASK:  “After E3, must shared-factory re-prime before next SRD?”
```

### TIER note

Both clean hangs are **TIER_JNI** (`lastEvent=SRD_NATIVE_CALL_ENTER`, `missing=NATIVE_CALL_EXIT`).  
Dirty scout also showed **TIER_PRE_JNI** once — out of scope for fix design target; ordinal-2 clean path is TIER_JNI.

---

## 3. Non-goals

### Fix goal is NOT

```text
“Second SRD always succeeds”
“All four participants media CONNECTED”
“Eliminate DOMAIN_BLOCKED classification”
“B2 safe re-realization / recovery admission”
“Another RCA field matrix”
```

### Fix goal IS

```text
Second sequential host SRD must not enter a known permanent non-return execution pattern
```

Operational success for verification:

```text
Ordinal 2 completes SRD_NATIVE_CALL_EXIT and reaches E3 without force-clearing lease/state
```

### Explicitly out of this design wave

```text
force-release · PC reset · native reset · Factory partition
watchdog timeout change · SRD retry loop
pending de-dup · double-offer suppression
Directed field archaeology · order-variant soaks
Domain recovery fallback (candidate D) — DEFERRED
```

---

## 4. Candidate minimal fixes

Each candidate must be **narrow**, **falsifiable**, and **not** expand into Factory rebuild or recovery.

### Candidate A — Sequencing only (E3 wait / SRD barrier)

**Idea:** Serialize host SRDs on `shared-factory` at operation boundary; do not start SRD#2 until SRD#1 → E3.

**Current state:** Lease + `runWithLease` already serializes grants. Clean 1a/1b prove **E3 completes before SRD#2 ENTER** yet hang persists.

| | |
|---|---|
| **Verdict** | **INSUFFICIENT alone** — do not ship “wait for E3” as the fix |
| **May retain** | As safety invariant, not as repair |

### Candidate B — Shared runtime re-prime after E3

**Idea:** After ordinal-1 `SRD_NATIVE_CALL_EXIT` + E3, run a **minimal, scoped re-prime** of shared-factory negotiation execution context before granting lease to ordinal 2.

Examples (design space — not authorized implementations):

```text
Re-sync factory negotiation epoch / attached-PC SRD generation counter
Clear factory-level “first SRD completed” native latch (if identified)
Re-assert shared audio track / factory session description baseline (scoped)
```

**Scope boundary:** Re-prime **negotiation SRD boundary**, not full Factory release / PC rebuild.

| Pro | Con |
|-----|-----|
| Directly targets “E3 ≠ native ready” | Requires native/code seam identification |
| Explains ordinal determinism | Wrong re-prime could mask bug or widen blast radius |

**Verdict:** **Candidate intervention** — mechanism **NOT SELECTED** until §11 seam investigation names reset target.

### Candidate C — SRD execution serialization + ordinal gate

**Idea:** Add an explicit **host SRD ordinal gate** on the conference session (distinct from lease):

```text
session SRD ordinal counter
    ordinal 1 complete (EXIT + E3) → allow ordinal 2 admission
    optional: invoke re-prime hook at gate (B)
```

Differs from A only if gate carries **extra condition beyond E3** (e.g. re-prime complete marker, factory SRD generation bump).

| Pro | Con |
|-----|-----|
| Auditable session-level sequencing | Without B, likely repeats A failure |
| Pairs naturally with B | Must not block unrelated edges incorrectly |

**Verdict:** **Execution envelope in principle** — intervention inside boundary **TBD** (§11).

### Candidate D — Domain recovery / force paths

**Idea:** On ordinal-2 hang, force-release / reset / partition / B2 re-realization.

| Verdict | **DEFERRED** — bypasses root cause; contradicts frozen investigation posture |

---

## 5. Safety constraints (must not break)

| Invariant | Requirement |
|-----------|-------------|
| Factory entry single owner | No second writer to shared factory lifecycle |
| Lease semantics | `BUSY` / `STUCK` / `QUARANTINED` meaning unchanged; no silent force-clear |
| GCT READY / PENDING / FAILED | No predicate drift |
| `PENDING ≠ DOMAIN_BLOCKED` | Admission pending must not map to domain block |
| Coordinator liveness | No blocking coordinator on native hang |
| ADR-0056 | Topology / membership untouched |
| B1 recovery intent | Still triggered from `EDGE_FAILED`; fix must not replace intent with silent retry |
| E3 integrity | Fix must not fake E3 without `EXIT` + `released=true` |

**Forbidden shortcuts:**

```text
SRD#2 success via force-release after hang
E3 emitted while native call still active
Skipping lease on ordinal 2
PC reset disguised as “re-prime”
```

---

## 6. Candidate C + B — **PROVISIONAL** (not chosen fix)

### Frozen design conclusions

```text
DO NOT ship:     A-alone (wait for E3 / sequencing only)     — falsified by clean 1a/1b
MAY study:       ordinal-aware SRD execution boundary (C)
NOT SELECTED:    any specific intervention inside the boundary until seam investigation completes
```

**C = execution envelope.** **B = candidate intervention** — not **C+B = chosen fix**.

### What evidence supports

```text
E3 is necessary     — ordinal 2 never starts without prior lease release in observed hangs
E3 is not sufficient — ordinal 2 TIER_JNI hang after full E3 chain (both clean runs)
```

Therefore:

```text
NOT:  E3 → therefore SRD#2 must succeed
NOT:  E3 wait alone is the fix
ASK:  what shared-factory state delta exists after SRD#1 EXIT that ordinal-2 ENTER inherits?
```

### Three unresolved mechanisms (do not pick one yet)

```text
A. Native negotiation state has a small resettable delta
B. Java/native lifecycle object needs re-sync at SRD boundary
C. WebRTC native defect not fixable by lightweight re-prime
```

**Risk to reject now:** naming `sharedFactorySrdReprime()` before the seam is known — a catch-all that obscures behavior and invites whack-a-mole.

### Provisional envelope C (in principle only)

**Ordinal-aware SRD execution boundary** on host `shared-factory` path:

```text
ordinal N host SRD admission
    → existing lease gate (unchanged semantics)
    → [future: boundary intervention TBD by seam investigation]
    → SRD JNI
```

**Safety prerequisite (not success contract):** for `N > 1`, prior ordinal `(N-1)` must have recorded **E3 fact**. This is a **safety invariant**, not a product serial barrier that implies ordinal 2 success.

**Do not freeze as contract:**

```text
ordinal 2 → prior E3 → therefore SRD must succeed
```

**Acceptable future shape (hypothesis only):**

```text
ordinal 2
  → prior E3 confirmed
  → <named seam intervention>    ← re-prime | reset negotiation state | rebind | other
  → SRD
```

Intervention type is **TBD** — seam investigation must name it.

### Provisional candidate B — **NOT SELECTED**

Re-prime, reset negotiation state, rebind, or other native-safe operation may be proposed **only after** seam investigation identifies **what** would be reset and **why** it is safe.

**Forbidden until seam named:**

```text
Implement sharedFactorySrdReprime() as a no-op placeholder shipped to field
Implement catch-all “reprime then SRD” without observable state delta proof
```

### Scope guard (unchanged)

```text
Boundary is per host SRD on shared-factory — not “wait for all edges”
Parallel ICE / signaling / non-SRD work must not stall
PC reset · Factory partition · force-release remain DEFERRED
```

---

## 7. Field verification plan (post-fix only)

**One verification wave — no archaeology extensions.**

### Pre-gate

```text
Same DIO-1 build + fix candidate
pm clear M01 M02 M03 each run
Accept orders fixed: 1a M02→M03, 1b M03→M02
M04 optional wireless (DOMAIN_BLOCKED corroboration only)
```

### Pass bar

**Per run (1a and 1b):**

```text
SRD#1  ENTER → EXIT → E3
SRD#2  ENTER → EXIT → E3          ← primary product fix signal
No TIER_JNI / TIER_PRE_JNI on ordinal 2
No force-release / LEASE_RELEASE(released=false) workaround on ordinal 2
```

**Cross-run:**

```text
1a: M02 then M03 both PASS + E3
1b: M03 then M02 both PASS + E3
```

### Fail bar (reject fix candidate)

```text
Ordinal 2 still TIER_JNI
E3 faked or LEASE force-cleared without native EXIT
Regression: ordinal 1 fails where previously PASS
B1 recovery intent leak / cross-session terminal violation
```

### Observability additions (fix wave)

```text
SRD_ORDINAL_GATE admit|block reason=
SHARED_FACTORY_SRD_REPRIME_ENTER|EXIT result=
```

---

## 8. Explicitly deferred recovery options

```text
force-release after hang
PC reset / native reset between SRDs
Factory partition (D-β / D-γ)
B2 safe re-realization
SRD retry / timeout budget change
Using recovery intent to bypass ordinal gate
“Hang is acceptable if DOMAIN_BLOCKED is classified correctly”
```

These remain **independent tracks** unless fix verification fails and new ADR authorizes escalation.

---

## 9. Review decisions — **ADJUDICATED**

| # | Decision | Ruling |
|---|----------|--------|
| 1 | Primary defect: ordinal-dependent native non-return | **ACCEPT** |
| 2 | A-alone insufficient | **ACCEPT** |
| 3 | Preferred = C+B | **PROVISIONAL** — **not frozen as chosen fix** |
| 4 | Block Implementation IA until native seam named | **ACCEPT** — **must retain** |
| 5 | Next artifact | **Conditional:** Native seam investigation **first** → then Implementation IA |
| 6 | Post-fix field | **ACCEPT** — one **1a + 1b** verification → **STOP** |

---

## 10. Status

```text
Primary defect                         ACCEPTED
A-alone insufficient                   ACCEPTED
Ordinal gate as execution envelope     ACCEPTED IN PRINCIPLE (C — not product success contract)
Re-prime / B intervention              CANDIDATE / NOT SELECTED
Design review                          CONDITIONALLY ACCEPTED
Native seam investigation            **COMPLETE** — see [primary-native-srd-fault-native-seam-investigation-20260825.md](./primary-native-srd-fault-native-seam-investigation-20260825.md)
Implementation IA                      **BLOCKED** — no named re-prime seam; Result 2 primary
Field                                  NOT AUTHORIZED
Recovery / reset                       DEFERRED
```

---

## 11. Native seam investigation — **AUTHORIZED** (not Implementation IA)

### Authorization (single sentence)

> **Authorize desk/native investigation only** to identify the **minimal observable shared-factory state delta** between SRD#1 success and SRD#2 pre-ENTER — sufficient to name a concrete boundary intervention; **no behavior change**, **no field**, **no `sharedFactorySrdReprime()` implementation**.

### Question (must answer)

After `setRemoteDescription()` **first success returns**, what **shared-factory native state** is observably different immediately before the **second** call enters?

### Method

```text
SRD#1 success (clean 1a/1b host logs + code trace)
    ↓
snapshot native / shared state at SRD#1 EXIT + E3
    ↓
snapshot at SRD#2 pre-ENTER (same session, same factoryHash)
    ↓
compare → minimal delta set
```

**Primary sources:** existing clean captures (`pnsrd-1a-*`, `pnsrd-1b-*`); desk code/native trace — **not** new order-variant field runs.

### Delta categories to inspect

```text
factory-level state
shared signaling state
attached PeerConnection state
negotiation / task state
native objects · epochs · counters
```

### Required output (gate for Implementation IA)

```text
Named delta list (what changes ordinal 1 → ordinal 2)
Proposed intervention mechanism (not a placeholder name)
Safety argument vs §5 constraints
Falsification: what observation would prove A vs B vs C mechanism class (§6)
```

**If output cannot answer “re-prime resets **what**?” → Implementation IA remains blocked.**

**§11 outcome (20260825):** **Result 2 primary** — no provable re-prime seam at current observability; **B NOT SELECTED**. See investigation doc. Next: observability snapshot spec (9a) or native source brief (9b) — not Implementation IA.

### Explicitly not authorized in this investigation

```text
New 1a/1b field archaeology
Implement re-prime / ordinal gate / any SRD behavior change
PC reset · Factory partition · force-release
Implementation IA · field verification
```

### Chain after investigation

```text
Native seam investigation (this §11)
    ↓
Named mechanism proposal
    ↓
Implementation IA v0.1
    ↓
1a + 1b verification
    ↓
STOP
```
