# E.18 Targeted Field Validation Plan

**Status:** `DRAFT / EXECUTION CONTRACT` (2026-08-03; Step 0 complete locally — PR pending with archaeology)  
**ADR:** §E.18.3 / §E.21.3–§E.21.5  
**Related artifact:** [e18-case-c-closure.md](./e18-case-c-closure.md) (Case-C `FIELD_VERIFIED`, PR #115)

**Does not:** close E.18 overall · modify ADR status · authorize R4-impl · trigger field runs by itself

---

## §1 Scope

E.18 field **coverage completion** plan for the §E.21 control-reconciliation classification family.

| Case | Authority state | Question |
|------|-----------------|----------|
| **A** | Unwired | Is infrastructure gap surfaced as `UNWIRED`, not silent-open? |
| **B** | Wired + epoch match | Does completion gate consume `CHECKED(true)` without implying adoption? |
| **C** | Wired + epoch mismatch | Is mismatch `CHECKED(false)`, not collapsed to `UNWIRED`? |

### Current

```text
Case-C   FIELD_VERIFIED  (e18-case-c-closure.md, PR #115)
Case-A   PENDING
Case-B   PENDING
```

### Out of scope (this plan)

- Full Attempt-4c re-run (delivery + deferred intent + joint topology)
- Joint exercises (`WITH_SUPPRESS`, D1, PR52C drain)
- R4-impl / `SUCCESSOR_OBLIGATION_ADOPTED` / `TRANSFERRED`
- Fixing hash lag or membership sync to clean completion

### Recommended execution order

```text
Step 0  Log archaeology (existing logs)
        ↓
Case-B targeted field (conditional)
        ↓
Case-A targeted field (conditional)
        ↓
A/B/C field coverage COMPLETE
        ↓
E.18 closure review (separate decision)
        ↓
R4-impl design review (still gated; not auto-unlocked)
```

Case-B before Case-A is preferred: stable meeting suffices; higher closure value. Either order is acceptable if contracts stay isolated.

---

## §2 Step 0 — Log archaeology

### Purpose

Decide whether existing field logs already satisfy Case A/B **contracts** before spending a new field window.

```text
已有现场事实
    ↓
是否满足 Case A/B contract
    ↓
能否形成 artifact
    ↓
不足才补 targeted run
```

### Inputs

- `logs/phase3c-b-attempt4c-*` (baseline, no SUPPRESS)
- `logs/joint1*` / `logs/joint1s*` (corroboration only unless contract fully met)
- Auth-side logs on authority module (M02)

### Tooling

- Semi-automatic: `scripts/analyze-attempt4c-baseline.ps1 -LogDir <dir>`
- Manual: [attempt4c-baseline-checklist.md](./attempt4c-baseline-checklist.md)

### Outputs (per candidate run)

| Field | Description |
|-------|-------------|
| `logDir` | Source run |
| `caseCandidate` | `A` / `B` / `none` |
| `confidence` | `LOW` / `MEDIUM` / `HIGH` |
| `evidenceLines` | Representative log lines (with timestamps) |
| `gaps` | Missing MUST / forbidden MUST NOT |
| `promotion` | `PROMOTE` / `INSUFFICIENT` / `NOT_RELEVANT` (Step 0 tri-state; see archaeology artifact) |

### Promotion rules (frozen)

```text
candidate != verified
```

Promotion to `FIELD_VERIFIED` requires:

1. §E.21 contract for that case fully satisfied in a **single coherent episode window**
2. Exercise class documented (Attempt-4c targeted preferred; Joint only if contract + stimulus unambiguous)
3. Separate artifact file written (see §7)
4. No `E18_VIOLATION` / `COMPLETION_VIOLATION` per analyzer

**Joint / informal runs** may produce archaeology rows at `INSUFFICIENT` only unless stimulus and episode boundaries are auditable for `PROMOTE`.

### Step 0 tri-state (frozen)

Archaeology promotion uses **only**:

```text
PROMOTE        — satisfies E.21 contract; may become field artifact
INSUFFICIENT   — relevant signs; missing surface, exercise class, or MUST fields
NOT_RELEVANT   — wrong case, module, or exercise; or Case C already closed
```

Do **not** use `FAILED` in Step 0 (archaeology ≠ experiment failure).

Recommended archaeology table columns:

| Run | Candidate | Evidence | Promotion | Why not promoted |

Reviewers care most about **why a sample was not promoted**, not merely what lines were seen.

---

## §2.1 Candidate Case-A rules (frozen)

### Required (both)

```text
membershipProbeDisposition=UNWIRED
+
CONTROL_RECONCILIATION_MEMBERSHIP_UNWIRED
```

in the **same reconciliation evaluation window**.

### Insufficient alone

```text
AUTHORITY_DIGEST_MISSING
```

alone — may be cold start, sync lag, or init phase; **not** sufficient for Case-A promotion.

### MUST NOT (same window)

```text
membershipProbeDisposition=CHECKED
candidate=RECOVERED / completion claims checked convergence
SUCCESSOR_OBLIGATION_ADOPTED
TRANSFERRED
membershipEpochConverged=true with UNWIRED disposition
```

### Confidence notes

- `reason=MEMBERSHIP_AUTHORITY_UNWIRED` on control fact strengthens candidate
- `reason=CONTROL_HANDSHAKE_PENDING` with UNWIRED is ambiguous — manual review required

---

## §2.2 Candidate Case-B rules (frozen)

### Required (all)

```text
membershipProbeDisposition=CHECKED
+
membershipEpochConverged=true
+
CONTROL_RECONCILIATION_MEMBERSHIP_CHECKED ... converged=true
+
RECOVERY_CONTROL_RECONCILIATION_FACT emitted (control fact present)
```

### Insufficient alone

```text
membershipEpochConverged=true
```

without `CHECKED` disposition and `CONTROL_RECONCILIATION_MEMBERSHIP_CHECKED` — not closure evidence.

### Anti–false-positive (E.18.1 regression guard)

Case-B artifact **must** include wired-authority identity proof:

```text
authoritySource != DefaultOpenMembershipAuthoritySentinel (or sentinel not sole path)
authorityId != NONE
resolverImpl=DefaultMembershipAuthorityResolver (or documented wired resolver)
MEMBERSHIP_AUTHORITY_RESOLVE_TRACE ... result=true (when present)
```

Without identity proof, label `CANDIDATE_B_AMBIGUOUS` — not `CASE_B_PASS`.

### MUST NOT infer

```text
successor adopted obligation
SUCCESSOR_OBLIGATION_ADOPTED
TRANSFERRED
RECOVERED == adoption
```

### Allowed report shape

```text
CASE_B_PASS

control:
  CHECKED=true
  converged=true
  authority wired (identity proof lines)

ownership:
  NOT_EVALUATED
```

---

## §3 Case-A contract (targeted field)

**Purpose:** Guard verification — `UNWIRED` ≠ silent default-open (§E.21.3).

### Stimulus topology

```text
successor episode / control reconciliation window
authority digest unavailable for channel
```

### Harness

- **No** D1 · **No** Joint · **No** SUPPRESS
- Minimal: stable or cold-start meeting + natural recovery edge on M03
- Log dir naming: `Attempt-4c-A` or equivalent

### MUST observe

```text
CONTROL_RECONCILIATION_MEMBERSHIP_UNWIRED
RECOVERY_CONTROL_RECONCILIATION_FACT ... membershipProbeDisposition=UNWIRED
RECOVERY_CONTROL_RECONCILIATION_FACT ... membershipEpochConverged=false
RECOVERY_CONTROL_RECONCILIATION_FACT ... reason=MEMBERSHIP_AUTHORITY_UNWIRED
```

### MUST NOT observe (episode under test)

```text
RECOVERED
SUCCESSOR_OBLIGATION_ADOPTED
TRANSFERRED
membershipEpochConverged=true with membershipProbeDisposition=UNWIRED
```

### Allowed classification

```text
CASE_A_PASS
```

May prove: E.18 prevents silent-open; unwired ≠ false.  
May **not** prove: adoption, delivery conservation, successor ownership.

---

## §4 Case-B contract (targeted field)

**Purpose:** Positive control path — completion gate consumes wired `CHECKED(true)` (§E.21.4).

### Stimulus topology

```text
successor episode / reconciliation window
authority digest present and aligned with local TopologyDigest
```

### Harness

- **No** D1 · **No** Joint · **No** SUPPRESS
- **Stable meeting** preferred (no WiFi flap)
- Log from authority module (M02)

### MUST observe

```text
CONTROL_RECONCILIATION_MEMBERSHIP_CHECKED
    authorityId=<A> expectedEpoch=<N> observedEpoch=<N> converged=true
RECOVERY_CONTROL_RECONCILIATION_FACT ... membershipProbeDisposition=CHECKED
RECOVERY_CONTROL_RECONCILIATION_FACT ... membershipEpochConverged=true
```

Plus **identity proof** (§2.2 anti–false-positive block).

### Non-verdict (allowed)

```text
RECOVERED in same window          — may or may not occur
no RECOVERED + CHECKED converged=true — valid; other gates may hold
```

### MUST NOT write

```text
successor adopted obligation
SUCCESSOR_OBLIGATION_ADOPTED
TRANSFERRED
```

### Allowed classification

```text
CASE_B_PASS
```

May prove: completion gate correctly consumes wired authority fact.  
May **not** prove: obligation transfer or adoption.

---

## §5 Case-C reference (closed)

Case-C is **not** in scope for new field work.

Canonical closure: [e18-case-c-closure.md](./e18-case-c-closure.md).

```text
CHECKED + converged=false + MEMBERSHIP_EPOCH_MISMATCH + no RECOVERED
```

Do not re-run for additional mismatch samples unless regression is suspected.

---

## §6 Closure rules

### A/B/C field coverage complete ≠ E.18 CLOSED

```text
Case-A FIELD_VERIFIED
Case-B FIELD_VERIFIED
Case-C FIELD_VERIFIED   (done)
        ↓
A/B/C field coverage COMPLETE
        ↓
E.18 closure review (separate artifact / ADR amendment if approved)
        ↓
E.18 overall CLOSED?   (explicit decision only)
```

E.18 overall still includes: **production path must not regress to default-open**. Three case samples do not replace ongoing guard discipline.

### R4 gate (frozen)

```text
Case-C closure does not authorize R4 implementation.
A/B field coverage complete does not authorize R4 implementation.
R4-impl remains gated by explicit adoption authority review.
```

---

## §7 Artifacts (when promoting)

Per case promotion:

```text
e18-case-a-field-closure.md
e18-case-b-field-closure.md
```

Minimum header fields:

```text
case=A|B
status=FIELD_VERIFIED|CANDIDATE
sourceLogDir=...
exerciseClass=attempt4c-targeted|log-archaeology|joint-corroboration-only
primaryEvidenceLines=...
identityProof=... (Case B required)
E18_VIOLATION=false
COMPLETION_VIOLATION=false
ownership=NOT_EVALUATED
doesNotAuthorizeR4Impl=true
```

---

## §8 Status board (informative)

```text
E.18
 ├─ E.18.1 Observable Gap        LANDED
 ├─ E.18.2 Authority Wiring      VERIFIED (PR-D #107)
 ├─ Case-C Membership Mismatch   FIELD_VERIFIED (#115)
 ├─ Case-A Unwired               PENDING
 └─ Case-B Match                 PENDING

E.18 Targeted Validation Plan    LOCAL DRAFT (pair with archaeology for PR)
Step-0 Log Archaeology           COMPLETE (local artifact)
Targeted Field Runs               CONDITIONAL (after Step 0)

R4-def                            DEFINED
R4-impl                           WAITING
```

---

## §9 Step 0 execution checklist (operator)

1. Enumerate log dirs: `logs/phase3c-b-attempt4c-*`, `logs/joint1*`, `logs/joint1s*`
2. For each auth log, grep Case A / Case B patterns per §2.1 / §2.2
3. Record `gaps` and `confidence`
4. Produce: `docs/analysis/e18-step0-log-archaeology.md` — **done** (2026-08-03)
5. Decision: skip field if `PROMOTE`; else minimal targeted run per §3 / §4 when `INSUFFICIENT`

**Do not** merge Step 0 candidates into ADR or mark E.18 CLOSED.

---

## Freeze declaration

This plan defines **how** to complete E.18 field coverage discipline.  
It does not authorize production changes, R4-impl, or reinterpretation of Joint #1-S / Case-C artifacts.
