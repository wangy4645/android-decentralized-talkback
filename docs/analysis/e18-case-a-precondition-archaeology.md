# E.18 Case-A Precondition Archaeology

**Status:** `ARCHAEOLOGY` (2026-08-03)  
**Inputs:** Attempt-4c-A `204240` | [e18-attempt4c-a-postmortem.md](./e18-attempt4c-a-postmortem.md) | ADR-0022 §E.18 / §E.21 | production `TalkbackCoordinator` / resolver / probe  
**Does not:** amend ADR | merge PR | authorize re-run | claim `CASE_A_FAILED`

**PR posture:** `HOLD` — ownership gate may change #118 semantic boundary; do not merge field contract until this note is reviewed.

---

## 1. Scope

Frozen questions from post-Attempt-4c-A review:

| ID | Question |
|----|----------|
| **Q1** | `isLocalMembershipAuthority=true` — source, retention, relinquish |
| **Q2** | Case-A = authority **absent** vs authority **unreachable** (ADR alignment) |
| **Q3** | Digest cache vs authority ownership — separate preconditions? resolver order? |

**Out of scope:** fixing resolver/probe in this note; R4-impl; Case-B replay.

---

## 2. Authority ownership lifecycle

### 2.1 Assignment events (production)

Authority identity for membership convergence is **not** a single HELLO flag. Three layered mechanisms apply:

```text
(1) session.anchorModuleId          — per-session anchor (GROUP payloads, split-brain merge)
(2) resolveBootstrapPrimary()       — channel mesh bootstrap host (ChannelMeshHostElection)
(3) recordAuthorityDigestFromHello  — observation cache only (does NOT grant ownership)
```

**`isMembershipAuthority(session)`** (`TalkbackCoordinator` ~7850):

```kotlin
if (session.anchorModuleId == localModuleId) return true
return resolveBootstrapPrimary(dialableRemoteModuleIds() + localModuleId) == localModuleId
```

**`WiredMembershipEpochProbe`** passes `isLocalMembershipAuthority = isMembershipAuthority(conferenceSession)` into `RecoveryMembershipContext` (~524–536).

**Implication:** CONFERENCE host (M02) can become membership authority **without** M01 HELLO, if:

- `anchorModuleId == M02`, or
- bootstrap primary resolves to `M02` among `{local} ∪ dialableRemotes`.

### 2.2 Bootstrap primary election

`ChannelMeshHostElection.electHost` → `AnchorRanking.electForBootstrap`:

| State | Rule |
|-------|------|
| Remote HELLO health **incomplete** for all remotes | Fallback: **min `moduleId` lexicographic** among candidates |
| Directory complete | Rank by anchor health score |

Candidates = `{localModuleId} ∪ reachableModuleIds` where reachable comes from `dialableRemoteModuleIds()` (~10012).

**`isModuleDialable`** (~3362): true if module in `discoveredByModule`, `staticPeers`, `signalPeersByModule`, or presence with host/port — **not** “currently online / HELLO fresh”.

**Attempt-4c-A `204240` consequence:** With M01 in static/discovery roster but WiFi OFF, M01 may still be dialable. If health directory incomplete, bootstrap min is often **M01** → `isLocalMembershipAuthority=false` on M02 **unless** conference `anchorModuleId` or host role overrides.

Observed run had `LOCAL_IS_MEMBERSHIP_AUTHORITY` + `authorityId=M02` → on that episode M02 **was** local membership authority (host/bootstrap/anchor path), independent of M01 silence at recovery time.

### 2.3 Retention

| Artifact | Cleared on session hangup? | Cleared on mesh leave? | Cleared on M01 WiFi OFF? |
|----------|---------------------------|------------------------|--------------------------|
| `sessions[conferenceId]` | yes (`hangupInternal` ~6752) | n/a | n/a |
| `lastSeenAuthorityDigestByChannel` | **no** | **no** | **no** |
| `anchorModuleId` on other sessions | per-session | per-session | no |
| Bootstrap primary | recomputed each call | recomputed | M01 may remain in dialable set |

