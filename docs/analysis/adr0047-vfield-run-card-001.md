# ADR-0047 V-field' Post-Merge Observation Run Card 001

**Status:** **AUTHORIZED** (post-merge passive observation) · **informational only** · **NOT a merge gate**  
**Date:** 2026-08-09  
**Parent ADR:** [0047-ordinary-recovery-post-defer-evaluability-contract.md](../adr/0047-ordinary-recovery-post-defer-evaluability-contract.md)  
**Merged:** PR #148 (`434313c`)  
**Desk gate:** V-desk' D1–D7 — `OrdinaryPostDeferEvaluabilityContractTest` (pre-merge PASS)

```text
Purpose:
  post-merge observational evidence for ordinary recovery
  post-defer evaluability attribution (ADR-0047)

Not purpose:
  Sync recovery validation
  terminal convergence validation
  media reconnect latency proof
  watchdog timeout behavior study
  amend ADR / reopen implementation / block future work
```

---

## 1. Observation objective

Verify only:

```text
ordinary recovery episode
        |
        v
post-defer evaluability attribution exists
```

Do **not** validate:

```text
✗ Sync disappears faster
✗ recovery succeeds faster
✗ media reconnect latency
✗ watchdog timeout behavior
✗ UI ONLINE timing
```

Success criterion (field):

```text
RECOVERY_ATTEMPT_OPENED
        +
intent bound
        +
defer-exit
        +
manifested
        =>
ordinary recovery obligation remained evaluable after defer
```

---

## 2. Observation entry conditions

**Allow (passive / natural):**

```text
ordinary recovery episode
        +
negotiation defer path (if present)
        +
defer-exit
```

**Do not manufacture:**

```text
✗ WiFi flap for evidence farming
✗ Directed #5
✗ FAILED_MEDIA injection
✗ successor admission (ADMIT_SUCCESSOR) episodes
✗ forced Sync UI transitions
```

---

## 3. Topology (field convention)

| Role | Module | Serial |
|------|--------|--------|
| Peer | M01 | `HTUBB21B09220661` |
| Host / observer | M02 | `2d73067a` |
| DUT / peer | M03 | `MDX0220416001963` |

SSID: **`happy`** only

---

## 4. Evidence matrix

| Phase | Expected evidence |
|-------|-------------------|
| **Open** | `ORDINARY_POST_DEFER_EVALUABILITY_INTENT_BOUND` |
| **Defer** | existing negotiation defer evidence (e.g. `deferredReason=NEGOTIATION_SETTLING`, `ICE_RESTART_DEFERRED`) |
| **Defer-exit** | defer-exit category identifiable (`deferExitCategory=` on manifest log, or `NEGOTIATION_BUDGET_EXHAUSTED` / `NEGOTIATION_INTENT_CLOSE_*`) |
| **Manifest** | `ORDINARY_POST_DEFER_EVALUABILITY_MANIFESTED` |
| **Residency** | `ORDINARY_EPISODE_EVALUABILITY_ARMED` / `_PENDING` / `_RETAINED` and/or `RECOVERY_WATCHDOG_STARTED` (existing clock connection — not timeout proof) |

**Episode correlation (K4'):** log lines must bind `obligationGen` + `attempt` for the same edge.

---

## 5. Pass conditions

```text
PASS (informational coverage):
  ≥1 natural ordinary episode with:
    INTENT_BOUND
    + MANIFESTED (≤ defer-exit class)
    + (EVALUABILITY_* OR existing attempt-clock armed post-manifest)
```

**Pass does not require:**

```text
SUCCESS / FAILED_MEDIA
Sync cleared
shorter Sync duration
membership convergence
```

---

## 6. Fail / anomaly record (narrow)

Record only when:

```text
obligationOpen=true
        +
post-defer phase
        +
missing ordinary attribution manifest
        on ordinary recovery episode
```

**Do not equate:**

```text
Sync stuck  =  ADR-0047 field fail
```

Routing reminder:

```text
SYNCING projection     → ADR-0044 (faithful while obligation open)
recovery attribution   → ADR-0047 (this card)
successor convergence  → ADR-0046 (sibling — separate)
```

---

## 7. ADR-0046 separation check (per sample)

Each field sample MUST confirm:

```text
successorTerminalConvergenceContractBound = false
        (no SUCCESSOR_TERMINAL_CONVERGENCE_CONTRACT_BOUND on same episode)
```

If `ADMIT_SUCCESSOR` / successor contract markers dominate → **not** an ADR-0047 V-field' sample; route to ADR-0046 track.

---

## 8. Observation disposition

```text
V-field':
  informational only

Does not:
  block merge (already merged)
  amend ADR-0047 normative text
  reopen Design / Runtime grill
  authorize new runtime workstream
```

Escalate only if:

```text
post-merge natural ordinary episode
  + intent bound at open
  + defer-exit occurred
  + manifest still absent (S4'-class hollow)
```

→ new evidence note; **no casual patch**; protected domains still require new ADR.

---

## 9. Log placement

```text
talkback/logs/adr0047-vfield-YYYYMMDD-HHMMSS/
```

Append disposition one-liner (e.g. `SAMPLE_DISPOSITION.txt`):

```text
sample_kind: ORDINARY_INTENT_MANIFEST | ORDINARY_HOLLOW_ANOMALY | NOT_0047_ORDINARY_PATH
session / edge / obligationGen / attempt
markers found (INTENT_BOUND / MANIFESTED / EVALUABILITY_*)
0046 orthogonality: successor contract absent? (Y/N)
```

---

## 10. Prior evidence (pre-merge Case B)

| LogDir | Role |
|--------|------|
| `talkback/logs/adr0046-vfield-20260809-112330/` | Pre-fix Case B — **NOT** post-merge compliance sample (`NOT_ADR0046_VFIELD_SAMPLE`) |

Archive: [vfield-case-b-20260809-112330-media-online-obligation-pending.md](./vfield-case-b-20260809-112330-media-online-obligation-pending.md)

Post-merge samples on builds **≥ PR #148** may corroborate attribution manifest on natural ordinary episodes.

---

## 11. Governance reminder

```text
ADR-0047 ACCEPTED
        |
        +-- Design C1' / DP-ACCEPT ✅
        |
        +-- Runtime Auth F1 ✅
        |
        +-- PR #148 MERGED (434313c) ✅
        |
        +-- V-desk' ✅ (pre-merge)
        |
        +-- V-field' ⏳ passive observation (this card)
        |
        +-- no new ADR / Design grill unless boundary violation
```
