# ADR-0022: Recovery Completion Ownership & Reachability (ADR-CONF-004)

## Status

**Partial Accepted** (2026-07-10; **R28-H / R28-H.1 Accepted 2026-07-13**; **R28-H.2 Accepted 2026-07-13**; **R28-I Accepted 2026-07-14**) — **Accepted:** R27′-A/B, R28-D/D1 (gate), **R28-E/F/G** (P2-A completion re-evaluate seam, frozen `/grill-with-docs` 2026-07-10), **R28-H / R28-H.1** (Recovery Edge Obligation Lifetime + deadline / pending-decision single writer; soak `647484ef`), **R28-H.2** (DISCONNECTED_DEBOUNCING reconnect clears suspicion without starting recovery), **R28-I** (WAITING ownership; soak `ea6466f1` M03→M02 participant edge). **Accepted companion:** ADR-0024 R29-E (host prune eligibility consumes R28-H; does not redefine obligation). **Draft:** P2-B re-evaluate action decision tree, full S13 completion. Complements ADR-0021 (R24–R26) and ADR-0023 (R29).

## Summary

S13-B soak proved `RECOVERY_REATTACH_SENT` with `peerReachable=true transportReady=true` does **not** imply host inbound — root cause is **boolean collapse** of orthogonal reachability layers, not missing recovery machinery.

This ADR freezes:

1. **Recovery Edge vs Recovery Attempt** (edge obligation ≠ attempt terminal)
2. **Completion ownership** (per-edge controller, not initiator module)
3. **Action authority + explicit completion decisions** (no decision vacuum)
4. **Two-axis reachability** (`ReachabilitySnapshot`, not linear chain)
5. **Presence projection boundary** (UI reads `ConferencePresenceProjection`, never `ReachabilitySnapshot`)
6. **Recovery Edge Obligation Lifetime** (R28-H: OPEN/CLOSED exclusive close set + observation window; attempt terminal ≠ obligation CLOSED)
7. **WAITING ownership** (R28-I: every WAITING state must name a next-action owner)

```text
ReachabilitySnapshot  →  Recovery Controller  →  EdgeRecoveryFacts
                                                      ↓
                              ConferenceRuntimeProjector  |  ConferencePresenceProjector
                                      ↓                 |           ↓
                              Runtime phase/UI          |    joined/connected/recoveringPeers
                                                          ↓
                                                         UI
```

## Context

### S13-B soak evidence (session `dc040181`, M02 host, M01 WiFi loss)

| Observation | Implication |
|-------------|-------------|
| M01 `RECOVERY_REATTACH_SENT` `peerReachable=true transportReady=true` | Local send success ≠ mesh delivery |
| M02 no `INBOUND` / no `RECOVERED` | `routeConverged=false` while authority view stale |
| M02 Meeting pill `roster=3` while `connected=1` | UI reads membership count, not presence projection |
| M01 SENT then M02 silent | **Decision vacuum** — no `WAITING(reason)` emitted |

Probe markers (`peerReachable`, `transportReady`) are **diagnostic only** until R28-D1 gates replace them.

### Architectural layers (extended)

```text
Conference Lifecycle           — ESTABLISHED / TERMINATED
Membership Authority           — JOINED / LEFT
Reachability fact writers      — Connectivity, Discovery, Signaling/Mesh, Conference Runtime
ConferenceEdgeRecoveryController — per-edge policy, ReachabilitySnapshot consumer, EdgeRecoveryFacts producer
ConferenceRuntimeProjector     — phase, bootstrap, degraded, authority (lifecycle/runtime)
ConferencePresenceProjector    — joinedCount, connectedCount, recoveringPeers (presence)
```

## Decision

### R28-A — Recovery Edge vs Recovery Attempt

A **Recovery Edge** is keyed `(sessionId, remoteModuleId)` and may span multiple **Recovery Attempts** (`attemptId`).

| Terminal scope | Allowed values |
|----------------|----------------|
| **Attempt** | `RECOVERED`, `CANCELLED`, `ATTEMPT_TIMEOUT`, `SUPERSEDED` |
| **Edge obligation** | `RECOVERED`, Membership `LEFT(remoteModuleId)`, `CONFERENCE_TERMINATED`, **`obligationDeadline exceeded` (R28-H)** |

**`ATTEMPT_TIMEOUT` terminates the attempt, not the edge completion obligation.**

When reachability improves after attempt terminal, the edge controller **MUST re-evaluate** completion. Re-evaluate **MAY** start a new attempt but **MUST NOT** be conflated with "must start next attempt."

