# P02-A9 — Fan-Out Execution Envelope and N_max Relationship (Adjudication)

## Status

```text
P02-A9: ACCEPTED — MODEL C1
Date:   2026-09-03
Prerequisites: P02-A1..A8 ACCEPTED
Parent: 0058-profile-02-amendment-unicast-fanout-outline.md
        ADR-0060 ACCEPTED — FROZEN (Decision 4, G-RES-3)
Scope:  contract variables, counting semantics, overflow failure class
        numeric cap values deferred to capacity evidence
Next:   P02-A10 (acceptance corpus)
```

---

## Core question

How does Profile 02 express the sender-side unicast fan-out execution limit, and how
does it relate to ADR-0060 `N_max`?

**Frozen answer:** Explicit hard cap in Profile 02; product envelope requirement in
ADR-0060; closure inequality between them. **No numeric value in A9.**

---

## Options adjudicated

### Model C1 — Explicit hard cap + ADR-0060 closure (ACCEPTED)

```text
MaxUnicastFanOutTargetsPerSource  = Profile 02 runtime/execution hard cap
N_max                             = ADR-0060 declared-envelope capacity requirement

MaxUnicastFanOutTargetsPerSource >= N_max
N_max >= declaredConferenceMembers - 1

Overflow → CAPACITY_NOT_ADMISSIBLE (not silent drop, not relay)
```

### Model C2 — No cap; best-effort send (REJECTED)

```text
Runtime sends to as many receivers as possible; excess silently skipped or delayed
REJECTED: silent degradation; no admission boundary; incompatible with G-RES-3
```

### Model C3 — Overflow triggers relay / owner replication (REJECTED)

```text
Exceed cap → auto-introduce relay Module
REJECTED: violates ADR-0060 V1 (no relay / no new topology authority)
```

---

## Frozen symbols

### Profile 02 — execution hard cap

```text
MaxUnicastFanOutTargetsPerSource
  = maximum distinct unicast fan-out targets one admitted Source may serve
    per media-time slot at send execution time
  = runtime / wire execution hard ceiling
  = numeric value NOT frozen in A9 — set with capacity evidence before cutover
```

### ADR-0060 — product envelope requirement

```text
N_max
  = worst-case simultaneous unicast fan-out demand of the declared V1 Conference
    member envelope
  = capacity evidence target (G-RES-3)
  = numeric value NOT frozen in A9 — frozen at product/capacity sign-off
```

### Closure inequality (frozen)

```text
For declared V1 Conference member envelope size S (including sender):

  worstCaseUnicastFanOutDemand = S - 1

  N_max >= worstCaseUnicastFanOutDemand

  MaxUnicastFanOutTargetsPerSource >= N_max
```

**Example — 10-member Conference:**

```text
worst-case sender fan-out = 9 (all other members on UNICAST_FALLBACK)
N_max >= 9
MaxUnicastFanOutTargetsPerSource >= 9
```

Profile 02 MUST NOT assume a default `S` or default cap. Values enter via product
declaration + capacity evidence + implementation configuration bound by the
inequality above.

---

## Frozen counting — ActiveUnicastFanOutTargets

Cap metering uses **unicast replication burden only**.

```text
ActiveUnicastFanOutTargets(S, t)
  = count of distinct receivers R for which Source S MUST execute a unicast
    delivery leg for slot t
```

### Counted

```text
each receiver R with authoritative or transition-mandated unicast leg for (S, t)
each distinct receiverModuleId once per slot
```

### NOT counted toward ActiveUnicastFanOutTargets

```text
multicast leg (group send is separate)
sender self
receivers in steady-state MULTICAST-only delivery (no unicast leg for this slot)
teardown / expired / inactive legs
multicast copy during transition overlap (overlap adds multicast datagram to
  FanoutDatagramRate but does not add a second unicast target for the same R)
```

### Transition overlap (A7)

```text
Receiver R in bounded overlap (multicast + unicast for same slot):
  ActiveUnicastFanOutTargets counts R as 1 unicast target
  not 2 (multicast + unicast are not double-counted for cap purposes)

Cap limits sender-side unicast replication burden, not total UDP leg count.
```

### Per-slot check (frozen)

```text
Before executing unicast fan-out for slot t:

  if ActiveUnicastFanOutTargets(S, t) > MaxUnicastFanOutTargetsPerSource
    → CAPACITY_NOT_ADMISSIBLE
    → MUST NOT silently drop subset of receivers
    → MUST NOT introduce relay
```

---

## Frozen overflow semantics — CAPACITY_NOT_ADMISSIBLE

