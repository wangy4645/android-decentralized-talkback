# ADR-0056 Phase 0.5 — NTI × Topology Authority Mapping

## Status

**FROZEN** (2026-09-18)

```text
Parent contracts:
  ADR-0063 Negotiation Transaction Integrity     FROZEN
  ADR-0056 topology-first                        ACCEPTED
  0056-phase-05-contract-mapping.md              FROZEN (2026-08-14)

This document:                                  FROZEN (NTI × topology slice)
Scope:                                           design / mapping only
Production code:                                 NOT AUTHORIZED
Current MESH behavior:                           NOT AUTHORIZED to change
Field / multicast / Meeting GA:                  NOT AUTHORIZED

Gate:
  Phase 0.5 NTI mapping CLOSED
    → Phase 1a (AudioMixer / PcmInjectionPort) AUTHORIZED
```

**Purpose:** Answer — under Anchor/SFU-lite, who produces each negotiation / admission / readiness fact, without re-inventing transaction semantics per topology.

**Does NOT replace:** [0056-phase-05-contract-mapping.md](./0056-phase-05-contract-mapping.md) (recovery / epoch / provider matrix).

---

## Architecture stack (frozen)

```text
                    Product / Scale (8–9 convergence)
                              |
                ADR-0056 topology-first
          (MESH → Anchor/SFU-lite; ActualMediaEdgeSet)
                              |
              ADR-0063 Negotiation Transaction Integrity
          (per-edge transaction correctness; Q1–Q10)
                              |
                 WebRTC engine / transport
```

```text
Contract frozen ≠ all implementation conform   (ADR-0063 Q10)
Topology changes WHO negotiates on WHICH edge
NTI defines WHAT a legal transaction IS on any edge
```

---

## §1 — Current MESH (≤3 participants)

### Topology shape

```text
M01 ──edge── M02
  \
   └──edge── M03
```

Each `(sessionId, remoteModuleId)` = one **MediaEdge execution binding** = one PeerConnection.

### Fact ownership (MESH — as-is)

| Fact | Authoritative owner | Producer seam | Consumer (read-only) |
|------|---------------------|---------------|----------------------|
| **Settlement truth** | `WebRtcAudioEngine` per edge | `applyRemoteOffer` / `createOffer` → `justSettledAsAnswerer()` | Coordinator answerer gates |
| **Commit truth** | `WebRtcAudioEngine` + Coordinator | `commitAnswererTransaction` after handoff | Recovery drain, admission READY |
| **Negotiation publish** | `TalkbackCoordinator` | `GROUP_ACCEPT`, classification logs | Signaling transport |
| **Admission phase** | `ConferenceAdmissionTracker` | `transitionConferenceAdmission` | Recovery gate, edge restart suppress |
| **Admission handoff latch** | `ConferenceAdmissionTracker` | `begin/completeAdmissionHandoff` | `canRestartConferenceEdge` |
| **Topology membership (roster)** | Membership authority | `ConferenceParticipantManager` / reducer | Topology compose **input** (not writer today) |
| **ActualMediaEdgeSet** | *implicit* — roster ≈ edges | Coordinator mesh setup | Recovery (inferred — **gap**) |
| **Media established** | Runtime observation | ICE CONNECTED + `meshCompletedModules` | Presence, UI readiness |
| **Participant active** | Coordinator | `markConferenceParticipantActive` post-commit | Projector, roster media column |
| **Recovery obligation** | `ConferenceEdgeRecoveryController` | `beginRecovery` / completion | Presence recovering set |
| **Recovery complete** | Completion authority (ADR-0022/0038) | `markRecovered` / `RECOVERY_EDGE_RECOVERED` | Runtime projection |

### NTI seam binding (MESH)

| Seam | Edge context | ADR-0063 Q10 |
|------|--------------|--------------|
| `JOIN_FIRST` | first attach / rejoin answerer | **CONFORM** |
| `INVITE` | conference invite answerer | **CONFORM** |
| `JOIN` | WiFi recovery / ICE restart on completed mesh | **GAP** |
| `RECONNECT` | invite reconnect | **GAP** |
| `GROUP` | group accept engine path | **GAP** |

MESH implementation **MAY** continue operating with Q10 gaps on legacy seams until those seams are touched; **MUST NOT** add new answerer seams without full NTI conform.

---

## §2 — Future Anchor / SFU-lite (4–10 participants)

### Topology shape

```text
        M01 (Anchor)
       /    \
   edge      edge
   /            \
 M02            M03

(M02 ↔ M03 direct media edge: ABSENT)
```

**Steady state:** `|ActualMediaEdgeSet| = N−1` star edges (each non-anchor ↔ anchor).

### Questions this mapping answers

