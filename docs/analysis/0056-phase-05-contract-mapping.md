# ADR-0056 Phase 0.5: Contract Mapping Matrix

## Status

**FROZEN** (2026-08-14) · **Phase 0.5 CONTRACT PASS** (pending v2.1 ADR merge)

**Parent:** [ADR-0056](../adr/0056-conference-topology-first.md) (v2.1) · **Issue:** [#197](https://github.com/wangy4645/android-decentralized-talkback/issues/197)

**Purpose:** Map ADR-0056 contracts onto existing authority / digest / recovery vocabulary. Phase 0.5 does **not** invent a parallel topology universe.

---

## Normative chain (frozen)

```text
Membership authority
        ↓
ConferenceTopologyAuthority          (facade — see § Authority)
        ↓ atomic publish
ConferenceTopologySnapshot
        ↓
ActualMediaEdgeSet (admitted)
        ↓
RecoveryEdgeProvider               f(Snapshot) only — Phase 2
        ↓
Set<AdmittedRecoveryTarget>
        ↓ resolve binding
ConferenceEdgeKey + ADR-0022 Recovery Edge
        ↓
RecoveryAttempt / obligationGeneration
```

```text
Only facts flow downward.
Recovery MUST NOT create topology truth.
```

---

## Mapping matrix

| ADR-0056 contract | Existing code / model | Current writer | State | Phase 0.5 conclusion |
|-------------------|----------------------|----------------|-------|----------------------|
| `ConferenceTopologySnapshot` | Session + roster + anchor + digest scattered | Multiple partial writers | 🔴 multi-source | New **read-only DTO**; single authority transaction publishes |
| `members` | `canonicalRoster` / conference participants | `ConferenceParticipantManager` | ✅ | Reuse membership authority; do not duplicate roster |
| `rosterEpoch` | `TopologyDigest.rosterEpoch` | Membership authority | ✅ | **Keep name and semantics** — membership axis version |
| `anchorEpoch` | `TopologyDigest.anchorEpoch` | Anchor election / failover | 🟡 | Keep; same-epoch dual-primary → `ANCHOR_EPOCH_CONFLICT`, no LWW |
| `meshGeneration` | `TopologyDigest.meshGeneration` (wire field; P0 often `0`) | Reserved / dormant | 🟡 activate | **Reuse field** as media-topology generation — see § Epoch axes |
| ~~`topologyGeneration`~~ | — | — | ❌ removed | **Do not add**; maps to `meshGeneration` |
| `ActualMediaEdgeSet` | No canonical set; peers inferred from roster/mesh | Implicit multi-path | 🔴 gap | Snapshot field; admitted inside authority transaction only |
| `MediaEdge` | `ConferenceEdgeKey(sessionId, remoteModuleId)` is execution key only | Controller | 🟡 partial | New topology-level identity; **not** `ConferenceEdgeKey` |
| ~~ADR-0056 `RecoveryEdge`~~ | Collides with ADR-0022 Recovery Edge | Controller | 🔴 rename | → **`AdmittedRecoveryTarget`** |
| ADR-0022 Recovery Edge | `(sessionId, remoteModuleId)` + episodes | `ConferenceEdgeRecoveryController` | ✅ frozen | **Do not change** R28 lifecycle |
| `RecoveryAttempt` | `RecoveryEpisode` / `attemptId` | Controller | ✅ frozen | Phase 2 changes **input eligibility only** |
| `obligationGeneration` | Recovery wire / episode | Execution layer | ✅ frozen | **Fourth axis** — independent of `meshGeneration` |
| `membershipEpochConverged` | `MembershipEpochConvergenceProbe` | Probe / fact | ✅ | **Convergence fact**, not an epoch axis — see § Terminology |
| `AuthorizedTransition` | None | — | 🔴 new | Topology authority only; explicit `affectedEdges` |
| `RecoveryEdgeProvider` | None; Controller infers edges | Controller today | 🔴 gap | Phase 2: stateless projection `f(Snapshot)` |
| `ConferenceEdgeRecoveryController` | Existing execution | Controller | ✅ | Retain; execution layer only |
| `ConferenceHealth` | Runtime / Presence projectors | Projectors | 🟡 | New room adjudicator; obligation ≠ UI |
| `AudioMixer` | `ConferenceAudioBus` PCM relay skeleton | Bus | 🔴 | Phase 1a-1 |
| `PcmInjectionPort` | `feedProgramPcm` + reflection | Bus | 🔴 | Phase 1a-2 |
| `ParticipantMediaMode` | Incomplete Program/Conference modes | Audio wiring | 🟡 | Phase 1a-3 after 0.5 close |
| `ConferenceAudioBus` | Session wiring | Bus | 🟡 | **Read** snapshot; **forbidden** write topology |

**References:** `TopologyDigest` — `android-board-talkback/.../TopologyDigest.kt`; ADR-0022 R28-A Recovery Edge vs Attempt; recovery wire `obligationGeneration`; GROUP remediation — `meshGeneration` reserved P0.

---

## Epoch axes (four — frozen)

```text
rosterEpoch           membership authority version
anchorEpoch           anchor authority version
meshGeneration        media topology generation (field reuse — activated by ADR-0056)
obligationGeneration  recovery execution lineage (ADR-0022 — unchanged)
```

```text
rosterEpoch ≠ anchorEpoch ≠ meshGeneration ≠ obligationGeneration
```

### `meshGeneration` activation (Conference)

Historical wire name retained. Semantics upgraded:

> `meshGeneration` is the monotonic **media-topology generation** in `TopologyDigest`. ADR-0056 **activates** this field for Conference; it is not a new counter.

**Bump rules (Conference — frozen):**

| Event | `rosterEpoch` | `anchorEpoch` | `meshGeneration` |
|-------|---------------|---------------|------------------|
| Roster membership change | +1 | — | +1 **only if** `ActualMediaEdgeSet` changes |
| Anchor failover | — | +1 | +1 |
| Topology mode change (MESH↔ANCHOR) | — | — | +1 |
| Admitted edge set change (same roster) | — | — | +1 |
| ICE restart / candidate refresh / signaling transient | — | — | **no bump** |

```text
meshGeneration ≠ anchorEpoch
```

Both may increment on anchor failover; they are not interchangeable comparators.

**GROUP boundary (frozen):**

> `meshGeneration` shares the digest **wire field** with GROUP. ADR-0056 defines **Conference** bump rules only. GROUP bump semantics remain under GROUP / remediation contracts — ADR-0056 MUST NOT silently rewrite PTT semantics.

---

## Terminology (non-negotiable)

| Term | Role |
|------|------|
| `rosterEpoch` | **Version number** — membership authority |
| `membershipEpochConverged` | **Local fact / probe** — NOT an epoch axis |
| `MediaEdge` | Topology admitted edge identity |
| `AdmittedRecoveryTarget` | Topology projection: this edge may be recovered |
| Recovery Edge (ADR-0022) | Execution obligation on `(sessionId, remoteModuleId)` |
| `ICE_CONNECTED` | Runtime **observation** |
| MediaEdge **ADMITTED** | Topology authority **decision** |

---

## ConferenceTopologyAuthority (minimal landing)

**Status:** **New boundary — not an existing class.**

Phase 0.5 defines a **facade / transaction boundary**, not a large new subsystem.

**Phase 1 landing (allowed):**

```text
TalkbackCoordinator.publishConferenceTopologySnapshot(...)
```

**Target:**

```text
all topology mutations → ConferenceTopologyAuthority → atomic snapshot publish
```

WebRTC / media runtime → **observations only** → authority admission decision → new snapshot.

---

## MediaEdge ↔ ConferenceEdgeKey binding (Q6)

```text
MediaEdge
  conferenceId, mediaEdgeId, local, remote
  meshGeneration, anchorEpoch, role

ConferenceEdgeKey
  (sessionId, remoteModuleId)          // execution — unchanged
```

**Binding chain (Phase 2):**

```text
MediaEdge
    → AdmittedRecoveryTarget
    → Provider resolves binding
    → ensureEdge(ConferenceEdgeKey)
    → existing Recovery Edge
```

Controller receives `mediaEdgeId`, `meshGeneration`, `anchorEpoch` on admission. Controller asks:

```text
"Does this target still belong to valid topology lineage?"
```

Controller MUST NOT infer:

```text
"Does this edge exist at all?"
```

---

## Q6-B — Topology edge removal (frozen)

When snapshot N+1 removes a `MediaEdge` that had an OPEN Recovery Edge:

```text
Topology edge removed
    → RecoveryEdgeProvider emits removal in diff
    → Controller receives topology invalidation
```

**Semantics (does not replace R28):**

1. Topology removal **revokes future attempt admission** for that target.
2. In-flight attempt: completes under existing R28 terminal contract.
3. Open obligation not yet terminal: closed via explicit **`EDGE_REMOVED_BY_TOPOLOGY`** path — distinct from `MEMBER_LEFT` / `CONFERENCE_TERMINATED`.
4. **No new recovery lifecycle** — ADR-0022 episode rules still govern terminal disposition.

**Hard review rule:** `EDGE_REMOVED_BY_TOPOLOGY` is topology → recovery **close reason only**. Route through existing Recovery Edge terminal / close machinery. MUST NOT add recovery phases, retry, supersede, or watchdog (would rewrite R28).

```text
INV-056-15  MediaEdge removal invalidates future recovery admission
INV-056-16  Topology-invalidated close ≠ membership mutation (R29)
```

Provider emitting nothing on next snapshot is **insufficient** — Controller MUST process explicit removal invalidation.

---

## RecoveryEdgeProvider contract (frozen)

```text
Input:  ConferenceTopologySnapshot (optional Snapshot[N-1] for diff)
Output: Set<AdmittedRecoveryTarget> + RecoveryEdgeDiff
```

**Stateless:** `f(Snapshot[N])` or `f(N-1, N)` — no `retryCount`, `lastSeenPeer`, ICE state, membership side channel.

**Forbidden merges (Phase 2 implementation checklist):**

```text
TransportAdmission + ConferenceRecoveryAdmission + AdmittedRecoveryTarget
    → single predicate     ❌ FORBIDDEN
```

Layers remain:

```text
TransportAdmission → ConferenceRecoveryAdmission → AdmittedRecoveryTarget → Controller
```

---

## Phase 0.5 review questions — answers

| Q | Question | Frozen answer |
|---|----------|---------------|
| **Q1** | Who publishes `ConferenceTopologySnapshot`? | `ConferenceTopologyAuthority` (facade; v1 may be Coordinator method) |
| **Q2** | Who decides `ActualMediaEdgeSet`? | Same authority, **inside** snapshot transaction |
| **Q3** | When do the four axes change? | See § Epoch axes bump table |
| **Q4** | How are targets derived? | Pure deterministic Provider projection |
| **Q5** | Who creates transitions? | Topology authority only; explicit `affectedEdges`; no wildcard |
| **Q6** | MediaEdge ↔ ConferenceEdgeKey? | Provider resolves; Controller does not infer topology |
| **Q6-B** | Edge removed while obligation open? | Topology invalidation path; see § Q6-B |

---

## Phase 0.5 close checklist

```text
[x] Mapping matrix frozen (this document)
[x] Terminology: RecoveryEdge → AdmittedRecoveryTarget
[x] meshGeneration activation + bump rules
[x] meshGeneration ≠ anchorEpoch; GROUP boundary stated
[x] ConferenceTopologyAuthority minimal facade
[x] MediaEdge ↔ ConferenceEdgeKey binding
[x] Q6-B topology removal contract
[x] membershipEpochConverged = fact, not epoch
[x] ADR-0056 v2.1 merged (companion amendment)
```

**Gate:**

```text
Phase 0.5 CLOSED  →  CONTRACT PASS / IMPLEMENTATION READY
```

**Then authorized (restricted):**

```text
Phase 1a-1  AudioMixer unit tests
Phase 1a-2  PcmInjectionPort contract tests
```

**Phase 1a-1/1a-2 forbidden:**

```text
❌ import ConferenceEdgeRecoveryController
❌ modify TalkbackCoordinator topology path
❌ modify roster / anchor / digest
❌ modify recovery semantics
❌ lower Phase 1a spec gates for tests
❌ use production ConferenceTopologySnapshot as mixer test fixture
```

---

## v2.1 ADR amendments (summary)

1. `RecoveryEdge` → `AdmittedRecoveryTarget` (ADR-0056 scope only)
2. `topologyGeneration` → `meshGeneration` (semantic activation)
3. MediaEdge ↔ ConferenceEdgeKey binding + Q6-B removal contract
4. `ConferenceTopologyAuthority` as facade boundary
5. Four epoch axes + `membershipEpochConverged` fact clarification

---

## References

- [ADR-0056](../adr/0056-conference-topology-first.md)
- [ADR-0022](../adr/0022-recovery-completion-ownership.md) — R28 Recovery Edge
- [ADR-0023](../adr/0023-conference-membership-mutation-authority-boundary.md) — R29
- [Phase 1a spec](./0056-phase-1a-conference-audio-mcu-lite-spec.md)
- [#197](https://github.com/wangy4645/android-decentralized-talkback/issues/197)
- `TopologyDigest.kt`, `EdgeRecoveryModels.kt` (`ConferenceEdgeKey`)
- `GROUP-Membership-Debt-Remediation-v1.0.md` — `meshGeneration` P0 reserved
