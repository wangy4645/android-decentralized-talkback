# ADR-0036: Recovery Completion Authority — Membership Convergence on Conference Recovery (RCA-v1)

## Status

**Accepted with conditions** (2026-08-05) — architect review: P0-A + RCA-6 + baseline P1-A → **Phase 1 implementation authorized**. P1-B + full failure taxonomy + UI projection → **Phase 2**. P0-C rejected; W3′ deferred.

**Parent audit:** [recovery-completion-authority-audit.md](../analysis/recovery-completion-authority-audit.md)

**Complements:** [ADR-0022](./0022-recovery-completion-ownership.md), [ADR-0023](./0023-conference-membership-mutation-authority-boundary.md), [ADR-0009](./0009-group-session-identity-consistency.md), [ADR-0030](./0030-presence-projection-contract.md)

## Summary

WiFi Recovery Matrix M03 W1–W2 proved transport and media recover, but **recovery completion cannot close** because membership epoch diverges with no repair producer — watchdog then mislabels the outcome as `FAILED_MEDIA_RECOVERY`.

This ADR freezes the **Recovery Completion Authority** model: completion is convergence across all state-owning authority domains, not transport-only recovery.

## Context

See [recovery-completion-authority-audit.md](../analysis/recovery-completion-authority-audit.md) for W1/W2 evidence chain.

**Gap:** Recovery graph today covers transport + media only. **Membership recovery** is missing on the conference WiFi path.

```text
Today:     Transport → Media → (membership stuck) → timeout → wrong failure → UI sticky
Required:  Transport → Media → Membership convergence → Control reconcile → COMPLETE
```

## Decision

### RCA-1 — Dual Authority & Recovery Complete Predicate

| Domain | Owner | Provides |
|--------|-------|----------|
| Membership epoch | `resolveMembershipAuthorityId()` | `rosterEpoch`, `memberHash` |
| Conference host | Session host module | ICE authority, runtime reachability |

**Recovery complete predicate (frozen):**

```text
RecoveryComplete :=
    TransportReady
 AND MediaReady
 AND MembershipConverged
 AND ControlReconciled
```

Any authority domain left unconverged MAY produce ghost state (roster, floor, presence). Completion MUST NOT close while a required domain is diverged **unless** probe disposition is `UNWIRED` (existing ADR-0022 rule — no false CHECKED requirement).

**Invariant RCA-INV-001:** Every completion prerequisite MUST have a **reachable repair path**.

**Invariant RCA-INV-002:** `lastSeenAuthorityDigestByChannel` is observation only; digest update without authority snapshot apply does not satisfy `MembershipConverged`.

### RCA-2 — P0-A: Membership Convergence Trigger (narrow)

**MUST NOT** trigger on `network_available` alone. Short WiFi jitter, quick socket reconnect, and NAT rebind MUST NOT cause spurious resync.

**Trigger predicate (all required):**

```text
TransportRecovered
        AND SessionType == CONFERENCE
        AND (AuthorityDigestChanged OR EpochAlignmentUnknown)
        AND membership probe disposition == CHECKED
        AND converged == false
        → MembershipConvergenceRequired
        → GROUP_RESYNC_REQUEST (to membership authority only)
```

| Term | Definition |
|------|------------|
| `TransportRecovered` | Signaling path live: e.g. `BIDIRECTIONAL_READY` or `network_available` + `FIRST_INBOUND_AFTER_REBIND` (same bar as WiFi audit transport closure) |
| `AuthorityDigestChanged` | `localDigest.rosterEpoch != authorityDigest.rosterEpoch` OR `memberHash` mismatch |
| `EpochAlignmentUnknown` | No valid local/authority comparison yet after transport recovery (probe unwired → do not resync; wait until CHECKED) |

**Flow:**

```text
TransportRecovered + predicate true
        │
        ▼
GROUP_RESYNC_REQUEST → membership authority
        │
        ▼
Authority membership snapshot (validated apply)
        │
        ▼
Local membership store aligned
        │
        ▼
refreshControlReconciliationFact → completion re-evaluate
```

