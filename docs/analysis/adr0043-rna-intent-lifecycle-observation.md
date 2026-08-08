# ADR-0043 — RNA Intent Lifecycle Observation (Separate Track)

**Status:** **OBSERVATION ONLY** · **not ADR-0043 regression** · **not Field FAIL**  
**Date:** 2026-08-08  
**Source run:** `logs/adr0043-appendix-b-20260808-185802/`  
**Related:** Appendix B [adjudication](./adr0043-appendix-b-adjudication.md) (**PASS**) · [analysis](./rna-intent-lifecycle-observation-analysis.md) · [hypothesis](./rna-intent-lifecycle-hypothesis.md)

---

## Observation

During Appendix B passive observation, operator reported:

```text
M02 UI: M03 shows "syncing..." only
M01: appears ONLINE
```

Log evidence (M02 view of M03 edge):

```text
obligationState=SYNC_PENDING
stateReason=MEDIA_RECOVERED_BUT_INTENT_UNCOVERED
completionReason=DEFERRED_INTENT_UNCOVERED
edgePhase=REATTACH_ACCEPTED
mediaReady=true · controlReady=true · iceConnected=true
```

Duration: ~19:00:57 – 19:04:03 (log capture end).

---

## Interpretation

```text
Media recovered
        ↓
Control recovered
        ↓
Intent obligation uncovered
        ↓
SYNC_PENDING (presentation)
```

This is **not**:

```text
P1 evidence missing
invalid GROUP_RESYNC dispatch
ADR-0043 authorization bypass
NO_MEMBERSHIP_CONTEXT at handler
```

---

## Routing

| Phenomenon | Route |
|------------|-------|
| Seam I gate logs correct; recovery stalls on intent | RNA intent lifecycle observation |
| GROUP_RESYNC without PRESENT | ADR-0043 Seam I regression |
| Handler terminal on missing context | Seam II (deferred) |

**Do not modify** as ADR-0043 follow-up:

```text
P1 PRESENT predicate
O1 authorization rules
completion predicate
NO_MEMBERSHIP_CONTEXT handler semantics
```

---

## One-line statement

> Observation: M03 edge SYNC_PENDING with DEFERRED_INTENT_UNCOVERED during Appendix B — RNA recovery intent lifecycle gap, not ADR-0043 regression.
