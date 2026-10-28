# ADR-0061: Conference Create Continuation Across Media Release

## Status

**ACCEPTED — OPTION C / FIELD PROVEN / CLOSED FOR THIS GAP** (2026-09-08)

```text
ADR-0061                    FIELD PROVEN / CLOSED FOR THIS GAP
Meeting release/quiescence  CLOSED
RCA (release gap)           COMPLETE
Implementation design       SIGNED OFF
ADR-0061 v1 implementation  VERIFIED (T1–T5 unit; J0 operational chain PASS)

CREATION peer E2E           BLOCKED BY SESSION-BRIDGE GAP (see deferred-session-bridge RCA)
J0 operational chain        PASS
J0 end-to-end               NOT PASS
ADR-0061 code               FREEZE
CREATION replay code        FREEZE
MEMBERSHIP                  HOLD
Phase A                     BLOCKED
```

**Session-bridge RCA (separate gap):** `docs/analysis/meeting-profile01-deferred-session-bridge-rca.md`  
**Pre-bind retention design slice (authorized, code not):** `docs/analysis/meeting-profile01-pre-bind-fact-retention-drain-design-slice.md`

**Implementation design slice (authorized next, code not):**  
`docs/analysis/adr0061-create-continuation-implementation-design-slice.md`

**Parents / boundaries:**

- [ADR-0017](./0017-transition-declaration-and-media-lifecycle.md) — Coordinator consumes invite dispatch outcome; **does not** retry sends. This ADR **does not** reopen that clause.
- [ADR-0018](./0018-conference-media-lifecycle-ownership.md) — media lifecycle ownership.
- `adr0057-group-invite-execution-narrow-authorization.md` — `GROUP + releaseInFlight` post-close drain. **Preserved unchanged.**
- `m03-host-pc-generation-ownership-lineage-rca-20260824.md` — CONFERENCE hangup stacking rationale (gen 71/72/73 churn). **Preserved unchanged.**
- RCA: `docs/analysis/meeting-release-quiescence-gap-rca.md` — evidence basis.

**Does NOT authorize:**

```text
CREATION per-peer replay changes
MEMBERSHIP bridge / Phase A
signaling transport / Profile01 contract changes
coordinator-side timer/retry state machine
enlarging or altering hangup stacking semantics
```

---

## Context

Field J0 runs with operational gates O0 (no sticky conference residue) and O0b (media release quiescent before CREATE) **both PASS** still fail O1 (`invites sent > 0 / pending=1`).

Confirmed chain:

```text
GROUP mesh engine present for M02 at CREATE
→ mediaSessionClose(M02) scheduled
→ CREATE / apply_group_topology / sendConferenceInvites
→ requestConferenceEngine(M02, CONFERENCE)
→ releaseInFlight → queue CONFERENCE:releaseInFlight (MEDIA_RELEASE_DEFERRED_CREATE)
→ dispatch returns invites sent=0
→ mediaSessionClose completes (~123ms)
→ scheduleGroupReleaseInFlightPendingDrainAfterClose (GROUP only)
→ CONFERENCE pending orphaned; onReady never invoked
→ no GROUP_INVITE, no pending=1, no peer Join UI
```

Evidence fingerprints:

| RunId | Pre-CREATE `mediaSessionClose` | Drain item | Wire | Verdict |
|-------|-------------------------------|------------|------|---------|
| `…-20260907-175549` | absent | `CONFERENCE:requestEngine` | `GROUP_INVITE` + `pending=1` | J0_PASS |
| `…-20260908-101856` | present | **none** | none | ENV_INVALID / O1 FAIL |

Both runs log `MEDIA_RELEASE_DEFERRED_CREATE` and both log an initial `Conference invites sent=0`. The divergence is **whether a drain follows**.

### The failing assumption

```text
REJECTED:
  "deferral cause (releaseInFlight / requestEngine / mediaSessionReuse)
   is sufficient to decide whether a pending engine request must be resumed."
```

`pendingEngineRequests` is classified by **why it was deferred**. Drain policy discriminates on that same axis. But "why deferred" does not tell you **whether an obligation is still owed**, nor **whether any future event can ever drain it**.

### Sharper root-cause framing

