# G-RES-4-P3 — Cadence and PublicationFreshnessTimeout (Adjudication)

## Status

```text
G-RES-4-P3: ACCEPTED — MODEL F1
Date:       2026-09-03
Parent:     0060-gres4-publication-transport-contract-outline.md
Prerequisites: G-RES-4-P1/P2 ACCEPTED
        Profile 03 R3 (observation timers)
        Profile 03 R4 (freshness semantics — numerics deferred here)
Scope:  PublicationCadenceMs, PublicationFreshnessTimeoutMs V1 numerics;
        periodic + event-driven publication; freshness clock rules
NOT IN SCOPE: snapshot / late join grace (P4)
              loss/reorder wire handling (P5)
              fence transport (P6)
Next:       G-RES-4-P5 (loss / reorder / duplicate / gap semantics)
```

---

## Question

What are the V1 **publication cadence** and **`PublicationFreshnessTimeout`**
numeric values, and how do they interact with R3 observation timers?

P3 freezes **transport-layer timing numerics** only. It does not reopen R4
ordering semantics or R5 mode FSM.

---

## Two timer domains (frozen — MUST NOT conflate)

```text
R3 timers (receiver observation):
  SuspectAfterNoIngressMs = 300
  DeadAfterNoIngressMs    = 1000
  Alive earn/recovery     = 1000ms + 40 distinct

  Question: "Is multicast ingress still alive at this receiver?"

G-RES-4-P3 timers (sender knowledge):
  PublicationCadenceMs         = 250
  PublicationFreshnessTimeoutMs = 1000

  Question: "Can sender still trust last accepted publication for this receiver?"
```

```text
FORBIDDEN (F5):
  PublicationFreshnessTimeoutMs = R3 SuspectAfterNoIngressMs (300)
  single timer governing both observation and knowledge
```

These clocks are intentionally separate (R4 principle; now numerically closed).

---

## Options adjudicated

| Model | Description | Verdict |
|-------|-------------|---------|
| **F1** | 250ms periodic + state-change immediate publish + 1000ms freshness | **ACCEPT** |
| **F2** | Periodic refresh only | **REJECT** (slow downgrade on transition) |
| **F3** | State-change publish only | **REJECT** (static ALIVE can stale forever) |
| **F4** | Rejected/duplicate facts refresh freshness | **REJECT** (proof laundering) |
| **F5** | Freshness timeout = R3 300ms | **REJECT** (conflates domains) |

---

## V1 constants (frozen)

```text
PublicationCadenceMs          = 250
PublicationFreshnessTimeoutMs = 1000
```

```text
Nominal publication rate per receiver: 4 Hz (every 250ms)
Freshness window: 1000ms from last R4-accepted fact
≈ 4 publication opportunities per freshness window under nominal cadence
```

Future tuning MAY amend via G-RES-4 parameter amendment without reopening
P2 transport properties or R4 ordering rules.

---

## Publication mechanisms (frozen — dual)

### 1. Periodic refresh

```text
Each publishing receiver MUST re-publish its current livenessState
for the active conferenceMediaIncarnation at least every PublicationCadenceMs (250).

Applies regardless of state: ALIVE, SUSPECT, or DEAD.
```

```text
receiver timeline (example, state unchanged ALIVE):
  t=0     publish ALIVE
  t=250   publish ALIVE
  t=500   publish ALIVE
  t=750   publish ALIVE
  ...
```

### 2. Event-driven immediate publish

```text
On any livenessState transition (R3), receiver MUST publish without waiting
for the next periodic cadence tick:

  ALIVE → SUSPECT       immediate publish
  SUSPECT → DEAD        immediate publish
  SUSPECT → ALIVE       immediate publish
  DEAD → ALIVE          immediate publish
  (any material R3-driven state change)
```

```text
"immediate" means:
  MUST NOT wait for next 250ms periodic slot

"immediate" does NOT mean:
  physical 0ms delivery guarantee
  bypass of authentication or R4 acceptance at sender
```

Aligns with R6 "immediate" semantics for downgrade activation.

### Why both (F1 rationale)

```text
Periodic-only (F2) worst case:
  ALIVE at t=0
  R3 → SUSPECT at t=300ms
  next periodic at t=500ms
  sender learns ~500ms+ → unnecessary correctness outage

State-change-only (F3):
  ALIVE with no further transitions → no new publications
  sender freshness expires → false STALE while ingress still healthy
```

---

## Every publication advances ordering (frozen)

```text
Each publication attempt MUST emit a new ordered fact:

  observationSequence++   (within current publisherIncarnation)

Even when livenessState is unchanged:

  ALIVE seq=41
  ALIVE seq=42
  ALIVE seq=43
```

```text
FORBIDDEN:
  re-send identical (publisherIncarnation, observationSequence) as "refresh"
  → R4 REJECT_DUPLICATE; does not refresh sender freshness

refresh of unchanged state = new publication fact, not retransmission of old fact
```

Periodic refresh therefore **is** freshness proof, not duplicate noise.

---

## Sender freshness clock (frozen)

```text
Clock starts from last R4-ACCEPTED fact for (receiverModuleId, conferenceMediaIncarnation).

age = now - lastAcceptedPublicationTime

age <  PublicationFreshnessTimeoutMs (1000)  → FRESH
age >= PublicationFreshnessTimeoutMs (1000)  → STALE
```

