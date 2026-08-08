# RNA Intent Lifecycle — Observation Analysis (Initial)

**Status:** **OBSERVATION ONLY** · **not ADR-0043** · **not Field FAIL** · **implementation NOT AUTHORIZED**  
**Date:** 2026-08-08  
**Source:** `logs/adr0043-appendix-b-20260808-185802/`  
**Parent:** [adr0043-rna-intent-lifecycle-observation.md](./adr0043-rna-intent-lifecycle-observation.md)  
**Related:** [recovery-edge-convergence-audit.md](./recovery-edge-convergence-audit.md) (B-0 `DEFERRED_INTENT_UNCOVERED` precedent)

---

## Question (this track only)

> After media and control recover on an edge, why does completion remain blocked on `DEFERRED_INTENT_UNCOVERED`?

**Not asking:**

```text
Was GROUP_RESYNC authorized?          → ADR-0043 (answered: yes)
Did membership context proof fail?    → ADR-0043 (answered: no)
Should we soften membership gate?      → out of scope
```

---

## Episode summary

```text
Session:   81d23632-d796-42b7-83de-605fbe2cc959
Observer:  M02 (conference host)
Edge:      M02 → M03
UI:        M03 "syncing..." (~19:00:57 – 19:04:03)
```

---

## Timeline (M02 view, edge=M03)

| Time | State | Key facts |
|------|-------|-----------|
| 19:00:44 | RECOVERING | `ICE_TRANSPORT_PENDING` · `uncoveredIntent=true` |
| 19:00:54.709 | RECOVERING | `uncoveredIntent=true` · membership epoch mismatch (`expected=4 observed=1`) |
| 19:00:56.764 | REATTACH_INBOUND | M03 rejoin received · ICE restart · attempt supersede 1→2 |
| 19:00:56.934 | Control reconciled | `membershipEpochConverged=true` after `DIGEST_REFRESH` (epoch 4→1) |
| 19:00:56.935 | **SYNC_PENDING** | `iceConnected=true` · `controlReconciled=true` · `topologySatisfied=true` · **`uncoveredIntent=true`** · `intentTerminal=NONE` |
| 19:00:56.935 | Completion blocked | `reason=DEFERRED_INTENT_UNCOVERED` · `decision=NO_ACTION` |

---

## Layer decomposition at stall point

```text
ICE transport:        ✅ CONNECTED
Media evidence:       ✅ satisfied
Control handshake:    ✅ reconciled
Topology:             ✅ satisfied
Membership epoch:     ✅ converged (post DIGEST_REFRESH)
Intent obligation:    ❌ uncovered (uncoveredIntent=true)
Intent terminal:      NONE
Completion candidate: WAITING
```

Presentation follows obligation:

```text
obligationState=SYNC_PENDING
stateReason=MEDIA_RECOVERED_BUT_INTENT_UNCOVERED
finalPresence=SYNCING
```

---

## Relationship to ADR-0043 (explicit separation)

ADR-0043 chain completed **before** SYNC_PENDING:

```text
19:00:56.813 QUERY_SENT → PRESENT → GROUP_RESYNC_REQUEST_SENT (M02)
19:00:54.611 GROUP_RESYNC_REQUEST_SENT (M03)
19:00:51.189 GROUP_RESYNC_HANDLER_ACCEPTED (M01)
```

Membership context authorization and GROUP_RESYNC dispatch are **not** the blocking layer at 19:00:56.935.

---

## Preliminary classification

| Hypothesis bucket | Evidence |
|-------------------|----------|
| Seam I / membership gate | **Ruled out** — P1+O1 chain PASS; handler accepted |
| Membership epoch lag | **Transient** — resolved by DIGEST_REFRESH before stall |
| Intent lifecycle gap | **Primary** — `uncoveredIntent=true` persists after L2 satisfied |
| Transport / ADR-0042 | **Not primary** — ICE connected; reattach delivered |

Aligns with Audit-B B-0 pattern:

```text
media + control restored
        ↓
DEFERRED_INTENT_UNCOVERED
        ↓
no EDGE_RECOVERED
```

---

## Open questions (not answered here)

```text
1. Which intent obligation remains uncovered on M02→M03 after reattach?
2. Why does intentTerminal stay NONE while uncoveredIntent=true?
3. Is a recovery negotiation offer/intent terminal expected but not produced?
4. Does this reproduce on directed RNA runs or only passive observation?
```

Answering these requires a **separate** authorization — not ADR-0043 field run.

---

## Routing

| Action | Route |
|--------|-------|
| Modify P1/O1/completion predicate | **Reject** — wrong track |
| Field run for recovery success | **Reject** — wrong metric |
| Directed RNA intent observation | **Candidate** — separate run card |
| ADR-0042 transport review | **COMPLETE** — see [adr0042-transport-truth-boundary-review.md](./adr0042-transport-truth-boundary-review.md) |

---

## One-line statement

> M03 SYNC_PENDING: media+control+membership converged; intent obligation uncovered — RNA lifecycle observation, independent of ADR-0043.
