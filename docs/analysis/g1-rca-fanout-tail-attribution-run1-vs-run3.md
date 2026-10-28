# G1-RCA — FANOUT_MAX / Egress Tail Attribution (Run 1 post-G2 vs Historical Run 3)

## Status

```text
G1              CLOSED / FIELD-PROVEN (3× VALID PASS post G1-M1)
G1-RCA          CLOSED (H1c sufficient)
G1-MITIGATION   G1-M1 VERIFIED — native cached mmsghdr
Focus           UDP egress fanout tail latency (SENDMMSG_BATCH) — CLOSED
Date            2026-09-14
Attribution     COMPLETE (H1c lightweight evidence)
H1d             CANCELLED — attribution sufficient; skip per-leg diagnostic repro
Set evidence    logs/ga-g1-gres3-qualifying-set-g1m1-20260914-3x
Next            G3 stability (do not reopen G2 / RCA4 / RCA5)
```

## Evidence runs

| Run | logDir | build | fanoutMax | verdict |
|-----|--------|-------|-----------|---------|
| **G1 Run 1** (post-G2 baseline) | `logs/ga-g1-gres3-qualifying-set-20260914-151738/run-01` | `faf8884` | **11.766 ms** | VALID FAIL |
| Historical Run 3 | `logs/gres3-qualifying-3-20260904-105431` | pre-G2 | **14.201 ms** | VALID FAIL |

Both: `FANOUT_MAX_EXCEEDED` · C3 Tier-1 FAIL · mechanical completeness PASS · `legsFailed=0` on worst slot.

---

## Instrumentation limits (frozen)

H1c/QUALIFYING `slot-metrics.csv` columns:

```text
slotIndex, schedLatenessNs, fanoutDurationNs, legsFailed
```

**Not captured in qualifying path:** per-leg durations, batch-build vs syscall split, tail-events.csv, CPU vs wall inside fanout.

Run-level diagnostics (summary.json `sendmmsgProbe`):

```text
worstSyscallWallNs, minReturnedMessages=9, partialBatchCount=0
```

---

## Q1 — Where does worst-slot 9-leg fanout time go?

### Measurement model

```text
schedLatenessNs  = fanoutStartNs - nominalSlotStartNs   (pacing thread delay BEFORE sendFanOut)
fanoutDurationNs = fanoutEndNs - fanoutStartNs          (entire sendFanOut() wall, incl. sendmmsg batch)
```

### G1 Run 1 worst slot **1255**

| Phase | Estimate | Source |
|-------|----------|--------|
| Scheduler / preemption (pre-fanout) | **2.26 ms** | `schedLatenessNs` @ slot 1255 |
| **sendFanOut wall (fanoutDuration)** | **11.77 ms** | C3 MAX scorer |
| → sendmmsg syscall (run-level probe) | **~9.39 ms** | `worstSyscallWallNs` |
| → userspace gap (batch build + JNI/wrapper) | **~2.38 ms** | fanoutMax − worstSyscallWall |
| Per-leg loop (isolated) | *not measured* | H1c qualifying |
| Allocation / GC | *not supported* | no GC markers; singleton spike |
| Socket/kernel backpressure | **inferred primary** | syscall wall ≈ fanout; `eagainCount=0` |

**Adjacent cluster (slots 1252–1253, same tail region):**

```text
slot 1252: sched 2.91 ms, fanout 1.23 ms
slot 1253: sched 9.13 ms, fanout 2.68 ms   ← global schedLatenessMax
slot 1254: sched 0.31 ms, fanout 0.24 ms
slot 1255: sched 2.26 ms, fanout 11.77 ms ← global fanoutMax
```

Slot 1253 shows **pacing-thread deschedule without fanout spike** — scheduler pressure in the same region, separate from the MAX fanout slot.

### Historical Run 3 worst slot **7778**

| Phase | Value |
|-------|-------|
| schedLateness @ fail slot | **2.6 µs** (negligible) |
| fanoutDuration | **14.20 ms** |
| worstSyscallWallNs | **13.63 ms** |
| userspace gap | **~0.57 ms** |

### Q1 conclusion

```text
PRIMARY (both runs):  sendmmsg / sendFanOut syscall-dominated wall time
                      (fanoutDuration ≈ worstSyscallWallNs at run level)

SECONDARY (Run 1 only):  scheduler preemption cluster @ slots 1252–1253
                      preceding the MAX fanout slot

NOT primary:          multi-leg accumulation (legsFailed=0 everywhere)
                      batch incompleteness (partialBatch=0, minReturned=9)
                      sustained GC/thermal (singleton isolated spikes)
```

---

## Q2 — What is 11.77 ms?

| Hypothesis | Run 1 @ slot 1255 | Verdict |
|------------|-------------------|---------|
| Single abnormal leg stretches slot | Cannot confirm (no per-leg in H1c) | **Unlikely** — sendmmsg batch; legsFailed=0 |
| Multiple legs accumulate | — | **Rejected** — batch syscall model |
| **Syscall overall block** | fanout 11.77 ms; syscall probe 9.39 ms | **PRIMARY** |
| Userspace descheduled during fanout | gap 2.38 ms | **Possible contributor** |
| Pacing descheduled before fanout | sched 2.26 ms at 1255; **9.13 ms @ 1253** | **Contributing** (compound tail region) |

