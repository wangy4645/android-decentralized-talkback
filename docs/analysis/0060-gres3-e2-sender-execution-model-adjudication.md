# G-RES-3 E2 — Sender Fan-out Execution Model Adjudication

## Status

```text
Track:              G-RES-3 E2 — execution model adjudication
Type:               engineering decision track (NOT formal ADR yet)
Prerequisites:        H1d remediation series CLOSED AS INSUFFICIENT
Parent:             G-RES-3-H1 evidence harness · G-RES-3-C2 Model B1
C3 gates:             FROZEN (P99≤5ms, MAX≤10ms, slot<20ms)
H1c lightweight:      FAIL / STOP (run1 MAX 10.795ms)
3×QUALIFYING:         HOLD
Formal ADR:           NOT STARTED — only if frozen implementation seam changes
```

---

## Decision trigger

H1d bounded remediation is closed. Single-socket sequential sender is:

```text
FUNCTIONALLY VIABLE
CAPACITY-EVIDENCE UNSTABLE
```

Latest H1c lightweight run had **no experimental-validity excuse**:

```text
warmEgress        9/9
priority          -8 applied
topology          ELIGIBLE
measurement       complete (4500 slots)
sendFail          0
deadlineMiss      0
P99               4.716ms   PASS
MAX               10.795ms  FAIL
```

Historical valid MAX distribution:

```text
7.23ms    PASS
12.40ms   FAIL
7.48ms    PASS
10.795ms  FAIL
```

**Conclusion:** steady state is adequate; rare single-slot tail is not stable enough
for frozen C3 `MAX < 10ms` under current execution primitive. Further H1d micro-tuning
(SO_SNDBUF, warm-up variants, Kotlin loop) is stopped.

---

## H1d remediation series — closure

```text
H1d.2 scheduler:       CLOSED
H1d.3 Java hot path:   CLOSED
H1d.4 attribution:     CLOSED
H1d.5 socket/warmup:   CLOSED AS INSUFFICIENT
H1d.5a warm-up gate:   CLOSED (validity mechanism retained)
H1d.5b thread affinity: CLOSED (pacing-thread warm-up path retained)

single-socket sequential:
  FUNCTIONALLY VIABLE
  CAPACITY-EVIDENCE UNSTABLE
```

`CLOSED AS INSUFFICIENT` ≠ sndbuf/warm-up useless. It means the bounded remediation
set did not push the execution model to **stable qualifying margin**.

---

## Question

> Under frozen C3, N=9, 50 pps, Android/WLAN runtime — which sender execution
> primitive provides repeatable tail margin for formal capacity evidence?

This is **not** declaring physical impossibility. It declares the current
single-socket sequential model lacks product-evidence determinism for `MAX < 10ms`.

---

## Candidate models (round 1 only)

| Model | Definition | Status |
|-------|------------|--------|
| **E0** | Current: one `DatagramSocket`, sequential 9×`send()` on pacing thread | **REJECT** — capacity-evidence unstable |
| **E1** | Per-target socket; **single pacing thread**; sequential 9×`send()` | **PROBE PASS / NOT SELECTED** — insufficient tail margin |
| **E3** | One blocking native `sendmmsg(9)` per slot | **AUTHORIZED FOR ARCHITECTURE PROBE** |

### Held (not in round 1)

```text
E2  NIO / nonblocking          HOLD
E4  parallel send threads      REJECT for now
```

### Why E1 first

Minimal execution-model change. Preserves:

```text
1 logical slot
→ protect once
→ identical 120B artifact
→ same sequence / ROC
→ 9 send attempts
→ per-leg failure isolation
→ one pacing thread
→ same warmup path (time → pacing thread → egress 9/9 → measurement)
```

Only change:

```text
OLD:  one socket → R01..R09
E1:   socket[R01]→R01 .. socket[R09]→R09
```

Targets observed kernel/socket egress tail without changing scheduling model.

---

## E1 adjudication (2026-09-04)

3× M03 E1 diagnostic probes (hash `a420ec9a…`):

| Run | P99 | MAX |
|-----|-----|-----|
| #1 | 4.208ms | 9.815ms |
| #2 | 4.607ms | 9.778ms |
| #3 | 4.691ms | 9.945ms |

