# ADR-0017: Transition Declaration, Media Lifecycle, and Policy Governance (RO-M1)

## Status

Accepted (2026-07-06) — RO-M1a implemented in #59

## Summary

本设计的核心目标是消除 Runtime Transition 的非确定性来源，通过 Declaration immutability、Policy
single-source governance、以及 Media lifecycle 解耦，将系统行为从「可运行但不可证明」转为
「可证明且可观测」。所有 fallback、隐式推导、运行时修复路径被严格禁止。

[ADR-0016](./0016-transition-completion-contract.md) (TCC) 定义了 `READY` 必须等于 Completion
Predicate satisfied。RO-M1 soak (2026-07-06) 证明控制面已稳定，但 **Predicate 输入源错误**
与 **Media 生命周期未与 Transition 正交建模** 仍导致 M02 Connecting 复发。本 ADR **amends
ADR-0016** § MEETING_START predicate inputs and adds the Media Lifecycle observability layer.

## Context

### What RO-G1 / TCC already solved

- `GATE_DECISION BLOCK` = 0 (admission stable)
- Transition begin/terminal observable
- No silent request drops on control plane

### What remains (converged root cause)

> **PeerConnection lifecycle has no session-bound, transition-scoped semantics across GROUP →
> MEETING switches.**

Soak failures project as four symptoms but share one structural gap:

| Symptom | Log fingerprint |
|---------|-----------------|
| TCC false-green | `host_solo_conference` READY in 4–11ms before invites |
| GROUP→Meeting ICE gap | `remoteTrackAttached ice=CLOSED` at accept |
| GROUP throttle pollution | `GROUP_JOIN ICE restart throttled from M02` |
| SDP reuse error | `setup attribute` on ICE restart loop |

These are **not** fixed by more Gate rules, probes, or timeouts alone.

### Architectural boundary (three layers)

```
Transition Layer   — when is the business establishment/recovery complete?
Media Lifecycle    — how connected is media right now? (observability only)
Gate Layer         — may the next operation start?
```

## Decision

### 1. READY semantics (unchanged from ADR-0016 intent)

`TRANSITION_TERMINAL = READY` **SHALL** mean:

> Completion Predicate for this `TransitionTrigger` is satisfied.

**MUST NOT** downgrade READY to "Control Ready" or split into `FULL_READY`. Participant join
progress belongs to Media Lifecycle, not Transition.

### 2. Declaration Ownership (P0 — single writer)

Every `TransitionTrigger` has exactly **one Declaration Owner**. Only the owner may:

- create declaration
- update declaration (while OPEN only)
- finalize declaration (OPEN → FROZEN)

Coordinator and Gate are **consumers only** — read frozen declaration, evaluate predicate, log.

| TransitionTrigger | Declaration Owner |
|-------------------|-------------------|
| `MEETING_START` | `TalkbackCoordinator` (Runtime core) |
| `MEETING_END` | `TalkbackCoordinator` |
| `GROUP_BOOTSTRAP` | Group runtime owner (future) |
| `IDENTITY_REBOUND` | Membership runtime owner (future) |

App-layer (`TalkbackRuntimeManager`) **MUST NOT** own declaration fields. It invokes Runtime
public APIs that submit intent into the owner's declaration window.

### 3. Declaration Lifecycle — Declaration Window (P0)

Declaration is **Intent**, not Runtime snapshot. It is initialized in a bounded window, then frozen.

```
beginTransition(MEETING_START)
    ↓
Declaration OPEN
    - set MeetingMode
    - set expectedInviteTargets
    - inviteDispatchFinished = false
    ↓
InviteDispatcher executes (may retry per Policy)
    ↓
DispatchCompleted(success | failed)
    ↓
Declaration FROZEN  (all fields immutable)
    ↓
Predicate evaluation (only on FROZEN declaration)
```

**Rules:**

- Predicate **MAY** evaluate during OPEN; it **CANNOT** satisfy until `DeclarationFrozen`.
- After FROZEN, **no field** on declaration may change. Runtime drift (peer left, ICE closed)
  does not rewrite declaration.
- `CompletionPredicate` **SHALL** include `DeclarationFrozen` as a prerequisite.

### 4. MeetingMode and expectedInviteTargets (P0 — explicit intent)

Declaration **MUST** carry explicit `MeetingMode`, not derived from empty targets:

```kotlin
enum class MeetingMode { SOLO_HOST, MULTI_PARTY }

data class MeetingStartDeclaration(
    val mode: MeetingMode,
    val expectedInviteTargets: Set<EndpointId>,
    val inviteDispatchFinished: Boolean,  // true only after FROZEN
)
```

**Invariant (consistency):**

```
mode == SOLO_HOST  ⇔  expectedInviteTargets.isEmpty()
mode == MULTI_PARTY  ⇔  expectedInviteTargets.isNotEmpty()
```

