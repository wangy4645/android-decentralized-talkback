# E.18 Case-A Follower Topology -- Field Plan

**Status:** `REVIEW_PASSED` (2026-08-03) -- ready for contract-delta PR; field `NOT_AUTHORIZED` until PR merged  
**Parent contract:** [e18-attempt4c-a-minimal-field-contract.md](./e18-attempt4c-a-minimal-field-contract.md)  
**Archaeology:** [e18-case-a-precondition-archaeology.md](./e18-case-a-precondition-archaeology.md)  
**Postmortem (host run):** [e18-attempt4c-a-postmortem.md](./e18-attempt4c-a-postmortem.md) (`204240` = precondition discovery, not Case-A failure)

**Does not:** authorize field run | amend ADR | merge PR | substitute E.21.2 successor exercise

**PR posture:** `HOLD` -- close or replace #118; new contract-delta PR after this plan is reviewed.

---

## Review freeze (frozen until plan accepted)

Three constraints govern review and any future field authorization:

1. **Observer vs authority owner isolation** -- formal role names + preflight invariant (Section 2).
2. **Cold-start** -- operational hygiene only; never a Case-A semantic requirement (Section 4).
3. **Promotion gate** -- positive + negative facts frozen before field (Section 7).

**Field execution:** `NOT_AUTHORIZED` until plan review complete and contract-delta PR merged.

---

## 1. Purpose

Define the **only** approved field topology for Case-A membership observation after Attempt-4c-A host run `204240`.

Attempt-4c-A **(host)** is `COMPLETE / NOT_PROMOTED` -- explained by authority ownership short-circuit. It is **not** a Case-A failure sample. It is a valid **precondition discovery** run.

Attempt-4c-A **(follower)** is `NOT_STARTED` -- the only remaining verifiable path.

**Scope boundary:** A successful Case-A observation validates observer-side UNWIRED handling only. It does not validate authority election, relinquish, or cache eviction behavior.

---

## 2. Role isolation (frozen vocabulary)

Do **not** use runtime-ambiguous labels such as "authority candidate" in contract text.

| Symbol | Module | Role |
|--------|--------|------|
| `authorityOwnerUnderTest` | **M01** | Channel authority plane under stimulus (silent/offline for this exercise) |
| `observerModule` | **M02** | Follower observation surface (`auth-stream.log`) |
| `recoveryTarget` | **M03** | Recovery ingress target (WiFi flap); conference host |

### Conference layout

```text
recoveryTarget   = meeting host (initiator)
observerModule   = participant (follower)
authorityOwnerUnderTest = not in conference (silent / offline for stimulus)
```

### Core Case-A question

Case-A is **not** "did M01 send HELLO?" It is:

> Does **observerModule** sit on the **follower branch** of membership authority resolution?

### Preflight invariant (required)

```text
observerModule != resolvedMembershipAuthority
```

Equivalently on M02 auth-stream before stimulus:

```text
isLocalMembershipAuthority = false
no MEMBERSHIP_AUTHORITY_RESOLVE_TRACE reason=LOCAL_IS_MEMBERSHIP_AUTHORITY
```

**Failure mode to prevent (Attempt-4c-A host `204240`):**

```text
observerModule
    -> resolveBootstrapPrimary() / anchor path
    -> local authority
    -> LOCAL_IS_MEMBERSHIP_AUTHORITY
    -> CHECKED(true)    // Case-B family, not Case-A
```

### Retired topology (#118)

```text
observerModule = host + M01 silent
    -> BLOCKED_BY_RUNTIME_LIFECYCLE
    -> TOPOLOGY_CONTRADICTS_E21_3
```

Do not schedule field under retired layout.

---

## 3. Exercise scope

| Exercise | Purpose | Status |
|----------|---------|--------|
| Attempt-4c-A (host) | Precondition discovery | `COMPLETE / NOT_PROMOTED` |
| Attempt-4c-A (follower) | Membership observation during recovery ingress | `NOT_STARTED` |
| E.21.2 successor admission | Obligation lifecycle on successor episode | **Separate** -- not this harness |

Harness (follower only, when authorized): `run-attempt4c-baseline.ps1 -NoAutoFlap`

Forbidden: Joint / WITH_SUPPRESS | D1 deferred-intent | one harness for 4c-A and E.21.2

---

## 4. Cold-start discipline (operational hygiene)

```text
cold-start:
  purpose = reduce stale cache interference
  type    = operational hygiene

not:
  semantic requirement of Case-A
```

Cold-start may be used **only** to help reach `authorityDigestKnown=false` when runtime cannot evict `lastSeenAuthorityDigestByChannel`.

If preflight gates are unreachable without lifecycle reset:

```text
ABORT_NOT_CASE_A_PRECONDITION
```

Do **not** document or promote "Case-A requires cold-start." That would mix an implementation lifecycle workaround into the E.21 contract.

---

## 5. Preflight (observerModule auth-stream)

Run in stable meeting **before** `WATCH armed` / recoveryTarget WiFi flap.

