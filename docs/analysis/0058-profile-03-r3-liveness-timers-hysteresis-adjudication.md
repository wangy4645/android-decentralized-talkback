# P03-R3 — Liveness Evidence, Timers, and Hysteresis (Adjudication)

## Status

```text
P03-R3: ACCEPTED — MODEL T1
Date:   2026-09-03
Prerequisites: P03-R1 ACCEPTED, P03-R2 ACCEPTED
Parent: 0058-profile-03-resilience-amendment-outline.md
Scope:  transition structure + V1 initial timer constants + evidence rules
        publication freshness (R4), sender FSM (R5) NOT in R3
Next:   P03-R4
```

---

## Question

How is `MulticastReceiveLiveness` earned, lost, and recovered — using **admitted
multicast media ingress** as the sole clock input?

R3 freezes **structure, evidence unit definition, and V1 initial numerics**.
Future field tuning MAY amend constants without reopening R1/R2 semantics.

---

## Options adjudicated

| Model | Description | Verdict |
|-------|-------------|---------|
| **T1** | Dual-threshold earned/recovery + span+count; 300ms SUSPECT / 1000ms DEAD | **ACCEPT** |
| **T2** | Single packet or ultra-short window establishes ALIVE | **REJECT** (R1/R2) |
| **T3** | 120ms no-packet → DEAD (playout deadline as liveness timeout) | **REJECT** |
| **T4** | Packet count only, no observation span | **REJECT** (burst forges ALIVE) |

---

## Evidence clock (frozen)

```text
Sole liveness clock input:
  valid admitted multicast media packet observation on MULTICAST_INGRESS path
  (Profile 02 admitted; post Q5/Q6 admission to media observation seam)

MUST NOT refresh liveness:
  raw UDP/socket receive without admission
  IGMP / MulticastLock / WiFi counters
  unicast fan-out leg packets (UNICAST_INGRESS)
  duplicate delivery of same RTP identity (see below)
```

```text
lossTimer basis:
  timeSinceLastValidAdmittedMulticastIngress
```

---

## Valid liveness evidence unit (frozen)

```text
One liveness evidence unit =
  one distinct admitted multicast media packet identity per observation:

    (remoteSenderModuleId, Source admission, SSRC, RTP sequenceNumber)

Duplicate deliveries of the same identity (A7 overlap, replay) count at most once
for liveness evidence accumulation.

FORBIDDEN:
  counting the same Source+seq twice from duplicate multicast datagrams
  → replay/duplicate traffic MUST NOT artificially sustain ALIVE
```

R3 observes **multicast ingress path only** (R1). Unicast fallback packets do not
earn or refresh multicast optimization liveness.

---

## State transition structure (frozen)

```text
initial → SUSPECT

SUSPECT
  -- sufficient continuous valid ingress evidence --> ALIVE
  -- prolonged no valid ingress -----------------> DEAD

ALIVE
  -- short evidence gap (no valid ingress) --------> SUSPECT

DEAD
  -- sufficient recovery evidence -----------------> ALIVE
     (direct DEAD → ALIVE allowed; need not publish intermediate SUSPECT)
```

```text
DEAD → ALIVE:
  MAY occur directly when recovery hysteresis satisfied.
  Implementation MAY pass through SUSPECT internally; contract MUST NOT require
  an extra SUSPECT publication delay beyond recovery criteria.
```

---

## Earned ALIVE — dual condition (frozen)

`ALIVE` requires **both** span and count within a rolling observation window.

### Observation window variables

```text
firstValidIngressTime   earliest valid evidence unit in current earn attempt
lastValidIngressTime    latest valid evidence unit in current earn attempt
validPacketCount        distinct valid evidence units in window
```

### Earn condition (SUSPECT → ALIVE and DEAD → ALIVE recovery)

```text
(lastValidIngressTime - firstValidIngressTime) >= AliveEvidenceWindowMs
AND
validPacketCount >= AliveMinValidPackets
```

```text
Recovery uses parallel structure with RecoveryEvidenceWindowMs and
RecoveryMinValidPackets (may equal earn constants in V1).
```

**Anti-burst rule (T4 rejected):** 40 packets arriving inside 100ms without
required span MUST NOT earn ALIVE.

At nominal 50 pps, `AliveEvidenceWindowMs = 1000` expects ~50 slots; requiring
`AliveMinValidPackets = 40` means "sustained receive," not accidental burst.

---

## Loss timers (frozen semantics)

From `ALIVE`:

```text
if timeSinceLastValidAdmittedMulticastIngress >= SuspectAfterNoIngressMs
  → SUSPECT
```

From `SUSPECT` or `ALIVE` (continued absence):

