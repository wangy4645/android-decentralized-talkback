# P03-R6 — Downgrade Execution Semantics (Adjudication)

## Status

```text
P03-R6: ACCEPTED — MODEL D1
Date:   2026-09-03
Prerequisites: P03-R1..R5 ACCEPTED
        Profile 02 A7 (overlap duplicate semantics)
Parent: 0058-profile-03-resilience-amendment-outline.md
        ADR-0060 Decision 3 (fallback transition)
Scope:  when fallback starts; authoritative handoff; overlap; failure branches
        TransitionOverlapMaxMs numeric → R8
Next:   P03-R7 (upgrade / recovery execution)
```

---

## Question

Once the sender loses multicast eligibility proof (R5), **when** does fallback
execute, **when** does `UNICAST_FALLBACK` become authoritative, and **may**
downgrade wait?

---

## Options adjudicated

| Model | Description | Verdict |
|-------|-------------|---------|
| **D1** | Immediate fail-safe unicast activation + bounded A7 overlap; sender commit authoritative | **ACCEPT** |
| **D2** | Wait for fresh DEAD before executing downgrade | **REJECT** (R5) |
| **D3** | Stop multicast reliance first, then start unicast | **REJECT** (gap) |
| **D4** | Wait for receiver ACK / unicast media proof before authoritative | **REJECT** (no ACK contract) |

---

## Downgrade trigger (frozen)

```text
Downgrade trigger =
  sender observes any condition that makes MULTICAST no longer eligible
  for remote receiver R (per R5 mapping)
```

Including (non-exhaustive):

```text
fresh SUSPECT published/consumed
fresh DEAD published/consumed
publication knowledge becomes STALE
no valid current fact for (R, incarnation)
incarnation / freshness scope reset (REJECT_STALE_SCOPE class)
loss of FRESH + ALIVE combination while in MULTICAST
```

```text
Downgrade trigger ≠ requirement to wait for:
  receiver to publish DEAD (SUSPECT already sufficient)
  Membership change
  conference owner / anchor approval
  renegotiation
  multicast socket teardown completion
  control-plane confirmation round-trip
```

All wait conditions above → **REJECT**.

---

## D1 execution semantics (frozen)

### Phase sequence

```text
Prior steady state (may be):
  PerReceiverTransportMode = MULTICAST
  multicast leg authoritative for R

On downgrade trigger for R:

1. IMMEDIATELY activate receiver-specific unicast delivery leg
   (PerReceiverUnicastEndpoint per P02-A1)
   for current slot and forward

2. A7 bounded overlap MAY exist:
   multicast leg MAY continue sending same ProtectedDatagramArtifact
   concurrently during transition window (R8 bounds duration)

3. Sender execution commit:
   when unicast delivery leg is admitted and activated for
   (R, conferenceMediaIncarnation)
   → PerReceiverTransportMode := UNICAST_FALLBACK (authoritative)

4. After authoritative commit:
   multicast to R is non-authoritative overlap artifact only
   MUST NOT be sole correctness dependency (A7 steady-state rules)
```

### "Immediately" definition

```text
IMMEDIATELY means:
  no waiting for extra control-plane confirmation (see reject list above)
  unicast activation MUST begin in the same sender execution scope
    as downgrade trigger handling (same slot pipeline or next eligible slot
    without intentional deferral — implementation detail bounded by R8)

IMMEDIATELY does NOT mean:
  zero-latency physical send completion guaranteed
  forbidden to use bounded overlap while unicast path establishes
```

---

## Authoritative transition point (frozen)

```text
Authoritative commit occurs at SENDER execution:

  unicast delivery leg admitted and activated
  for (remoteReceiverModuleId, conferenceMediaIncarnation)

  → sender commits PerReceiverTransportMode = UNICAST_FALLBACK
```

```text
NOT required for authoritative commit:
  receiver ACK
  receiver unicast media proof / first received packet
  RTCP or application-level confirmation (forbidden by Profile 02 Q7)

Sender-side execution commit IS the authoritative mode transition.
```

Aligns with ADR-0060: downgrade is transport execution, not Membership mutation.

---

## Overlap ordering (A7 reinforcement)

