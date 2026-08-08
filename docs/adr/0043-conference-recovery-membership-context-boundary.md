# ADR-0043: Conference Recovery Membership Context Boundary

## Status

**Status:** **ACCEPTED** (2026-08-08) — **Option A**  
**Architecture:** **CLOSED** — package **P1 + O1 + F1/F4** ([architecture close](../analysis/adr0043-architecture-close.md))  
**Implementation:** **v0 MERGED** · **Appendix B PASS** ([adjudication](../analysis/adr0043-appendix-b-adjudication.md)) · **Field NOT AUTHORIZED**  
**Does NOT authorize** field runs, timeout changes, handler softening, or scope beyond the authorization doc.

```text
ADR-0043
Decision:              ACCEPT
Selected option:       A
Architecture:          CLOSED · P1 + O1 + F1/F4
Implementation:        v0 MERGED · behavior PASS
Appendix B:              PASS
Field:                 NOT AUTHORIZED
```

**Upstream closed:**

| Layer | Status |
|-------|--------|
| ADR-0042 Transport send truth | CLOSED / FIELD PASS |
| Transport RCA (reattach delivery path) | CLOSED |
| RCA-0036 observation (Phases 1–3) | **CLOSED** (contract gap handed to this ADR) |

**Parents / evidence:**

- [ADR-0036](./0036-recovery-completion-authority-v1.md) — membership convergence authority
- [ADR-0023](./0023-conference-membership-mutation-authority-boundary.md) — membership mutation authority
- [RCA-0036 Phase 3](../analysis/rca0036-membership-context-contract-observation.md) — Q4–Q6 classified
- [Option matrix](../analysis/adr0043-option-matrix.md) — B rejected standalone; C deferred
- Seed: `logs/adr0042-p0-narrow-20260808-162002/` · `ADJUDICATION_RCA0036_P3.txt`

```text
ADR-0042                  CLOSED

RCA-0036                  CLOSED (observation)
Membership Contract       ADR-0043 ACCEPTED

ADR-0043:
    Decision              OPTION_A
    Implementation plan   APPROVED FOR REVIEW
    Gate M0               DONE · CONTEXT_NOT_ESTABLISHED (+ KEY_SCOPE_MISMATCH)
    Seam I evidence       APPROVED
    Seam I decision       APPROVED · A/B/C OPEN · INV-0043-DB-001
    Seam I ownership      APPROVED · O1/O2/O3 OPEN · INV-0043-OWN-001
    Seam I truth          APPROVED · T1/T2/T3 OPEN · INV-0043-TRUTH-001 TARGET
    Seam I mapping        APPROVED · Observed MIXED · ARCHITECTURE GAP
    Projection boundary   APPROVED · INV-0043-PROJ-001 · P1/P2/P3 OPEN · P4 OBSERVED ONLY
    Freshness boundary    APPROVED · INV-0043-F-001 · F1–F5 OPEN
    Minimum F set         APPROVED · ACCEPT A · F1+F4 · INV-0043-F-MIN-001
    Invalid patterns      APPROVED · INV-0043-IP-001 · IP-1…IP-8
    P comparison          APPROVED
    P decision criteria   APPROVED · C-OWN-MIN · INV-0043-PROJ-OWN-001
    P1 vs P2              APPROVED · INV-0043-P2-BOUNDARY-001
    Selection             ACCEPTED · v0 projection baseline = P1
    P1 design boundary    APPROVED
    P1 auth boundary      APPROVED · INV-0043-P1-AUTH-001
    O constraints         APPROVED · O-INV-001…006
    O evaluation          APPROVED · E1–E5
    O decision memo       APPROVED (pre-selection)
    O-selection grill     ACCEPTED · O1 · P1+O1+F1/F4
    Class II / Seam II    DEFERRED
    O1 boundary           APPROVED
    Architecture close    APPROVED · architecture CLOSED
    Implementation auth   ACCEPTED · v0 AUTHORIZED · Field NOT
    Implementation        v0 AUTHORIZED (narrow seam)
    Runtime               FROZEN except authorized seam

X1-B                      NOT ENTERED
X2                        HOLD
```

