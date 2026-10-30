# ADR-0057 GROUP Bootstrap Convergence — Implementation Seam Mapping

## Status

```text
Document:               DESIGN ONLY
Parent contract:        ADR-0057 ACCEPTED — CONTRACT ONLY
Implementation:         NOT AUTHORIZED
Field validation:       NOT AUTHORIZED
Conference / ADR-0058:  OUT OF SCOPE
ADR-0056:               UNCHANGED
```

This document maps GBC-1…10 onto the current codebase. It does not select a wire
encoding, timeout, retry budget, storage technology, or cryptographic profile.
It does not authorize code.

---

## 1. Current finding

The current code has useful local pieces, but no Module owns the complete
cross-node generation contract.

```text
GroupChannelAuthority
  owns local origin-side authority validity (ADR-0053)

GroupBootstrapAdmissionSupport
  owns a peer admission intent keyed by channel + peer

GroupLatePeerAdmissionSupport
  selects a late peer as an admission candidate

TalkbackCoordinator
  owns periodic reconcile, session creation, invite emission and callbacks

GroupSessionPayload / sessionId / sessionTraceId / sessionLineageId
  carry session, media or diagnostic identity
```

None of those is an Authoritative Generation Fact or a verified generation
lineage. Consequently the current flow cannot implement GBC-1…10 by adding one
more retry or by treating `sessionId` as generation truth.

The missing ownership is one deep Module at the seam between authority facts
and session/media execution.

---

## 2. Proposed deep Module

Working design name:

```text
GroupBootstrapConvergence
```

Its interface should remain small:

```text
handle(event) -> effects
snapshot(channelId) -> convergence projection
provideFact(channelId, factIdentity) -> verified fact or absent
```

The Module owns all generation knowledge, conflict evidence, Bootstrap Intent
identity, generation-scoped Admission Binding, fencing and non-resurrection.
Callers must not inspect or mutate its internal maps.

The Module does **not** own:

```text
Primary election
ADR-0053 authority invalidation
UDP / signaling transport
GROUP session construction
WebRTC / ICE / media teardown
Membership readiness or UI readiness
Conference state
```

Deletion test: without this Module, generation comparison, binding fencing,
conflict handling and delayed-result rejection would reappear in the
Coordinator, late-peer path, invite path and signaling ingress. The Module
therefore earns the seam.

---

## 3. Inputs and effects

### Inputs

The semantic event set should be closed around contract facts rather than
scheduler callbacks:

```text
BootstrapRequested(channel, peer, intentCause)
AuthorityObserved(channel, ADR-0053 snapshot)
AuthorityInvalidationCommitted(channel, evidence)
VerifiedFactReceived(channel, fact)
FactUnavailable(channel, requestIdentity, reason)
PrerequisiteChanged(channel, peer, prerequisiteSnapshot)
AdmissionCompleted(channel, bindingIdentity, result)
AttemptTerminal(channel, bindingIdentity, reason)
LocalSessionObserved(channel, localGenerationIdentity?)
```

Periodic reconcile may submit observations or requests. A tick is not a state
advance and is never represented as success.

### Effects

The Module returns explicit effects; an execution adapter performs them:

```text
OriginateGenerationFact
PublishVerifiedFact
RequestFact
CreateAdmissionBinding
StartAdmission
FenceAdmissionBinding
QuarantineGenerationExecution
AlignLocalSession
RecordAttemptTerminal
NoEffect(reason)
```

`StartAdmission` must carry both Bootstrap Intent identity and Admission Binding
identity. Every asynchronous result returns those identities. A result from a
fenced binding is rejected before it can touch session or media execution.

`QuarantineGenerationExecution` is asynchronous execution intent. It must not
perform synchronous WebRTC teardown inside the convergence transition.

---

## 4. Internal state owned by the Module

Per channel:

