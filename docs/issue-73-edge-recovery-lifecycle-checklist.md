# Issue #73 — Conference Edge Recovery Lifecycle Checklist

**Status:** Draft (2026-07-09)  
**Depends on:** PR #72 Recovery Admission Protocol Foundation  
**ADR:** [ADR-0021](./adr/0021-conference-edge-recovery-lifecycle.md)  
**Milestone:** RO-M3 Recovery Foundation (#72 + #73 combined merge)

## North Star

> 一个已经 JOINED 的 participant，在 Conference 未结束的情况下，因短暂 connectivity loss，可以**无需用户操作**，通过 `RECOVERY_REATTACH` + bounded ICE restart 恢复同一条 edge。

**#72 证明：** 恢复可以被接受。  
**#73 必须证明：** 恢复会自动发生。

---

## Scope

### In scope (#73 v1)

- [ ] `ConferenceEdgeRecoveryController` — edge key `(conferenceSessionId, remoteModuleId)`
- [ ] Automatic recovery trigger (ICE debounce + Lifecycle + Membership predicate)
- [ ] Edge recovery FSM (through `RECOVERED` / `FAILED_*` / `CANCELLED`)
- [ ] `RECOVERY_REATTACH` before ICE restart (control → media ordering)
- [ ] Host accepted → controller owns edge state (handlers dispatch only)
- [ ] Termination cancellation + generation/token invalidation + tombstone
- [ ] `EdgeRecoveryFacts` as sole recovery input to projector
- [ ] Bounded ICE restart: **1 per recovery attempt**
- [ ] Re-entry rule: new attempt only after HEALTHY cycle or user intent
- [ ] Terminal state retention + GC policy
- [ ] Unit / integration tests for FSM + reject paths
- [ ] Soak gate updates: S13 edge lifecycle, S14 stale recovery
- [ ] Three-device T4 auto-recovery (no manual cancel/re-enter)

### Out of scope (#73 v1)

- [ ] PC recreate escalation (Phase 2 / ADR-0022 candidate)
- [ ] Multi ICE restart per attempt
- [ ] Endpoint handover via recovery
- [ ] Membership migration
- [ ] Mesh defer timeout (separate issue)
- [ ] `MEDIA_SESSION_REUSE` root-cause fix beyond recovery ownership

---

## Implementation Checklist

### A. Controller & state model

- [ ] Introduce `ConferenceEdgeRecoveryController` (or rename existing stub)
- [ ] Edge key: `(conferenceSessionId, remoteModuleId)`
- [ ] Lineage fields: `remoteEndpointKey`, `membershipEpoch`, `authorityEpoch`, `mediaGeneration`, `recoveryAttemptId`, `connectivityEpoch`
- [ ] States: `CONNECTED`, `DISCONNECTED_DEBOUNCING`, `RECOVERY_PENDING`, `REATTACH_REQUESTED`, `REATTACH_ACCEPTED`, `ICE_RESTARTING`, `RECOVERED`, `FAILED_MEDIA_RECOVERY`, `FAILED_IDENTITY_MISMATCH`, `FAILED_STALE_LINEAGE`, `FAILED_REQUIRES_USER_ACTION`, `CANCELLED`, tombstone
- [ ] `RecoveryAction` enum reserves `PC_RECREATE` but v1 does not emit it

### B. Trigger (P1-A)

- [ ] ICE `DISCONNECTED` starts debounce timer (v1: 3s)
- [ ] Eligibility predicate (R4): debounced disconnect + `ESTABLISHED` + both `JOINED`
- [ ] **No** trigger from `joinMeeting()` / UI resume alone
- [ ] Emit `RECOVERY_EDGE_STARTED` log marker

### C. Admission integration (#72)

- [ ] Wire automatic trigger → `RECOVERY_REATTACH` request (reuse #72 payload)
- [ ] Host handler dispatches to controller only
- [ ] Accept path: verify lineage, **do not** bump `membershipEpoch`
- [ ] Reject paths: permanent vs recoverable per R8/R9
- [ ] `EPOCH_ADVANCED`: one lineage refresh + one retry max

### D. Media execution

- [ ] After accept: command `MediaSessionManager` for **one** ICE restart
- [ ] ICE restart timeout (suggest: 10s); total attempt budget (suggest: 15s)
- [ ] Success → `RECOVERY_EDGE_RECOVERED`
- [ ] Failure → `FAILED_MEDIA_RECOVERY` (no auto loop)

### E. Termination & cancellation (S11/S14)

- [ ] On `CONFERENCE_TERMINATED`: all edges → `CANCELLED`
- [ ] Invalidate generation/token; drop late callbacks
- [ ] Session tombstone registry (TTL independent of edge diagnostic TTL)
- [ ] Reject inbound `RECOVERY_REATTACH` for terminated session
- [ ] No `rejoin memory saved` after `clearRejoinState=true`

### F. Projection (P1.2)

- [ ] Controller emits `EdgeRecoveryFacts`
- [ ] Projector consumes facts only — **no** ICE/count inference for recovery UI
- [ ] Conference `phase` stays `ACTIVE` during edge recovery (R15)
- [ ] Participant shows `RECOVERING`; conference shows `degraded` / `recoveringEdges`
- [ ] `FAILED_MEDIA_RECOVERY` → participant unavailable / reconnect failed (not conference Connecting)

### G. Logging & soak

- [ ] Markers: `RECOVERY_EDGE_STARTED`, `RECOVERY_REATTACH_REQUESTED`, `RECOVERY_REATTACH_ACCEPTED`, `RECOVERY_EDGE_RECOVERED`, `RECOVERY_EVENT_DROPPED`, `FAILED_MEDIA_RECOVERY`
- [ ] Update `soak-tcc-hard-gates.ps1` S13 to edge lifecycle pairs
- [ ] S13 fail if `joinMeeting`/`openMeetingScreen` precedes recovery in T4 window
- [ ] S14 hard: zero stale recovery after termination

---

## Test Matrix

### Unit

| Case | Expect |
|------|--------|
| ICE disconnect < debounce | No recovery |
| ICE disconnect + ESTABLISHED + JOINED | `RECOVERY_EDGE_STARTED` |
| ICE disconnect + TERMINATED | No recovery / reject |
| ICE disconnect + LEFT | No recovery |
| `RECOVERY_REATTACH` accept | `membershipEpoch` unchanged |
| `ENDPOINT_DRIFT` | `FAILED_IDENTITY_MISMATCH`, no JOIN |
| `EPOCH_ADVANCED` ×1 | retry with refreshed lineage |
| `EPOCH_ADVANCED` ×2 | `FAILED_STALE_LINEAGE` |
| `CONFERENCE_TERMINATED` during recovery | `CANCELLED`, late callback dropped |
| ICE restart fail | `FAILED_MEDIA_RECOVERY`, no auto retry while still disconnected |
| HEALTHY → DISCONNECTED again | new recovery attempt allowed |

### Integration

- [ ] `RecoveryReattachRequestTest` (from #72) still pass
- [ ] New: auto-trigger from simulated ICE disconnect (no UI)
- [ ] New: termination cancels pending recovery timer
- [ ] New: projector shows participant RECOVERING, conference ACTIVE

### Three-device soak (hard)

**TC-RECOVERY-01 / T4 (S13-E2E)**

1. M01 Host, M02+M03 JOINED, all ACTIVE
2. M02 WiFi off 20–30s
3. M02 WiFi on
4. Within 60s, logs show full edge recovery chain **without** user opening meeting screen
5. M01/M03 conference UI remains ACTIVE; M02 shows reconnecting then connected
6. Audio restored M01↔M02↔M03

**Anti-cheat:** FAIL if first recovery signal after WiFi-on is preceded by `joinMeeting` / `openMeetingScreen`.

---

## Definition of Done

- [ ] All in-scope checklist items complete
- [ ] Unit + integration tests green
- [ ] Three-device P1Full: T4 pass (auto recovery), T7 pass, S11=0, S14=0
- [ ] S13 edge lifecycle gate pass on soak logs
- [ ] ADR-0021 status → Accepted (post-review)
- [ ] #72 + #73 ready for combined merge as RO-M3 Recovery Foundation

## Explicit Non-Goals (defer)

Document in PR description if requested:

- PC recreate after ICE restart failure
- Mesh defer 30s timeout / `MESH_RECOVERY_REQUIRED`
- Second-meeting cleanup for S8 `MEDIA_SESSION_REUSE`
- Authority-level conference `RECOVERING` phase
