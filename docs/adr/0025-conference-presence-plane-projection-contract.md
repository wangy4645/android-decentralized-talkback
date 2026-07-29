# ADR-0025: Conference Presence Plane Projection Contract (ADR-CONF-007)

## Status

**Accepted** (2026-07-14) — freezes the Presence Plane projection contract (R30) unlocked after ADR-0023 R29 and ADR-0022 R28-H / R27′ gates PASS. **R30-F Presentation Semantics Revision** accepted same date (soak `4d1a33c1`, M03 third-party join UX). Complements ADR-0020 (Runtime Projection), ADR-0022 R27′ (Presence Projection Boundary), ADR-0023 (Membership Mutation Authority). Does **not** redefine roster authority, recovery obligation, mesh bootstrap, or conference lifecycle phase UI.

## Summary

Governance (membership authority + recovery obligation) is complete. The remaining defect class is **projection lag**: Runtime already exposes edge-level health facts, but Meeting UI still renders a conference-level world-view.

```text
Problem class:

  Contract exists but rendering ignores it.
```

This ADR freezes:

1. **Three Planes** — Roster / Self / Edge — as the Presence Plane worldview.
2. **Extension of R27′ `ConferencePresenceProjection`** — no new projection DTOs.
3. **Per-participant authoritative projection** — `participants[]` is not a UI convenience list.
4. **Self vs peer network quality** — named fields that must never be derived from each other.
5. **Aggregate ↔ list consistency** — `joinedCount` / `connectedCount` / `recoveringPeers` must equal projections over local self + `participants[]` (remote only).
6. **Presentation semantics (R30-F revision)** — header participant count is membership (`joinedCount`); media connectivity (`connectedCount`) is diagnostic / per-avatar only — **MUST NOT** alter header membership rendering.

```text
Roster Plane  — global truth (authority sole writer)  → who belongs to the conference?
Self Plane    — local truth (this node)               → is my uplink healthy?
Edge Plane    — local truth (this node's edges)       → which peer do I have trouble with?
```

## Context

### Sequencing already frozen (ADR-0023)

```text
P0  R29                    — PASS
P1  supersede / watchdog   — PASS
P2  Presence / Pill        — do not touch until P0 + P1 land
```

P0 and P1 have landed. Presence work is now unlocked. Fixing UI before R29 would have masked roster split; that risk is closed.

### What already exists (R27′)

```kotlin
data class ConferencePresenceProjection(
    val joinedCount: Int,
    val connectedCount: Int,
    val recoveringPeers: Set<String>,  // ModuleId
)
```

Producer: `ConferencePresenceProjector`. UI **MUST** consume this (ADR-0022 R27′-A) and **MUST NOT** read `ReachabilitySnapshot`.

Observed lag:

| Fact already available | UI behavior |
|------------------------|-------------|
| `recoveringPeers` contains offline peer | Avatar still green / CONNECTING |
| `mediaState` RECONNECTING / FAILED | Mapped to online-looking CONNECTING |
| Any mesh edge lost | Global `networkLabel=Poor` on every node |

Root cause is not missing Runtime facts. It is incomplete Presence Plane contract + incomplete UI subscription.

### What this ADR is not

| Concern | Owner |
|---------|-------|
| Who may mutate roster | ADR-0023 |
| When obligation episode closes / when host may prune | ADR-0022 R28-H / **R28-J**, ADR-0024 R29-E |
| Per-peer Presence synthesis | **ADR-0030** |
| Live / Waiting / Connecting pill mapping | **Deferred** — separate ADR (e.g. 0026 Conference Pill Mapping) |
| Authority edge-matrix “local grey vs global grey” | **Deferred P2** — field reserved, algorithm not frozen here |

## Decision

### R30-A — Three Planes (Presence Worldview)

Every Presence field **MUST** belong to exactly one plane:

| Plane | Truth scope | Writer / source | Answers |
|-------|-------------|-----------------|---------|
| **Roster** | Global | Authority membership (sole writer per R29) | Who belongs? |
| **Self** | This node | Local transport health facts only | Is *my* network healthy? |
| **Edge** | This node × peer | Local edge observations + `EdgeRecoveryFacts` | Which peer am I having trouble with? |

```text
Reachability is local truth.   (Edge / Self planes)
Roster is global truth.        (Roster plane — ADR-0023)
```

UI **MUST NOT** collapse Edge/Self observations into a single conference-wide “network unstable” banner that overrides per-peer attribution.

