# ADR-0056: Conference Participant Presence Projection (CPP)

## Status

**CPP Contract Review v0.1** (2026-08-17) · **CPP overall CLOSED** (2026-08-18) · **Phase 1b contract review NEXT** · **Phase 1b impl NOT AUTHORIZED**

**Parent:** [ADR-0056](../adr/0056-conference-topology-first.md) · [Phase 0.5 mapping](./0056-phase-05-contract-mapping.md)

**Field:**
- CPP-1 / 2a: `talkback/logs/ui-anchor-0056-198-20260817-165225/ADJUDICATION.txt`
- CPP-2b locate: `talkback/logs/cpp2b-truth-table-20260817-172609/ADJUDICATION.txt`
- 4p Truth Gate Run A: `talkback/logs/cpp-truth-gate-4p-20260818-102714/`
- 4p Truth Gate Run B + merge: `talkback/logs/cpp-truth-gate-4p-runB-20260818-103818/` · `MERGED_ADJUDICATION.txt`

**Purpose:** One immutable `ParticipantPresenceProjection`. List and aggregates are functions of that vector. Presence is a topology-lineage **projection fact**, not a third authority.

**Milestone (2026-08-18):** ADR-0056 Phase 1a + CPP **CLOSED**. Conference Audio Anchor path validated at 4p. Participant Presence projection unified and field-validated. Phase 1b Anchor admission contract is the next workstream; implementation remains unauthorized.

**Next authorized action:** Phase 1b contract review (not implementation).

---

## Gate posture (frozen 2026-08-18)

```text
Phase 1a                  CLOSED
IG-1                      PASS
IG-2                      PASS (CONDITIONAL / OBS path)

CPP-1                    CONFIRMED
CPP-2a                   CONFIRMED
CPP-2b                   CONFIRMED
CPP-3 HOST≠ANCHOR        CONTRACT FROZEN

Cases 1–6                PASS
Consumer Gate            PASS
Runtime projector migration  PASS
4p Presence Truth Gate   COMPLETE (split-capture) / FIELD VALIDATED

Avatar migration         UNIT PASS (22/22)

CPP overall              CLOSED

Phase 1b                   COMPLETE / PASS (unit) — [closeout](./0056-phase-1b-complete.md)
Phase 1b Field             NOT AUTHORIZED
Phase 2 Recovery           NOT STARTED
Recovery / Bus / Health    FROZEN
```

**CPP CLOSED means:** Presence contract + projection implementation complete and passed current evidence gates. **Not** 8–10 network convergence certification.

**4p Truth Gate evidence strength (retain):**

```text
COMPLETE (split-capture)  ✅  four local vectors satisfy canonical CPP contract
same-session same-window  ❌  NOT proven
```

---

## Normative chain (frozen intent)

```text
MembershipAuthority
        ↓
ConferenceTopologyAuthority
        ↓ atomic publish
ConferenceTopologySnapshot
        ↓
ConferencePresenceSnapshot     ← projection fact (NOT an authority)
        ↓
ParticipantPresenceProjection  ← one derivation
        ↓
UI / ConferenceHealth / connectedCount / recoveringPeers
```

```text
Only facts flow downward.
Presence MUST NOT write roster, topology, Bus, ICE, or recovery.
Anchor MAY produce/aggregate the snapshot. Anchor is NOT a third truth domain.
```

**Forbidden name:** `PresenceAuthority`.

---

## Sub-problems

| ID | Claim | Status | Evidence |
|----|-------|--------|----------|
| CPP-1 | ANCHOR must not treat missing direct peer PC as JOINING/DISCONNECTED | CONFIRMED | connecting/joining while MCU-lite healthy |
| CPP-2a | aggregates and per-participant hint must share one derivation | CONFIRMED | M03 `joined=4 connected=3 hint=3 joining` (4−3=1) |
| CPP-2b | `rosterContains` → `joinedCount` uses a different semantic | CONFIRMED | M02 17:30:28: `M04 rosterContains=true` and `joinedCount=3` |
| CPP-3 | HOST ≠ ANCHOR: membership vs media composed once in projection | CONTRACT FROZEN | Cases 1–6 below; no more field for this freeze |

