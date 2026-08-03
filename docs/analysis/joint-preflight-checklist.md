# Joint Preflight Checklist (post Attempt-4c-S)

**Gate:** Attempt-4c-S Case S-A PASS archived.  
**Still forbidden:** R4-impl / `ADOPTED` / `TRANSFERRED` / generation rewrite / Completion fence changes.

## J0 — Evidence freeze (done when field report lands)

- [x] Attempt-4c-S Case S-A PASS
- [x] Log dir linked: `logs/phase3c-b-attempt4cs-20260803-155535`
- [x] Report: `docs/analysis/attempt4cs-field-report.md`
- [ ] Commit / merge archival PR (docs only)

## J1 — Attribution hygiene before Joint run

### 1. E.18 Case-C

Do **not** fix for Joint cleanliness.

Joint report MUST carry:

```text
Control:
  CASE-C observed (or possible)
  membershipEpochConverged may be false
  completion interpretation limited
```

Never attribute Joint fail to missing adoption.

### 2. PR52C integrity

- [ ] No `PR52C_RELEASE_DISPATCH` (or other PR52C wake) on host during Joint
- [ ] Process remains alive (no ANR kill) on M02

### 3. Experiment-condition table

Joint write-up MUST include:

| Run | Condition |
|-----|-----------|
| 4c baseline | successor allowed |
| 4c-S | successor suppressed (`HARNESS_SUCCESSOR_SUPPRESSION_APPLIED`) |
| Joint (this run) | state condition explicitly |

If Joint runs **with** SUPPRESS, `topologyMode=EXERCISE_SUPPRESSED_SUCCESSOR`.  
If Joint runs **without** SUPPRESS, say so — do not silently mix.

## J2 — Joint entry criteria (minimum)

- [ ] J0 archival committed
- [ ] E.18 labeled (OPEN / Case-C), not "fixed"
- [ ] PR52C off
- [ ] SUPPRESS condition recorded for the Joint attempt
- [ ] No R4-impl in the build under test

## Next after preflight

Run Joint as integration confidence gate only — not as R4 adoption proof.