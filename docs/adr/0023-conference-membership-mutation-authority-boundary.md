# ADR-0023: Conference Membership Mutation Authority Boundary (R29) (ADR-CONF-005)

## Status

**Accepted** (2026-07-13) — R29-A/B/C/D. Implements **R26 v2** and formalizes **WM-R5** post-terminal guarantees. Complements ADR-0021 (R24–R26) and ADR-0022 R28-F (attempt vs edge obligation).

> This is not a new requirement. It closes the `R26 v2 post-terminal roster semantics` hole that `ro-m3-recovery-write-matrix.md` §7 explicitly froze, and it upgrades WM-R5 from "not violated this soak" to an enforced boundary after the P2-B soak proved it violated on participant nodes.

## Summary

The P2-B soak (session `c46a2ba0`, M02 host, M01 WiFi loss) proved that a **participant** node, after **local** media recovery failure, executed an **authority-only membership mutation transaction** — mutating canonical roster, `memberModules`, floor ownership, and mesh binding — while the host authority still held the member.

This is **not** presence/projection drift. It is a **canonical roster split**:

```text
Membership(M02 host) = [M01, M02, M03]
Membership(M03)      = [M01, M02, M03]
Membership(M01)      = [M01, M02]        ← participant self-mutated
```

This ADR freezes:

1. **Membership mutation is a transaction** (roster + memberModules + floor + mesh binding), not four independent writes.
2. **Only authority sources may execute it** (`AuthorityMembershipMutationSource`).
3. **`LOCAL_RECOVERY_FAILURE` is not a mutation source** — it is a recovery fact source only, and MUST NOT enter the mutation API.
4. **Membership mutation MUST NOT implicitly terminate the edge recovery obligation** (couples ADR-0022 R28-F).

```text
FAILED_MEDIA_RECOVERY  ≠  member_left  ≠  GROUP_LEAVE
```

## Context

### P2-B soak evidence (session `c46a2ba0`, M02 host, M01 WiFi loss)

| Node | Observation | Implication |
|------|-------------|-------------|
| M01 `10:38:39.937` | `FAILED_MEDIA_RECOVERY remote=M03 reason=attempt_timeout` | attempt terminal (R28-F) |
| M01 `10:38:42.644` | `RECOVERY_EDGE_CANCELLED remote=M03 reason=member_left` → pill `joined=2` | **participant-local** membership mutation |
| M01 `10:38:43.123` | M02 ICE `CONNECTED` → `REEVALUATE → SUPERSEDED → RECOVERED` | M01↔M02 edge was **not** cancelled and recovered |
| M02 (host) | `joined=3` throughout; M03 **never** in recovering set; no `Pruning`/`GROUP_LEAVE`/`removeConferenceParticipant(M03)` | authority never pruned M03 |
| M03 | `joined=3`; no `GROUP_LEAVE`/`CONFERENCE_LEAVE` | M03 never left |

**Key counterfactual (in-soak proof):** the M01↔M02 edge suffered the same `attempt_timeout` but was **not** subjected to `removeConferenceParticipant`, so its `cancelEdge` never fired; when route converged it re-evaluated and recovered. The M01↔M03 edge differed **only** in that the participant-local prune path ran `removeConferenceParticipant → cancelEdge(member_left) → releaseMeshPeer`, destroying the obligation before route convergence. Not pruning M03 would very likely have let it recover identically (also resolves the orphan-edge case).

### Trigger path

`10:38:39` M03 `FAILED_MEDIA_RECOVERY` → `deferConferenceParticipantPruneIfRecovering` releases (edge no longer recovering) → **post-terminal** prune (`cleanupUnhealthyConferenceSession` / `canPruneConferenceParticipant`) fires on the **participant** → `removeConferenceParticipant`. This is exactly the case `ro-m3-recovery-write-matrix.md` §3.5 marked "out of R26 v1 scope — R26 v2 / separate ADR".

### The mutation transaction (as-is)

```kotlin
// removeConferenceParticipant(session, moduleId) — four writes, no source contract:
conferenceParticipantManager.applyPrune(session.id, moduleId)   // canonical roster
conferenceEdgeRecoveryController.cancelEdge(session.id, moduleId, "member_left")  // edge obligation
session.memberModules.remove(ModuleId(moduleId))                // canonical membership
releaseMeshPeer(session, moduleId)                              // mesh binding
// + releaseFloorIfHolderUnavailable(session, moduleId)          // floor ownership
```

## Decision

### R29-A — Membership Mutation is an Authority-Owned Transaction

The following mutations form a single **Conference Membership Mutation Transaction** and MUST execute atomically under one authority source:

