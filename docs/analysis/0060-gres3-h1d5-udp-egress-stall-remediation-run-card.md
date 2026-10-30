# G-RES-3-H1d.5 — UDP Egress Stall Remediation

## Status

```text
Phase:              H1d.5 — UDP egress stall remediation
Series status:      CLOSED AS INSUFFICIENT
Sub-phase:          H1d.5a COMPLETE · H1d.5b COMPLETE
Parent:             H1d.4 attribution (single send() non-CPU wall stall)
Prerequisites:        H1d.4 COMPLETE
C3 gates:             UNCHANGED (P99≤5ms, MAX≤10ms, slot<20ms)
Formal 3-run:         HOLD
Next track:           G-RES-3 E2 per-target socket probe (E1)
```

### Closure note

H1d.5 bounded remediation (SO_SNDBUF, egress warm-up, pacing-thread affinity)
established a valid benchmark path but did **not** stabilize C3 MAX under H1c
lightweight (run1 MAX 10.795ms). See `0060-gres3-e2-sender-execution-model-adjudication.md`.

---

## H1d.5a — Egress warm-up validity (evidence gate)

**Scope:** evidence-validity completion only — not performance optimization.

```text
1. Warm-up validity
   attempted = true
   succeededLegs == 9 && failedLegs == 0 → warmEgressSucceeded = true

2. Failure observability (per failed leg)
   receiver/leg identity, exception class, message, cause (if any)

3. Hard gate
   warm-up != 9/9 → ABORTED_BEFORE_MEASUREMENT
   no measurement, no H1d.5 efficacy verdict
   WarmupAttempts = 1 (no retry)
```

State machine:

```text
SOCKET_CONFIGURED → TIME_WARMUP → EGRESS_WARMUP
  ├── 9/9 → MEASUREMENT
  └── <9  → ABORTED_BEFORE_MEASUREMENT
```

Evidence: `h1d5a-adjudication.json` (always written after egress warm-up attempt).

Next verify (only):

```powershell
.\scripts\adr0060-gres3-h1c-qualification-run.ps1 -Sender M03 -HarnessPhase H1d -RunId "gres3-h1d5a-verify-..."
```

Answer Q1 (9/9?) and Q2 (if not, why?) before any H1c or performance discussion.

### H1d.5b — Egress warm-up thread affinity (AUTHORIZED)

**Root cause (H1d.5a):** `NetworkOnMainThreadException` — warm-up ran on Activity main thread.

**Fix:** pacing thread lifecycle:

```text
pacing thread starts → apply/verify priority → EGRESS_WARMUP → warm-up gate → MEASUREMENT
```

Shared: same thread, priority, socket, endpoints, send path. `WarmupAttempts = 1` unchanged.

---

## Problem statement

H1d.4 attributed worst tail to **fanout-internal non-CPU stall** with **single `send()` wall dominance** (leg2 ≈ 6.4ms, wall≈10.5ms, cpu≈3.2ms). Remediation is at **socket/kernel egress**, not Java loop tuning.

---

## Goal

Reduce occasional `DatagramSocket.send()` blocking impact on 9-leg sequential fanout MAX, while preserving:

```text
protect-once / byte-identical replication / single sender socket / unconnected mode
```

---

## Allowed scope

### 1. Socket configuration (minimal)

```text
SO_SNDBUF default read + explicit set (256 KiB)
confirm unconnected DatagramSocket (no connect())
endpoint semantics unchanged
```

### 2. Sender socket lifecycle

```text
single socket opened once per run
InetSocketAddress resolved at open only (no DNS on hot path)
no per-slot/per-leg reopen/rebind
```

### 3. Egress warm-up (pre-measurement)

```text
one 9-leg fan-out after time warmup, before measurement window
not counted in slot metrics / evidence counters
no ACK / receiver dependency
```

### 4. Socket observability (manifest/summary only)

```text
effective SO_SNDBUF, traffic class, connected/reuse/broadcast flags
egress warm-up leg success/fail counts
no H1c per-slot instrumentation
```

---

## Rejected (H1d.5)

```text
multi-socket / per-receiver socket
parallel send / coroutine / thread pool
JNI sendmmsg / nonblocking NIO refactor
20ms slot change
C3 threshold change
```

If H1d.5 field verify still shows stable single-send 5–7ms non-CPU stalls with sndbuf already optimal → **stop H1d.5** and open separate architecture decision (not in this scope).

---

## Implementation

| Component | Change |
|-----------|--------|
| `Gres3UdpEgressSocketConfigurator` | SO_SNDBUF 262144, profile snapshot |
| `FanOutDatagramSender.warmEgress()` | pre-measurement 9-leg warm |
| `Gres3CapacityHarnessRunner` | egress warm after time warmup |
| `manifest.json` / `summary.json` | `udpEgressSocket` block |
| `Gres3BenchmarkConfigHash` | includes `udpSendBufferBytes` |

---

## Verification sequence

```text
1. H1d.5 diagnostic verify
   → no regression
   → single-send stall tail converges if remediation effective

2. 2× consecutive H1c lightweight:
     P99 < 5ms
     MAX < 10ms
     deadlineMiss = 0
     sendFail = 0

→ only then解除 3×QUALIFYING HOLD
```

```powershell
.\scripts\adr0060-gres3-h1c-qualification-run.ps1 -Sender M03 -HarnessPhase H1d -RunId "gres3-h1d5-verify-..."
.\scripts\adr0060-gres3-h1c-qualification-run.ps1 -Sender M03 -HarnessPhase H1c -SkipBuild -RunId "gres3-h1c-h1d5-run1-..."
.\scripts\adr0060-gres3-h1c-qualification-run.ps1 -Sender M03 -HarnessPhase H1c -SkipBuild -RunId "gres3-h1c-h1d5-run2-..."
```

---

## Frozen state

```text
H1d.4:           COMPLETE
H1d.5:           INCONCLUSIVE (efficacy unproven)
H1d.5a:          COMPLETE (INVALID — NetworkOnMainThreadException)
H1d.5b:          AUTHORIZED / IMPLEMENTED (thread affinity fix)
H1c:             HOLD
3×QUALIFYING:    HOLD
C3:              FROZEN
```
