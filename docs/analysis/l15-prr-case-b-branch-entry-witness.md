# Case B Branch-Entry Witness Gate

**Status:** `FROZEN`  
**Frozen:** 2026-08-04 — `FREEZE CASE_B_BRANCH_ENTRY_WITNESS`  
**Parent:** [l15-prr-case-b-evidence-acquisition-v2.md](./l15-prr-case-b-evidence-acquisition-v2.md)  
**Causality:** [l15-prr-case-b-stimulus-causality-review.md](./l15-prr-case-b-stimulus-causality-review.md)  
**Preflight:** [l15-prr-case-b-field-preflight-v1.md](./l15-prr-case-b-field-preflight-v1.md)

**Does not authorize:** Attempt #7 alone  
**Is not:** L.1.5 runtime requirement · product contract clause

---

## 1. Purpose

Separate two questions:

| Question | Owner |
|----------|-------|
| Can we start the experiment today? | [Preflight v1](./l15-prr-case-b-field-preflight-v1.md) |
| Has this stimulus **ever** entered the target state machine? | **Branch-entry witness** |

```text
Formal field attempt MUST NOT be the first proof that stimulus is effective.
```

---

## 2. Anti-pollution rule (frozen)

Witness is **experiment qualification evidence** only.

```text
MUST NOT become:
  L.1.5 runtime requirement
  product contract adaptation to lab harness
```

Forbidden reverse flow:

```text
Field witness requirement  →  product contract forced to match experiment
```

---

## 3. Witness definition

### Scope (all required)

```text
same DUT scope          (M03)
same capture discipline (durable logcat / Talkback:I)
same stimulus family    (S1_LOCAL_QUALIFICATION_REPAIR)
```

### Token threshold

At least one **real** DUT log token:

```text
LOCAL_TRANSPORT_UNQUALIFIED
OR
QUALIFICATION_WAIT
```

(`UNQUALIFIED` in `LINK_QUALIFICATION_STATE_CHANGED` context is acceptable if clearly local-transport scoped.)

### Artifact

```text
WITNESS_ID        = case-b-witness-<yyyyMMdd-HHmmss>
timestamp         = first qualifying token time
logSource         = path to DUT capture file
stimulusFamily    = S1_LOCAL_QUALIFICATION_REPAIR
causalityRow      = M03 qualification owner CONFIRMED
countsAsAttempt   = false
```

Formal attempt metadata MUST reference:

```text
witnessReference=<WITNESS_ID>
```

---

## 4. Relationship to preflight

```text
Preflight     →  "environment exists right now"
Witness       →  "path was observed at least once"
Causality     →  "stimulus theory maps to owners"
```

Witness runs MAY use preflight capture discipline but are **not** formal attempts.  
`PRE_ATTEMPT_ABORT` on witness runs does not burn attempt numbers.

### Session budget (frozen)

```text
Witness sessions allowed: ≤2
```

Witness runs are **not** semi-formal attempts (`witness-1`, `witness-2`, … must not become infinite exploration).  
After 2 sessions, apply Witness Decision Table below and **stop** — do not design S2/S3 until decision is recorded.

### Environment snapshot (record before witness — not a gate)

Before each witness session starts, record context so `WITNESS_REFUTED` is not ambiguous:

```text
WITNESS_ENVIRONMENT_SNAPSHOT.txt

Topology:
  node count · anchor · membershipEpoch / members=

Network:
  DUT interface state (WiFi on/off · connected SSID if visible)

Software:
  build / version · protocol revision if logged

Observer:
  collector PID · pre-start byte count
```

Purpose: distinguish **stimulus does not trigger qualification** from **environment was not the target scenario**.

Verdict tokens remain `WITNESS_CONFIRMED` | `WITNESS_REFUTED` | `INVALID_WITNESS` — **not** PASS/FAIL (experiment premise, not product behavior).

---

## 5. Witness Decision Table (frozen)

| Case | DUT tokens observed | Verdict | Meaning | Next |
|------|---------------------|---------|---------|------|
| **A** | `LOCAL_TRANSPORT_UNQUALIFIED` or `QUALIFICATION_WAIT` | `WITNESS_CONFIRMED` | Stimulus reached qualification owner | Causality row CONFIRMED → eligible for #7 **review** (still needs Preflight + explicit authorize) |
| **B** | `NETWORK_LOST` · `PEER_EDGE_*` · `BIDIRECTIONAL_READY` but **no** qual/wait tokens | `WITNESS_REFUTED` | S1 does not target qualification owner — **not** L.1.5 failure | Return to stimulus review; **no #7** |
| **C** | Observer loss / incomplete capture | `INVALID_WITNESS` | Acquisition failure | Fix harness; does **not** enter product judgment |

```text
Formal attempt failure ≠ product failure

May be:
  A) stimulus never entered target state machine
  B) acquisition lost data
  C) product actually failed

Witness eliminates (A) before formal #7.
```

---

## 6. Four-layer authorization gate (Attempt #7)

```text
┌─────────────────────────┐
│ 1. Preflight PASS        │  environment exists
├─────────────────────────┤
│ 2. Stimulus Causality PASS│  fact table: M03 qual row CONFIRMED
├─────────────────────────┤
│ 3. Branch Witness EXISTS │  WITNESS_ID + token in capture
├─────────────────────────┤
│ 4. Explicit Authorization│  AUTHORIZE CASE B-1 ATTEMPT #7
└─────────────────────────┘
              ↓
        Formal Attempt #7
        (first product-evidence-eligible run)
```

---

## 7. Freeze checklist

| # | Check | ☐ |
|---|-------|---|
| W1 | Witness ≠ preflight | ☑ |
| W2 | Witness ≠ L.1.5 product requirement | ☑ |
| W3 | Token threshold frozen | ☑ |
| W4 | `witnessReference` on formal attempt | ☑ |
| W5 | Witness run does not count as attempt | ☑ |
| W6 | ≤2 witness sessions; Decision Table exit | ☑ |
| W7 | Witness not semi-formal attempt numbering | ☑ |