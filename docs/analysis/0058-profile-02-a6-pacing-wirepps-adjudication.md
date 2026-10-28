# P02-A6 — Pacing, WirePpsCeiling, and FanoutDatagramRate (Adjudication)

## Status

```text
P02-A6: ACCEPTED
Date:   2026-09-03
Prerequisites: P02-A1..A5 ACCEPTED
Parent: 0058-profile-02-amendment-unicast-fanout-outline.md
Scope:  cadence metering under fan-out; NOT changing NominalPps=50 or WirePpsCeiling=60
Next:   P02-A7 (mixed-mode duplication at wire layer)
```

---

## Core question

After unicast fan-out, what does `WirePpsCeiling = 60` measure — and what metric
captures aggregate socket egress for capacity evidence?

**This adjudication does not change the numeric constants.** It freezes **what is
counted**.

---

## Decision summary

```text
ACCEPT:
  WirePpsCeiling = 60 applies to logical media emissions per Source timeline
  aggregate UDP sendto count across fan-out legs is NOT WirePps
  introduce FanoutDatagramRate as separate capacity/transport load metric
  fan-out replication MUST NOT catch up missed slots across legs
  existing late-media expendable / no historical catch-up semantics preserved
```

---

## Three distinct metrics (frozen)

Profile 02 post-amendment distinguishes three quantities. They MUST NOT be
conflated.

### 1. NominalPps (unchanged)

```text
NominalPps = 50 media slots/s per Source
           = derived from Q1 20 ms packetization only
           = normal Source media-time timeline cadence
           = not independently configurable
```

### 2. WirePpsCeiling (unchanged value; clarified metering object)

```text
WirePpsCeiling = 60 logical media emissions/s per Source
               = implementation validation / safety anomaly threshold only
               = counts logical RTP emissions on the Source timeline
               ≠ aggregate UDP datagrams/s across fan-out legs
               ≠ Traffic Profile / pacing target / rate limiter / congestion control
```

**Metering rule:**

```text
For each admitted Source S, count at most one logical media emission per
20 ms media-time slot t, regardless of how many transport legs carry slot t.

LogicalMediaEmissionPps(S) MUST NOT exceed WirePpsCeiling except as transient
anomaly detection — same role as pre-amendment Q8.

Fan-out leg count MUST NOT enter this calculation.
```

**Mixed-mode note (feeds A7):** If slot `t` is delivered on multicast **and**
unicast to the **same** receiver during a transition overlap window, that still
counts as **one** logical media emission for WirePpsCeiling — not two. Steady-state
duplicate delivery rules are A7.

### 3. FanoutDatagramRate (new — capacity metric only)

```text
FanoutDatagramRate(S, t)
  = logical media emissions per second for Source S
    × activeDeliveryLegs(S, t)

activeDeliveryLegs(S, t)
  = count of transport legs selected for slot t
    (multicast leg counts as 1 if active; each unicast leg counts as 1)

Example — worst-case unicast fallback, sender + 9 receivers:
  logicalMediaPps = 50
  activeDeliveryLegs = 9
  FanoutDatagramRate ≈ 450 UDP datagrams/s sender egress
```

```text
FanoutDatagramRate is:
  capacity / transport load evidence metric
  input to P02-A9 / N_max / ADR-0060 G-RES-3 capacity runs

FanoutDatagramRate is NOT:
  a Profile 02 wire cadence gate
  bounded by WirePpsCeiling
  a reason to suppress media slots or change ptime/bitrate
```

---

## Worked example (frozen interpretation)

```text
50 media slots/s × 9 unicast destinations = 450 UDP datagrams/s

Correct:
  LogicalMediaEmissionPps = 50        (within NominalPps)
  WirePpsCeiling check    = 50 ≤ 60   PASS
  FanoutDatagramRate      = 450       capacity evidence only

Incorrect (REJECT interpretation):
  WirePps = 450 → violates WirePpsCeiling = 60
```

---

## Fan-out replication scheduling (frozen)

Fan-out multiplies **destinations** for the **current valid artifact** only.

```text
FORBIDDEN:
  after socket/scheduling delay, queue and burst-deliver multiple missed
  historical slots × N legs to "catch up" receivers

FORBIDDEN:
  replicate old ProtectedDatagramArtifact for prior slots because some legs
  were not yet sent while others were

REQUIRED:
  slot cadence remains bounded on the Source timeline
  fan-out sends the current eligible slot's artifact to all selected legs
  late / missed slots remain expendable per existing Q8 burst rules
```

This extends Q8 without replacing it:

```text
forbidden (unchanged + fan-out explicit):
  RTP retransmission bursts
  RTCP / feedback-generated bursts
  historical-slot catch-up after scheduler delay
  fan-out catch-up bursts across legs for expired slots
```

```text
allowed (unchanged):
  bounded scheduler / socket jitter on an otherwise Q1 cadence
  spreading N sendto calls for the same slot within a bounded wire window
    (implementation-defined; MUST NOT emit additional logical media emissions)
```

---

## Anti-examples

### Anti-example 1 — misreading WirePpsCeiling

```text
Sender at 50 logical slots/s, 9 active unicast legs → 450 UDP datagrams/s.

Verdict: REJECT as WirePpsCeiling violation.

WirePpsCeiling applies to logical media emissions (50), not FanoutDatagramRate (450).
```

### Anti-example 2 — fan-out catch-up burst

```text
Socket blocks at slot t=100.
At t=105 scheduler wakes and attempts to send artifacts for slots 101,102,103,104,105
each to 9 unicast legs → 5 × 9 = 45 datagrams in a burst.

Verdict: REJECT.

Historical-slot catch-up is forbidden. Fan-out leg count MUST NOT legitimize a
multi-slot replay burst. At most the current eligible slot may be emitted;
expired slots are discarded.
```

---

## Relationship to prior adjudications

```text
A2/A3: one logical RTP emission per slot (seq advances once)
A4:    one ProtectedDatagramArtifact per slot, replicated to legs
A6:    one logical media emission per slot for WirePpsCeiling metering
       FanoutDatagramRate = emissions × legs for capacity only
A5:    each leg datagram still <= 120 bytes (independent per datagram)
```

---

## Profile 02 amendment text (normative insert)

### Q8 — Cadence and WirePpsCeiling (amendment)

Replace the metering paragraph under `WirePpsCeiling` with:

```text
WirePpsCeiling = 60 logical media emissions per second per Source.

A logical media emission is one emitted media-time slot on the Source timeline
(P02-A2/A3), regardless of fan-out leg count.

Fan-out does not increase the WirePpsCeiling count for a slot.

FanoutDatagramRate = logical media emissions/s × activeDeliveryLegs
  is a capacity/transport load metric for evidence (P02-A9 / G-RES-3).
  It is NOT bounded by WirePpsCeiling and is NOT a wire cadence gate.

Fan-out replication MUST NOT catch up missed historical slots across legs.
Q8 late-media expendable and no historical-slot catch-up rules apply unchanged.
```

---

## Consequences

```text
NominalPps=50 and WirePpsCeiling=60 numeric values unchanged
A9 / N_max capacity evidence may use FanoutDatagramRate directly
A7 may define overlap without reopening WirePpsCeiling semantics
A10 corpus C7: prove 50×N datagrams does not trip WirePpsCeiling; 61 logical emissions does
```

---

## References

- `docs/analysis/0058-profile-02-a4a5-srtp-wire-envelope-adjudication.md`
- `docs/analysis/0058-implementation-profile-02-rtp-wire.md` (Q8)
- `docs/adr/0060-conference-media-transport-resilience-contract.md`