Repair target: **one `ParticipantPresenceProjection`**. Not “make existing UI topology-aware”.

CPP-2b break (not UI-only):

```text
canonical membership / probe
        ↓   ← HERE
joinedCount
        ↓
UI
```

---

## v0.1 freeze (four invariants)

```text
1. membership plane
   joinedCount = count(canonicalRoster members)
   joinedCount MUST NOT equal count(media=CONNECTED)

2. media plane
   connectedCount = count(participants with mediaRelation in {VIA_ANCHOR, DIRECT}
                          AND evidence=FRESH)

3. single projection
   participants[] is the only source for list and aggregates

4. aggregate invariant
   joinedCount / connectedCount / joiningCount / recoveringPeers
       == functions(participants[])
```

```text
joined=4 connected=3
```

is **legal** when one member is JOINED with media NONE/UNKNOWN/DEGRADED. It is not a defect.

Forbidden (current bug pattern):

```text
roster        → joinedCount
receivePath   → connectedCount
pendingPeers  → joiningHint
```

---

## CPP-3 — HOST ≠ ANCHOR composition (CONTRACT FROZEN)

```text
Membership plane
    ↓
TopologySnapshot.members
    ↓
ParticipantPresenceProjection.membership

Anchor Presence producer
    ↓
ConferencePresenceSnapshot
    ↓
ParticipantPresenceProjection.media

             ↓
      single canonical participants[]
             ↓
   joinedCount / connectedCount / hints
             ↓
              UI
```

```text
joinedCount            = canonical membership projection
mediaRelation          = anchor presence projection
connectedCount         = f(participants[])
joiningHint            = f(participants[])
UI participant count   = f(participants[])
```

**Forbidden:**

```text
HOST   → joinedCount
ANCHOR → connectedCount
UI     → merge a third time
```

HOST admission is not an aggregate owner. Anchor is fact producer for media only.

### Four frozen details

**1. Membership vs presence set mismatch — membership wins the list**

```text
Roster:    M01 M02 M03 M04
Presence:  M01 M02 M03
→ participants = 4
  M04 membership=JOINED  mediaRelation=NONE  evidence=UNKNOWN
```

MUST NOT drop M04 from the list because the snapshot omitted it.

**2. Presence snapshot missing**

```text
presence missing → membership=JOINED, evidence=UNKNOWN
```

MUST NOT: `presence missing → participant absent`.

**3. Dual producers, one composer**

HOST may have roster facts; ANCHOR may have media facts. Only `ParticipantPresenceProjection` owns aggregates. No local third count.

**4. Snapshot lineage** (same as § Snapshot identity)

```text
conferenceId    hard equality
anchorEpoch     snapshot < current → reject (stale producer)
meshGeneration  ordering within same anchorEpoch; NOT equality hard-gate
                same anchorEpoch + higher meshGeneration → newer wins
rosterEpoch     membership validity of records
snapshotAge     freshness → STALE / UNKNOWN, never OFFLINE inference
```

### Contract fixtures (implementation must pass; no field run)

```text
Case 1  roster=4, presence=4
        → participants=4

Case 2  roster=4, presence=3
        → participants=4
        → missing member media=NONE/UNKNOWN

Case 3  roster=3, presence=4
        → extra presence id is NOT_JOINED / ignored (non-member)

Case 4  HOST != ANCHOR
        → one canonical projection
        → no third count

Case 5  presence stale
        → membership retained
        → evidence=STALE/UNKNOWN
        → no OFFLINE inference

Case 6  same anchorEpoch, meshGeneration advances
        → newer snapshot wins
        → old snapshot does not overwrite
```

---

## Axes (not a flat enum)

ADR-0010: membership and media projection stay separate.

```text
membership:    JOINED | NOT_JOINED | PENDING
mediaRelation: VIA_ANCHOR | DIRECT | NONE | DEGRADED
evidence:      FRESH | STALE | UNKNOWN
```

UI maps the triple to a display state. Do not collapse into a single boolean `connected`.