```text
E1:
  MECHANICALLY PROMISING
  TAIL VARIANCE IMPROVED (vs E0)
  CAPACITY MARGIN INSUFFICIENT

production selection: NO
```

E1 eliminated shared-socket tail instability but still runs **9 kernel egress entries /
slot**. MAX clustered ~9.8–9.9ms — not safe margin for 3×600s qualifying.

Logs:

```text
talkback/logs/gres3-e1-probe-1-20260904-085611/
talkback/logs/gres3-e1-probe-2-20260904-090256/
talkback/logs/gres3-e1-probe-3-20260904-090635/
```

---

## E3 probe authorization (2026-09-04)

**Why E3 before E2:** remaining tail is syscall/kernel-entry count (9× per slot).
`sendmmsg` tests `1 logical slot → 1 sendmmsg → 9 datagrams` without NIO partial-send
semantics. E2 remains HOLD.

### E3 scope (frozen)

```text
single pacing thread
protect once
same 120B artifact
same RTP seq / ROC
9 distinct destinations
50 logical slots/s
same topology / priority / warm-up
no multicast / no retry / no catch-up
blocking sendmmsg only (no MSG_DONTWAIT)
```

Only change vs E1:

```text
E1: 9 × DatagramSocket.send()
E3: 1 × native sendmmsg(9 datagrams)
```

### E3 observability (probe-only)

```text
requestedMessages = 9
returnedMessages
syscallWallNs
errno

returnedMessages < 9 → probe send failure (no retry)
```

Summary manifest includes `sendmmsgProbe` block when `executionModel=SENDMMSG_BATCH`.

### E3 architecture-selection gate (engineering — not new C3 contract)

```text
3 × short probe
each run:
  P99 < 5ms
  MAX < 10ms
  sendFail = 0
  deadlineMiss = 0
  returnedMessages == 9 for every slot

architecture signal:
  MAX materially away from 10ms boundary (~5–8ms expected)
  not borderline 9.6–9.9ms scrape
```

If E3 wins → E3 adoption review (partial-send mapping, C2 seam, JNI product cost).
If E3 fails margin → revisit E2 or C3 contract re-adjudication (ADR only).

---

```text
same M03 sender
same 9 real endpoints
same 50 pps
same 120B protect-once artifact
same priority (-8)
same warmup (time + pacing-thread egress 9/9)
same topology (gres3-h1c-lab-m03-sender-v1)
E0 baseline retained for comparison

E1 probe: 3 short diagnostic/lightweight runs
```

### Probe pass criteria (engineering — stricter than single 9.9ms scrape)

```text
P99 < 5ms
MAX consistently < 10ms
ideally MAX has material margin (not 9.8ms / 9.9ms borderline)
slotDeadlineMiss = 0
sendFail = 0
warmEgress = 9/9
priorityApplied = true
measurement complete
```

3 runs = **architecture selection experiment only** — does not violate C4 formal
3-run rule.

### Probe fail interpretation

If E1 shows unstable pattern (e.g. 7ms / 11ms / 8ms), **do not** continue socket-count
or buffer-size sweeps (2 sockets, 384K, 768K). Escalate to heavier model comparison:

```text
nonblocking batching  vs  native sendmmsg
```

That indicates tail is inherent to "9 independent blocking sends" on Android/Linux/WLAN,
not shared-socket queue isolation alone.

---

## C2 boundary (probe phase)

**Do not amend C2 during probe.**

C2 Model B1 freezes:

```text
9 real UDP send() per slot
byte-identical protect-once replication
one pacing thread / slot discipline
```

C2 does **not** explicitly freeze "one DatagramSocket". It requires "actual socket
send path" and real kernel sends (G3-C2-I4). Per-target sockets may be an
**implementation seam** change if semantics unchanged.

**Only if E1 wins** and is selected as product execution model:

1. Re-check C2 / Profile 02 A9 fan-out seam for "single socket" normative language
2. If frozen → C2 execution-seam amendment + `benchmarkConfigHash` re-baseline
3. If harness-only → implementation change + hash update + re-evidence

Do not silently change contract during probe.

---

## Frozen execution path (all models)

Benchmark configuration now includes:

