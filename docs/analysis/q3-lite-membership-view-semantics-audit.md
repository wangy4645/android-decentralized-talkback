# Q3-lite: Membership Four-View Semantics Audit

**Status:** COMPLETE (read-only; no behavior change)  
**Date:** 2026-08-07  
**Parent:** [rca-m03-wifi-reconnect-membership-divergence.md](./rca-m03-wifi-reconnect-membership-divergence.md)  
**Purpose:** Confirm which facts a future **SignalingAdmissionReady** / convergence fence can trust — before defining predicate or directed run.

## 1. Scope

**In scope:**

- Static code audit of four membership views and digest ownership
- Answer Q3.1 / Q3.2 / Q3.3 for fence predicate design
- Reframe smoke-test `members=M01,M02` observation

**Out of scope (frozen):**

- Modify `applyMembershipSnapshot`
- Append self to `groupMembers`
- Epoch / digest algorithm changes
- Invite retry / BUSY reason changes
- Any runtime fix

---

## 2. Four-view table

| View | Code source | Owner / writer | Intended semantics | Includes local self? | Used by |
|------|-------------|----------------|-------------------|----------------------|---------|
| **Authority digest** | `lastSeenAuthorityDigestByChannel[channelId].digest` (`TopologyDigest`) | Authority via HELLO / `observeAuthorityDigest()` after snapshot apply | Authoritative epoch + memberHash for channel | Hash: **remote `groupMembers` ids only** (see §4) | `membershipDigestAlignedWithAuthority()` |
| **`groupMembers`** | `TalkbackSession.groupMembers` | `applyGroupMembersList()`, session create, `promoteInviteeToCanonicalRoster()` | Canonical **endpoint roster** for GROUP control plane | **Often yes** at create (`allMembers`); **may be no** after remote-only snapshot apply | `canonicalMemberModuleIds()`, topology `members=`, digest hash |
| **`memberModules`** | `TalkbackSession.memberModules` | Session create, `applyGroupMembersList()`, `applyMembershipSnapshot()` (+local add) | Module-level membership set for mesh / transmit planning | **Yes** — local explicitly added after snapshot apply | `memberModuleIds()` → `completeGroupMesh()` targets |
| **TopologyDigest (local)** | `TopologyDigest.fromSession(session)` | Derived read-only | `{ rosterEpoch, anchorEpoch, meshGeneration, memberHash }` | **Hash excludes self** when self ∉ `groupMembers` | Digest compare, control reconciliation context |

### Authority snapshot payload (fifth related object)

| Object | Code source | Semantics | Includes local? |
|--------|-------------|-----------|-----------------|
| `MembershipSnapshot.members` (JSON strings) | `membershipSnapshotForSession()` → `rosterMembersForPayload().map { it.key }` | Module keys carried in snapshot-only GROUP_INVITE | **Depends on session type** — see §3 |
| GROUP_INVITE `payload.members` (apply input) | `GroupSessionPayload.parseMembers(payload.members)` | Endpoint keys used as `applyMembershipSnapshot(members=…)` input | **Can be full roster** (unit tests use M01–M03) |

**Important:** Snapshot apply uses **`payload.members` endpoint list**, not `membershipSnapshot.members` string list, as the roster written to `groupMembers`:

```8724:8740:talkback/android-board-talkback/src/main/java/com/talkback/app/TalkbackCoordinator.kt
        val members = GroupSessionPayload.parseMembers(payload.members)
        ...
            GroupMembershipSupport.applyMembershipSnapshot(
                session,
                snapshot.rosterEpoch,
                snapshot.anchorEpoch,
                members,
```

---

## 3. Snapshot semantics (Q3.1)

### Verdict: **Not a single global semantic — path-dependent (A + B coexist)**

#### Semantic A — Remote-only (observation / broadcast)

**Evidence:** `broadcastMembershipSnapshot()` explicitly excludes local:

```8296:8300:talkback/android-board-talkback/src/main/java/com/talkback/app/TalkbackCoordinator.kt
        GroupMembershipSupport.canonicalRosterEndpoints(session)
            .filter { it.moduleId != session.local.moduleId }
            .forEach { remote ->
```

**Implication:** Outbound snapshot push to remotes is **remote peer list**. Receiver must add local to `memberModules` (see apply path).

#### Semantic B — Full canonical roster (apply / tests)

**Evidence:** `GroupMembershipSupportTest.applyMembershipSnapshot_*` uses authority member lists including M01, M02, M03. Apply replaces `groupMembers` with the supplied endpoint list.

