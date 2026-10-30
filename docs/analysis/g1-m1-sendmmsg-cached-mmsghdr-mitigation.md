# G1-M1 — sendmmsg Cached mmsghdr Mitigation

## Status

```text
Phase:          G1-M1 — minimal egress-tail mitigation
Trigger:        G1 Run 1 VALID_FAIL (fanoutMax 11.77 ms); H1c attribution CLOSED
H1d:            CANCELLED
C3:             FROZEN (P99≤5ms, MAX≤10ms)
Field:          3× VALID PASS @ N=9 (2026-09-14) — G1 CLOSED / FIELD-PROVEN
```

## Problem

H1c attributed G1 tail to **blocking sendmmsg syscall** (primary) with a **~2.38 ms
fanoutWall − syscallWall gap** on Run 1. Native probe rebuilt `sockaddr_in` / `mmsghdr`
from JNI strings on **every slot**, adding userspace work before each syscall and
widening compound sched+syscall tail windows.

## Change (G1-M1)

```text
At nativeOpen(bindPort, destIps, destPorts):
  resolve endpoints once
  cache sockaddr_in + mmsghdr per leg

Per slot sendmmsgBatch(fd, payload, len):
  pin payload buffer
  update iovec base/len only
  sendmmsg (blocking or MSG_DONTWAIT per execution model)
```

Semantics unchanged: protect-once artifact, 9 distinct destinations, single syscall,
no retry, same SO_SNDBUF / warm-up / pacing thread path.

## Field verification (2026-09-14)

First attempt (`154521`) **INVALID** — instrumentation timeout 1200s; only pre-warmup
manifest on device (MainActivity foreground contention). Not capacity FAIL.

Retried with SkipInstall + force-stop hygiene → **3× VALID PASS**:

| Run | logDir | fanoutP99 | fanoutMax | worstSyscall | C3 |
|-----|--------|-----------|-----------|--------------|-----|
| 1 | `gres3-qualifying-ga-g1-20260914-161026-r1` | 2.31 ms | **7.24 ms** | 6.35 ms | PASS |
| 2 | `gres3-qualifying-ga-g1-20260914-162408-r1` | 2.17 ms | **9.08 ms** | 8.91 ms | PASS |
| 3 | `gres3-qualifying-ga-g1-20260914-163627-r1` | 2.12 ms | **8.72 ms** | 7.33 ms | PASS |

Set aggregate: `logs/ga-g1-gres3-qualifying-set-g1m1-20260914-3x` → **G1_RESULT=PASS**.

Mechanical completeness all runs: 30k slots · 270k legs · 0 notAttempted · partialBatch=0.

```text
G1              CLOSED / FIELD-PROVEN
G1-M1           EFFICACIOUS (vs pre-mitigation 11.77 ms FAIL)
E3b             NOT REQUIRED
C3 10ms         UNCHANGED
Next            G3 stability (G2 already CLOSED)
```

## Verification

```powershell
# Merge focused verify with qualifying Run 1 (600s, N=9, SENDMMSG_BATCH)
.\talkback\scripts\meeting-product-ga-g1-gres3-qualifying-set.ps1 -RunCount 1 -Sender M03
```

**PASS** → continue Run 2/3 on same build.

**FAIL** → E3b 90s probe (`adr0060-gres3-e3b-nonblocking-probe-run.ps1`); do not reopen H1d.

## Disposition matrix

| Outcome | Next |
|---------|------|
| Run 1 PASS | qualifying Run 2 → Run 3 |
| Run 1 FAIL, syscall still dominates | E3b probe |
| Run 1 FAIL, E3b integrity break | ADR / contract review |
| Run 1 FAIL, E3b tail PASS | E3b adoption for qualifying path |
| **3× VALID PASS (observed)** | **G1 CLOSED → G3** |
