# ADR-0043 Seam I — Decision Boundary Design

**Status:** **APPROVED FOR NEXT DESIGN LAYER** · **docs only** · **Implementation NOT AUTHORIZED**  
**Date:** 2026-08-08  
**ADR:** [0043-conference-recovery-membership-context-boundary.md](../adr/0043-conference-recovery-membership-context-boundary.md) (**ACCEPTED · Option A**)  
**Evidence layer:** [adr0043-seam-i-authority-context-evidence.md](./adr0043-seam-i-authority-context-evidence.md) (**APPROVED**)  
**Next layer:** [adr0043-seam-i-decision-ownership.md](./adr0043-seam-i-decision-ownership.md) (**DRAFT FOR REVIEW**)  
**Plan:** [adr0043-implementation-plan.md](./adr0043-implementation-plan.md)

---

## Status board

```text
ADR-0043                     ACCEPTED · OPTION_A
Seam I evidence              APPROVED
Seam I decision boundary     APPROVED FOR NEXT DESIGN LAYER
A / B / C selection          OPEN (not chosen)
Current focus                Seam I decision ownership
Implementation               NOT AUTHORIZED
Runtime                      FROZEN
Field / branch               NOT AUTHORIZED
```

---

## Layering (frozen)

```text
Evidence layer
    ↓
Decision boundary layer   ← this document
    ↓
Decision ownership layer  ← next
    ↓
Implementation            ← NOT AUTHORIZED
```

Do not mix: context evidence · resync decision · retry policy · FSM transition.

---

## Core invariant

### INV-0043-DB-001

> `GROUP_RESYNC` MUST NOT be dispatched without **PRESENT** `AuthorityMembershipContextEvidence` for the target conference scope.

Closes M0:

```text
WAS:  UNKNOWN/ABSENT → GROUP_RESYNC → NO_MEMBERSHIP_CONTEXT
MUST: PRESENT        → GROUP_RESYNC   (only legal emission gate)
```

`PRESENT` is the **only** evidence value that may enter RESYNC dispatch.

---

## Decision table (frozen)

| Evidence | Allowed for current resync attempt |
|----------|-------------------------------------|
| **PRESENT** | May continue / dispatch `GROUP_RESYNC` (subject to ownership layer) |
| **UNKNOWN** | Must **not** dispatch; A / B / C each need additional policy (selection **OPEN**) |
| **ABSENT** | Must **not** dispatch directly; A / B / C each need additional policy (selection **OPEN**) |

Inherited freezes:

```text
UNKNOWN MUST NOT be interpreted as PRESENT
UNKNOWN MUST NOT be interpreted as ABSENT
Evidence ≠ State
Historical existence alone MUST NOT satisfy PRESENT
Handler NO_MEMBERSHIP_CONTEXT remains terminal reject
```

---

## Candidate behaviors A / B / C (OPEN — no selection)

When evidence is **UNKNOWN** or **ABSENT**, issuer withholds `GROUP_RESYNC`. Candidates for the withheld path:

| ID | Behavior | Status |
|----|----------|--------|
| **A** | Wait for evidence | OPEN |
| **B** | Request context establishment | OPEN · Seam II space |
| **C** | Terminate current resync attempt | OPEN |

```text
A / B / C = OPEN
None authorizes GROUP_RESYNC without PRESENT.
```

### A — wait for evidence

```text
UNKNOWN → wait → PRESENT → resync
```

**Valid only if** there is an **explicit mechanism** capable of producing **fresh** `AuthorityMembershipContextEvidence` for the target scope.

```text
wait ≠ wait for authority (process / reachability)
wait = wait for a producer of fresh PRESENT/ABSENT evidence
```

Without such a mechanism, A risks infinite UNKNOWN wait — not a licensed default.

### B — request context establishment

```text
UNKNOWN → establish → PRESENT → resync
```

Architecturally complete relative to M0 (`recovery arrived but authority context absent`), but it **opens Seam II**:

```text
Seam I:  may we resync?
Seam II: how do we establish context?
```

**B must not enter Seam I implementation.** Retain as candidate only; establishment ownership / admission / acceptance timing are out of this layer.

```text
establish ≠ synthetic context creation
establish ≠ bypass authority acceptance
establish ≠ temporary membership invent
```

### C — terminate current resync attempt

```text
UNKNOWN → abort current attempt (no GROUP_RESYNC emission)
```

Minimizes the `RESYNC → NO_MEMBERSHIP_CONTEXT → timeout` path. Risks **silent abandonment** if no later recovery path is defined.

Out of scope for this document (must be answered before selecting C as sole path):

```text
- Does terminate close recovery obligation?
- May a later attempt re-enter the decision?
- Who owns the next decision epoch?
```

Terminate **must not** silently expand into whole-recovery FAILED by default (see failure-domain guard).

---

## Legality matrix (design, not selection)

| Constraint | A Wait | B Establish | C Terminate attempt |
|------------|--------|-------------|---------------------|
| INV-0043-DB-001 (no RESYNC without PRESENT) | Required | Required | Required |
| Soften `NO_MEMBERSHIP_CONTEXT` handler | Forbidden | Forbidden | Forbidden |
| Synthetic / recovery-only context | Forbidden | Forbidden | Forbidden |
| Topology / reachable ⇒ PRESENT | Forbidden | Forbidden | Forbidden |
| Enlarge timeout / watchdog budget | Out of scope | Out of scope | Out of scope |
| New recovery FSM states as deliverable | Out of scope | Out of scope | Out of scope |
| Silent obligation abandonment | Risk if wait forever | — | Risk if no re-entry |
| Cross into Option C / Seam II impl | No | Opens Seam II (defer) | No |

---

## What this layer does **not** decide

```text
NO selection among A / B / C
NO retry cadence / backoff
NO new membership or recovery timeout
NO field gate / runtime code
NO “UNKNOWN → fail whole recovery” as default
NO “UNKNOWN → continue resync” as default
NO Seam II establishment design
```

---

## Failure-domain guard

| Error | Forbidden |
|-------|-----------|
| A′ | `UNKNOWN → continue resync` |
| B′ | `UNKNOWN → fail recovery` (domain expansion) |

Legal withheld-path shape:

```text
withhold GROUP_RESYNC
+ (wait-for-evidence-producer | Seam-II establish | abandon current attempt)
```

---

## Relation to M0

Any accepted path (A, B, or C) must make M0’s premature emission **illegal** when evidence ≠ PRESENT at the decision epoch.

---

## One-line statement

> INV-0043-DB-001: only PRESENT may dispatch GROUP_RESYNC; without PRESENT, A/B/C remain OPEN policy options — wait only for an evidence producer, B stays Seam II, C must not silently abandon obligation — without selecting, implementing, or enlarging fail/timeout domains.
