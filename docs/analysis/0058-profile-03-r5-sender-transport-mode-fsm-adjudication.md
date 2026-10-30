# P03-R5 — Sender PerReceiverTransportMode FSM (Adjudication)

## Status

```text
P03-R5: ACCEPTED — MODEL M1
Date:   2026-09-03
Prerequisites: P03-R1..R4 ACCEPTED
Parent: 0058-profile-03-resilience-amendment-outline.md
        ADR-0060 Decision 2 (sender owns delivery-mode execution)
Scope:  PerReceiverTransportMode FSM; input mapping; upgrade/downgrade admission
        PublicationFreshnessTimeout numeric NOT frozen (consumes FRESH/STALE from R4)
Next:   P03-R6 (downgrade execution semantics)
```

---

## Question

How does the sender choose and maintain `PerReceiverTransportMode` per remote
receiver from consumed `MulticastReceiveLivenessFact` knowledge?

**Frozen principle:**

```text
Only fresh ALIVE may maintain or enter MULTICAST.
All other knowledge states MUST NOT let correctness depend on multicast.
```

---

## Options adjudicated

| Model | Description | Verdict |
|-------|-------------|---------|
| **M1** | Default UNICAST_FALLBACK; MULTICAST only on fresh ALIVE eligibility; fail-safe downgrade | **ACCEPT** |
| **M2** | Fallback only on fresh DEAD; SUSPECT may stay MULTICAST | **REJECT** |
| **M3** | Fresh ALIVE auto-forces MULTICAST (receiver fact as command) | **REJECT** |
| **M4** | Session starts MULTICAST until failure | **REJECT** |

---

## Execution states (frozen)

```text
PerReceiverTransportMode
  MULTICAST
  UNICAST_FALLBACK

No third transport mode in V1.
```

Mode is **per (sender, remoteReceiverModuleId, conferenceMediaIncarnation)**.

---

## Sender knowledge input (from R4)

For each remote receiver `R`:

```text
knowledgeStatus     FRESH | STALE
lastAcceptedFact    MulticastReceiveLivenessFact | none
  └─ livenessState  ALIVE | SUSPECT | DEAD (when fact present)
```

R5 consumes R4 outputs. It does **not** define `PublicationFreshnessTimeout` numerics.

---

## Mode eligibility mapping (frozen)

| Sender knowledge | Receiver fact (if any) | Multicast mode eligibility |
|------------------|------------------------|----------------------------|
| FRESH | ALIVE | `MULTICAST` **eligible** |
| FRESH | SUSPECT | `UNICAST_FALLBACK` **required** |
| FRESH | DEAD | `UNICAST_FALLBACK` **required** |
| STALE | any / last known | `UNICAST_FALLBACK` **required** |
| no accepted fact yet | — | `UNICAST_FALLBACK` **required** |

```text
Explicit R5 ruling:
  FRESH + SUSPECT → MUST NOT remain sole authoritative MULTICAST path
  (R2 NOT_PROVEN must not be treated as ELIGIBLE)

Only FRESH + ALIVE row permits MULTICAST eligibility.
```

```text
STALE:
  MUST NOT sustain MULTICAST on stale ALIVE cache (M1 fail-safe)
  MUST NOT invent receiver DEAD (R4-I5)
  → UNICAST_FALLBACK required (correctness path)
```

---

## FSM (frozen)

```text
initial (per receiver, per incarnation)
  → UNICAST_FALLBACK

UNICAST_FALLBACK
  -- fresh ALIVE + upgrade admission satisfied --> MULTICAST

MULTICAST
  -- fresh SUSPECT ------------------------------> UNICAST_FALLBACK
  -- fresh DEAD ----------------------------------> UNICAST_FALLBACK
  -- knowledge becomes STALE --------------------> UNICAST_FALLBACK
  -- incarnation mismatch / scope reset ---------> UNICAST_FALLBACK
  -- fresh ALIVE lost (no longer FRESH+ALIVE) ----> UNICAST_FALLBACK
```

```text
No path from initial → MULTICAST without fresh ALIVE + upgrade admission.
No path remains in MULTICAST under SUSPECT, DEAD, STALE, or no fact.
```

---

## Asymmetry: fail-safe downgrade vs gated upgrade

### Downgrade — fail-safe (frozen)