### R30-B — Extend R27′; Forbid New Presence DTOs

Presence remains a **single** projection type owned by **`ConferencePresenceProjector`**.

**Forbidden** (anti-bloat; continues ADR-0022 R27′-B):

```text
MeetingAvatarProjection
MeetingHeaderProjection
ConferenceUiProjection
ConferenceParticipantProjection   (as a sibling DTO outside Presence)
speakerParticipants / headerParticipants / networkParticipants
```

**Forbidden:** stuffing presence fields into `ConferenceRuntimeProjector` / `ConferenceRuntimeState`.

Future features (speaker highlight, volume ring, mute, raise-hand, screen share, PTT preemption) **MUST** add fields on the Presence participant entry (or Presence aggregates), not invent parallel participant lists.

### R30-C — Projection Contract

```kotlin
data class ConferencePresenceProjection(
    val selfNetworkQuality: NetworkQuality,           // Self Plane — NEW
    val joinedCount: Int,                             // Roster Plane — existing
    val connectedCount: Int,                          // Edge aggregate — existing
    val recoveringPeers: Set<String>,                 // Edge aggregate — existing (compat)
    val participants: List<ParticipantPresence>,      // remote peers only — NEW
)

data class ParticipantPresence(
    val moduleId: String,
    val membership: MembershipState,                 // Roster Plane
    val mediaState: MediaState,                       // Edge Plane
    val recovering: Boolean,                          // Edge Plane
    val peerNetworkQuality: NetworkQuality,           // Edge Plane — NEW
)

enum class NetworkQuality {
    GOOD,
    POOR,
    LOST,
    UNKNOWN,
}
```

`NetworkQuality` naming is normative for the contract; concrete enum placement may follow existing `ConferenceNetworkIndicator` migration, but semantic axes **MUST** remain Self vs Edge.

#### `participants[]` scope (normative — Option A)

`participants[]` contains **remote peers only**. Local self is **not** a list entry.

Aggregates count **local self + remote `participants[]`**:

```text
joinedCount
    = number of conference participants whose membership == JOINED
      (local self + remote entries)

connectedCount
    = number of conference participants whose mediaState == CONNECTED
      (local self + remote entries)
```

**Examples:**

| Scenario | `joinedCount` | `connectedCount` |
|----------|---------------|------------------|
| Host solo in conference, local media up | 1 | 1 |
| Host + 2 remotes JOINED; 1 remote ICE lost | 3 | 2 |
| Session not accepted | 0 | 0 |

This matches existing `ConferencePresenceProjector` semantics (`connectedCount = 1 + connectedRemoteModuleIds.size` when local self is CONNECTED).

#### Field provenance (normative)

| Field | Plane | Allowed sources | Forbidden sources |
|-------|-------|-----------------|-------------------|
| `membership` | Roster | Authority roster / JOINED membership | `mediaUnavailablePeers`, ICE alone, HELLO alone |
| `joinedCount` | Roster | Count of JOINED membership | Invite roster size, expected invitees |
| `mediaState` | Edge | Per-peer media facts already owned by membership/media projection | Reconstructing from UI callbacks |
| `recovering` / `recoveringPeers` | Edge | `EdgeRecoveryFacts` per `remoteModuleId` (R27′) | ICE or HELLO alone |
| `connectedCount` | Edge aggregate | Count of JOINED participants (local + remote) with `mediaState == CONNECTED` | Roster size; remote-only list size without local self |
| `selfNetworkQuality` | Self | Local transport health facts only (see below) | Peer failures, peer edge aggregates, OR of all edges |
| `peerNetworkQuality` | Edge | This node's observation of the edge to that peer | Self uplink aggregate, other peers' edges |

#### Hard invariant — Self vs Edge never cross-derive

```text
selfNetworkQuality and peerNetworkQuality
MUST NEVER be derived from each other.
```

This freezes the plane split in the type system and provenance table. A future `aggregateNetworkQuality()` that OR-collapses peer edges into self (or vice versa) is a contract violation.

#### `selfNetworkQuality` sources (normative)

**Allowed** (local transport health facts only):

```text
ICE convergence (local-side)
local packet loss
local send bitrate
local RTT
```

**Forbidden:**

```text
peer failures
peer edge states
OR-aggregate of any peer edge
```

### R30-D — `participants[]` Is Authoritative Projection