```text
Boundary frozen as >= (not >) to avoid off-by-one implementation drift.
```

```text
STALE semantics unchanged from R4:
  stale ALIVE ≠ eligibility
  STALE → loss of proof → UNICAST_FALLBACK required (R5)
```

---

## Only accepted facts refresh freshness (frozen — G4-P3-I5)

```text
ONLY an R4-ACCEPTED fact may set:

  lastAcceptedLivenessFact[receiver, incarnation]
  lastAcceptedPublicationTime = receiveTime
```

```text
The following MUST NOT refresh freshness (F4 REJECT):

  wrong conferenceMediaIncarnation     → REJECT_STALE_SCOPE
  old / non-increasing order           → REJECT_STALE_ORDER
  duplicate ordering                   → REJECT_DUPLICATE
  authentication / integrity failure
  malformed message
  invalid publisher identity
```

Otherwise stale or replayed messages could indefinitely sustain ALIVE proof.

```text
Transport-layer send() success at publisher does NOT refresh sender freshness.
Only consumption-seam ACCEPT does (G4-P2-I6 + G4-P3-I5).
```

---

## No startup grace period (frozen — G4-P3-I7)

```text
P3 MUST NOT define StartupFreshnessGraceMs or equivalent.

Sender with no accepted current fact:
  → no freshness proof
  → UNICAST_FALLBACK (R5 initial binding)

Late join / sender restart recovery → G-RES-4-P4 (snapshot).
MUST NOT manufacture multicast eligibility during startup grace.
```

---

## System-level downgrade paths (closed)

Two independent paths to loss of multicast optimization proof:

### Path A — Media liveness failure

```text
multicast ingress lost at receiver
  → ~300ms R3 → SUSPECT (or 1000ms → DEAD)
  → immediate event-driven publication
  → sender accepts new fact → UNICAST_FALLBACK (R6)
```

### Path B — Control publication failure

```text
ingress may still be healthy but publications lost/delayed
  → no new R4-accepted fact
  → age >= 1000ms → STALE
  → UNICAST_FALLBACK (R5 fail-safe)
```

```text
P2 loss tolerance + P3 cadence/TTL jointly bound Path B outage.
Single datagram loss does NOT trigger fallback (≈4 chances per window).
```

### Loss tolerance example

```text
0ms      ALIVE seq=100 accepted
250ms    seq=101 lost
500ms    seq=102 accepted
         → freshness clock reset; remains FRESH
```

---

## Relationship to R4 (frozen)

```text
R4 froze FRESH/STALE semantics and ordering rules.
G-RES-4-P3 freezes PublicationFreshnessTimeoutMs = 1000.

R4 document deferral ("numeric → R5 or transport") is superseded for V1 by P3.
Profile 03 consumption rules unchanged; numerics now authoritative at G-RES-4 layer.
```

---

## Explicit non-decisions in P3

```text
NOT in P3:
  snapshot on late join / sender restart        → P4
  wire duplicate suppression policy             → P5
  incarnation / publisher restart transport     → P6
  jitter / backoff under congestion             → implementation (MUST meet cadence + immediate rules)
  publication suppression when DEAD long-term     → implementation (MUST still meet freshness if state unchanged)
```

---

## Invariants (frozen)

```text
G4-P3-I1  PublicationCadenceMs = 250.

G4-P3-I2  PublicationFreshnessTimeoutMs = 1000.

G4-P3-I3  State transition publishes without waiting for periodic cadence.

G4-P3-I4  Every current-state refresh is a new ordered publication fact
          (observationSequence advances).

G4-P3-I5  Only an R4-accepted fact refreshes sender freshness.

G4-P3-I6  age >= PublicationFreshnessTimeoutMs → STALE.

G4-P3-I7  No startup grace may manufacture multicast eligibility.
```

---

## Normative contract insert

```text
G-RES-4-P3 — Cadence and freshness (Model F1):

PublicationCadenceMs = 250; PublicationFreshnessTimeoutMs = 1000.
Dual publish: periodic 250ms + immediate on livenessState transition.
Each publish advances observationSequence; unchanged state still new fact.
Freshness from last R4-accepted fact only; age >= 1000ms → STALE.
Separate from R3 300/1000ms observation timers.

F2/F3/F4/F5 rejected. G4-P3-I1..I7 frozen.
```

---

## Consequences

```text
P4: snapshot must deliver accepted facts; no P3 grace substitute
P5: duplicate/reorder handling must preserve G4-P3-I4/I5
P7: tests for periodic refresh, immediate transition, TTL boundary, reject-no-refresh
Profile 03 R10 C5 may reference frozen 1000ms TTL in transport integration tests
```

---

## References

- `docs/analysis/0060-gres4-publication-transport-contract-outline.md`
- `docs/analysis/0060-gres4-p2-transport-channel-adjudication.md`
- `docs/analysis/0060-gres4-p1-publication-topology-adjudication.md`
- `docs/analysis/0058-profile-03-r3-liveness-timers-hysteresis-adjudication.md`
- `docs/analysis/0058-profile-03-r4-publication-freshness-adjudication.md`
- `docs/analysis/0058-profile-03-r5-sender-transport-mode-fsm-adjudication.md`
