# E.18 Step 0 — Log Archaeology

**Status:** `ARTIFACT` (2026-08-03)  
**Companion:** [e18-targeted-field-validation-plan.md](./e18-targeted-field-validation-plan.md) (`DRAFT / EXECUTION CONTRACT`)  
**ADR:** §E.18.3 / §E.21.3–§E.21.5  
**APK:** #113 HEAD `e3db13e` (SHA256 `ce0ef8e081fba5ff85f01aa06ad530a06e67c773f48b9110da81203e403184ce`)  
**Devices:** M01 `HTUBB21B09220661` · M02/auth `2d73067a` · M03/remote `MDX0220416001963`

**Purpose:** Decide whether existing field logs already satisfy Case A/B §E.21 contracts before a minimal targeted field run.

**Step 0 promotion vocabulary (frozen):**

| Value | Meaning |
|-------|---------|
| `PROMOTE` | Contract satisfied; may write Case A/B field artifact per §E.21 |
| `INSUFFICIENT` | Relevant signals present; missing exercise class, surface, or MUST fields |
| `NOT_RELEVANT` | Wrong case, wrong module, invalid exercise, or Case C-only / already closed |

Step 0 is archaeology — not experiment failure. Do not use `FAILED`.

---

## Summary

| Run | Candidate | Evidence | Promotion | Why not promoted |
|-----|-----------|----------|-----------|------------------|
| `phase3c-b-attempt4c-20260803-150349` | — | Case C: `CHECKED` + `MEMBERSHIP_EPOCH_MISMATCH` (n=4) | `NOT_RELEVANT` | A/B scan negative; Case C already `FIELD_VERIFIED` (#115) |
| `phase3c-b-attempt4c-20260803-144151` | — | Case C: same mismatch pattern (n=4) | `NOT_RELEVANT` | A/B scan negative; corroborates Case C only |
| `phase3c-b-attempt4cs-20260803-155535` | — | Case C: mismatch (n=4) | `NOT_RELEVANT` | A/B scan negative; SUPPRESS variant not A/B stimulus |
| `phase3c-b-attempt4c-20260803-143250` | — | `INVALID_EXERCISE` (harness ANR) | `NOT_RELEVANT` | Process death; no auditable reconciliation window |
| `joint1-suppress-20260803-165532` | Case-A? | M02 auth: `UNWIRED` + `membershipProbeDisposition=UNWIRED` + `reason=MEMBERSHIP_AUTHORITY_UNWIRED` (16:55:54) | `INSUFFICIENT` | Joint `WITH_SUPPRESS`; run `INVALID_EXECUTION` (pre-#113 harness wiring); not Attempt-4c targeted successor baseline |
| `joint1-suppress-20260803-161723` | Case-B? | M01 only: `CHECKED` `converged=true` + `MEMBERSHIP_AUTHORITY_RESOLVE_TRACE` `LOCAL_IS_MEMBERSHIP_AUTHORITY` (16:18:15) | `INSUFFICIENT` | Canonical surface is M02 `auth-stream.log` (zero Case-B lines); host-local authority path ≠ §E.21 auth-module observation |
| `joint1-suppress-20260803-170929` | — | Case C: `MEMBERSHIP_EPOCH_MISMATCH` (n=32) | `NOT_RELEVANT` | Mismatch-only; Joint pacing / invalid exercise context |
| `joint1-suppress-20260803-171134` | — | Case C: mismatch (n=32) | `NOT_RELEVANT` | `INCONCLUSIVE_SUPERSEDE_RACE`; not A/B contract |
| `joint1-suppress-20260803-171457` | — | Case C: mismatch (n=20) | `NOT_RELEVANT` | `INCONCLUSIVE_SUPERSEDE_RACE`; not A/B contract |
| `joint1s-suppress-20260803-172712` | — | Case C: mismatch (n=12); Joint #1-S frozen | `NOT_RELEVANT` | Supersede-before-drain exercise; Case C aux only per #115 |

### Step 0 verdict

| Case | Promotion from archaeology | Next |
|------|---------------------------|------|
| **A** | `INSUFFICIENT` (best candidate: `joint1-165532`) | Minimal **Attempt-4c-A** targeted field (no D1 / Joint / SUPPRESS) |
| **B** | `INSUFFICIENT` (best candidate: `joint1-161723` M01 only) | Minimal **Attempt-4c-B** targeted field on M02 auth stream |
| **C** | — | Already `FIELD_VERIFIED` (#115); not re-scanned for promotion |

**No run promoted to `PROMOTE` for Case A or B.**

---

## Method

1. Enumerated: `logs/phase3c-b-attempt4c-*`, `logs/joint1*`, `logs/joint1s*` (10 dirs with auth or device logs).
2. Pattern counts on `auth-stream.log` (M02) and, where present, `M01-talkback.log`.
3. Cross-check: existing `ATTEMPT4C_BASELINE_CLASSIFICATION.txt`, `JOINT1_CLASSIFICATION.txt`, `JOINT1S_CLASSIFICATION.txt`.
4. Promotion rules: plan §2 + §2.1 / §2.2; Step 0 tri-state only.

**Scan patterns (auth-side):**

- Case A: `CONTROL_RECONCILIATION_MEMBERSHIP_UNWIRED`, `membershipProbeDisposition=UNWIRED`, `MEMBERSHIP_AUTHORITY_UNWIRED`
- Case B: `CONTROL_RECONCILIATION_MEMBERSHIP_CHECKED` + `converged=true`, `membershipEpochConverged=true` + `CHECKED`, `MEMBERSHIP_AUTHORITY_RESOLVE_TRACE`
- Case C (context): `MEMBERSHIP_EPOCH_MISMATCH`

---

## Per-run notes

### Attempt-4c baseline runs

**`150349` / `144151`** — analyzer Control: **Case C**. Auth stream: 0 UNWIRED, 0 `converged=true` CHECKED; 4 mismatch facts each. No Case A/B candidate lines.

Representative (150349):

```text
08-03 15:04:10.682 ... RECOVERY_CONTROL_RECONCILIATION_FACT ... membershipProbeDisposition=CHECKED result=false reason=MEMBERSHIP_EPOCH_MISMATCH
```

**`143250`** — `ATTEMPT4C_INVALID_EXERCISE`: harness-induced ANR after PR52C wake broadcast. No reconciliation classification possible.

**`attempt4cs-155535`** — Case C mismatch only (n=4); SUPPRESS successor variant. Not an A/B exercise.

### Joint runs

**`joint1-165532` (Case-A candidate)**

M02 `auth-stream.log` at `16:55:54.468–471`:

```text
CONTROL_RECONCILIATION_MEMBERSHIP_UNWIRED ... reason=AUTHORITY_DIGEST_MISSING
RECOVERY_CONTROL_RECONCILIATION_FACT ... membershipProbeDisposition=UNWIRED ... reason=MEMBERSHIP_AUTHORITY_UNWIRED
```

Satisfied §E.21.3 **log lines** in one window (`session=f9ad94e8-...`, `recoveryAttemptId=1`). No `candidate=RECOVERED` in auth stream.

**Gaps blocking `PROMOTE`:**

- Exercise: Joint `WITH_SUPPRESS` / `EXERCISE_SUPPRESSED_SUCCESSOR`, not Attempt-4c natural successor baseline.
- Run verdict: `INVALID_EXECUTION` / `HARNESS_WIRING_GAP_BLOCK_NOT_IN_READINESS` (pre-#113); stimulus not auditable as §E.21 field artifact.
- `AUTHORITY_DIGEST_MISSING` on UNWIRED broadcast present — acceptable only because paired `UNWIRED` disposition exists; still not sufficient without correct exercise class.

**`joint1-161723` (Case-B candidate)**

M02 `auth-stream.log`: **no** `CHECKED converged=true`, **no** resolve trace.

M01 `M01-talkback.log` at `16:18:15.642–647`:

```text
MEMBERSHIP_AUTHORITY_RESOLVE_TRACE ... resolverImpl=DefaultMembershipAuthorityResolver ... result=true reason=LOCAL_IS_MEMBERSHIP_AUTHORITY
CONTROL_RECONCILIATION_MEMBERSHIP_CHECKED ... authorityId=M01 expectedEpoch=1 observedEpoch=1 converged=true
RECOVERY_CONTROL_RECONCILIATION_FACT ... membershipProbeDisposition=CHECKED membershipEpochConverged=true result=true
```

**Gaps blocking `PROMOTE`:**

- Plan inputs require auth-side logs on **authority module (M02)**; evidence only on M01 host talkback process.
- `LOCAL_IS_MEMBERSHIP_AUTHORITY` with `authorityEpoch=null` is host-local path; §E.21 Case B artifact expects wired digest alignment on authority observation surface.
- Joint `WITH_SUPPRESS`; not Attempt-4c-B targeted topology.

**`170929` / `171134` / `171457` / `joint1s-172712`** — auth stream shows Case C mismatch counts only (or Joint #1-S supersede characterization). No UNWIRED or CHECKED-converged A/B pattern on M02. Classified `NOT_RELEVANT` for A/B.

---

## Decision (operator)

```text
Case-A  archaeology → INSUFFICIENT → schedule Attempt-4c-A targeted field
Case-B  archaeology → INSUFFICIENT → schedule Attempt-4c-B targeted field
Case-C  unchanged   → FIELD_VERIFIED (#115)
```

**Do not** merge this artifact into ADR or mark E.18 CLOSED.  
**Do not** unlock R4-impl from Step 0 negatives.

---

## Artifacts chain

```text
#115  e18-case-c-closure.md          (Case C FIELD_VERIFIED)
        |
        v
e18-targeted-field-validation-plan.md + e18-step0-log-archaeology.md  (this file)
        |
        v
Attempt-4c-A / Attempt-4c-B targeted field  (if INSUFFICIENT — confirmed)
        |
        v
e18-case-a-closure.md / e18-case-b-closure.md  (future, per-case)
```

---

*Generated: 2026-08-03. Scanner: manual grep + `analyze-attempt4c-baseline.ps1` cross-check on Attempt-4c dirs.*