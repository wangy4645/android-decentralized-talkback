# ADR-0044: User Visible Connectivity Semantics for Media Residency

## Status

**ACCEPTED** (2026-08-09) · **presentation-only implementation AUTHORIZED** · **Field NOT AUTHORIZED**  
**Decision review:** [adr0044-decision-review.md](../analysis/adr0044-decision-review.md) (candidates → Accept, no amend)

**Parents:**

- [ADR-0034](./0034-user-visible-connectivity-projection.md) — UVCP vocabulary (Accepted; amended here for terminal residency gap)
- [mobile-validation-track-close.md](../analysis/mobile-validation-track-close.md) — Q1–Q3 desk CLOSED
- [mobile-validation-failed-media-recovery-q3-presentation.md](../analysis/mobile-validation-failed-media-recovery-q3-presentation.md)

```text
ADR-0044
Decision:              ACCEPT (no amend)
Model:                 ACTIVE / TERMINAL presentation vocabulary
Implementation:        AUTHORIZED (presentation path only)
Field:                 NOT AUTHORIZED

Does NOT reopen:
  WiFi Recovery Architecture
  ADR-0043 / RNA
  obligation / completion / residency ownership
```

---

## Accepted decisions

| DQ | Decision |
| -- | -------- |
| **DQ1** | **B — Separate** active recovery and terminal media unavailability; they MUST NOT share the same user-visible label |
| **DQ2** | **User:** `Degraded` · **Diagnostic:** `Media unavailable` |
| **DQ3** | **NO CHANGE** to conference poor-network banner (P1a frozen) |

### Normative presentation model

```text
Recovery Activity          Media Availability
-----------------          ------------------
active repair?             usable?
yes / no                   yes / no

Presentation owns user meaning.
Recovery lifecycle owns attempt / residency.
Presentation state ≠ Recovery lifecycle state.
```

Minimal mapping intent (implementation detail TBD in PR):

| Facts | User-visible |
| ----- | ------------ |
| Active repair (`isActivelyRecovering` / repair-active media path) | **Reconnecting…** (or equivalent active label) |
| Terminal media residency (`mediaUnavailable` + repair not active) | **Degraded** |
| Diagnostic / detail | Media unavailable (not primary chrome) |

Do **not** expand into FSM-mirror enums:

```text
✗ RECOVERING / RETRYING / WAITING / FAILED / PARTIAL_FAILED / …
```

### Banner (frozen)

```text
single peer media residency
        ≠
conference Poor Network banner
```

---

## Context

Mobile validation established:

```text
FAILED_MEDIA_RECOVERY lifecycle     ✅ correct
obligation close semantics          ✅ correct (deadline = stop trying)
problem location:
  truth → EndpointStatus compression loss
```

Observed internal truth could be:

```text
mediaUnavailable=true
isActivelyRecovering=false
phase=FAILED_MEDIA_RECOVERY
obligationOpen=false
iceConnectionState=CONNECTED
```

Pre-ADR projection collapsed to `EndpointStatus.RECONNECTING` / "Reconnecting…", implying active repair when repair may already have ended.

### Relation to ADR-0034

ADR-0034 intended `MEDIA_UNAVAILABLE + repair active → RECONNECTING`.  
This ADR closes the gap for **`MEDIA_UNAVAILABLE + repair NOT active` (terminal residency)** without changing recovery ownership.

---

## Scope

### In

```text
- EndpointStatus contract (user-facing peer status)
- UVCP projection mapping
- User-visible connectivity labels / copy / icons
- Meeting peer presentation
- Diagnostic detail layer for "Media unavailable"
```

### Out

```text
- ICE recovery FSM
- Obligation lifecycle / deadline
- FAILED_MEDIA_RECOVERY ownership / residency lifetime
- Completion admission predicate
- Retry policy
- Conference poor-network banner rules (P1a — frozen PASS)
- ADR-0043 / RNA
```

---

## Non-goals (normative — Accept freeze)

This ADR does **not** authorize:

```text
- FAILED_MEDIA_RECOVERY lifecycle changes
- obligation completion changes
- ICE recovery changes
- membership convergence changes
- conference banner behavior changes
- automatic ONLINE projection after deadline
- clear FAILED_MEDIA residency because ICE CONNECTED
```

**Anti-misread:** Showing **Degraded** for terminal residency does **not** mean "recovery is broken and must be fixed." Lifecycle remains correct; only vocabulary is corrected.

---

## Implementation authorization

### Authorized (presentation only)

```text
UVCP presentation projection
EndpointStatus mapping
UI string / icon mapping
Diagnostic detail surfacing (optional, secondary)
```

### Not authorized (separate ADR required)

```text
Recovery FSM
Completion predicate
Obligation state
Media residency clear logic
Field validation campaigns (until separately authorized)
```

---

## Evidence index

| Doc | Role |
| --- | ---- |
| [adr0044-decision-review.md](../analysis/adr0044-decision-review.md) | Candidates → Accept |
| [mobile-validation-track-close.md](../analysis/mobile-validation-track-close.md) | Track archive |
| [Q1](../analysis/mobile-validation-failed-media-recovery-q1-ownership.md) · [Q2](../analysis/mobile-validation-failed-media-recovery-q2-classification.md) · [Q3](../analysis/mobile-validation-failed-media-recovery-q3-presentation.md) | Desk chain |
| [Case A P1a](../analysis/mobile-validation-case-a-p1a-verdict.md) | Banner PASS |
| Episode | `logs/mobile-validation-case-a-p1a-20260808-215605/` |

---

## Status board

```text
ADR-0044
========
Status:          ACCEPTED
Decision:        ACTIVE/TERMINAL presentation model
Implementation:  AUTHORIZED (presentation only)
Field:           NOT AUTHORIZED

WiFi Recovery Architecture     CLOSED
ADR-0043 / RNA                 FROZEN · NO REOPEN
P1a banner boundary            CLOSED (PASS)
FAILED_MEDIA desk Q1–Q3        CLOSED OBSERVATION
```

---

## Next step

Design a **minimal presentation-only implementation PR** (UVCP → EndpointStatus → UI).  
No recovery / obligation / residency / banner behavior changes.  
Field validation separately authorized later.
