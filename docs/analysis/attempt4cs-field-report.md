# Attempt-4c-S Field Report (Case S-A)

**Status:** PASS (Case S-A)  
**Date:** 2026-08-03  
**Artifact log:** `logs/phase3c-b-attempt4cs-20260803-155535`  
**Harness PR:** #109 (`SUPPRESS_SUCCESSOR_ATTEMPT`)  
**APK SHA256:** `bcc5d1a80ce0071932c33615cc2313166317c6a0db166ce1644f507087427bbe`

## Normative reading (ADR discipline)

This run is **not** "recovery success."

> Under an explicitly applied harness suppression of successor admission,
> the old lineage terminates by its own obligation lifecycle, and the system
> does **not** emit successor adoption or completion-as-recovered facts.

## Condition

```text
SUPPRESS_SUCCESSOR_ATTEMPT applied
topologyMode = EXERCISE_SUPPRESSED_SUCCESSOR
no D1
no PR52C release path
no R4-impl
```

Protocol used (round that produced S-A):

```text
ARM
  -> WiFi OFF + HOLD until OBLIGATION_DEADLINE / CLOSED
  -> WiFi ON
  -> collect APPLIED / ADMIT / RECOVERED / ADOPTION
```

## Observed

| Dimension | Evidence |
|-----------|----------|
| Harness | `SUPPRESS_SUCCESSOR_ATTEMPT_APPLIED` + `HARNESS_SUCCESSOR_SUPPRESSION_APPLIED` (`namespace=HARNESS_ONLY`) |
| Successor admission | `ADMIT_SUCCESSOR = 0` |
| Old lineage | `OBLIGATION_DEADLINE` -> `RECOVERY_OBLIGATION_CLOSED` |
| Completion | `RECOVERED` absent |
| Adoption | `ADOPTED` / `TRANSFERRED` absent |

Causal seam (field):

```text
OBLIGATION_DEADLINE / CLOSED
        |
        v
REMOTE_MODULE_RECOVERED
        |
        v
suppress_successor_attempt
        |
        v
(no ADMIT_SUCCESSOR_OBLIGATION_EPISODE)
```

## Conclusion

```text
R3 lifecycle valid under successor suppression
SUPPRESS seam intercepts before admitSuccessorObligationEpisode
R4-def remains uncontaminated (no ADOPTED / TRANSFERRED)
```

## Explicit non-conclusions

- Does **not** prove Joint coexistence
- Does **not** close E.18 (Case-C may still be present; leave OPEN)
- Does **not** authorize R4-impl
- Does **not** mean media/user recovery "worked"

## Contrast baseline

| Run | Condition | Role |
|-----|-----------|------|
| Attempt-4c baseline | successor allowed (no SUPPRESS) | natural topology / observation vocabulary |
| Attempt-4c-S | successor suppressed | no-adoption world; R4-impl contrast baseline |

## Pointers

- Classifier: `logs/phase3c-b-attempt4cs-20260803-155535/ATTEMPT4CS_SUPPRESS_CLASSIFICATION.txt`
- Contract: `logs/phase3c-b-attempt4cs-20260803-155535/ATTEMPT4CS_CONTRACT.txt`
- Auth stream: `logs/phase3c-b-attempt4cs-20260803-155535/auth-stream.log`
- Runner: `scripts/run-attempt4cs-suppress.ps1` (OFF-HOLD-UNTIL-CLOSED protocol)