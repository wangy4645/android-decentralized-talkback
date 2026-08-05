# ADR-0037 Amendment: RNA-5 — Recovery Negotiation Intent Terminal Contract (v2)

## Status

**ACCEPTED / FROZEN** (2026-08-05). Architecture accepted 2026-08-05. Gate 3C implementation **COMPLETE** (PR-3C-A..D on main) — READY FOR FIELD / RNA Directed #3.

**Parent:** [ADR-0037](./0037-recovery-negotiation-authority-v1.md) (RNA-5, RNA-016, INV-RNA-010, INV-RNA-011)

**Trigger:** RNA-0037 Directed #2 audit + Intent/Settling offline audit (M01/M03 edges). Field evidence: `talkback/logs/wifi-recovery-m03-rna0037-directed-20260805-183625`.

**Amends:** RNA-5 v1 deferred-intent lifecycle sketch; redefines Gate 3C acceptance scope.

## Summary

Directed #2 proved **owner election is healthy** (`OWNER_CONFLICT = 0`) but **negotiation transaction closure fails**. The gap is not "who owns negotiation" (RNA-2/RNA-3) but **who may close the negotiation obligation** (RNA-5 / RNA-016).

Audit exposed a **split-writer violation**:

```text
RecoveryNegotiationAuthority     → owner / epoch / transaction
DeferredIntent / MediaAction     → defer / release / supersede
```

When Writer B terminates a deferred object without Writer A closing the RNA transaction:

```text
Media lifecycle:   SUPERSEDED ✅
RNA lifecycle:     OPEN ❌
```

This amendment freezes the **intent creation boundary**, **terminal single-writer rule**, **terminal enumeration**, **SUPERSEDED mapping**, and **MEDIA_NOT_READY isolation** so Gate 3C implementation has a closed acceptance contract.

## Confirmed exclusions (audit closure)

| Finding | Conclusion |
|---------|------------|
| `OWNER_CONFLICT = 0` on Directed #2 | RNA-2 / RNA-3 **not reopened** |
| `negotiationOwner ≠ intentOwner` | Ownership split is expected mid-flight; failure is **missing terminal writer** |
| `DEFERRED_INTENT_SUPERSEDED` with `domain=MEDIA` | Not media-internal cleanup — any event that ends negotiation intent **must** emit RNA terminal fact |
| `MEDIA_NOT_READY → RECOVERY_NEGOTIATION_INTENT` | **Ghost intent** — forbidden under this amendment |

## RNA-5.1 — Intent creation conditions

`RECOVERY_NEGOTIATION_INTENT` (including `DEFERRED`) MAY be created only when **all** hold:

```text
RecoveryNegotiationKey exists
AND RecoveryNegotiationAuthority owner resolved
AND negotiation action admitted
```

**Forbidden:**

```text
MEDIA_NOT_READY → RECOVERY_NEGOTIATION_INTENT
```

No negotiation key ⇒ no negotiation intent.

## RNA-5.2 — Terminal single writer

`RECOVERY_NEGOTIATION_INTENT_TERMINAL` has **exactly one writer**: `RecoveryNegotiationAuthority`.

| Layer | May | May not |
|-------|-----|---------|
| `RecoveryNegotiationAuthority` | Emit `RECOVERY_NEGOTIATION_INTENT_TERMINAL`; close negotiation transaction | — |
| `DeferredIntentAuthority` / `MediaAction` | Emit `NEGOTIATION_INTENT_CLOSE_REQUEST` (or equivalent close signal) | Emit terminal fact; mutate RNA transaction state |

This extends RNA-016: transaction state and **intent terminal facts** share the same writer.

**Invariant RNA-5-INV-001:** `intent object absent ≠ negotiation transaction closed`. Terminal fact is mandatory.

## RNA-5.3 — Terminal enumeration

Frozen terminal reasons for `RECOVERY_NEGOTIATION_INTENT_TERMINAL`:

| Terminal | Meaning |
|----------|---------|
| `EXECUTED` | Negotiation action executed; completion evidence satisfied |
| `BLOCKED_BY_GLARE` | Remote transaction wins glare resolution |
| `EXPIRED` | Negotiation budget exhausted |
| `SUPERSEDED` | Recovery episode superseded by higher-priority transaction |

Every admitted negotiation intent MUST reach exactly one of these terminals before the edge may treat negotiation as closed.

