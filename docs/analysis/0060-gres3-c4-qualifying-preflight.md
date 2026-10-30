# G-RES-3 C4 Qualifying — Preflight (before Run #1)

## Status

```text
Date:                 2026-09-04
Trigger:              E3 H1c PASS → B1–B5 harness wiring authorized
Verdict:              WIRING CLOSED — C4 qualifying preflight PASS (static)
                      Qualifying Run #1: AUTHORIZED pending operator launch

E3 H1c:               PASS / CLOSED (still valid — hot path unchanged)
executionModel:       SENDMMSG_BATCH (CONTRACT-ADOPTED)
C3:                   FROZEN
ADR-0060:             NO CHANGE
E3 architecture probe: NOT REQUIRED (wiring-only change)
```

---

## Verdict

```text
B1–B5 harness wiring:     CLOSED
C4 preflight (static):    PASS
Qualifying Run #1:        AUTHORIZED (operator may launch 600s soak)

Formal 3×600s closeout:   NOT STARTED until Run #1 VALID PASS
```

Static preflight validates launcher/adjudicator/summary contract wiring.
First live 600s run still must produce `c4-adjudication.json` with
`runVerdict=PASS` and `eligibleForQualifyingSet=true`.

---

## B1–B5 closure

| ID | Requirement | Status | Implementation |
|----|-------------|--------|----------------|
| **B1** | `runClass=QUALIFYING` in manifest/summary/adjudicator; not inferred from HarnessPhase | **CLOSED** | `-e runClass QUALIFYING`; `Gres3RunClass.parse()`; `RUN_META` + `config.json` |
| **B2** | Instrument await timeout > 60+600+30 lifecycle | **CLOSED** | `QUALIFYING_INSTRUMENTATION_TIMEOUT_SEC=900` |
| **B3** | All 9 sinks lifetime ≥ 900s | **CLOSED** | `adr0060-gres3-qualifying-run.ps1 -SinkDurationSec 900` (default) |
| **B4** | Formal qualifying launcher + evidence + C4 adjudication closed loop | **CLOSED** | `adr0060-gres3-qualifying-run.ps1` → `adr0060-gres3-qualifying-adjudicate.ps1`; device writes `c4-adjudication.json` |
| **B5** | Summary/adjudicator mechanical proof of submission completeness | **CLOSED** | `qualifyingEvidence` block + C4 Tier-1 gates; P99/MAX from COMPLETE slots only |

### B5 frozen gates (qualifying only)

```text
Hard gate (C4 Tier-1):
  expectedLogicalSlots = 30,000
  expectedLegSendAttempts = 270,000
  perLegSendAttemptCount = 270,000
  sendFailCount = 0
  notAttemptedCount = 0
  partialBatchCount = 0
  fanoutIncompleteSlotCount = 0
  minReturnedMessages = 9   (SENDMMSG_BATCH only)
  all scored fanout slots = COMPLETE (P99/MAX histogram excludes INCOMPLETE)

Diagnostic only:
  batchSyscallDurationUs / worstSyscallWallNs
```

---

## Preflight checklist result (post-wiring)

```text
runClass = QUALIFYING .................... PASS (wired)
warmup/measurement/cooldown 60/600/30 .... PASS (Activity + config)
ExpectedLogicalSlots = 30,000 ............ PASS (summary.qualifyingEvidence)
ExpectedLegSendAttempts = 270,000 ........ PASS
executionModel = SENDMMSG_BATCH .......... PASS (qualifying script -ExecutionModel E3)
socketMode = sendmmsg_batch .............. PASS (unchanged hot path)
multicastLegs = 0 / targets = 9 .......... PASS
harness/runtime parity ................... PASS
topology = formal H1c .................... PASS
thermal observability .................... PASS
instrument timeout for 600s .............. PASS (900s)
sink duration for 600s ................... PASS (900s default)
notAttemptedCount in summary ............. PASS
fanoutCompletion proof ................... PASS (fanoutComplete/Incomplete counts)
batchSyscallDurationUs ................... PASS (diagnostic in qualifyingEvidence)
C4 post-run adjudicate script ............ PASS
```

---

## Authorized next work

```text
1. Launch Qualifying Run #1:
     scripts/adr0060-gres3-qualifying-run.ps1 -ExecutionModel E3

2. On VALID PASS → retain bundle; authorize Run #2
   On VALID FAIL → stop; do not run #2
   On INVALID → fix harness; rerun #1 (not capacity FAIL)

3. After 3× VALID PASS → Gres3QualifyingSetAdjudicator closeout
```

```text
STILL FORBIDDEN:
  treat H1c 90s QUALIFICATION as C4 evidence
  rename QUALIFICATION → QUALIFYING after the fact
  reopen C3 / E3 probe without hot-path change
  production cutover
```

---

## Disposition

```text
B1–B5:                 CLOSED
C4 preflight (static): PASS
Qualifying Run #1:     AUTHORIZED
3×600s QUALIFYING:     READY (Run #1 not yet executed)
G-RES-3:               EVIDENCE PATH UNBLOCKED AT HARNESS WIRING
```

---

## References

- `scripts/adr0060-gres3-qualifying-run.ps1` (NEW)
- `scripts/adr0060-gres3-qualifying-adjudicate.ps1` (NEW)
- `scripts/adr0060-gres3-h1c-qualification-run.ps1` (H1c short gate — unchanged)
- `android-board-talkback/.../Gres3H1cCapacityHarnessInstrumentedTest.kt`
- `android-board-talkback/.../Gres3C4EvidenceAssembler.kt` (NEW)
- `android-board-talkback/.../Gres3CapacityRunAdjudicator.kt`
- H1c PASS: `logs/gres3-e3-h1c-20260904-094413/`
