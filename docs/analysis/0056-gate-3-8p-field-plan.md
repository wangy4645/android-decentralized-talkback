# ADR-0056 Gate 3 — 8p Field Plan

## Status

**PLAN FROZEN / APPROVED SCOPE** (2026-08-18)
**Field execution AUTHORIZED** (2026-08-18) · **N=8 ENV_INVALID** (lab cannot run 8 concurrent)
**Architecture / contract change FORBIDDEN**

**Parent:** [ADR-0056](../adr/0056-conference-topology-first.md) Gate 3 · [Capacity CLOSED](./0056-capacity-gate-closeout.md) · [Phase 3 UI CLOSED](./0056-phase-3-health-ui-closeout.md)

**Prerequisite:** Phase 1b–2-5 + Capacity + Phase 3 UI **unit/impl PASS**. This plan does **not** reopen those contracts.

---

## Purpose

Verify the **already frozen** architecture on **8 real devices**. Observe; do not invent edges or rewrite topology from a single log.

```text
N=8 cold start
    ↓
ANCHOR admission
    ↓
|ActualMediaEdgeSet| == 7
    ↓
RecoveryTarget ⊆ star (O(N))
    ↓
Health / UI: MEDIA_USABLE → ONLINE
```

Not: performance optimization. Not: Gate 4 (10p). Not: WiFi recovery main chain.

---

## Five layers (ADR field gate)

Every scenario records all five. Fail is **observation vs frozen contract**, not a new contract.

| Layer | Pass signal (existing invariants) |
| ----- | --------------------------------- |
| Topology | `topologyMode=ANCHOR`; edges=7; no MESH fallback; no peer-pair |
| Media | admitted star ICE/media facts only |
| Recovery | TEL-1/2: targets ⊆ ActualMediaEdgeSet; count O(N) not N×(N−1) |
| Health/UI | TEL-6: usable admitted media → ONLINE; obligation alone ≠ room SYNCING |
| Resource | log CPU/memory/temp/latency **observe only** (1→4→6→8 if captured); no budget change |

---

## Scenarios (authorized when field is signed)

| ID | What | Pass |
| -- | ---- | ---- |
| G3-S1 | 8p cold start, same channel | ANCHOR; 7 edges; no MESH |
| G3-S2 | Steady ≥90s, 1–2 speakers | Health/UI ONLINE when media usable |
| G3-S3 | 1-node ICE flap (not WiFi recovery campaign) | recovery O(N); room not SYNCING if media still usable |
| G3-S4 | Join/leave one participant (N=8↔7) | star remains N−1; no peer-pair |
| G3-S5 | Optional 2-node flap | no recovery storm (TEL-5); **do not** treat as Directed #5 |

ADR also lists resource curve 1→4→6→8. Capture if hardware allows; **not** required to change architecture. Missing curve ≠ topology FAIL.

---

## Environment

**SSID：不检查、不作门条件。**

**当前实验室（2026-08-18 说明）：**

```text
终端总数 = 4
同时在线 ≤ 3
  M01  常在
  M03  常在
  M02 XOR M04   USB/ADB 互斥，只能连一台
```

已知 serial：

```text
M01  HTUBB21B09220661
M02  2d73067a
M03  MDX0220416001963
M04  现场记录
```

```text
N=8 concurrent  = ENV_INVALID
3p/4p 观察     ≠ Gate 3 Field PASS
```

Forbidden during this gate:

```text
USER_LEAVE all / membership rewrite as topology fix
WiFi recovery main chain / RNA / completion / Directed #5
ADR-0055 Track P/O
lowering threshold / MESH at N=8
patching ActualMediaEdgeSet from field
```

---

## Evidence

```text
logs/gate3-8p-<timestamp>/
  ADJUDICATION.txt
  M01.log … M08.log
```

`ADJUDICATION.txt` **must** start with:

```text
Gate: ADR-0056 Gate 3
Mode: Observation only
Architecture changes: forbidden
Contract changes: forbidden
```

Then roster (8 × moduleId/serial). Then only:

| Question | Evidence |
| -------- | -------- |
| 是否 ANCHOR | topology snapshot |
| edge 是否 7 | ActualMediaEdgeSet |
| recovery 是否 topology bound | RecoveryTarget |
| UI 是否 ONLINE | Health/UI projection |
| 是否资源异常 | observation |

Do not enter fix mode from a field phenomenon.

Grep (existing tags only):

```text
CONFERENCE_TOPOLOGY_SNAPSHOT
CONFERENCE_HEALTH
CONFERENCE_PRESENCE_PROJECTION
```

Adjudicate against K3/K4/K6 and U1/U3/U6. **Do not** add new K/U fixtures from this run.

---

## Exit

```text
G3-S1…S4 PASS (S5 if run)
TEL-1, TEL-2, TEL-6 PASS
no architecture delta
        ↓
Gate 3 Field PASS
        ↓
Gate 4 Field Plan may be authorized separately
```

```text
FAIL / INCONCLUSIVE
        ↓
collect evidence
        ↓
targeted ADR only if a frozen invariant is violated
        ↓
DO NOT rewrite topology contract from one log
```

---

## Door

```text
Phase 3 UI                 CLOSED
Gate 3 8p Field Plan       FROZEN / APPROVED SCOPE
Gate 3 Field execution     AUTHORIZED
Gate 3 Field               ENV_INVALID (N=8 not possible)
Gate 4                     NOT AUTHORIZED
R28 / RNA / Completion     FROZEN
```
