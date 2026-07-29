# ADR-0024: Host Post-Terminal Prune Eligibility (R29-E) (ADR-CONF-006)

## Status

**Accepted** (2026-07-13) — freezes **R29-E v1**.

**Amended** (2026-07-21) — **R29-E v2** boundary freeze. Supersedes v1 linkage `OBLIGATION_DEADLINE → isPruneEligible() → AUTHORITY_PRUNE`. Depends on **ADR-0022 R28-H / R28-H.1 / R28-J** and **ADR-0023 R29**. Does **not** redefine obligation lifetime. Does **not** freeze Membership Eviction mechanism (timer, controller API, cancel evidence) — future amendment only.

## Summary

Authority ownership grants the **right** to prune, **not** the right to prune immediately after `FAILED_MEDIA_RECOVERY`.

**R29-E v2 core:** A recovery terminal event is terminal **only within the Recovery domain**. It is not system-wide terminal truth and does not establish membership removal eligibility.

```text
ADR-0022 R28-H  →  when the current obligation episode ends (episode CLOSED)
ADR-0022 R28-J  →  Edge Lifecycle vs episode scope (`CLOSED(RECOVERED)` ≠ lifecycle end)
ADR-0023 R29    →  who may mutate membership
ADR-0024 R29-E  →  when Membership MAY AUTHORITY_PRUNE (requires independent eviction decision)
```

This ADR answers one question:

```text
When may the conference authority execute AUTHORITY_PRUNE?
```

It does **not** answer what an obligation is, when it closes, or who writes `obligationDeadlineAt` — those are ADR-0022.

### R29-E v2 — superseded v1 linkage (2026-07-21)

**Withdrawn (v1):**

```text
OBLIGATION_DEADLINE
        ↓
isPruneEligible() == true
        ↓
canAuthorityPrune MAY become true
```

**Frozen (v2):**

```text
Recovery terminal (e.g. OBLIGATION_DEADLINE)
        ↓
Recovery fact only
        ↓
(optional) Membership Eviction evaluation
        ↓
explicit authority Membership Eviction decision
        ↓
AUTHORITY_PRUNE transaction (R29-E.3 / R29-A)
```

Key sentence:

```text
OBLIGATION_DEADLINE closes a recovery obligation.
It does not establish membership removal eligibility.
```

M-S1 soak evidence (session `8c187a94`, 2026-07-21): `OBLIGATION_DEADLINE` at `10:38:11` followed by `AUTHORITY_PRUNE` at `10:38:13` while M01 still held `JOINED` + `REATTACH_REQUESTED` — v1 linkage produced split brain. v2 forbids this path.

## Context

### R29 soak evidence (session `647484ef`, M02 host)

| Time | Event | Implication |
|------|-------|-------------|
| `11:18:01` | M02 `FAILED_MEDIA_RECOVERY remote=M01` | attempt terminal; obligation **MUST** stay OPEN (R28-H) |
| `11:18:05` | M02 `AUTHORITY_PRUNE` M01 (`canPrune` via `!isEdgeRecovering`) | prune consulted **attempt** phase, not obligation |
| `11:18:22` | HELLO from M01; host re-invite → BUSY | observation window would have allowed re-eval |

Participant side (R29-A) held: M01/M03 kept `joined=3`. Split was **authority policy missing**, not Single Writer violation. Local-only prune without roster broadcast left participants at `joined=3`.

### Layering already frozen

| Concept | Domain | Terminal by `FAILED_MEDIA_RECOVERY`? |
|---------|--------|--------------------------------------|
| Attempt | Recovery execution | Yes |
| Obligation episode | Recovery ownership (R28-H; per `obligationGeneration`) | **No** — episode stays OPEN until close set |
| Edge Lifecycle | Observer edge record lifetime (R28-J) | **No** — `CLOSED(RECOVERED)` does not remove record |
| Membership | Conference authority | **No** — only `LEFT` / `TERMINATED` / `AUTHORITY_PRUNE` |

## Decision

### INV-MEM-002 — Recovery terminal MUST NOT mutate membership