**Rate limit:** One in-flight resync per `(channelId, recoveryEpisodeId)`.

**Trace:** `MEMBERSHIP_CONVERGENCE_REQUESTED reason=conference_recovery`.

Reuse: `requestGroupResyncFromAuthority`, `handleGroupResyncRequest`, `sendMembershipSnapshotInvite`, `applyMembershipSnapshot`.

### RCA-3 — Membership Authority Apply Boundary (critical)

**Conference host MUST NOT be roster authority.**

```text
WRONG:
  M02 (conference host) → snapshot → M03 overwrites roster epoch

RIGHT:
  M01 (membership authority) → validated membership snapshot → local membership store
```

Conference host MAY provide: channel context, media topology, conference runtime state.

Conference host MUST NOT: become roster epoch authority or push membership snapshots that bypass membership authority validation (ADR-0023 R29).

After authority snapshot apply:

| Target | Rule |
|--------|------|
| GROUP session (if present) | `GroupMembershipSupport.applyMembershipSnapshot` |
| CONFERENCE session | `rosterEpoch` / participant roster aligned in same transaction — implementation detail, single authority source |

**MUST NOT** apply HELLO digest or host hints as local epoch (P0-B rejected for v1).

### RCA-4 — P1-A: RecoveryFailureClass (taxonomy)

`FAILED_MEDIA_RECOVERY` phase MUST NOT be the sole semantic carrier. Introduce:

```kotlin
enum class RecoveryFailureClass {
    MEDIA_PATH_FAILED,
    TRANSPORT_RECONNECT_FAILED,
    MEMBERSHIP_CONVERGENCE_TIMEOUT,
    CONTROL_RECONCILIATION_TIMEOUT,
    UNKNOWN_RECOVERY_TIMEOUT,
    EXPLICIT_ABORT
}
```

**Mapping (normative):**

| Condition | Class |
|-----------|-------|
| `!mediaRecoveryEvidenceSatisfied` at terminal | `MEDIA_PATH_FAILED` |
| Signaling rebind failed / no inbound after avail | `TRANSPORT_RECONNECT_FAILED` |
| `!membershipEpochConverged` through budget | `MEMBERSHIP_CONVERGENCE_TIMEOUT` |
| `!controlReconciled` + membership converged | `CONTROL_RECONCILIATION_TIMEOUT` |
| `attempt_timeout` + media satisfied + control blocked | `MEMBERSHIP_*` or `CONTROL_*` per probe |
| Unclassified timeout | `UNKNOWN_RECOVERY_TIMEOUT` |

Phase enum `FAILED_MEDIA_RECOVERY` MAY remain for ADR-0030 residency window until Phase 2. **Logs and `RecoveryFailureClass` MUST carry precise class in Phase 1.**

**UI mapping (Phase 2 only):** Media issue / Syncing roster / Reconnecting control — **not Phase 1**.

### RCA-5 — P1-B: Failed Residency Hygiene (conditional clear)

**Phase 2.** Do not clear failure projection blindly on `mediaReady`.

**Clear predicate (all required):**

```text
recoveryFailureClass != MEDIA_PATH_FAILED
        AND mediaReady (mediaRestored OR iceConnected per existing logs)
        AND obligation closed (RECOVERED or OBLIGATION_DEADLINE)
        → clear failed-media residency latch
        → phase may exit FAILED_MEDIA_RECOVERY
```

**MUST NOT** clear while `mediaRecoveryEvidenceSatisfied=false` or class is `MEDIA_PATH_FAILED`.

Rationale: only proven non-media failures may release media-unavailable projection.

### RCA-6 — Watchdog Defer & Blocked State

Watchdog MUST NOT enter `FAILED_MEDIA_RECOVERY` while membership convergence repair is in-flight.