```text
participants[] is an authoritative projection,
not a UI convenience list.

ConferencePresenceProjector is the sole writer of participants[].
```

Implications:

1. Aggregates are **views** of local self + `participants[]` (remote only), not independently authored truths.
2. Avatar rows, meeting header chips, and future interaction chrome **MUST** read `participants[]` plus local self (or the frozen aggregates derived from them).
3. Parallel lists keyed by the same `moduleId` for different UI surfaces are forbidden (see R30-B).
4. No other module (ViewModel, UI binder, recovery controller) may author or mutate `participants[]`.

### R30-E — Aggregate ↔ List Consistency

At every emission of `ConferencePresenceProjection` (`participants[]` = remote only):

```text
joinedCount
    == (localMembership == JOINED ? 1 : 0)
     + participants.count { membership == JOINED }

connectedCount
    == (localMediaState == CONNECTED ? 1 : 0)
     + participants.count { mediaState == CONNECTED }

recoveringPeers
    == participants.filter { recovering }.map { moduleId }.toSet()
```

Local self is evaluated from the same facts the projector already consumes; it is **not** duplicated as a `ParticipantPresence` row.

**Rationale for the third equality:** `recoveringPeers` already existed while avatars ignored it. Freezing set ↔ list consistency prevents a dual-truth regression (`recoveringPeers={M03}` while `participants[M03].recovering=false`).

If a projector cannot satisfy these equalities, it **MUST NOT** emit a partial / best-effort projection; fix the single writer.

### R30-F — Meeting Header Count Semantics (Presentation revision — Accepted 2026-07-14)

R29/R30 through R30-E freeze **who is a real member** and **aggregate/list consistency**. R30-F freezes **what the user-facing number means**.

**Normative (EN):**

```text
Meeting header participant count MUST represent conference membership.

The header count MUST be derived from joinedCount only.

Media connectivity MUST NOT alter participant count rendering.

connectedCount is a connectivity observation metric and MUST NOT be used
as a membership indicator in the meeting header or primary participant label.
```

**Normative (ZH):**

```text
会议 Header 人数必须表示会议成员数量。
Header 人数只能来源于 joinedCount。
媒体连通状态不得改变会议人数展示。
connectedCount 仅表示媒体连通观测，不得作为参会人数。
```

**Supersedes:** prior R30-F rule that rendered `connectedCount/joinedCount` (e.g. `2/3`) in the meeting header when aggregates diverged. That pattern conflated Edge Plane connectivity with Roster Plane membership and is **forbidden** for primary UI.

#### Three presentation layers (normative)

| Layer | Question answered | Plane | Primary fields | Primary surfaces |
|-------|-------------------|-------|----------------|----------------|
| **Header** | Who is in this meeting? | Roster | `joinedCount` | Meeting title / participant count chip |
| **Participant** | What is each person's state? | Edge (+ Recovery badge) | `participants[].mediaState`, `recovering` | Avatar row, per-peer affordances |
| **Diagnostic** | How healthy is media mesh? | Edge aggregate | `connectedCount`, per-edge facts | Engineer / debug view only |

```text
Roster Plane     → joinedCount        → Header ("3 Participants")
Edge Plane       → mediaState         → Avatar (online / connecting / reconnecting)
Recovery Plane   → recovering         → Avatar badge
Edge aggregate   → connectedCount     → Diagnostic ("Media 2/3" or edge list)
```

**MUST NOT** collapse layers:

```text
Header count = f(connectedCount)                    // forbidden
Header shows "2/3" as primary participant count   // forbidden
connectedCount used when user asks "how many joined?" // forbidden
```

#### Field → UI mapping (normative)

| Data | Source plane | UI location | Meaning |
|------|--------------|-------------|---------|
| `joinedCount` | Roster | Header | Who joined the conference |
| `connectedCount` | Edge (aggregate) | Diagnostic / secondary | How many media legs are CONNECTED locally |
| `participants[].mediaState` | Edge | Avatar | This peer's media state on this node |
| `participants[].recovering` | Recovery (Edge facts) | Avatar badge | This peer is in edge recovery |

#### Required rendering