Recovery-domain terminal events **MUST NOT** synchronously or transitively authorize or execute a Conference Membership Mutation Transaction.

They **MAY** trigger Membership Eviction evaluation, but they are **never** sufficient evidence for eviction.

Membership mutation requires an independently owned and explicit **Membership Eviction decision**.

Examples of recovery-domain events:

```text
ATTEMPT_TIMEOUT
FAILED_MEDIA_RECOVERY
SUPERSEDED
OBLIGATION_DEADLINE
OBLIGATION_CLOSED(RECOVERED)
```

These events close or update recovery state only. They do **not** imply:

```text
MEMBER_LEFT
roster removal
mesh membership removal
authority prune
```

**Forbidden pattern:** Recovery emits a terminal fact → listener / callback / scheduled task → `removeConferenceParticipant` without an explicit Membership Eviction decision owned by the authority membership layer. Recovery **MAY** reach evaluation; Recovery **MUST NOT** reach decision.

Couples ADR-0023 R29-D: membership mutation **MUST NOT** be triggered implicitly by recovery terminal.

### R29-E v2 — Three-domain boundary (not a pipeline)

Recovery, Health, and Membership are **orthogonal dimensions**. They share facts; they do not share terminal authority.

```text
                 Recovery facts
                      |
          +-----------+-----------+
          |                       |
          v                       v
   Health projection       Membership evaluation
   (capability view)       (authority decision)
          |                       |
          |                       v
          |              Membership mutation
          |              (AUTHORITY_PRUNE transaction)
          v
   Presence / degradation UI
```

| Domain | Owner | Responsibility |
|--------|-------|----------------|
| Recovery | Conference Edge Recovery Controller | Obligation episode lifecycle; recovery terminal facts |
| Health | Presence / health projection (ADR-0030) | Interpret capability; surface degradation |
| Membership | Authority membership owner (ADR-0023) | Identity add/remove; `AUTHORITY_PRUNE` |

**Normative:**

```text
DEGRADED is NOT a MembershipState.
Health projection is never a membership authority input by itself.
```

A member **MAY** remain `JOINED` while health projection shows degradation (`RECONNECTING` / conference degraded aggregate per ADR-0025). `OBLIGATION_DEADLINE` closes the obligation episode; it does **not** transition membership to a new FSM state such as `JOINED_DEGRADED`.

**Eviction decision seam (v2 freeze only):** Between recovery terminal and `AUTHORITY_PRUNE` there **MUST** exist an independent Membership Eviction decision owned by the authority membership layer. v2 does **not** define eviction policy, timers, or controller API — only that the seam exists and recovery facts alone cannot cross it.

### R29-E — Host Post-Terminal Prune Contract

#### Core rule

```text
FAILED_MEDIA_RECOVERY
does not immediately make a member prune-eligible.

attempt terminal ≠ membership terminal
attempt terminal ≠ prune eligible
recovery obligation CLOSED ≠ membership removal eligible   (v2)
```

#### Eligibility (normative) — v2

Host **MAY** execute `AUTHORITY_PRUNE(remote)` only when **all** hold:

```text
canAuthorityPrune(session, remote) :=
    isConferenceAuthority(session)
    ∧ remote still JOINED in canonical roster
    ∧ explicitMembershipEvictionDecision(session, remote)   // v2: independent authority decision
    ∧ !hasPendingCompletionDecision(session, remote)
    ∧ <existing negative health guards unchanged>
```

**v2 necessary recovery inputs** (read-only; **not sufficient** for prune):

```text
edgeObligationClosed(session, remote)
obligationCloseReason(session, remote)   // e.g. OBLIGATION_DEADLINE — closes obligation only
```

`OBLIGATION_DEADLINE` **MUST NOT** alone satisfy `canAuthorityPrune`. Implementations **MUST** treat `obligationCloseReason == OBLIGATION_DEADLINE` as recovery-domain terminal only until a future amendment defines `explicitMembershipEvictionDecision`.

