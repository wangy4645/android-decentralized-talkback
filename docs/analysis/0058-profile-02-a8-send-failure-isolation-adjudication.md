# P02-A8 — Per-Leg Send Failure Isolation (Adjudication)

## Status

```text
P02-A8: ACCEPTED — MODEL F1
Date:   2026-09-03
Prerequisites: P02-A1..A7 ACCEPTED
Parent: 0058-profile-02-amendment-unicast-fanout-outline.md
Scope:  transport send isolation per delivery leg; NOT receiver capability DEAD
Next:   P02-A9 (fan-out execution envelope / N_max relationship)
```

---

## Core question

If unicast `sendto` fails for one receiver on one slot, may that failure affect
other receivers, the multicast leg, or the Source RTP/SRTP timeline?

**Frozen answer:** No. **Failure isolation = per delivery leg.**

---

## Authority boundary

```text
P02-A8 defines:     transport send isolation at fan-out
Profile 03 defines: when repeated leg failures imply MulticastReceiveLiveness DEAD
                    or trigger mode transition

A8 MUST NOT freeze capability / liveness policy.
```

---

## Options adjudicated

### Model F1 — Per-leg independent delivery outcome (ACCEPTED)

```text
One logical media emission per slot (A2–A4, A6)
Each delivery leg has its own PerLegDeliveryOutcome
Leg failure is local; other legs continue
No timeline rollback; no SRTP re-protect; no cross-slot catch-up
```

### Model F2 — Any leg failure fails whole slot (REJECTED)

```text
One sendto failure → entire slot marked failed / retried
→ risks seq hold-back or re-protect
REJECTED: violates single logical emission per slot
```

### Model F3 — Per-receiver retransmit queue (REJECTED)

```text
Failed receiver gets historical slot queue for later catch-up
REJECTED: violates Q7/Q8 no RTP retransmission and A6 no catch-up
```

---

## Frozen decision — outcome separation

Profile 02 distinguishes **logical emission** from **per-leg delivery**.

### LogicalMediaEmissionOutcome (per Source, per slot)

```text
EMITTED
  = encode + RTP identity + SRTP protect completed for slot t
  = seq/ROC/timeline advanced per A2–A4
  = occurs once per slot regardless of fan-out leg count

NOT USED in V1:
  EMIT_FAILED because one leg's sendto failed
```

A single leg `sendto` failure means **the artifact was not delivered on that leg**,
not that the logical RTP emission did not occur.

### PerLegDeliveryOutcome (per leg, per slot)

```text
SENT            datagram accepted by local transport for this leg
DROPPED_LOCAL   local policy dropped before send (evidence only)
SEND_FAILED     sendto / socket error / bounded backpressure drop for this leg
```

```text
PerLegDeliveryOutcome is transport evidence.
It MUST NOT:
  roll back sequence number or ROC
  trigger SRTP re-protect for the same slot
  block or cancel other legs for the same slot
  mutate Membership, Source admission, or key context
  imply receiver capability DEAD (Profile 03)
```

---

## Frozen decision — fan-out send semantics

For slot `t` with `ProtectedDatagramArtifact_t` and active legs
`{multicast?, unicast(R2), unicast(R3), …}`:

```text
ProtectedDatagramArtifact_t
  ├─ multicast leg          → PerLegDeliveryOutcome
  ├─ unicast → M02          → PerLegDeliveryOutcome
  ├─ unicast → M03          → PerLegDeliveryOutcome   ← SEND_FAILED allowed alone
  └─ unicast → M04          → PerLegDeliveryOutcome
```

### Example (frozen)

```text
Slot t, M03 sendto FAIL:

  Record:     PerLegDeliveryOutcome(M03) = SEND_FAILED
  Continue:   multicast, M02, M04 legs for slot t
  Timeline:   seq/ROC already advanced — no rollback
  Protect:    no second SRTP protect for slot t
  Next slot:  emit slot t+1 only — no catch-up of t to M03
```

---

## Frozen rules

### R1 — Timeline immunity