`hangupInternal` clears conference recovery state, mesh reconciler channel, media buses — **does not** touch `lastSeenAuthorityDigestByChannel` (grep: put-only, no `remove`/`clear` in production).

### 2.4 Relinquish paths (production)

| Mechanism | Effect on membership authority | Effect on digest cache |
|-----------|-------------------------------|------------------------|
| `demoteToMemberAndReconnect` (~9883) | Moves `anchorModuleId` to winner | none |
| `yieldDuplicateGroupSession` (~4932) | Yields duplicate GROUP to remote authority | none |
| Split-brain / anchor merge (~9805–9890) | Reassigns `anchorModuleId` | none |
| M01 stop HELLO / WiFi OFF | none on M02 ownership | none (cache retains) |
| Process death / cold start | full reset | full reset |
| `testSeedAuthorityDigestForChannel` | test only | test write |

**Answer to Q1 sub-question:**

> Does a module that became authority ever **naturally** relinquish membership authority in the same runtime?

**Partially** — anchor demotion / split-brain can move `anchorModuleId`, but **no production path clears `lastSeenAuthorityDigestByChannel`**. Digest and ownership are decoupled; relinquish of anchor does not evict stale digest.

---

## 3. Local authority election (CONFERENCE vs GROUP)

| Session type | `groupMembers` at create | `ensureConvergenceAnchor` | Typical probe `localMembershipView` |
|--------------|--------------------------|---------------------------|-------------------------------------|
| GROUP | populated (~1877) | yes (~1903) | GROUP topology digest |
| CONFERENCE | **not set** (~1874–1878) | **no** | `TopologyDigest.fromSession(conference)` → often **empty roster** (~Case-B archaeology) |

Membership probe runs on **CONFERENCE** `sessionId` (`WiredMembershipEpochProbe` ~528–535) while authority digest often reflects **GROUP** plane (M01 HELLO). This is the documented projection split (ADR §E.12, Case-B reachability analysis).

For Case-A, the decisive branch is **`isLocalMembershipAuthority`**, which is evaluated on the **same CONFERENCE session** — not GROUP.

**CONFERENCE create** (~1869–1873): sets `initiatorModuleId` / `floorAuthorityModuleId` to local host; does **not** set `anchorModuleId`. Host M02 still becomes authority via bootstrap primary when M02 wins election among dialable members.

---

## 4. Authority relinquish paths (summary diagram)

```text
                    ┌─────────────────────────────────────┐
                    │  Membership authority (runtime)      │
                    │  isMembershipAuthority(session)      │
                    └─────────────────────────────────────┘
                                      │
              ┌───────────────────────┼───────────────────────┐
              ▼                       ▼                       ▼
     anchorModuleId==local    bootstrapPrimary==local    else: follower
              │                       │                       │
              └───────────┬───────────┘                       │
                          ▼                                   ▼
              LOCAL_IS_MEMBERSHIP_AUTHORITY          resolver uses digest
              (short-circuit converged=true)                  │
                          │                       ┌───────────┴───────────┐
                          │                       ▼                       ▼
                          │                 digest==null              digest!=null
                          │                       │                       │
                          │                    UNWIRED              ALIGNED / HASH_MISMATCH
                          │                  (if probe wired)      (Case B / Case C)
                          ▼
                   Case-A blocked on this node
```

**No arrow** from “M01 stops HELLO” to “digest cleared” or “ownership relinquished on M02”.

---

## 5. Digest cache lifecycle

### 5.1 Writers

| Writer | When |
|--------|------|
| `recordAuthorityDigestFromHello` (~11000) | GROUP session exists; HELLO from `anchorModuleId` or bootstrap primary; `rosterEpoch > 0` |
| `testSeedAuthorityDigestForChannel` (~3058) | test only |

### 5.2 Readers

| Consumer | Use |
|----------|-----|
| `DefaultMembershipAuthorityResolver` | epoch/hash compare |
| `membershipDigestAlignedWithAuthority` | stability / governance |
| `evaluateGroupIdentityStability` | `authorityDigestSeen` boolean |

### 5.3 Eviction

**None in production.** Cache survives:

- conference hangup
- new conference UUID on same channel
- M01 radio silence
- recovery episodes