#### Apply path split

```121:126:talkback/android-board-talkback/src/main/java/com/talkback/core/session/GroupMembershipSupport.kt
    fun applyGroupMembersList(session: TalkbackSession, members: List<EndpointAddress>) {
        session.groupMembers = members
        session.memberModules.clear()
        members.map { it.moduleId }.forEach { session.memberModules.add(it) }
        ...
    }
```

```182:183:talkback/android-board-talkback/src/main/java/com/talkback/core/session/GroupMembershipSupport.kt
        applyGroupMembersList(session, members)
        session.memberModules.add(session.local.moduleId)
```

After apply:

- `groupMembers` = exactly what authority sent in `payload.members`
- `memberModules` = same module set **plus local** (always)

### Smoke-test reframe: `TOPOLOGY_SNAPSHOT members=M01,M02` on M03

**Not proven self-invariant violation.**

`TOPOLOGY_SNAPSHOT.members` is projected from `groupMembers` only (`GroupRuntimeHealthProjector` → `canonicalMemberModuleIds`). `localModuleId=M03` is a **separate field**.

If M03's `groupMembers` = [M01, M02] after remote-only snapshot apply, log is **consistent with Semantic A** — not evidence that M03 deleted itself from the cluster.

**Do not** patch with force-append-self without resolving path semantics.

---

## 4. Digest ownership (Q3.2)

### What `memberHash` includes

```210:214:talkback/android-board-talkback/src/main/java/com/talkback/core/session/GroupMembershipSupport.kt
    fun memberHashForSession(session: TalkbackSession): Int {
        val channelId = session.channelId ?: return 0
        val ids = canonicalMemberModuleIds(session).map { it.value }
        return memberHash(channelId, session.rosterEpoch, ids)
    }
```

`canonicalMemberModuleIds()` = **`groupMembers` only** (not `memberModules`).

Therefore:

| Structure | Includes local in hash? |
|-----------|---------------------------|
| `groupMembers` = [M01,M02,M03] | Yes |
| `groupMembers` = [M01,M02], local only in `memberModules` | **No** |

### `TopologyDigest` source

```13:18:talkback/android-board-talkback/src/main/java/com/talkback/core/model/TopologyDigest.kt
        fun fromSession(session: TalkbackSession): TopologyDigest = TopologyDigest(
            rosterEpoch = session.rosterEpoch,
            anchorEpoch = session.anchorEpoch,
            meshGeneration = session.meshGeneration,
            memberHash = GroupMembershipSupport.memberHashForSession(session)
        )
```

**Single source for local digest:** `groupMembers` → hash. No dual-source inside `TopologyDigest` itself.

### Dual-view tension (real, but not digest-vs-digest)

| View | May contain local? |
|------|-------------------|
| `groupMembers` / digest hash | Optional |
| `memberModules` | Yes (always after snapshot apply) |
| `activeMemberModuleIds()` | Yes (explicitly adds `session.local.moduleId`) |

So:

```text
memberModules ⊇ groupMembers (module-id set)
```

after snapshot apply, with local ⊆ `memberModules` \ `groupMembers` possible.

### `membershipDigestAligned=true` vs `CANONICAL_MISMATCH`

These are **different probes**:

| Signal | Mechanism |
|--------|-----------|
| `membershipDigestAligned` | `TopologyDigest.fromSession` vs `authorityDigestForChannel` epoch+hash |
| `CANONICAL_MISMATCH` (HELLO path) | `assertCanonicalConsistencyFromHello` — authority HELLO epoch/hash vs local GROUP session; logs `authorityMembers=[pending_resync]` as placeholder |

**Aligned digest does not mean** `local ∈ groupMembers`. It means **remote roster hash matches authority** for the current epoch.

Smoke log reading both `membershipDigestAligned=true` and `CANONICAL_MISMATCH` is **not necessarily contradictory** — different code paths, different timing, different authority observation state.

---

## 5. Fence candidate inputs (Q3.3)

### Existing facts (usable without new FSM)

| Predicate (concept) | Code today | Used on mesh invite path? |
|---------------------|------------|---------------------------|
| `membershipDigestAlignedWithAuthority` | `TalkbackCoordinator.membershipDigestAlignedWithAuthority()` | **No** — only projected to health / UI inputs |
| `membershipReconciled` | `GroupRuntimeHealthProjector`: `accepted && digestAligned && suspectPeers.isEmpty()` | **No** — projected only |
| `groupTopologyReadiness != MEMBERSHIP_PENDING` | Derived from above | **No** |
| `isMembershipResyncInFlight(channelId, episodeId)` | `membershipResyncRecordByKey` | **Partial** — wired to `conferenceEdgeRecoveryController.isMembershipConvergenceInFlight`, **not** `completeGroupMesh()` |
| `GroupIdentityStability` | `MEMBERSHIP_DIGEST_MISMATCH` | Floor / identity gating — not mesh invite |
| `GroupMeshReconciler.canOfferJoin` | ICE checking / backoff | **Yes** — but transport-only |

