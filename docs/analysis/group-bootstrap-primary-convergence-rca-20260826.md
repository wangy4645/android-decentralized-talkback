# GROUP Bootstrap / Primary Convergence RCA

## Status

**RCA — Round 1 (desk/log)** · 2026-08-26

**Implementation authorization:** **NONE**

**Field authorization:** **NONE** (observation sufficient for Round 1)

**Classification:** **PRODUCT BUG**

**Track:** C — independent of Track A (Primary Native SRD) and Track B (MESH LIVE)

---

## Frozen track posture

```text
Track A — Primary Native SRD
  R3 = HOLD
  reason = GROUP readiness preflight not met
  mutex_closure = OPEN

Track B — MESH LIVE
  CLOSED

Track C — GROUP Bootstrap / Primary (this RCA)
  PRODUCT BUG
  phase = RCA Round 1 COMPLETE (desk/log)
  next = Round 2 code-path pin (still no impl)
```

**Do not touch:** R3 / PNSRD · GCT · Conference health · HangupMediaBarrier · PC reset · force-release · lease

---

## Problem statement

After coordinated app reopen (Talk page, no Meeting), GROUP does **not** converge to a unified session / primary lineage within idle observation. Devices remain split:

```text
M01  sessionTraceId=eb0a006b   sessionRole=HOST      sessionLineageId=(empty — cold path)
M02  sessionTraceId=d5b3cfd3   sessionLineageId=51f755ac   waitingForPrimary=true
M03  sessionTraceId=(empty)   sessionCreateCount=0        waitingForPrimary=true
```

Downstream symptoms (all observed, never recovered):

```text
transmitReady=false
meshIceConnectedPeers=(empty)
meshMissingIce=M02 (M01) / peerIceStates=UNKNOWN (M02/M03)
bootstrapAttemptCount climbing (M02: 391→597 in ~5 min)
primaryResolveNoMutation climbing (M01)
```

**Primary hypothesis (Round 1 — CONFIRMED at log layer):**

> GROUP bootstrap primary / session lineage does not converge across devices after reopen, so peers never join the same channel session and mesh ICE bootstrap cannot start.

This is **upstream of** “ICE did not connect.” ICE failure here is a **consequence**, not the root.

**Related but distinct:** [group-post-hangup-recovery-rca-minimal-repro-review-20260826.md](./group-post-hangup-recovery-rca-minimal-repro-review-20260826.md) — post-hangup abort path shares downstream symptoms (`transmitReady=false`, `bootstrapAttemptCount` stall) but this observation is **clean-start idle**, not post-Conference.

---

## Evidence bundle

| Artifact | Role |
|----------|------|
| `talkback/logs/group-readiness-observation-20260826-175700/` | Field observation |
| `.../ADJUDICATION.txt` | Case A verdict: SELF_RECOVER=NO |
| M01-talkback.log | Clean boot · HOST · eb0a006b · ~43s |
| M03-talkback.log | Clean boot · no session · ~37s |
| M02-talkback.log | **~5 min authoritative window** · stale lineage |

### Procedural caveat (M02 only — does not invalidate conclusion)

M01/M03 logs show cold boot (`Coordinator started`, new pids). M02 log **starts mid-process** (no boot banner); first snapshot already has `bootstrapAttemptCount=391`, `waitingForPrimaryDurationMs≈511s`, `sessionLineageId=51f755ac`. M02 was **not force-stopped** with M01/M03 in this run.

This **amplifies** the split-lineage bug (stale in-memory session + metrics survive reopen on other devices). It does **not** weaken M02’s ~5 min “retry without progress” evidence. **No supplemental M01/M03 5 min capture authorized.**

---

## Causal chain (field)

```text
clean reopen (M01/M03 cold; M02 stale)
    ↓
M01 elects self primary → GROUP_SESSION_CREATE trace=eb0a006b roster=M01,M02
    ↓
GROUP_INVITE → M02 BLOCKED (LOCAL_NOT_BIDIRECTIONAL)
    ↓
M01 primary_resolve loop (no mutation) · ICE candidates sent · no peer join
    ↓
M02/M03: waitingForPrimary=true on stale / empty session
    ↓
HELLO seen but GROUP_LATE_PEER_ADMISSION_SKIPPED (HELLO_CHANNEL_ABSENT)
    ↓
meshMissingIce · transmitReady=false (persistent)
```