Complements ADR-0021 R24 (Strategy A degraded residency); R28-A clarifies edge/attempt orthogonality R24 assumes.

### R28-B — Completion Ownership

**Owner** = per-edge **Conference Edge Recovery Controller** on **this device** for **this** `(sessionId, remoteModuleId)`.

| Concept | Meaning |
|---------|---------|
| **Completion Owner** | Controller maintaining re-evaluate obligation until edge terminal |
| **Preferred Recovery Initiator** | Role hint (`initiatesReattach`); **≠** lifetime owner |
| **Recovery Action Authority** | Which side **may invoke** role-allowed actions when reachable |

Exactly-one obligation is **per edge per local controller**, not "M01 or M02 owns the edge globally."

### R28-C — Action Authority & Explicit Decisions

**v1 capabilities** (minimum):

| Role | Allowed actions |
|------|-----------------|
| Preferred initiator (participant edge) | Dispatch `RECOVERY_REATTACH` |
| Authority (host) | Accept/reject reattach; bounded **media recovery actions** (not frozen to ICE restart only) |
| Both | **MUST NOT** mutate membership |

On every **re-evaluate** (including after reachability change, attempt terminal, or inbound timeout), controller **MUST** emit exactly one **Recovery Completion Decision**:

```text
1. role-allowed completion action
2. WAITING(reason)
3. SUPERSEDED(nextAttemptId)
4. CANCELLED(reason)
```

**Forbidden:** passive wait with no logged decision (S13-B vacuum: SENT → host silence).

#### WAITING(reason) taxonomy

**Connectivity waiting** — not eligible to run recovery protocol:

```text
WAITING_FOR_LINK
WAITING_FOR_DISCOVERY
WAITING_FOR_ROUTE
```

**Protocol waiting** — protocol started, not yet complete:

```text
WAITING_FOR_AUTHORITY
WAITING_FOR_INBOUND
WAITING_FOR_ACCEPT
```

Mapping from `EdgeReachabilitySnapshot` (R28-D): e.g. `!routeConverged` → `WAITING_FOR_ROUTE`, not `WAITING_FOR_AUTHORITY`.

### R28-D — Edge Reachability Facts (two-axis model)

Recovery Controller aggregates **read-only facts**; it **does not own** and **MUST NOT write back** them.

| Fact | Writer | Meaning |
|------|--------|---------|
| `linkReady` | Connectivity | Local network usable |
| `peerDiscovered` | Discovery | Remote module visible |
| `routeConverged` | Signaling / Mesh | Packets can enter routing domain |
| `authorityReachable` | Conference Runtime | Authority can serve conference semantics |

```kotlin
ReachabilitySnapshot(
    linkReady: Boolean,
    peerDiscovered: Boolean,
    routeConverged: Boolean,
    authorityReachable: Boolean,
)
```

**NOT a linear chain.** `authorityReachable` and `routeConverged` are **orthogonal axes**. Soak counterexample: `peerDiscovered=true`, stale `authorityReachable=true`, `routeConverged=false` → reattach sent, host receives nothing.

#### Gates

```text
canDispatchRecoverySignal(edge) :=
    linkReady && peerDiscovered && routeConverged

canCompleteRecovery(edge) :=
    canDispatchRecoverySignal(edge) && authorityReachable
```

Controller **MUST** evaluate dispatch against `canDispatchRecoverySignal` before sending recovery signals.

### R28-D1 — No Boolean Collapse

Recovery decisions **MUST NOT** depend on standalone booleans:

```text
peerReachable
transportReady
authorityReachable   (as sole gate)
```

Completion and dispatch gates **MUST** be evaluated against **`ReachabilitySnapshot`**.

Diagnostic probes (S13-B) may log legacy fields; they **MUST NOT** drive gating after R28 implementation.

### R28-E — Completion Re-evaluate Seam (P2-A)

#### Core invariant

```text
Media Edge Restored     — transport / ICE connectivity re-established (connectivity fact)
Recovery Edge Completed — controller explicitly declares edge terminal (recovery decision)

Media Edge Restored MUST NOT imply Recovery Edge Completed.
```

#### ICE restoration vs completion

When edge phase is **`RECOVERY_PENDING`** (or otherwise non-terminal per R28-F) and **control-plane has not started** for the current attempt:

```text
controlPlaneStarted := attempt has crossed the control-plane boundary
    (e.g. REATTACH_REQUESTED, REATTACH_ACCEPTED, ICE_RESTARTING)
```

**ICE connectivity restoration MUST NOT directly transition the edge to `RECOVERED`.**

Instead, the controller **MUST**:

```text
1. record the media restoration fact (no phase shortcut)
2. emit RECOVERY_REEVALUATE
3. run completion evaluation (R28-C)
```

Only completion evaluation **MAY** produce: `RECOVERED`, `WAITING(reason)`, `SUPERSEDED(nextAttemptId)`, `CANCELLED(reason)`.

**Narrow exception:** when `controlPlaneStarted == true`, ICE CONNECTED **MAY** satisfy completion evaluation immediately and yield `RECOVERED`.

**Forbidden:**

```text
phase == REATTACH_REQUESTED → direct RECOVERED   (use controlPlaneStarted, not phase enumeration)
routeConverged → coordinator.resend()
ICE CONNECTED → auto REATTACH_REQUESTED
```

The re-evaluate **seam is identical for all edges** (host and participant). Role differences appear only in **evaluation output** (P2-B), not in which connectivity events invoke re-evaluate.

### R28-F — Attempt Terminal vs Edge Obligation

#### Definitions

| Term | Meaning |
|------|---------|
| **Attempt Terminal** | Current recovery attempt ends: `RECOVERED`, `FAILED_MEDIA_RECOVERY`, `CANCELLED`, `SUPERSEDED` |
| **Edge Obligation** | Completion owner maintains re-evaluate duty until edge terminal: `RECOVERED`, Membership `LEFT(remote)`, or Conference `TERMINATED` |
| **Superseded Attempt** | Material capability change causes explicit abandonment of current attempt; new attempt receives new budget |

#### Rules

```text
attempt_timeout terminates the current attempt only.
It MUST NOT terminate the edge obligation.
```

**Phase model (v1 / P2-A):** `FAILED_MEDIA_RECOVERY` = **attempt terminal marker**; edge record **remains** in the controller map (R24-A degraded residency). P2-A deferred an explicit obligation state machine; **R28-H supersedes that deferral** and freezes `OPEN`/`CLOSED` lifetime + `obligationDeadline`.

When a **material** reachability transition occurs **after** attempt terminal (`FAILED_MEDIA_RECOVERY` record retained):

```text
controller MUST:
    1. emit RECOVERY_REEVALUATE
    2. perform completion evaluation

evaluation MAY produce:
    SUPERSEDED(nextAttemptId)   — not required on every transition
    WAITING(reason)
    CANCELLED(reason)
    RECOVERED
```

**Watchdog:**

```text
watchdog budget belongs to attempts, not to recovery edges.

RECOVERY_REEVALUATE  ≠ extend watchdog
RECOVERY_WAITING       ≠ pause watchdog
```

Before attempt timeout, watchdog **MUST** trigger **`RECOVERY_FINAL_EVALUATION`** (`reason=ATTEMPT_TIMEOUT`) — the last evaluation before attempt terminal — then transition to `FAILED_MEDIA_RECOVERY` if still non-success.

During `FAILED_MEDIA_RECOVERY`: ICE `DISCONNECTED`/`FAILED` **MUST NOT** auto-`beginRecovery` (anti attempt-storm). Coordinator-driven material transitions **MAY** invoke re-evaluate.

### R28-G — Capability Re-evaluation Contract

#### Ownership

```text
Materiality detection belongs to TalkbackCoordinator.

Fact writers MUST NOT invoke recovery evaluation directly.
```

Coordinator assembles `EdgeReachabilitySnapshot`, projects **`RecoveryCapabilitySignature`**, compares against per-edge last signature, and notifies the controller **only on material change**.

#### Recovery Capability Signature

A projection of `EdgeReachabilitySnapshot` capturing the **set of recovery actions currently permitted** — not raw connectivity booleans.

```kotlin
RecoveryCapabilitySignature(
    permittedActions: Set<RecoveryAction>,   // e.g. DISPATCH_REATTACH, COMPLETE_EDGE, …
    waitingReason: WaitingReason?           // current blocker for evaluation
)
```

**Material transition** ⇔ `permittedActions` or `waitingReason` changes.

`permittedActions` / `waitingReason` are **recovery-domain** projections. **`authorityReachable=true` does not imply `COMPLETE_EDGE ∈ permittedActions`** (e.g. `WAITING_FOR_INBOUND` while route and authority facts are true).

**Examples:**

| Scenario | Before | After | Material? |
|----------|--------|-------|-----------|
| Participant, route blocked | `{}`, `WAITING_FOR_ROUTE` | `{DISPATCH_REATTACH}`, `null` | ✅ |
| HELLO seq+1, peer already discovered | unchanged | unchanged | ❌ |
| Authority fact enables completion | `{DISPATCH_REATTACH}`, `WAITING_FOR_AUTHORITY` | `{DISPATCH_REATTACH, COMPLETE_EDGE}`, `null` | ✅ |
| Host, `WAITING_FOR_INBOUND`, route only restores | `{…}`, `WAITING_FOR_INBOUND` | unchanged | ❌ |