| State | Behavior |
|-------|----------|
| Diverged, no repair dispatched | MAY timeout → `MEMBERSHIP_CONVERGENCE_TIMEOUT` |
| Diverged, resync in-flight | Watchdog **DEFERRED**; emit `RECOVERY_COMPLETION_BLOCKED_BY_CONTROL reason=MEMBERSHIP_CONVERGENCE_PENDING` |
| Control blocked, not membership | `RECOVERY_COMPLETION_BLOCKED_BY_CONTROL reason=CONTROL_RECONCILIATION_PENDING` |
| Converged + media satisfied | MUST attempt `markRecovered` before timeout |

**MUST NOT** map control/membership pending to `FAILED_MEDIA_RECOVERY` (W1/W2 root bug).

### RCA-7 — Recovery Completion Authority Owner

**Problem:** Transport, media, membership, and coordinator each touch recovery verdict — no single owner.

**Decision:** `ConferenceEdgeRecoveryController` + `CompletionObservationProjection` / `RecoveryCompletionPolicy` (existing ADR-0022 stack) act as the **Recovery Completion Authority** on device. Phase 1 does not require a new class name if the controller remains the sole **verdict writer**.

**Normative split:**

```text
Fact producers (read-only inputs):
  Transport layer      → link ready, rebind, inbound
  Media / ICE          → iceConnected, mediaRouteConnected, mediaRestored
  Membership probe     → membershipEpochConverged, digest traces
  Control reconcile    → controlReconciliationFact

Verdict writer (single):
  RecoveryCompletionPolicy / CompletionObservationProjection
  → RECOVERED | WAITING(blocked reason) | FAILED(class)
```

**MUST NOT** allow MediaController, ICE manager, or membership store to **unilaterally** set final recovery verdict or `FAILED_MEDIA_RECOVERY` residency without passing through completion policy.

Phase 1 may consolidate under existing controller; RCA-7 is the **ownership contract**, not necessarily a new type in v1.

## Implementation Phases

### Phase 1 — Minimal closure (AUTHORIZED)

Fix W1/W2 class. **No UI changes.**

| Item | Scope |
|------|-------|
| P0-A | Conference recovery hook + narrow trigger (RCA-2) |
| RCA-6 | Watchdog defer + `RECOVERY_COMPLETION_BLOCKED_BY_CONTROL` |
| P1-A baseline | `RecoveryFailureClass` on logs / terminal paths |
| RCA-3 | Authority-only snapshot apply |

**Verify:** W1/W2 rerun — `membershipEpochConverged`, `controlReconciled`, `RECOVERY_EDGE_RECOVERED`, UI `ONLINE` (observed, not fixed in code).

### Phase 2 — Semantic governance (NOT authorized yet)

| Item | Scope |
|------|-------|
| P1-B | Conditional failed residency clear (RCA-5) |
| P1-A full | Phase enum split if needed |
| UI | Projection mapping from `RecoveryFailureClass` |

UI currently **correctly exposes** wrong underlying state — do not patch UI until completion truth is fixed.

## Verification (Phase 1 gate)

```text
W1/W2 rerun:
  TransportRecovered           PASS
  MEMBERSHIP_CONVERGENCE_REQUESTED when epoch diverged
  membershipEpochConverged     true before watchdog
  controlReconciled            true
  RECOVERY_EDGE_RECOVERED
  No FAILED_MEDIA_RECOVERY on media-satisfied control block
  UI finalPresence             ONLINE (observation only)
```

W3′ optional. WiFi matrix not re-run for transport proof.

## Explicit non-goals

```text
NO P0-C (bypass membership)
NO network_available → resync (too broad)
NO conference host as roster authority
NO Phase 1 UI mapper changes
NO WiFi FSM / L.1.5 / Case B reopen
NO 6-8 node scale
```

## Acceptance conditions (met by this revision)

1. Recovery complete predicate explicit (RCA-1)
2. Membership authority cannot be replaced by conference host (RCA-3)
3. Completion authority owner contract (RCA-7)
4. Phase 1 fixes completion truth only; UI deferred to Phase 2

## References

- [recovery-completion-authority-audit.md](../analysis/recovery-completion-authority-audit.md)
- [wifi-recovery-audit.md](../analysis/wifi-recovery-audit.md)