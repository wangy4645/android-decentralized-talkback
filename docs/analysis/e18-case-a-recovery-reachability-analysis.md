# E.18 Case-A Recovery Reachability Analysis (v2)

**Status:** `ARCHAEOLOGY_OPEN` (2026-08-04)  
**Does not:** amend ADR | modify code | authorize field replay | claim `NOT_REACHABLE` | claim `Case-A failed`  
**Inputs:** [e18-attempt4c-a-postmortem.md](./e18-attempt4c-a-postmortem.md) | [e18-case-a-precondition-archaeology.md](./e18-case-a-precondition-archaeology.md) | [e18-attempt4c-a-follower-field-plan.md](./e18-attempt4c-a-follower-field-plan.md)  
**Runs:** `204240` (host) | `125125` (follower)

**Field posture:** `PAUSED` — no replay until this analysis closes.

---

## Question (frozen)

> Is there a **real recovery episode** that simultaneously satisfies:

```text
T0:     observer != resolvedMembershipAuthority
AND
Tprobe: observer != resolvedMembershipAuthority
AND
        authorityDigestKnown=false
AND
        probe disposition = UNWIRED
```

This is **Case-A Reachability v2**. It supersedes the narrower v1 question ("digest absent at preflight") without retracting v1 findings.

---

## What field evidence already shows

| Run | Topology | T0 `observer != authority` | Tprobe `observer != authority` | `authorityDigestKnown=false` | `UNWIRED` | Classification |
|-----|----------|----------------------------|--------------------------------|-------------------------------|-----------|----------------|
| `204240` | host (`M02` host) | **FAIL** (already local authority) | — | partial (stale digest) | no | `PRECONDITION_NOT_SATISFIED` / setup error |
| `125125` | follower (`M03` host, `M02` observer) | **PASS** | **FAIL** (promoted to authority) | PASS (clean observer runtime) | no | `ATTEMPT4C_A_WRONG_CASE` / stimulus-induced transition |

**Follower requirement not yet met:** no run has reached `Tprobe` with observer still on the follower branch.

**Do not merge** `204240` and `125125` into one failure narrative — they are different failure classes.

---

## Core finding (125125)

Case-A is not blocked only by "digest absent cannot be observed." The deeper issue:

> **Authority ownership is a dynamic variable during recovery**, not a static preflight fact.

```text
Initial (rest):

    M03 (authority)
     |
    M02 (observer)


After M03 host ICE lost (recovery stimulus):

    AUTHORITY_LOST
         |
         v
    bootstrap reevaluate
         |
         v
    M02 promoted -> LOCAL_IS_MEMBERSHIP_AUTHORITY
         |
         v
    CHECKED(converged=true)
```

Preflight invariant:

```text
observerModule != resolvedMembershipAuthority
```

is currently enforced at **T0 only**. Case-A requires it at **Tprobe** (membership probe evaluation time).

**Contract gap (documented, not patched in this note):**

| Invariant | Scope today | Case-A needs |
|-----------|-------------|--------------|
| `observer != authority` | T0 preflight | T0 **and** Tprobe |
| `authorityDigestKnown=false` | T0 preflight | T0 **and** Tprobe (if digest can be written mid-episode) |

---

## Authority promotion trigger chain (125125, code-aligned)

Observed sequence on M02 `auth-stream` (`logs/phase3c-b-attempt4c-a-follower-20260804-125125`):

| Time | Event | Code / log anchor |
|------|-------|-------------------|
| 12:51:41.546 | M03 ICE `DISCONNECTED` | recovery edge opened |
| 12:51:41.633 | `authorityReachable=false` `hostModule=M03` `hostIce=DISCONNECTED` | `emitConferenceRuntimeProjection` |
| 12:51:41.641 | `AUTHORITY_TIMELINE ... reachable=false cause=host_ice_lost` | `maybeAuditAuthorityTransition` |
| 12:51:41.642 | `RECOVERY_REEVALUATE trigger=AUTHORITY_LOST` | `maybeNotifyRecoveryReachabilityChanged` |
| 12:51:41.643 | `LOCAL_IS_MEMBERSHIP_AUTHORITY` | `MembershipAuthorityResolver` short-circuit |
| 12:51:41.644 | `authorityId=M02` `converged=true` | `CONTROL_RECONCILIATION_MEMBERSHIP_CHECKED` |
| 12:51:41.645 | `membershipProbeDisposition=CHECKED` | not `UNWIRED` |

