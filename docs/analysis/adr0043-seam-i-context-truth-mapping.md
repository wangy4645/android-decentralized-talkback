# ADR-0043 Seam I — Context Truth Mapping (Desk)

**Status:** **APPROVED** · **desk fact frozen** · **Implementation NOT AUTHORIZED**  
**Date:** 2026-08-08  
**ADR:** [0043-conference-recovery-membership-context-boundary.md](../adr/0043-conference-recovery-membership-context-boundary.md) (**ACCEPTED · Option A**)  
**Truth candidates:** [adr0043-seam-i-context-truth-authority.md](./adr0043-seam-i-context-truth-authority.md) (**APPROVED** · T1/T2/T3 **OPEN**)  
**Next layer:** [adr0043-context-projection-boundary.md](./adr0043-context-projection-boundary.md) (**APPROVED**) · [adr0043-projection-evidence-freshness-boundary.md](./adr0043-projection-evidence-freshness-boundary.md) (**DRAFT**)
**Code base audited:** `talkback/android-board-talkback/.../TalkbackCoordinator.kt` (+ `MembershipAuthorityResolver.kt`, `TopologyDigest.kt`)  
**Seed (behavior):** M0 · `logs/adr0042-p0-narrow-20260808-162002/`

---

## Status board

```text
Observed truth locus:        MIXED          ← FROZEN FACT
INV-0043-TRUTH-001:          TARGET (not satisfied in practice)
Classification:              ARCHITECTURE GAP OBSERVED
                             (not a runtime bug verdict)
T1 / T2 / T3:                OPEN (refined below)
Projection boundary:         NEXT
Implementation / Field:      FROZEN
```

---

## Frozen fact: MIXED

```text
Observed truth locus: MIXED
```

Not pure T1, not pure T2. Precise shape:

```text
T1-like issuer observation
        +
T2-like handler authority storage
```

This explains M0 better than “authority failed to keep context.”

---

## M0 reinterpretation (frozen)

```text
Issuer (M03):
  local conference + cached digest
        |
        | assumes “context usable”
        ↓
  GROUP_RESYNC

Authority (M01):
  accepted GROUP|CONFERENCE in local sessions
        |
        | lookup
        ↓
  NO_MEMBERSHIP_CONTEXT
```

Two components answer **different questions**:

| Role | Question answered |
|------|-------------------|
| Issuer | “I believe this exists / is usable” |
| Authority handler | “Do I currently accept that this exists?” |

```text
Issuer truth source ≠ Handler truth source
→ no binding between assumption and acceptance
```

---

## INV-0043-TRUTH-001 status

Invariant (target):

> Membership context MUST have exactly one authoritative truth locus.

**Practice today:** violated in the architectural sense (dual sources).

**Classification:**

```text
ARCHITECTURE GAP OBSERVED
```

**Not:** runtime bug verdict · not “handler bug” · not “timeout bug”.

No unique truth **projection** from authority storage to issuer decision.

---

## Ti update (still OPEN — refined)

### T1 — must be re-scoped

Not:

```text
any TalkbackSession
```

Must be (if T1 is ever chosen):

```text
authority-owned TalkbackSession
```

Local sessions exist on every node; only the authority node’s session is trusted by the handler. Unscoped T1 recreates dual truth.

### T2 — closest to observed architecture

```text
authority local session store = accepted membership context for RESYNC snapshot
```

Handler already works this way. **Issuer does not consume it.** T2 describes observed handler truth; it is not yet an accepted ADR selection.

### T3 — NOT OBSERVED

```text
HOLD · no evidence · do not introduce
```

---

## Audit summary (unchanged substance)

| Q | Observed |
|---|----------|
| Create | Per-node `TalkbackSession` → local `sessions` |
| Destroy | Per-node remove / hangup |
| Epoch/roster prove | Local digest vs `lastSeenAuthorityDigest` (observation ≠ context) |
| Handler query | Authority-local accepted GROUP\|CONFERENCE |
| Issuer evidence | Local conference + digest cache — **no** authority session proof |

---

## Next layer

Do not ACCEPT T2 yet.

Missing question:

> Does the issuer have a **legitimate** way to obtain the authority’s context decision?

→ [adr0043-context-projection-boundary.md](./adr0043-context-projection-boundary.md)

---

## One-line statement

> MIXED is frozen: issuer assumes from local conference+digest; handler accepts only authority-local session — ARCHITECTURE GAP vs TRUTH-001; next designs how authority truth projects to the issuer, without selecting Ti or implementing.