**Temporary fail-closed (until eviction policy lands):** `canAuthorityPrune` **MUST** return `false` when the only recovery close reason is `OBLIGATION_DEADLINE` and no explicit Membership Eviction decision exists. Unhealthy members remain `JOINED` with degraded health projection — accepted trade-off over split-brain prune.

**Close reasons and membership (v2):**

```text
OBLIGATION_DEADLINE      → obligation closed; NOT prune-eligible alone (v2)
RECOVERED                → member healthy; prune forbidden
MEMBERSHIP_LEFT          → already left; wrong path
CONFERENCE_TERMINATED    → conference teardown owns cleanup
```

Participant nodes **MUST NOT** call `AUTHORITY_PRUNE` (R29); they keep `RECOVERY_MEDIA_DEGRADED` advisory path.

#### Forbidden gates

```text
!isEdgeRecovering()
isFailedMediaRecovery()
ICE unhealthy + meshNegotiationGraceMs from unhealthySince
attempt_timeout age alone
HELLO silence / route=false / ICE CLOSED inferred locally
obligationCloseReason == OBLIGATION_DEADLINE alone    // v2
```

These are attempt / media-health / local inferences / recovery terminal facts. They **MUST NOT** alone authorize `AUTHORITY_PRUNE`, and **MUST NOT** be used to invent `hasPendingCompletionDecision` or deadlines.

#### Relationship to Observation Window

Observation Window lives in Recovery (R28-H). Membership **does not** sleep.

```text
FAILED_MEDIA_RECOVERY
    → obligation stays OPEN
    → material transitions → REEVALUATE → new Attempt (still OPEN)
    → controller closes obligation (e.g. OBLIGATION_DEADLINE)
    → recovery fact recorded; membership unchanged (v2)
    → (future) Membership Eviction evaluation → explicit decision
    → canAuthorityPrune MAY become true only after explicit decision
```

#### Control-plane delivery boundary (v2 note only)

Recovery completion evidence requires explicit delivery semantics. This ADR does **not** define REATTACH state machines, nonce models, or ACK contracts.

```text
TRANSPORT_SENT ≠ REMOTE_DELIVERED
SENT alone MUST NOT close obligation or imply membership progress
```

Full control-plane delivery contract is out of scope (future ADR or ADR-0022 appendix).

### R29-E v1 — superseded eligibility (archived 2026-07-21)

<details>
<summary>v1 text — withdrawn; retained for audit trail</summary>

Host **MAY** execute `AUTHORITY_PRUNE(remote)` when `obligationCloseReason.isPruneEligible()` (v1: `OBLIGATION_DEADLINE` only) and other gates held.

```text
canAuthorityPrune (v1) :=
    ...
    ∧ obligationCloseReason(session, remote).isPruneEligible()
```

G-R29-E3 v1: deadline → `canAuthorityPrune` MAY become true immediately.

</details>

### R29-E.1 — Recovery owns obligation facts

```text
ConferenceEdgeRecoveryController is the single writer of:

    edgeObligationOpen / edgeObligationClosed
    obligationOpenedAt / obligationDeadlineAt / obligationClosedAt
    obligationCloseReason
    hasPendingCompletionDecision
```

(Couples ADR-0022 R28-H.1; this clause names the Membership consumer contract.)

### R29-E.2 — Membership consumes read-only

Membership / `cleanupUnhealthyConferenceSession` / `canAuthorityPrune` **MUST** consume:

```text
edgeObligationClosed
obligationCloseReason
hasPendingCompletionDecision
```

**read-only.**

**Forbidden:** recomputing deadline, inventing pending-decision, or deriving prune eligibility from HELLO silence / ICE CLOSED / `route=false` inside coordinator cleanup.

### R29-E.3 — Post-prune convergence (MUST)

Authority prune **MUST** execute as one atomic membership transaction (aligns / extends R29-A):

```text
Authority prune transaction:

1. mutate canonical roster
2. mutate memberModules
3. emit GROUP_LEAVE / roster broadcast
4. publish projection update
5. release media bindings   (releaseMeshPeer / floor cleanup / cancelEdge as in R29-A)
```