`DEGRADED` is allowed **only** when the producer has an explicit media-health observation on the **admitted** edge it can see:

```text
presence.mediaRelation(X)
    = roster membership
    + producer observation of admitted edge(s) to X
    ≠  "X can hear"
```

Anchor observes `anchor↔X` at the anchor. That is not receive-side proof on X.

---

## Snapshot identity and validity

`ConferencePresenceSnapshot` binds topology lineage. It is not a fifth epoch axis.

Required fields:

```text
conferenceId
producerModuleId          // current anchor (fact producer)
rosterEpoch
anchorEpoch
meshGeneration
presenceSnapshotGeneration
producedAtMs
participants[]            // one record per canonical roster member
```

**Accept / reject (monotonic lineage — not equality on meshGeneration):**

```text
HARD reject if conferenceId != current
HARD reject if snapshot.anchorEpoch < current.anchorEpoch   // old producer after failover
ACCEPT     if snapshot.anchorEpoch == current.anchorEpoch
meshGeneration is ordering within the same anchorEpoch — NOT an equality gate
staleness  is snapshotAge / producedAtMs — NOT generation equality
```

Reason: `meshGeneration` bumps when `ActualMediaEdgeSet` changes. Equality-gating it would flash UNKNOWN on every admitted-edge change (worse at 8–10).

`rosterEpoch` participates in **membership validity** of each participant record, not as a third equality gate on the whole snapshot unless the snapshot predates the current roster epoch for that member.

Invalid / stale snapshot → all `evidence=UNKNOWN`. MUST NOT map UNKNOWN to OFFLINE.

---

---

## Open contract decision (must be explicit before impl)

Participant receive-side liveness (`ReceivePathLivenessObserver` / `receiveSampled`) as **uplink into** the snapshot:

- **In:** presence can report “X hears via anchor” (true receive fact)
- **Out:** presence remains producer-edge observation only

Default until decided: **Out**. Do not silently mix receive facts into `DEGRADED`.

---

## Non-goals / forbidden workarounds

```text
timeout → force connected
receivePath NONE → wait longer
ConferenceAudioBus change to satisfy UI
require participant↔participant PC under ANCHOR
weaken roster convergence to display 4
Presence writes topology or membership
Phase 1b implementation from this draft
anchor failover / WiFi / 8–10 mixed into CPP-2b field run
```

---

## 4p Presence Truth Gate — COMPLETE (split-capture, 2026-08-18)

Grep: `CONFERENCE_PRESENCE_PROJECTION`

**SoT:** that line's `participants=` vector.

**Runs:** Run A USB M01+M03+M04(local) · Run B USB M01+M03+M02(local) · M02/M04 share one cable.

**Merged steady snapshots (each node self-consistent):**

```text
M01  4/4/0   Run B  producer=M01  HOST=ANCHOR=M01
M02  4/3/1   Run B  M04 NONE:UNKNOWN
M03  4/3/1   Run B  M04 NONE:UNKNOWN
M04  4/3/1   Run A  M03 NONE:UNKNOWN
```

All lines: `aggregate == f(participants[])`. FAIL classes: none.

**Evidence strength (must not over-read):**

```text
COMPLETE (split-capture)     ✅  four local vectors satisfy canonical CPP contract
same-session same-window     ❌  NOT proven — different sessions / different instants
```

Do **not** upgrade this to "end-to-end network convergence proof". Sufficient for **CPP factual model FIELD VALIDATED**. Stronger convergence certification = future single-window 4-USB capture (optional; not required before avatar migration or Phase 1b contract review).

**Legal asymmetry:** `joined=4 connected=3` with `JOINED + NONE + UNKNOWN` is projection fact, not FAIL.

Out of scope: failover, leave/rejoin, WiFi, recovery, 8/10, topology transition.

---

## Avatar consumer migration — CLOSED (unit, 2026-08-18)

```text
ConferencePresenceProjection
        ↓
ConferencePresenceUiBind.avatarRows / joiningHint
        ↓
MeetingPresenceDisplay.renderFromProjection
        ↓
TalkViewModel avatar row + network banner
```

**Frozen product semantics:**

