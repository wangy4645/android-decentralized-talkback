# V-field Observation Case B-20260809-112330

**Title:** Media ONLINE but Recovery Obligation Pending  
**Status:** **ARCHIVED** (observation only) · **NOT** ADR-0046 compliance sample  
**Date:** 2026-08-09  
**LogDir:** `talkback/logs/adr0046-vfield-20260809-112330/`  
**Collected under:** ADR-0046 V-field' collector window (misnomer for this episode — see §ADR-0046)  
**Stop note:** `talkback/logs/adr0046-vfield-20260809-112330/FIELD_STOP_NOTE.txt`

```text
Case: Media restored + Recovery obligation residency

结论:
UI Sync 持续的直接原因 = obligationOpen=true
不是 media connectivity failure
不是 ADR-0046 successor admission failure
```

---

## Classification

| Axis | Value |
|------|--------|
| ADR-0046 | **Reference only / Not applicable** |
| Recovery lifecycle | **Observation** |
| Implementation impact | **None from this observation alone** |
| Projection | ADR-0044 recovering / obligation → `SYNCING` (faithful) |

---

## Topology

| Role | Module | Serial |
|------|--------|--------|
| Observer (Sync stuck) | M01 | `HTUBB21B09220661` |
| Authority / OK view | M02 | `2d73067a` |
| Peer edge | M03 | `MDX0220416001963` |

SSID: **`happy`** only · session `0b76a23b-41f1-407f-8a77-174450bdd5c2`

---

## Locked timeline (M01 → M03)

| Time | Event | Semantics |
|------|--------|-----------|
| 11:28:02 | `RECOVERY_ATTEMPT_OPENED` / `UPSERT_EDGE` / `ICE_DISCONNECTED` | Ordinary recovery edge opens |
| 11:28:05 | `RECOVERY_WATCHDOG_DEFERRED` / `NEGOTIATION_SETTLING` | Obligation exists; attempt clock deferred |
| 11:28:13 | `mediaRestored=true` / ICE CONNECTED | Media plane restored |
| 11:28:13+ | `RECOVERY_CONTROL_PLANE_REQUIRED` | Completion still on control-plane obligation |
| 11:28:15 | `NEGOTIATION_BUDGET_EXHAUSTED` / intent SUPERSEDE | Negotiation intent ends; **no** successor evaluability re-arm |
| 11:28:35 | `RECOVERY_ATTEMPT_OWNERSHIP_LOST` / `DIAGNOSTIC_ONLY` | Diagnostic; does **not** close obligation |
| 11:30:47 | `RECOVERY_OBLIGATION_CLOSED` / `MEMBERSHIP_LEFT` | Obligation ends; Sync clears |

**Duration:** ~2.5 min `SYNCING` after media CONNECTED.

**Contrast:** M02 projected M03 `ONLINE` (`obligationOpen=false`) while M01 still `SYNCING`.

---

## Causal chain (frozen)

```text
ICE CONNECTED
      ≠
Recovery obligation completed
      ≠
finalPresence ONLINE
```

Projection observed:

```text
Media plane:        CONNECTED
Control/obligation: PENDING (RECOVERY_PENDING, obligationOpen=true)
Projection:         SYNCING
```

Not a UVCP bug; ADR-0044 semantics.

---

## ADR-0046 attribution

**Entry path this episode:**

```text
ICE_DISCONNECTED
        |
        v
RECOVERY_ATTEMPT_OPENED
        |
        v
UPSERT_EDGE
```

**Not:**

```text
ADMIT_SUCCESSOR
        |
        v
successor obligation episode
```

| Question | Verdict |
|----------|---------|
| ADR-0046 contract missing? | **No evidence** (path N/A) |
| ADR-0046 runtime failed? | **N/A** |
| Usable as 0046 V-field sample? | **No** |
| Worth recording? | **Yes** (side observation) |

Markers **absent** on this edge (expected for non-successor):  
`SUCCESSOR_TERMINAL_CONVERGENCE_CONTRACT_BOUND` · `SUCCESSOR_EPISODE_EVALUABILITY_*`

Also absent (material to residency): `RECOVERY_WATCHDOG_STARTED` / `RECOVERY_ATTEMPT_TIMEOUT` / SUCCESS close.

---

## What this reinforces

> **Media recovery and obligation convergence are distinct lifecycles.**

Governance boundary clarified by same UI symptom:

| If field shows… | Route |
|-----------------|--------|
| `ADMIT_SUCCESSOR` → long Sync | ADR-0046 contract / evaluability |
| Ordinary recovery edge → obligation residency (this case) | **Separate track** — not 0046 |

Do **not** write: Recovery broken · Watchdog bug confirmed · fix recovery timeout · fold into ADR-0046.

---

## Implementation posture

```text
This observation alone:
  NO predicate change
  NO completion admission change
  NO ADR-0046 amend
  NO Directed #5
  NO UVCP / media-as-authority shortcut
```

Further work requires a **new candidate / grill** (or explicitly authorized revisit of ordinary-edge post-defer evaluability / control-plane completion), not casual patch from this note.

**ADR-0047 ACCEPTED** · **Impl-Auth I1** (Planning/Design): [0047-ordinary-recovery-post-defer-evaluability-contract.md](../adr/0047-ordinary-recovery-post-defer-evaluability-contract.md) · [adr0047-implementation-proposal-entry.md](../analysis/adr0047-implementation-proposal-entry.md)
