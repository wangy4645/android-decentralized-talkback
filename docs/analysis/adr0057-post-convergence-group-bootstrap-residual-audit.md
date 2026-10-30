# ADR-0057 Track C — Post-Convergence Group Bootstrap Residual Audit

## Status

**READ-ONLY AUDIT** — no implementation authorized (2026-08-31)

```text
Prerequisite (frozen):
  Generation convergence FIELD PASS
  ACQ / CSO CLOSED FOR THIS DEFECT
  GBC Core / trust / VB / F1 FROZEN

Audit scope:
  GROUP membership admission
  GROUP invite eligibility / execution
  waitingForPrimary (non-generation)

Out of scope (this audit):
  CSO, ACQ, Generation Fact, FactStore, F1, VB, PV, PTI/PAP/OE/PW
  ICE retry hacks, transmitReady forcing, successor generation for M03 join
```

**Evidence:** `talkback/logs/adr0057-track-c-case-a-20260831-160349/`  
**Parent:** [Case A field adjudication](./adr0057-track-c-case-a-field-adjudication.md)

---

## Audit chain (fixed order)

```text
HELLO / discovery visibility
        ↓
membership candidate
        ↓
membership admission
        ↓
currentMembers
        ↓
GROUP_INVITE eligibility
        ↓
LOCAL_NOT_BIDIRECTIONAL
        ↓
invite send / accept
        ↓
media edge creation
        ↓
transmitReady
```

Do **not** reason backward from `transmitReady=false`.

---

## Q1 — Why is M03 HELLO-visible but not in M01 `currentMembers`?

### Field timeline (M01)

| Time | Event |
|------|-------|
| 16:04:34 | `mesh_create` with `dialable = {M02}` only (M03 app not yet open) |
| 16:04:34 | `GROUP_SESSION_CREATE currentMembers=M01,M02` |
| 16:04:49 | `Discovery peer added: M03` |
| 16:04:52 | First `HELLO from M03` |
| 16:04:52+ | Repeated `GROUP_LATE_PEER_DISCOVERED peer=M03` |
| run end | `currentMembers` never includes M03; M02/M03 received **zero** `GROUP_INVITE` |

### Code path — initial roster snapshot

`reconcileGroupBootstrap` builds invite set from `dialableRemoteModuleIds()` at reconcile time:

```16068:16087:talkback/android-board-talkback/src/main/java/com/talkback/app/TalkbackCoordinator.kt
        val inviteModuleIds = GroupMeshPlanner.inviteTargets(localModuleId, allModules)
        ...
        meshCallInternal(
            local,
            inviteEndpoints,
            channelId,
            SessionType.GROUP,
            ...
        )
```

`allModules = dialable + localModuleId`. M03 was **not dialable** when M01 first became primary and emitted `mesh_create`. Roster at session create is therefore **M01,M02 by construction**, not a post-hoc freeze bug.

### Code path — late M03 admission (exists)

After session exists, `maybeAdmitLatePeerFromHello` → `GroupLatePeerAdmissionSupport.evaluate` → on `Admit`, `tryReinviteGroupPeerPairwise(M03)`:

```15584:15596:talkback/android-board-talkback/src/main/java/com/talkback/app/TalkbackCoordinator.kt
                    is GroupLatePeerAdmissionSupport.Decision.Admit -> {
                        log(
                            GroupLatePeerAdmissionLog.discoveredMessage(
                                peerModuleId,
                                localModuleId.value,
                                channelId,
                                session.id,
                                resolution.channelSource.name
                            )
                        )
                        testLatePeerAdmitInvokeCount++
                        tryReinviteGroupPeerPairwise(peerModuleId)
                    }
```

**Field:** `GROUP_LATE_PEER_DISCOVERED` fired hundreds of times for M03 — admission **predicate PASS**, owner gate PASS (M01 `<` M03 in MESH topology).

### Drop point

Immediately after each `GROUP_LATE_PEER_DISCOVERED`, M01 logs:

```text
Coordinator error: Synchronous mesh engine create pending async release for M03
```

`tryReinviteGroupPeerPairwise` → `sendGroupMeshInvitesInternal` → `acquireMeshEngine` → `MediaSessionManager.create()` throws when async engine release is pending (`MediaSessionManager.kt:87`).

**Conclusion Q1:** M03 is not in `currentMembers` because (a) staggered startup fixed initial roster at M01,M02, and (b) the **incremental late-peer invite path fires but fails before wire `GROUP_INVITE`**, at media engine acquisition — not at HELLO visibility or late-peer admission classification.

---

