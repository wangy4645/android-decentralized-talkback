# ADR-0056 Phase 1b COMPLETE

## Status

**PHASE 1b — COMPLETE / PASS** (2026-08-18)

**Scope of PASS:** contract + implementation + unit/integration evidence.

**Not claimed:** field / 4p system-level PASS.

**Parent:** [Phase 1b mapping](./0056-phase-1b-contract-mapping.md) · [F1–F8](./0056-phase-1b-contract-fixtures.md) · [1b-6 consistency](./0056-phase-1b-6-consistency-contract.md) · [ADR-0056](../adr/0056-conference-topology-first.md)

---

## What PASS means

Phase 1b proves the **Conference topology authority boundary is closed**.

```text
Roster
  ↓
AnchorAdmissionPolicy
  ↓
TopologyMode
  ↓
ActualMediaEdgeSet
  ↓
ConferenceTopologySnapshot
  ↓
Presence / CPP / UI / Diagnostic
```

Reverse paths remain sealed:

```text
ICE ───────────────X→ Topology
CPP ───────────────X→ Topology
Presence ──────────X→ Topology
Diagnostic ────────X→ Topology
Recovery ──────────X→ Topology   (FROZEN)
```

---

## Acceptance matrix

| Layer | Result |
| ----- | ------ |
| F1–F8 Snapshot construction/admission | PASS |
| G1–G8 Anchor admission decision | PASS |
| M1–M8 ActualMediaEdgeSet admission | PASS |
| T1–T8 MESH/ANCHOR transition | PASS |
| S1–S8 downstream consistency | PASS |
| 1b-1 Authority facade | PASS |
| 1b-2 Read binding | PASS |
| 1b-3 Anchor admission | PASS |
| 1b-4 Media edge admission | PASS |
| 1b-5 Mode transition | PASS |
| 1b-6 Consistency | PASS |
| Authority tests | PASS |
| CPP/UI 24/24 | PASS |
| Recovery | FROZEN |
| Bus | FROZEN |
| Health | FROZEN |
| Field | FROZEN |

---

## Static single-path inspection (re-check on Coordinator topology edits)

```text
Phase 1b static inspection
- single snapshot publish owner: PASS
    ConferenceTopologyAuthority is the only publish owner
    Coordinator only orchestrates applyConferenceTopologyAdmission → authority.publish*
- no second topology construction path: PASS
    ConferenceTopologySnapshot(...) only in ConferenceTopologyContract / ModeTransitionContract
    Conference applyGroupTopology delegates to applyConferenceTopologyAdmission
- no ICE → ActualMediaEdgeSet mutation: PASS
    rejectIceDrivenAdmission remains; ICE is observation only
- no downstream topology mutation: PASS
    bindDownstreamView is read-only; Presence/CPP/UI/Diagnostic consume bound fields
- no host-as-authority fallback: PASS (Conference path)
    candidate ≠ host fallback; GROUP-only localModuleId fallback is out of Conference 1b
- no MESH peer-pair edge admission: PASS
    MESH actualMediaEdges = ∅; MESH CPP stays MESH_SESSION_OBSERVATION
```

---

## Formal door

```text
PHASE 1b — COMPLETE / PASS

Contract       PASS
Implementation PASS
Regression     PASS
Static Gate    PASS

Phase 2        NOT STARTED
Recovery       FROZEN
Bus            FROZEN
Health         FROZEN
Field          FROZEN
```

---

## Phase 2 entry (NOT STARTED)

Do **not** wire existing Recovery onto snapshot as a first cut.

Phase 2 first freeze:

```text
ConferenceTopologySnapshot
        ↓
ActualMediaEdgeSet(currentGeneration)
        ↓
RecoveryEdgeProvider
        ↓
AdmittedRecoveryTarget
        ↓
ConferenceEdgeKey / Recovery obligation
```

Forbidden:

```text
roster → RecoveryEdgeSet
```

This is ADR-0056 finding C. Phase 1b = topology truth. Phase 2 = recovery binding.