Violation → `FAILED(INVALID_DECLARATION)`. **No auto-correction.**

Runtime emptiness **MUST NOT** imply solo. This is the root fix for false-green.

### 5. MEETING_START Completion Predicate (v2)

Predicate evaluates only when `DeclarationFrozen == true`:

```
PredicateSatisfied =

    DeclarationFrozen

    AND InviteDispatchFinished

    AND HostLinkRequirementSatisfied

    AND ExpectedInviteTargetsResolved
```

Where:

| Sub-predicate | SOLO_HOST | MULTI_PARTY |
|---------------|-----------|-------------|
| `InviteDispatchFinished` | true at freeze (no sends required) | all targets dispatched successfully |
| `HostLinkRequirementSatisfied` | true (no peer link required) | ∃ target ∈ expectedInviteTargets : hostLink(target) == CONNECTED |
| `ExpectedInviteTargetsResolved` | mode/targets consistent | mode/targets consistent |

**ANY_ONE_CONNECTED** for MULTI_PARTY: READY proves meeting is **established**, not that all
participants joined. Remaining joins are Media Lifecycle (`PARTIAL_CONNECTED` → `FULLY_CONNECTED`).

### 6. Terminal state responsibility matrix (P0)

| Scenario | Declaration | Dispatch | Predicate wait | Terminal | Reason |
|----------|-------------|----------|----------------|----------|--------|
| Valid SOLO_HOST | FROZEN | N/A | satisfied at freeze | **READY** | — |
| Dispatch partial failure (MULTI_PARTY) | OPEN/FROZEN | ✗ | — | **FAILED** | `INVITE_DISPATCH_FAILED` |
| Invalid declaration | — | — | — | **FAILED** | `INVALID_DECLARATION` |
| Dispatch OK, ICE never converges | FROZEN | ✓ | timeout | **TIMED_OUT** | convergence |
| Owner cancels | any | any | — | **ABORTED** | owner explicit |
| Policy missing/invalid | — | — | — | **FAILED** | `INVALID_POLICY` |

**MUST NOT** use TIMED_OUT for deterministic dispatch/declaration failures.

### 7. Invite dispatch retry (Policy-owned, Runtime-executed)

- Declaration is immutable; **dispatch may retry** against the same frozen intent while OPEN.
- Retry whitelist, `maxRetry`, `deadline`, backoff **SHALL** live in `TransitionPolicy` only.
- `InviteDispatcher` reads policy and executes; Coordinator **MUST NOT** implement retry logic.
- Coordinator consumes final `DispatchCompleted(success | failed)` only.

Example policy shape:

```text
TransitionPolicy.MEETING_START
    timeout = 12s
    inviteDispatch {
        retryableErrors = { TRANSPORT_NOT_READY, SIGNALING_RECONNECTING, ... }
        nonRetryableErrors = { INVALID_DECLARATION, UNKNOWN_ENDPOINT, SDP_BUILD_FAILED, ... }
        maxRetry = 3
        deadline = 3s
        backoff = EXPONENTIAL
        initialDelayMs = 100
    }
```

### 8. TransitionPolicy — fail-closed, startup fatal (P0)

> TransitionPolicy is the sole authority for transition convergence parameters (timeout, retry,
> backoff). Runtime executes; it does not own policy defaults.

- Policy missing or validation failure at runtime → `FAILED(INVALID_POLICY)`. **No fallback.**
- **Startup:** `PolicyRegistry.validate()` failure is a **startup-time fatal error**. The system
  **MUST NOT** enter operational state without a validated TransitionPolicy snapshot. No runtime
  fallback or degraded execution is allowed.

### 9. Media Lifecycle (observability layer — orthogonal)

Media Lifecycle tracks connectivity phases for UI, metrics, soak, and debug. It **MUST NOT**
drive Transition state:

- Media **MUST NOT** call `completeTransition()`, `abortTransition()`, or `beginTransition()`.

Suggested states:

```
IDLE → BOOTSTRAPPING → NEGOTIATING → PARTIAL_CONNECTED → CONNECTED → DEGRADED → FAILED
```

Allowed combination:

```
Transition READY  +  MediaLifecycle = PARTIAL_CONNECTED
```

UI may show: "Meeting ready — connecting remaining participants…"

### 10. Completion Predicate interface (P1 — uniform shape)

Predicates **SHOULD** implement a common interface to avoid per-trigger ad-hoc evaluators:

```kotlin
interface CompletionPredicate<TDeclaration> {
    fun evaluate(declaration: TDeclaration, runtimeFacts: RuntimeFacts): TransitionPredicateEval
}
```

Implementations: `MeetingStartPredicate`, `MeetingEndPredicate`, (future) `BootstrapPredicate`.

