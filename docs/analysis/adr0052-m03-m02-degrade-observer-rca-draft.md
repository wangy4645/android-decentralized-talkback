# ADR-0052 Field Observation — M03 Observer M02 Degrade (RCA Draft)

Status: **DRAFT / READ-ONLY** — not part of PR #174 / ADR-0052 Phase 2 scope.

Tracking: GitHub [#175](https://github.com/wangy4645/android-decentralized-talkback/issues/175) (merged baseline: PR #174 / `0ab08c1`).

## Observation

During Case B (READY-state WiFi flap on M02), M03 UI showed **M02 degraded** while M02 self-recovered the host (M01) edge.

Field log: `talkback/logs/adr0052-case-b-field-20260812-100404`

Session: `e114df61-ac98-4dd8-ba63-43fc7a49793a` · channel `CH-01` · 3 participants (M01 host, M02, M03).

## Why this is out of ADR-0052 scope

ADR-0052 targets **admission vs premature recovery collision** on participant accept.

Case B shows:

| Edge | Observer | Outcome |
|------|----------|---------|
| M02 → M01 (host) | M02 | RECOVERED @ 10:06:56 |
| M02 ↔ M03 (mesh) | M03 | ATTEMPT_TIMEOUT → FAILED_MEDIA_RECOVERY @ 10:07:06 |

M02→M01 success does not invalidate M03's local obligation on edge `(session, M02)`.

## Timeline (M03 primary)

```
10:06:48  MEDIA_LIFECYCLE module=M02 scope=CONFERENCE ice=DISCONNECTED
          RECOVERY_ATTEMPT_OPENED remote=M02 (M03 local obligation)
          conferenceDegraded=true

10:06:49  Meeting pill: M02 reconnecting... recovering=[M02]

10:07:06  RECOVERY_ATTEMPT_TIMEOUT edge=M02
          FAILED_MEDIA_RECOVERY
          Meeting pill: M02 degraded...
          finalPresence=DEGRADED (module=M02)
```

## Timeline (M02 cross-check)

```
10:06:49  CONFERENCE scope=M01 ice=DISCONNECTED
          RECOVERY_ATTEMPT_OPENED remote=M01
          RECOVERY_REATTACH_SEND_FAILED (WiFi down)

10:06:56  RECOVERY_COMPLETION candidate=RECOVERED edge=M01
          conferenceDegraded=false on M02
```

## Hypothesis (model gap)

**Edge-local recovery truth ≠ global conference topology truth**

```
       M01
      /   \
   M02 --- M03

M02: "I recovered host authority edge"
M03: "My mesh edge to M02 is still failing obligation"
```

Both may be locally correct without a convergence signal.

## Questions for follow-up RCA (do not patch in #174)

1. Is `ConferenceRecoveryEdgeState` visible only on the observing peer?
2. Does recovery success on one edge broadcast topology / roster epoch to invalidate stale obligations on other peers?
3. Does degraded projection have TTL or stale-obligation invalidation when remote participant self-heals?
4. When M02 recovers M01, should M03 receive digest / epoch bump to drop `(session,M02)` obligation?
5. Do `rosterEpoch` / mesh generation participate in cross-edge recovery convergence (CTA / topology digest track)?

## Non-goals (this RCA)

- No ADR-0052 admission gate changes
- No SDP role / setup attribute work
- No recovery throttle tuning
- No merge into PR #174

## Related tracks

- ADR-0040 obligation convergence (field CLOSED for flap case; different symptom)
- ADR-0045 failed-media residency / clear admission
- RCA-003 presentation / media-unavailable projection
- CTA / topology digest convergence (if applicable)

## Next step

Passive observation only until a dedicated ADR authorizes contract change for **cross-edge recovery projection convergence**.

Focus: topology digest / roster epoch propagation — not ICE collision or admission gate work. See #175.