## Q2 — What does `LOCAL_NOT_BIDIRECTIONAL` depend on?

### Semantics (not per-peer membership)

`PeerEdgeSignalingReadiness.snapshot` returns `LOCAL_NOT_BIDIRECTIONAL` when **local** link qualification ≠ `BIDIRECTIONAL_READY`:

```107:114:talkback/android-board-talkback/src/main/java/com/talkback/core/signaling/peer/PeerEdgeSignalingReadiness.kt
        if (local.linkQualification != LinkQualificationState.BIDIRECTIONAL_READY) {
            return PeerEdgeSignalingSnapshot(
                ...
                reason = PeerEdgeSignalingNotReadyReason.LOCAL_NOT_BIDIRECTIONAL
            )
        }
```

It does **not** read: membership roster, generation Current, sessionId, or ICE connected state.

Per-peer readiness additionally requires inbound observed on current `rebindGeneration` (ADR-0022 Q5).

### Field sequence (M01 → M02)

```text
16:04:34.836  PEER_EDGE_CONTROL_BLOCKED GROUP_INVITE peer=M02 reason=LOCAL_NOT_BIDIRECTIONAL
16:04:34.921  LINK_QUALIFICATION newState=BIDIRECTIONAL_READY reason=FIRST_INBOUND
16:04:34.946  PEER_EDGE_READY remote=M02
16:04:35.027  GROUP_BOOTSTRAP_EDGE_READY_EVALUATED peer=M02 decision=ISSUE_INVITE
```

~85 ms window: first `GROUP_INVITE` attempt preceded first inbound (M02 `FACT_REQUEST`), which promoted local link to `BIDIRECTIONAL_READY`.

### Circular dependency assessment

```text
invite needs peerEdgeReady
peerEdgeReady needs local BIDIRECTIONAL_READY + inbound on epoch
first inbound can be FACT_REQUEST (not GROUP_INVITE)
```

**Not a sustained bootstrap circular dependency.** Transient cold-start race; retried via `evaluateBootstrapAdmissionOnEdgeReady` after `PEER_EDGE_READY`.

Sustained invite failure on this run is **downstream** (mesh engine sync create / async release), not `LOCAL_NOT_BIDIRECTIONAL` stuck high.

---

## Q3 — Why does `waitingForPrimary` persist after same Current?

### Code

Non-primary nodes exit bootstrap emission early:

```16054:16063:talkback/android-board-talkback/src/main/java/com/talkback/app/TalkbackCoordinator.kt
        val primary = resolveBootstrapPrimary(allModules)
        if (primary != null && localModuleId != primary) {
            log("Waiting for primary ${primary.value} to bootstrap GROUP on $channelId")
            observeGroupTransitionBootstrapAttempt(
                ...
                waitingForPrimary = true,
                meshRecoveryState = "waiting_primary"
            )
            return
        }
```

`waitingForPrimary` is **observability / scheduler** (C-R2); explicitly **not** generation authority (`GbcLegacyAuthorityProxies`).

### Independent inputs

| Input | M02/M03 this run |
|-------|------------------|
| Generation Current Gx | PASS (aligned) |
| Bootstrap primary | M01 (correct) |
| Accepted GROUP session locally | **FAIL** (no inbound invite) |
| `waitingForPrimary` | **true** (expected until primary completes group bootstrap) |

**Conclusion Q3:** `waitingForPrimary=true` after Current alignment is **legitimate**. Followers correctly wait for primary M01 to complete GROUP bootstrap. Persistence is because **group session was never established on M02/M03** (zero `GROUP_INVITE` received), not because generation/sessionId proxy is wrong.

---

## Q4 — Is roster frozen after `GROUP_SESSION_CREATE`?

### Initial create

Yes — snapshot reflects `dialable` at `mesh_create` time (M01,M02 only when M03 offline).

### Incremental paths (exist in code)

1. **Late peer HELLO** — `maybeAdmitLatePeerFromHello` (see Q1)
2. **Maintenance reconcile** — `reconcileGroupMeshMaintenance` adds `missingPeers` from current `dialable` not in `session.memberModules`:

```16107:16125:talkback/android-board-talkback/src/main/java/com/talkback/app/TalkbackCoordinator.kt
        val missingPeers = dialable
            .filter { it != localModuleId && it !in session.memberModules }
            ...
        if (toInvite.isNotEmpty()) {
            sendGroupMeshInvitesInternal(session.id, toInvite)
        }
```

**Field:** M03 entered `dialable` at 16:04:49; maintenance cycles ran, but no successful invite wire. Late-peer path logged `GROUP_LATE_PEER_DISCOVERED` repeatedly.

