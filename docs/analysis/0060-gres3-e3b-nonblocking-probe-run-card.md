# G-RES-3 E3b — Nonblocking sendmmsg Remediation Probe

## Status

```text
Date:                 2026-09-04
Trigger:              3× QUALIFYING FAIL @ Run #3 (MAX 14.201ms, syscall tail)
E3 blocking:          SELECTED MODEL, G-RES-3 CAPACITY FAIL
E3b nonblocking:      AUTHORIZED — ARCHITECTURE REMEDIATION PROBE
Run #4:               NOT AUTHORIZED
C3:                   FROZEN
random rerun:         PROHIBITED
```

## Objective

Validate whether `sendmmsg(..., MSG_DONTWAIT)` converts blocking kernel stall tails into
explicit partial / EAGAIN outcomes **without** pacing-thread 10ms+ blocks — while preserving
Decision 1/2 semantics:

```text
prefix SUCCEEDED
boundary SEND_FAILED
suffix SEND_NOT_ATTEMPTED
NO RETRY / NO CATCH-UP / NO seq-ROC rollback
```

## Probe configuration

```text
Harness:        H1c formal topology (90s measurement gate)
runClass:       QUALIFICATION (NOT qualifying evidence)
executionModel: SENDMMSG_BATCH_NONBLOCKING (E3b)
socketMode:     sendmmsg_batch_nonblocking
Runs:           3× independent probe (mirror E3 probe discipline)
```

## Success criteria (two layers)

**Layer 1 — tail suppressed**

```text
fanoutP99 <= 5 ms
fanoutMAX < 10 ms (strong signal: MAX <= 8 ms)
```

**Layer 2 — no latency gaming**

```text
partialBatchCount = 0
notAttemptedCount = 0
eagainCount = 0
sendFailCount = 0
minReturnedMessages = 9
fanoutIncompleteSlotCount = 0
slotDeadlineMissCount = 0
```

**REJECT** if MAX is low but partial/EAGAIN/notAttempted appear frequently — that is
substituting dropped sends for slow sends.

## Launch

```powershell
# single probe
.\scripts\adr0060-gres3-e3b-nonblocking-probe-run.ps1 -ProbeIndex 1

# 3× probe chain (manual — no auto-qualifying)
1..3 | ForEach-Object { .\scripts\adr0060-gres3-e3b-nonblocking-probe-run.ps1 -ProbeIndex $_ }
```

## Disposition matrix

| Outcome | Next |
|---------|------|
| 3× STRONG / PASS tail+integrity | E3b adoption review → may supersede E3 blocking for evidence path |
| tail PASS, integrity FAIL | REJECT — latency gaming; do not adopt E3b |
| tail FAIL | E3b does not remediate stall; new ADR required |
| INVALID | fix harness only |