Only **process restart** or test hooks guarantee empty cache.

### 5.4 Observation vs reality (Q3)

| State | `authorityDigestSeen` | `isLocalMembershipAuthority` | Resolver path |
|-------|----------------------|-------------------------------|---------------|
| Stale digest, owner offline, follower | true | false | `HASH_MISMATCH` or `EPOCH_MISMATCH` → **CHECKED(false)** — **Case C** |
| No digest, follower | false | false | `AUTHORITY_DIGEST_MISSING` → probe **UNWIRED** — **Case A** |
| Stale digest, local authority | true | true | `LOCAL_IS_MEMBERSHIP_AUTHORITY` → **CHECKED(true)** — **Case B family** |
| No digest, local authority | false | true | `LOCAL_IS_MEMBERSHIP_AUTHORITY` → **CHECKED(true)** — **Case B family** (Attempt-4c-A `204240`) |

**Critical:** Stale digest **without** local authority is **not** Case-A in current resolver — it is **Case C** (`CHECKED(false)`). ADR §E.21.5: “authority answered; not infrastructure gap.”

Stale digest **with** local authority (Attempt-4c-A) is **Case B path** on host — even when M01 is silent.

---

## 6. Case-A reachability matrix

Legend: M02 = auth observation module (§E.21 canonical surface).

| # | Topology | M01 roster | Digest cache (CH-01) | `isLocalMembershipAuthority` (M02) | Expected disposition (M02) | §E.21 case |
|---|----------|------------|----------------------|-----------------------------------|---------------------------|------------|
| R1 | **M02 host**, M03 joined, M01 silent (Attempt-4c-A `204240`) | static/dialable | stale or fresh | **true** | `CHECKED(true)` | **B** (not A) |
| R2 | M02 follower, M03 host, digest absent | any | absent | false | `UNWIRED` | **A** |
| R3 | M02 follower, M01 bootstrap offline, digest absent | dialable M01 | absent | false | `UNWIRED` | **A** |
| R4 | M02 follower, digest present, local/conference view mismatches | any | present | false | `CHECKED(false)` | **C** |
| R5 | Cold start, follower, no digest | any | absent | false | `UNWIRED` | **A** |
| R6 | Joint `joint1-165532` (M02 auth UNWIRED lines) | varies | absent in window | false (inferred) | `UNWIRED` | **A** (lines only; wrong exercise class) |

**Historical evidence:** Step-0 `joint1-165532` already produced §E.21.3 log lines on M02 — blocked by Joint/SUPPRESS exercise class, not by unreachable UNWIRED.

**#118 frozen topology (`M02 = host`)** aligns with row **R1** — structurally **anti-Case-A** on M02 auth surface when host wins bootstrap/anchor.

---

## 7. Q2 — Absent vs unreachable (ADR)

### ADR-0022 §E.18.2 (frozen)

```text
Unwired  = no authority answered
Checked(false) = authority answered with epoch/hash mismatch
```

### §E.21.3 Case A (frozen)

```text
membership authority digest unavailable
(no lastSeenAuthorityDigestByChannel entry; local not membership authority)
```

Purpose: **infrastructure missing** — not epoch mismatch; not “authority exists but offline.”

### Mapping “M01 offline, M02 retains role”

| Interpretation | Disposition | Case label |
|----------------|-------------|------------|
| No digest + not local authority | `UNWIRED` | **A** |
| Digest present + not local authority + mismatch | `CHECKED(false)` | **C** |
| Digest present or absent + **local authority** | `CHECKED(true)` via `LOCAL_IS_MEMBERSHIP_AUTHORITY` | **B family** |
| Digest present + aligned | `CHECKED(true)` | **B** (field N/A under reachability exception) |

**Conclusion Q2:** ADR does **not** treat “authority unreachable” as Case-A. Unreachable with cached digest is **wired authority that does not answer cleanly** → **Case C** (or B if aligned). Case-A requires **infrastructure missing** on a **non-authority** node.

**Not a new branch** — contract must not conflate M01 offline with `AUTHORITY_UNWIRED` while M02 holds ownership.

---

## 8. Exercise-class note (Attempt-4c vs §E.21.2)

