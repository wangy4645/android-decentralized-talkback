# ADR-0056: Conference Topology-First Architecture

## Status

**ACCEPTED** (2026-08-14) · **Amended v2.1** (2026-08-14: Phase 0.5 mapping — `AdmittedRecoveryTarget`, `meshGeneration`, Q6-B) · **Phase 0.5 CONTRACT PASS** · **Implementation NOT AUTHORIZED** (restricted 1a-1/1a-2 only)

**Branch:** `adr/0056-conference-topology-first`

**Issue:** [#197](https://github.com/wangy4645/android-decentralized-talkback/issues/197)

**Phase 0.5 artifact:** [0056-phase-05-contract-mapping.md](../analysis/0056-phase-05-contract-mapping.md)

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
Primary invariant:     AdmittedRecoveryTarget valid iff admitted MediaEdge in current meshGeneration OR epoch-authorized transition
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
  → AdmittedRecoveryTarget (via RecoveryEdgeProvider)
  → ADR-0022 Recovery Edge
  → ConferenceHealth
  → UI
```

**Forbidden:**

```text
Roster → AdmittedRecoveryTarget → WebRTC → UI   // without topology authority — forbidden
```

Roster membership MAY inform topology election and control-plane coordination. Roster membership MUST NOT directly determine which edges carry media recovery obligations.

---

## Decision 2 — Three distinct edge sets

Under `ConferenceMembership`, three edge sets are **normatively distinct**:

| Set | Meaning | Recovery obligation? |
|-----|---------|-------------------|
| **ControlEdgeSet** | Membership, election, negotiation, HELLO, and other control-plane relationships | **No** |
| **ActualMediaEdgeSet** | Media edges **formally admitted** under current `meshGeneration` (contract layer) | — |
| **EstablishedMediaEdge** | Runtime: PeerConnection / transport established for an admitted edge | — (observation; **not** topology truth) |
| **AdmittedRecoveryTarget** | Provider projection referencing admitted `MediaEdge` | Maps to ADR-0022 Recovery Edge at execution bind time |

`ActualMediaEdgeSet` in `ConferenceTopologySnapshot` is **admitted** edges only. A legacy PeerConnection surviving from generation N−1 does **not** re-admit that edge.

**Steady-state Anchor Conference:**

```text
|AdmittedRecoveryTarget| == |ActualMediaEdgeSet|
```

(star topology: each participant ↔ elected Anchor)

A control edge MUST NOT implicitly create recovery eligibility. **ADR-0022 Recovery Edge** (execution obligation) MUST NOT be created without an `AdmittedRecoveryTarget` from topology projection (Phase 2).

---

## Decision 3 — Epoch-versioned topology

Each Conference is versioned by `(conferenceId, anchorEpoch)`.

1. **Exactly one** valid Anchor (primary) per `(conferenceId, anchorEpoch)`. Backup candidates exist only for failover candidacy, not concurrent dual-primary operation.
2. `anchorEpoch` is **monotonic**. Failover, partition heal, or authoritative topology change produces a **new** epoch.
3. On epoch increment, all media and recovery edges belonging to **prior generations** become **invalid**. They MUST NOT continue ICE restart, SUPERSEDE, or obligation churn under the old generation.
4. Within the **same** epoch, divergent Anchor primaries MUST converge via **deterministic arbitration** to a single primary. Last-writer-wins or indefinite dual-primary operation is a **contract violation**.
5. `same epoch / different primary` is **split-brain conflict**, not normal merge. System MUST emit auditable `ANCHOR_EPOCH_CONFLICT`; silent LWW is forbidden.
6. Deterministic winner rule (frozen contract): higher `anchorEpoch` wins; same epoch → deterministic primary rank wins; same rank → stable endpoint tie-break. **Same input set + same epoch → all nodes MUST converge to the same anchor.**
7. `ActualMediaEdgeSet` and `AdmittedRecoveryTarget` entries MUST be bound to the `meshGeneration` / `anchorEpoch` that authorized them.

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
Output: RecoveryTargetSnapshot
          rosterEpoch, anchorEpoch, meshGeneration
          targets: Set<AdmittedRecoveryTarget>
          authorizedTransitions: Set<AuthorizedTransition>
          diff: RecoveryTargetDiff (added / removed / retained / transitioned)
```

**RecoveryEdgeProvider MUST:**

- Emit `AdmittedRecoveryTarget` only when `mediaEdge ∈ actualMediaEdges` OR `mediaEdge ∈ authorizedTransition.affectedEdges`
- Emit explicit **removal** in diff when `MediaEdge` drops from snapshot (Q6-B)
- Produce `RecoveryTargetDiff` on `meshGeneration` change; controller MUST NOT re-derive

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
INV-056-1  ControlEdgeSet MUST NOT imply AdmittedRecoveryTarget
INV-056-2  AdmittedRecoveryTarget MUST reference admitted MediaEdge (steady-state Anchor: |targets| == |ActualMediaEdgeSet|)
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
INV-056-15 MediaEdge removal invalidates future recovery admission (Q6-B); explicit topology-invalidated close path
INV-056-16 Topology-invalidated close MUST NOT mutate membership (R29)
```

**Terminology (ADR-0056 vs ADR-0022 — frozen):**

| ADR-0056 | ADR-0022 |
|----------|----------|
| `AdmittedRecoveryTarget` | **Recovery Edge** (obligation on `ConferenceEdgeKey`) |
| `MediaEdge` | Not the same as Recovery Edge key |
| `meshGeneration` | Not `obligationGeneration` |
| `membershipEpochConverged` | **Fact/probe** — not an epoch axis |
| `rosterEpoch` | Membership authority version (`TopologyDigest`) |

**Core invariant (admission validity):**

```text
AdmittedRecoveryTarget valid iff
  mediaEdge ∈ ActualMediaEdgeSet(current meshGeneration)
  OR
  mediaEdge ∈ AuthorizedTransition.affectedEdges
```

ADR-0022 Recovery Edge execution lifecycle is **unchanged**; Phase 2 changes **which targets** may bind to obligations.

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
- Add recovery controller branches during field runs before Phase 0.5 contract verification completes — **Phase 0.5 CLOSED**; escape hatch applies.

---

## Phase 0.5 — Contract freeze (**CONTRACT PASS**)

Phase 0.5 maps ADR-0056 onto existing `TopologyDigest` / ADR-0022 / R29. **Authoritative artifact:** [0056-phase-05-contract-mapping.md](../analysis/0056-phase-05-contract-mapping.md).

### Four epoch axes (frozen)

```text
rosterEpoch           membership authority version
anchorEpoch           anchor authority version
meshGeneration        media topology generation (wire field — activated by ADR-0056)
obligationGeneration  recovery execution lineage (ADR-0022 — unchanged)
```

**Do not introduce `topologyGeneration`.** `membershipEpochConverged` is a **fact/probe**, not a fifth axis.

**Conference `meshGeneration` bump rules** and **GROUP boundary** — see mapping doc. `meshGeneration ≠ anchorEpoch`.

### Key v2.1 contracts (summary)

- **`ConferenceTopologyAuthority`** — new facade; v1 MAY be `TalkbackCoordinator.publishConferenceTopologySnapshot(...)`
- **`AdmittedRecoveryTarget`** — replaces ADR-0056 `RecoveryEdge` name; does not replace ADR-0022 Recovery Edge
- **Q6-B** — topology edge removal → `EDGE_REMOVED_BY_TOPOLOGY`; explicit Controller invalidation
- **Provider** — stateless `f(Snapshot)`; forbidden to merge transport + recovery admission predicates

### Phase 0.5 status

```text
CONTRACT PASS · IMPLEMENTATION READY (restricted Phase 1a-1 / 1a-2 only)
```

### Architecture escape hatch (frozen)

Recovery-only fix when edge does not exist → **FORBIDDEN**. Fix `TopologyAuthority → ActualMediaEdgeSet`.

---

## Known gaps (tracked separately)

Pre-implementation architecture review identified three gaps that MUST be resolved before Phase 1b field gates. Tracked in [#197](https://github.com/wangy4645/android-decentralized-talkback/issues/197) (not mixed with Q11 / #196):

| ID | Gap |
|----|-----|
| **A** | ConferenceAudioBus correctness — **Phase 1a spec ACCEPTED** ([spec](../analysis/0056-phase-1a-conference-audio-mcu-lite-spec.md)) |
| **B** | anchorEpoch not bound to media admission; same-epoch dual-primary possible; stale-generation edges not invalidated in recovery |
| **C** | Recovery targets derived from roster instead of `ActualMediaEdgeSet` — Phase 2 via `AdmittedRecoveryTarget` |

---

## Implementation phases (reference only — NOT AUTHORIZED)

```text
Phase 0     ADR ACCEPTED; freeze new roster-derived recovery exceptions
Phase 0.5   Contract mapping — **CLOSED** (see mapping artifact)
Phase 1a-1  AudioMixer unit tests only (**restricted authorization**)
Phase 1a-2  PcmInjectionPort contract tests only (**restricted authorization**)
Phase 1a-3  ParticipantMediaMode + ConferenceAudioBus integration (after 1a-1/1a-2 PASS)
Phase 1a    Full Phase 1a field gate (T1–T10)
Phase 1b    Conference 4+ Anchor; admitted ActualMediaEdgeSet == participant↔anchor
Phase 1c    Deterministic epoch arbitration; epoch → media admission; failover containment
            Gate 1 (4p) → Gate 2 (6p) → Gate 3 (8p)  [media + admission + failover proven]
Phase 2     RecoveryEdgeProvider wired to Controller (after Gate 3 PASS)
Phase 3     ConferenceHealth adjudicator → UI
Phase 4     Gate 4 (10p) stress / capacity — no new architecture
```

Each phase requires separate implementation authorization and field run card.

**Ordering constraints (v2 — conservative):**

- Phase 0.5 **CLOSED** — restricted **1a-1/1a-2** authorized; see mapping doc forbidden list.
- Phase 1a-3+ MUST NOT begin until 1a-1/1a-2 PASS.
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
| **Gate 1** | 4 | Anchor cutover; `ActualMediaEdgeSet == anchor↔participant`; `AdmittedRecoveryTarget ⊆ ActualMediaEdgeSet` |
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
TEL-1  AdmittedRecoveryTarget ⊆ ActualMediaEdgeSet(current meshGeneration)
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
Invariants:    AdmittedRecoveryTarget ⊆ Admitted MediaEdge; meshGeneration mismatch → invalid
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
- Phase 0.5 mapping: [0056-phase-05-contract-mapping.md](../analysis/0056-phase-05-contract-mapping.md)
- Phase 1a spec: [0056-phase-1a-conference-audio-mcu-lite-spec.md](../analysis/0056-phase-1a-conference-audio-mcu-lite-spec.md)
- [ADR-0022](./0022-recovery-completion-ownership.md) — Recovery Edge / Attempt (unchanged)
- Prior architecture review: agent transcript `b6e09de2-9914-4cb6-b003-d2a819c6f476`
- [ADR-0055](./0055-conference-recovery-outcome-convergence.md)
- [ADR-0054](./0054-conference-edge-recovery-liveness-ownership.md)
- [ADR-0052](./0052-conference-transport-scope-admission-recovery-gate.md)
- [ADR-0010](./0010-conference-membership-vs-media-projection.md)
- WIP branch: `wip/pre-adr0056-q11-196` (Q11 / #196; out of scope for this ADR)
