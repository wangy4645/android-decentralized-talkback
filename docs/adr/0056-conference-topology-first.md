# ADR-0056: Conference Topology-First Architecture

## Status

**ACCEPTED** (2026-08-14) · **Amended v2** (2026-08-14: contract freeze, unidirectional flow, Provider contract, gate telemetry, phase reorder) · **Implementation NOT AUTHORIZED**

**Branch:** `adr/0056-conference-topology-first` (documentation only)

**Issue:** [#197](https://github.com/wangy4645/android-decentralized-talkback/issues/197) — pre-implementation gaps A / B / C

**Relation:**

- [ADR-0055](./0055-conference-recovery-outcome-convergence.md) — recovery outcome convergence · **orthogonal**; does not subsume topology / media-edge authority
- [ADR-0054](./0054-conference-edge-recovery-liveness-ownership.md) — edge liveness reclaim · **orthogonal**; execution layer retained
- [ADR-0052](./0052-conference-transport-scope-admission-recovery-gate.md) — transport scope isolation · **related**; does not define topology-first media edges
- [ADR-0010](./0010-conference-membership-vs-media-projection.md) — roster vs media projection · **parent vocabulary**; this ADR extends separation to recovery obligations
- [ADR-0019](./0019-conference-signaling-media-separation.md) — signaling vs media lifecycle · **related**; control edges remain non-recovery
- WiFi recovery main chain — **CLOSED**; do not merge membership / RNA / completion predicate changes here
- Q11 / #196 edge-level patches — **deferred**; must not accumulate new roster-derived recovery exceptions under this ADR

```text
ADR-0056
Decision:              ACCEPTED (architecture contract freeze)
Model:                 Conference topology-first, epoch-versioned Anchor SFU-lite (4–10)
Primary invariant:     RecoveryEdge valid iff current-generation media edge OR epoch-authorized transition
Implementation:        NOT AUTHORIZED (phased; see Implementation Phases)
Does NOT reopen:       WiFi recovery · RNA · completion predicate · ADR-0055 implementation
Does NOT authorize:    full-mesh Conference at 8–10 · roster-derived recovery edges · UI-as-health authority
```

---

## Context

Conference recovery obligations are currently derived from **roster membership**, not from **realized media topology**. Control relationships (HELLO, membership, election, negotiation coordination) can therefore acquire ICE restart / media recovery FSMs even when no PeerConnection exists for that pair.

Field evidence (#190, #196, Q11) shows the resulting failure class:

```text
Roster pair exists
  → recovery obligation created
  → no ActualMediaEdge (observer / non-media pair)
  → SUPERSEDE / handoff churn without media substrate
```

Industry practice for 8–10 participant audio converges on **SFU-lite**: each publisher uploads once; a selected node forwards to subscribers. Full mesh does not scale and multiplies recovery obligations linearly with roster size.

**PTT vs Conference (do not conflate):**

| Track | Architecture | Implementation | Current config | ADR-0056 scope |
|-------|--------------|----------------|----------------|----------------|
| **GROUP PTT** | Anchor (`AnchorTopology` + `ProgramAudioBus` + failover) | **Exists** | `CHANNEL_ANCHOR_THRESHOLD=99` — temporary Mesh A/B (GROUP-Membership-Debt-Remediation Phase 2) | **Out of scope** — threshold restore + 6–8 validation is independent PR / run card |
| **CONFERENCE** | 4+ Anchor / SFU-lite | `ConferenceAudioBus` **not product-ready**; recovery **not topology-first** | 3-device field runs mesh (`<4` threshold) | **In scope** — topology → media edge → recovery edge |

PTT does **not** require architectural redesign under this ADR. Conference does.

**Core requirement:** Conference media and recovery MUST be **topology-first**, not roster-first. Topology MUST be **epoch-versioned** so anchor failover invalidates stale media and recovery edges deterministically.

This ADR freezes the **target architecture contract** for Conference 8–10. It does **not** authorize implementation, retry budgets, concrete class APIs, or timeout tuning.

**Field development posture (frozen):**

```text
DO NOT add new roster-derived recovery exceptions while ADR-0056 phases proceed.
DO NOT patch ConferenceEdgeRecoveryController with Q12/Q13/Q14-style edge cases.
Architecture skeleton and contract verification MUST precede further field-driven recovery branches.
```

**Engineering iron law (frozen):**

```text
Only facts and state flow downward.
Recovery MUST NOT create topology truth.

Membership
  → ConferenceTopologyAuthority
  → ConferenceTopologySnapshot (atomic publish)
  → ActualMediaEdgeSet (admitted)
  → RecoveryEdgeProvider
  → ConferenceEdgeRecoveryController (execution only)
  → ConferenceHealth
  → UI
```

Recovery mechanisms are sufficient; recovery **must lose the right to produce truth**. #196-class bugs are structural: media usable while recovery continues to manufacture obligations from roster inference.

---

## Decision 1 — Topology-first (not roster-first)

Conference media topology and recovery obligations MUST be derived in this order:

```text
Roster
  → Topology Authority
  → ActualMediaEdgeSet
  → RecoveryMediaEdgeSet
  → ConferenceHealth
  → UI
```

**Forbidden:**

```text
Roster → RecoveryMediaEdgeSet → WebRTC → UI
```

Roster membership MAY inform topology election and control-plane coordination. Roster membership MUST NOT directly determine which edges carry media recovery obligations.

---

## Decision 2 — Three distinct edge sets

Under `ConferenceMembership`, three edge sets are **normatively distinct**:

| Set | Meaning | Recovery obligation? |
|-----|---------|-------------------|
| **ControlEdgeSet** | Membership, election, negotiation, HELLO, and other control-plane relationships | **No** |
| **ActualMediaEdgeSet** | Media edges **formally admitted** under the current topology generation (contract layer) | — |
| **EstablishedMediaEdge** | Runtime: PeerConnection / transport actually established for an admitted edge | — (execution / health input; **not** topology truth) |
| **RecoveryMediaEdgeSet** | Media recovery obligations | **Yes; MUST be ⊆ admitted ActualMediaEdgeSet** |

`ActualMediaEdgeSet` in `ConferenceTopologySnapshot` is **admitted** edges only — what TopologyAuthority formally acknowledges. A legacy PeerConnection object surviving from generation N−1 does **not** re-admit that edge into generation N. PC CONNECTED state MUST NOT write back into the snapshot.

**Steady-state Anchor Conference:**

```text
RecoveryMediaEdgeSet == ActualMediaEdgeSet
```

(star topology: each participant ↔ elected Anchor)

A control edge (e.g. M01 ↔ M02 for election or negotiation) MUST NOT implicitly create a recovery obligation. Presence of a roster pair is **neither necessary nor sufficient** for a recovery edge.

---

## Decision 3 — Epoch-versioned topology

Each Conference is versioned by `(conferenceId, anchorEpoch)`.

1. **Exactly one** valid Anchor (primary) per `(conferenceId, anchorEpoch)`. Backup candidates exist only for failover candidacy, not concurrent dual-primary operation.
2. `anchorEpoch` is **monotonic**. Failover, partition heal, or authoritative topology change produces a **new** epoch.
3. On epoch increment, all media and recovery edges belonging to **prior generations** become **invalid**. They MUST NOT continue ICE restart, SUPERSEDE, or obligation churn under the old generation.
4. Within the **same** epoch, divergent Anchor primaries MUST converge via **deterministic arbitration** to a single primary. Last-writer-wins or indefinite dual-primary operation is a **contract violation**.
5. `same epoch / different primary` is **split-brain conflict**, not normal merge. System MUST emit auditable `ANCHOR_EPOCH_CONFLICT`; silent LWW is forbidden.
6. Deterministic winner rule (frozen contract): higher `anchorEpoch` wins; same epoch → deterministic primary rank wins; same rank → stable endpoint tie-break. **Same input set + same epoch → all nodes MUST converge to the same anchor.**
7. `ActualMediaEdgeSet` and `RecoveryMediaEdgeSet` entries MUST be bound to the topology generation (epoch) that authorized them.

**AuthorizedTransition** (see Invariants) is the only exception to strict current-generation membership during anchor handoff.

---

## Decision 4 — Anchor Conference media (4–10 participants)

1. Conference with **4–10** participants MUST use **elected Anchor / SFU-lite** topology (not full mesh).
2. Conference with **≤3** participants MAY retain mesh topology; field validation is a separate run card.
3. GROUP PTT and CONFERENCE **share** Anchor topology infrastructure (election, epoch, failover).
4. GROUP PTT and CONFERENCE **do not share** media semantics:
   - GROUP: single-floor relay via ProgramAudioBus
   - CONFERENCE: multi-speaker audio relay via ConferenceAudioBus (independent bus)
5. Anchor failover is **P0** for 8–10 scale, not an optimization. Failover MUST NOT cause unbounded recovery storm across all participants without bounded coordination.

---

## Decision 5 — ConferenceHealth ≠ edge recovery

User-visible Conference state MUST be driven by **ConferenceHealth**, not by individual edge recovery obligation state.

| Layer | Role |
|-------|------|
| **ConferenceHealth** | Room-level usability: `MEDIA_USABLE`, participant health, control recovery |
| **Edge recovery** | Per-edge diagnostic / background state; obligation lifecycle continues independently |

**Contract:**

- `MEDIA_USABLE = true` → user-facing **ONLINE** (hear/speak per product definition)
- Edge recovery retry, obligation open/close, and SUPERSEDE MUST NOT alone force room-level SYNCING when media is actually usable
- `DEGRADED` applies only when **MEDIA_USABLE is genuinely impaired**

This ADR defines the **separation principle** only. It does **not** authorize ADR-0055 UI implementation or UVCP projection changes.

**Target ConferenceHealth model (Phase 3 — specification reference):**

| Dimension | Role |
|-----------|------|
| **MediaUsable** | Room audio genuinely usable (hear/speak per product definition) |
| **AnchorHealthy** | Elected anchor reachable; epoch consistent |
| **MembershipConverged** | Roster / control plane consistent with topology authority |
| **RecoveryBackground** | Per-edge obligation state; diagnostic only |

```text
MediaUsable = true  →  user-facing ONLINE
MediaUsable = false ∧ anchor/topology impaired  →  DEGRADED / RECOVERING
obligationOpen alone  →  MUST NOT force room-level SYNCING when MediaUsable = true
```

ConferenceHealth MUST be a **room-level adjudicator**, not a rename of per-edge `recovering` projection.

---

## Decision 6 — Recovery execution seam (adapter, not rewrite)

Phase 2 MUST introduce a **single producer** for recovery edge membership. Multiple subsystems MUST NOT independently derive topology (participant manager, channel manager, roster, HELLO, WebRTC state, etc.).

**Normative seam:**

```text
ConferenceTopologyAuthority
  → ConferenceTopologySnapshot (atomic)
  → ActualMediaEdgeSet (admitted)
  → RecoveryEdgeProvider
  → ConferenceEdgeRecoveryController (ICE / REATTACH / TIMEOUT / TERMINAL only)
```

`RecoveryEdgeProvider` projects authoritative topology into recovery obligations. It is **not** a recovery controller enhancement.

**Provider contract:**

```text
Input:  ConferenceTopologySnapshot
Output: RecoveryEdgeSnapshot
          topologyGeneration, anchorEpoch
          edges: Set<RecoveryEdge>
          authorizedTransitions: Set<AuthorizedTransition>
          diff: RecoveryTopologyDiff (added / removed / retained / transitioned)
```

**RecoveryEdgeProvider MUST:**

- Emit recovery edges only for `mediaEdge ∈ actualMediaEdges` OR `∈ authorizedTransition.affectedEdges`
- Produce `RecoveryTopologyDiff` on generation change; controller MUST NOT re-derive

**RecoveryEdgeProvider MUST NOT:**

```text
create PeerConnection · ICE restart · signaling · owner election
retry · watchdog · SUPERSEDE · decide recovery success · decide UI health
derive edges from membership · derive edges from "peer recently seen"
```

**Controller retains:** attempt lifecycle, owner, lease, retry, terminal, ICE, reconnect, signaling, completion.

**Controller forbidden:** roster → edge inference, membership → implicit recovery, all-participant-pair recovery.

**Forbidden after Phase 2:**

```text
Roster → ConferenceEdgeKey → RecoveryController (direct)
```

---

## Invariants

```text
INV-056-1  ControlEdgeSet MUST NOT imply RecoveryMediaEdgeSet membership
INV-056-2  RecoveryMediaEdgeSet ⊆ ActualMediaEdgeSet (steady-state Anchor: equality)
INV-056-3  Prior-generation edges MUST NOT survive epoch increment
INV-056-4  Same-epoch dual-primary MUST NOT persist; deterministic arbitration required
INV-056-5  AuthorizedTransition MUST be epoch-authorized; pure time windows are forbidden
INV-056-6  UI projection MUST NOT be primary ConferenceHealth authority
INV-056-7  WiFi recovery / RNA / completion predicate domains remain frozen
INV-056-8  No new roster-derived recovery exceptions during ADR-0056 phased rollout
INV-056-9  RecoveryEdgeProvider is the sole recovery-edge membership producer after Phase 2
INV-056-10 Recovery MUST NOT create or mutate topology truth (downward flow only)
INV-056-11 Topology snapshots MUST publish atomically (no Frankenstein generation/epoch/anchor/edge tuples)
INV-056-12 PeerConnection object survival MUST NOT re-admit old-generation edges into current snapshot
INV-056-13 Anchor failover MUST NOT create O(N²) recovery obligations (Anchor steady state ≈ N−1 edges)
INV-056-14 MEDIA_USABLE requires topology admission valid; background recovery alone MUST NOT block ONLINE
```

**Core invariant (recovery edge validity):**

```text
RecoveryEdge valid iff
  edge ∈ ActualMediaEdgeSet(currentGeneration)
  OR
  edge ∈ AuthorizedTransition(fromGeneration → currentGeneration)
```

- `AuthorizedTransition` covers handoff when old PeerConnections tear down and new ones establish under a new epoch. Legitimacy MUST be derived from **topology generation / epoch authority**, not ad-hoc timers.
- **Forbidden:** wall-clock duration, retry count, or watchdog timeout as the **architectural** reason an edge exists in transition. Timers MAY bound **wait**; they MUST NOT **authorize** topology membership.
- Control-plane relationships MUST NOT create recovery edges by side effect.

---

## Non-goals (this ADR)

- Rewrite the per-edge recovery execution engine (existing edge controller remains execution layer; Phase 2 is adapter-only).
- Redesign GROUP PTT Anchor architecture (`ProgramAudioBus` and failover already exist; only config restore is deferred).
- Authorize ADR-0055 Track P / Track O implementation.
- Modify WiFi recovery main chain, RNA-5/6, or completion admission predicate.
- Define retry counts, obligation deadlines, ICE algorithms, or concrete class APIs.
- Change GROUP `CHANNEL_ANCHOR_THRESHOLD` (deferred to independent PR / run card; PTT Anchor architecture is **not** a gap).
- Accumulate Q11 / #196 roster-pair recovery exceptions.
- Add recovery controller branches during field runs before Phase 0.5 contract verification completes.

---

## Phase 0.5 — Contract freeze (NOT AUTHORIZED)

Phase 0.5 is a **Contract Freeze**, not "add a few data classes." No production controller migration. Freeze read models, authority boundaries, identity rules, and contract tests before Phase 1a code.

### Normative pipeline

```text
Membership
  ↓
ConferenceTopologyAuthority          ← sole topology truth source
  ↓
ConferenceTopologySnapshot           ← atomic publish only
  ↓
ActualMediaEdgeSet (admitted)
  ↓
RecoveryEdgeProvider                 ← contract defined in 0.5; wired in Phase 2
  ↓
ConferenceEdgeRecoveryController
  ↓
Recovery execution
```

```text
Recovery consumes topology.
Recovery MUST NOT define topology.
```

### ConferenceTopologyAuthority (sole truth source)

**Owns:** membership input, anchor, `anchorEpoch`, `topologyGeneration`, `topologyMode`, admitted `actualMediaEdges`.

**MUST NOT be fragmented across:** separate membership / anchor / recovery / WebRTC subsystems each maintaining partial topology.

**Publishes:** `ConferenceTopologySnapshot` atomically. Consumers see snapshot N **or** snapshot N+1 — never a mixed tuple (e.g. `generation=19, epoch=9, anchor=M01, edges=new`).

### ConferenceTopologySnapshot (normative fields)

```text
ConferenceTopologySnapshot
  conferenceId
  topologyGeneration              // monotonic; see semantics below
  anchorEpoch
  anchorId                        // exactly one primary per (conferenceId, anchorEpoch)
  topologyMode                    // MESH | ANCHOR
  members                         // roster membership (input only)
  actualMediaEdges                // admitted ActualMediaEdgeSet only
  generatedAt
```

`actualMediaEdges` = edges **formally admitted** in this generation — not theoretical roster pairs, not "PC object still alive."

Anchor example (`anchor=M01, generation=17, epoch=9`):

```text
M01↔M02, M01↔M03, M01↔M04, M01↔M05   ✅ admitted
M02↔M03, M02↔M04                     ❌ MUST NOT appear (roster ≠ media)
```

### MediaEdge (first-class object)

```text
MediaEdge
  conferenceId
  edgeId                          // stable logical identity (see below)
  local: EndpointId
  remote: EndpointId
  topologyGeneration              // validity context — identity boundary
  anchorEpoch                     // validity context — identity boundary
  role                            // e.g. ANCHOR_RELAY | MESH_DIRECT
  bearerScope                     // CONFERENCE
```

**Edge identity (frozen):**

```text
MediaEdgeId = conference + ordered endpoint pair   // stable across generations
topologyGeneration / anchorEpoch = validity context // NOT part of edgeId
```

Old edge `(M01↔M03, generation=16, epoch=8)` is **invalid** in generation 17 even if WebRTC object persists.

### RecoveryEdge (references MediaEdge — does not redefine endpoints)

```text
RecoveryEdge
  mediaEdge                       // required reference — cannot exist without MediaEdge
  obligationGeneration
  reason: RecoveryReason
  transitionAuthorization?        // present only under AuthorizedTransition
```

```text
RecoveryEdge contains MediaEdge — NOT parallel local/remote identity
```

### AuthorizedTransition

```text
AuthorizedTransition
  fromGeneration, toGeneration
  fromAnchorEpoch, toAnchorEpoch
  affectedEdges: Set<MediaEdgeId>
  authority: TransitionAuthority
  reason: TransitionReason
```

Example: anchor failover `G17/E9 → G18/E10`. Old `M01→M03` may exist **only** while listed in `affectedEdges` under this transition — not because "it happened 3 seconds ago."

### topologyGeneration semantics (frozen before code)

**Increments on:** anchor change, admitted `actualMediaEdges` set change, `topologyMode` change.

**Does NOT increment on:** ICE restart, candidate refresh, transient signaling, per-edge retry.

### anchorEpoch semantics (frozen before code)

Define normatively: epoch bump, same-epoch conflict (`ANCHOR_EPOCH_CONFLICT`), demotion, failover — before Phase 1c implementation.

### Phase 0.5 implementation checklist (all MUST freeze before Phase 1a code)

| # | Item |
|---|------|
| ① | Topology authority — single publisher of `ConferenceTopologySnapshot` |
| ② | Generation semantics — what increments `topologyGeneration` |
| ③ | Anchor epoch semantics — bump, conflict, demotion, failover |
| ④ | Edge identity — `MediaEdgeId` vs validity context |
| ⑤ | ActualMediaEdgeSet authority — who admits / removes edges (admitted layer) |
| ⑥ | Transition authorization — when old edges may temporarily survive |
| ⑦ | RecoveryEdgeProvider contract — input, output, invariants, forbidden ops |
| ⑧ | ConferenceHealth model — `MediaUsable`, `AnchorHealthy`, `MembershipConverged`, `RecoveryBackground`; `RecoveryBackground ≠ MediaUnavailable` |
| ⑨ | AudioMixer canonical format — see [Phase 1a spec](../analysis/0056-phase-1a-conference-audio-mcu-lite-spec.md) |
| ⑩ | Observability schema — `conferenceId`, `generation`, `anchorEpoch`, `anchorId`, `edgeId`, `mediaState`, `recoveryState`, `health` on one causal chain |

### Phase 0.5 exit criteria

1. `ConferenceTopologyAuthority` contract + atomic snapshot publish specified.
2. `MediaEdge` / `RecoveryEdge` / `AuthorizedTransition` identity rules frozen.
3. Admitted vs established media edge separation documented and testable.
4. `RecoveryEdgeProvider` interface + forbidden-ops list frozen (implementation deferred to Phase 2).
5. Phase 1a spec ACCEPTED.
6. Contract tests drafted (generation mismatch → invalid; no roster-derived recovery in target design).

### Architecture escape hatch (process rule — frozen)

Before any PR touching recovery, answer:

```text
Does this change modify: topology? media admission? recovery? health?
```

If change is **recovery-only** but root cause is **edge does not exist** → **FORBIDDEN** to add recovery exception. Fix truth at `TopologyAuthority → ActualMediaEdgeSet`.

---

## Known gaps (tracked separately)

Pre-implementation architecture review identified three gaps that MUST be resolved before Phase 1b field gates. Tracked in [#197](https://github.com/wangy4645/android-decentralized-talkback/issues/197) (not mixed with Q11 / #196):

| ID | Gap |
|----|-----|
| **A** | ConferenceAudioBus correctness — **Phase 1a spec ACCEPTED** ([spec](../analysis/0056-phase-1a-conference-audio-mcu-lite-spec.md)) |
| **B** | anchorEpoch not bound to media admission; same-epoch dual-primary possible; stale-generation edges not invalidated in recovery |
| **C** | RecoveryMediaEdgeSet derived from roster instead of ActualMediaEdgeSet |

---

## Implementation phases (reference only — NOT AUTHORIZED)

```text
Phase 0     ADR ACCEPTED; freeze new roster-derived recovery exceptions
Phase 0.5   Contract freeze (Authority + Snapshot + Edge identity + Provider contract)
Phase 1a    ConferenceAudioBus MCU-lite productization (spec linked)
Phase 1b    Conference 4+ Anchor; admitted ActualMediaEdgeSet == participant↔anchor
Phase 1c    Deterministic epoch arbitration; epoch → media admission; failover containment
            Gate 1 (4p) → Gate 2 (6p) → Gate 3 (8p)  [media + admission + failover proven]
Phase 2     RecoveryEdgeProvider wired to Controller (after Gate 3 PASS)
Phase 3     ConferenceHealth adjudicator → UI
Phase 4     Gate 4 (10p) stress / capacity — no new architecture
```

Each phase requires separate implementation authorization and field run card.

**Ordering constraints (v2 — conservative):**

- Phase 0.5 MUST complete before Phase 1a code.
- Phase 1a MUST precede Phase 1b.
- Phase 1c MUST precede Gate 2 (failover is industrial gate).
- **Phase 2 MUST NOT begin until Gate 3 (8 participants) PASS** — media admission, epoch cutover, and admitted edge set MUST be proven before Controller changes input source.
- Phase 3 follows Phase 2.
- Gate 4 (10p) follows Phase 2 + Phase 3.
- PTT threshold restore (6–8) is **parallel track**, not prerequisite for Conference phases.
- **Do NOT extend #196 / Q11 recovery patches** during Phases 0.5–1c.

**WIP isolation:** Q11 B1 + #196 E2 on `wip/pre-adr0056-q11-196`; MUST NOT merge with ADR or Phase 1+ PRs.

### Field gates — five verification layers (each gate)

Every gate MUST verify all five layers:

```text
Topology · Media · Recovery · Health · Resource
```

| Gate | N | Purpose |
|------|---|---------|
| **Gate 1** | 4 | Anchor topology takes over; `admitted ActualMediaEdgeSet == anchor↔participant`; `RecoveryEdge ⊆ ActualMediaEdgeSet` |
| **Gate 2** | 6 | Not a "4-person special path"; join/leave/anchor-leave; obligation count ≈ **N−1**, not **N×(N−1)** |
| **Gate 3** | 8 | Product capacity; CPU/memory/temp/latency curve **1→4→6→8**; 1-node + 2-node flap; join/leave during recovery |
| **Gate 4** | 10 | Architecture limit — stability only; Tests A–F (see below) |

Gate N MUST PASS before Gate N+1 authorization.

### Gate 4 mandatory scenarios (10 participants)

| Test | Scenario |
|------|----------|
| **A** | 10 participants, 3 simultaneous speakers |
| **B** | 10 participants, all speech activity |
| **C** | Anchor failure → epoch+1, generation cutover, new admitted edges, old edge retirement, no recovery storm |
| **D** | Anchor failure + packet loss |
| **E** | Anchor failure + concurrent join/leave |
| **F** | One wedged participant → MUST NOT cause conference-wide recovery storm |

### Telemetry invariants (auto-adjudication — field PASS authority)

```text
TEL-1  RecoveryMediaEdgeSet ⊆ ActualMediaEdgeSet(currentGeneration)
TEL-2  No recovery obligation for nonexistent admitted media edge
TEL-3  No old-generation recovery after generation cutover (except AuthorizedTransition)
TEL-4  same (conferenceId, anchorEpoch) → exactly one valid anchor
TEL-5  Anchor failover → obligation count O(N), not O(N²)
TEL-6  media CONNECTED + receivePathLive + topology admission valid → MEDIA_USABLE
       (control reconciliation outstanding alone → MUST NOT SYNCING forever)
```

### Industrial completion standard (Conference track)

```text
Architecture:  Membership → Authority → Snapshot → AdmittedMediaEdge → RecoveryProvider → Health → UI
Invariants:    RecoveryEdge ⊆ AdmittedMediaEdge; generation mismatch → invalid
Media:         4–10 Anchor MCU-lite; no PCM corruption; anchor speaks
Recovery:      bounded, terminal, no unbounded churn; no roster-derived edges
UX:            MediaUsable → ONLINE; RecoveryBackground ≠ room SYNCING
Scale:         Gates 1–4 PASS; resource curve 1→4→6→8→10 on target hardware
```

## Risks

| Risk | Mitigation (contract-level) |
|------|----------------------------|
| Anchor single point of failure | Failover + epoch increment + bounded rebuild; field gate required |
| Failover recovery storm | Phase 1c + Phase 4 gates; backoff / coordination required before 8–10 |
| PCM relay CPU at anchor | 8→10 staged field gates; measured on target Android hardware |
| Premature mesh extension to 10 | Decision 4 forbids full mesh at 8–10 |

---

## References

- Issue: [#197](https://github.com/wangy4645/android-decentralized-talkback/issues/197)
- Phase 1a spec: [0056-phase-1a-conference-audio-mcu-lite-spec.md](../analysis/0056-phase-1a-conference-audio-mcu-lite-spec.md)
- Prior architecture review: agent transcript `b6e09de2-9914-4cb6-b003-d2a819c6f476`
- [ADR-0055](./0055-conference-recovery-outcome-convergence.md)
- [ADR-0054](./0054-conference-edge-recovery-liveness-ownership.md)
- [ADR-0052](./0052-conference-transport-scope-admission-recovery-gate.md)
- [ADR-0010](./0010-conference-membership-vs-media-projection.md)
- WIP branch: `wip/pre-adr0056-q11-196` (Q11 / #196; out of scope for this ADR)
