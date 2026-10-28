# Case B Stimulus Causality Review

**Status:** `OPEN` — analysis / evidence qualification  
**Opened:** 2026-08-04  
**Parent:** [l15-prr-case-b-evidence-acquisition-v2.md](./l15-prr-case-b-evidence-acquisition-v2.md)  
**Stimulus:** [l15-prr-case-b-stimulus-review.md](./l15-prr-case-b-stimulus-review.md)  
**Board:** [l15-prr-baseline-status.md](./l15-prr-baseline-status.md)

---

## Top principle (frozen)

> **Phase B is NOT "verify L.1.5".**  
> **Phase B verifies whether the stimulus can enter the state machine that precedes L.1.5.**

```text
NOT product validation
NOT L.1.5 contract change
NOT timeout / fallback design review
```

---

## 1. Purpose

Answer one question before any formal field verdict:

> Does `S1_LOCAL_QUALIFICATION_REPAIR` possess causal power to move the DUT into the qualification branch that L.1.5 observes?

```text
S1 causal validity = OPEN   (not rejected, not confirmed)
```

Attempts #1–#6 closed stimulus discovery; this review owns the fact table exit.

---

## 2. Stimulus Fact Table (normative)

| Stimulus | Observable Fact | Owner | Evidence Token | Status |
|----------|-----------------|-------|----------------|--------|
| M02 WiFi OFF | remote unreachable | network / peer edge | `NETWORK_LOST` · `PEER_EDGE_*` | **CONFIRMED** (#4, #6 peer flap) |
| M03 qualification transition | local qualification degraded | `LinkQualificationTracker` | `LOCAL_TRANSPORT_UNQUALIFIED` · `QUALIFICATION_WAIT` | **REFUTED** under S1 peer-WiFi-OFF (witness session 1) |
| qualification timeout | repair admission trigger | L.1.5 admission path | `QUALIFICATION_TIMEOUT` | **OPEN** |
| repair admission | repair attempt created | `QualificationRepairCoordinator` | `LINK_QUALIFICATION_REPAIR_*` | **OPEN** |
| PRR start | epoch reannounce path | PRR | `PRR_EPISODE_*` | **OPEN** |

### Invariant (frozen)

```text
peer reachability loss  ≠  local qualification state loss
```

`PEER_EDGE_NOT_READY` MUST NOT be substituted for `LOCAL_TRANSPORT_UNQUALIFIED` (#4).

---

## 3. Exit condition

```text
M03 qualification owner row:
  OPEN  →  CONFIRMED  |  REFUTED
```

| Exit | Meaning | Next |
|------|---------|------|
| **CONFIRMED** | witness tokens observed on DUT under S1 family | [Witness Decision Table](./l15-prr-case-b-branch-entry-witness.md#5-witness-decision-table-frozen) Case A → #7 gate review |
| **REFUTED** | S1 cannot move DUT qualification owner | [Witness Decision Table](./l15-prr-case-b-branch-entry-witness.md#5-witness-decision-table-frozen) Case B → stimulus review; **no #7** |

### Timebox (anti infinite-analysis)

```text
Deliverable = fact table row resolved + ≤2 witness sessions (see Witness Decision Table)
Does NOT expand into: timeout tuning · fallback design · product knife · S2/S3 until witness verdict
```

---

## 4. Implicit chain under test (S1)

```text
M02 WiFi OFF
    → remote unreachable          [CONFIRMED at peer / edge]
    → M03 local qualification loss [OPEN — key row]
    → QUALIFICATION_WAIT / TIMEOUT   [OPEN]
    → L.1.5 repair admission         [OPEN — population 0]
    → PRR epoch                      [OPEN — population 0]
```

**Do not** conclude `S1 rejected` until M03 qualification row is **REFUTED**.  
**Do not** conclude `S1 confirmed` until row is **CONFIRMED** + branch witness exists.

---

## 5. Out of scope

- Why the design is shaped this way
- Whether timeout should change
- Whether to add fallback / bypass
- UVCP / completion / mesh claims

Those belong to separate product review, not this evidence-qualification artifact.

---

## 6. References (closed attempts)

| Attempt | Relevant finding |
|---------|------------------|
| #2/#3 | Branch A: DUT `NETWORK_LOST` → fast `BIDIRECTIONAL_READY` bypasses qualification |
| #4 | `PEER_EDGE_NOT_READY ≠ LOCAL_TRANSPORT_UNQUALIFIED` |
| #5 | S1 topology precondition fail (M01 in mesh) |
| #6 | Peer flap OK; DUT observer gap — cannot observe M03 qual row |

---

## 7. Checklist

| # | Check | ☐ |
|---|-------|---|
| C1 | Top principle: Phase B ≠ L.1.5 verification | ☑ |
| C2 | Fact table with Owner + Token + Status | ☑ |
| C3 | M03 qualification row exit = CONFIRMED \| REFUTED | ☑ session1 REFUTED |
| C4 | S1 causal validity remains OPEN until C3 | → trending REFUTED (budget 1 left) |
| C5 | No product code change in this review | ☑ |

---

## 8. Witness session results

| Session | ID | Verdict | Note |
|---------|----|---------|------|
| 1 | `case-b-witness-1-20260804-213048` | `WITNESS_REFUTED` | Peer flap → `PEER_EDGE_NOT_READY` / `peer_edge_stale`; no `UNQUALIFIED` / `QUALIFICATION_WAIT` |
| 2 | — | pending (budget 1) | optional; or close S1 as REFUTED |

```text
NOT L.1.5 failure
S1 WiFi-peer-OFF does not move DUT local qualification owner
```