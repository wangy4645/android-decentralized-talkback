# ADR-0058 E3a.3a.2 — M03 Receiver Probe Execution Fix

## Status

```text
SCOPE:     probe/runtime execution only — no socket/join/receive semantics change
RETAINED:  50 pps / 120 B / timeouts / 3a.1 transport fields
```

## Root cause

M03 (Huawei ELS_AN00) froze **background instrumentation threads** — probe never reached `PROBE_END` / host timed out even when JSON eventually existed on-device.

## Fixes (minimal)

| Change | File |
|--------|------|
| Foreground `UnderlayMulticastProbeActivity` (KEEP_SCREEN_ON + wake lock) | `UnderlayMulticastProbeActivity.kt` |
| Test launches activity; awaits `ProbeCompletion` latch | `UnderlayMulticastProbeInstrumentedTest.kt` |
| `instrumentation.context` for probe I/O | activity + test |
| Scheduled socket shutdown + worker deadline (Huawei `receive` block) | `UnderlayMulticastProbeRunner.kt` |
| `force-stop com.talkback.test` + poll receiver JSON during wait | `adr0058-underlay-mcast-probe-start-run.ps1` |

## Exit criteria — PASS

M03 receiver smoke `probe-m03-fix5`: **OK (1 test)** in ~21s, `PROBE_END` + JSON on host.

## E3a.3 results (post-fix)

| Run | Result |
|-----|--------|
| M01→M03 `095052` | **93.2% PASS** |
| M03→M02 `095230` | **0%** (M02 receiver; join OK) |

See `logs/adr0058-e3a3-classification-20260903/CLASSIFICATION_MATRIX.txt`.
