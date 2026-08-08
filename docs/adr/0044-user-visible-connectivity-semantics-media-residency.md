# ADR-0044: User Visible Connectivity Semantics for Media Residency

## Status

**REVIEWING** · **decision candidate recorded** · **NOT ACCEPTED** · **docs only** · **no runtime authorization**  
**Date:** 2026-08-09  
**Decision review:** [adr0044-decision-review.md](../analysis/adr0044-decision-review.md)

**Parents:**

- [ADR-0034](./0034-user-visible-connectivity-projection.md) — UVCP vocabulary (Accepted)
- [mobile-validation-track-close.md](../analysis/mobile-validation-track-close.md) — Q1–Q3 desk CLOSED
- [mobile-validation-failed-media-recovery-q3-presentation.md](../analysis/mobile-validation-failed-media-recovery-q3-presentation.md)

```text
This ADR does NOT reopen:
  WiFi Recovery Architecture
  ADR-0043 / RNA
  obligation / completion / residency ownership

This ADR asks only:
  Is the user-visible connectivity vocabulary sufficient
  for already-correct media residency truth?

Status ladder:
  DRAFT → REVIEWING (now) → Accept/Amend → impl authorized
```

### Decision candidates (REVIEWING — not Accept)

| DQ | Candidate |
| -- | --------- |
| DQ1 | **B — Distinguish** active recovery vs terminal unavailable |
| DQ2 | User: **Degraded**; Diagnostic: **Media unavailable** |
| DQ3 | **NO CHANGE** to conference banner (P1a frozen) |

```text
Presentation state ≠ Recovery lifecycle state
(no FSM-mirror vocabulary explosion)
```

Full rationale: [adr0044-decision-review.md](../analysis/adr0044-decision-review.md)

---

## Context

Mobile validation (P1a PASS + FAILED_MEDIA desk Q1–Q3) established:

```text
FAILED_MEDIA_RECOVERY lifecycle     ✅ correct
obligation close semantics          ✅ correct (deadline = stop trying)
problem location:
  truth → EndpointStatus compression loss
```

Observed internal truth:

```text
mediaUnavailable=true
isActivelyRecovering=false
phase=FAILED_MEDIA_RECOVERY
obligationOpen=false   (after deadline)
iceConnectionState=CONNECTED   (often)
```

Current projection collapses to:

```text
EndpointStatus.RECONNECTING
UI: "Reconnecting…"
```

That label implies:

```text
system is actively attempting repair
```

But the truth may be:

```text
repair attempt already ended
media remains unavailable
```

### Relation to ADR-0034

ADR-0034 already intended:

```text
MEDIA_UNAVAILABLE + repair active   → RECONNECTING
MEDIA_OK + control sync             → SYNCING / DEGRADED
recovering alone                    → MUST NOT drive RECONNECTING
```

Field + desk (Q3) show a remaining gap for:

```text
MEDIA_UNAVAILABLE + repair NOT active (terminal residency)
```

Today `deriveAxes` forces `control=SYNCING` whenever `mediaUnavailable=true`, so the dual-axis map always yields `RECONNECTING` for terminal residency. That is a **vocabulary / mapping** question — not a recovery lifecycle defect.

---

## Scope

### In

```text
- EndpointStatus contract (user-facing peer status)
- UVCP projection mapping
- User-visible connectivity labels / copy
- Meeting peer presentation
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

## Problem statement

```text
Is the user-visible connectivity vocabulary sufficient
to express already-existing media residency truth?

NOT:
  How do we fix recovery?
```

---

## Decision questions

### DQ1 — Must users distinguish active vs terminal?

#### Option A — Keep coarse abstraction

```text
Unavailable / repair-related states
        ↓
Reconnecting…
```

**Pros:** simpler UI; users only need “connection problem”.  
**Cons:** long-lived “Reconnecting…” after attempt ended is misleading.

#### Option B — Distinguish active vs terminal

```text
ACTIVE_RECOVERY     (repair in progress)
vs
MEDIA_UNAVAILABLE / TERMINAL_UNAVAILABLE   (attempt ended; media still bad)
```

**Pros:** UI matches system facts.  
**Cons:** vocabulary growth; EndpointStatus / UVCP contract update.

**Author inclination (not a decision):** Option B — mesh / PTT / conference + residency already exceed a single reconnect label. **ADR must decide; do not sneak into implementation.**

---

### DQ2 — If distinguishing: what is terminal residency’s user meaning?

Only relevant if DQ1 = B.

| Scheme | User label | Meaning | Risk |
| ------ | ---------- | ------- | ---- |
| 1 | **Degraded** | Connection impaired; session may continue | May understate media unusable |
| 2 | **Media unavailable** | Media path not usable | More precise; more technical |
| 3 | Coarse **Connection issue** | Generic; hide FAILED_MEDIA internally | Minimal change; may keep overload |

Do **not** invent new recovery phases here. Labels map **existing** facts (`mediaUnavailable`, `isActivelyRecovering`).

---

### DQ3 — Conference banner rules?

**Proposed decision (default):** **NO CHANGE**

```text
single peer media / recovery issue
        ≠
conference-level Poor Network banner
```

P1a Case A field PASS must remain frozen under this ADR.

---

## Non-goals (normative)

This ADR does **not** change:

```text
- recovery state machine
- media residency lifetime
- obligation closure
- completion predicate
- ICE handling
- retry behavior
- force ONLINE / clear FAILED_MEDIA residency on ICE CONNECTED
```

---

## Implementation authorization gate

### Before ADR decision

```text
✗ UVCP / EndpointStatus / UI label code changes
✗ enum additions
✗ field validation for reconnect copy
```

### After ADR decision (Accepted)

**Allowed** (presentation path only):

```text
UVCP
  ↓
Connectivity projection
  ↓
EndpointStatus
  ↓
UI label
```

**Forbidden** even after Accept (requires separate ADR):

```text
FAILED_MEDIA_RECOVERY → clear → ONLINE
obligation / completion / retry changes
```

---

## Evidence index

| Doc | Role |
| --- | ---- |
| [mobile-validation-track-close.md](../analysis/mobile-validation-track-close.md) | Track archive |
| [Q1 ownership](../analysis/mobile-validation-failed-media-recovery-q1-ownership.md) | Residency ownership valid |
| [Q2 classification](../analysis/mobile-validation-failed-media-recovery-q2-classification.md) | Terminal residency; deadline = stop trying |
| [Q3 presentation](../analysis/mobile-validation-failed-media-recovery-q3-presentation.md) | RECONNECTING overload |
| [Case A P1a verdict](../analysis/mobile-validation-case-a-p1a-verdict.md) | Banner boundary PASS |
| Episode | `logs/mobile-validation-case-a-p1a-20260808-215605/` |

---

## Status board

```text
ADR-0044
========
REVIEWING
Decision:     CANDIDATE (DQ1=B · DQ2=Degraded+diagnostic · DQ3=NO CHANGE)
              formal Accept: PENDING
Impl:         NOT AUTHORIZED
Field:        NOT AUTHORIZED

Frozen upstream
===============
WiFi Recovery Architecture     CLOSED
ADR-0043 / RNA                 FROZEN
P1a banner boundary            CLOSED (PASS)
FAILED_MEDIA desk Q1–Q3        CLOSED OBSERVATION
```

---

## Next step

1. Review [adr0044-decision-review.md](../analysis/adr0044-decision-review.md).  
2. Formal **Accept** or **Amend** on this ADR (separate docs commit).  
3. Only after Accept: presentation-only implementation PR.