```text
Fact store
  verified facts by stable identity/digest
  verified predecessor links
  accepted resolution facts

Generation knowledge
  UNKNOWN | CURRENT | SUPERSEDED | CONFLICT
  current Fact identity when knowable
  auditable conflict set / insufficient-order evidence

Bootstrap intents
  stable intent identity
  target peer and cause
  ACTIVE | CONFLICT_BLOCKED | DORMANT | INTENT_TERMINAL

Admission bindings
  unique binding identity
  bound generation identity
  PENDING | EMITTED | COMPLETED | FENCED | ATTEMPT_TERMINAL

Non-resurrection fences
  superseded / invalidated generation identities
  fenced binding identities
  consumed delayed-result identities
```

`sessionId`, `sessionTraceId`, `sessionLineageId`, roster epoch and ICE state may
be attached as observations. They must not be keys in generation ordering.

---

## 5. Existing code ownership after the seam

### `GroupChannelAuthority`

Keep as the ADR-0053 origin-side authority Module. It supplies authority and
invalidation results to `GroupBootstrapConvergence`.

It must not grow follower Fact alignment, Fact lineage, conflict resolution or
Admission Binding state. Election result remains evidence of origin eligibility,
not generation truth.

### `GroupBootstrapAdmissionSupport` and `BootstrapAdmissionIntent`

Current strengths:

- prerequisite delay preserves an unresolved intent;
- edge-ready reevaluation is isolated and testable;
- invite-sent is distinguished from accepted.

Current contract gaps:

- key is only `channelId + targetModuleId`;
- no stable intent identity;
- `sessionId` is the only execution binding;
- no generation identity or binding identity;
- no conflict-blocked or dormant state;
- no atomic old-binding fence + new-binding install;
- `FAILED` / `EXPIRED` do not express GBC-10 intent survival;
- delayed callbacks cannot prove which binding they complete.

The prerequisite evaluation may remain an internal policy. Intent and binding
ownership should move behind the convergence interface rather than creating a
second public state machine.

### `GroupLatePeerAdmissionSupport`

Keep discovery as a candidate producer only. A HELLO may produce
`BootstrapRequested`; it must not directly select a generation or admit media.

`HELLO_CHANNEL_ABSENT` remains insufficient context, not a permanent admission
failure. Once a verified Current Fact supplies channel/generation context, the
same retained intent can receive a new binding.

### `TalkbackCoordinator`

Current `reconcileGroupBootstrap` mixes observation, authority choice, retry
telemetry, session creation and media invitation. It should become an adapter:

```text
collect observations
→ GroupBootstrapConvergence.handle(...)
→ execute returned effects asynchronously
→ return binding-tagged results
```

Required removals from Coordinator ownership at implementation time:

- deciding SAME/SUPERSEDED from local session presence;
- treating `waitingForPrimary` ticks as bootstrap progress;
- creating or completing admission without a generation-scoped binding;
- allowing late invite/session callbacks to mutate a newer binding;
- resolving competing facts by arrival order.

The Coordinator remains the execution orchestrator. It does not become the
generation state machine.

### `GroupSessionPayload`

Do not turn `GROUP_INVITE` SDP into the only Generation Fact carrier. GBC-8
requires a Current Fact to remain requestable/re-providable independently of
media invite success.

A later wire-profile design must provide a first-class control fact or an
equivalent independently retrievable envelope. A media invite may reference the
Fact and binding identities; it must not originate truth merely because SDP was
sent.

### Observability

Keep `GroupTransitionReadinessLog` and `GroupSessionCommitTrace` observational.
Add contract telemetry only at state transitions, not scheduler ticks:

```text
FACT_ACCEPTED / FACT_REJECTED
GENERATION_RELATION_CHANGED
CONFLICT_ENTERED / RESOLUTION_ACCEPTED
INTENT_CREATED / INTENT_DORMANT / INTENT_TERMINAL
BINDING_CREATED / BINDING_FENCED / LATE_RESULT_REJECTED
ATTEMPT_TERMINAL
```

Logs must not be read back as protocol state.

---

## 6. GBC invariant-to-mechanism mapping

