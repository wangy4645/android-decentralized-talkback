# ADR-0043 Seam I — Authority Membership Context Evidence

**Status:** **APPROVED** · **docs only** · **Implementation NOT AUTHORIZED**  
**Date:** 2026-08-08  
**ADR:** [0043-conference-recovery-membership-context-boundary.md](../adr/0043-conference-recovery-membership-context-boundary.md) (**ACCEPTED · Option A**)  
**Plan:** [adr0043-implementation-plan.md](./adr0043-implementation-plan.md) (**APPROVED FOR REVIEW**)  
**Next layers:** [adr0043-seam-i-decision-boundary.md](./adr0043-seam-i-decision-boundary.md) (**APPROVED**) · [adr0043-seam-i-decision-ownership.md](./adr0043-seam-i-decision-ownership.md) (**APPROVED**) · [adr0043-seam-i-context-truth-authority.md](./adr0043-seam-i-context-truth-authority.md) (**DRAFT**)
**M0:** [ADJUDICATION_ADR0043_M0.txt](../../logs/adr0042-p0-narrow-20260808-162002/ADJUDICATION_ADR0043_M0.txt) · `CONTEXT_NOT_ESTABLISHED` (+ `KEY_SCOPE_MISMATCH`)

---

## Status board

```text
ADR-0043                     ACCEPTED · OPTION_A
Implementation plan          APPROVED FOR REVIEW
M0 context probe             COMPLETE
Seam I evidence              APPROVED
Seam I decision boundary     APPROVED · A/B/C OPEN
Current focus                Context truth authority
Implementation               NOT AUTHORIZED
Runtime                      FROZEN
Field / branch               NOT AUTHORIZED
```

---

## Design goal (frozen)

Not:

> “Is M01 alive / reachable?”

Is:

> “Is there sufficient evidence that the membership authority holds an **accepted membership context** capable of producing a TalkbackSession snapshot?”

```text
reachable ≠ context exists
Evidence ≠ State
```

M0 / Phase 2 already showed: M01 can **receive** `GROUP_RESYNC_REQUEST` and still lack accepted context.

**Evidence is not a membership lifecycle FSM.** Do not expand:

```text
UNKNOWN → create recovery membership state
```

---

## 1. Evidence definition

Plan-level evidence model only — **not** a new runtime FSM state in this document.

```text
AuthorityMembershipContextEvidence
```

| Value | Meaning |
|-------|---------|
| **UNKNOWN** | No sufficient evidence yet |
| **PRESENT** | Authority confirms accepted membership context exists (can source snapshot) |
| **ABSENT** | Authority confirms context removed / not established |

```text
UNKNOWN ≠ FAILED
ABSENT  ≠ transport fail
PRESENT ≠ recovery complete
PRESENT ≠ durable existence   (INV-0043-PROJ-001)
stale PRESENT → UNKNOWN
```

**UNKNOWN freeze (mandatory):**

```text
UNKNOWN MUST NOT be interpreted as PRESENT.
UNKNOWN MUST NOT be interpreted as ABSENT.
```

Forbidden shortcuts:

| Error | Pattern | Outcome |
|-------|---------|---------|
| A | `if unknown: continue resync` | Premature RESYNC → `NO_MEMBERSHIP_CONTEXT` (M0 recurrence) |
| B | `if unknown: fail recovery` | Failure domain enlarged beyond evidence |

When UNKNOWN: withhold GROUP_RESYNC; legal next moves are wait / establish evidence / abandon current operation — chosen in [decision boundary](./adr0043-seam-i-decision-boundary.md), not here as retry policy.

**Forbidden inferences:**

```text
topology says M01 exists          ⇏  PRESENT
authorityReachable == true        ⇏  PRESENT
PEER_EDGE_READY                   ⇏  PRESENT
GROUP_RESYNC_REQUEST_SENT         ⇏  PRESENT
stale TOPOLOGY sessionAccepted    ⇏  PRESENT   (M0)
```

---

## 2. Producer / consumer ownership

### Consumer (Option A issuer)

```text
maybeRequestMembershipConvergenceForConferenceRecovery
onPeerEdgeSignalingReady (resync retry)
→ requestMembershipConvergenceFromAuthority
```

**Owns:** deciding whether **to issue** `GROUP_RESYNC` given evidence.

```text
LINK_READY / BIDIRECTIONAL_READY / PEER_EDGE_READY
        ↓
AuthorityMembershipContextEvidence ?
        ↓
   PRESENT  → may issue GROUP_RESYNC
   UNKNOWN / ABSENT → must NOT issue GROUP_RESYNC
                      (see decision boundary layer)
```

### Producer (who may assert PRESENT / ABSENT)

Evidence must be **authority-grounded** (or equivalently authoritative membership plane), not requester guesswork.

Producer **does not** mean:

```text
authority process alive / reachable
```

Producer **means** a source that can assert:

```text
authority has accepted membership context
for the target conference / channel scope
```

Candidates for a future impl plan (not chosen here):

| Producer class | Role |
|----------------|------|
| Authority explicit advertisement | “accepted context live / gone” fact for target scope |
| Authority-side session lifecycle observation mirrored to requester | only if trustworthy and fresh |
| Shared membership-authority plane already trusted for digest | only if it **proves accepted session**, not mere digest match |

**Requester must not invent PRESENT** from local conference alone.

### Handler (unchanged last line of defense)

```text
NO_MEMBERSHIP_CONTEXT → terminal reject for that request
```

Handler is **not** a compensation layer and **must not** be relaxed by Seam I.

---

## 3. Evidence freshness boundary

M0 window:

```text
16:20:20  context destroyed on authority
16:22:29  resync issued (stale topology still “accepted”)
```

Question is **not**:

> “Did authority ever have context?”

Question is:

> “At this recovery decision moment, is there valid evidence?”

Frozen wording:

```text
AuthorityMembershipContextEvidence is evaluated
against the current recovery decision epoch.

Historical existence alone MUST NOT satisfy PRESENT.
```

No new field required by this clause — only a misuse guard against stale topology / aged advertisements.

---

## 4. UNKNOWN / ABSENT handling (pointer)

When evidence is UNKNOWN or ABSENT, issuer **withholds** GROUP_RESYNC.

Legal behaviors for the **current resync attempt** are specified in:

→ [adr0043-seam-i-decision-boundary.md](./adr0043-seam-i-decision-boundary.md)

Seam II remains **non-P0**: establish path must not bypass authority acceptance or create temporary membership.

---

## 5. Non-goals

```text
NO:
- runtime implementation in this document
- new recovery FSM states as a deliverable of this layer
- retry policy design
- timeout / watchdog budget change
- GROUP_RESYNC handler acceptance change
- synthetic / recovery-only membership context (Option C)
- field validation / branch open
- treating authorityReachable / topology as PRESENT
- ADR-0042 / X1 / completion reopen
```

---

## 6. Relation to M0

```text
M0: CONTEXT_NOT_ESTABLISHED at T_reject
    + KEY_SCOPE_MISMATCH
    + premature issuer RESYNC
```

Seam I must require **fresh** PRESENT evidence — stale `sessionAccepted=true` topology is explicitly insufficient.

---

## 7. One-line statement

> Seam I defines who may declare that authority accepted membership context exists before GROUP_RESYNC — capability evidence (UNKNOWN/PRESENT/ABSENT), not reachability — without implementing, softening the handler, or inventing context.
