# WiFi recovery last-mile freeze — sign-off

**Date:** 2026-08-10  
**Impl evidence:** `logs/conf-same-session-rejoin-20260810-182832/`  
**Soak evidence:** `logs/lastmile-soak-20260810-192332/`  
**Milestone freeze:** [recovery-lastmile-milestone-v1-freeze.md](./recovery-lastmile-milestone-v1-freeze.md)  
**PR:** [#150](https://github.com/wangy4645/android-decentralized-talkback/pull/150)  
**Tag:** `recovery-protocol-last-mile-v1`

```text
Progress (INV-T3)                              CLOSED
Phase-2 Delivery (RRA-005)                     VERIFIED
Media Action Ownership (RCA-001)               VERIFIED
RCA-002 Delivery Opportunity Reacquisition     FIELD VERIFIED
Conference same-session rejoin acceptance      FIELD VERIFIED
Soak (protocol chain)                          PASS
```

## Frozen status (authoritative)

```text
WiFi Recovery Protocol Chain      PASS
Recovery UX / Presentation       OPEN
Session Churn / Join Stability    OPEN
```

**Do not write:** `Recovery 全部 PASS`.

## Completeness wording

```text
protocol last-mile v1 COMPLETE
stage: fault localization → regression maintenance
presentation / join / roster     ORTHOGONAL OPEN
```

`Architecture Closed ≠ Behavior Exhausted.`  
Protocol PASS ≠ post-recovery UX PASS.

## Field chain (authoritative)

```text
ARMED → EXPIRED → REACQUISITION_ELIGIBLE → REEVALUATE
  → second ARMED → OBTAINED
  → Conference rejoin invite
  → Conference invite reconnect accepted
  → GROUP_ACCEPT_HANDOFF path=RECONNECT
  → EDGE_RECOVERED
```

## Do not reopen

- INV-T3 / Phase-2 facade ownership
- Ownership supersede (`PARTICIPANT_REATTACH` ← `HOST_RESTART`)
- Blind `BUSY→ACCEPT`
- Global reattach retry / `EXPIRED⇒RETRY_REQUIRED`
- WiFi listener / ICE timeout / membership epoch / completion predicate

## Next

1. ~~Merge #150~~ · ~~bounded soak~~ — **done**  
2. **RCA-003** Presentation Convergence after Recovery — [rca-003-presentation-convergence-entry.md](./rca-003-presentation-convergence-entry.md)  
3. Join churn — independent session-lifecycle observation (not recovery)

## Docs

- [recovery-lastmile-milestone-v1-freeze.md](./recovery-lastmile-milestone-v1-freeze.md)
- `docs/analysis/rca-002-reattach-delivery-opportunity-reacquisition.md`
- `docs/analysis/conference-same-session-rejoin-acceptance-patch.md`
- `docs/analysis/rca-001-post-receipt-completion-attribution-entry.md`
- `docs/adr/0042-recovery-reattach-transport-delivery-semantics.md` (INV-T3-SCHEDULE status sync)