## RNA-5.4 — SUPERSEDED semantics

`DeferredIntent` supersede is **not** a media-only event when it retires a negotiation-scoped deferred object.

**Required mapping:**

```text
DEFERRED_INTENT_SUPERSEDED (negotiation-scoped)
        ↓
NEGOTIATION_INTENT_CLOSE_REQUEST
        ↓
RECOVERY_NEGOTIATION_INTENT_TERMINAL
        reason=SUPERSEDED
        source=MEDIA_ACTION_SUPERSEDE
```

**Forbidden:**

```text
DeferredIntent RELEASED / SUPERSEDED → (no RNA terminal)
```

**Invariant RNA-5-INV-002:** Any path that removes negotiation intent residency MUST produce `RECOVERY_NEGOTIATION_INTENT_TERMINAL`.

## RNA-5.5 — MEDIA_NOT_READY isolation

`MEDIA_NOT_READY` MAY produce only **media-domain** lifecycle facts:

```text
MEDIA_ACTION_DEFERRED
MEDIA_WAITING | MEDIA_READY | MEDIA_ABORTED
```

It MUST NOT create `RECOVERY_NEGOTIATION_INTENT`.

When both lifecycles are active:

```text
Negotiation:  WAITING   (intent admitted; not yet terminal)
Media:        BLOCKED   (capability / path not ready)
```

**Invariant RNA-5-INV-003:** Do not merge negotiation deferral and media deferral into a single ghost intent without `intentId` / negotiation key.

## Gate 3C — upgraded scope (not authorized until this freeze is accepted)

Gate 3C is **not** `NEGOTIATION_SETTLING timeout`. It is:

```text
NEGOTIATION_INTENT_TERMINAL_CLOSURE
```

Acceptance tests **any exit path** from recovery negotiation, not watchdog duration:

| Scenario | Required terminal |
|----------|-------------------|
| Answer never arrives | `EXPIRED` |
| Media supersede | `SUPERSEDED` |
| Glare accept remote | `BLOCKED_BY_GLARE` |
| Successful restart | `EXECUTED` |
| Episode cancel | `EXPIRED` or `SUPERSEDED` |

Directed field chain (unchanged tail):

```text
RECOVERY_NEGOTIATION_INTENT_TERMINAL
        ↓
NEGOTIATION_RECOVERY_FACT
        ↓
RECOVERY_EDGE_RECOVERED
```

## Dual-clock note (INV-REC-001 tension)

Capability block (`INV-REC-001`) MAY pause **media** clocks. Negotiation intent terminality uses an **independent** negotiation clock (design review option C). This amendment does not pick implementation mechanics — it requires that capability pause **cannot** leave RNA transaction OPEN without terminal fact.

## Explicit non-goals

- Gate 3C code implementation COMPLETE (PR-3C-A..D)
- No watchdog / budget tuning
- No membership or RCA-0036 changes
- No completion bypass
- No reopening RNA-2 / RNA-3 owner election

## Acceptance (freeze only)

This document is accepted when architecture signs off that RNA-5 v2 closes the three-layer boundary:

```text
RecoveryNegotiationAuthority
        ↓
DeferredIntentAuthority
        ↓
MediaAction lifecycle
```

Implementation authorization requires a separate gate after unit/controller tests map to the scenario table above.

## Status board

```text
ADR-0036 Membership                 VERIFIED / RE-SIGNED

ADR-0037 Design                     FINAL
  RNA-5 v1                          SUPERSEDED by this amendment (intent sketch only)
  RNA-5 v2 Terminal Contract        ACCEPTED / FROZEN

Phase 3.2
  Authority gates                   VERIFIED
  Controller gates                  VERIFIED

RNA Directed #2
  owner                             PASS
  transaction closure               FAIL
  root cause                        RNA-5 lifecycle gap (addressed by this freeze)

Gate 3C
  status                            VERIFIED / READY FOR FIELD
  PR-3C-A..D                        DONE on main

Next
  RNA Directed #3                   READY
  run card: ../analysis/rna-directed-3-run-card.md

## References

- [ADR-0037](./0037-recovery-negotiation-authority-v1.md)
- [Phase 3.2 design review](../analysis/phase32-recovery-negotiation-behavior-design-review.md)
- Directed #2 evidence: `talkback/logs/wifi-recovery-m03-rna0037-directed-20260805-183625`