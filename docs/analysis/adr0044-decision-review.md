# ADR-0044 — Decision Review

**Status:** **ACCEPTED** (2026-08-09) · **no amend** · presentation-only impl AUTHORIZED · Field NOT AUTHORIZED  
**ADR:** [0044-user-visible-connectivity-semantics-media-residency.md](../adr/0044-user-visible-connectivity-semantics-media-residency.md)  
**Upstream:** [mobile-validation-track-close.md](./mobile-validation-track-close.md)

---

## Status board

```text
ADR-0044 Presentation Semantics

Status:
    DRAFT → REVIEWING → ACCEPTED

Decision:
    ACCEPT candidates (no amend)

Implementation:
    AUTHORIZED (presentation only)

Field:
    NOT AUTHORIZED
```

---

## Formal Accept

Candidates from [aeedf9e](https://github.com/wangy4645/android-decentralized-talkback/commit/aeedf9e) review are **Accepted without amendment**.

| DQ | Accepted decision |
| -- | ----------------- |
| DQ1 | **B** — separate active recovery vs terminal unavailable |
| DQ2 | User **Degraded** · Diagnostic **Media unavailable** |
| DQ3 | **NO CHANGE** — conference banner / P1a boundary |

### Decision Summary (normative)

```text
1. Adopt separated presentation semantics:
   active recovery and terminal media unavailability
   must not share the same user-visible label.

2. Keep recovery lifecycle ownership unchanged.

3. Keep conference banner projection unchanged.

4. Presentation vocabulary remapping is authorized
   only on the UVCP → EndpointStatus → UI path.
```

### Constraint

```text
Presentation state ≠ Recovery lifecycle state
(no FSM-mirror vocabulary explosion)
```

---

## Accept does not authorize

```text
- FAILED_MEDIA_RECOVERY lifecycle changes
- obligation completion changes
- ICE recovery changes
- membership convergence changes
- conference banner behavior changes
- automatic ONLINE projection after deadline
```

---

## Next

Minimal presentation-only implementation PR. No Q4 / further desk expansion.
