# G-RES-3-C2 Amendment — Submission Primitive Generalization (Reading A)

## Status

```text
G-RES-3-C2 Amendment:  ACCEPTED / CLOSED
Date:                  2026-09-04
Parent:                G-RES-3-C2 Model B1 (ACCEPTED 2026-09-03)
Prerequisites:         P02-A8 SEND_NOT_ATTEMPTED amendment (ACCEPTED)
Trigger:               G-RES-3 E3 sendmmsg adoption review (Decision 3)
Scope:                 execution-path wording + observation defs; capacity semantics unchanged
C3:                    FROZEN / NO CHANGE
ADR-0060:              no change (benchmark contract refinement at C2 layer)
Next:                  E3 H1c AUTHORIZED; 3×600s QUALIFYING HOLD pending H1c PASS
```

---

## Summary

C2 Model B1 capacity intent is **9 real UDP datagram submission attempts per logical
slot** — not mandatory **9 independent `send()` syscalls**. E3 `sendmmsg(9)` is one
syscall carrying nine receiver submissions when complete (`R == 9`).

This amendment generalizes syscall-literal wording introduced during E0/E1 probe era to
**selected production submission primitive**, while preserving:

```text
9-leg workload unchanged
protect-once / 50 slots/s / 120B artifact unchanged
harness execution model = production runtime model
Reading A ACCEPT; Reading B REJECT
C3 thresholds unchanged
```

---

## Reading adjudication (frozen)

```text
Reading A — datagram submission attempts (syscall-agnostic):  ACCEPT
Reading B — mandatory 9× independent send() syscalls:         REJECT
```

---

## Amended execution path (replaces syscall-literal path)

**Was:**

```text
for each target: real UDP send() on production sender path
fanoutCompletionDuration = fan-out start → last leg send() return
```

**Now:**

```text
logical slot tick (20ms nominal)
  → one ProtectedDatagramArtifact for active Source
  → enumerate 9 distinct admitted PerReceiverUnicastEndpoint targets
  → for each target: real UDP submission using the selected production
     sender execution model (e.g. sequential send(), sendmmsg batch, …)
  → record per-slot / per-leg observations

Harness execution model MUST equal intended production runtime execution model.

Benchmark MUST NOT use a submission primitive absent from the production fan-out path
(no harness-only batching shortcut).
```

```text
MUST use same key execution seams as runtime fan-out:
  admission / target enumeration
  byte-identical artifact replication
  actual kernel/socket submission path (not stubbed)

FORBIDDEN (unchanged — B4):
  memcpy-only without real kernel submission
  mock send that never hits kernel/socket layer
```

---

## Fan-out completion (amends G3-C2-I6 observation)

```text
fanoutCompletion:
  COMPLETE   — all 9 target datagrams successfully submitted for the slot
  INCOMPLETE — otherwise (partial batch, syscall error, etc.)

fanoutCompletionDuration (perSlotFanoutCompletionUs):
  defined ONLY when fanoutCompletion = COMPLETE
  = wall time from fan-out submission start to point at which all 9 target
    datagrams have been successfully submitted by the execution model

  E3 sendmmsg example (R == 9):
    t0 = immediately before sendmmsg()
    t1 = immediately after sendmmsg() returns
    fanoutCompletionDuration = t1 - t0

batchSyscallDuration:
  optional diagnostic (e.g. t1 - t0 on partial return)
  MUST NOT be scored as fanoutCompletionDuration when fanoutCompletion = INCOMPLETE
  MUST NOT be used as C3 fanoutCompletionP99/Max input when INCOMPLETE

slotSchedulingLateness:
  unchanged — nominal slot boundary → fan-out start
```

C3 frozen thresholds apply only to **COMPLETE** slots:

```text
fanoutCompletionP99Us <= 5000
fanoutCompletionMaxUs  <= 10000
```

---

## Amended raw observations

