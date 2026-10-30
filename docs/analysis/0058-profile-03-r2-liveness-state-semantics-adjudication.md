# P03-R2 — ALIVE / SUSPECT / DEAD State Semantics (Adjudication)

## Status

```text
P03-R2: ACCEPTED
Date:   2026-09-03
Prerequisite: P03-R1 ACCEPTED
Parent: 0058-profile-03-resilience-amendment-outline.md
        ADR-0060 Decision 2
Scope:  three-state meaning + optimization eligibility + invariants
        NO timer numerics (R3)
        NO publication freshness (R4)
        NO sender FSM / mandatory downgrade timing (R5/R6)
Next:   P03-R3
```

---

## Question

What do `ALIVE`, `SUSPECT`, and `DEAD` **mean** for `MulticastReceiveLiveness` — and what
optimization eligibility do they imply?

R2 freezes **semantics and safety class only**, not transition thresholds.

---

## Decision summary

```text
ACCEPTED:
  three-state model ALIVE | SUSPECT | DEAD
  initial state = SUSPECT (no UNKNOWN fourth state)
  optimization eligibility dimension separate from sender execution policy

REJECTED:
  SUSPECT equivalent to ALIVE for multicast eligibility
  SUSPECT mandating immediate sender UNICAST (R5/R6 scope)
  DEAD as permanent device/network incapability
  single packet establishing ALIVE or single gap establishing DEAD
```

---

## State definitions (receiver-side meaning)

### ALIVE

```text
Receiver has sustained, recent, sufficient admitted multicast ingress evidence
that multicast optimization is currently eligible for this receiver.

Multicast optimization eligibility: ELIGIBLE
```

`ELIGIBLE` means publication MAY assert that multicast optimization path is
**currently supported by receive-side evidence**. It does not command sender
behavior (R5).

### SUSPECT

```text
Evidence that previously supported ALIVE is weakening or absent, but liveness
has not yet been judged lost; OR initial observation scope has not yet earned ALIVE.

Transient observation state — uncertainty.

Multicast optimization eligibility: NOT_PROVEN
```

```text
NOT_PROVEN ≠ NOT_ELIGIBLE

NOT_PROVEN means:
  neither "proven good" nor "proven bad"
  multicast optimization MUST NOT be treated as currently reliable
  MUST NOT be read as permission to assume ALIVE-equivalent eligibility
```

**Critical:** `SUSPECT` MUST NOT be treated as `ALIVE` for multicast eligibility.
If `SUSPECT → ELIGIBLE`, the state collapses into ALIVE and R3 detection windows
become correctness outages.

R2 does **not** require sender to immediately select `UNICAST_FALLBACK` on
`SUSPECT` — that is sender execution policy (R5/R6). ADR-0060 already forbids
upgrade to MULTICAST on less than fresh ALIVE; `NOT_PROVEN` blocks treating
SUSPECT as upgrade-eligible.

### DEAD

```text
Multicast receive liveness is judged lost for the current valid observation scope.

Multicast optimization eligibility: NOT_ELIGIBLE
```

```text
DEAD ≠ device permanently cannot receive multicast
DEAD ≠ network permanently incompatible
DEAD ≠ Conference failure

DEAD means only:
  within the current valid observation scope, the receiver currently has no
  dependable multicast receive liveness for optimization purposes
```

Recovery to `ALIVE` remains possible via new sufficient ingress evidence (R3
defines recovery hysteresis). `DEAD` is **current** liveness loss, not a permanent
capability stamp.

---

## Optimization eligibility table (frozen)

| State | Receiver-side meaning | Multicast optimization eligibility |
|-------|----------------------|----------------------------------|
| **ALIVE** | Sustained recent ingress sufficiently proven | `ELIGIBLE` |
| **SUSPECT** | Evidence insufficient to prove current reliability | `NOT_PROVEN` |
| **DEAD** | Liveness judged lost | `NOT_ELIGIBLE` |

```text
Only ALIVE carries explicit ELIGIBLE.

SUSPECT and DEAD MUST NOT be published or consumed as ELIGIBLE.
DEAD MUST NOT be conflated with NOT_PROVEN — DEAD is explicit loss, not uncertainty.
```

---

## Initial state (frozen)

```text
On new receiver observation scope incarnation:

  initial MulticastReceiveLiveness = SUSPECT

Semantics:
  sufficient sustained multicast receive evidence has NOT yet been established.

No UNKNOWN fourth state:
  initial uncertainty is expressed by SUSPECT + NOT_PROVEN.
```