```text
JOINED + NONE + UNKNOWN  →  CONNECTING / JOINING  (≠ OFFLINE, ≠ LEFT)
JOINED + VIA_ANCHOR/DIRECT + FRESH  →  in-meeting / normal
```

Conference avatar **decision source:** projection only.

**Retained non-authoritative (no further cleanup required this track):**

```text
receivePathLivenessProvider  → legacy / non-Conference support
DEBUG-rprobe                 → diagnostic only (may read visibleParticipants for contrast)
```

**Unit gate:** Cases 1–6 + `ConferencePresenceAvatarMigrationTest` + `MeetingPresenceDisplayCppTest` — **22/22 PASS**.

---

## 4p Presence Truth Gate (CPP-2b locate) — EXECUTED (2026-08-17)

**Result:** CPP-2b **CONFIRMED** (2026-08-17). USB M01/M02/M03; M04 no cable. HOST=ANCHOR=M01.

Same conference, ≥90s steady. No failover / WiFi flap / leave-rejoin / 8–10.

`ConferencePresenceSnapshot` did **not** exist. Proxies only. No APK fields added.

| Contract field | This-run proxy |
|----------------|----------------|
| canonicalRoster | `TOPOLOGY_SNAPSHOT members=` (conference session if present; else note GROUP vs CONFERENCE) |
| rosterEpoch / anchorEpoch / meshGeneration | `GROUP_DIGEST` on CONFERENCE session |
| anchorId | `anchorModuleId=` / topology ANCHOR |
| presenceSnapshotGeneration / snapshotAge | **ABSENT** — record as N/A |
| participants[] | `REACHABILITY_PROBE` per module (`rosterContains`, `media=`) |
| joinedCount / connectedCount / recoveringPeers | `CONFERENCE_RUNTIME_PROJECTION` + `Meeting pill` |
| UI count / states | `Meeting pill` display / connectingHint |

Adjudication (this run):

```text
Case A  CONFIRMED  M02: probe rosterContains(M04)=true, joinedCount=3, UI=3
Case B  REJECTED   UI count == joinedCount (not a render-only drop)
Case C  CONFIRMED  M03: joined=4 connected=3 hint=3 joining  (CPP-2a)
```

Pass iff:

```text
all nodes: canonicalRoster size equal and same set
all nodes: joinedCount == canonicalRoster size (after admit)
all nodes: connectedCount == count(mediaRelation in {VIA_ANCHOR, DIRECT} AND evidence=FRESH)
all nodes: connectingHint count == joinedCount - that connectedCount
no node: hint count contradicts the per-participant vector
```

Leave/rejoin is a **second phase**, not mixed into the 90s steady sample.

Mute/unmute: presence MUST NOT rebuild topology; `mediaRelation` stays; local mic fact is orthogonal (`CONFERENCE_AUDIO_PATH.localMicActive`).

---

## Relation to 8–10

```text
4p Anchor Audio                 PASS
CPP overall                     CLOSED
4p same-window convergence      NOT claimed (split-capture)
6p / 8p / 10p Gates             after Phase 1b contract + impl gates
```

```text
CPP CLOSED ≠ Capacity PASS
split-capture COMPLETE ≠ same-session convergence proof
```

---

## Phase 1b — Anchor admission (contract review NEXT)

**Authorized:** contract review only. **Not authorized:** implementation.

Review chain (not AudioBus-first):

```text
ConferenceTopologyAuthority
        ↓
ConferenceTopologySnapshot
        ↓
ActualMediaEdgeSet
        ↓
Anchor admission
        ↓
ConferencePresenceSnapshot / CPP
```

**Required contract case (from 4p field):** `HOST ≠ ANCHOR` — formal admission/composition case before impl, not a post-hoc discovery.

CPP is an **input** to Phase 1b review:

```text
TopologyAuthority → TopologySnapshot → Media topology → PresenceSnapshot → ParticipantPresenceProjection → UI
UI ≠ topology authority
UI ≠ direct media edge detector
```

Do not implement Phase 1b until contract review closes. Do not reopen CPP for Phase 1b media-edge work.