`MediaSessionManager.requestEngine(moduleId, scope, sessionId, onReady, onFailed)` is a **promise**: the caller hands over a continuation and expects exactly one terminal outcome.

Today there are three outcomes, not two:

```text
onReady    invoked   → provisioned
onFailed   invoked   → explicit failure
neither    invoked   → SILENT ABANDONMENT   ← current CONFERENCE-after-close path
```

Hangup stacking is **not** abandonment — stacked CONFERENCE pending is drained by the *next* `mediaSessionProvision`. It is deferral with a live drain source.

CREATE-time deferral **is** abandonment: after `mediaSessionClose` completes, nothing else will request that engine, because the only requester was the invite dispatch whose continuation is the abandoned closure.

**Root cause is a broken completion promise, not a missing retry.**

---

## Problem statement

```text
CONFERENCE CREATE
→ requestEngine deferred by releaseInFlight
→ mediaSessionClose completes
→ who is responsible for re-driving this CREATE?
```

This ADR adjudicates **ownership only**. Implementation follows a separate authorization.

---

## Options

### Option A — MSM drains all CONFERENCE pending on close complete

Remove the GROUP-only filter in `scheduleGroupReleaseInFlightPendingDrainAfterClose`.

| | |
|--|--|
| Pro | Smallest diff; single drain path |
| Con | **Directly reopens m03 hangup stacking.** Reintroduces multi-generation provision churn during hangup (m03 RCA documented gen 71/72/73 from a single drain). Re-provisions engines nobody wants during teardown. |
| Verdict | **REJECTED** — overturns a deliberate constraint to fix an unrelated case |

### Option B — Coordinator re-drives invite on release complete

Coordinator subscribes to `MEDIA_RELEASE_COMPLETE` and re-invokes `sendConferenceInvitesInternal`.

| | |
|--|--|
| Pro | Zero MSM policy change; zero m03 risk |
| Con | Creates a **second lifecycle authority**. Contradicts ADR-0017 (`Coordinator does not retry sends`). Requires coordinator to *re-derive* whether the invite is still owed (roster/declaration/gate re-evaluation), which is where duplicate-invite and glare bugs are born. Original continuation identity is lost. |
| Verdict | **REJECTED as primary** — correct layer for *intent lifetime*, wrong layer for *continuation resumption* |

### Option C — MSM resumes only pending explicitly marked as CREATE continuation

Classify pending requests by **intent**, not only by deferral cause:

```text
CONFERENCE:releaseInFlight
    ├─ CREATE_CONTINUATION   → drain after mediaSessionClose completes
    └─ HANGUP_STACKING/...   → ADR-0057 semantics unchanged; no drain
```

| | |
|--|--|
| Pro | Preserves m03 + ADR-0057 exactly. Keeps the original `onReady` closure, so resumption is naturally exactly-once and needs no re-derivation. Honors the promise at the layer that made it. |
| Con | Requires an explicit intent parameter on `requestEngine` — a real, if small, API seam. Classification must be supplied by the caller, not guessed by MSM. |
| Verdict | **ACCEPTED** |

---

## Decision

**Option C.** Frozen ownership and obligation classification:

```text
ADR-0061 Decision: ACCEPTED — Option C

MediaSessionManager
= continuation completion owner

TalkbackCoordinator
= intent lifetime + admission owner

CREATE_CONTINUATION
= explicit obligation identity
= eligible for post-close continuation drain

HANGUP_STACKING
= existing semantics preserved
= NOT drained by close completion
```

Continuation ownership stays with the layer that accepted the continuation.

MSM resuming a stored `onReady` is **not** MSM implementing retry policy — it is MSM discharging a promise it already made. Coordinator retains ADR-0017 semantics: it never schedules a resend, never holds retry state, never times anything.

The caller declares intent at enqueue time. MSM does not infer it.

### Core contract (I5)

Every accepted `requestEngine(...)` continuation MUST satisfy:

```text
ready / failed / live-pending
三者必居其一
```

There is no fourth state: no terminal outcome and no reachable drain source. Silent abandonment is a contract violation.

### Implementation v1 boundaries (frozen)

