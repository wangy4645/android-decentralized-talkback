# Replacement Cutover RC1 — Field Run Card

**Status:** **CLOSED / FIELD-PROVEN** (2026-09-10)  
**Closure:** `replacement-cutover-rc1-field-closure.md`  
**Evidence:** `talkback/logs/replacement-cutover-rc1-field-20260910-140843/`  
**ADR:** `0062-replacement-cutover-rc1-audible-ownership-transition.md`  
**Next:** `meeting-product-controlled-pilot-run-1.md`

---

## Windows (only three)

### A — Anchor owner (baseline)

```text
pilot flag OFF or pre-cutover
ADR-0056 audible active when floor/media policy allows
multicast shadow metrics only (no production AudioTrack)
evidence: audioTrackOwner=ANCHOR or SHADOW_METRICS_ONLY
```

### B — Cutover

```text
arm → execute handoff
exactly-one-owner invariant PASS
multicast production AudioTrack owner
media continues (playout writes > 0)
evidence: AUDIBLE_OWNERSHIP_STATE=MULTICAST_ACTIVE
          OWNER_VERIFY=PASS
```

### C — Rollback

```text
manual rollback (or deterministic trigger)
multicast loses production ownership
ADR-0056 reacquires audible ownership
media recovers
no dual owner · no zombie AudioTrack
evidence: AUDIBLE_OWNERSHIP_STATE=ANCHOR_ACTIVE
          OWNER_VERIFY=PASS
```

---

## Verdict routing

| Verdict | Meaning |
|---------|---------|
| `PASS_RC1` | A + B + C satisfied |
| `FAIL_RC1` | Handoff or invariant failure |
| `INVALID_PREREQ` | Phase A / shadow session not operational |

PASS → `READY_FOR_CONTROLLED_PILOT` (not ADR-0056 DELETE · not N=9 READY).

---

## Log tags

```text
REPLACEMENT_CUTOVER_RC1
AUDIBLE_OWNERSHIP_*
OWNER_VERIFY
```