```text
TIME_WARMUP
→ pacing thread start
→ apply/verify priority
→ EGRESS_WARMUP 9/9 (1 attempt, hard gate)
→ MEASUREMENT
```

This path is part of frozen benchmark execution. Must not be removed for formal runs.

---

## Evidence references

| Run | Phase | Result | MAX |
|-----|-------|--------|-----|
| H1d.5a verify | warm-up INVALID | 0/9 NetworkOnMainThread | — |
| H1d.5b verify | gate PASS | 7.48ms | PASS |
| H1c #1 post-H1d.5b | lightweight FAIL | 10.795ms | FAIL |

Logs:

```text
talkback/logs/gres3-h1d5a-verify-20260903-172741/
talkback/logs/gres3-h1d5b-verify-20260903-173432/
talkback/logs/gres3-h1c-h1d5b-run1-20260903-174105/
```

---

## Current posture

```text
E0 shared socket sequential:     REJECT — capacity-evidence unstable
E1 per-target socket sequential: PROBE PASS / NOT SELECTED — insufficient headroom
E2 NIO/nonblocking:              CLOSED / NOT REQUIRED
E3 sendmmsg batching:            CONTRACT-ADOPTED — SENDMMSG_BATCH
                                  Adoption review + amendments CLOSED
                                  → 0058-profile-02-a8-send-not-attempted-amendment.md
                                  → 0060-gres3-c2-submission-primitive-amendment.md
                                  → 0060-gres3-e3-sendmmsg-adoption-review.md

E3 H1c:                          PASS / CLOSED (`gres3-e3-h1c-20260904-094413`)
3×600s QUALIFYING:               HOLD — preflight BLOCKED (do NOT start Run #1)
ADR-0060:                        NO CHANGE
C3:                              FROZEN (UNCHANGED)
Production cutover:              NOT AUTHORIZED
```

### E3 probe results (2026-09-04, M03, hash `06cffc9a…`)

| Run | P99 | MAX | partialBatch | warmEgress |
|-----|-----|-----|--------------|------------|
| #1 | 2.765ms | 7.649ms | 0 | 9/9 |
| #2 | 2.369ms | 7.345ms | 0 | 9/9 |
| #3 | 2.866ms | 8.521ms | 0 | 9/9 |

```text
E3:
  MECHANICAL PASS (3/3)
  MATERIAL TAIL MARGIN (MAX ~7.3–8.5ms vs E1 ~9.8–9.9ms)
  sendFail=0 deadlineMiss=0 returnedMessages=9 every slot

production selection: NOT AUTHORIZED — adoption review next
  → docs/analysis/0060-gres3-e3-sendmmsg-adoption-review.md
E2 comparison:        NOT REQUIRED (batching hypothesis confirmed)
```

Logs:

```text
talkback/logs/gres3-e3-probe-1-20260904-091556/
talkback/logs/gres3-e3-probe-2-20260904-092108/
talkback/logs/gres3-e3-probe-3-20260904-092606/
```

### E3 probe harness (implemented)

```text
Gres3SenderExecutionModel.SENDMMSG_BATCH
SendmmsgBatchFanOutSender — blocking native sendmmsg(9) per slot
Gres3SendmmsgNative + gres3_sendmmsg_probe.cpp (JNI)
FanOutSenderFactory — selects E0/E1/E3 from config.executionModel
benchmarkConfigHash includes executionModel + socketCount
summary sendmmsgProbe: partialBatchCount, minReturnedMessages, worstSyscallWallNs
```

Run (H1d diagnostics + E3 execution model):

```powershell
.\scripts\adr0060-gres3-h1c-qualification-run.ps1 -Sender M03 -HarnessPhase H1d -ExecutionModel E3 -RunId "gres3-e3-probe-1-..."
```

### E1 probe harness (retained for comparison)

```text
Gres3SenderExecutionModel.PER_TARGET_SOCKET_SEQUENTIAL
PerTargetFanOutDatagramSender — 9 long-lived sockets, single pacing thread
```

Run:

```powershell
.\scripts\adr0060-gres3-h1c-qualification-run.ps1 -Sender M03 -HarnessPhase H1d -ExecutionModel E1 -RunId "gres3-e1-probe-1-..."
```

---
