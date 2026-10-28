# ADR-0056 Phase 3 — ConferenceHealth → UI Contract (U1–U8)

## Status

**CONTRACT PASS** (2026-08-18) · U1–U8 **8/8 PASS** · Impl **PASS** · Closeout: [0056-phase-3-health-ui-closeout.md](./0056-phase-3-health-ui-closeout.md)

**Parent:** [Phase 3 UI Scope](./0056-phase-3-health-ui-scope.md) **ACCEPTED** · [Phase 2-5 Health](./0056-phase-2-5-health-projection-contract.md) · [ADR-0056](../adr/0056-conference-topology-first.md) Decision 5

**Purpose:** Freeze room-facing ONLINE as a read-only projection of `ConferenceHealth`. UI must not derive `MEDIA_USABLE`.

**Exit:** U1–U8 unit PASS → Phase 3 UI Contract PASS → Implementation NOT until signed

---

## 1. Authorities (frozen)

```text
ConferenceHealth          = room usability authority
ConferenceRuntimeState    = lifecycle / control-plane
recoveryInFlight / peers  = diagnostic / peer chrome only
```

```text
mediaUsable == true   →  room ONLINE
mediaUsable == false  →  room NOT_ONLINE
health == null        →  room NOT_ONLINE
```

No extra room enum (no SYNCING / DEGRADED as room machine).

INV-056-14:

```text
runtime.phase == RECOVERING
edgeRecovering
recoveryInFlight
        ↓
MUST NOT override room ONLINE when mediaUsable
```

Forbidden:

```text
UI → topology / CapacityGate / RecoveryTarget
ViewModel → guess MEDIA_USABLE
RECOVERING → override MEDIA_USABLE
peer recovering → room SYNCING
```

---

## 2. Projection

```text
ConferenceHealthBinder.project()     (already exists; 2-5)
        ↓
ConferenceHealth
        ↓
ConferenceHealthUiProjectionContract.project(health, runtime?, recoveringPeers?)
        ↓
roomFacing ONLINE | NOT_ONLINE
+ diagnostic flags (do not drive room facing)
```

`roomFacing` reads **only** `health.mediaUsable` (null health → NOT_ONLINE).  
Runtime and peer sets are passed in as facts; they never write topology.

---

## 3. Fixtures U1–U8

Shared: ANCHOR 4p M01-star unless noted. Health via existing 2-5 `project` / `ConferenceHealth.from`.

### U1 — mediaUsable → ONLINE

Admitted usable → `roomFacing == ONLINE`.

### U2 — no admitted usable → NOT_ONLINE

### U3 — recovery in-flight does not kill ONLINE

`recoveryInFlight` + usable media → ONLINE.

### U4 — MESH does not apply ANCHOR ONLINE

MESH health → `anchorHealthModel == false`, room NOT_ONLINE.

### U5 — UI read-only

Project does not mutate snapshot; does not emit recovery targets.

### U6 — runtime RECOVERING does not override ONLINE

`ConferenceRuntimePhase.RECOVERING` + `edgeRecovering` + `mediaUsable` → still ONLINE.

### U7 — peer recovering chrome ≠ room SYNCING

Non-empty recovering peer ids; room stays ONLINE when mediaUsable. Chrome set ≠ room facing.

### U8 — null health → NOT_ONLINE, no invented topology

`health == null` → NOT_ONLINE. No snapshot constructed by UI contract.

---

## 4. Test mapping

| Fixture | Test method |
| ------- | ----------- |
| U1 | `u1_mediaUsable_roomOnline` |
| U2 | `u2_noUsableMedia_notOnline` |
| U3 | `u3_recoveryInFlight_doesNotOverrideOnline` |
| U4 | `u4_mesh_notAnchorRoomOnline` |
| U5 | `u5_ui_readOnly_noTargetsOrEdgeMutation` |
| U6 | `u6_runtimeRecovering_doesNotOverrideOnline` |
| U7 | `u7_peerChrome_isNotRoomSyncing` |
| U8 | `u8_nullHealth_notOnline_noTopologyInvent` |

```text
Contract:  .../ConferenceHealthUiProjectionContract.kt
Tests:     .../ConferenceHealthUiPhase3FixturesTest.kt
```

---

## 5. Non-scope

```text
ViewModel / DisplayResolver wiring
ADR-0055 Track P/O
topology / capacity / RecoveryEdgeProvider / R28
Gate 3 / Gate 4 field
new room states beyond ONLINE / NOT_ONLINE
```

---

## 6. Door

```text
Phase 3 UI CLOSED (0056-phase-3-health-ui-closeout.md)
Gate 3 Field Plan FROZEN (0056-gate-3-8p-field-plan.md)
Gate 3 execution  NOT AUTHORIZED
```