```text
Downgrade overlap is one-way safe:

  new correctness path (unicast) activated FIRST
  old optimization path (multicast) MAY overlap but stops being relied upon

FORBIDDEN (D3):
  stop multicast reliance / tear down multicast leg
  → wait
  → then start unicast

Reason: creates avoidable audio gap (O2-class hard switch rejected at A7).
```

During overlap:

```text
same RTP identity on both legs (P02-A2/A3/A4)
→ duplicate delivery per P02-A7 / P03-R3
→ decode at most once
```

```text
Overlap multicast is non-authoritative after unicast leg activation begins;
full authoritative flip at sender commit per above.
```

`TransitionOverlapMaxMs` numeric bound → **R8** (not R6).

---

## Failure branch (frozen)

```text
If downgrade required for R
AND unicast delivery leg cannot be admitted or activated
  (endpoint unavailable, socket/resource failure, CAPACITY_NOT_ADMISSIBLE, …)

THEN:
  transport correctness cannot be satisfied for R on current slot path
  MUST surface explicit local failure / capacity failure
  MUST NOT silently remain authoritative MULTICAST
  MUST NOT pretend UNICAST_FALLBACK is active without leg activation
```

```text
Use existing failure classes where applicable:
  CAPACITY_NOT_ADMISSIBLE (P02-A9)
  PerLegDeliveryOutcome.SEND_FAILED (P02-A8)
  Profile 03 runtime degradation evidence (bounded)

R6 does NOT invent a new umbrella failure type.
```

```text
Continuing MULTICAST as correctness path because unicast failed → FORBIDDEN (R6-I4)
```

---

## Timeline preservation (frozen)

```text
Downgrade execution MUST NOT:
  reset RTP sequence / timestamp / SSRC
  re-protect slot with new sender SRTP context
  mint new Source identity

Unicast leg carries same ProtectedDatagramArtifact timeline (P02-A2–A4).
Mode transition only; not Source incarnation change.
```

---

## Invariants (frozen)

### R6-I1 — No external gate

```text
Downgrade MUST NOT wait for Membership, owner, renegotiation, or explicit DEAD
beyond R5 trigger conditions.
```

### R6-I2 — Correctness path first

```text
Unicast activation MUST precede or coincide with reduction of multicast reliance;
MUST NOT defer unicast behind multicast teardown (D3 rejected).
```

### R6-I3 — Timeline continuity

```text
Downgrade does not reset RTP/SRTP timeline or Source identity (P02).
```

### R6-I4 — Explicit failure

```text
Failure to establish required unicast MUST surface explicitly;
MUST NOT justify continuing MULTICAST as correctness path.
```

---

## Relationship to R5 / R7

```text
R5: defines WHEN mode eligibility is lost (FSM transitions)
R6: defines HOW downgrade executes once triggered (this document)
R7: upgrade/recovery — MAY be stricter / slower than downgrade (asymmetric)
```

---

## Explicit non-decisions in R6

```text
NOT in R6:
  TransitionOverlapMaxMs value (R8)
  upgrade execution (R7)
  publication transport wire
  UI / session teardown policy
  multi-receiver coordinated downgrade ordering
```

---

## Profile 03 amendment text (normative insert)

```text
P03-R6 — Downgrade execution (D1):

On loss of multicast eligibility proof: immediately activate unicast leg for R;
bounded A7 overlap permitted; sender commit on unicast leg admitted/activated
→ UNICAST_FALLBACK authoritative.

No wait for DEAD-only, Membership, owner, renegotiation, ACK, multicast teardown.

Unicast activation before multicast reliance reduction; D3 order forbidden.
Unicast establishment failure → explicit failure; no silent MULTICAST retention.

D2/D3/D4 rejected. R6-I1..I4 frozen.
```

---

## Consequences

```text
R7 may require stricter upgrade path than downgrade (intentional asymmetry)
R8 freezes TransitionOverlapMaxMs for overlap window
R10: downgrade trigger vectors, D3 negative, unicast-fail-no-multicast-retain negative
Profile 02 execution: immediate PerReceiverUnicastEndpoint on R5 trigger
```

---

## References

- `docs/analysis/0058-profile-03-r5-sender-transport-mode-fsm-adjudication.md`
- `docs/analysis/0058-profile-02-a7-mixed-mode-overlap-adjudication.md`
- `docs/analysis/0058-profile-02-a8-send-failure-isolation-adjudication.md`
- `docs/adr/0060-conference-media-transport-resilience-contract.md` (Decision 3)