```text
- conferenceParticipantManager roster mutation (applyPrune / replaceRoster)
- session.memberModules mutation
- edge obligation termination (cancelEdge)
- floor ownership cleanup (releaseFloorIfHolderUnavailable)
- mesh peer release (releaseMeshPeer)
```

No caller may execute a subset. Partial execution (e.g. roster removed but mesh kept) is forbidden — it produces half-split states (`roster=3, mesh=2, floor=orphan`).

This is the concretization of `ro-m3-recovery-write-matrix.md` §1 **Membership Single Writer = `ConferenceParticipantManager` + leave/remove paths**.

### R29-B — Authority Mutation Sources

`removeConferenceParticipant` MUST take an explicit source, and the source type admits **only** authority sources:

```kotlin
enum class AuthorityMembershipMutationSource {
    AUTHORITY_GROUP_LEAVE,   // received host GROUP_LEAVE signal
    AUTHORITY_PRUNE,         // host local authoritative prune
    USER_LEAVE,              // local user leaves the conference
    HOST_TERMINATE,          // host terminates the conference
}

removeConferenceParticipant(session, moduleId, source: AuthorityMembershipMutationSource)
```

**`LOCAL_RECOVERY_FAILURE` is deliberately absent from this enum.** It is not a mutation source; it cannot be passed to the mutation API. This prevents the future regression where a `when(source){ LOCAL_RECOVERY_FAILURE -> { /* no-op today */ } }` branch silently grows a `memberModules.remove(...)` line.

Callsite ownership (aligns with write-matrix §2):

| Callsite | Source | May run mutation |
|----------|--------|------------------|
| `handleGroupLeave` | `AUTHORITY_GROUP_LEAVE` | yes |
| host `cleanupUnhealthyConferenceSession` prune | `AUTHORITY_PRUNE` | yes (only `isConferenceHostSession`) |
| `leaveConferenceInternal` | `USER_LEAVE` | yes (local) |
| host terminate path | `HOST_TERMINATE` | yes |
| `scheduleParticipantPrune` (non-host) | — (recovery fact) | **no** |
| `cleanupUnhealthyConferenceSession` (non-host) | — (recovery fact) | **no** |

### R29-C — `LOCAL_RECOVERY_FAILURE` is a Recovery Fact, not a Membership Event

On a non-authority node, media recovery failure (`FAILED_MEDIA_RECOVERY` / `attempt_timeout` / edge failure) is permitted to record **only** recovery facts:

```text
ALLOWED:
    edgeRecoveryFailed = true
    conferenceDegraded = true
    mediaUnavailablePeers += remote        // ConferenceEdgeRecoveryFact (Media Health Fact)
    preserve mesh binding
    preserve recovery record               // retained for RECOVERY_REEVALUATE

FORBIDDEN:
    removeConferenceParticipant()
    applyPrune() / memberModules.remove()
    cancelEdge(reason=member_left)
    releaseMeshPeer()
    floor release
```

**`mediaUnavailablePeers` ownership:** it lives in `ConferenceEdgeRecoveryController` / `EdgeRecoveryFacts` as a **Media Health Fact**, **not** on `TalkbackSession`. Per WM-R2 an edge-scoped fact MUST NOT gate a conference-scoped decision, and per write-matrix §1 projectors read facts only. Keeping it out of `Session` prevents membership re-contamination (`if (mediaUnavailablePeers.contains(x)) memberModules.remove(x)`).

**Media health facts are advisory only and MUST NOT participate in membership convergence.** Projectors may surface degraded/unavailable media for UI; they MUST NOT derive joined/left roster from `mediaUnavailablePeers`.

**Convergence guarantee:** participant membership is eventually consistent and is driven **solely** by the host's `GROUP_LEAVE` / roster broadcast. If M03 genuinely leaves, the host emits authority membership, and the participant removes it via `AUTHORITY_GROUP_LEAVE`.

### R29-D — Membership Mutation MUST NOT Terminate the Edge Recovery Obligation

The old model coupled membership and recovery lifetime:

```text
FAILED_MEDIA_RECOVERY → cancelEdge(member_left) → releaseMeshPeer → edge obligation gone
```

This violates **ADR-0022 R28-F** (`attempt terminal ≠ edge obligation terminal`) in addition to R29.

Frozen rule:

```text
Membership mutation MUST NOT implicitly terminate the edge recovery obligation.
```

Per R28-A the edge obligation is terminated only by `RECOVERED`, Membership `LEFT(remote)` (authority-driven), or Conference `TERMINATED`. A participant-local recovery failure is none of these, so it MUST leave both the membership and the edge obligation intact — allowing the same `RECOVERY_REEVALUATE → SUPERSEDED → RECOVERED` path the M01↔M02 edge demonstrated in this soak.