### Required PASS (all)

```text
(1) observerModule != resolvedMembershipAuthority
(2) authorityDigestKnown=false on observerModule (CH-01 cache absent)
(3) observerModule joined as participant (recoveryTarget = host)
(4) authorityOwnerUnderTest silent -- no new GROUP HELLO establishing digest in preflight window
```

### ABORT

Any gate failure -> `ABORT_NOT_CASE_A_PRECONDITION`. Do not flap.

---

## 6. Stimulus sequence (when authorized)

```text
T0  Preflight PASS on observerModule (M02)
T1  authorityOwnerUnderTest (M01) silent before meeting; remain silent through T5
T2  recoveryTarget (M03) hosts; observerModule (M02) joins as participant
T3  Harness WATCH armed; stable meeting confirmed
T4  recoveryTarget WiFi OFF ~30s (manual if svc wifi disable ineffective)
T5  recoveryTarget WiFi ON; observe recovery reconciliation on observerModule auth-stream
```

**Log dir:** `logs/phase3c-b-attempt4c-a-YYYYMMDD-HHMMSS`  
**APK:** #113 HEAD `e3db13e`

---

## 7. Promotion gate (frozen before field)

Do **not** promote on `authorityDigestMissing=true` alone.

### Positive (all required, same coherent reconciliation window)

```text
resolvedAuthority != observerModule
authorityDigestKnown=false
membershipProbeDisposition=UNWIRED
fact.reason=MEMBERSHIP_AUTHORITY_UNWIRED
membershipEpochConverged=false
completion != RECOVERED
```

Log lines (observerModule auth-stream):

```text
CONTROL_RECONCILIATION_MEMBERSHIP_UNWIRED
RECOVERY_CONTROL_RECONCILIATION_FACT ... membershipProbeDisposition=UNWIRED
RECOVERY_CONTROL_RECONCILIATION_FACT ... membershipEpochConverged=false
RECOVERY_CONTROL_RECONCILIATION_FACT ... reason=MEMBERSHIP_AUTHORITY_UNWIRED
```

### Negative (must not appear in Case-A window)

```text
LOCAL_IS_MEMBERSHIP_AUTHORITY
CHECKED(true)
CHECKED(false)
AUTHORITY_DIGEST_MISSING only   (without UNWIRED disposition fact)
RECOVERED / SUCCESSOR_OBLIGATION_ADOPTED / TRANSFERRED
membershipEpochConverged=true with membershipProbeDisposition=UNWIRED
```

| Observation | Classification |
|-------------|----------------|
| `AUTHORITY_DIGEST_MISSING` without `UNWIRED` fact | `INSUFFICIENT` -- not promotion |
| `AUTHORITY_DIGEST_MISSING` + `CHECKED(false)` | `ATTEMPT4C_A_WRONG_CASE` -- **Case C**, not A |
| `LOCAL_IS_MEMBERSHIP_AUTHORITY` on observerModule | `NOT_PROMOTED` -- wrong topology |
| `CHECKED(true)` | `ATTEMPT4C_A_WRONG_CASE` -- Case B family |
| absent authority + `RECOVERED` | `E18_UNWIRED_DEFAULT_OPEN` |

### Promotion artifact (only if positive + negative satisfied)

```text
CASE_A_FIELD_VERIFIED
ATTEMPT4C_A_FIELD_PROMOTION.txt
```

---

## 8. Open risks (pre-field)

| Risk | Mitigation |
|------|------------|
| authorityOwnerUnderTest still dialable while silent | Verify invariant (1) on observerModule at preflight |
| Stale digest survives hangup | Cold-start hygiene only if needed; document in run notes |
| recoveryTarget host still elects observerModule as bootstrap primary | recoveryTarget must host; abort on `LOCAL_IS_MEMBERSHIP_AUTHORITY` |
| Exercise class drift | Attempt-4c baseline only |

---

## 9. PR strategy (frozen recommendation)

Do **not** rebase and merge #118 as-is. Original #118 assumed `observerModule = host` -- disproven by archaeology.

Recommended:

```text
#118 close or replace

New PR:
  docs(recovery): refine E.18 Case-A observer topology contract

Contents:
  - follower topology constraint + role isolation
  - 204240 postmortem reference
  - archaeology reference
  - follower field plan reference

Then (separate):
  field run -> CASE_A_FIELD_VERIFIED artifact -> promotion PR
```

---

## 10. Status

```text
Follower field plan           REVIEW_PASSED (ready for PR)
Host topology field           BLOCKED (do not schedule)
Attempt-4c-A (host)           COMPLETE / NOT_PROMOTED (precondition discovery)
Attempt-4c-A (follower)       NOT_STARTED
Case-A contract               READY (pending PR merge)
Case-A field                  NOT_AUTHORIZED (AUTHORIZED_PENDING_EXECUTION after merge)
Field execution               NOT_AUTHORIZED
PR #118                       SUPERSEDE (close; replace with contract-delta PR)
```

---

*Local field plan only. Does not alter ADR or production behavior.*
