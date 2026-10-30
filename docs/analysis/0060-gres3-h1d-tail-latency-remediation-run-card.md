# G-RES-3-H1d — Sender Fan-out Tail-Latency Remediation

## Status

```text
Phase:              H1d — tail-latency diagnosis/remediation
Series status:      CLOSED AS INSUFFICIENT (see G-RES-3 E2)
Sub-phase:          H1d.2–H1d.5b all CLOSED
Parent:             G-RES-3-H1 evidence harness
Prerequisites:        H1c PASS / topology ELIGIBLE
C3 gates:             UNCHANGED (P99≤5ms, MAX≤10ms, slot<20ms)
Formal 3-run:         HOLD
Next track:           G-RES-3 E2 sender execution-model adjudication
```

---

## Purpose

Decompose **901ms-class tail stalls** into:

```text
slotSchedulingLateness
  vs fanout loop duration
  vs per-leg send() latency
  vs GC / thread deschedule / system stall
```

H1d is **not** formal capacity evidence. It adds diagnostics and minimal
hot-path fixes only when evidence points to implementation issues.

---

## Allowed scope

| In scope | Out of scope |
|----------|--------------|
| Per-leg send duration + offending leg index | Change C3 5/10/20ms gates |
| Tail event capture + stall context snapshot | Skip to 3×QUALIFYING |
| Hot-path alloc / setData / I/O audit | Contract amendment |
| Minimal sender seam fix if proven | |

---

## Diagnostics added (H1d)

```text
slot-metrics.csv:
  + maxLegIndex, maxLegDurationNs per emitted slot

tail-events.csv (threshold-triggered, max 128):
  schedLatenessNs, fanoutDurationNs, per-leg durations
  threadPriority, heap, native heap, uptimeMs at stall

h1d-adjudication.json:
  readiness, attribution summary, unexplained extreme tail flag
```

### Tail capture thresholds (diagnostics only)

```text
fanout ≥ 10ms
sched lateness ≥ 5ms
slot execution ≥ 15ms
slot deadline miss
extreme stall ≥ 100ms
```

### Hot-path fix (H1d.1)

```text
FanOutDatagramSender:
  shared sendBuffer + System.arraycopy (no per-leg setData)
  per-leg nanoTime around socket.send() (H1d phase only)
```

### Scheduling remediation (H1d.2)

```text
Root cause found:
  DEFAULT_PACING_THREAD_PRIORITY was Thread.MAX_PRIORITY-2 (=8) passed to
  Process.setThreadPriority → Linux nice 8 (LOW), not elevated scheduling.

Fix:
  Process.THREAD_PRIORITY_URGENT_DISPLAY (-8) via Gres3PacingThreadBootstrap
  verify configured vs observed priority; record tid/name in manifest/summary
  remove Java Thread.setPriority boost (Process priority is authoritative)
  AbsoluteSlotScheduler: LockSupport.parkNanos instead of Thread.sleep

Sched-tail attribution (H1d only):
  sched-tail-events.csv with thread/GC/thermal/lifecycle/screen context
  Gres3SchedTailAttributor hints:
    APP_RUNTIME_CONTENTION | ANDROID_SCHEDULER_JITTER |
    HARNESS_INSTRUMENTATION_OVERHEAD | THERMAL_THROTTLE |
    LIFECYCLE_NOT_FOREGROUND | SCREEN_POWER_STATE
  h1d-adjudication: recurrentSchedulingDominantTail, schedAttributionHints

Qualification path hygiene:
  tail capture + per-leg timing + h1d adjudication → H1d phase ONLY
  H1c/qualifying: lightweight slot-metrics (no leg columns, no tail capture)
```

---

## Engineering gate before 3×QUALIFYING

```text
H1c topology = ELIGIBLE
AND fanout P99 < 5ms
AND fanout MAX < 10ms          ← C3 hard gate; no "explainable" waiver
AND slotDeadlineMiss = 0
AND sendFail = 0
AND no unexplained extreme tail
AND no recurrent scheduling-dominant tail
```

Qualification rerun target after H1d.2:

```text
fanout P99 < 5ms
fanout MAX < 10ms
slotDeadlineMiss = 0
sendFail = 0
schedLatenessMaxNs stable without recurrent sched-dominant tails
```

### Fanout hot-path (H1d.3)

```text
Scope: 9-leg sequential UDP send only — no new diagnostics, C3 unchanged

Changes:
  fixed DatagramPacket length at open (no per-slot packet.length mutation)
  single arraycopy per slot into shared sendBuffer; indexed while-loop send
  FanOutSendOutcomeScratch — preallocated outcome, no data class per slot
  per-leg System.nanoTime only when recordLegTiming=true (H1d mode)
  open() indexed loops; no map/forEach on hot path

Rejected: parallel send, coroutines, multi-socket, sendmmsg, slot period change

Post-H1d.3 engineering gate (before 3×QUALIFYING):
  1× H1d diagnostic verify
  2× consecutive H1c lightweight — all must pass P99<5ms, MAX<10ms, deadlineMiss=0, sendFail=0
```

### Fanout tail stall attribution (H1d.4)

```text
Scope: diagnose only — no sender algorithm change, C3 unchanged

In-app (H1d path only):
  fanoutWallNs + fanoutThreadCpuNs per tail event (Debug.threadCpuTimeNs)
  legWallSumNs + interLegGapNs (wall minus sum of per-leg send durations)
  fanout-tail-attribution.csv + h1d4-attribution.json
  Classes: A deschedule | B single send block | C multi-leg accumulation | D not attributed

External (H1d runs):
  atrace async capture: sched/freq/idle/workq → logs/<runId>/atrace-sched.out

H1c/qualifying: unchanged lightweight path (no wall/cpu per slot)
```

### UDP egress stall remediation (H1d.5)

```text
Scope: socket/kernel egress minimal remediation — see 0060-gres3-h1d5-udp-egress-stall-remediation-run-card.md

Changes:
  SO_SNDBUF explicit 256 KiB + effective value in manifest
  single unconnected socket; addresses at open only
  egress warm-up (9-leg) after time warmup, before measurement
  udpEgressSocket observability in manifest/summary (not H1c hot path)

Rejected: multi-socket, parallel send, NIO/sendmmsg, C3 change
```

---

## Execution

```powershell
# H1d diagnostic run (same topology as H1c, harnessPhase=H1d)
.\scripts\adr0060-gres3-h1c-qualification-run.ps1 -Sender M03 -HarnessPhase H1d

# After H1d.2 remediation → H1c rerun (lightweight path, no tail capture)
.\scripts\adr0060-gres3-h1c-qualification-run.ps1 -Sender M03 -HarnessPhase H1c
```

---

## Flow

```text
H1c PASS (topology ELIGIBLE, C3 projection LIKELY_FAIL)
  → H1d diagnostic run + analyze tail-events.csv / h1d-adjudication.json
  → H1d.1 buffer remediation + H1d.2 scheduling remediation
  → H1d verify run (optional) → H1c rerun on lightweight path
  → only then 3×QUALIFYING @ N=9
```