```text
11.77 ms is NOT explained by sched alone at the scoring slot.
C3 MAX = fanoutDurationNs → syscall-dominated sendFanOut stall.
Run 1 additionally shows scheduler pressure in the 2 slots before worst fanout.
```

Post-spike artifact:

```text
Run 3 slot 7779: sched 2.76 ms, fanout 0.52 ms  (classic downstream-of-syscall-block)
Run 1 slot 1256: sched 0.51 ms, fanout 0.47 ms (mild; region already sched-stressed earlier)
```

---

## Q3 — Same fingerprint as Run 3 (14.20 ms)?

### Shared fingerprint (YES — same failure class)

```text
fanoutP99 healthy (~2.4–2.9 ms) while fanoutMax exceeds 10 ms gate
Isolated worst slot (~10–11× neighbor±50 avg fanout)
legsFailed=0 on worst slot (R=9 complete batch)
worstSyscallWallNs ≈ fanoutMaxNs (syscall-dominated)
Mechanical gates: 30k/30k slots, 270k/270k leg attempts, 0 notAttempted
```

### Differences (Run 1 is not a carbon copy)

| Dimension | G1 Run 1 | Hist Run 3 |
|-----------|----------|------------|
| fanoutMax | 11.77 ms | 14.20 ms |
| sched @ worst fanout slot | **2.26 ms** | **2.6 µs** |
| Pre-worst sched cluster | **9.13 ms @ 1253** | absent @ 7776–7777 |
| fanout − syscall gap | **2.38 ms** | **0.57 ms** |
| Tail slots >5 ms / 30k | 65 | 40 |
| sched >1 ms slots | 21 | 9 |

```text
SAME CLASS:     E3 SENDMMSG_BATCH isolated syscall tail (VALID FAIL)
RUN 1 VARIANT:  milder magnitude + compound sched pressure in tail region
                (not pure syscall-only like Run 3 slot 7778)
```

---

## Top-20 / Top-50 stable patterns

### Top-10 fanout slots

**G1 Run 1:** only slot 1255 >10 ms; slots 5650 (8.95 ms), 3731 (8.42 ms) also >8 ms — all with sched <15 µs except 1255.

**Run 3:** only slot 7778 >10 ms; slot 20142 (8.17 ms) second — sched <3 µs.

### Top-50 composition

```text
                Run 1          Run 3
sched <100µs    46/50          44/50
fanout≥8ms & sched<100µs   2       2
sched>1ms & fanout>5ms     3       0
avg top-50 fanout          6.34 ms  5.82 ms
```

**Stable pattern:** tail is **sparse syscall spikes** on otherwise healthy P99, not broad elevation.

---

## Tail class adjudication (H1c evidence)

| Class | Run 3 | G1 Run 1 |
|-------|-------|----------|
| A. scheduler/deschedule (primary @ MAX) | **REJECTED** | **REJECTED @ slot 1255**; **present in region @ 1252–1253** |
| **B. sendmmsg syscall/kernel** | **PRIMARY** | **PRIMARY** |
| C. thermal / GC | NOT SUPPORTED | NOT SUPPORTED |
| D. harness anomaly | REJECTED | REJECTED |
| E. multi-leg accumulation | REJECTED | REJECTED |

---

## G2 orthogonality

```text
G-RES-3 path:     UDP capacity harness (sendmmsg_batch, 9 sink fanout)
G2 path:          Meeting membership / session / SOURCE_DECLARATION

G1 Run 1 FAIL is NOT explained by G2 fixes.
G2 fixes do NOT eliminate syscall tail — post-G2 repro confirms same failure class.
```

---

## Disposition

```text
G1-RCA attribution:     CLOSED (H1c-level)
G1 scale defect:          CONFIRMED — tail instability, not threshold fantasy
                          (historical PASS @ 7.06 ms proves gate reachable)
H1d diagnostic repro:   CANCELLED — evidence sufficient for mitigation entry

DO NOT:
  relax C3 10 ms threshold
  reopen G2 / RCA4 / RCA5
  run G1 Run 2/3 before mitigation field verify

AUTHORIZED NEXT:
  G1-M1 — native cached mmsghdr at open (eliminate per-slot JNI address rebuild)
  → qualifying Run 1 as focused verify (PASS → Run 2/3; FAIL → E3b probe or deeper RCA)
```

## Evidence paths

- G1 Run 1: `logs/ga-g1-gres3-qualifying-set-20260914-151738/run-01/bundle/`
- Run 3: `logs/gres3-qualifying-3-20260904-105431/bundle/` + `TAIL-ATTRIBUTION.md`
- Prior Run 3 attribution: `logs/gres3-qualifying-3-20260904-105431/TAIL-ATTRIBUTION.md`