### 11. Invariants

| ID | Invariant |
|----|-----------|
| I1 | Declaration is immutable after FROZEN. |
| I2 | `READY ⇔ Completion Predicate satisfied` (on frozen declaration + runtime facts). |
| I3 | Media Lifecycle never changes Transition state. |
| I4 | `Transition READY` does not imply all peers `MediaLifecycle.CONNECTED`. |
| I5 | Runtime data **MUST NOT** infer business intent (no `targets.isEmpty() ⇒ solo`). |
| I6 | Policy is single source; no runtime fallback constants for convergence behavior. |

### 12. Soak KPI extension

| ID | Rule |
|----|------|
| S6 | No `MEETING_START` READY before `DeclarationFrozen` log |
| S7 | No `host_solo_conference` when `MeetingMode=MULTI_PARTY` in declaration |
| S8 | `MEDIA_SESSION_REUSE = 0` across GROUP→MEETING boundary (future RO-M2) |
| S9 | `INVITE_DISPATCH_FAILED` count tracked; partial dispatch never degrades to solo |

## Soak failure coverage (rules reverse-mapping)

Rules below map known soak failures (logs `logs-rog1-*`, `logs-tcc-*`, 2026-07-06) to ADR-0017
coverage. **Covered** = addressed by this ADR's governance model. **Follow-up** = requires RO-M2
media lifecycle implementation, not ADR alone.

| Failure type | Example | ADR-0017 coverage | Follow-up work |
|--------------|---------|---------------------|----------------|
| TCC false-green (`host_solo` 6ms) | M01 T12 READY before invites | **Covered** — explicit `MeetingMode`, frozen declaration, no empty-target inference | Wire declaration in coordinator + runtime manager |
| Predicate before invite dispatch | READY then `Conference invites sent=2` | **Covered** — `DeclarationFrozen` prerequisite | Integration test prod path |
| GROUP→Meeting ICE CLOSED at accept | `remoteTrackAttached ice=CLOSED` | **Partial** — READY no longer false-green; UI uses MediaLifecycle | RO-M2 MediaSession reset barrier |
| GROUP_JOIN throttle on conference restart | `ICE restart throttled from M02` | **Not covered** — out of scope | RO-M2: conference-scoped ICE path |
| SDP setup attribute error | Coordinator error on answer SDP | **Not covered** — out of scope | RO-M2: per-transition PeerConnection |
| Deferring >10s (S3) | M02 stuck Connecting ~20s | **Partial** — TIMED_OUT semantics clear; MediaLifecycle observable | RO-M2 media reset + soak S3 |
| M02 Connecting UI | Participant `Deferring full conference mesh` | **Partial** — I4 separates READY vs full connect | MediaLifecycle projection to UI |

**Conclusion:** ADR-0017 fully covers **control-plane false completion** (the recurring false-green).
It does **not** alone fix ICE reuse / throttle / SDP — those require RO-M2 MediaSession lifecycle.
Implementing ADR-0017 without RO-M2 will stop declaring success too early but may still show
Connecting until media layer is rebuilt.

## Out of Scope (RO-M1)

RO-M1 does **NOT** solve:

- ICE restart policy (GROUP vs CONFERENCE path separation)
- SDP negotiation / setup attribute generation
- PeerConnection lifecycle reset across GROUP → MEETING
- `MediaSessionManager` implementation
- Full `MEDIA_SESSION_REUSE = 0` enforcement

These are tracked as **RO-M2 Media Lifecycle Spec**.

## Consequences

- `MEETING_START` declaration must be wired before predicate can pass soak S6/S7.
- App layer loses ability to "fix" declaration at runtime; must submit correct intent in window.
- Startup fails hard on bad policy — preferable to unprovable soak results.
- Tests must cover: `conferenceCall(emptyList)` + `sendConferenceInvites`, and
  Meeting→Hangup→Meeting×3 regression.

## Implementation tracking

| Item | Scope |
|------|-------|
| ADR-0017 + CONTEXT terms | This ADR |
| `MeetingStartDeclaration` + window API | Coordinator |
| `MeetingStartPredicate` v2 | governance/transition |
| `TalkbackRuntimeManager` submits intent | talkback-app |
| `TransitionPolicy` inviteDispatch block | governance |
| `PolicyRegistry` startup validation | governance |
| MediaLifecycle enum + logs (read-only) | media runtime |
| Soak S6–S9 grep | scripts |
| RO-M2 MediaSession | follow-up ADR |

## References

- ADR-0015 Runtime Governance
- ADR-0016 Transition Completion Contract (amended)
- Soak logs: `logs-rog1-20260706-141845`, `logs-tcc-20260706-153352`, `logs-tcc-20260706-161922`
- Epic: #50
