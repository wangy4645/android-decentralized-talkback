# Recovery Last-mile Milestone v1 — freeze / soak adjudication

**Date:** 2026-08-10  
**Stage transition:** fault localization → **regression maintenance**  
**Authoritative field (impl):** `logs/conf-same-session-rejoin-20260810-182832/`  
**Authoritative soak (stability):** `logs/lastmile-soak-20260810-192332/`  
**Code baseline:** PR [#150](https://github.com/wangy4645/android-decentralized-talkback/pull/150) · tag `recovery-protocol-last-mile-v1`

## Frozen status (do not conflate)

```text
WiFi Recovery Protocol Chain      PASS
Recovery UX / Presentation       OPEN
Session Churn / Join Stability    OPEN
```

**Do not write:** `Recovery 全部 PASS` / `WiFi recovery solved` / `all related UX PASS`.

Protocol PASS means the observation chain is intact. It does **not** mean post-recovery user experience is closed.

## Milestone PASS (protocol only)

```text
Recovery Last-mile Milestone v1

PASS:
- short flap
- long flap
- delivery opportunity reacquisition
- multi-peer sequential flap
- media ownership handoff (supersede; REJECTED=0)
- conference same-session reconnect acceptance
- EDGE_RECOVERED terminal observed

Known unrelated (OPEN, do not fold back into recovery):
- join / session churn (e.g. M02 enter-meeting failures)
- roster projection (peer disappear from host UI)
- UI / presentation convergence (recovering sticky, syncing)
```

## Soak adjudication — `lastmile-soak-20260810-192332`

**Verdict:** Protocol chain **PASS** (with environmental session churn noise).  
**Do not reopen** INV-T3 / Phase-2 / RCA-001 / RCA-002 / same-session acceptance / completion predicate.

Observed in-meeting recovery markers (representative):

| Approx | Stimulus class | Chain |
|--------|----------------|-------|
| 19:24 | M02 short | OBTAINED → reconnect → EDGE_RECOVERED |
| 19:26 | M02 long / reacquire | EXPIRED → REACQUISITION → REEVALUATE → OBTAINED → reconnect |
| 19:28 | M02 | reacquire → OBTAINED → reconnect |
| 19:29 | M03 sequential | OBTAINED → reconnect → EDGE_RECOVERED |
| 19:32–19:34 | M02 consecutive short | reconnect + EDGE_RECOVERED (incl. reacquire) |
| 19:35 | M02 | OBTAINED → EDGE_RECOVERED |

Frozen-semantics checks:

```text
RECOVERY_MEDIA_OWNER_REJECTED = 0
OWNER_SUPERSEDED present (expected handoff)
same-session reconnect accepted present
no blind BUSY classification of rejoin as defect
```

Operator notes (parked — not protocol FAIL):

- M02 several times failed to enter meeting → Session Churn / Join Stability OPEN  
- M02 disappeared once from M01 UI → roster / presentation OPEN  
- M03 showed M01 sync once → presentation OPEN  

Mid-run leave/hangup/session restart occurred; soak “stay in one meeting” was imperfect. **In-conference flap recovery chains still closed.**

## Architecture gain — triage table

Previous risk: WiFi flap → unknown death point.  
Now each layer has an observation point. Future “WiFi blip, peer didn’t come back” routes as:

| Observation | Own |
|-------------|-----|
| no OBTAINED | Delivery |
| OBTAINED but no reconnect | Acceptance / session |
| reconnect but no EDGE_RECOVERED | Media / completion |
| EDGE_RECOVERED but UI recovering | Presentation (RCA-003) |
| member disappears | Roster / session churn |

## Problem rename (frozen)

```text
Was:  “Why does WiFi recovery fail?”
Now:  “After WiFi recovery succeeds, which upper-layer states fail to converge?”
```

These are different problems. **Do not edit the recovery chain to “fix” UX/join.**

## Next (ordered)

| Pri | Action | Track |
|-----|--------|-------|
| P0 | This memo + status freeze | **done by landing this doc** |
| P1 | RCA-003 Presentation Convergence after Recovery | independent; no recovery edits |
| P2 | Join churn observation (M02 leave / restart / join fail) | Session lifecycle; independent |

## Do not change

```text
WiFi listener · retry counts · ICE timeout · membership epoch
completion / RNA-5/6 predicates · blind BUSY→ACCEPT
EXPIRED⇒RETRY_REQUIRED · timeout budget enlargement
```

## Related

- [wifi-recovery-last-mile-freeze-20260810.md](./wifi-recovery-last-mile-freeze-20260810.md)  
- [wifi-recovery-protocol-last-mile-v1-soak-run-card.md](./wifi-recovery-protocol-last-mile-v1-soak-run-card.md)  
- [rca-003-presentation-convergence-entry.md](./rca-003-presentation-convergence-entry.md)  
- [rca-002-reattach-delivery-opportunity-reacquisition.md](./rca-002-reattach-delivery-opportunity-reacquisition.md)