For **non-initiator edges**: route restoration alone **does not necessarily** constitute a material transition — only signature change counts.

#### Coordinator hooks (v1)

| Fact change | May change signature |
|-------------|-------------------|
| Mesh ICE state | route / dispatch capability |
| Channel readiness | link capability |
| Peer first callable (`0→1`) | discovery capability |
| **Conference authority reachability fact** flip | completion capability |

**Authority fact source:** domain fact (e.g. `isConferenceAuthorityReachable` / future `ConferenceAuthorityTracker`) — **NOT** `emitConferenceRuntimeProjection` itself. Runtime and Recovery projectors **both consume** the same authority fact; recovery **MUST NOT** read projection output.

**Explicit non-triggers:** per-HELLO refresh when peer already discovered; gossip timestamps; ICE `CHECKING` (v1 route = connected/completed only).

#### Observability (P2-A log contract)

| Marker | Role |
|--------|------|
| `RECOVERY_REEVALUATE` | Capability changed; controller awakened |
| `RECOVERY_FINAL_EVALUATION` | Watchdog expiry; last evaluation before attempt terminal |
| `RECOVERY_DECISION` | Evaluation output (P2-B enriches) |
| `RECOVERY_WAITING` | Explicit wait (protocol state, not debug noise) |

`RECOVERY_REEVALUATE` **SHOULD** log: `session`, `edge`, `attempt`, `trigger`, `capabilityBefore`, `capabilityAfter`, `controlPlaneStarted` — compact capability labels, not raw action-set dumps when avoidable.

**Forbidden in P2-A:** `routeConverged → resend()`; debounce material re-evaluate by default; extend watchdog on `WAITING`.

See `docs/audit/p2a-completion-re-evaluate-seam.md` (Accepted).

### R28-H — Recovery Edge Obligation Lifetime

**Rationale (R29 soak `647484ef`, 2026-07-13):** host M02 ran `FAILED_MEDIA_RECOVERY(M01)` → ~4s cleanup → `AUTHORITY_PRUNE`. Gate was `!isEdgeRecovering()` (attempt-scoped). `edgeObligationOpen()` already returned true for failed residency, but prune never consulted it, and no close/deadline existed — so either "prune immediately after attempt terminal" or (if blindly swapped to `edgeObligationOpen`) "never prune". R28-H freezes the missing middle lifecycle.

**Naming note:** R28-G remains **Capability Re-evaluation Contract**. This section is **R28-H**.

#### Two independent lifecycles

```text
RecoveryAttempt              — one recovery try (phase machine already in code)
        │
        ▼
RecoveryEdgeObligation       — whether Controller still owes completion (THIS section)
        │
        ▼
Membership Mutation (R29)    — who may prune / leave (ADR-0023; when = ADR-0024 R29-E)
```

| Lifecycle | Answers | Terminal meaning |
|-----------|---------|------------------|
| **RecoveryAttempt** | Did this try end? | End of attempt #N only |
| **RecoveryEdgeObligation** | Does Controller still own completion for this edge? | End of recovery ownership for `(sessionId, remote)` |
| **Membership** | Who may mutate roster? | Separate authority boundary (ADR-0023) |

They **MUST** remain independent. **MUST NOT** implicitly terminate each other except via the explicit close set below.

#### Attempt Terminal (unchanged scope; clarified non-derivations)

Attempt terminal values:

```text
RECOVERED
FAILED_MEDIA_RECOVERY
FAILED_REQUIRES_USER_ACTION
CANCELLED
SUPERSEDED
```

Attempt terminal **only** means: this attempt has ended.

**MUST NOT** derive from attempt terminal alone:

```text
membership mutation
prune eligible
edge obligation CLOSED
```

#### Obligation states

```text
OPEN
CLOSED
```

While **OPEN**, the edge **MAY** host many attempts without closing:

```text
Attempt#3 FAILED     →  obligation stays OPEN
Attempt#4 SUPERSEDED →  obligation stays OPEN
Attempt#5 FAILED     →  obligation stays OPEN
…
```

**There is no "reopen".** Obligation never left OPEN; a material transition starts a **new Attempt**, not a reopen of the obligation.

#### Close Conditions (exclusive set)

`RecoveryEdgeObligation` **MUST** transition to **CLOSED** **only** when one of:

