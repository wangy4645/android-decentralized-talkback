# PA-SR3 Field Re-run — INVALID_GROUP_PRECONDITION (PRE-G)

**Stamp:** 20260909-140523 (also prior abort 135934)  
**LogDir:** `talkback/logs/phase-a-sr3-field-20260909-140523/`  
**Build:** B1-R1 APK installed on both; `-SkipBuildInstall`  
**Devices:** M01 online · M02 online (portrait locked)

---

## Verdict

```text
VERDICT=INVALID_GROUP_PRECONDITION
PA-SR3=NOT_TESTED
reason=PRE_G_group_teardown_incomplete_or_residual_grp_CH-01

Not FAIL_PA_SR3 — SOURCE chain never entered.
Not PASS.
```

## Gate score

| Gate | Result |
|------|--------|
| PRE-1 trust | PASS |
| EP probe | PASS |
| Service UI start | PASS (stop+restart path) |
| PRE-G leave → CLEAR | **FAIL** |
| PA-SR3 SOURCE chain | **NOT TESTED** |

## RCA (orchestration / runtime attach — not SOURCE)

```text
Ensure-ServiceStarted → Coordinator started (GBC/HELLO live)
→ later Coordinator stopped on both devices
→ P1C_FORCE_LEAVE_CHANNEL_SESSION delivered
→ DEBUG_DISPATCH_SKIPPED reason=runtime_unavailable
→ no P1C_GROUP_PRECONDITION_LEAVE outcome=CLEAR
→ residual grp:CH-01 → INVALID_GROUP_PRECONDITION
```

Harness note: stop+restart was added because UI “already running” still left leave path `runtime_unavailable`. After restart, coordinator still drops before PRE-G leave completes — leave cannot clear residual GROUP.

## Status board (unchanged product gates)

```text
PR-PA-SR-B1     CLOSED / UNIT-PROVEN · FIELD FAIL (prior 121252)
B1-R1           CLOSED / UNIT-PROVEN
PA-SR3          AUTHORIZED FOR RE-RUN · NOT TESTED (this attempt INVALID_*)

PA-SR4…SR6      HOLD
ADR-0056        KEEP
```

## Next

1. Before next PA-SR3 attempt: ensure TalkbackRuntime stays up through PRE-G leave (no `Coordinator stopped` before `outcome=CLEAR`), or clear residual `grp:CH-01` by a known-good leave while runtime is confirmed live.
2. Do **not** treat this as SOURCE origin RCA; do not open SR4–SR6.
3. Re-run `phase-a-sr3-field-first-run.ps1 -SkipBuildInstall` only after PRE-G can PASS.