| Question | MESH (wrong to copy) | Anchor (frozen answer) |
|----------|----------------------|------------------------|
| Who is "member active"? | per-peer meshCompleted | **Membership projector** reads roster + **EstablishedMediaEdge** on participant↔anchor edge |
| Who is "media ready"? | pairwise ICE on every roster pair | **EstablishedMediaEdge** on admitted `MediaEdge` only; room usable via **ConferenceHealth.MediaUsable** |
| Who is "recovery complete"? | any pairwise recovery edge | **only** on `AdmittedRecoveryTarget` bound to current `meshGeneration` |
| Who negotiates M02↔M03? | Coordinator mesh join | **NOBODY** — not in `ActualMediaEdgeSet` |

### Fact ownership (Anchor — target)

| Fact | Authoritative owner | Change from MESH |
|------|---------------------|------------------|
| **Settlement truth** | Edge-scoped `WebRtcAudioEngine` (one PC per `MediaEdge`) | Same NTI rule; edge identity = **`MediaEdge`**, not roster pair inference |
| **Commit truth** | Coordinator (per edge execution key) | Same ordering (Q5); execution key still `(sessionId, remoteModuleId)` at bind time |
| **Negotiation publish** | Coordinator | Same INV-NTI-002..010; new seam labels `ANCHOR_UPLINK` / `ANCHOR_DOWNLINK` |
| **Admission phase** | `ConferenceAdmissionTracker` (or successor) keyed by **`(sessionId, mediaEdgeId)`** | Key **MUST NOT** remain implicit roster-pair-only |
| **Admission handoff** | Admission controller | Same lifecycle (ADR-0063 Q7); bound to **edge admission attempt**, not room |
| **Topology membership** | Membership authority | Unchanged — roster input only |
| **ActualMediaEdgeSet** | **`ConferenceTopologyAuthority`** | **Single writer** inside snapshot transaction |
| **Media established** | Runtime → **observation** | `EstablishedMediaEdge` fact; **does not** re-admit edges |
| **Participant active** | **Projector / admission** after NTI success chain | **MUST NOT** be set by Coordinator bypassing topology admission |
| **Recovery obligation** | Controller on **`AdmittedRecoveryTarget` only** | No M02↔M03 obligation |
| **Recovery complete** | ADR-0022 completion on bound Recovery Edge | Target must pass Provider lineage check |
| **Room media usable** | **`ConferenceHealth` adjudicator** | **NOT** pairwise meshCompleted aggregation |

### Forbidden Anchor shortcuts

```text
roster pair exists → create Recovery Edge                    ❌
M02 heard M03 via mesh memory → keep negotiating M02↔M03     ❌
topology mode change without meshGeneration bump             ❌
anchor seam skips NOT_SETTLED gate "because SFU"             ❌
ConferenceHealth = any edge recovering → room SYNCING        ❌ (when MediaUsable=true)
```

---

## §3 — ADR-0063 × ADR-0056 mapping matrix

Cross-topology invariant: **transaction semantics are invariant; topology selects edges.**

| ADR-0063 concept | MESH execution | Anchor execution | Topology authority role |
|------------------|----------------|------------------|-------------------------|
| Negotiation Transaction (Q1) | per `(sessionId, remoteModuleId)` PC | per admitted `MediaEdge` PC | admits which edges **exist** |
| Authoritative owner (Q2) | Engine per edge | Engine per edge | **never** owns settlement |
| Concurrent offer winner (Q3) | Engine polite/impolite | Same | N/A |
| Loser disposition (Q4) | Engine + Coordinator classify | Same | N/A |
| Success facts (Q5) | post-commit meshCompleted / participantActive | post-commit **EstablishedMediaEdge** + projector inputs | **never** publishes success facts |
| Progression when owner absent (Q6) | existing invite/recovery paths | Provider + recovery on star edges only | emits **successor targets**, not negotiation SDP |
| Admission handoff (Q7) | `(sessionId, peerId)` | `(sessionId, mediaEdgeId)` | unrelated to handoff latch |
| Illegal states (Q8) | IL-1..IL-8 | Same IL-1..IL-8 on every seam | N/A |
| Unified applicability (Q9) | full | full | full |
| Seam conform (Q10) | partial (see §1) | **PENDING** — must ship conform | N/A |

### Layer responsibility (frozen)

```text
ConferenceTopologyAuthority
  writes:  ActualMediaEdgeSet, meshGeneration, anchorEpoch snapshot fields
  never:   settlement, GROUP_ACCEPT, commit, meshCompleted

WebRtcAudioEngine (per MediaEdge)
  writes:  settlement truth, signalingState
  never:   roster, recovery obligation, admission phase

TalkbackCoordinator
  writes:  negotiation publish timing, commit invocation, handoff send
  never:   ActualMediaEdgeSet, AdmittedRecoveryTarget inference from roster

ConferenceEdgeRecoveryController
  writes:  recovery episode / attempt lifecycle
  never:   topology snapshot, negotiation settlement

ConferenceHealth / Presence projectors
  write:   UI-facing aggregates
  never:   negotiation truth, topology admission
```

---

## §4 — Migration seam list

Ordered seams that **MUST** be migrated when MESH → Anchor. Each seam **MUST** preserve ADR-0063 Q1–Q8 on first modification.

### Tier A — Topology truth (before audio mixer)

