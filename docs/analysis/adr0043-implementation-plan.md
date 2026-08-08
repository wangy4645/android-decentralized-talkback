# ADR-0043 Implementation Plan

**Status:** **APPROVED FOR REVIEW** · **v0 runtime AUTHORIZED** ([implementation authorization](./adr0043-implementation-authorization.md)) · **Field NOT AUTHORIZED**  
**Date:** 2026-08-08  
**ADR:** [0043-conference-recovery-membership-context-boundary.md](../adr/0043-conference-recovery-membership-context-boundary.md) (**ACCEPTED · Option A**)  
**Parents:** [adr0043-option-matrix.md](./adr0043-option-matrix.md) · RCA-0036 P3 · seed `logs/adr0042-p0-narrow-20260808-162002/`  
**M0 result:** [ADJUDICATION_ADR0043_M0.txt](../../logs/adr0042-p0-narrow-20260808-162002/ADJUDICATION_ADR0043_M0.txt)

```text
ADR-0042                       CLOSED
RCA-0036                       CLASSIFIED / observation CLOSED
ADR-0043                       ACCEPTED · OPTION_A
ADR-0043 implementation plan   APPROVED FOR REVIEW
M0 context probe               COMPLETE
Seam I evidence                APPROVED
Seam I decision boundary       APPROVED · A/B/C OPEN · INV-0043-DB-001
Seam I decision ownership      APPROVED · O1/O2/O3 OPEN · INV-0043-OWN-001
Seam I context truth           APPROVED · T1/T2/T3 OPEN · INV-0043-TRUTH-001 TARGET
Seam I truth mapping           APPROVED · Observed MIXED · ARCHITECTURE GAP
Projection boundary            APPROVED · INV-0043-PROJ-001 · P1/P2/P3 OPEN · P4 OBSERVED ONLY
Freshness boundary             APPROVED · INV-0043-F-001 · F1–F5 OPEN
Minimum F set                  APPROVED · ACCEPT A · F1+F4 · INV-0043-F-MIN-001
Invalid patterns               APPROVED · INV-0043-IP-001 · IP-1…IP-8 ACCEPT
P comparison                   APPROVED
P decision criteria            APPROVED · C-OWN-MIN · PROJ-OWN-001 · order P1≳P2>>P3
P1 vs P2                       APPROVED · P1 lower second-truth · P2-BOUNDARY-001
Projection selection           ACCEPTED · v0 baseline = P1
P1 design boundary             APPROVED
P1 authorization boundary      APPROVED · INV-0043-P1-AUTH-001
O-selection constraints        APPROVED · O-INV-001…006
O evaluation criteria          APPROVED · E1–E5 · order O1≳O2>>O3
O decision memo                APPROVED (pre-selection complete)
O-selection                    ACCEPTED · O1
O1 boundary                    APPROVED
Architecture close             APPROVED · architecture CLOSED
Architecture package           P1 + O1 + F1/F4 · Class II DEFERRED · Seam II DEFERRED
Implementation auth            ACCEPTED · v0 AUTHORIZED
Current focus                  Implement Seam I gate (P1+O1+F1/F4)
                               → adr0043-implementation-authorization.md
P2 / O2                        RESERVED
P3 / O3                        FALLBACK ONLY
Implementation                 v0 AUTHORIZED (narrow)
Field                          NOT AUTHORIZED
Runtime                        FROZEN except authorized seam
```

**Purpose:**

> At which recovery stage is Option A satisfiable, and what minimal seam applies — without authorizing code.

---

## 0. Gate M0 — Context Ownership Probe (desk) ✅

**Goal:** Compare M01 accepted membership context vs topology / recovery view at reject — not to fix.

| Label | Meaning |
|-------|---------|
| `CONTEXT_EXISTS_BUT_NOT_FOUND` | Context live but lookup key miss |
| `CONTEXT_NOT_ESTABLISHED` | No accepted context at reject |
| `KEY_SCOPE_MISMATCH` | Envelope key vs resolve key disagree |

### M0 result (seed)

```text
PRIMARY:   CONTEXT_NOT_ESTABLISHED   (at T_reject 16:22:26)
SECONDARY: KEY_SCOPE_MISMATCH        (envelope=conference 88a94716… never on M01;
                                      channel resolve needed GROUP|CONFERENCE — GROUP gone)
NOT:       CONTEXT_EXISTS_BUT_NOT_FOUND at T_reject
```

Timeline:

```text
16:20:20  M01 grp:CH-01 HANGUP / GROUP_PTT→IDLE / remote hangup
          last GROUP_DIGEST · last TOPOLOGY sessionAccepted=true (stale thereafter)
16:20:20→16:22:26  no further sessionId=grp:CH-01 activity
16:22:26  M01 GROUP_RESYNC_HANDLER_REJECTED NO_MEMBERSHIP_CONTEXT
          requestSession=UNKNOWN requestSessionId=88a94716…
16:22:29  M03 MEMBERSHIP_CONVERGENCE_REQUESTED / later SENT  (issuer after authority context gone)
```

**Implication for seams:** Seam I must treat “authority has accepted context” as a **live** prerequisite — topology age alone is insufficient. Requester issued while authority context was **not established** (torn down / never conference).

---

## 1. Option A restatement

```text
Recovery MUST establish / restore accepted membership context
before issuing GROUP_RESYNC requiring TalkbackSession snapshot.
```

Ownership stays on issuer (recovery/resync request path). Handler stays strict.

---

## 2. Current call sites (desk map)

| Stage / trigger | Function | Local preconditions today |
|-----------------|----------|---------------------------|
| `LINK_READY` / transport recovery triggers | `maybeRequestMembershipConvergenceForConferenceRecovery` | transport recovered · episode open · not converged · digest CHECKED · not in-flight |
| `PEER_EDGE_READY` | retry | pending record · local conference accepted · edge recovering |

Missing today: proof that **authority** holds accepted GROUP|CONFERENCE for channel.

**Stage answer:** At LINK_READY / PEER_EDGE_READY requester knows **whom to ask**, not that authority **can answer**. Option A not satisfied by current gates.

---

## 3. Seam selection (APPROVED FOR REVIEW)

### ✅ Seam I — primary (P0 candidate when impl authorized)

> **Do not issue GROUP_RESYNC until authority accepted membership context is evidenced.**

Evidence model: [adr0043-seam-i-authority-context-evidence.md](./adr0043-seam-i-authority-context-evidence.md) (**APPROVED**).  
Decision boundary: [adr0043-seam-i-decision-boundary.md](./adr0043-seam-i-decision-boundary.md) (**APPROVED** · A/B/C **OPEN** · **INV-0043-DB-001**).  
Decision ownership: [adr0043-seam-i-decision-ownership.md](./adr0043-seam-i-decision-ownership.md) (**APPROVED** · O1/O2/O3 **OPEN** · **INV-0043-OWN-001**).  
Context truth: [adr0043-seam-i-context-truth-authority.md](./adr0043-seam-i-context-truth-authority.md) (**APPROVED** · T1/T2/T3 **OPEN** · **INV-0043-TRUTH-001 TARGET**).  
Context mapping: [adr0043-seam-i-context-truth-mapping.md](./adr0043-seam-i-context-truth-mapping.md) (**APPROVED** · observed **MIXED** · **ARCHITECTURE GAP**).  
Projection boundary: [adr0043-context-projection-boundary.md](./adr0043-context-projection-boundary.md) (**APPROVED** · **INV-0043-PROJ-001** · P1/P2/P3 **OPEN** · P4 **OBSERVED ONLY**).  
Freshness boundary: [adr0043-projection-evidence-freshness-boundary.md](./adr0043-projection-evidence-freshness-boundary.md) (**APPROVED** · **INV-0043-F-001** · F1–F5 **OPEN**).  
Minimum F set: [adr0043-freshness-minimum-sufficient-set.md](./adr0043-freshness-minimum-sufficient-set.md) (**APPROVED** · **ACCEPT A** · **INV-0043-F-MIN-001** · F1+F4).  
Invalid patterns: [adr0043-projection-invalid-patterns.md](./adr0043-projection-invalid-patterns.md) (**APPROVED** · **INV-0043-IP-001** · IP-1…IP-8).  
P comparison: [adr0043-projection-p-comparison.md](./adr0043-projection-p-comparison.md) (**APPROVED**).  
P decision criteria: [adr0043-projection-p-decision-criteria.md](./adr0043-projection-p-decision-criteria.md) (**APPROVED** · **C-OWN-MIN** · **INV-0043-PROJ-OWN-001**).  
P1 vs P2: [adr0043-projection-p1-vs-p2.md](./adr0043-projection-p1-vs-p2.md) (**APPROVED** · **INV-0043-P2-BOUNDARY-001**).  
Selection memo: [adr0043-projection-selection-memo.md](./adr0043-projection-selection-memo.md) (**ACCEPTED** · **v0 = P1**).  
P1 design boundary: [adr0043-p1-design-boundary.md](./adr0043-p1-design-boundary.md) (**APPROVED**).  
P1 authorization boundary: [adr0043-p1-authorization-boundary.md](./adr0043-p1-authorization-boundary.md) (**APPROVED** · **INV-0043-P1-AUTH-001**).  
O-selection constraints: [adr0043-o-selection-constraint-memo.md](./adr0043-o-selection-constraint-memo.md) (**APPROVED** · **O-INV-001…006**).  
O evaluation criteria: [adr0043-o-evaluation-criteria.md](./adr0043-o-evaluation-criteria.md) (**APPROVED** · **E1–E5**).  
O decision memo: [adr0043-o-decision-memo.md](./adr0043-o-decision-memo.md) (**APPROVED**).  
O-selection decision: [adr0043-o-selection-decision.md](./adr0043-o-selection-decision.md) (**ACCEPTED** · **O1** · package **P1+O1+F1/F4**).  
O1 boundary: [adr0043-o1-boundary.md](./adr0043-o1-boundary.md) (**APPROVED**).  
Architecture close: [adr0043-architecture-close.md](./adr0043-architecture-close.md) (**APPROVED** · **architecture CLOSED**).  
Implementation authorization: [adr0043-implementation-authorization.md](./adr0043-implementation-authorization.md) (**ACCEPTED** · **v0 AUTHORIZED** · **Field NOT**).

