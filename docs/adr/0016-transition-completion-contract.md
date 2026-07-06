# ADR-0016: Transition Completion Contract (TCC)

## Status

Proposed (2026-07-06) — **MEETING_START predicate inputs amended by
[ADR-0017](./0017-transition-declaration-and-media-lifecycle.md)**

## Context

[ADR-0015](./0015-runtime-governance-transition-coordination.md) introduced per-channel Transition
Coordination and Operation Gating. RO-G1 Phase 1 soak (2026-07-06, three devices) proved the
**control plane** is stable:

- `GATE_DECISION BLOCK` = 0 across all devices
- Every `TRANSITION_BEGIN` received a `TRANSITION_TERMINAL` within policy timeout

However, soak also exposed a **semantic completion gap**:

> `TRANSITION_TERMINAL: READY` can occur while conference media paths are still negotiating —
> especially after GROUP → Meeting on a channel where host–participant ICE has not reconverged.

Example (session `3b721fe9`, M02, second Meeting after PTT):

1. M01 `MEETING_START` → `TERMINAL READY` in ~6ms (mesh call returned)
2. M02 `Conference invite accepted` → `Deferring full conference mesh until host link is stable`
3. 33s ICE restart loop → `Left conference locally`

This is not a Gate failure. It is **completion without convergence**: the governance layer declared
the establishment transition finished before the Establishment predicate was true.

### Relationship to ADR-0015

ADR-0015 defines *who* declares transitions and *how* Gate uses Capability snapshots. TCC defines
*when* `completeTransition` may be called — the **Completion Predicate** per `TransitionTrigger`.

TCC is an **amendment** to ADR-0015 § Transition Terminal State: `READY` SHALL mean predicate
satisfied, not merely "caller returned".

## Decision

### 1. Transition Completion Model

- Per channel, at most one ACTIVE transition (unchanged from ADR-0015).
- `completeTransition(channelId)` **SHALL** only be invoked when
  `CompletionPredicate(trigger, channelId)` evaluates to `satisfied=true`.
- **MUST NOT** call `completeTransition` at the end of a mesh-call / invite-send function unless
  the predicate is already true (solo host is the explicit exception below).
- Unsatisfied evaluations **SHALL** be logged as `TRANSITION_PREDICATE_EVAL satisfied=false reason=...`
  at most once per evaluation boundary (ICE change, convergence boundary, explicit probe).

### 2. Completion Predicates (v1)

#### `MEETING_END` (Recovery — unchanged intent)

Complete when GROUP topology on channel is recoverable:

- Accepted GROUP session exists
- `GroupTopologyReadiness == OPERATIONAL`
- Membership digest aligned
- No accepted CONFERENCE on channel
- Channel mode ∈ {IDLE, GROUP_PTT}

(Already wired via `maybeCompleteRecovery` + `onGroupConvergenceBoundary`.)

#### `MEETING_START` (Establish — **new contract**)

Only the **host** declares `MEETING_START`. Complete when:

| Role | Predicate |
|------|-----------|
| **Host solo** (no invite targets at creation) | Accepted CONFERENCE session exists on channel |
| **Host with invitees** | Accepted CONFERENCE + ≥1 invited peer ICE `CONNECTED` |
| **Participant** | Does not own `MEETING_START`; Conference Capability reflects host-link readiness instead |

**Rejected:** `meshCallInternal` return ⇒ `READY` for non-solo host.

#### `GROUP_BOOTSTRAP` / `IDENTITY_REBOUND` (deferred v1.1)

Predicate aligns with `TOPOLOGY_SNAPSHOT` OPERATIONAL + digest aligned. Track in follow-up issue;
not blocking TCC v1.

### 3. Capability alignment

`Capability.Conference` admission on a channel **SHALL** return:

- `READY` — host may invite / channel idle for conference
- `RECONCILING` — participant accepted CONFERENCE but host ICE not `CONNECTED`, or active `MEETING_END`
- `NOT_READY` — conference blocked (e.g. stale reclaim guard)

`Capability.Media` during an active CONFERENCE **SHALL** derive from conference session readiness
(`isConferenceUiReady` semantics), not stale GROUP snapshot.

### 4. Soak hard gates (RO-G3 extension)

These grep KPIs **fail the run** if violated (anti false-green):

| ID | Rule |
|----|------|
| S1 | Every `TRANSITION_BEGIN` has matching `TRANSITION_TERMINAL` within `deadlineMs + 2s` |
| S2 | Within 2s after `TERMINAL=READY` for `MEETING_START`, no `ice=CLOSED` on conference host link for that channel |
| S3 | No `Deferring full conference mesh` span >10s without intervening `TRANSITION_*` or `ICE` log |
| S5 | Second Meeting per round: invite sent → accepted → participant host ICE `CONNECTED` within 15s |

## Consequences

- Host `MEETING_START` may remain `RECONCILING` for seconds while first peer ICE converges — Gate
  for operations that depend on active transition may block longer; this is intentional.
- Solo host enters meeting immediately (predicate satisfied at session creation).
- Participant "Connecting" UI remains until host ICE connects; Conference Capability stays
  `RECONCILING` during that window.
- Integration tests must assert transition completion **after** ICE, not at call return.

## Implementation tracking

| Item | Issue |
|------|-------|
| ADR-0016 + CONTEXT | This ADR |
| `MEETING_START` predicate wiring | #55 |
| Conference Capability / Media probe | #55 |
| Red integration test (second meeting ICE) | #56 |
| RO-G3 soak S1–S5 script | #57 |

## References

- ADR-0015 Runtime Governance
- ADR-0014 Conference Host-Owned Lifetime
- Soak logs: `logs-rog1-20260706-141845`
- Epic: #50
