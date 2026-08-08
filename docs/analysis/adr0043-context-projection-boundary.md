# ADR-0043 — Context Projection Boundary

**Status:** **APPROVED DESIGN LAYER** · **docs only** · **Implementation NOT AUTHORIZED**  
**Date:** 2026-08-08  
**Wording patch:** PRESENT validity · INV-0043-PROJ-001 · anti-patterns · P4 observed-only  
**ADR:** [0043-conference-recovery-membership-context-boundary.md](../adr/0043-conference-recovery-membership-context-boundary.md) (**ACCEPTED · Option A**)  
**Upstream fact:** [adr0043-seam-i-context-truth-mapping.md](./adr0043-seam-i-context-truth-mapping.md) (**APPROVED** · **Observed = MIXED**)  
**Next layer:** Do not pick P yet. Exclude illegal PRESENT sources first → [adr0043-projection-invalid-patterns.md](./adr0043-projection-invalid-patterns.md). Then compare P1/P2/P3 in the residual legal space.
**Related:** Evidence · Decision boundary · Ownership · Context truth (all APPROVED; Oi/Ti/A-B-C **OPEN**)

---

## Status board

```text
Observed truth locus:        MIXED (frozen)
INV-0043-TRUTH-001:          TARGET
INV-0043-PROJ-001:           FROZEN (PRESENT time-bounded)
INV-0043-F-001:              FROZEN (PRESENT = conjunction of bindings)
Classification:              ARCHITECTURE GAP OBSERVED
Projection boundary:         APPROVED DESIGN LAYER
Freshness boundary:          APPROVED DESIGN LAYER
P1                           OPEN
P2                           OPEN
P3                           OPEN (projection + ownership impact)
P4                           OBSERVED ONLY · not compliant target
F1–F5                        OPEN
Implementation               NOT AUTHORIZED
Runtime / Field              FROZEN
```

---

## Separation (frozen)

```text
authority truth
    ≠
projection evidence
    ≠
issuer authorization
```

---

## PRESENT validity boundary

### INV-0043-PROJ-001

> **PRESENT is a time-bounded evidence of authority accepted context, not a durable assertion of existence.**

中文：PRESENT 只能表示某个 decision epoch 内，authority 已确认存在 accepted membership context；不能表示该 context 永久存在。

Without this bound:

```text
16:20:00  authority PRESENT (projected)
16:22:00  context destroyed
16:22:30  issuer consumes old PRESENT → GROUP_RESYNC → NO_MEMBERSHIP_CONTEXT
```

= M0 variant.

### PRESENT lifecycle semantics (evidence, not FSM)

| State | Meaning | May gate RESYNC? |
|-------|---------|------------------|
| **PRESENT** | Authority has confirmed accepted context **for the current decision epoch** | Yes (subject to OWN) |
| **ABSENT** | Authority **explicitly** confirms context not established / removed | No |
| **UNKNOWN** | No current proof | No |

```text
UNKNOWN ≠ stale PRESENT
stale PRESENT → UNKNOWN   (must not remain PRESENT)
```

Expiry or loss of freshness **without** an authority ABSENT confirmation → **UNKNOWN**, not invented ABSENT.

---

## Single question (this document)

> How may **authority truth** leave the authority and be **legally consumed** by the recovery issuer before `GROUP_RESYNC`?

```text
Not: selecting P1/P2/P3
Not: F-axis implementation
Only: legitimate projection paths + PRESENT validity
```

Inherited:

```text
INV-0043-DB-001     no GROUP_RESYNC without PRESENT evidence
INV-0043-OWN-001    authorizer must prove current accepted context
INV-0043-TRUTH-001  exactly one authoritative truth locus (TARGET)
INV-0043-PROJ-001   PRESENT is time-bounded evidence (not durable existence)
Observed            MIXED · issuer source ≠ handler source
```

---

## Candidates (desk classification)