```text
1. RECOVERED                         — completion success
2. membership committed LEFT(remote) — authority-driven membership terminal (R29)
3. conference TERMINATED             — conference lifecycle terminal
4. obligationDeadline exceeded       — hard abandon (anti permanent stuck roster)
```

**MUST NOT** close obligation:

```text
FAILED_MEDIA_RECOVERY
FAILED_REQUIRES_USER_ACTION
SUPERSEDED
CANCELLED   (attempt-scoped cancel ≠ obligation close unless it coincides with 2 or 3)
```

R28-A / R28-F close set is **extended** by condition 4 (`obligationDeadline`). Conditions 1–3 remain.

#### Observation Window (not sleep)

```text
obligationDeadline =
    attemptTerminalAt + observationWindow
```

`attemptTerminalAt` = wall-clock when the **current** attempt entered an attempt-terminal state that leaves obligation OPEN (typically `FAILED_MEDIA_RECOVERY` / `FAILED_REQUIRES_USER_ACTION`). A later SUPERSEDED → new attempt that again fails **resets** `attemptTerminalAt` to that new terminal instant (deadline follows the latest failed residency entry).

Observation Window duty is **not** "wait then prune".

It **accepts Reachability Material Transitions** and feeds re-evaluation (R28-G):

```text
Recovery Re-evaluation Triggers (examples):
  HELLO (when it changes capability signature)
  routeConverged flip
  authorityReachable flip
  RecoveryCapabilitySignature material change

≠ Obligation Close Triggers
```

Re-evaluation **MAY** start Attempt #N+1 while obligation remains **OPEN**.

Only when `now >= obligationDeadlineAt` **and** none of close conditions 1–3 have fired does condition 4 close the obligation.

#### R28-H.1 — Obligation Deadline Ownership

```text
ConferenceEdgeRecoveryController is the single writer of:

    obligationOpenedAt
    obligationDeadlineAt
    obligationClosedAt
    obligationCloseReason   // RECOVERED | MEMBERSHIP_LEFT | CONFERENCE_TERMINATED | OBLIGATION_DEADLINE
    hasPendingCompletionDecision
```

Membership / projector / prune / `cleanupUnhealthyConferenceSession` **MUST** consume these timestamps, close reason, and pending-decision flag **read-only**.

**Forbidden:** recomputing `obligationDeadline` (or equivalent grace) in coordinator prune paths, presence projectors, or mesh health cleanup. Dual writers recreate the soak failure class (`FAILED` → local cleanup clock → premature `AUTHORITY_PRUNE`).

**Forbidden:** deriving `hasPendingCompletionDecision` from HELLO silence, ICE CLOSED, or `route=false` outside the controller.

Controller **MUST** set `obligationDeadlineAt` when an attempt enters a failed-media residency that leaves obligation OPEN (`attemptTerminalAt + observationWindow`). Subsequent failed residency after SUPERSEDE **MAY** refresh `obligationDeadlineAt` (follows latest failed entry). Closing **MUST** stamp `obligationClosedAt` + `obligationCloseReason` exactly once; CLOSED **MUST NOT** reopen.

`hasPendingCompletionDecision` **MUST** be true while a completion evaluation / re-evaluate / supersede decision for that edge is in flight, and false only when the controller has emitted a settled completion decision (or the edge has no active evaluation). Membership **MUST NOT** invent this flag.

#### R28-H.2 — Debounce Suspicion Clear on ICE Reconnect

`DISCONNECTED_DEBOUNCING` is a **suspicion buffer**, not an attempt and not recovery ownership.

If ICE reconnects while the edge is still in `DISCONNECTED_DEBOUNCING`:

```text
MUST cancel debounce timer
MUST clear debouncing state → CONNECTED (HEALTHY)
MUST NOT start a recovery attempt
MUST NOT emit REATTACH
MUST NOT model the transition as RECOVERED
```

```text
DISCONNECTED_DEBOUNCING + ICE CONNECTED  →  HEALTHY
(not DISCONNECTED_DEBOUNCING → RECOVERED)
```

**Rationale:** leaving the debounce timer armed after media is already CONNECTED produces false `beginRecovery` / `REATTACH` and sticky `edgeRecovering` while topology is healthy — conflating suspicion with obligation/attempt lifecycles.

#### R28-I — WAITING Ownership

