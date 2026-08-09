# ADR-0046 V-field' Post-Merge Observation Run Card

**Status:** **AUTHORIZED** (post-merge observation track) · **NOT a merge gate** · **no stimulus manufacturing**  
**Date:** 2026-08-09  
**Parent ADR:** [0046-successor-admission-terminal-convergence-contract.md](../adr/0046-successor-admission-terminal-convergence-contract.md)  
**Merged:** PR #146 (`a485e85`)  
**Desk gate:** V-desk' D1–D6 covered in `SuccessorObligationAdmissionTest` (pre-merge PASS)

```text
Purpose:
  observe real successor episodes for ADR-0046 compliance evidence
  (contract binding non-hollow / non-purely-external terminal-related facts)

Not purpose:
  manufacture SUCCESS / FAILED_MEDIA
  force WiFi drop for evidence
  reopen design / amend 0038 / 0045 / 0044
  absorb M03→M01 RECONNECTING
```

---

## Topology (same as prior successor observation)

| Role | Module | Serial |
|------|--------|--------|
| Peer | M01 | `HTUBB21B09220661` |
| Host / observer | M02 | `2d73067a` |
| DUT / peer | M03 | `MDX0220416001963` |

SSID: **`happy`** only

---

## What to record (passive / natural)

When a natural `ADMIT_SUCCESSOR` appears, capture log dir and mark:

| Marker | Meaning |
|--------|---------|
| `ADMIT_SUCCESSOR_OBLIGATION_EPISODE` | successor episode opened |
| `SUCCESSOR_TERMINAL_CONVERGENCE_CONTRACT_BOUND` | M1 binding present |
| `SUCCESSOR_EPISODE_EVALUABILITY_PENDING` / `_ARMED` / `_RETAINED` | R-M2 evaluability attribution |
| `NEGOTIATION_SETTLING` / `NEGOTIATION_BUDGET_EXHAUSTED` | defer path (if present) |
| `RECOVERY_WATCHDOG_STARTED` after negotiation close | evaluability resumed (Field #2 class) |
| `RECOVERY_EDGE_RECOVERED` / failed-media residency enter | non-purely-external terminal-related |
| `USER_LEAVE` / cancel only | external close — insufficient alone for S1' satisfaction |

**Pass for this track (coverage, not product UX):**

```text
≥1 natural episode with:
  CONTRACT_BOUND
  + (EVALUABILITY_* OR non-purely-external terminal-related fact)
```

**Do not fail** the track solely because SUCCESS/FAILED_MEDIA remain sparse.

---

## Stop / escalate

```text
Stop manufacturing stimuli
Stop if tempted to “fix Sync” via UVCP / clear / ICE auto-terminal
Escalate only if field shows contract bound but evaluability still hollow
  after negotiation close on successor-admitted episode
  → new evidence note; do not casual-patch
```

---

## Log placement

```text
talkback/logs/adr0046-vfield-YYYYMMDD-HHMMSS/
```

Append a one-line note under the log dir or in the observation parent:

```text
sample_kind: SUCCESSOR_BOUND | SETTLING_EVALUABLE | TERMINAL_RELATED | EXTERNAL_ONLY
session / edge / obligationGen / attempt
markers found
```

---

## Governance reminder

```text
PR #146 MERGED
        |
        +-- V-desk' ✅ (pre-merge)
        |
        +-- V-field' ⏳ passive observation (this card)
        |
        +-- no automatic new runtime workstream
```

---

## Side observation (NOT compliance)

| LogDir | Disposition |
|--------|-------------|
| `talkback/logs/adr0046-vfield-20260809-112330/` | **Not** ADR-0046 V-field sample |

Episode was ordinary `ICE_DISCONNECTED` / `UPSERT_EDGE` recovery with media ONLINE + obligation pending (Case B Sync). Routed to **ADR-0047** (not 0046 compliance):

- [vfield-case-b-20260809-112330-media-online-obligation-pending.md](./vfield-case-b-20260809-112330-media-online-obligation-pending.md)
- [0047-ordinary-recovery-post-defer-evaluability-contract.md](../adr/0047-ordinary-recovery-post-defer-evaluability-contract.md) — **ACCEPTED** (A1)

```text
Same UI symptom (Sync) ≠ same lifecycle.
ADMIT_SUCCESSOR long Sync  → ADR-0046 track
Ordinary edge residency    → ADR-0047 track (Case B evidence)
```