```text
Per-leg SEND_FAILED MUST NOT:
  prevent LogicalMediaEmissionOutcome = EMITTED for the slot
  hold back or revert RTP sequence / timestamp / sender ROC
  cause a second logical emission or re-protect for the same slot
```

### R2 — Cross-leg non-blocking isolation

```text
One leg's failure, backpressure, or socket error MUST NOT:
  indefinitely block sendto to other legs for the same slot
  cancel already-successful legs for the same slot

Per-leg failure and backpressure MUST be bounded and isolated.

Implementation (nonblocking socket, short timeout, bounded queue) is
Profile 03 / runtime — not frozen here. The contract outcome is frozen.
```

A sequential `for (leg in legs) sendto(...)` loop is permitted only if it
cannot propagate unbounded wait from one leg to others.

### R3 — No cross-slot catch-up (A6 alignment)

```text
Slot 100: M03 SEND_FAILED
Slot 101: emit slot 101 only

FORBIDDEN:
  retry slot 100 to M03 during slot 101 or later
  burst historical artifacts to a leg that previously failed
```

Aligns with A6: late media expendable; no historical-slot catch-up; fan-out
does not legitimize retransmit queues (F3 rejected).

### R4 — Multicast leg independence

```text
Unicast leg failure for receiver R MUST NOT:
  suppress multicast leg for the same slot (when multicast is active)
  suppress unicast to other receivers for the same slot
```

### R5 — No whole-slot retry (F2 rejected)

```text
FORBIDDEN:
  any leg failed → mark slot failed → retry entire slot for all legs
  any leg failed → withhold seq advance until all legs succeed
```

---

## Relationship to prior adjudications

```text
A2/A3: one seq advance per slot — leg failure does not undo it
A4:    one protect per slot — leg failure does not re-protect
A6:    one logical emission for WirePpsCeiling — independent of per-leg SEND_FAILED count
A7:    steady-state single authoritative leg per receiver; A8 isolates send failures
       within the legs actually selected for a slot
```

---

## Anti-examples

### Anti-example 1 — logical failure on leg failure

```text
M03 sendto fails on slot 500.
Implementation sets LogicalMediaEmissionOutcome = EMIT_FAILED and does not
advance seq.

REJECT (F2). Timeline must advance; failure is PerLegDeliveryOutcome only.
```

### Anti-example 2 — retransmit queue

```text
M03 failed slots 498, 499, 500 queued; sender bursts them at slot 510.

REJECT (F3). No per-receiver historical retransmit queue.
```

### Anti-example 3 — blocking other legs

```text
M03 socket blocks; M02 and M04 sendto for same slot never run.

REJECT. Per-leg isolation — other legs must not be indefinitely blocked.
```

---

## Profile 02 amendment text (normative insert)

```text
P02-A8 — Fan-out send failure isolation:

For each slot, LogicalMediaEmissionOutcome (EMITTED) is independent of
PerLegDeliveryOutcome per delivery leg.

Per-leg SEND_FAILED:
  local transport evidence only
  MUST NOT roll back RTP/SRTP timeline
  MUST NOT re-protect or re-emit logical slot for other legs
  MUST NOT block other legs beyond bounded isolation
  MUST NOT trigger cross-slot catch-up or retransmit queue

Whole-slot fail/retry (F2) and per-receiver retransmit queue (F3): FORBIDDEN.

Receiver capability DEAD / mode transition on sustained failure: Profile 03.
```

---

## Consequences

```text
F1 accepted; F2/F3 rejected
Runtime may implement bounded per-leg send without reopening A2–A6
A9 may use FanoutDatagramRate and leg-failure observability for capacity evidence
A10 corpus: leg-isolation vectors (one SEND_FAILED, others SENT)
```

---

## References

- `docs/analysis/0058-profile-02-a6-pacing-wirepps-adjudication.md`
- `docs/analysis/0058-profile-02-a7-mixed-mode-overlap-adjudication.md`
- `docs/analysis/0058-profile-02-a2a3-encode-rtp-timeline-adjudication.md`
- `docs/analysis/0058-implementation-profile-02-rtp-wire.md` (Q7, Q8)
- `docs/adr/0060-conference-media-transport-resilience-contract.md`
