# ADR-0043 — Projection Invalid Patterns

**Status:** **APPROVED FOR NEXT DESIGN LAYER** · **docs only** · **Implementation NOT AUTHORIZED**  
**Date:** 2026-08-08  
**Wording patch:** IP-1 digest role · IP-4 UNKNOWN hold · IP-8 intent vs proof · **INV-0043-IP-001**  
**Purpose:** Exclude illegal PRESENT sources **before** P1/P2/P3 comparison.  
**Upstream:** [adr0043-freshness-minimum-sufficient-set.md](./adr0043-freshness-minimum-sufficient-set.md) (**APPROVED** · **ACCEPT A** · **INV-0043-F-MIN-001**)  
**Next:** [adr0043-projection-p-comparison.md](./adr0043-projection-p-comparison.md) (**DRAFT FOR REVIEW**)  
**Related:** [adr0043-context-projection-boundary.md](./adr0043-context-projection-boundary.md) (AP-1…AP-5)

---

## Status board

```text
Invalid Patterns:         APPROVED FOR NEXT DESIGN LAYER
IP-1 … IP-8:              ACCEPT
INV-0043-IP-001:          FROZEN
P1 / P2 / P3:             STILL OPEN
Implementation / Field:   FROZEN
```

---

## Core invariant

### INV-0043-IP-001

> No non–authority-accepted-context fact — alone or in combination — MAY produce PRESENT.

```text
PRESENT ≠ inference
PRESENT = projected authority truth
```

---

## Inherited invariants

```text
INV-0043-TRUTH-001   exactly one authoritative truth locus (TARGET)
INV-0043-PROJ-001    PRESENT = time-bounded authority evidence
INV-0043-F-001       PRESENT = conjunction of required bindings
INV-0043-F-MIN-001   at least scope match ∧ decision-epoch match
INV-0043-DB-001      no GROUP_RESYNC without PRESENT
INV-0043-OWN-001     authorizer must prove current accepted context
INV-0043-IP-001      no non-authority fact → PRESENT
```

Pipeline (legal):

```text
Authority accepted context
        ↓
Projection boundary
        ↓
PRESENT evidence
        ↓
F1 + F4 validation
        ↓
Issuer may consider GROUP_RESYNC
```

Forbidden pipeline:

```text
digest / reachability / old receipt / local session /
previous accept / media ready
        ↓
fake PRESENT
```

---

## Invalid patterns (ACCEPT)

### IP-1 Local digest promotion — ACCEPT

```text
Digest MAY prove observation consistency.
Digest MUST NOT prove accepted membership existence.
```

Digest may still be used to **compare**, **detect divergence**, or **trigger re-evaluation**. It MUST NOT mean `digest == membership context` (M0 recurrence).

### IP-2 Reachability inference — ACCEPT

```text
authority reachable → PRESENT
```

Illegal. Transport ≠ membership.

### IP-3 Historical acceptance reuse — ACCEPT

```text
previous PRESENT / previous accepted / prior handler success → current PRESENT
```

Illegal. Bound by epoch (F1) and PROJ-001. Covers AP-3 / AP-4.

### IP-4 Authority-unknown promotion — ACCEPT (+ tightened)

```text
UNKNOWN MUST remain UNKNOWN until a new authority-grounded
PRESENT evidence is obtained.
```

Forbidden path:

```text
UNKNOWN → issuer belief → PRESENT
```

Must cross the authority evidence boundary; not merely “allow some flow to continue.”

### IP-5 Scope / epoch unbound claim — ACCEPT

```text
any evidence without matching scope AND decision epoch → PRESENT
```

Illegal. Violates F-MIN-001 (F4 and F1). Mismatch → **UNKNOWN** (issuer must not invent ABSENT).

### IP-6 Evidence accumulation — ACCEPT

```text
PRESENT(t0) + reachable(t1) + same peer(t2) → still PRESENT
```

Illegal. AP-5. Only current authority-grounded evidence may yield PRESENT.

### IP-7 Stale PRESENT retention — ACCEPT

```text
PRESENT past freshness / binding loss → remain PRESENT
```

Illegal. Must become **UNKNOWN**. Must not become invented ABSENT.

### IP-8 Issuer-local TalkbackSession — ACCEPT (+ limited)

```text
Issuer-local session MAY be used as recovery intent context.
Issuer-local session MUST NOT be used as authority membership proof.
```

Issuer still needs to know which conference / edge is recovering. Forbidden:

```text
local session exists → therefore authority accepts membership
```

`intent ≠ truth`.

---

## Adjudication table

| ID | Verdict | Note |
|----|---------|------|
| IP-1 | ACCEPT | Digest for consistency only, not existence |
| IP-2 | ACCEPT | Transport ≠ membership |
| IP-3 | ACCEPT | Epoch-bound |
| IP-4 | ACCEPT | Hold UNKNOWN until new authority PRESENT |
| IP-5 | ACCEPT | F4 ∧ F1 unbound |
| IP-6 | ACCEPT | No accumulation |
| IP-7 | ACCEPT | Stale → UNKNOWN |
| IP-8 | ACCEPT | Intent OK; proof forbidden |

No new IP IDs. Defense set sufficient for M0-class errors before P comparison.

---

## Legal residual space (for P comparison)

| Direction | Still explorative |
|-----------|-------------------|
| **P1** | Query authority for current scope+epoch-bound evidence |
| **P2** | Authority publishes current projection artifact (not digest alias) |
| **P3** | Authority triggers; issuer does not invent PRESENT locally |

P4 remains **OBSERVED ONLY** — outside legal target space.

---

## Explicit non-goals

```text
NO:
- select P1/P2/P3 in this document
- expand IP set further before P comparison
- retry / timeout / FSM / handler / impl
- runtime / field / branch
```

---

## One-line statement

> INV-0043-IP-001: only projected authority truth may yield PRESENT; digest/reachability/history/UNKNOWN/unbound/accumulation/stale/local-session-as-proof are forbidden — P comparison next inside residual legal space.