```text
EngineRequestIntent.CREATE_CONTINUATION
  ONLY: beginConferenceInviteWhenEngineReady → requestConferenceEngine
  NOT:  other CONFERENCE request sites; IGIE/GIE unchanged

scheduleCreateContinuationDrainAfterClose order (fixed):
  release complete
  → find CREATE_CONTINUATION pending
  → fence (sessionId / moduleId / roster)
  → consume pending entry
  → RESUMED or DISCARDED
  → exactly one terminal callback
```

Consume pending **before** `onReady` to preserve T5 exactly-once under re-entrancy.

### IGIE / inbound accept (decided)

- **Static audit only** in implementation design slice: confirm whether any path reaches silent abandonment without a live drain source.
- **Do not** expand ADR-0061 v1 implementation scope to IGIE. If audit hits, adjudicate separately whether to reuse the same obligation classification.

---

## Invariants

I5 is the **core contract** of this ADR; I1–I4 and I6 operationalize it.

### I1 — Exactly-once continuation

A given CREATE obligation is resumed **at most once**. Resumption consumes the pending entry.

### I2 — Generation / session fenced

If, at release completion, the originating conference session is superseded, terminated, or the module is no longer an eligible invite target, the continuation is **discarded via `onFailed`** — never silently dropped, never provisioned into a dead session.

### I3 — No Coordinator retry loop

Coordinator MUST NOT poll, schedule timers, or maintain retry state for this path. Its only role is to declare intent at enqueue and to consume the terminal outcome.

### I4 — ADR-0057 / m03 preservation

`GROUP:releaseInFlight` post-close drain behavior is unchanged. CONFERENCE hangup stacking is unchanged. No pending entry not marked `CREATE_CONTINUATION` changes behavior.

### I5 — Promise totality (**core contract**)

`requestEngine` MUST terminate in exactly one of `onReady` / `onFailed`, **or** remain queued with a **live drain source**. Silent abandonment is a contract violation. Any pending entry with no reachable drain event MUST be failed explicitly and observably.

### I6 — Adjudicable observability

The field record MUST distinguish continuation drain from stacking. `MEDIA_RELEASE_DEFERRED_CREATE` + `invites sent=0` are **intermediate** facts only — field gates MUST NOT treat `invites sent=0` alone as failure (PASS run `…-175549` also logs it before drain). Distinct **terminal** facts required: continuation resumed / continuation discarded (fenced).

---

## Exit tests

Both historical fingerprints must be reproducible as fixtures.

### T1 — FAIL fingerprint (`…-101856`) becomes PASS

```text
mediaSessionClose in flight
→ CONFERENCE CREATE requestEngine deferred
→ MEDIA_RELEASE_COMPLETE
→ CREATE_CONTINUATION drain
→ GROUP_INVITE
→ pending=1
```

### T2 — PASS fingerprint (`…-175549`) unchanged

```text
mediaSessionProvision drain path
→ existing single drain
→ exactly one GROUP_INVITE
→ no duplicate invite
```

### T3 — m03 stacking regression guard

CONFERENCE pending queued during hangup (not `CREATE_CONTINUATION`) MUST NOT be drained on close complete. Provision generation count must match the pre-change baseline.

### T4 — Fence

Session superseded/terminated before release completion → `onFailed`, no provision, no invite.

### T5 — Exactly-once

Repeated release completion / duplicate drain attempts yield exactly one invite.

Existing fixtures that MUST keep passing: `gieEg1`–`gieEg6`, `Adr0057GroupInviteInboundExecutionFixturesTest`, `MediaSessionManagerAsyncReleaseBoundaryTest`, `ConferenceRealizationLineageTest`.

---

## Resolved / deferred

| Item | Disposition |
|------|-------------|
| Obligation identity v1 | **DECIDED:** explicit enum `CREATE_CONTINUATION`; token deferred unless fence insufficient |
| O1 field gate | **DECIDED:** must use I6 terminal facts; `invites sent=0` is not a failure signal |
| IGIE inbound accept | **DECIDED:** static audit in design slice only; no v1 scope expansion |

---

## Architectural note

The narrow reading of this defect is "one missing drain branch." The durable reading is that `pendingEngineRequests` is indexed on **deferral cause** while the drain policy actually needs **obligation identity**. Option C introduces that axis minimally, without reopening any frozen contract. If a second case of the same class appears (see open question 3), the correct follow-up is to generalize obligation classification — not to widen the drain filter again.