**Architecture CLOSED.** **v0 implementation AUTHORIZED** for Seam I gate only.

Violation today = **issuer too early**, not handler too strict.

### Seam II — design space only (not P0 commitment)

May later mean:

```text
Recovery → request membership context establishment
        → authority accepted context
        → GROUP_RESYNC
```

**Hard limits:**

```text
establish ≠ synthetic context creation
establish ≠ bypass authority acceptance
establish ≠ temporary membership invent
```

Otherwise slides to Option C. **Not in P0 implementation promise.**

### Seam III — envelope prefer GROUP

Insufficient alone (M0: GROUP may be gone on authority).

---

## 4. Scope / non-goals (frozen)

```text
NO:
- GROUP_RESYNC handler modification
- retry as substitute for precondition
- timeout / watchdog change
- synthetic membership
- recovery-only snapshot (Option C)
- X1 / completion / ADR-0042 / UI
```

---

## 5. Authorization ladder

```text
ADR-0043 ACCEPT Option A              ✅
Plan APPROVED FOR REVIEW              ✅
Gate M0 desk probe                    ✅
Seam I evidence                       ✅ APPROVED
Seam I decision boundary              ✅ APPROVED · A/B/C OPEN · INV-0043-DB-001
Seam I decision ownership             ✅ APPROVED · O1/O2/O3 OPEN · INV-0043-OWN-001
Seam I context truth authority        ✅ APPROVED · T1/T2/T3 OPEN · INV-0043-TRUTH-001 TARGET
Seam I context truth mapping          ✅ APPROVED · Observed MIXED · ARCHITECTURE GAP
Context projection boundary           ✅ APPROVED · INV-0043-PROJ-001 · P4 OBSERVED ONLY
Projection evidence freshness         ✅ APPROVED · INV-0043-F-001 · F1–F5 OPEN
Minimal sufficient F set              ✅ APPROVED · ACCEPT A · F1+F4 · INV-0043-F-MIN-001
Projection invalid patterns           ✅ APPROVED · INV-0043-IP-001 · IP-1…IP-8
P1/P2/P3 comparison                   ✅ APPROVED
P decision criteria                   ✅ APPROVED · C-OWN-MIN · PROJ-OWN-001
P1 vs P2                              ✅ APPROVED · P2-BOUNDARY-001
Projection selection memo             ✅ ACCEPTED · v0 = P1 (architecture only)
P1 design boundary                    ✅ APPROVED
P1 authorization boundary             ✅ APPROVED · INV-0043-P1-AUTH-001
O-selection constraint memo           ✅ APPROVED · O-INV-001…006
O1/O2/O3 evaluation criteria          ✅ APPROVED · E1–E5
O decision memo (pre-selection)       ✅ APPROVED
O-selection decision                  ✅ ACCEPTED · O1 · P1+F1/F4 · Class II/Seam II DEFERRED
O1 boundary                           ✅ APPROVED
Architecture close                    ✅ APPROVED · architecture CLOSED
Implementation authorization          ✅ ACCEPTED · v0 AUTHORIZED · Field NOT
Separate explicit field authorization ← required before field
Red-then-green · merge · field        later, separately authorized
```

---

## 6. One-line

> Plan approved for review with Seam I primary: after M0, seed shows CONTEXT_NOT_ESTABLISHED on authority at reject plus premature issuer RESYNC — gate issuer on live authority context evidence; do not soften handler or enter Seam II/C yet.
