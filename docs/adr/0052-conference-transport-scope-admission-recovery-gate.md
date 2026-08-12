# ADR-0052: Separate Conference Transport State from Group Transport State and Gate Recovery During Admission

## Status

**PROPOSED** (2026-08-12) · Field evidence: M02 conference Connecting wedge — `talkback/logs/adr0051-pr1-20260812-074344`

**Relation:** Independent of ADR-0051. PR1 cleaned explicit admission entry; this ADR addresses **participant attach** after invite accept.

**Issue title (tracker):** Conference participant host-link recovery races with admission acceptance due to GROUP ICE state leakage

**Labels:** `conference` · `ice` · `signaling` · `media-scope` · `not ADR-0051`

---

## Context

Talkback runs two mesh media scopes:

```text
GROUP PTT transport  (grp:CH-xx)
CONFERENCE transport (per-session host / mesh links)
```

`MediaSessionManager` / `SessionMediaRegistry` already isolate PeerConnections per scope (`MediaBearerScope.GROUP` vs `CONFERENCE`). Meeting barrier correctly closes GROUP PCs before CONFERENCE admission.

**Observation / QoS / gate layer does not mirror that separation.**

`NetworkQualityMonitor` stores mesh ICE in `groupSnapshots[moduleId]`. Legacy API `snapshot(remoteModuleId)` reads only that table. `onMeshIceStateChanged` writes GROUP and CONFERENCE ICE events into the same table via `updateIceState` → `updateGroupIceState`.

Field sequence (M02 participant, session `e7d7274a…`):

```text
07:47:26  GROUP PC M01 → CLOSED (meeting barrier)
07:47:33  CONFERENCE PC M01 → NEW, signaling STABLE (invite accept)
07:47:33  projection: hostIce=CLOSED (reads GROUP snapshot)
07:47:34  scheduleConferenceHostLinkKick (500ms) → RECOVERY_REATTACH
07:47:44  SDP apply failure (offer collision symptom)
```

Two **confirmed** architecture violations:

1. **Scope pollution:** CONFERENCE gates read stale GROUP ICE (`hostIce=CLOSED` while conference PC is NEW/CHECKING).
2. **Admission / recovery priority:** Recovery ICE restart runs during initial admission negotiation (no barrier after STABLE).

`MediaSessionManager` ownership is **correct**. Fix target is **runtime state ownership** (`NetworkQualityMonitor` + consumers), not `SessionMediaRegistry`.

---

## Decision 1 — Media scope state MUST be isolated

### Forbidden

Using `snapshot(remoteModuleId)` (or `qosSnapshotForModule`) as the single truth for both GROUP and CONFERENCE.

### Contract (frozen)

```text
GROUP transport state ≠ CONFERENCE transport state
```

Any UI readiness, transmit gate, recovery eligibility, mesh defer, or audit field that references peer ICE **must declare scope** (`MediaBearerScope`).

### Target API shape

Replace ambiguous:

```kotlin
snapshot(remoteModuleId: String)
```

With:

```kotlin
snapshot(scope: MediaBearerScope, remoteModuleId: String): QosSnapshot?
```

Examples:

```kotlin
snapshot(MediaBearerScope.GROUP, "M01")
snapshot(MediaBearerScope.CONFERENCE, "M01")
```

`update*` and `isConnected(scope, moduleId)` must follow the same rule. CONFERENCE ICE updates must not overwrite GROUP snapshots (and vice versa).

### Consumer inventory

See `talkback/docs/analysis/adr0052-scope-consumer-inventory.md` before implementation. Risk: partial migration leaves conference paths reading GROUP by accident.

---

## Decision 2 — Admission negotiation preempts recovery

`signalingState == STABLE` after invite accept does **not** mean conference admission is complete.

### Admission phase (projection, not a new FSM)

Expose a read-only projection, e.g.:

```kotlin
enum class ConferenceAdmissionPhase {
    IDLE,
    ACCEPTING_INVITE,
    SIGNALING,
    FIRST_MEDIA_NEGOTIATION,
    READY,
}
```

Derived from existing session / transition facts (invite accept, answerer commit, first conference-scope ICE progress, `isConferenceUiReady` inputs).

### Recovery gate (frozen intent)

Before:

```kotlin
if (!hostIceConnected) restart()
```

After:

