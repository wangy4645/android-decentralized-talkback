# Joint Preflight READY (2026-08-03)

## Status board

```text
R3 Delivery Obligation          VERIFIED
Delivery Observation Hygiene    FIELD_VERIFIED
E.18 Membership Authority       Case-C FIELD_VERIFIED (see docs/analysis/e18-case-c-closure.md); E.18 overall OPEN (A/B pending)
R4-def                          DEFINED
SUPPRESS_SUCCESSOR_ATTEMPT      VERIFIED (harness + Case S-A)
Attempt-4c-S                    PASS (Case S-A) archived (#110)
J0 Evidence freeze              DONE
J1 Attribution hygiene          READY (constraints below)
J2 Entry                        UNBLOCKED after harness PR merge (test/anr-safe-joint-debug-controls)
R4-impl                         WAITING (forbidden)
```

## J0 — DONE

- Attempt-4c-S Case S-A PASS
- Report: `docs/analysis/attempt4cs-field-report.md`
- Artifact: `logs/phase3c-b-attempt4cs-20260803-155535`
- Merged: PR #110

## J1 — Attribution constraints (must travel with any Joint report)

```text
Control:
  CASE-C observed (or possible)
  membershipEpochConverged may be false
  completion interpretation limited

Harness integrity:
  PR52C wake must not ANR-kill host
  Process lifecycle ALIVE_OR_UNKNOWN (not DEAD_OBSERVED)

Experiment condition table (required):
  4c baseline | successor allowed
  4c-S        | successor suppressed (HARNESS_SUCCESSOR_SUPPRESSION_APPLIED)
  Joint       | <explicit: WITH_SUPPRESS | WITHOUT_SUPPRESS>
```

Recommended Joint condition for first post-S-A run:

```text
topologyMode = EXERCISE_SUPPRESSED_SUCCESSOR
Joint WITH_SUPPRESS
```

Reason: coexistence under the same counterfactual topology already proven for R3 lifecycle;
avoids re-mixing "natural successor" noise into the first Joint confidence gate.

## J2 — Harness surface PR open (await merge + install)

Current main debug broadcast surface (`TalkbackForegroundService`) exposes **only**:

```text
SUPPRESS_SUCCESSOR_ATTEMPT_ARM
SUPPRESS_SUCCESSOR_ATTEMPT_CLEAR
```

Classic Joint (§E.16) also needs:

| Need | Status on main |
|------|----------------|
| SUPPRESS | LANDED |
| D1 arm/clear broadcast + `D1IngressMissDebugInjection` | NOT on main service/runtime wiring |
| PR52C CREATE/BLOCK/NEG/RELEASE under ANR-safe dispatcher | Core injection exists; **service/runtime debug API not wired on main** |

Therefore: **do not start full Joint on current APK** — it cannot arm D1/C.

## Next engineering (single PR, harness-only)

Title suggestion:

```text
test(harness): restore ANR-safe D1 + PR52C debug surface for Joint
```

Scope:

1. Wire D1 + PR52C debug methods on Coordinator/Runtime (as before)
2. Extend DEBUG receiver under existing `DebugHarnessBroadcastDispatcher` (goAsync + watchdog)
3. Keep SUPPRESS actions
4. **No** RecoveryDeliveryPolicy / CompletionPolicy / adoption / generation changes
5. UT: dispatcher still finishes within watchdog; D1 drop still ingress-only

Out of scope:

- R4-impl / ADOPTED / TRANSFERRED
- E.18 Case-C "fix"
- Changing J-B semantics

After that PR merges + install:

```text
Joint run contract:
  topologyMode=EXERCISE_SUPPRESSED_SUCCESSOR
  SUPPRESS armed for episode
  D1 for J-A
  PR52C for J-B (ANR-safe path only)
  Report must include E.18 Case-C label + condition table
```

## Explicit non-goals now

- Do not implement ADOPTED
- Do not treat S-A as Joint PASS
- Do not fix E.18 to clean Joint attribution

## Update 2026-08-03 evening

Joint blocker reframed: **PR52C release readiness defect** (not missing debug surface). Fix PR: https://github.com/wangy4645/android-decentralized-talkback/pull/112