## Relationship to prior governance

| Prior | This ADR |
|-------|----------|
| write-matrix §1 Membership Single Writer | R29-A makes the four writes one authority-owned transaction |
| write-matrix WM-R5 (`FAILED_MEDIA_RECOVERY` must not mutate Membership) | R29-C enforces it on participant + post-terminal (soak now proves it was violated) |
| write-matrix §3.5 / §7 R26 v2 (post-terminal prune, frozen) | R29 is the R26 v2 resolution |
| ADR-0022 R28-F (attempt vs edge obligation) | R29-D forbids membership mutation from implicitly terminating the edge obligation |
| ADR-0013 Floor Authority Route | floor cleanup is part of the authority mutation transaction (R29-A) |

## Consequences

- **Positive:** canonical roster can no longer split from the authority on participant recovery failure; orphan mesh edges get the same re-evaluate/recovery chance (soak counterfactual); membership and recovery lifetimes are decoupled.
- **Negative:** callsites must thread an explicit source; a new `mediaUnavailablePeers` recovery fact + projection wiring.
- **Neutral:** presence/pill divergence (`M01=2` vs host `3`) is a downstream symptom, not a UI bug — fixing UI before R29 would only mask the split.

## Priority / sequencing (frozen)

```text
P0  R29  (this ADR)          — participant timeout ≠ roster/floor/mesh mutation
P1  supersede / watchdog     — REATTACH_ACCEPTED must supersede; old watchdog must not kill a live edge
P2  Presence / Pill          — do not touch until P0 + P1 land
```

## Soak gates (future)

| Gate | Pass criterion | Status |
|------|----------------|--------|
| G-R29-1 | participant `timeout(remote)`: `roster` and `memberModules` unchanged; mesh preserved; `edgeRecoveryFailed=true`; **recovery record preserved** (route restore → `RECOVERY_REEVALUATE` still possible); **no** `member_left` on non-authority | **PASS** IT `conferenceR29_participantHealthCleanup_doesNotMutateMembership` + `conferenceR29_participantPreservesEdgeObligationDuringRecovery`. Evidence: participant health cleanup keeps roster; no `member_left` cancel; failed-media fact + `RECOVERY_MEDIA_DEGRADED`; edge preserved so later `RECOVERY_REEVALUATE` remains possible |
| G-R29-2 | only host prune → all three nodes converge `roster=2` via `GROUP_LEAVE`/roster broadcast | **PASS** IT `conferenceR29E_hostMayAuthorityPruneAfterObligationDeadline` + `conferenceR29E4_authorityPruneConvergesJoinedCountAndRosterEpoch`. Evidence: only host `AUTHORITY_PRUNE` mutates membership; after prune commit, host and participant converge equal joined count and same roster epoch via leave/roster propagation |
| G-R29-3 | `FAILED_MEDIA_RECOVERY` → route restored → `RECOVERY_REEVALUATE` → `RECOVERED` (edge not torn down early) | Pending — IT proves `FAILED_MEDIA_RECOVERY` → route restore → `RECOVERY_REEVALUATE` without early tear-down (`conferenceR28H2_materialReevalKeepsObligationOpenWithoutPrune` / `conferenceR29_participantPreservesEdgeObligationDuringRecovery`); full `RECOVERED` close after that path not yet gated |

## Test plan (by invariant, not by module)

1. **(highest) participant recovery failure does not delete members** — `ConferencePruneIntegrationTest` M03 case: after M01 `timeout(M03)` assert `roster==3 && memberModules==3 && mesh preserved && edgeRecoveryFailed==true && recovery record preserved` (route restore → `RECOVERY_REEVALUATE` still possible).
2. **only host may prune** — M02 `prune(M03)` → three nodes end `roster==2`.
3. **failure then re-recover** — `FAILED_MEDIA_RECOVERY → route restored → REEVALUATE → RECOVERED`; assert edge not torn down before re-evaluate.

## Non-goals / open questions

- **Host migration / authority unreachable:** when the authority itself is unreachable, **no node** prunes — freeze membership until authority restored or host migration completes. Not implemented in P0.
- **P1 supersede/watchdog** is a separate follow-up.
- **Presence / host pill** (P2/P3) remains deferred.

## References

- ADR-0021 — Conference Edge Recovery Lifecycle (R24–R26)
- ADR-0022 — Recovery Completion Ownership & Reachability (R28-F attempt vs edge obligation)
- ADR-0013 — Floor Authority Route
- `docs/audit/ro-m3-recovery-write-matrix.md` (§1 Membership Single Writer, WM-R5, §3.5 / §7 R26 v2)
- Soak `logs-p2a-reevaluate-20260713-104019` (session `c46a2ba0`)