### Gap (confirms H1 static audit)

`completeGroupMesh()` → `offerGroupMeshJoin()` has **no** membership convergence gate. Retries via `scheduleGroupMeshRetries()` likewise.

### Recommended fence predicate sketch (design only — not authorized)

**Do not use:** `networkAvailable`, `iceConnected`, `sleep(N)`.

**Prefer composite on GROUP session (or channel-scoped health input):**

```text
SignalingAdmissionReady :=
    authorityDigestKnown(channelId)           // lastSeenAuthorityDigestByChannel != null
    && membershipDigestAlignedWithAuthority(session)
    && !suspectPeers.nonEmpty()
    && !isMembershipResyncInFlight(channelId, relevantEpisode)
    && groupTopologyReadiness != MEMBERSHIP_PENDING   // or membershipReconciled
```

**Conference note:** During meeting, mesh runs on **GROUP** session (`sessionId=grp:CH-01`) while CONFERENCE session is separate. Fence should target the **GROUP session** that drives `completeGroupMesh()`, not conference UI session alone.

**Transport:** Keep `GroupMeshReconciler` ICE/backoff as secondary gate — necessary but not sufficient.

---

## 6. Findings

### PASS — fence can use these (after directed run confirms correlation)

1. **`membershipDigestAlignedWithAuthority(session)`** — already computed; maps to authority epoch/hash agreement on **remote roster view**.
2. **`membershipReconciled` / `groupTopologyReadiness`** — already projected in `TOPOLOGY_SNAPSHOT`; field `membershipDigestAligned` and `groupTopologyReadiness=MEMBERSHIP_PENDING` are valid **observation tokens** for directed run T2.
3. **`isMembershipResyncInFlight(channelId, …)`** — exists for resync ownership; candidate for `NoPendingResync` arm (may need channel-level API for mesh path).
4. **Smoke `members=M01,M02`** — reframed as likely **Semantic A** (remote-only `groupMembers` projection), not confirmed self-invariant break.

### OPEN — ambiguity remains

1. **Snapshot apply dual carrier:** `membershipSnapshot.members` (JSON) vs `payload.members` (apply roster) can diverge — fence must not assume they are identical.
2. **`memberModules` vs `groupMembers`:** Mesh targeting uses `memberModuleIds()` (includes local); digest/alignment uses `groupMembers` — document in convergence contract so fence does not use wrong view.
3. **Authority digest freshness during flap:** `membershipDigestAligned` can be true while HELLO mismatch logger fires — directed run must capture ordering, not single timestamps.
4. **CONFERENCE vs GROUP session:** Fence predicate must bind to correct session id (`grp:CH-01` for mesh); conference-only state insufficient.

### FAIL — not supported

1. **"Self missing from roster" as root cause** — not established; likely log semantics.
2. **Time-based readiness** — no code path should use sleep/grace as admission authority.
3. **Immediate membership self-heal patch** — not authorized; would risk wrong invariant.

---

## 7. Impact on RCA and next steps

| Step | Action |
|------|--------|
| 1 | Update RCA Phase 2 §O3 / Q3 with this reframe |
| 2 | Commit RCA + Q3-lite together |
| 3 | Directed run T0–T4 — correlate `MEMBERSHIP_PENDING` / `membershipDigestAligned=false` with `MESH_OFFERED` |
| 4 | If correlated → draft lightweight **Post-Reconnect Convergence Contract** with `SignalingAdmissionReady` predicate above |
| 5 | Small PR: gate `completeGroupMesh()` / `offerGroupMeshJoin()` on predicate — **not authorized until step 4** |

### Risk assessment (unchanged)

| Outcome | Likelihood |
|---------|------------|
| Snapshot / member field semantics misleading observers | **High** (this audit) |
| Digest / readiness input definition unclear | **Medium** (resolved enough for predicate sketch) |
| True membership authority architecture broken | **Low** |

**Conclusion:** Problem remains **convergence contract missing**, not membership foundation failure. Fence predicate inputs **already exist in code** but are **not wired to mesh invite admission**.
