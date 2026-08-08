# ADR-0043 — P1 Design Boundary

**Status:** **APPROVED FOR NEXT DESIGN LAYER** · **docs only** · **Implementation NOT AUTHORIZED**  
**Date:** 2026-08-08  
**Wording patch:** epoch non-transfer · correlation ≠ freshness  
**Baseline:** [adr0043-projection-selection-memo.md](./adr0043-projection-selection-memo.md) (**ACCEPTED** · **v0 = P1**)  
**Next:** [adr0043-p1-authorization-boundary.md](./adr0043-p1-authorization-boundary.md) (**DRAFT FOR REVIEW**)

---

## Status board

```text
v0 projection:            P1 ACCEPTED
P1 Design Boundary:       APPROVED
O-selection:              OPEN
F2/F3/F5:                 OPEN
Seam II:                  OPEN
Implementation / Field:   FROZEN
Runtime:                  FROZEN
```

---

## Single question (answered at this layer)

> What **abstract** constraints must a P1 query/response evidence carry so PRESENT can satisfy F-MIN-001 and feed authorization **without** becoming truth or auto-dispatch?

---

## Inherited freezes

```text
v0 baseline = P1
TRUTH-001 · F-MIN-001 · IP-001 · PROJ-001 · F-001 · PROJ-OWN-001
truth ≠ evidence ≠ authorization
query response ≠ authority truth itself / ≠ remote membership replica
P1 reduces retention risk ≠ eliminates freshness design
O-selection OPEN · PRESENT ≠ auto GROUP_RESYNC
```

---

## 1. Minimal response evidence semantics (abstract)

| Semantic | Role |
|----------|------|
| **Authority origin** | Authority-grounded (IP-001) |
| **Context existence answer** | PRESENT / ABSENT / (insufficient → UNKNOWN at issuer) |
| **Scope identity** | Scope being answered (F4) |
| **Decision-epoch bind** | Bound to recovery decision epoch of the ask (F1) |
| **Correlation** | Ties response to specific query/ask instance |

```text
Semantic obligations — not a wire schema.
Response evidence ≠ authority truth database / remote membership replica.
```

**ABSENT** only when authority asserts non-existence. Timeout / mismatch / stale → **UNKNOWN** (issuer must not invent ABSENT).

---

## 2. Scope / epoch / correlation (abstract)

| Bind | Rule |
|------|------|
| **Scope** | Response scope MUST match; mismatch → not PRESENT (UNKNOWN) |
| **Epoch** | Response PRESENT is valid **only** for the decision epoch it is bound to; it **MUST NOT** be transferred across decision epochs. No issuer “near enough” inference |
| **Correlation** | Uncorrelated / mismatched response MUST NOT be consumed as PRESENT for this decision |

```text
correlation ≠ freshness
correlation: prevents wrong attachment / replay across asks
freshness:   prevents stale validity
```

`same query id` alone MUST NOT imply still-valid PRESENT.

F-MIN-001: PRESENT requires scope match ∧ epoch match.

---

## 3. Evidence → authorization boundary

```text
authority truth
        ↓
P1 response evidence (may be PRESENT)
        ↓
issuer authorization rule (OWN — still OPEN)
        ↓
may or may not dispatch GROUP_RESYNC
```

| Must not collapse |
|-------------------|
| PRESENT evidence → automatic GROUP_RESYNC |
| Successful query RTT → PRESENT |
| Reachability → PRESENT |
| Local conference session → PRESENT (intent OK; proof forbidden) |

---

## 4. Non-goals

```text
NO: wire / protobuf / API · O1/O2/O3 pick · F2/F3/F5 pick · Seam II · handler / Option C · branch / runtime / field
```

---

## Next

→ [adr0043-p1-authorization-boundary.md](./adr0043-p1-authorization-boundary.md)

When issuer holds PRESENT evidence, what principles allow/forbid entering authorization evaluation?

---

## One-line statement

> P1 evidence is authority-originated, scope/epoch-bound, correlated (not freshness), never a membership replica or auto-dispatch — authorization boundary next; impl still frozen.