**Rationale (soak `ea6466f1`, 2026-07-14):** participant M03 observed M02 `ICE_RESTORED` + `mediaRestored=true` while `controlPlaneStarted=false`. Controller logged `decision=WAITING rejectReason=control_plane_not_started` and **returned with no next-action owner** — obligation stayed OPEN, presence stuck (`recoveringPeers` / `mediaUnavailablePeers`), until watchdog timeout or `OBLIGATION_DEADLINE`. WAITING was treated as a terminal parking lot, not a owned intermediate state.

A recovery attempt **MAY** enter a **WAITING** state only if an **explicit next-action owner** exists.

**Valid owners:**

```text
inbound control-plane message
route convergence callback
watchdog timeout
recovery reevaluation
```

A recovery attempt **MUST NOT** remain in WAITING without an owner capable of advancing or terminating the obligation.

**Normative log markers (implementation):**

```text
RECOVERY_CONTROL_PLANE_REQUIRED   — media restored; control-plane continuation scheduled
RECOVERY_CONTROL_PLANE_BOUNDARY   — cross control-plane without transport flap (ICE_RESTART_ONLY + ICE CONNECTED)
decision=WAIT_FOR_CONTROL_PLANE   — owned wait; watchdog / reevaluate owns exit
```

**Forbidden:**

```kotlin
onLog("decision=WAITING rejectReason=xxx_not_started")
return   // no owner scheduled
```

Review question for any new WAITING: **who is responsible for pulling this attempt out of WAITING?**

恢复 attempt 可以进入 WAITING，但必须显式声明下一步动作的 owner。

合法 owner：

- 入站 control-plane
- route 收敛回调
- watchdog 超时
- recovery reevaluate

禁止出现没有 owner 的 WAITING。

#### API contract (normative direction)

Prune / membership eligibility consumers **MUST** consult obligation, not attempt phase helpers:

```kotlin
fun edgeObligationOpen(sessionId, remote): Boolean
fun edgeObligationClosed(sessionId, remote): Boolean
fun obligationDeadlineAt(sessionId, remote): Long?
fun obligationCloseReason(sessionId, remote): ObligationCloseReason?
fun hasPendingCompletionDecision(sessionId, remote): Boolean
```

**Non-normative for prune:**

```kotlin
isEdgeRecovering()          // Attempt phase only
isFailedMediaRecovery()     // Attempt residency marker only
isActivelyRecovering()      // Attempt phase only
```

Existing `EdgeRecoveryRecord.edgeObligationOpen()` (phase actively recovering **or** failed-media residency) is a **partial** open predicate for P2-A. R28-H requires it to become a true lifetime API: OPEN until exclusive close set; expose CLOSED; honor `obligationDeadlineAt` owned solely by the controller.

#### Boundary with Membership (ADR-0024 R29-E)

```text
ADR-0022 R28-H  →  when Recovery truly ends (obligation CLOSED)
ADR-0024 R29-E  →  after that, when Membership MAY mutate (prune eligibility)
```

R28-H **MUST NOT** define `canAuthorityPrune`. It only freezes obligation CLOSED as a **necessary** recovery-domain input for that future contract.

#### Soak counterexample this freezes

```text
11:18:01  FAILED_MEDIA_RECOVERY(M01)     — attempt terminal; obligation MUST stay OPEN
11:18:05  AUTHORITY_PRUNE(M01)           — illegal relative to R28-H: obligation not CLOSED
11:18:22  HELLO from M01                 — would have been re-eval trigger inside observation window
```

### R27′-A — Presence Projection Boundary

UI **MUST** consume **`ConferencePresenceProjection`** (and runtime phase from `ConferenceRuntimeProjector` where needed).

**`ReachabilitySnapshot` is recovery-internal** and **MUST NOT** surface to UI or ViewModel.

**Forbidden in UI/ViewModel:**

```kotlin
if (routeConverged && authorityReachable) { showRecovering() }
```

ViewModel **MUST NOT** reconstruct presence from `memberKeys.size`, ICE, or transport callbacks.

### R27′-B — Presence Projection Ownership

```kotlin
data class ConferencePresenceProjection(
    val joinedCount: Int,
    val connectedCount: Int,
    val recoveringPeers: Set<String>,  // ModuleId
)
```

**Producer:** dedicated **`ConferencePresenceProjector`**, sibling to `ConferenceRuntimeProjector`.

Both projectors consume the same read-only facts:

```text
MembershipRoster
EdgeRecoveryFacts
ConnectedPeers
AuthorityState (as needed for connected semantics)
```

**MUST NOT** extend `ConferenceRuntimeProjector.Output` with presence fields — prevents runtime DTO bloat (`suspectPeers`, `speakerPeers`, etc. belong on presence plane).

