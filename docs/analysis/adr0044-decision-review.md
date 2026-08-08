# ADR-0044 — Decision Review (Candidate)

**Status:** **REVIEWING** · **decision candidate recorded** · **NOT ACCEPTED** · **impl NOT AUTHORIZED**  
**Date:** 2026-08-09  
**ADR:** [0044-user-visible-connectivity-semantics-media-residency.md](../adr/0044-user-visible-connectivity-semantics-media-residency.md)  
**Upstream:** [mobile-validation-track-close.md](./mobile-validation-track-close.md)

---

## Status board

```text
ADR-0044 Presentation Semantics

Status:
    DRAFT → REVIEWING

Decision:
    CANDIDATE RECORDED (not formal Accept)

Implementation:
    NOT AUTHORIZED

Field:
    NOT AUTHORIZED
```

---

## Purpose

Freeze the **user-visible semantics model** before any code plan.

```text
Freeze semantics first
        ↓
Formal Accept (separate step)
        ↓
Only then authorize presentation-only impl
```

---

## Decision candidates

### DQ1 — Distinguish active vs terminal?

```text
Candidate: B — Distinguish
```

**Rationale:** Two independent fact axes already exist:

```text
Recovery Activity     active repair?     yes / no
Media Availability    usable?            yes / no
```

Today both collapse into `EndpointStatus.RECONNECTING`, so users read “actively repairing” when repair may already have ended.

**Constraint (normative for this candidate):**

```text
Presentation state
    ≠
Recovery lifecycle state
```

Choosing B does **not** authorize mirroring the recovery FSM into UI:

```text
✗ RECOVERING / RETRYING / WAITING / FAILED / PARTIAL_FAILED / …
```

Only a minimal active-vs-terminal vocabulary split is in scope if Accept follows.

---

### DQ2 — Terminal residency user vocabulary?

```text
Candidate ranking:

1. User visible:  Degraded          (recommended)
2. Diagnostic:    Media unavailable (detail / logs / advanced)
3. Connection issue                (too broad — not preferred as primary)
```

**Recommended split:**

```text
EndpointStatus (user):
    DEGRADED

Diagnostic / detail layer:
    Media unavailable
```

**Rationale:** “Media unavailable” is precise but engineering-facing; “Degraded” is common network-product language for mesh/conference. Keep technical precision in diagnostics, not primary peer chrome.

**Not decided as formal Accept yet** — remains candidate pending ADR Accept.

---

### DQ3 — Conference banner?

```text
Candidate: NO CHANGE
```

```text
single peer media residency
        ≠
conference Poor Network banner
```

P1a Case A field PASS remains frozen. ADR-0044 must not reopen that boundary.

---

## Decision Summary (candidate — not Accept)

```text
1. Adopt separated presentation semantics:
   active recovery and terminal media unavailability
   must not share the same user-visible label.

2. Keep recovery lifecycle ownership unchanged.

3. Keep conference banner projection unchanged.

4. Introduce / remap presentation vocabulary only after
   formal ADR Accept — not during this review memo.
```

---

## Still forbidden

Until **formal Accept** of ADR-0044:

```text
✗ EndpointStatus enum change
✗ UVCP projection change
✗ string resource change
✗ field validation for reconnect copy
✗ FAILED_MEDIA_RECOVERY modification
✗ force ONLINE / clear residency
```

---

## Next formal step

1. Product / architecture **Accept** or **Amend** this candidate set on ADR-0044.  
2. On Accept: write short Accept amendment (decision + vocabulary table).  
3. Only then open presentation-only implementation PR.