### `isMembershipAuthority` (production)

```kotlin
// TalkbackCoordinator.kt
if (session.anchorModuleId == localModuleId) return true
return resolveBootstrapPrimary(dialableRemoteModuleIds() + localModuleId) == localModuleId
```

On a two-party conference with M03 unreachable, M02 can become bootstrap primary 뿯↽ local membership authority without any M01 HELLO or digest seeding.

### `AUTHORITY_LOST` path (participant observer)

`maybeAuditAuthorityTransition` fires on follower sessions when host ICE reachability drops:

```kotlin
cause = if (authorityReachable) "host_ice_connected" else "host_ice_lost"
// -> RecoveryReevaluateTrigger.AUTHORITY_LOST
```

**Implication:** Attempt-4c-A's approved stimulus (M03 WiFi flap on `recoveryTarget`) is structurally coupled to authority-loss reevaluation on the observer.

---

## Open archaeology questions

### Q1 — Promotion necessity

When host ICE is lost on a two-party follower topology, is `LOCAL_IS_MEMBERSHIP_AUTHORITY` on M02 **expected** runtime behavior or an implementation side effect?

Sub-questions:

- Does `resolveBootstrapPrimary` intentionally elevate the remaining dialable peer?
- Is there a conference-session path where authority remains "absent" (UNWIRED) while host is offline?

### Q2 — Stable authority assumption in E.21.3

Does ADR 뿯½E.21.3 Case A implicitly assume:

```text
authority ownership stable across recovery probe window
```

If yes, Attempt-4c-A's M03 flap may be **outside** the Case-A observation domain — not because UNWIRED is unreachable in general, but because this stimulus changes the domain.

If no, contract must state what "authority absent" means when the authority host is the recovery target.

### Q3 — Recovery stimuli inventory

Without changing product behavior, enumerate recovery episodes that **do not** trigger authority promotion on the observer:

| Stimulus class | Observer promotion? | Case-A candidate? |
|----------------|----------------------|-------------------|
| M03 host WiFi flap (current 4c-A) | **yes** (125125) | **no** (wrong case) |
| M01 silent (authority plane) | TBD | TBD |
| Participant-only flap (non-authority) | TBD | TBD |
| Authority host remains reachable | TBD | TBD |

**Discipline:** do not engineer artificial freezes (block bootstrap, flap M01, etc.) to force UNWIRED — that would test harness constraints, not production semantics.

### Q4 — Digest vs ownership at Tprobe

125125 showed `authorityHash=null` at probe time but still `CHECKED` via `LOCAL_IS_MEMBERSHIP_AUTHORITY`.

Confirms archaeology Q3 from precondition note:

```text
digest absent ≠ authority absent
ownership absent (at Tprobe) is the binding gate for UNWIRED
```

---

## Status label (do not overstate)

```text
Case-A reachability:
    BLOCKED_BY_RECOVERY_AUTHORITY_TRANSITION
```

**Not:**

```text
NOT_REACHABLE          # not proven — other stimuli unexplored
Case-A failed            # wrong — wrong-case / wrong stimulus class
UNWIRED unreachable      # wrong — conflates stimulus with semantics
```

---

## Relationship to E.18 closure and R4

This finding affects **coverage boundary**, not the **safety boundary** already established by Case-C:

- Case-C: wired mismatch 뿯↽ `CHECKED(false)` — verified
- Case-A: unwired 뿯↽ `UNWIRED` — **field path under Attempt-4c-A follower + M03 flap not yet demonstrated**

For R4: authority ownership transition during recovery is a **lifecycle fact** that any adoption / transfer design must account for. This analysis does not unlock R4-impl.

---

## Recommended next steps (ordered)

1. Close Q1–Q2 via code + ADR trace (no field).
2. Decide whether Attempt-4c-A contract should document **stimulus class** separately from Case-A semantics.
3. If a non-promoting recovery stimulus exists in production, define it in a **new** field plan — do not replay `125125`.
4. Update closure review status board (see [e18-closure-review.md](./e18-closure-review.md)).

**Frozen:**

- No field replay
- No stimulation changes (no flap retarget, no bootstrap freeze, no resolver/cache edits)

---

*Local analysis artifact. Does not change ADR acceptance criteria or implementation behavior.*
