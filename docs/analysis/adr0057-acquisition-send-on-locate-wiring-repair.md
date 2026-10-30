# ADR-0057 Track C — Acquisition Send-on-Locate Wiring Repair

## Status

**IMPLEMENTED / PASS** (2026-08-31)

```text
ADR-0057 Track C

Acquisition Send-on-Locate Wiring Repair:
  IMPLEMENTED / PASS

ACQ-IA-T1:
  SATISFIED

ACQ-EG1..EG5:
  PASS

GBC Core:
  PASS / FROZEN

PW / PTI / PAP / OE:
  PASS / FROZEN

Track C Field:
  OPEN (F3: cold-start origin gap)

ADR-0057 COMPLETE:
  NO
```

**Field conformance (2026-08-31):**

```text
F2 / M04 isolation:              PASS (run 153654)
ACQ send-on-locate repair:       FIELD CONFORMANCE PASS
Track C Finding F3:              CONFIRMED (cold-start origin gap)
```

**Parents:**

- [Track C Case A seam audit](../analysis/adr0057-track-c-case-a-requestfact-fact-request-seam-audit.md)
- [Operational Establishment](./0057-operational-establishment-narrow-authorization.md) — **CLOSED / PASS**
- [Production Verifier Wiring](./0057-production-verifier-wiring-narrow-authorization.md) — **CLOSED / PASS**

**Does NOT authorize:**

- New acquisition protocol or GBC semantic cut
- Timer retry / exponential backoff / retry budget
- Global acquisition scheduler redesign
- Holder scoring redesign
- M04 discovery isolation (F2 — separate field precondition track)
- PV / PTI / PAP / OE reopen
- ADR-0057 COMPLETE

---

## Frozen root cause

```text
GBC:
  AcquisitionObligation = OPEN
  RequestFact effect = EMITTED

Production orchestration:
  RequestFact arrives while holders = {}
  → no wire attempt (silent no-op)

Later:
  HELLO locates holder
  → holder becomes available
  → no acquisition scheduling edge is generated

Result:
  OPEN obligation
  + available holder
  + zero FACT_REQUEST attempts
```

**Principle:** Acquisition intent survived semantically, but was lost operationally.

---

## ACQ-IA-T1 — Obligation / Attempt Separation

An OPEN `AcquisitionObligation` **MAY** produce multiple acquisition opportunities over its
lifetime.

Idempotence **MUST** prevent duplicate handling of the **same scheduling opportunity**.

It **MUST NOT** permanently suppress future attempts solely because the same obligation
previously produced `RequestFact` while no holder was available.

```text
Obligation OPEN
  ├── opportunity #1 → no holder → DEFERRED_NO_HOLDER
  ├── HELLO locates holder
  ├── opportunity #2 → FACT_REQUEST sent
  └── accepted authoritative evidence → CLOSED
```

---

## Authorized repair scope

| # | Repair | Requirement |
|---|--------|-------------|
| 1 | **send-on-locate** | When a newly located eligible holder becomes available and obligation is OPEN, acquisition scheduler MUST obtain another wire opportunity. HELLO is **not** authority — GBC obligation is. |
| 2 | **empty-holder guard** | `RequestFact` + `selectedHolders = {}` → `DEFERRED_NO_HOLDER` diagnostic; obligation remains OPEN; zero wire attempt. |
| 3 | **stale log cleanup** | Remove `"production crypto verifier NOT AUTHORIZED"` from RequestFact path. |
| 4 | **attempt idempotence** | Track wire attempts per `(channelId, correlation, holder)` — not per OPEN obligation lifetime. |
| 5 | **regression fixtures** | ACQ-EG1..5 (below) |

### send-on-locate flow (frozen)

```text
HELLO
  → locate / update holder availability
  → observe OPEN acquisition obligation
  → schedule acquisition opportunity
  → normal holder selection
  → FACT_REQUEST
```

**Not authorized:** every HELLO → send FACT_REQUEST.

### empty-holder guard (frozen)

```text
RequestFact + selectedHolders = {}
  → DEFERRED_NO_HOLDER
  → explicit diagnostic
  → zero wire attempt
  → AcquisitionObligation remains OPEN
```

**Forbidden:**

- no holder → obligation CLOSED
- no holder → retry exhausted
- no holder → originate successor
- no holder → treat as convergence progress

---

## Implementation seams

| Component | Change |
|-----------|--------|
| `AcquisitionWirePlan` | New sealed plan: `Send`, `DeferredNoHolder`, `ObligationClosed`, `NoNewHolderLocate` |
| `FactDeliveryOrchestrator` | `planWireAttemptForRequestFact`, `planWireAttemptAfterHelloLocate`, `markHoldersWireAttempted` |
| `TalkbackCoordinator` | RequestFact → plan + DEFERRED log; HELLO → send-on-locate; mark attempted after send |
| Tests | `Adr0057AcquisitionSendOnLocateFixturesTest` |

---

## Minimum fixtures (ACQ-EG1..5)

```text
ACQ-EG1
  RequestFact while holders={}
  → no send
  → DEFERRED_NO_HOLDER
  → obligation remains OPEN

ACQ-EG2
  later HELLO locates eligible holder
  → new acquisition opportunity
  → exactly one FACT_REQUEST planned (Send plan)

ACQ-EG3
  duplicate HELLO for same holder
  → no uncontrolled duplicate burst

ACQ-EG4
  obligation already CLOSED
  + later HELLO
  → no FACT_REQUEST

ACQ-EG5
  existing A–E / V1–V3 / wire / PW / ADR-0057 regression unchanged
```

---

## Field re-run prerequisites (F2 — separate from this cut)

Hard gate before Case A:

```text
M04 force-stop
→ PRECONDITIONS
→ M01/M02/M03 observed participant set MUST NOT contain M04
```

Soft (allowed):

```text
M01 sees M01 only, or partial M01/M02/M03 discovery
```

Cold-start discovery convergence is Case A itself — do **not** require full
`{M01,M02,M03}` mutual observation before start.

Hard fail:

```text
Any device observes M04
→ PRECONDITION FAIL
→ DO NOT START CASE A
```

---

## Sign-off

```text
☑ Root cause frozen (field seam audit)
☑ ACQ-IA-T1 SATISFIED
☑ ACQ-EG1..EG5 PASS
☑ Acquisition Send-on-Locate Wiring Repair: IMPLEMENTED / PASS
☑ Field conformance PASS (logs/adr0057-track-c-case-a-20260831-153654)
□ Track C Case A overall PASS (blocked by F3)
```