```text
GBC-1  Fact lineage + origin-authority proof; never sessionId ordering
GBC-2  verified Fact store + SAME/SUPERSEDED/UNKNOWN comparison
GBC-3  stale execution quarantine + explicit realignment effect
GBC-4  stable Bootstrap Intent independent of emission prerequisites
GBC-5  first-class pending Binding with explicit attempt terminal
GBC-6  monotonic reducer + binding fence on every generation change
GBC-7  one generation identity referenced across invite/admission/session stages
GBC-8  retained verified Fact store + request/provide transport port
GBC-9  generation and binding fences checked before every delayed effect
GBC-10 attempt terminal fences Binding; Intent becomes dormant, not destroyed
```

No mechanism may use retry count, wall clock, ICE, roster, election result,
session trace or arrival order as a substitute for verified lineage.

---

## 7. Real seams and adapters

Two adapters are justified at each external seam:

```text
Fact transport seam
  production: GROUP control signaling adapter
  test: in-memory reordered/duplicated/lossy adapter

Execution seam
  production: Coordinator session/invite adapter
  test: recording adapter with delayed completion control

Fact verification seam
  production: selected origin-auth/digest profile
  test: deterministic verifier and invalid-fact adapter
```

Clock and ID generation are internal dependencies for deterministic tests. They
must not leak into the external convergence interface as caller-managed state.

---

## 8. Test surface

Tests should cross the `GroupBootstrapConvergence` interface, not its internal
maps.

Minimum contract matrix:

```text
1. Authority originates G1; followers accept the same Current Fact.
2. Follower with no session receives G1 → one pending binding, no origin.
3. G2 successor arrives while G1 binding is pending → G1 fenced, G2 bound.
4. Delayed G1 completion after G2 → rejected, no session/media mutation.
5. Edge prerequisite absent then present → same intent, one binding effect.
6. UNKNOWN ticks → no progress; Fact arrival or attempt terminal is required.
7. Same-parent G2a/G2b → conflict, both execution candidates quarantined.
8. Valid Resolution covering all known claims → atomic fence + one new binding.
9. Resolution missing a known branch → conflict retained.
10. Late uncovered branch after Resolution → conflict re-entered, intent retained.
11. Attempt terminal → binding fenced, intent dormant; retry alone does nothing.
12. New legal Fact reactivates dormant intent with a new binding identity.
13. Holder re-provides an accepted Fact after Origin becomes unreachable.
14. Invalid/unverifiable Fact → UNKNOWN; never forwarded as authoritative.
15. Route/ICE changes → no generation origin or ordering change.
```

Coordinator integration tests should verify only adapter behavior:

- execution effects are asynchronous;
- every invite/session completion returns its binding identity;
- fenced results never reach session/media mutation;
- late HELLO creates or reuses intent without inventing generation truth;
- existing GROUP readiness projection remains downstream and unchanged.

---

## 9. Design sequence after explicit authorization

Informative only:

```text
1. Freeze Fact / identity / verification wire profile.
2. Add interface-level contract tests with in-memory adapters.
3. Introduce convergence Module without enabling production effects.
4. Route origin and Fact availability through the Module.
5. Add generation-scoped Admission Binding and delayed-result fences.
6. Route stale and late-peer paths through retained Bootstrap Intent.
7. Add conflict / Resolution behavior.
8. Cut over periodic reconcile from retry-owner to observation adapter.
9. Run 3-node deterministic fault matrix, then 4/6/8/10 field gates.
```

Do not start at step 8 by changing retry loops. Without Facts and fences, that
would only move the same convergence gap.

---

## 10. Authorization boundary

This mapping is sufficient to locate the seam, but not to begin code.

Still required:

```text
ADR-0057 implementation profile / wire mechanism review
explicit implementation authorization
explicit field authorization after deterministic tests
```

This document does not modify ADR-0057, ADR-0058, ADR-0059, GROUP media topology,
Conference topology, or ADR-0056 retention.