| Surface | Consumes | Renders (examples) |
|---------|----------|-------------------|
| Meeting header count | `joinedCount` only | `3 Participants` |
| Avatar / participant row | local self + `participants[]` | Per-peer online / connecting / reconnecting |
| Connectivity auxiliary (optional, non-header) | `participants[]` + `connectedCount` | `Connecting M02…` or `1 participant connecting` — **MUST** attribute to peer(s), not bare `Media 2/3` in primary UI |
| Self network affordance (if shown) | `selfNetworkQuality` only | Local uplink health |
| Diagnostic / engineer mode | `joinedCount`, `connectedCount`, edge facts | `Membership: 3` / `Media: 2/3` / per-edge matrix |

Minimum avatar mapping (Presence → visual):

| Condition | Visual intent |
|-----------|---------------|
| Authority-driven roster removal (peer no longer in `participants[]`) | Not shown — left conference |
| `membership == LEFT` but peer still in roster / projection | **Still shown** (reconnecting or transitional); **MUST NOT** hide on LEFT alone |
| `recovering == true` or `mediaState` in RECONNECTING / FAILED | Reconnecting (grey / distinct from online) |
| `mediaState == CONNECTING` | Connecting (distinct from online) |
| `mediaState == CONNECTED` and not recovering | Online |
| Mute (when mute fact exists) | Muted affordance — field may be added later on the same participant entry |

**Forbidden:**

```text
OR(all mesh edges) → conference-wide "Poor Network" / "网络不稳定" as the primary attribution
Mapping RECONNECTING / FAILED → online-looking CONNECTING with green online dot
Reading ReachabilitySnapshot / ICE callbacks / memberKeys.size in ViewModel to rebuild presence
if (membership == LEFT) hide()   // bypasses R29; LEFT ≠ roster removal
Overriding lifecycle pill with awaiting-participant secondary state   // owned by future Pill ADR
participantCountLabel(connectedCount, joinedCount) → "x/y" in header   // superseded 2026-07-14
Using connectedCount < joinedCount to imply "fewer people in the meeting"   // semantic error
```

Top bar presence chrome **SHOULD** remain:

```text
Live (lifecycle — not defined here)
joinedCount as membership count (e.g. "3 Participants")
Optional secondary: peer-attributed connecting hint — not connectedCount/joinedCount fraction
```

Peer fault attribution **MUST** land on the peer row, not on a global network banner driven by peer edges.

**Scope:** presentation contract only. No Runtime, Recovery, or mesh bootstrap changes.

### R30-G — ConferenceJoinLatency (observability — Accepted 2026-07-14)

Not a correctness gate. Separates bootstrap **performance** from membership / recovery bugs (e.g. soak `4d1a33c1`: ~21s to `connectedCount == joinedCount` on M03 while membership was already 3 — bootstrap latency, not roster defect).

#### Timeline anchors

| Marker | Event |
|--------|-------|
| **T0** | `INVITE_ACCEPTED` (local user accepts conference invite) |
| **T1** | Local conference runtime session created |
| **T2** | `firstRemoteMediaConnected` (first remote peer `mediaState == CONNECTED`) |
| **T3** | `allJoinedParticipantsMediaConnected` (local self + all JOINED remotes CONNECTED) |

#### Metrics

```text
SessionCreateLatency           = T1 - T0
FirstMediaJoinLatency          = T2 - T0
FullMeshJoinLatency            = T3 - T0    // primary UX metric for mesh third joiner
```

**Focus:** `FullMeshJoinLatency` P50 / P95 per device role (host vs participant). In mesh, the last edge to converge bounds T3.

#### Suggested thresholds (engineering guidance, not ADR hard gates)

| FullMeshJoinLatency | Interpretation |
|---------------------|----------------|
| 2–5 s | Normal |
| > 10 s | Investigate bootstrap / ICE throttle |
| > 20 s | User-perceptible; log edge + throttle context |

**Logging:** emit timeline markers on `CONFERENCE_JOIN_LATENCY` (grep tag) and/or `CONFERENCE_LIFECYCLE_TIMELINE`; implemented in `ConferenceJoinLatencyTracker` (observability only).

### R30-H — Mesh bootstrap latency vs presentation correctness (Frozen 2026-07-14)

Soak `ad7af8bb` (M02 participant, third joiner M03): after force-stop + P0-C UI, M02 correctly showed `3 Participants` + `1 connecting...` for ~12s while M03 showed `3 Participants` without hint. **Not a presentation defect.**