---

## 1. Problem statement

Conference recovery may issue membership resync before the membership authority has an accepted membership context for the recovered conference identity.

Observed chain (seed, classified):

```text
Recovery
    ↓
GROUP_RESYNC_REQUEST  (envelope sessionId = conference id)
    ↓
Authority
    ↓
lookup sessions[conferenceId]  → MISS  (log: requestSession=UNKNOWN)
    ↓
resolveMembershipContextForResync(channel)
    → no accepted GROUP / CONFERENCE
    ↓
NO_MEMBERSHIP_CONTEXT
    ↓
terminal reject  (no snapshot emit)
```

**Not** the problem: authority “没等够” · membership slow · network unknown · ADR-0042 regression.

**Is** the problem: recovery resync **precondition** vs authority **accepted membership context** lifecycle mismatch.

---

## 2. Decision (ACCEPTED) — Option A

```text
Recovery MUST establish / restore accepted membership context
before issuing GROUP_RESYNC that requires a TalkbackSession snapshot.
```

Corrected call order:

```text
Recovery
   ↓
Accepted Membership Context  (TalkbackSession, accepted)
   ↓
GROUP_RESYNC
   ↓
Snapshot
```

### Ownership freeze

```text
TalkbackSession
        ↓
membership snapshot
        ↓
authority resync answer
```

Existing snapshot ownership stays on **accepted `TalkbackSession`**. This ADR does **not** invent a new snapshot owner.

### Why not Option C (deferred, not selected)

C would change snapshot ownership / identity / lifecycle models — architecture expansion, not the minimal contract fix. Re-open C only if future evidence proves:

```text
Recovery cannot recreate accepted TalkbackSession membership context
```

### Why not standalone Option B (rejected)

Handler cannot emit a truthful snapshot without a membership context object. “Accept without context” either invents context or cannot snapshot — both out of scope here.

---

## 3. Non-goals (frozen)

```text
NO:
- create synthetic membership context
- change GROUP_RESYNC handler acceptance rules
- introduce recovery-only snapshot ownership (Option C)
- modify timeout / watchdog budget
- modify transport delivery semantics
- reopen ADR-0042
- retry NO_MEMBERSHIP_CONTEXT as a substitute for precondition
- X1 / X1-B / X2 / UI changes
- runtime patch without a separate implementation-plan ACCEPT
```

---

## 4. Semantic freeze (from RCA-0036)

| Token | Meaning |
|-------|---------|
| `requestSession=UNKNOWN` | Envelope `sessionId` not in authority `sessions[]` |
| `NO_MEMBERSHIP_CONTEXT` | No accepted GROUP/CONFERENCE for channel — **terminal reject** for that request |
| `GROUP_RESYNC_REQUEST_SENT` | Local dispatch only ≠ convergence |

Roll-up: `AUTHORITY_RESYNC_CONTEXT_NOT_RESOLVED` — addressed at **contract** layer by Option A precondition; not by timeout.

---

## 5. Implementation gate

**Architecture CLOSED** · **v0 Implementation AUTHORIZED**:

- [adr0043-architecture-close.md](../analysis/adr0043-architecture-close.md) (**APPROVED**)
- [adr0043-implementation-authorization.md](../analysis/adr0043-implementation-authorization.md) (**ACCEPTED** · Field **NOT**)

```text
v0 runtime (Seam I P1+O1+F1/F4 gate) = AUTHORIZED
Field = NOT AUTHORIZED
Handler soften / timeout / Seam II / Class II = FORBIDDEN
```

---

## 6. One-line statement

> ADR-0043 Option A: recovery must not GROUP_RESYNC without authority-grounded PRESENT (P1+O1+F1/F4); architecture closed; v0 impl authorized; field not; no handler soften / Option C.