```text
Loss of multicast eligibility proof → leave MULTICAST

Triggers include (non-exhaustive):
  fresh SUSPECT
  fresh DEAD
  STALE knowledge (including stale last-known ALIVE)
  incarnation mismatch / reset
  loss of FRESH+ALIVE combination

Downgrade:
  MUST NOT wait for receiver DEAD if SUSPECT already published
  MUST NOT require Membership mutation, owner action, or renegotiation
  MUST NOT block correctness path (Profile 02 unicast fan-out)
```

Execution timing details (overlap, authoritative handoff) → **R6**.

### Upgrade — eligibility ≠ execution (frozen)

```text
fresh ALIVE
  = upgrade eligibility only
  ≠ automatic MULTICAST transition
  ≠ receiver command to sender

explicit sender mode transition
  = upgrade execution (sender-owned decision)

Receiver publishes observation; sender decides when to enter MULTICAST
after eligibility + local upgrade admission checks.
```

```text
FORBIDDEN (M3):
  consuming fresh ALIVE immediately forces MULTICAST without sender transition

Upgrade admission (local, sender-owned — structural; numerics in R7):
  MAY include hysteresis beyond single fresh ALIVE fact (R7 recovery anti-flap)
  R5 freezes: fresh ALIVE necessary, not sufficient alone for execution instant
```

---

## Initial behavior (frozen)

```text
On new (sender, receiver, conferenceMediaIncarnation) binding:

  PerReceiverTransportMode = UNICAST_FALLBACK

Until fresh ALIVE fact accepted and upgrade executed:

  correctness MUST NOT depend on multicast to that receiver
```

```text
FORBIDDEN (M4):
  default MULTICAST for new/joining receiver pending liveness proof
```

This closes the multicast-only correctness bypass at session join.

---

## Relationship to Profile 02

```text
PerReceiverTransportMode selects Profile 02 delivery directive per receiver per slot:
  MULTICAST           → group leg (+ unicast only during R6/R8 overlap rules)
  UNICAST_FALLBACK    → PerReceiverUnicastEndpoint leg

R5 does not redefine wire semantics.
```

---

## Explicit non-decisions in R5

```text
NOT in R5:
  PublicationFreshnessTimeout ms (R4 semantic only; numeric follow-up)
  publication cadence / transport wire
  downgrade overlap timing (R6)
  upgrade hysteresis numerics (R7)
  TransitionOverlapMaxMs (R8)
  incarnation reset triggers (R9)
  evidence / observability record format
```

---

## Invariants (frozen)

```text
R5-I1  UNICAST_FALLBACK is safe default and correctness mode.
R5-I2  MULTICAST only when FRESH + ALIVE eligibility + upgrade executed.
R5-I3  SUSPECT/DEAD/STALE/no-fact → MUST NOT be sole multicast dependency.
R5-I4  fresh ALIVE grants eligibility, not imperative mode switch.
R5-I5  Downgrade fail-safe; no Membership/epoch mutation.
```

---

## Profile 03 amendment text (normative insert)

```text
P03-R5 — PerReceiverTransportMode FSM (M1):

States: MULTICAST | UNICAST_FALLBACK only.
Initial: UNICAST_FALLBACK.

Eligibility: only FRESH+ALIVE → MULTICAST eligible; all other rows → UNICAST_FALLBACK required.

Upgrade: fresh ALIVE + explicit sender transition (+ R7 admission).
Downgrade: loss of proof → UNICAST_FALLBACK (fail-safe).

M2/M3/M4 rejected.
```

---

## Consequences

```text
R6: downgrade execution — when unicast becomes authoritative, A7 overlap start/stop
R7: upgrade execution — recovery hysteresis beyond single ALIVE
R8: overlap bounded while mode transitions
R10: FSM table-driven acceptance tests
ADR-0060 G-RES-2 partially satisfied at contract layer; implementation + R10 remain
```

---

## References

- `docs/analysis/0058-profile-03-r4-publication-freshness-adjudication.md`
- `docs/analysis/0058-profile-03-r2-liveness-state-semantics-adjudication.md`
- `docs/adr/0060-conference-media-transport-resilience-contract.md`
- `docs/analysis/0058-profile-02-a7-mixed-mode-overlap-adjudication.md`
