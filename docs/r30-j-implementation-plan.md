# R30-J Implementation Plan

**Status:** Frozen (2026-07-17)  
**ADR:** [ADR-0028](./adr/0028-participant-presence-ownership-local-reachability-projection.md)  
**Out of scope:** ADR-0028 expansion, ADR-0025 superseded notes (Phase 5), runtime / recovery ownership changes

## North Star

> Per-peer reconnecting hint / avatar / badge have **one** construction point: `LocalReachability.resolve(...)`. Illegal cross-projection combinations (e.g. `recovering=[]` + `hint="M01 reconnecting..."`) must be **unrepresentable**.

Soak fixture: `logs/wifi-ui-norestore-20260716-175905/` — M03 `17:57:42`–`17:57:49`.

---

## 1. Fact owner — `receivePathLive`

### Interface (frozen)

```kotlin
interface ReceivePathLivenessProvider {
    fun receivePathLive(
        sessionId: String,
        remoteModuleId: String
    ): Boolean
}
```

### Semantics

```text
receivePathLive(P) == true

≠ ICE CONNECTED
≠ edge recovered
≠ playback pipeline created

= this node has continuously received decodable audio from P
  (debounced upstream; Tplayback ≈ 500ms — media layer owns value)
```

### Ownership chain (frozen)

```text
Media receive-path observer
        │
        ▼
ReceivePathLivenessProvider
        │
        ▼
LocalReachability.resolve(...)
        │
        ▼
hint / avatar / badge
```

### Forbidden producers

```text
ParticipantDisplayStateMapper
ICE callback
recoveringPeers (aggregate)
ConferencePresenceProjector (inference)
UI / ViewModel cache
```

`receivePathLive` is a **media observation fact**, not a UI inference.

### Debounce / SLA split

| Concern | Owner |
|---------|--------|
| `Tplayback` debounce | Media receive-path observer |
| `Tsla` (~1s) assertion | R30-J tests / soak only |
| Timers in projection | **Forbidden** |

---

## 2. `LocalReachability.resolve()` — signature frozen

Phase 1 uses stubs; **signature does not change** when media layer lands.

```kotlin
data class LocalReachability(
    val state: ParticipantPresenceState
)

fun resolve(
    membership: MembershipState,
    receivePathLive: Boolean,
    recovering: Boolean,
    mediaUnavailable: Boolean,
    everConnected: Boolean
): LocalReachability
```

| Input | Drives |
|-------|--------|
| `membership` | LEFT vs still-present |
| `everConnected` | JOINING vs RECONNECTING |
| `receivePathLive` | ONLINE (V1) |
| `recovering` / `mediaUnavailable` | RECONNECTING only when `!receivePathLive` |

### Phase 1 stub (explicit, temporary)

```kotlin
// TODO(R30-J): replace playbackReady stub with media-layer receivePathLive
receivePathLive = playbackReady
```

Do **not** derive `receivePathLive` from `ICE_CONNECTED`, `VISIBLE_CONNECTED`, or `recoveringPeers`.

### Presentation wiring

- `aggregateAvailabilityHint()` folds over `LocalReachability(*)` — **not** `recoveringPeers`.
- Retire parallel hint machines in `ParticipantDisplayStateMapper` / `MeetingPresenceDisplay` as consumers migrate.

Touch points (current): `ParticipantDisplayStateMapper.kt`, `MeetingPresenceDisplay.kt`, `TalkViewModel.kt`.

---

## 3. Replacing `playbackReady`

| Stage | `receivePathLive` source | Notes |
|-------|--------------------------|-------|
| Phase 1–3 | `playbackReady` stub | Unblocks skeleton + tests; tagged `TODO(R30-J)` |
| Phase 4 | `ReceivePathLivenessProvider` | Inject at ViewModel / presence glue layer only |
| Never | `displayState`, ICE, `recoveringPeers` | Projection回流 forbidden |

Migration path:

```text
1. Introduce ReceivePathLivenessProvider (no-op / false until wired)
2. LocalReachability.resolve reads injected boolean
3. Wire provider from media receive-path observer
4. Delete playbackReady stub at single call site
5. G-R30-J soak green on receivePathLive
```

---

## 4. R30-J tests (Phase 2 — before media wiring)

Tests assert **ownership**, not incidental log strings.

### R30-J-1 — V1

```text
Given: receivePathLive=true, recovering=true
Expect: state != RECONNECTING, hint == null
```

### R30-J-2 — single owner (P1 fixture)

```text
Given: recovering=false, media facts that would yield ONLINE if receivePathLive=true
Expect: hint MUST equal aggregate(LocalReachability(*))
        — no independent hint path
```

Regression target: `recovering=[]` + `hint="M01 reconnecting..."` **cannot be constructed** once hint is only derived from `LocalReachability`.

### R30-J-OUT — asymmetric mesh (design-allowed)

```text
Given: M01 resolve → ONLINE, M03 resolve → RECONNECTING (different local facts)
Expect: no cross-device consistency assertion
```

### G-R30-J-3 — purity

`LocalReachability` has no timer fields, no mutable state, no internal cache.

---

## 5. Phase order (locked)

```text
Phase 1   LocalReachability skeleton + frozen resolve() signature
    ↓
Phase 2   R30-J unit tests (expect RED on current code)
    ↓
Phase 3   playbackReady stub wired through resolve(); tests GREEN
    ↓
Phase 4   ReceivePathLivenessProvider + media observer; swap stub at one site
    ↓
Phase 5   ADR-0025 superseded note (hint/avatar/badge only; keep aggregates)
```

**Forbidden:**

```text
wire media layer before tests
derive receivePathLive from VISIBLE_CONNECTED / ICE
add timers inside LocalReachability
```

---

## 6. Phase 5 preview (docs only — do not execute until Phase 4 green)

ADR-0025 addendum:

```text
Superseded by ADR-0028:
  - participant hint ownership
  - avatar ownership
  - reconnecting badge ownership

Retained in ADR-0025:
  - ConferencePresenceProjection aggregates
  - recoveringPeers telemetry
  - conference summary / header counts
```

---

## Acceptance

| Gate | When |
|------|------|
| G-R30-J / J1 / J2 / J3 | Phase 3+ unit tests |
| G-R30-J-OUT | Documented only — no failure on asymmetric devices |
| WiFi flap soak | Phase 4 — `receivePathLive` true ⇒ hint cleared within `Tsla` |
