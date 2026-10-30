# ADR-0056 Phase 2-5 — Conference Health Projection Contract (H1–H8)

## Status

**CONTRACT REVIEW** (2026-08-18) · H1–H8 fixtures · **Phase 2-5 Impl NOT AUTHORIZED**

**Parent:** [ADR-0056](../adr/0056-conference-topology-first.md) Decision 5 · [Phase 2-0 binding](./0056-phase-2-0-recovery-binding-contract.md) · [Phase 2-4 transition](./0056-phase-2-4-authorized-transition-contract.md)

**Prerequisite:** Phase 2-4 Implementation PASS

**Purpose:** Freeze how topology / media / recovery **facts** map to ConferenceHealth, without Health becoming a topology or recovery authority.

**Exit:** H1–H8 unit PASS → Phase 2-5 Contract PASS → Implementation NOT until signed

---

## 1. Fact sources (frozen)

```text
Topology → topology fact     (ConferenceTopologySnapshot / ActualMediaEdgeSet)
Media    → media fact        (usability observation on an admitted edge)
Recovery → recovery progress (in-flight / success / failure — input fact only)
Health   → aggregate projection (read-only)
```

Forbidden:

```text
Health → guess topology
Health → guess recovery eligibility
Health → mutate ActualMediaEdgeSet
Health → produce AdmittedRecoveryTarget
Recovery Controller state → infer media topology
```

INV-056-14 / Decision 5:

```text
MEDIA_USABLE = true  →  user-facing ONLINE
obligationOpen / recovery in-flight alone MUST NOT block ONLINE
when media is actually usable on an admitted edge
```

```text
MEDIA_USABLE requires topology admission valid
```

---

## 2. Projection chain

```text
ActualMediaEdgeSet
        ↓
Media usability observation (admitted edges only)
        ↓
Recovery progress (diagnostic input)
        ↓
ConferenceHealthProjection
```

Health does **not** call `RecoveryEdgeProvider` to decide topology. Eligibility remains Provider-owned.

Recovery progress is a **passed-in fact**, not a Controller read inside the projector.

---

## 3. ANCHOR vs MESH

```text
ANCHOR
  MEDIA_USABLE iff
    ∃ e ∈ ActualMediaEdgeSet : media observation says usable
  reconnecting observation does not remove e from ActualMediaEdgeSet

MESH
  ActualMediaEdgeSet = ∅
  MUST NOT apply ANCHOR star health
  health.anchorHealthModel = false
```

---

## 4. Fixtures H1–H8

Shared: `members4`, M01-star as in Phase 1b/2-0.

### H1 — admitted media usable → MEDIA_USABLE

ANCHOR snapshot, observation `M01→M02` usable.  
`projection.mediaUsable == true`.

### H2 — no usable admitted edge → not MEDIA_USABLE

ANCHOR snapshot, all admitted edges `usable=false` (or no usable obs).  
`mediaUsable == false`.

### H3 — reconnecting ≠ topology edge gone

`M01→M02` reconnecting.  
`M01→M02 ∈ snapshot.actualMediaEdges` after project.  
`topologyUnchanged`.

### H4 — recovery in-flight does not mutate topology

`recovery.inFlight = true`.  
Input snapshot == snapshot after project.  
`mediaUsable` follows media observation, not in-flight bit (INV-056-14).

### H5 — recovery success → health may recover

After success fact + usable observation: `mediaUsable == true`.  
Topology edges unchanged.

### H6 — recovery failure reflects media, not topology rewrite

`recovery.failed = true`, media not usable.  
`mediaUsable == false`.  
Failed edge **still** in `ActualMediaEdgeSet`.

### H7 — MESH must not use ANCHOR health

MESH snapshot, empty edges.  
`anchorHealthModel == false`.  
`mediaUsable` is not derived from a fabricated M01-star.

### H8 — Health is read-only

Project does not mutate snapshot.  
Does not emit `AdmittedRecoveryTarget`.  
Does not add/remove `actualMediaEdges`.

---

## 5. Explicit non-scope

```text
ConferenceHealth UI / ADR-0055
UVCP rewrite
R28 / RNA / completion
RecoveryEdgeProvider changes
Field
Coordinator Health wiring
```

---

## 6. Test mapping

| Fixture | Test method |
| ------- | ----------- |
| H1 | `h1_admittedEdgeUsable_mediaUsable` |
| H2 | `h2_noUsableAdmittedEdge_notMediaUsable` |
| H3 | `h3_reconnecting_doesNotDropTopologyEdge` |
| H4 | `h4_recoveryInFlight_doesNotMutateTopology` |
| H5 | `h5_recoverySuccess_healthMayRecover` |
| H6 | `h6_recoveryFailure_reflectsMedia_notTopology` |
| H7 | `h7_mesh_doesNotApplyAnchorHealth` |
| H8 | `h8_health_readOnly_noTargetsOrEdgeMutation` |

```text
Contract:  .../ConferenceHealthProjectionContract.kt
Tests:     .../ConferenceHealthProjectionPhase25FixturesTest.kt
```

---

## 7. Gate

```text
H1–H8 PASS
        ↓
Phase 2-5 Contract PASS
        ↓
Phase 2-5 Impl NOT AUTHORIZED until signed
```

---

## 8. Current door

```text
Phase 2-4 Impl       PASS
Phase 2-5 Contract   REVIEW
Phase 2-5 Impl       NOT AUTHORIZED
R28 / RNA / Completion / Field   FROZEN
```