`recoveringPeers` **MUST** derive from **`EdgeRecoveryFacts` per `remoteModuleId`**, not ICE state or HELLO alone.

#### Semantic split (frozen)

```text
Reachability  → Recovery domain
Phase         → Runtime domain
Who is in / connected / recovering → Presence domain
```

## Relationship to ADR-0021

| ADR-0021 | ADR-0022 |
|----------|----------|
| R24 completion ownership after `attempt_timeout` | R28-A/B/C formalize edge vs attempt, re-evaluate, explicit decisions |
| R5 recovery ownership during attempt | R28-B separates owner vs initiator vs action authority |
| R16 EdgeRecoveryFacts → RuntimeProjector | R27′ adds PresenceProjector consumer |
| S13-B probe (in flight) | R28-D/D1 replace bool gates; probes remain audit-only |

R24 Strategy A (degraded residency) **remains v1 default**; R28 does not authorize Strategy B handoff.

## Consequences

- **Positive:** Soak failures become classifiable (`WAITING_FOR_ROUTE` vs `WAITING_FOR_AUTHORITY`); UI decouples from recovery internals; RuntimeProjector stops growing presence fields.
- **Positive (R28-H):** Attempt terminal no longer silently collapses into prune eligibility; host can observe HELLO/route inside `observationWindow` without prematurely closing recovery ownership; `obligationDeadline` prevents permanent joined=3 stuck conferences.
- **Negative:** Two projectors to keep in sync on shared facts; ReachabilitySnapshot wiring is new work (#73-B implementation).
- **Negative (R28-H):** Controller must track `attemptTerminalAt` / `obligationDeadline` and expose OPEN/CLOSED APIs; prune gate (ADR-0024) must migrate off `isEdgeRecovering`.
- **Neutral:** S13-B probe markers stay until gate implementation; Meeting pill fix is R27′ (can ship before R28 behavior fix).
- **Neutral (R28-H):** Does not authorize prune; ADR-0024 R29-E remains required before host post-terminal membership mutation changes.

## Implementation notes (non-normative)

1. **P0 docs:** this ADR + audit cross-links (`s13b-recovery-reattach-reachability.md`, `ro-m3-recovery-write-matrix.md`).
2. **P1 R27′ (implemented 2026-07-10):** `ConferencePresenceProjector` + `TalkbackSessionSnapshot.conferencePresenceProjection`; Meeting pill reads `connectedCount` / `recoveringPeers` — not roster size.
3. **P1 R28 reachability (implemented 2026-07-10):** `EdgeReachabilitySnapshot` gates `dispatchRecoveryReattachOutcome`; `DEFERRED` → `RECOVERY_PENDING` + `RECOVERY_WAITING(reason)`; v1 `routeConverged = qosMonitor.isGroupConnected(remoteModuleId)`. Soak G-R28-D PASS (`logs-s13b-reattach-reachability-20260710-161257`): no `RECOVERY_REATTACH_SENT` while `routeConverged=false`.
4. **P2-A re-evaluate seam (frozen 2026-07-10):** R28-E/F/G — Coordinator-owned `RecoveryCapabilitySignature`; `RECOVERY_REEVALUATE` / `RECOVERY_FINAL_EVALUATION`; `FAILED_MEDIA_RECOVERY` record retained; material transition → MUST re-evaluate, MAY SUPERSEDE. See grill: `p2a-completion-re-evaluate-seam.md`.
5. **R28-H obligation lifetime (frozen 2026-07-13):** OPEN/CLOSED exclusive close set + `obligationDeadline`; no reopen; prune consumers must use `edgeObligationClosed()` — implementation pending; `observationWindow` value TBD at impl (soak showed ~4s too short vs ~20s WiFi restore).
6. **P2-B re-evaluate actions:** decision tree for `permittedActions` → dispatch / ICE restart / `WAITING_FOR_INBOUND` / SUPERSEDE — not frozen in P2-A.
7. **P2 cleanup:** retire probe-only bools from decision paths; S13→E matrix update in write matrix.
8. **ADR-0024 R29-E (not this ADR):** host post-terminal prune eligibility after obligation CLOSED.

## Soak gates (future)

| Gate | Pass criterion | Status |
|------|----------------|--------|
| G-R28-D | WiFi loss: `RECOVERY_WAITING` / `RECOVERY_REATTACH_DEFERRED` with `WAITING_FOR_ROUTE` **before** any `RECOVERY_REATTACH_SENT` when `!routeConverged` | **PASS** `logs-s13b-…-161257` |
| G-R27′ | Meeting pill shows `joinedCount` / `connectedCount` / per-peer recovering consistent with host logs | PASS (prior soak) |
| G-R28-C | No interval where edge is non-terminal and no completion decision for > debounce | **PASS via G-P2-A1/A2** (continuation liveness; was FAIL → P2-A) |
| G-P2-A1 | When `RecoveryCapabilitySignature` changes materially, the recovery controller **MUST** evaluate again within the allowed debounce window. Evidence: `RECOVERY_REEVALUATE` **or** `RECOVERY_DECISION` **or** `RECOVERY_WAITING`. (Continuation liveness only — does **not** require `REATTACH_SENT` / `RECOVERED`.) | **PASS** UT `failedMediaRecovery_materialTransition_emitsReevaluate` + `deferredReattach_iceConnected_blocked_emitsReevaluateOnCapabilityChange` + IT `conferenceR28H2_materialReevalKeepsObligationOpenWithoutPrune`. Material change wakes evaluation inside debounce; may still end WAITING / SUPERSEDE / no dispatch |
| G-P2-A2 | No material capability transition may remain unevaluated until attempt timeout or obligation deadline | **PASS** same suite + UT `capability_participant_routeBlocked_thenConverged_isMaterial` (signature materiality) + H2 IT: route restore after `FAILED_MEDIA_RECOVERY` emits `RECOVERY_REEVALUATE` **before** deadline/timeout silence. Proves continuation seam, not recovery success |
| G-P2-A3 | May still have no `RECOVERY_REATTACH_SENT` (actions = P2-B) | Pending |
| G-S13-E | `RECOVERY_EDGE_RECOVERED` or explicit protocol terminal after WiFi restore | Pending → P2-B |
| G-R28-H1 | After `FAILED_MEDIA_RECOVERY`: obligation stays OPEN; no `AUTHORITY_PRUNE` until CLOSED | **PASS** UT `obligationFacts_stayOpenAfterFailedMediaRecovery` + IT `conferenceR29E_hostMayAuthorityPruneAfterObligationDeadline` (pre-deadline: no prune). Evidence: `ConferenceEdgeRecoveryController` is the single writer of obligation lifecycle (`openedAt` / `deadlineAt` / `closedAt` / `closeReason`); cleanup and prune paths consume facts only |
| G-R28-H2 | Material transition inside observation window → `RECOVERY_REEVALUATE` / new attempt; obligation still OPEN | **PASS** UT `failedMediaRecovery_materialTransition_emitsReevaluate` + IT `conferenceR28H2_materialReevalKeepsObligationOpenWithoutPrune` (also covers G-R29-E2 no prune) |
| G-R28-H3 | Permanent offline past `obligationDeadline` → obligation CLOSED (enables later R29-E prune) | **PASS** UT `obligationDeadline_pastWindow_closesWithObligationDeadline` + IT `conferenceR29E_hostMayAuthorityPruneAfterObligationDeadline`. Evidence: `FAILED_MEDIA_RECOVERY` keeps obligation OPEN until `obligationDeadline`; deadline expiration closes with `closeReason=OBLIGATION_DEADLINE` and unlocks R29-E prune eligibility |

## Conference transmit barrier scope — closed by ADR-0026 (2026-07-14)

**Open question:** Should conference recovery block unrelated participant transmit?

**Resolution:** **Closed by ADR-0026.** Conference transmit barriers are **edge-scoped**. Remote edge recovery / obligation OPEN MUST NOT block local capture when another publish path remains healthy. See `docs/adr/0026-conference-media-transmit-barrier-scope.md`.

**Device evidence:** Soak3 session `df7a5404` (2026-07-14) — M02 WiFi loss; M01↔M03 audio continued; no `CONFERENCE_WIDE` `stop_capture` on healthy peers.

**Observability:** `CONFERENCE_BARRIER_SNAPSHOT` logs `policy=EDGE_SCOPED`, `canPublish`, peer recovery telemetry (`recovering`, `obligationOpen`, `failed`) — peer fields are diagnostic only.

## References

- ADR-0020 — Conference Runtime Projection Contract
- ADR-0021 — Conference Edge Recovery Lifecycle (R24–R26)
- ADR-0023 — Conference Membership Mutation Authority Boundary (R29)
- ADR-0024 — Host Post-Terminal Prune Eligibility (R29-E)
- `docs/audit/p2a-completion-re-evaluate-seam.md`
- `docs/audit/s13b-recovery-reattach-reachability.md`
- `docs/audit/ro-m3-recovery-write-matrix.md`
- Issue #73-B Recovery Reattach Reachability
- R29 soak `logs-r29-soak-20260713-112015` (session `647484ef`)