**Conclusion Q4:** Roster is not permanently frozen; **incremental admission is wired** but **execution does not complete**. Member join does not require generation change (frozen semantics respected — no successor for M03).

---

## Q5 — Does invite gate incorrectly depend on media readiness?

### Dependency graph (intended)

```text
Membership admission decision
  → GROUP_INVITE eligibility (peer edge + authority)
  → acquireMeshEngine (media edge)
  → SDP in GROUP_INVITE
  → peer accept
  → ICE
  → transmitReady
```

### Eligibility gates (pre-media)

| Gate | Layer | Media? |
|------|-------|--------|
| `PeerControlSignalingAdmission.maySendNewControl` | Local link BIDIRECTIONAL + per-peer inbound | No ICE |
| `GroupBootstrapAdmissionSupport.evaluateEdgeReadyRetry` | `peerEdgeReady` + authority + admission incomplete | No ICE |
| `GroupLatePeerAdmissionSupport.evaluate` | Topology BUILDING/OPERATIONAL + owner | No ICE |

### Execution coupling (found)

`sendGroupMeshInvitesInternal` and `issuePairwiseMeshAdmissionInvite` both call `acquireMeshEngine` **before** `sendSignalHandoff(GROUP_INVITE)`:

```3038:3046:talkback/android-board-talkback/src/main/java/com/talkback/app/TalkbackCoordinator.kt
            val engine = acquireMeshEngine(session, moduleId, forReconnect = false)
            ...
            val invited = sendSignalHandoff(
                peer,
                buildSignedEnvelope(
                    SignalType.GROUP_INVITE,
```

If `MediaSessionManager.create()` cannot complete synchronously (async release pending), **no GROUP_INVITE is sent**.

**Field:** M01 logged `Synchronous mesh engine create pending async release` **~1100×** for M02 and **4×** for M03 after admission decisions. M02 `PAIRWISE_MESH_ADMISSION_ACTIVATION_EVALUATED decision=ISSUE_INVITE` repeated without corresponding outbound `GROUP_INVITE` handoff success.

**Conclusion Q5:** Invite **eligibility** is not stuck on ICE/transmitReady. Invite **execution** is **synchronously coupled** to mesh engine creation; async engine lifecycle blocks the wire path. `transmitReady=false` is a **downstream symptom**, not the upstream gate.

---

## Summary classification

```text
Generation convergence:     FIELD PASS (frozen — do not re-open)

GROUP Membership Admission:
  Late-peer classification:  WIRED / FIRES (GROUP_LATE_PEER_DISCOVERED)
  Roster increment path:     WIRED (maintenance + late peer)
  currentMembers update:     FAIL (no completed invite/admit)

GROUP Invite:
  Eligibility (edge ready):  TRANSIENT LOCAL_NOT_BIDIRECTIONAL at cold start; then PASS
  Wire emission:             FAIL (mesh engine sync create / async release)

waitingForPrimary:         LEGITIMATE on followers until primary bootstrap completes

Media Readiness:           DOWNSTREAM (no GROUP_INVITE received → no mesh → transmitReady impossible)
```

### Leading hypothesis for next narrow cut (audit only — NOT authorized)

```text
PRIMARY: Group invite execution seam
  admission/classification PASS
  → acquireMeshEngine synchronous failure during async release
  → zero GROUP_INVITE on wire
  → followers stuck waitingForPrimary
  → M03 never joins roster

SECONDARY: Staggered device startup
  initial roster M01,M02 expected
  late M03 handled by admission layer but hits same execution break

NOT implicated:
  generation / CSO / ACQ / VB / F1
  sustained LOCAL_NOT_BIDIRECTIONAL circular gate
  M03 join requiring new generation
```

### Forbidden next steps (frozen)

```text
Re-open CSO / ACQ / generation / trust
Bypass LOCAL_NOT_BIDIRECTIONAL
Force transmitReady
Hard-seed M03 into roster without invite path
Successor generation for M03 join
```

---

## Recommended next step (authorization pending)

Read-only complete. If implementation is requested, scope a **narrow cut** at:

```text
GROUP invite execution vs media engine lifecycle decoupling
(or async-safe invite dispatch after admission PASS)
```

with field gates proving:

```text
M01 → M02 GROUP_INVITE wire success after generation convergence
M01 → M03 late-peer GROUP_INVITE after staggered join
M02/M03 waitingForPrimary clears after accept
```

Do **not** start with ICE/media retry tuning until invite wire path is proven.
