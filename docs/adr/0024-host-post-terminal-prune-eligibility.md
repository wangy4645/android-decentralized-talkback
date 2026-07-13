# ADR-0024: Host Post-Terminal Prune Eligibility (R29-E) (ADR-CONF-006)

## Status

**Accepted** (2026-07-13) — freezes **R29-E**. Depends on **ADR-0022 R28-H / R28-H.1** and **ADR-0023 R29**. Does **not** redefine obligation lifetime.

## Summary

Authority ownership grants the **right** to prune, **not** the right to prune immediately after `FAILED_MEDIA_RECOVERY`.

```text
ADR-0022 R28-H  →  when Recovery truly ends (obligation CLOSED)
ADR-0023 R29    →  who may mutate membership
ADR-0024 R29-E  →  after obligation CLOSED, when Membership MAY AUTHORITY_PRUNE
```

This ADR answers one question:

```text
When may the conference authority execute AUTHORITY_PRUNE?
```

It does **not** answer what an obligation is, when it closes, or who writes `obligationDeadlineAt` — those are ADR-0022.

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
| Obligation | Recovery ownership | **No** (R28-H) |
| Membership | Conference authority | **No** — only `LEFT` / `TERMINATED` / `AUTHORITY_PRUNE` |

## Decision

### R29-E — Host Post-Terminal Prune Contract

#### Core rule

```text
FAILED_MEDIA_RECOVERY
does not immediately make a member prune-eligible.

attempt terminal ≠ membership terminal
attempt terminal ≠ prune eligible
```

#### Eligibility (normative)

Host **MAY** execute `AUTHORITY_PRUNE(remote)` only when **all** hold:

```text
canAuthorityPrune(session, remote) :=
    isConferenceAuthority(session)
    ∧ remote still JOINED in canonical roster
    ∧ edgeObligationClosed(session, remote)
    ∧ obligationCloseReason(session, remote).isPruneEligible()
    ∧ !hasPendingCompletionDecision(session, remote)
```

**v1 prune-eligible close reasons** (frozen set; exclusive for now):

```text
OBLIGATION_DEADLINE
```

**Non-goal:** Additional prune-eligible reasons (`HOST_TERMINATED`, `AUTHORITY_LEFT`, …) **MAY** be introduced only by future ADR amendments that extend `isPruneEligible()`. Implementations **MUST NOT** invent local reasons outside this ADR.

**v1 non-eligible close reasons** (examples; not prune path):

```text
RECOVERED                 → member healthy; prune forbidden
MEMBERSHIP_LEFT           → already left; wrong path
CONFERENCE_TERMINATED     → conference teardown owns cleanup
```

Participant nodes **MUST NOT** call `AUTHORITY_PRUNE` (R29); they keep `RECOVERY_MEDIA_DEGRADED` advisory path.

#### Forbidden gates

```text
!isEdgeRecovering()
isFailedMediaRecovery()
ICE unhealthy + meshNegotiationGraceMs from unhealthySince
attempt_timeout age alone
HELLO silence / route=false / ICE CLOSED inferred locally
```

These are attempt / media-health / local inferences. They **MUST NOT** alone authorize `AUTHORITY_PRUNE`, and **MUST NOT** be used to invent `hasPendingCompletionDecision` or deadlines.

#### Relationship to Observation Window

Observation Window lives in Recovery (R28-H). Membership **does not** sleep.

```text
FAILED_MEDIA_RECOVERY
    → obligation stays OPEN
    → material transitions → REEVALUATE → new Attempt (still OPEN)
    → controller closes with prune-eligible reason (v1: OBLIGATION_DEADLINE)
    → !hasPendingCompletionDecision
    → canAuthorityPrune MAY become true
```

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
- Expanding `isPruneEligible()` beyond `OBLIGATION_DEADLINE` in this ADR
- Presence / host pill media-health UX
- Choosing concrete `observationWindow` duration (Recovery config; soak suggests ≫ 4s)

## Consequences

- **Positive:** Host prune no longer races WiFi restore; Recovery and Membership stay decoupled; roster broadcast is part of the prune transaction.
- **Negative:** Unhealthy members remain JOINED until controller closes with a prune-eligible reason — intentional; presence must show degradation (R29-C).
- **Implementation order (landed):**
  1. R28-H API (`edgeObligation*`, `obligationCloseReason`, `hasPendingCompletionDecision`)
  2. ADR-0024 prune gate (`canAuthorityPrune`)
  3. P1 supersede / watchdog (`REATTACH_ACCEPTED` → cancel old watchdog) — **PASS** via #79 / ADR-0023 G-R29-P1

## Soak gates

| Gate | Pass criterion | Status |
|------|----------------|--------|
| G-R29-E1 | After `FAILED_MEDIA_RECOVERY`, no `AUTHORITY_PRUNE` while `edgeObligationOpen` | **PASS** IT `conferenceR29E_hostDoesNotAuthorityPruneWhileObligationOpen`. Evidence: host cleanup consumes `edgeObligationClosed` / prune-eligible close reason only; OPEN obligation blocks `AUTHORITY_PRUNE` |
| G-R29-E2 | HELLO / route material transition inside window → new attempt / REEVALUATE; still no prune | **PASS** IT `conferenceR28H2_materialReevalKeepsObligationOpenWithoutPrune` (shared with G-R28-H2). Evidence: material re-eval keeps obligation OPEN; no `AUTHORITY_PRUNE` inside observation window |
| G-R29-E3 | Permanent offline → controller closes prune-eligible (`OBLIGATION_DEADLINE`) + `!hasPendingCompletionDecision` → host MAY prune | **PASS** IT `conferenceR29E_hostMayAuthorityPruneAfterObligationDeadline` (shared with G-R28-H3). Evidence: deadline closes with `closeReason=OBLIGATION_DEADLINE`; then `canAuthorityPrune` may become true |
| G-R29-E4 | **Membership Convergence** — after authority prune commits: `host.joinedCount == participant.joinedCount` **and** all participants observe the **same membership roster version**. Temporary divergence allowed **only** during broadcast propagation. Count-equal with divergent roster version = **FAIL**. | **PASS** IT `conferenceR29E4_authorityPruneConvergesJoinedCountAndRosterEpoch`. Evidence: after `AUTHORITY_PRUNE`, host and participant converge on equal joined count **and** same roster epoch |

## References

- ADR-0022 — Recovery Completion Ownership (R28-H / R28-H.1)
- ADR-0023 — Conference Membership Mutation Authority Boundary (R29)
- R29 soak `logs-r29-soak-20260713-112015` (session `647484ef`)
- `docs/audit/ro-m3-recovery-write-matrix.md`