**Observation scope incarnation** (structural pointer — fences in R9):

```text
examples: new transport session for Conference media, scope reset per R9
R2 does not freeze reset triggers — only that initial ≠ ALIVE
```

---

## State invariants (frozen)

### R2-I1 — ALIVE is earned, never assumed

```text
ALIVE MUST NOT be the initial state.
ALIVE MUST NOT be set from session-start join, IGMP, MulticastLock, or roster ONLINE.
ALIVE requires sustained recent ingress evidence per R3 (thresholds deferred).
```

### R2-I2 — SUSPECT is uncertainty, not proof of health

```text
SUSPECT MUST NOT imply ELIGIBLE.
SUSPECT MUST NOT be treated as "still ALIVE for practical purposes."
SUSPECT is NOT_PROVEN — neither proof of health nor proof of failure.
```

### R2-I3 — DEAD is current loss, not permanent incapability

```text
DEAD is scoped to current observation validity.
DEAD MUST allow future transition toward ALIVE when R3 recovery criteria met.
DEAD MUST NOT be stored as immutable device capability without scope reset (R9).
```

### R2-I4 — No authority mutation

```text
MulticastReceiveLiveness state change MUST NOT directly mutate:
  Membership
  Source identity / SSRC admission
  conferenceEpoch / membershipVersion / mediaKeyEpoch
  Conference readiness for other members
```

---

## Packet-level structural rules (no timers)

R2 freezes **shape** of transitions; R3 owns **when**.

### Isolated valid packet does not establish ALIVE

```text
SUSPECT or DEAD
  + one isolated admitted multicast packet
≠ ALIVE

Recovery to ALIVE requires sustained evidence / hysteresis (R3).
```

Aligns with R1 and E3a.3b (brief receive window then loss).

### Isolated missing packet does not establish DEAD

```text
ALIVE
  + one isolated missing expected packet
≠ DEAD

Weakening toward SUSPECT or DEAD requires R3 window policy.
```

### Transition ownership (deferred)

```text
R3 adjudicates:
  ALIVE → SUSPECT
  SUSPECT → DEAD
  SUSPECT → ALIVE
  DEAD → ALIVE (recovery)

R5/R6 adjudicate:
  what sender DOES when consuming published ELIGIBLE / NOT_PROVEN / NOT_ELIGIBLE
```

---

## Explicit non-decisions in R2

```text
NOT in R2:
  ALIVE timeout = ___ ms
  SUSPECT duration before DEAD
  packets-per-window thresholds
  publication freshness / epoch (R4)
  sender must downgrade on SUSPECT (R5/R6)
  sender must stay on MULTICAST during SUSPECT (R5)
  TransitionOverlapMaxMs (R8)
```

---

## Relationship to ADR-0060

```text
ADR-0060:
  DEAD (published) → sender MUST use UNICAST_FALLBACK
  SUSPECT → sender MUST NOT upgrade to MULTICAST without fresh ALIVE

R2 makes SUSPECT semantically distinct so ADR-0060 "no upgrade without ALIVE"
is meaningful and SUSPECT cannot substitute for ALIVE eligibility.
```

R2 does not mandate sender action on SUSPECT alone — only eligibility class.

---

## Profile 03 amendment text (normative insert)

```text
P03-R2 — MulticastReceiveLiveness states:

ALIVE:    sustained recent ingress proven → optimization eligibility ELIGIBLE
SUSPECT:  evidence insufficient → NOT_PROVEN (not ELIGIBLE, not assumed healthy)
DEAD:     liveness lost → NOT_ELIGIBLE (current scope; recoverable per R3)

initial = SUSPECT

Invariants R2-I1..I2-I4 as frozen.

Isolated packet ≠ ALIVE; isolated gap ≠ DEAD.
Transition thresholds: R3. Sender policy: R5/R6.
```

---

## Consequences

```text
R3 may define evidence windows and hysteresis without reopening state meanings
R4 publication carries state + eligibility class + freshness identity
R5 sender FSM: only ALIVE publication supports MULTICAST upgrade eligibility
R7 recovery: DEAD → ALIVE path exists; anti-flap in R3 not R2
```

---

## References

- `docs/analysis/0058-profile-03-r1-liveness-observation-ownership-adjudication.md`
- `docs/analysis/0058-profile-03-resilience-amendment-outline.md`
- `docs/adr/0060-conference-media-transport-resilience-contract.md`
- E3a.3b establish-then-die (motivation for NOT_PROVEN and non-single-packet ALIVE)
