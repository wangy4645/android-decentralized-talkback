# RCA-003 IC — UVCP residency decoupling

**Status:** **CLOSED / FIELD VERIFIED**  
**PR:** [#157](https://github.com/wangy4645/android-decentralized-talkback/pull/157)  
**Case-3:** [rca-003-ic-uvcp-gray-adjudication-20260811-063722.md](./rca-003-ic-uvcp-gray-adjudication-20260811-063722.md)  
**Entry:** [rca-003-presentation-convergence-entry.md](./rca-003-presentation-convergence-entry.md)

## Freeze

```text
Root:   FAILED_MEDIA residency leaked into realtime availability projection
Fix:    UVCP consumes current MediaState availability only
Verify: Case-3 FIELD VERIFIED (CONNECTED + FAILED_MEDIA_RECOVERY → mediaUnavailable=false)
Status: CLOSED
```

Case 3 **does not** require `EDGE_RECOVERED`.

## Goal (achieved)

```text
FAILED_MEDIA ≠ DEGRADED
FAILED_MEDIA ≠ CURRENT_UNAVAILABLE
```

```text
Incident State     → diagnostics / history only
Current Availability → UVCP → Pill
```

## In scope (shipped)

| Change | Detail |
|--------|--------|
| `MediaUsabilityFact.currentUnavailable(mediaState)` | Live path only |
| `conferenceMediaUnavailable` | `currentUnavailable` only — no residency OR |
| Unit matrix + Case-3 field | PASS |

## Out of scope (remain out)

```text
Recovery / ICE / Phase-2 / clear predicate / timeout
Mesh peer unrecovered edges (separate track)
```

## ADR-0030

Interim note sufficient unless product asks for formal amendment:

```text
FAILED_MEDIA residency is diagnostic incident state.
It MUST NOT be interpreted as current media availability.
```