### Projection candidates (OPEN)

| ID | Projection | Sketch |
|----|------------|--------|
| **P1** | Issuer **queries** authority before RESYNC | Ask current context → evidence → OWN may allow RESYNC |
| **P2** | Authority **publishes** accepted-context evidence | Projection artifact from truth; issuer consumes as evidence |
| **P3** | Issuer **never** decides RESYNC; authority **triggers** | Authority initiates when it holds context |

### Observed only (not a candidate)

| ID | Label | Status |
|----|-------|--------|
| **P4** | Status quo (local observation) | **OBSERVED ONLY** |

```text
P1 / P2 / P3 = projection candidates (OPEN — no selection)
P4 = observation of current behavior only and cannot satisfy TRUTH-001 target
     unless the local observation is replaced by authority-grounded evidence.
```

P4 is **not** a low-cost architecture option.

---

## Desk notes

### P1 — Query before RESYNC

Naturally fits PROJ-001 if response is evaluated as **current** context. Future (not this ADR): response freshness · correlation epoch · scope identity.

```text
Authority query response may provide evidence;
whether that evidence satisfies authorization remains governed by OWN boundary.
```

```text
query response → context evidence → ownership decision → GROUP_RESYNC allowed
```

### P2 — Authority publishes evidence

May satisfy PROJ-001 only if:

```text
published PRESENT + fresh enough + authority-originated
```

```text
Published evidence is not membership truth itself;
it is a projection artifact derived from authority truth.
```

**Forbidden:** `lastSeenDigest ≈ PRESENT`

### P3 — Authority triggers

Remains **OPEN**. Owns truth + trigger (PROJ-001-friendly) but redesigns ownership / obligation / trigger model — reopening Ownership required if selected.

### P4 — Observed only

```text
P4 is not a projection mechanism.
P4 is absence of projection.
P4 cannot satisfy TRUTH-001 as a target.
```

---

## Projection anti-patterns (forbidden)

### AP-1 Local digest promotion

```text
local digest matches expected roster → assume PRESENT
```

### AP-2 Topology inference

```text
authority reachable → authority must have context → PRESENT
```

### AP-3 Historical PRESENT reuse

```text
previous PRESENT → current RESYNC permission
```

(Violates INV-0043-PROJ-001.)

### AP-4 Handler success inversion

```text
previous handler accepted context → future issuer may assume context
```

### AP-5 Evidence accumulation

```text
PRESENT(t0) + reachable(t1) + same peer(t2)  ≠  still PRESENT
```

Projection evidence **MUST NOT** be restored by concatenating historical facts. Only **current** authority-grounded evidence may yield PRESENT.

---

## Desk elimination (not selection)

| ID | Status |
|----|--------|
| P1 | OPEN · projection candidate |
| P2 | OPEN · must not equate digest cache with PRESENT |
| P3 | OPEN · projection + ownership impact |
| P4 | **OBSERVED ONLY** · not compliant target |

---

## Explicit non-goals

```text
NO:
- select P1 / P2 / P3
- treat P4 as a legal / low-cost projection choice
- select Ti / Oi / A-B-C / F-axes
- wire formats / TTL numbers
- runtime / field / branch
```

---

## Next layer pointer

Minimum F (**ACCEPT A** · F1+F4) is frozen. Next: invalid-pattern gate, then P comparison.

→ [adr0043-freshness-minimum-sufficient-set.md](./adr0043-freshness-minimum-sufficient-set.md) (**APPROVED**)  
→ [adr0043-projection-invalid-patterns.md](./adr0043-projection-invalid-patterns.md) (**DRAFT**)

---

## One-line statement

> Projection carries time-bounded PRESENT evidence (INV-0043-PROJ-001) under F1∧F4 (F-MIN-001); stale PRESENT → UNKNOWN; P1–P3 open, P4 observed-only; invalid patterns gate before P pick — no implementation.