```text
requiredTargets > MaxUnicastFanOutTargetsPerSource
  → CAPACITY_NOT_ADMISSIBLE
```

```text
CAPACITY_NOT_ADMISSIBLE is:
  capacity / admission failure
  evidence that the current Conference envelope exceeds execution hard cap

CAPACITY_NOT_ADMISSIBLE is NOT:
  PerLegDeliveryOutcome.SEND_FAILED (A8)
  silent degradation ("send to first N receivers only")
  trigger for relay / owner replication (C3 rejected)
  Profile 02 authority to terminate Conference, show UI, or reopen ADR-0056
```

P02-A9 freezes **failure class and forbidden behaviors** only. Session admission
policy, UI, and upper-layer rejection routing are **out of scope**.

---

## Relationship to FanoutDatagramRate (A6)

Capacity evidence uses aggregate datagram load:

```text
FanoutDatagramRate(S, t)
  = LogicalMediaEmissionPps(S)
    × ActiveUnicastFanOutTargets(S, t)
    + (1 if multicast leg emitted for slot t else 0)
```

**Example — 10 members, worst-case all unicast fallback:**

```text
LogicalMediaEmissionPps     = 50
ActiveUnicastFanOutTargets  = 9
unicast datagram rate         ≈ 450/s

If one receiver in transition overlap and multicast leg still emitted:
  ActiveUnicastFanOutTargets  = 9   (unchanged)
  FanoutDatagramRate          ≈ 450 + 50 = 500/s short-term
```

```text
FanoutDatagramRate feeds capacity evidence (G-RES-3).
MaxUnicastFanOutTargetsPerSource feeds execution admissibility.
WirePpsCeiling still applies only to LogicalMediaEmissionPps (A6).
```

---

## Split of responsibility (frozen)

| Concern | Owner |
|---------|--------|
| Hard cap symbol + per-slot check | Profile 02 (`MaxUnicastFanOutTargetsPerSource`) |
| Product envelope demand | ADR-0060 (`N_max`, `S - 1`) |
| Closure inequality | A9 + ADR-0060 G-RES-3 |
| Numeric values | Capacity evidence (not A9) |
| Overflow failure class | `CAPACITY_NOT_ADMISSIBLE` |
| Who blocks session / UX | Upper layers (not A9) |
| Sustained leg failure → DEAD | Profile 03 (not A9) |

---

## Anti-examples

### Anti-example 1 — silent drop

```text
11 receivers need unicast; cap = 10.
Sender sends to first 10, silently skips M11.

REJECT (C2). CAPACITY_NOT_ADMISSIBLE required; no silent subset drop.
```

### Anti-example 2 — relay on overflow

```text
Cap exceeded → elect M02 as relay for excess receivers.

REJECT (C3). ADR-0060 V1 forbids relay topology.
```

### Anti-example 3 — double-count overlap for cap

```text
R in overlap: count as 2 toward ActiveUnicastFanOutTargets because both
multicast and unicast fire.

REJECT. Overlap counts as 1 unicast target for cap purposes (A7 + A9).
```

---

## Profile 02 amendment text (normative insert)

```text
P02-A9 — Fan-out execution envelope:

MaxUnicastFanOutTargetsPerSource:
  hard cap on ActiveUnicastFanOutTargets per Source per slot.
  Numeric value set at implementation/capacity sign-off; not frozen in A9.

ActiveUnicastFanOutTargets:
  distinct receivers with unicast leg for slot t; see counting rules above.

If ActiveUnicastFanOutTargets > MaxUnicastFanOutTargetsPerSource:
  CAPACITY_NOT_ADMISSIBLE
  no silent receiver drop
  no relay

ADR-0060 closure:
  MaxUnicastFanOutTargetsPerSource >= N_max
  N_max >= declaredConferenceMembers - 1

Best-effort without cap (C2) and relay overflow (C3): FORBIDDEN in V1.
```

---

## Consequences

```text
C1 accepted; C2/C3 rejected
A10 corpus C6: CAPACITY_NOT_ADMISSIBLE at cap boundary
Capacity runs may use FanoutDatagramRate at N_max without reopening A6/A8
Numeric MaxUnicastFanOutTargetsPerSource and N_max frozen at G-RES-3 sign-off
```

---

## References

- `docs/adr/0060-conference-media-transport-resilience-contract.md` (Decision 4, G-RES-3)
- `docs/analysis/0058-profile-02-a6-pacing-wirepps-adjudication.md`
- `docs/analysis/0058-profile-02-a7-mixed-mode-overlap-adjudication.md`
- `docs/analysis/0058-profile-02-a8-send-failure-isolation-adjudication.md`