| ID | Seam | Current | Target | NTI note |
|----|------|---------|--------|----------|
| **M-01** | Topology publish | scattered session fields | `ConferenceTopologyAuthority.publish` | no negotiation side effects |
| **M-02** | Edge enumeration | roster → implicit all-pairs mesh | `ActualMediaEdgeSet` in snapshot | star edges only at 4+ |
| **M-03** | `meshGeneration` bump | often dormant | active on mode/anchor/admitted-set change | ICE restart ≠ bump |
| **M-04** | Recovery target derivation | controller infers from roster | `RecoveryEdgeProvider f(snapshot)` | no negotiation in provider |
| **M-05** | Edge removal | membership prune | `EDGE_REMOVED_BY_TOPOLOGY` invalidation | close obligation, not NTI |

### Tier B — Negotiation / admission (NTI-critical)

| ID | Seam | Current | Target | NTI note |
|----|------|---------|--------|----------|
| **M-06** | Answerer: `JOIN_FIRST` | Coordinator | unchanged logic; edge scoped by MediaEdge | **CONFORM** — reference |
| **M-07** | Answerer: `INVITE` | Coordinator | unchanged; polite answerer | **CONFORM** — reference |
| **M-08** | Answerer: `JOIN` | Coordinator | close Q10 gap before anchor | helper-only gate today |
| **M-09** | Answerer: `RECONNECT` / `GROUP` | Coordinator | close Q10 gap; fix pre-commit meshCompleted | **GAP** |
| **M-10** | Answerer: `ANCHOR_UPLINK` | n/a | new seam; participant → anchor | **PENDING — full Q5** |
| **M-11** | Answerer: `ANCHOR_DOWNLINK` | n/a | new seam; anchor → participant | **PENDING — full Q5** |
| **M-12** | Admission handoff key | `(sessionId, peerId)` | `(sessionId, mediaEdgeId)` | Q7 lifecycle preserved |
| **M-13** | `markMeshLinkCompleted` | Coordinator boolean set | `EstablishedMediaEdge` observation | post-commit only (Q5) |
| **M-14** | `markConferenceParticipantActive` | Coordinator | projector input after commit + topology admit | not pre-commit |

### Tier C — Readiness / health (after Tier A+B contract)

| ID | Seam | Current | Target | NTI note |
|----|------|---------|--------|----------|
| **M-15** | Peer media connected | `meshCompletedModules` | per-`MediaEdge` established fact | read-only |
| **M-16** | Room readiness | mesh peer count | `ConferenceHealth.MediaUsable` | not negotiation |
| **M-17** | Presence recovering | edge recovery facts | same, star edges only | not UI-as-truth |
| **M-18** | WiFi recovery path | JOIN + RECOVERY_REATTACH on star edge | same NTI chain, one edge | close M-08 first |

### Tier D — Explicitly deferred (not Phase 0.5)

```text
AudioMixer / PcmInjectionPort          → Phase 1a (after this mapping)
Multicast / Profile01 cutover          → ADR-0062 track
Meeting GA field                       → out of scope
MESH gap closure-only PR               → optional parallel; not blocker for M-01..M-05 design
8–9 lifecycle convergence evidence     → Phase 3 gate
```

---

## §5 — Phase authorization chain (frozen)

```text
ADR-0063                    FROZEN
0056 Phase 0.5 mapping      FROZEN (2026-08-14)
0056 Phase 0.5 NTI mapping  FROZEN (this document)
        ↓
Phase 1a-1 AudioMixer       AUTHORIZED
Phase 1a-2 PcmInjectionPort AUTHORIZED
        ↓
Phase 1b TopologyAuthority  facade implementation (existing 1b-1 scope)
        ↓
Phase 2 RecoveryEdgeProvider
        ↓
Anchor seam implementation (M-10..M-11) — must be NTI-conform at ship
```

**Rule:** Phase 1a **MUST NOT** import recovery controller or mutate Coordinator topology publish path. Mixer work proceeds in parallel with Tier A implementation planning.

---

## §6 — Review checklist (close gate)

```text
[x] MESH fact ownership table frozen
[x] Anchor fact ownership table frozen
[x] M02↔M03 non-edge explicit
[x] ADR-0063 Q1–Q10 mapped to topology layers
[x] Q10 gap policy preserved (contract ≠ full conform)
[x] Migration seam list Tier A–D
[x] Phase 1a authorization restated
[x] No production code / field / multicast / GA scope
```

---

## References

- [ADR-0063](../adr/0063-negotiation-transaction-integrity-cross-topology-contract.md) — NTI Q1–Q10
- [ADR-0056](../adr/0056-conference-topology-first.md)
- [0056-phase-05-contract-mapping.md](./0056-phase-05-contract-mapping.md)
- [0056-phase-1b-1-topology-authority-facade-scope.md](./0056-phase-1b-1-topology-authority-facade-scope.md)
- [0056-phase-1a-conference-audio-mcu-lite-spec.md](./0056-phase-1a-conference-audio-mcu-lite-spec.md)
- Incident closure: Conference Rejoin Cross-Plane Glare — CLOSED / STOP