---

## C-T1 — Primary authority: who, and why split?

### Authority rule (code)

Bootstrap primary = `ChannelMeshHostElection.electHost(local, remotes)` — **not** anchor health ranking.

```15942:15948:talkback/android-board-talkback/src/main/java/com/talkback/app/TalkbackCoordinator.kt
    private fun resolveBootstrapPrimary(members: Collection<ModuleId>): ModuleId? {
        if (members.isEmpty()) return null
        return ChannelMeshHostElection.electHost(
            localModuleId,
            members.filter { it != localModuleId }.map { it.value },
            emptyMap()
        )
    }
```

Non-primary peers **do not bootstrap**; they log and return:

```15565:15574:talkback/android-board-talkback/src/main/java/com/talkback/app/TalkbackCoordinator.kt
        val primary = resolveBootstrapPrimary(allModules)
        if (primary != null && localModuleId != primary) {
            log("Waiting for primary ${primary.value} to bootstrap GROUP on $channelId")
            observeGroupTransitionBootstrapAttempt(
                channelId = channelId,
                resolvedPrimary = primary.value,
                waitingForPrimary = true,
                meshRecoveryState = "waiting_primary"
            )
            return
        }
```

### Field answer

| Device | Primary belief | Session authority | Role |
|--------|----------------|-------------------|------|
| M01 | self (M01) | **Active** `grp:CH-01` trace **eb0a006b** | HOST · creates mesh |
| M02 | M01 (correct) | **Stale** `grp:CH-01` trace **d5b3cfd3** lineage **51f755ac** | NONE · waits forever |
| M03 | M01 (correct) | **No session** (`sessionCreateCount=0`) | NONE · waits forever |

**Why M02 still waits on 51f755ac while M01 runs eb0a006b:**

1. M02 retained pre-reopen in-memory GROUP session + `GroupTransitionReadinessLog` metrics (not force-stopped).
2. M01 cold-started a **new** trace (`GROUP_SESSION_CREATE` at 17:57:35.853) with **empty** `sessionLineageId` (cold path — lineage only assigned on `MEETING_END_BEGIN`, not APP_START).
3. Non-primary path has **no stale-session invalidation** tied to peer’s new trace — M02 keeps waiting for primary bootstrap **on its local stale session**, not reconciling to M01’s eb0a006b.
4. M01’s invite to M02 never delivered: `PEER_EDGE_CONTROL_BLOCKED type=GROUP_INVITE peer=M02 reason=LOCAL_NOT_BIDIRECTIONAL` → `GROUP_BOOTSTRAP_INTENT_WAITING`.

**Round 1 pin:** Primary **election** is consistent (all agree M01). **Session authority convergence** is broken — three different session identities.

---

## C-T2 — Why lineage does not converge?

### Identity model (code)

| Field | Meaning | Cold APP_START | Post-MEETING_END |
|-------|---------|----------------|------------------|
| `sessionTraceId` | Per GROUP session instance | New on `GROUP_SESSION_CREATE` | New on recreate |
| `sessionLineageId` | Convergence-window lineage | **Empty** until `onMeetingEndBegin` | Set via `newLineageId()` |
| `parentTraceId` | Recreate chain | Empty on first create | Prior trace |

```1081:1109:talkback/android-board-talkback/src/main/java/com/talkback/core/util/GroupTransitionReadinessLog.kt
        if (previousTraceId == null) {
            metrics.parentTraceIdForCurrent = null
            metrics.sessionCreateCount.incrementAndGet()
            TalkbackLog.i(
                buildString {
                    append("GROUP_SESSION_CREATE ")
                    appendKv("sessionLineageId", metrics.sessionLineageId)
                    appendKv("sessionTraceId", newTraceId)
                    ...
                }
            )
```

`GroupChannelAuthority` (ADR-0053) can detect stale authority (E1/E2/E3) and invalidate — but **non-primary waiter path never reaches bootstrap emission** to trigger realignment:

```119:123:talkback/android-board-talkback/src/main/java/com/talkback/core/session/GroupChannelAuthority.kt
    fun mayEmitBootstrap(snapshot: GroupAuthoritySnapshot, localModuleId: ModuleId): Boolean {
        if (snapshot.state != GroupAuthorityState.BOOTSTRAP_REQUIRED) return false
        if (snapshot.candidate != localModuleId) return false
        return !validAuthorityHeldAnywhere(snapshot, localModuleId)
    }
```

### Field timeline

```text
17:57:35  M01  GROUP_SESSION_CREATE  trace=eb0a006b  members=M01,M02  lineage=(empty)
17:57:36  M01  GROUP_INVITE M02 BLOCKED  LOCAL_NOT_BIDIRECTIONAL
17:57:41  M03  boot  HELLO_CHANNEL_ABSENT for M02  waitingForPrimary
17:58:44  M02  (already running)  HELLO from M03  GROUP_LATE_PEER_ADMISSION_SKIPPED HELLO_CHANNEL_ABSENT
17:58:47  M02  waitingForPrimary  lineage=51f755ac  trace=d5b3cfd3  bootstrapAttemptCount=391
```

**Convergence failures (ordered):**

1. **Invite gate:** M01 cannot signal M02 → no shared session fact on M02.
2. **Late admission gate:** M03 HELLO lacks `channelId` → `HELLO_CHANNEL_ABSENT` → cannot join M01 session.
3. **Stale retention:** M02 old trace not invalidated when M01 creates new trace.
4. **Roster skew:** M01 session roster M01+M02 only at create; M03 never admitted (M03 booted after M01 create; late path blocked).

**Round 1 pin:** Lineage split is a **session handoff / invalidation / admission** defect, not ICE.

---

## C-T3 — Why bootstrapAttemptCount rises without mutation?

### Two retry loops

**M01 (primary, HOST):** `reconcileGroupMeshMaintenance` → `observeGroupTransitionPrimaryResolve` every tick.

Mutation counted only when `traceChanged || membersChanged`:

```457:469:talkback/android-board-talkback/src/main/java/com/talkback/core/util/GroupTransitionReadinessLog.kt
        val traceChanged = metrics.lastObservedTraceId != snapshot.session.sessionTraceId
        val membersChanged = metrics.lastObservedJoinedMembers != snapshot.readiness.joinedMembers.toSet()
        val sessionMutated = traceChanged || membersChanged
        when {
            primaryChanged -> metrics.primaryChangeCount.incrementAndGet()
            !sessionMutated -> metrics.primaryResolveNoMutationCount.incrementAndGet()
            else -> metrics.primaryResolveMutationCount.incrementAndGet()
        }
```

Field: M01 `primaryResolveNoMutationCount` climbs (1→6+ in seconds) while `joinedMembers=M01,M02` frozen, `meshMissingIce=M02`, invite blocked.

**Each retry waits for:** M02 to accept GROUP_INVITE / become bidirectional on control plane — **fact never arrives**.

**M02/M03 (non-primary):** `reconcileGroupBootstrap` → `waitingForPrimary` → `onBootstrapAttempt` increments counter, **returns immediately**.

Field: M02 `bootstrapAttemptCount` 391→597 (~206 attempts / ~5 min ≈ periodic reconcile cadence). `primaryResolveCount=0` — non-primary never enters primary-resolve path.

**Each retry waits for:** primary (M01) to bootstrap GROUP **that M02 can join** — M01 did bootstrap eb0a006b but M02’s stale session + blocked invite means **no join ingress on M02’s session id**.

```text
retry != progress
```

**Round 1 pin:** Both loops are **observationally honest** — counters reflect scheduler ticks, not progress. Missing fact is **cross-device session join**, not primary election.

---

## C-T4 — GROUP READY terminal predicate

### Authoritative chain (code)

`GroupRuntimeHealthProjector` — transmit policy is sole readiness authority (ADR-0008):

```66:70:talkback/android-board-talkback/src/main/java/com/talkback/core/grouphealth/GroupRuntimeHealthProjector.kt
        val readiness = when {
            !membershipReconciled -> GroupTopologyReadiness.MEMBERSHIP_PENDING
            transmitMissingPeers.isNotEmpty() -> GroupTopologyReadiness.BUILDING
            else -> GroupTopologyReadiness.OPERATIONAL
        }
```

Snapshot `transmitReady` mirrors this:

```17865:17866:talkback/android-board-talkback/src/main/java/com/talkback/app/TalkbackCoordinator.kt
                membershipReady = health?.membershipReconciled == true,
                transmitReady = health?.transmitMissingPeers?.isEmpty() == true,
```

### Terminal predicate (frozen for Track C)

```text
GROUP READY (OPERATIONAL)
  IFF membershipReconciled
   AND transmitMissingPeers.isEmpty()
  IFF all transmitRequiredPeers have peerMediaConnected (ICE connected)

transmitReady=true  ⟺  transmitMissingPeers.isEmpty()
groupTopologyReadiness=OPERATIONAL  ⟺  same condition (when membership reconciled)
```

**Prerequisite chain (must all succeed before OPERATIONAL):**

```text
(1) Unified session trace across participants
      ↓ GROUP_INVITE / join ingress accepted on same sessionId + trace
(2) membershipReconciled (roster digest aligned, no suspect peers)
      ↓
(3) mesh ICE bootstrap (meshMissingIce → empty)
      ↓ peerMediaConnected for transmitRequiredPeers
(4) transmitReady=true / OPERATIONAL
```

**What does NOT terminate waiting:**

- `waitingForPrimary=true` alone (non-primary spin)
- `primary_resolve` with `sessionMutated=false`
- `terminalReady=true` on transition FSM (IDLE) — observed on all devices while mesh broken
- UI `channelReadiness=CONNECTING` / `DIRECTORY_SYNC`

Field: **never reached step (1)** in this observation → steps (2–4) unreachable.

---

## Root cause statement (Round 1)

**PRODUCT BUG — GROUP bootstrap session convergence failure**

Multiple contributing mechanisms, single user-visible outcome:

1. **Session handoff gap:** Primary cold-create (eb0a006b) does not invalidate / realign peer stale sessions (d5b3cfd3 / empty).
2. **Control-plane gate:** `LOCAL_NOT_BIDIRECTIONAL` blocks initial GROUP_INVITE — bootstrap intent waits (`GROUP_BOOTSTRAP_INTENT_WAITING`) with no timeout escalation to session realignment.
3. **Late admission gap:** HELLO without channel → `HELLO_CHANNEL_ABSENT` — M03 cannot join after M01 session create.
4. **Non-primary spin:** `waitingForPrimary` retry loop has no terminal failure / realignment trigger when primary has already created a incompatible session.

**Not root cause:** Mesh ICE algorithm, Conference hangup, PNSRD, UI CONNECTING display.

---

## Round 2 scope (desk/code — NOT authorized to implement)

Pin exact code owners for convergence break (expected files):

| Gap | Likely seam |
|-----|-------------|
| Stale session on peer reopen | `GroupChannelAuthority` E1/E2/E3 + invalidation commit path |
| Invite blocked at bootstrap | `PeerControlSignalingAdmission` / `LOCAL_NOT_BIDIRECTIONAL` |
| Primary create vs peer wait | `reconcileGroupBootstrap` vs `reconcileGroupMeshMaintenance` |
| Late peer HELLO | `GroupLatePeerAdmissionSupport` / `HELLO_CHANNEL_ABSENT` |
| Cross-device trace discovery | GROUP signaling / heartbeat session identity propagation |

**Round 2 deliverable:** code-path pin + minimal fix **design sketch** (ADR if predicate change). Still **no field, no code** until explicit authorization.

---

## Impact on other tracks

```text
Track A R3 preflight gate:
  transmitReady=true OR meshIceConnectedPeers includes peer
  + M02 receives M01 GROUP_INVITE
  → NOT MET · R3 remains HOLD

Track B: unchanged CLOSED

Track C post-hangup Case B: deferred until bootstrap convergence RCA Round 2
  (do not run Meeting/hangup repro on broken GROUP baseline)
```

---

## References

- Observation: `talkback/logs/group-readiness-observation-20260826-175700/`
- Post-hangup review: [group-post-hangup-recovery-rca-minimal-repro-review-20260826.md](./group-post-hangup-recovery-rca-minimal-repro-review-20260826.md)
- Authority: ADR-0053 (`GroupChannelAuthority`)
- Readiness projection: ADR-0008 (`GroupRuntimeHealthProjector`)
- Telemetry: `GroupTransitionReadinessLog` (P2-0 / ADR-0027 observation)