```text
if timeSinceLastValidAdmittedMulticastIngress >= DeadAfterNoIngressMs
  → DEAD
```

```text
Isolated missing packet while ALIVE:
  does not immediately DEAD (R2)
  starts/extends gap timer toward SUSPECT per SuspectAfterNoIngressMs
```

**T3 rejected:** `MaxPlayoutDelayMs = 120` is playout deadline (Profile 03 jitter),
not transport capability lifetime. Liveness timers MUST be strictly greater.

---

## V1 initial constants (frozen as amendment baseline)

```text
AliveEvidenceWindowMs       = 1000
AliveMinValidPackets        = 40

SuspectAfterNoIngressMs     = 300
DeadAfterNoIngressMs        = 1000

RecoveryEvidenceWindowMs    = 1000
RecoveryMinValidPackets     = 40
```

### Parameter inequality constraints (frozen)

```text
MaxPlayoutDelayMs (120)     < SuspectAfterNoIngressMs (300)
SuspectAfterNoIngressMs     < DeadAfterNoIngressMs (1000)

AliveEvidenceWindowMs       >= DeadAfterNoIngressMs
RecoveryEvidenceWindowMs    >= AliveEvidenceWindowMs
```

```text
At 50 pps nominal:
  300 ms ≈ 15 media slots — separates jitter/burst from ingress loss onset
  1000 ms ≈ 50 slots — sustained absence → DEAD
  1000 ms / 40 packets — earned/recovery requires ~80% delivery over full span
```

### Amendment path

```text
V1 numerics MAY be tuned via Profile 03 parameter amendment after field evidence.
R1/R2 authority semantics and T1 structure MUST NOT require reopening for numeric tune.
```

---

## State × timer reference table

| From | Condition | To |
|------|-----------|-----|
| initial | — | SUSPECT |
| SUSPECT | earn dual condition | ALIVE |
| SUSPECT | no ingress ≥ DeadAfterNoIngressMs | DEAD |
| ALIVE | no ingress ≥ SuspectAfterNoIngressMs | SUSPECT |
| ALIVE | no ingress ≥ DeadAfterNoIngressMs | DEAD |
| DEAD | recovery dual condition | ALIVE |

```text
While in SUSPECT, crossing DeadAfterNoIngressMs without earn → DEAD
(ALIVE short gap uses SuspectAfterNoIngressMs first)
```

---

## E3a alignment (motivation, not gate)

```text
E3a.3b establish-then-die (~12s window then 0%):
  earn window (1s/40) prevents single-burst false ALIVE
  DeadAfterNoIngressMs (1s) detects sustained loss without playout-scale flutter
  recovery requires full 1s/40 hysteresis — mitigates brief false recovery

Field may tune constants; structure remains T1.
```

---

## Explicit non-decisions in R3

```text
NOT in R3:
  publication freshness / epoch (R4)
  sender downgrade on SUSPECT vs DEAD timing (R5/R6)
  TransitionOverlapMaxMs (R8)
  observation scope reset triggers (R9)
  network-change invalidation (R9)
```

---

## Profile 03 amendment text (normative insert)

```text
P03-R3 — Liveness evidence and timers (T1):

Evidence clock: admitted multicast ingress only; distinct RTP identity per unit;
duplicates do not stack.

Earn/recovery ALIVE: span >= window AND count >= min packets.

V1 constants:
  AliveEvidenceWindowMs=1000, AliveMinValidPackets=40
  SuspectAfterNoIngressMs=300, DeadAfterNoIngressMs=1000
  RecoveryEvidenceWindowMs=1000, RecoveryMinValidPackets=40

Inequalities: 120 < 300 < 1000; earn window >= dead timeout.

DEAD → ALIVE direct recovery permitted when recovery hysteresis met.

T2/T3/T4 rejected.
```

---

## Consequences

```text
R4 may attach freshness identity to published state snapshots
R5 sender: consume DEAD → must UNICAST; SUSPECT → NOT_PROVEN (no MULTICAST upgrade)
R7 upgrade anti-flap aligns with RecoveryEvidenceWindowMs / RecoveryMinValidPackets
R10 acceptance: synthetic 50pps earn, 300ms gap→SUSPECT, 1s gap→DEAD, burst-forge negative
```

---

## References

- `docs/analysis/0058-profile-03-r2-liveness-state-semantics-adjudication.md`
- `docs/analysis/0058-profile-03-r1-liveness-observation-ownership-adjudication.md`
- `docs/analysis/0058-profile-02-a7-mixed-mode-overlap-adjudication.md`
- `docs/analysis/0058-implementation-profile-03-media-runtime.md` (MaxPlayoutDelayMs=120)
- `docs/adr/0060-conference-media-transport-resilience-contract.md`