ADR §E.21.2 informative topology:

```text
supersede / admission → successor episode → control reconciliation on successor
```

Attempt-4c-A harness (`run-attempt4c-baseline.ps1 -Attempt 4c`) is **D1 admission diag + conference recovery flap**, not the successor-admission stimulus in §E.21.3.

Attempt-4c-A `204240` exited `D1_DIAG_C` — consistent with D1 exercise, not §E.21.3 Case-A field proof.

**Archaeology verdict:** Even with perfect ownership/digest gates, **4c flap on host conference** is not the same observation contract as §E.21.3 successor topology. Step-0 already classified Joint UNWIRED lines as `INSUFFICIENT` for exercise class.

---

## 9. Decision (frozen pending operator review)

### Primary classification

```text
Decision = HYBRID (B-runtime-lifecycle + C-semantic/topology)
```

Not pure Situation A (no natural relinquish + no digest eviction).  
Not pure Situation B (UNWIRED **is** reachable — `joint1-165532`, matrix rows R2–R3, R5–R6).  
Includes Situation C (#118 `M02 = host` conflicts with ADR E.21.3 second clause).

### Situation mapping

| Situation | Applies? | Evidence |
|-----------|----------|----------|
| **A** — field stimulus can satisfy gates with contract tweak only | **No** | Host topology → R1; need follower role + digest absent |
| **B** — no relinquish, no eviction; lifecycle reset needed for **current host script** | **Yes (partial)** | `lastSeenAuthorityDigestByChannel` put-only; hangup does not clear |
| **C** — ADR semantics require contract/topology reinterpretation | **Yes (partial)** | `M02 = host` vs `local not membership authority`; unreachable ≠ UNWIRED |

### Recommended status labels

```text
Case-A field (under #118 host topology)   BLOCKED_BY_RUNTIME_LIFECYCLE
                                          + TOPOLOGY_CONTRADICTS_ADR_E21_3

Case-A field (follower topology)          FIELD_PLAN_REVIEW
                                          (e18-attempt4c-a-follower-field-plan.md)

Attempt-4c-A (host)                       COMPLETE / NOT_PROMOTED

Attempt-4c-A (follower)                 NOT_STARTED

PR #118                                   HOLD (close/replace)

Next                                    Review follower field plan; no host-topology field
```

### What would **not** be honest

- Label Attempt-4c-A `204240` as `CASE_A_FAILED` or `INCONCLUSIVE`
- Merge #118 as “ready for field” without resolving **host vs follower** observation topology
- Cold-start re-run **only** to clear digest without documenting **why** host M02 cannot be Case-A surface

### What **would** unblock (ordered)

1. **Topology decision (taken 2026-08-03):** follower topology on M02 -- see [e18-attempt4c-a-follower-field-plan.md](./e18-attempt4c-a-follower-field-plan.md). No ADR amendment for host-path UNWIRED.
2. **Lifecycle decision:** cold-start = operator precondition only; abort if gates unreachable.
3. **Exercise decision:** Attempt-4c-A separate from E.21.2 successor admission.
4. **PR review** -- close/replace #118; new contract-delta PR (do not rebase-merge #118).

---

## 10. Code references (quick index)

| Topic | Location |
|-------|----------|
| `isMembershipAuthority` | `TalkbackCoordinator.kt` ~7850 |
| `resolveBootstrapPrimary` | ~9727 |
| `dialableRemoteModuleIds` / `isModuleDialable` | ~10012, ~3362 |
| `WiredMembershipEpochProbe` | `WiredMembershipEpochProbe.kt` |
| Resolver short-circuit | `MembershipAuthorityResolver.kt` ~49–52 |
| Digest write | `TalkbackCoordinator.kt` ~11000 |
| Digest never cleared | `lastSeenAuthorityDigestByChannel` ~826 (put only) |
| ADR Case A topology | ADR-0022 §E.21.3 ~11545 |
| ADR Unwired semantics | §E.18.2 ~11288 |
| Prior UNWIRED on M02 | `e18-step0-log-archaeology.md` `joint1-165532` |

---

*Local archaeology only. Does not change ADR status or production behavior.*