| Observation | Definition (amended where noted) |
|-------------|----------------------------------|
| `logicalSlotsAttempted` | unchanged |
| `logicalSlotsEmitted` | unchanged |
| `perSlotFanoutCompletionUs` | fan-out start → all 9 submissions complete; **only when COMPLETE** |
| `batchSyscallDurationUs` | **new optional** — syscall wall time; diagnostic when INCOMPLETE |
| `fanoutCompletion` | **new** — `COMPLETE` \| `INCOMPLETE` per slot |
| `slotSchedulingLatenessUs` | unchanged |
| `slotDeadlineMissCount` | unchanged |
| `perLegSendAttemptCount` | legs with `SENT` or `SEND_FAILED` (attempted) per P02-A8 amendment |
| `perLegSendFailureCount` | `SEND_FAILED` legs only |
| `notAttemptedCount` | **new** — `SEND_NOT_ATTEMPTED` legs (P02-A8 amendment) |
| `catchUpEmissionCount` | unchanged (expect 0) |
| `actualDatagramsAttempted` | ≈ `logicalSlotsEmitted × 9` when all slots COMPLETE |

Formal qualifying gates (E3, workload unchanged):

```text
perLegSendAttemptCount == 270,000   (30,000 slots × 9)
notAttemptedCount == 0
sendFailCount == 0
fanoutCompletion = COMPLETE for all scored slots
```

---

## Amended invariants (delta only)

```text
G3-C2-I4 (amended wording):
  Benchmark performs real UDP submission operations for all active legs using the
  selected production sender execution model.

G3-C2-I6 (amended observation):
  Scheduling lateness and fan-out completion duration measured separately.
  Fan-out completion duration is defined only when all leg submissions for the slot
  are complete per the execution model.

G3-C2-I1..I3, I5, I7..I10:
  UNCHANGED
```

---

## Normative contract insert (amended C2 summary)

```text
G-RES-3-C2 — Sender capacity test (Model B1):

1 Source × 9 distinct unicast fallback legs × 50 slots/s × 120B worst-case artifact.
Real UDP submission via selected production execution model; multicast=0;
protect-once replication. Harness model MUST match production runtime model.
Pre-generated artifact permitted; no Opus/AudioRecord in gate.
Warmup / measurement / cooldown phases; observations frozen; thresholds → C3.

Per-leg outcomes per P02-A8 (+ SEND_NOT_ATTEMPTED amendment).
B2–B7 rejected. G3-C2-I1..I10 frozen except I4/I6 wording per this amendment.
```

---

## Explicit non-changes

```text
C3 PASS thresholds:           NO CHANGE
9-leg workload / N=9:         NO CHANGE
Model B1 workload dimensions: NO CHANGE
Multicast exclusion:          NO CHANGE
No catch-up (I8):             NO CHANGE
No receiver ACK (I7):         NO CHANGE
Opus/AudioRecord exclusion:   NO CHANGE
ADR-0060:                     NO CHANGE at this layer
```

---

## ADR-0060 classification (at amendment closeout)

```text
This amendment refines G-RES-3-C2 benchmark execution primitive under existing
ADR-0060 sender-side unicast fan-out capacity track.

ADR-0060 appendix amendment:  NOT REQUIRED
Profile 02 + C2 layer:        SUFFICIENT for E3 CONTRACT-ADOPTED status

Re-classify only if future work changes ADR-0060 architecture commitments
beyond execution-model / evidence refinement.
```

---

## Consequences

```text
E3 SENDMMSG_BATCH admissible for formal capacity evidence (with harness/runtime parity)
benchmarkConfigHash: re-baseline on E3 formal profile
H1c: AUTHORIZED on E3 execution model after this amendment + P02-A8 amendment
3×600s QUALIFYING: HOLD until H1c PASS
Production cutover: NOT AUTHORIZED (hardening + qualifying separate gates)
```

---

## References

- `docs/analysis/0060-gres3-c2-sender-capacity-test-contract-adjudication.md`
- `docs/analysis/0058-profile-02-a8-send-not-attempted-amendment.md`
- `docs/analysis/0060-gres3-e3-sendmmsg-adoption-review.md` (Decisions 3–4)
- `docs/analysis/0060-gres3-c3-pass-metrics-adjudication.md`
- `docs/adr/0060-conference-media-transport-resilience-contract.md` (no change)
