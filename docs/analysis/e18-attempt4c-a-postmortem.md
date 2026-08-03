# E.18 Attempt-4c-A Postmortem

**Status:** `POSTMORTEM` (2026-08-03)  
**Run:** `logs/phase3c-b-attempt4c-a-20260803-204240`  
**Session:** `5f22de23-d910-4902-8d64-82b28885e747`  
**Does not:** amend ADR | unlock R4-impl | claim `CASE_A_FAILED` or `CASE_A_INCONCLUSIVE`  
**Follow-up:** [e18-case-a-precondition-archaeology.md](./e18-case-a-precondition-archaeology.md) · **PR #118: HOLD**

---

## Result label (frozen)

```text
ATTEMPT4C_A_RESULT   = NOT_PROMOTED
classification       = AUTHORITY_STILL_PRESENT
cause                = PRECONDITION_NOT_SATISFIED
run_role             = precondition_discovery (host topology)
```

This run did **not** test whether authority absence yields `UNWIRED`. It discovered that host topology places **observerModule** on the local-authority branch (`CHECKED(true)`).

**Not** a Case-A failure sample. Do not reclassify as `CASE_A_FAILED` or `CASE_A_INCONCLUSIVE`.

**No** `ATTEMPT4C_A_FIELD_PROMOTION.txt`. **No** `CASE_A_FIELD_VERIFIED`.

---

## What we intended to test

```text
AUTHORITY_UNWIRED
        |
        v
UNWIRED disposition
        |
        v
completion HOLD / WAITING
```

---

## What actually happened

### Operator / harness

| Time | Event |
|------|-------|
| 20:42:57 | `WATCH armed` |
| 20:43:02 | M03 WiFi OFF (28s) |
| 20:43:11 | Harness `HIT CONFERENCE` (same session) |
| 20:43:16 | `ABSENT` seen; D1 admission trace collected |
| 20:43:20 | Harness exit `D1_DIAG_C` |
| 20:43:30 | M03 WiFi ON |

Harness recovery flap executed. Classifier: `Control: Case B`, `membershipProbeDisposition=CHECKED`, `membershipEpochConverged=true`.

### M02 auth-stream (20:43:13, recovery window)

```text
MEMBERSHIP_AUTHORITY_RESOLVE_TRACE
  reason=LOCAL_IS_MEMBERSHIP_AUTHORITY
  authorityHash=-528664596
  result=true

RECOVERY_CONTROL_RECONCILIATION_FACT
  membershipProbeDisposition=CHECKED
  membershipEpochConverged=true
  authorityId=M02
  result=true
```

**No** `CONTROL_RECONCILIATION_MEMBERSHIP_UNWIRED`. **No** `membershipProbeDisposition=UNWIRED`.

### M03 rem-stream (same window)

```text
MEMBERSHIP_AUTHORITY_RESOLVE_TRACE
  authorityHash=-528664596
  reason=HASH_MISMATCH
  result=false

membershipProbeDisposition=CHECKED
membershipEpochConverged=false
```

Remote observed wired digest mismatch; host observed local authority ownership — asymmetric but both on **CHECKED** path, not UNWIRED.

---

## Key finding: M01 silence ≠ authority absent

### Prior implicit model (incomplete)

```text
M01 silence
    -> no HELLO
    -> no authority digest
    -> UNWIRED
```

### Observed model

```text
M01 silence
    -> old authority state may survive (digest cache)
    -> M02 local membership authority active (host / bootstrap primary)
    -> LOCAL_IS_MEMBERSHIP_AUTHORITY
    -> CHECKED(converged=true)
    -> Case-B/C family (not Case-A)
```

**Stimulus did not fail.** Case-A precondition was incomplete.

---

## Contract gap exposed

Case-A preflight checked **observation** conditions (digest absent, no M01 HELLO in window) but not **runtime ownership**:

| Gate | Type | Was in contract? | This run |
|------|------|------------------|----------|
| `authorityDigestKnown=false` | observation | partial | digest trace present at recovery (`authorityHash=-528664596`) |
| `authorityOwnershipAbsent` | runtime | **no** | **violated** — `LOCAL_IS_MEMBERSHIP_AUTHORITY` on M02 |
| M01 HELLO silent | stimulus example | yes | satisfied after session T0 |
| M02 not local membership authority | runtime | **implied by ADR E.21.3, not in field contract** | **violated** |

ADR E.21.3 Case-A topology already states:

```text
(no lastSeenAuthorityDigestByChannel entry; local not membership authority)
```

