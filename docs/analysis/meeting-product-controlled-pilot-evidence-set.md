# Controlled Pilot — Minimum Evidence Set

**Status:** **CLOSED / 3× FIELD-PROVEN** (2026-09-10)  
**Nature:** Product ops evidence under pilot flag — **not** a continuing gate chain.

```text
Controlled Pilot
  Run-1                     PASS
  Run-2                     PASS
  Run-3                     PASS
  Minimum Evidence Set      CLOSED / 3× FIELD-PROVEN

Controlled-pilot readiness  100%
Controlled pilot evidence   PASS
ADR-0056                    KEEP / rollback path
mandatoryRollback           false
```

**Do not extend:** Run-4/5/10 at identical scope — marginal evidence only. Next work is Scale/Deletion track (`meeting-product-ga-adr0056-retirement-gates.md`).

---

## Evidence quality note

Run-1 initial attempt (`…-144645`) exposed a **real lifecycle defect** (Meeting end → `REPLACEMENT_RUNTIME_FATAL` mis-route). Fix: `onSessionTeardown` — release multicast without Anchor reacquire. **Post-fix 3/3 consecutive PASS** — higher quality than three green runs without a found defect.

---

## Authoritative runs

| Run | Log dir | Session | cutover | multicastDur | ownershipViol | fatal | teardown | verdict |
|-----|---------|---------|---------|--------------|---------------|-------|----------|---------|
| 1 | `talkback/logs/controlled-pilot-run1-field-20260910-145924` | `04d95658-4370-4d70-9aab-b7672a4ddb8f` | SUCCESS | 82s | 0 | 0 | true | PASS |
| 2 | `talkback/logs/controlled-pilot-run1-field-20260910-150808` | `b3b0a661-937d-406c-8b24-ebb93eb9bc1e` | SUCCESS | 77s | 0 | 0 | true | PASS |
| 3 | `talkback/logs/controlled-pilot-run1-field-20260910-151648` | `67343bd4-9396-4222-8ad5-de84d44e4425` | SUCCESS | 77s | 0 | 0 | true | PASS |

**Excluded (defect RCA, superseded):** `…-144645` — teardown mis-route; fixed before authoritative set.

---

## Flow proven (each PASS run)

```text
Meeting start → replacement healthy → controlled cutover
→ multicast production audible (~60s use) → Meeting end → TEARDOWN_EMPTY
```

No mandatory rollback. No dual owner. No unexpected auto-rollback.

---

## Soft telemetry (observe only — not architecture FAIL)

```text
jitterHits≈480–510   log-token hits — NOT “480 jitter failures”
plcHits=0            underrunHits=0   no audible/runtime failure correlated
```

Define interpretable jitter/PLC metrics in **G3 Stability** track — do not reopen media algorithm work from token counts alone.

---

## Lifecycle defect (closed)

| Item | Detail |
|------|--------|
| Symptom | Meeting end triggered `REPLACEMENT_RUNTIME_FATAL` rollback; `anchor_reacquire_failed` |
| Root cause | `onConferenceSessionStopped` called `onReplacementRuntimeFatal` instead of session teardown release |
| Fix | `AudibleOwnershipController.onSessionTeardown` + `ReplacementCutoverRc1.onSessionTeardown` |
| Verification | Unit test `sessionTeardown_releasesMulticastWithoutAnchorReacquire` · 3× field PASS |

---

## References

- Ops card: `meeting-product-controlled-pilot-run-1.md`
- Wrapper: `talkback/scripts/controlled-pilot-run1-field-first-run.ps1`
- Adjudicator: `talkback/scripts/controlled-pilot-run1-field-adjudicate.ps1`
- Next: `meeting-product-ga-adr0056-retirement-gates.md`