| Plane | M03 accept后 M02 表达 | 含义 |
|-------|----------------------|------|
| **Roster** | `joinedCount = 3` | M03 已 accept，名册三人 |
| **Edge (local)** | M02→M03 ICE `CHECKING` → `CONNECTED` | 本机到 M03 媒体边未就绪 |
| **UI (R30-F)** | Header `3 Participants` + auxiliary `1 connecting...` | 人数正确；边未就绪单独提示 |

**Frozen conclusions:**

1. Retire `connected/joined` header fractions — confirmed wrong semantics (pre-R30-F `2/3`).
2. `joinedCount` + per-peer edge hint — confirmed correct semantics; exposes runtime mesh bootstrap latency.
3. **Do not** fix the ~12s window in Presentation; investigate via R30-G `FullMeshJoinLatency` and P1 bootstrap (PC create timing, offer/close churn, Group/Conference ICE lifecycle).
4. Avatar/hint **MUST** distinguish first-join `Connecting` from post-connect `Reconnecting` (`everConnected` only from ICE CONNECTED, not signaling complete).

**P1 investigation suspects (not frozen as root cause):** accept-after-PC-create; repeated `Group mesh join offered` with `ice=CLOSED/CHECKING`; Group vs Conference ICE bearer competition.

**Superseded (hint only):** R30-H item 2–3 ICE-bootstrap `connecting` hint — replaced by **R30-I** user-perceived hint semantics (2026-07-14).

### R30-I — User-perceived Presence Hint Semantics (Accepted 2026-07-14; **R30-I.1 Accepted 2026-07-14**)

Presence hint and avatar badge **MUST** represent **user-perceived communication availability**, not media-edge convergence progress or recovery control-plane obligation state.

**Rationale:** In decentralized mesh conferences, `usable ⊄ full-mesh-connected`. Example: `M02 ↔ M03 = CHECKING` does **not** imply the meeting is unusable if playback is already ready on another path. Surfacing mesh bootstrap to users creates false alarms. Previously `connected == usable`; now **`usable` is independent of full mesh ICE convergence** unless it impacts receive/transmit.

**R30-I.1 — Recovery diagnostic ≠ user-visible reconnecting (2026-07-14):**

`recoveringPeers` / per-peer `recovering` **MUST** remain in `ConferencePresenceProjection` for soak, watchdog, and telemetry. Presentation **MUST NOT** map `recovering` alone to reconnecting hint or avatar badge.

> Recovery is not a user-visible state unless it affects media availability.

**Presentation priority (frozen):**

```text
1. Membership
2. Media availability (playbackReady / mediaUnavailable)
3. Recovery diagnostic (never alone)
```

| Condition | Avatar / Hint |
|-----------|---------------|
| `membership != JOINED` (visible row policy) | Hidden / LEFT |
| `playbackReady=true` | ONLINE — **no reconnect hint**, even if `recovering=true` |
| `mediaUnavailable=true` | RECONNECTING |
| `everConnected && !playbackReady` | RECONNECTING |
| `!everConnected && !playbackReady` | JOINING |

Hint **MUST NOT** be derived from:

- `connectedCount != joinedCount`
- `ICE != CONNECTED` / `peerConnectionState != CONNECTED`
- Pure edge bootstrap progress
- **`recoveringPeers` / `recovering` alone** (R30-I.1)

**Playback readiness** for Conference Presentation **MUST** use participant projection `displayState == VISIBLE_CONNECTED` (media `CONNECTED`), **not** `remotePlaybackEnabledForModule` — the latter reflects GROUP floor playback gate (`NO_FLOOR_OWNER`) and false-negatives in meeting. **`ICE_CONNECTED` alone is insufficient** — `playbackReady` requires `VISIBLE_CONNECTED` (or local).

Hint **MAY ONLY** be derived from:

| Scenario | Condition | Hint |
|----------|-----------|------|
| Usable | remote `playbackReady` && !local capture blocked | *(none)* |
| First join | `!everConnected && !playbackReady` | `{peer} joining...` |
| Reconnect | `everConnected && (mediaUnavailable \|\| !playbackReady)` | `{peer} reconnecting...` |
| Local cannot transmit | capture blocked (conference muted) | `Microphone unavailable` (optional) |

**Single source rule:** `ParticipantDisplayStateMapper` → `ParticipantPresentationState` drives **both** header hint and avatar badge. **MUST NOT** maintain separate `MeetingHeaderState` vs `MeetingAvatarState` hint machines.