**Partial execution is forbidden.** `releaseMeshPeer()` (and peer media teardown) **MUST NOT** run outside this transaction. Local roster mutation without broadcast recreates:

```text
host joined=2
participant joined=3
```

even when prune timing is correct. Wire form **MAY** reuse existing leave broadcast; completeness **MUST** hold: non-authority members can converge off the pruned remote.

## Non-goals

- Redefining R28-H close set or observation window semantics
- **v2:** `MembershipEvictionController` API, eviction timers, `evictionGeneration`, cancel-evidence lists, tombstone, roster replay
- **v2:** REATTACH delivery state machine (QUEUED / SENT / RECEIVED / ACCEPTED) — separate control-plane delivery contract
- Presence / host pill media-health UX implementation
- Choosing concrete `observationWindow` duration (Recovery config; soak suggests ≫ 4s)
- Host offline authority gap (`docs/backlog/authority-recovery-gap.md`)

## Consequences

- **Positive:** Host prune no longer races WiFi restore; Recovery and Membership stay decoupled; roster broadcast is part of the prune transaction.
- **Positive (v2):** Recovery terminal no longer acts as implicit membership terminal; three-domain boundary is explicit (INV-MEM-002).
- **Negative:** Unhealthy members remain `JOINED` with degraded health projection after `OBLIGATION_DEADLINE` until explicit Membership Eviction decision — intentional; avoids split-brain prune.
- **Negative (v2 temporary):** Fail-closed on deadline-only prune until eviction policy lands; members may linger in roster longer than v1.
- **Implementation order:**
  1. R28-H API — **landed**
  2. ADR-0024 v2 boundary + fail-closed guard — **in progress**
  3. REATTACH delivery contract (P0) — independent
  4. Membership Eviction policy + controller (P1) — future amendment
  5. P1 supersede / watchdog — **PASS** via #79 / ADR-0023 G-R29-P1

## Soak gates

| Gate | Pass criterion | Status |
|------|----------------|--------|
| G-R29-E1 | After `FAILED_MEDIA_RECOVERY`, no `AUTHORITY_PRUNE` while `edgeObligationOpen` | **PASS** IT `conferenceR29E_hostDoesNotAuthorityPruneWhileObligationOpen` |
| G-R29-E2 | HELLO / route material transition inside window → new attempt / REEVALUATE; still no prune | **PASS** IT `conferenceR28H2_materialReevalKeepsObligationOpenWithoutPrune` |
| G-R29-E3 | Permanent offline → `OBLIGATION_DEADLINE` closes obligation; **no** `AUTHORITY_PRUNE` without explicit Membership Eviction decision (v2) | **PENDING** v2 — supersedes v1 "deadline → MAY prune". IT `conferenceR29E_hostMayAuthorityPruneAfterObligationDeadline` to be split or revised |
| G-R29-E3a | After `OBLIGATION_DEADLINE`: member still `JOINED` on authority roster; health shows degradation; no `AUTHORITY_PRUNE` (v2 fail-closed) | **PENDING** |
| G-R29-E4 | After authority prune commits (with explicit eviction decision): `host.joinedCount == participant.joinedCount` **and** same roster epoch | **PASS** IT `conferenceR29E4_authorityPruneConvergesJoinedCountAndRosterEpoch` — convergence model unchanged; trigger path deferred to future eviction soak |

## References

- ADR-0022 — Recovery Completion Ownership (R28-H / R28-H.1 / R28-J)
- ADR-0023 — Conference Membership Mutation Authority Boundary (R29)
- ADR-0030 — Presence Projection Contract (health projection; not membership authority)
- R29 soak `logs-r29-soak-20260713-112015` (session `647484ef`)
- M-S1 soak `logs/obs-matrix-ms1-issue-20260721-103958` (session `8c187a94`) — v2 motivation
- `docs/audit/ro-m3-recovery-write-matrix.md`
- `docs/backlog/authority-recovery-gap.md` — host offline gap; out of v2 scope
