# RCA-003-R4 — ConferenceMediaUnavailable Ownership Trace

**Status:** COMPLETE (desk + field correlate) · **no product code**  
**Date:** 2026-08-11  
**Parent:** [rca-003-presentation-convergence-entry.md](./rca-003-presentation-convergence-entry.md)  
**Evidence:** Round-3 `logs/rca003-pres-conv-r3-20260810-220243/` · Round-2 `logs/rca003-pres-conv-r2-20260810-211253/`

## Three-line result (frozen)

```text
SET owner:
  ConferenceEdgeRecoveryController.enterFailedMediaResidency
  → EdgeRecoveryPhase.FAILED_MEDIA_RECOVERY
  → isMediaUnavailable() / MediaUsabilityFact(failedMediaResidency=true)
  Field SET trigger (R3 T2): attempt_timeout → failureClass=MEMBERSHIP_CONVERGENCE_TIMEOUT
  (media already CONNECTED when residency entered)

CLEAR owner:
  RecoveryResidencyClearPolicy.clearFailedMediaResidencyPostObligation (ADR-0045)
  Gate: obligationClosed ∧ iceConnected ∧ receivePathLive
  Field CLEAR (R3 T3): OBLIGATION_DEADLINE → FAILED_MEDIA_RESIDENCY_CLEARED
  Field STUCK (R2): CLEAR_HELD e4_snapshot_unsatisfied receivePathLive=false

Presentation consumer:
  TalkViewModel.edgeMediaUnavailablePeer
  → MeetingPresenceDisplay / UserVisibleConnectivityProjection
  → Meeting pill "… degraded..." (ADR-0044 MEDIA_UNAVAILABLE+STABLE)
```

## Q1 / Q2 classification

| Q | Answer |
|---|--------|
| Who set true? | **Residency (class B/C boundary):** not raw ICE flip. Phase `FAILED_MEDIA_RECOVERY` after attempt timeout; can assert while `media=CONNECTED`. |
| Who clear false? | **Not ICE_CONNECTED alone.** Obligation must close first; then ADR-0045 admits clear on iceConnected **and** receivePathLive. |

`MediaUsabilityFact` also ORs `MediaState.RECONNECTING/FAILED`, but Round-3 T2 shows `media=CONNECTED` + `edgeRecoveryPhase=FAILED_MEDIA_RECOVERY` → residency bit is the active SET.

## Correlate (Round-3)

```text
22:03:25  MEDIA CONNECTED + recovering  → pill syncing
22:03:38  FAILED_MEDIA_RECOVERY (attempt_timeout / MEMBERSHIP_CONVERGENCE_TIMEOUT)
          mediaUnavailable=true, obligationOpen=true → pill degraded
22:04:08  OBLIGATION_DEADLINE → FAILED_MEDIA_RESIDENCY_CLEARED
          (iceConnected=true receivePathLive=true) → pill healthy
```

## Correlate (Round-2 stuck)

```text
FAILED_MEDIA_RECOVERY entered (attempt_timeout / MEMBERSHIP_CONVERGENCE_*)
OBLIGATION_DEADLINE closed
FAILED_MEDIA_RESIDENCY_CLEAR_HELD reason=e4_snapshot_unsatisfied receivePathLive=false
→ degraded persists (DEGRADED_STUCK)
```

## Implication (not a fix authorization)

Matches stale-window shape:

```text
Recovery / media path restored
        ↓
FAILED_MEDIA residency still asserts unavailable
        ↓
clear waits obligation close + E4 (incl. receivePathLive)
```

Possible future direction (design only): recovery evidence invalidate / admit residency clear earlier — **not** UI rewrite.  
**Do not** reopen WiFi / Phase-2 / ownership / ICE restart main chain as the primary gap.

## Scope freeze

```text
Remaining seam: FAILED_MEDIA residency SET vs ADR-0045 CLEAR admission
Not: presentation boolean bug, not WiFi recovery last-mile
```