Implementation: `ParticipantDisplayStateMapper` + `MeetingPresenceDisplay.resolveParticipantPresentation` + `aggregateAvailabilityHint` (Presentation-only; no new Runtime fields; no Recovery / Media gate changes).

## Deferred / Non-goals

| Item | Why deferred |
|------|----------------|
| **Conference Pill Mapping** (ACTIVE→Live; awaiting must not override lifecycle) | Runtime phase UI, not Presence Plane — separate ADR |
| **Local grey vs global grey** (partitioned vs offline) | Requires authority-side edge-matrix aggregation + broadcast; complexity step-up; `peerNetworkQuality` reserved |
| **Mute / speaker / raise-hand / PTT fields** | Allowed as future fields on `ParticipantPresence`; not specified here |
| **Host migration when authority unreachable** | ADR-0023 non-goal; unchanged |

## Priority / sequencing

```text
P0-A  Authority boundary (R29 / R28-H)     — PASS (prior ADRs)
P0-B  This ADR (R30 contract freeze)       — Accepted
P0-C  R30-F presentation UI                — header joinedCount; retire connected/joined header fraction
P0-C2 R30-I user-perceived hint            — playback/capture/reconnect only; Presentation-only
P0-D  Avatar four-state UI                 — subscribe recovering + mediaState (no Runtime rewrite)
P1    selfNetworkQuality / peerNetworkQuality implementation + retire OR-all-edges conference label
P1    R30-G ConferenceJoinLatency logging  — implemented (observability only)
P2    Authority edge matrix (two greys)
```

## Consequences

- **Positive:** Presence Plane stops drifting into Runtime/UI-specific DTOs; Self vs Edge naming prevents re-collapse into `OR(all edges)`; aggregate/list invariants catch dual-truth early; R30-F separates membership display from media connectivity so third-party mesh join no longer reads as "missing participant."
- **Negative:** `ConferencePresenceProjector` and Meeting UI must be updated together; `MeetingPresenceDisplay.participantCountLabel(connected, joined)` header usage is non-compliant; existing `ConferenceNetworkIndicatorProjector` OR-all-edges semantics become non-compliant for Self Plane and must migrate.
- **Neutral:** `recoveringPeers` retained for compatibility during migration; consistency with `participants[].recovering` is mandatory from first emission of `participants[]`. `connectedCount` remains in projection for diagnostic and R30-E invariants.

## Soak / acceptance gates (suggested)

| Gate | Pass criterion |
|------|----------------|
| G-R30-1 | With one peer ICE lost: local avatar for that peer is reconnecting/grey; other peers remain online-looking |
| G-R30-2 | Same scenario: remaining nodes do **not** show conference-wide Poor driven solely by the lost peer edge; `selfNetworkQuality` stays GOOD if local uplink is healthy |
| G-R30-3 | Projector unit tests assert the three aggregate ↔ list equalities (local self + remote `participants[]`) on every fixture |
| G-R30-4 | No new Presence DTO types introduced; ViewModel does not rebuild presence from ICE/HELLO |
| G-R30-F | Third participant accepts while one remote edge still CONNECTING: header shows `joinedCount` (e.g. `3 Participants`); connecting peer attributed on avatar or secondary hint — header **MUST NOT** show `2/3` |
| G-R30-I | Third participant accept while `playbackReady` for that peer: header **MUST NOT** show joining/reconnecting hint; avatar **MUST NOT** show connecting badge solely because ICE is CHECKING |
| G-R30-I1 | Peer in `recoveringPeers` with `playbackReady=true` (VISIBLE_CONNECTED): avatar ONLINE; **no** reconnect hint |
| G-R30-I2 | Peer `recovering=true` with `!playbackReady`: reconnect hint and RECONNECTING avatar |
| G-R30-I3 | Peer `recovering=false` with `!playbackReady` and `everConnected`: reconnect hint still shown |
| G-R30-G | `FullMeshJoinLatency` logged T0→T3 on conference join soak; P95 tracked separately from recovery correctness gates |

## References

- ADR-0020 — Conference Runtime Projection Contract
- ADR-0022 — Recovery Completion Ownership & Reachability (R27′ Presence Projection Boundary)
- ADR-0023 — Conference Membership Mutation Authority Boundary (R29)
- ADR-0024 — Host Post-Terminal Prune Eligibility (R29-E)
- ADR-0010 — Conference Membership vs Media Projection
- `ConferencePresenceProjector` / `ConferenceNetworkIndicatorProjector` (as-is implementations)