Field contract (#118) under-specified the second clause.

---

## Code archaeology (lifecycle)

### 1. Where `LOCAL_IS_MEMBERSHIP_AUTHORITY` comes from

`MembershipAuthorityResolver.evaluateMembershipConvergence`:

```kotlin
context.isLocalMembershipAuthority -> reason = LOCAL_IS_MEMBERSHIP_AUTHORITY; converged = true
```

`isLocalMembershipAuthority` is set from `TalkbackCoordinator.isMembershipAuthority(session)`:

```kotlin
session.anchorModuleId == localModuleId
    || resolveBootstrapPrimary(...) == localModuleId
```

**M02 host** on a two-party CH-01 conference is typically **local membership authority**. That short-circuits the resolver before digest comparison.

### 2. Where UNWIRED is produced

`WiredMembershipEpochProbe.probe`:

```kotlin
if (!context.isLocalMembershipAuthority && outcome.authorityDigest == null) {
    return Unwired(outcome.reason)  // typically AUTHORITY_DIGEST_MISSING
}
return Checked(..., converged = outcome.converged)
```

**Both** conditions required:

- not local membership authority
- no authority digest in observation cache

Local authority → always `Checked`, never `Unwired`, regardless of M01 silence.

### 3. `lastSeenAuthorityDigestByChannel` lifecycle

| Event | Code | Eviction? |
|-------|------|-----------|
| Authority HELLO on GROUP session | `recordAuthorityDigestFromHello` (~11000) | — |
| Test seed | `testSeedAuthorityDigestForChannel` | — |
| **Production clear** | — | **none found** |

Map is `ConcurrentHashMap` with **put-only** paths in production. Digest from earlier M01-on-mesh session can survive:

- M01 WiFi OFF after meeting start
- new conference session UUID
- M01 HELLO absent in current session window

Preflight "no M01 HELLO since T0" does **not** imply cache absent.

### 4. Open questions (precondition archaeology — not resolved by restart)

1. **Ownership:** Under what topology can M02 host Case-A while `isLocalMembershipAuthority=false`?
2. **Cache:** Is cold-start / process kill required to clear digest, or is there a relinquish path we missed?
3. **ADR alignment:** Is Case-A field topology "M02 host + M03" compatible with ADR's "local not membership authority" without a different role assignment?

**Do not** assume cold-start retry until (1)-(3) are answered — restart may hide cache survival without explaining required field topology.

---

## Preflight post-hoc (session `5f22de23`)

| Check | At T0 (operator) | At recovery |
|-------|------------------|-------------|
| M01 WiFi OFF | yes | yes |
| M01 HELLO absent (post-T0) | yes | yes |
| `authorityHash` absent | appeared pass in narrow window | **fail** — `-528664596` |
| `LOCAL_IS_MEMBERSHIP_AUTHORITY` absent | **not checked** | **fail** |
| `UNWIRED` disposition | — | **fail** |

---

## Relationship to other cases

| Observation | Case family |
|-------------|-------------|
| `CHECKED` + `converged=true` + `LOCAL_IS_MEMBERSHIP_AUTHORITY` | B-aligned host path (not B field-verified) |
| `CHECKED` + `HASH_MISMATCH` on remote | C-like mismatch on follower |
| No `UNWIRED` | **Not Case-A** |

Do **not** relabel as `CASE_C_OBSERVED` for promotion — wrong exercise class. Use `AUTHORITY_STILL_PRESENT` / `PRECONDITION_NOT_SATISFIED`.

---

## Recommended next steps (ordered)

1. **Contract patch** — add `authorityOwnershipAbsent` gate (see updated [e18-attempt4c-a-minimal-field-contract.md](./e18-attempt4c-a-minimal-field-contract.md)).
2. **Precondition archaeology** — **done** → [e18-case-a-precondition-archaeology.md](./e18-case-a-precondition-archaeology.md) (HYBRID B+C; host topology blocked).
3. **Decide** topology (follower vs host) + exercise class (successor vs 4c flap) before merge or re-run.
4. **No immediate re-run** on current three devices without topology/lifecycle decision.

---

## Status board (post-run)

```text
E.18
 ├─ Case-C                      FIELD_VERIFIED
 ├─ Case-B                      ARCHAEOLOGY_CLOSED / REACHABILITY_EXCEPTION
 └─ Case-A                      NOT_PROMOTED
      reason=AUTHORITY_STILL_PRESENT
      archaeology=BLOCKED_BY_RUNTIME_LIFECYCLE (host topology)

Attempt-4c-A                    COMPLETE (not promoted)
Case-A field verification       NOT_REACHED
PR #118                         HOLD

R4-impl                         WAITING
```

---

*Local analysis artifact. Does not change ADR acceptance criteria or implementation behavior.*