```kotlin
if (admissionPhase != READY) {
    deferRecovery()
    return
}
if (!conferenceIceConnected(host)) {
    restart()  // only after scope-correct read
}
```

Applies at minimum to:

- `scheduleConferenceHostLinkKick`
- Outbound `RECOVERY_REATTACH` / `attemptConferencePeerIceRestart` on participant host link during first join
- Edge recovery `isIceConnected` when session is CONFERENCE host-edge

`ConferenceEdgeRecoveryController.onIceStateChanged` does not observe `CLOSED`; participant kick path is the primary false trigger in the field log.

---

## Decision 3 — Conference signaling transactions must not race

Initial admission (`applyRemoteOffer` / answer) and recovery (`createOffer(iceRestart=true)`) can interleave today:

```text
accept path (coordinatorExecutor)
scheduleConferenceHostLinkKick (scheduler thread → coordinatorExecutor)
edge recovery onIceRestart (recovery scheduler → coordinatorExecutor)
```

No mutex or single negotiation owner for conference peer signaling.

### Requirement

Conference `createOffer` / `setLocalDescription` / `setRemoteDescription` for a given `(sessionId, remoteModuleId)` must be **serialized** across:

- invite accept / answer
- host-link kick
- recovery reattach
- reconnect / handoff

Implementation options (implementation phase — not decided here):

- `conferenceNegotiationMutex` per edge, or
- `ConferenceNegotiationCoordinator` owning all SRD/offer transactions.

---

## Non-goals

This ADR does **not** change:

- ADR-0051 navigation / admission separation (entry intent)
- WiFi recovery, RNA, obligation / completion admission
- GROUP mesh recovery algorithms (`GroupMeshReconciler` L0/L1)
- Invite user-confirm policy
- ICE restart algorithm semantics
- SDP role / DTLS setup negotiation rules as first fix

Do **not** treat as root cause:

```text
Answerer must use either active or passive value for setup attribute
```

That is an **offer-collision symptom** when recovery crosses initial admission.

Do **not** modify `SessionMediaRegistry` / `MediaSessionManager` scope isolation (already validated).

---

## Implementation phases (ordered)

### Phase 1 — P0: Scope-aware QoS

- Split read/write paths in `NetworkQualityMonitor` (or `Map<MediaBearerScope, …>`).
- `onMeshIceStateChanged` writes by bearer scope.
- Migrate **conference-critical** consumers (inventory § Conference-critical).

**Acceptance log signature:**

```text
Before: conferencePc=NEW  hostIce=CLOSED
After:  conferencePc=NEW  conferenceHostIce=NEW|CHECKING  groupIce=CLOSED
```

### Phase 2 — P0: Admission barrier

- Defer `RECOVERY_REATTACH` / host-link kick until `ConferenceAdmissionPhase.READY` (or equivalent).
- No recovery offer within first admission window after accept.

**Acceptance:**

```text
07:47:33 accept
07:47:34 NO RECOVERY_REATTACH
(wait first conference-scope ICE progress)
```

### Phase 3 — P1: Negotiation serialization

- Single owner / lock for conference signaling transactions per edge.

### Phase 4 — P1: Throttle tuning

- `DROP_ICE_RESTART_THROTTLED` / `canAcceptIceRestart` only after Phases 1–2; throttle is protection, not root cause.

---

## Field routing

| Observation | Route |
|-------------|--------|
| M02 Connecting, `hostIce=CLOSED`, conference PC NEW | ADR-0052 Phase 1–2 |
| `RECOVERY_REATTACH` immediately after accept | ADR-0052 Phase 2 |
| SDP setup attribute error after dual offer | Symptom — verify Phase 3; do not patch SDP first |
| Accidental meeting start from More | ADR-0051 (closed track) |
| GROUP PTT Syncing wedge | Separate mesh-media track |

---

## References

- Field log: `talkback/logs/adr0051-pr1-20260812-074344`
- Scope consumer inventory: `talkback/docs/analysis/adr0052-scope-consumer-inventory.md`
- ADR-0051: `talkback/docs/adr/0051-meeting-navigation-intent-admission-separation.md`
- `NetworkQualityMonitor.kt`, `TalkbackCoordinator.kt` (`onMeshIceStateChanged`, `scheduleConferenceHostLinkKick`, `isPeerMediaConnected`)
- `ConferenceBootstrapDeferral.kt`, `ConferenceEdgeRecoveryController.kt`
