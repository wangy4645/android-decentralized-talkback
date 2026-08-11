# Session Churn / Join Stability Observation

**Status:** OPEN (observation track)  
**Branch:** `docs/join-stability-m02-observation`  
**Run card:** `docs/analysis/join-stability-m02-observation-run-card.md`

---

## Scope

- Membership convergence under churn
- Anchor stability across join/leave cycles
- Group join / sync channel / member missing / join timeout

## Not in scope

- WiFi recovery (architecture CLOSED; Appendix B passive only)
- ADR-0050 negotiation ingress (VERIFIED)
- UVCP / presentation convergence
- PTT UI product surface (see `phase32-changelog.md` — PTT UI V1 freeze)

---

## Routing

| Symptom | Track |
|---------|-------|
| `sync channel` / join timeout / member missing | **This track** (Group Stability) |
| recovery completion / obligation / ICE restart ingress | Recovery / ADR closed tracks |
| meeting admission / instance bind | Conference admission observation (separate scripts) |

Do not reopen recovery investigation for join-stability symptoms without new ADR boundary violation evidence.
