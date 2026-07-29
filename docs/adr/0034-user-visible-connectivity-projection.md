# ADR-0034: User Visible Connectivity Projection

## Status

**Accepted** (design freeze 2026-07-29; implementation landed 2026-07-29) — `/grill-with-docs` **Q1–Q9 ACCEPTED**; **INV-PRES-001..009**. Reference: `UserVisibleConnectivityProjection` + meeting/member UI consumers. **G-PRES-A PASS** (2026-07-29).

Complements **ADR-0022** (recovery / completion ownership — CLOSED for this workstream), **ADR-0028** / **ADR-0030** (local presence ownership), **QUALIFICATION_SIG_V1** (peer readiness — CLOSED).

**Partially supersedes [ADR-0030](./0030-presence-projection-contract.md) R30-P-3 Rule 2 and R30-P-5 Level 2** for **user-visible connectivity copy** (pill / member connectivity hint / avatar connectivity semantics):

```text
ADR-0030 Rule 2 (withdrawn for connectivity UX):
  recovering(P) || mediaUnavailable(P)  →  Presence RECONNECTING
  (even when receivePathLive)

ADR-0034 (normative for connectivity UX):
  MEDIA_OK + control sync incomplete  →  SYNCING
  MEDIA_OK + control degraded         →  DEGRADED
  MEDIA_UNAVAILABLE + repair active   →  RECONNECTING
  recovering / obligation alone       →  MUST NOT drive RECONNECTING
```

**Remain in force from ADR-0030 / 0028:** sole-owner discipline for per-peer local facts, purity (no timers inside projection), aggregate sets must not feed per-peer resolve, P2 asymmetric-mesh non-goal, membership-gated LEFT.

Does **not** reopen B3 capability, Recovery Completion Authority, Peer Edge Qualification, PRR, transport epoch, or repair flow.

---

## Summary

Post-merge smoke (`obs-smoke-post-merge-20260729-104309`) showed a **healthy isolation failure**: SIG / B3 / Completion behaved as frozen, but Meeting pill stayed on `M03 reconnecting...` while media was already `CONNECTED` and only control/negotiation sync remained pending (`sessionEdgeRecovering=true`, obligation OPEN, negotiation deferred).

Root cause class:

```text
Recovery lifecycle fact
        ↓
sessionEdgeRecovering / recoveringPeers
        ↓
UI = reconnecting
```

Not: wrong recovery completion. Not: wrong peer readiness.

This ADR freezes a dedicated **User Visible Connectivity Projection**:

> User-visible connectivity must reflect **user experience semantics**, not internal recovery lifecycle ownership.

Parallel discipline with CLOSED contracts:

```text
B3:   observation  ≠  completion
SIG:  readiness    ≠  repair
PRES: visibility   ≠  admission / authority
```

---

## 1. Scope

### In scope

Solve:

```text
media restored
+
control / negotiation still synchronizing
+
UI shows reconnecting
```

Deliver:

- Pure projection from independent **media** and **control sync** coarse facts
- V1 peer states: `CONNECTED` | `SYNCING` | `DEGRADED` | `RECONNECTING`
- Meeting pill non-escalating aggregation
- Display vs domain admission separation
- Regression that locks the smoke case

### Out of scope (explicit non-goals)

This ADR **MUST NOT** modify:

```text
B3 capability / NEGOTIATION_CAN_EXECUTE
Recovery Completion Authority
closeObligation / markRecovered
Peer Edge Qualification / PEER_EDGE_* readiness
PRR / transport epoch
Repair / ICE restart / reattach flow
Obligation lifecycle ownership
```

Implementation PR boundary:

```text
ADD:    UserVisibleConnectivityProjection (+ tests)
CHANGE: UI consumers of connectivity copy
KEEP:   all domain owners / admission paths
```

---

## 2. Ownership Boundary

```text
Domain Owners (media, peer readiness, negotiation coarse, …)
        |
        | facts only (read)
        v
UserVisibleConnectivityProjection
        |
        v
UI (pill / avatar connectivity / member hint)
```

### Projection MAY

- Read media usability facts
- Read coarse control-sync facts (including coarse peer readiness / negotiation sync phase — **not** protocol reason strings)
- Output `PeerConnectivityState` and meeting summary

### Projection MUST NOT

- `closeObligation` / `markRecovered`
- Trigger repair / PRR / rebind
- Alter qualification / generation / epoch
- Grant or deny protocol admission
- Own timers, expiration, retries, or terminal transitions

Normative invariants: **INV-PRES-001..009** (see §9). Glossary: `CONTEXT.md` — **User Visible Connectivity Projection**.

---

## 3. V1 State Model

```text
PeerConnectivityState (V1):

  CONNECTED
  SYNCING
  DEGRADED
  RECONNECTING
```

Reserved (not in V1 user-visible contract):

```text
UNAVAILABLE
```

**Forbidden identity:** internal `RECOVERING` / `sessionEdgeRecovering` as a user-visible state name.

Semantics (user language):

| State | User meaning |
|-------|----------------|
| `CONNECTED` | Media usable; control sync stable |
| `SYNCING` | Media usable; control synchronization still in progress |
| `DEGRADED` | Media usable; quality / integrity below normal (not "in progress") |
| `RECONNECTING` | Media not usable; repair / recovery active |

`SYNCING ≠ DEGRADED` (**INV-PRES-007**).

---

## 4. Dual Axis Mapping (Q6=F-4; hard veto ≡ F-1)

### Axis A — Media usability

```text
MEDIA_OK
MEDIA_UNAVAILABLE
```

### Axis B — Control sync state

```text
STABLE
SYNCING
DEGRADED
```

### Mapping table

| Media | Control | User state |
|-------|---------|------------|
| OK | STABLE | `CONNECTED` |
| OK | SYNCING | `SYNCING` |
| OK | DEGRADED | `DEGRADED` |
| UNAVAILABLE | any repairing / syncing / degraded control | `RECONNECTING` |

Hard rule:

```text
MEDIA_OK MUST NOT map to RECONNECTING
```

### Forbidden direct mappings (smoke class)

```text
obligation OPEN              → RECONNECTING     ❌
sessionEdgeRecovering=true   → RECONNECTING     ❌
HAVE_LOCAL_OFFER /
SIGNALING_NOT_STABLE /
NEGOTIATION_DEFERRED         → RECONNECTING     ❌
```

Correct smoke mapping:

```text
media = CONNECTED (MEDIA_OK)
negotiation deferred
obligation OPEN
sessionEdgeRecovering = true

→ MEDIA_OK + CONTROL SYNCING → SYNCING
```

Lifecycle signals remain **diagnostic only**; they may inform coarse `CONTROL_SYNC_STATE` derivation at the fact-adapter boundary, but **MUST NOT** be the UI state themselves (**INV-PRES-006**).

Protocol reasons (**INV-PRES-002**) **MUST NOT** appear in user copy (`SIGNALING_NOT_STABLE`, `HAVE_LOCAL_OFFER`, gate blocked strings, etc.).

---

## 5. Meeting Aggregation (Q3=G-3, Q8=A-1+A-3)

Peer-level state is the truth source; meeting pill is a secondary consumer.

```text
PeerConnectivityState(P)
        |
        v
Meeting Connectivity Summary  (non-escalating)
```

### Severity order (fixed; precedes count)

```text
RECONNECTING > DEGRADED > SYNCING > CONNECTED
```

(`UNAVAILABLE` reserved; not V1.)

### Rules

- **MUST NOT** upgrade a lighter peer state into a heavier meeting state
- Equal severity: presentation **MAY** include peer count or identifiers; state **MUST** stay the same
- Count **MUST NOT** outrank higher severity (**A-4 rejected**)

### Examples

| Peers | Meeting summary |
|-------|-----------------|
| M01 CONNECTED, M03 SYNCING | `M03 syncing...` |
| M01 CONNECTED, M03 DEGRADED, M04 SYNCING | `M03 degraded...` |
| M01 CONNECTED, M03 RECONNECTING, M04 DEGRADED | `M03 reconnecting...` |
| M01/M02/M03 DEGRADED | `3 members degraded` (or name list) |

---

## 6. Admission Separation (Q9=C-2+C-4)

```text
Display:   UserVisibleConnectivityProjection
Action:    Domain Admission Authority
           (ChannelGovernance / Directory / Membership /
            Peer Control Admission / Qualification Gate / …)
```

### Allowed inconsistencies (healthy)

```text
pill = SYNCING
CALL_INVITE admission = ALLOW
```

```text
pill = CONNECTED
GROUP_INVITE admission = BLOCK (peer_edge_not_ready / membership / …)
```

```text
pill = DEGRADED
admission = ALLOW
```

### Forbidden

```text
projection state → hard admission decision
SYNCING → disable call because projection says so
CONNECTED → bypass domain admission
```

Soft UX hint (**C-4**) is allowed ("may be unavailable") and **MUST NOT** replace hard admission (**INV-PRES-009**).

---

## 7. Lifecycle purity (Q5=L-1)

```text
SYNCING lifecycle:

ENTER:  coarse facts indicate control synchronization ongoing
STAY:   facts continue to justify
EXIT:   facts no longer justify
OWNER:  none (projection)
TIMER:  none
MUTATION: none
```

Projection announces **what the user should see now**, never **when recovery completes**.

---

## 8. Mandatory Regression

### Case A — Post-merge smoke (primary)

**Input:**

```text
media = CONNECTED
negotiation = deferred
obligation = OPEN
sessionEdgeRecovering = true
```

**Expect:**

```text
UI / PeerConnectivityState = SYNCING
NOT RECONNECTING
```

**Unchanged:**

```text
closeObligation path
markRecovered path
domain admission path
```

### Case B — True media loss

**Input:**

```text
media = UNAVAILABLE
repair / recovery active
```

**Expect:**

```text
UI = RECONNECTING
```

### Case C — Admission isolation

**Input:**

```text
UI = CONNECTED
domain admission = BLOCK
```

**Expect:**

```text
BLOCK remains (projection does not grant)
```

### Case D — DEGRADED not folded into SYNCING

**Input:**

```text
media = OK
control = DEGRADED   // e.g. peer inbound freshness soft-fail, media still usable
```

**Expect:**

```text
UI = DEGRADED
NOT SYNCING
NOT RECONNECTING
```

---

## 9. Invariants (INV-PRES)

| ID | Statement |
|----|-----------|
| **INV-PRES-001** | User-visible connectivity **MUST NOT** be derived solely from a single lifecycle boolean (`sessionEdgeRecovering` / bare `recoveringPeers` → RECONNECTING). |
| **INV-PRES-002** | Projection **MUST NOT** expose internal protocol lifecycle / gate / defer **reasons** as user connectivity semantics. |
| **INV-PRES-003** | Meeting summary **MUST** be a **non-escalating** aggregation of peer states. |
| **INV-PRES-004** | Meeting summary **MAY** show `SYNCING`, but **MUST NOT** present it as reconnecting / unavailable / failure. |
| **INV-PRES-005** | Projection **MUST** be pure: no timers, expiration, retries, or terminal transitions. |
| **INV-PRES-006** | Connectivity **MUST** derive from independent media and control-sync facts; lifecycle ownership signals **MUST NOT** be direct UI states. `RECONNECTING` **requires** media unavailable. |
| **INV-PRES-007** | `DEGRADED` is first-class in V1; **MUST NOT** alias `SYNCING`; `SYNCING` **MUST NOT** mean persistent degradation. |
| **INV-PRES-008** | Meeting aggregation severity: `RECONNECTING > DEGRADED > SYNCING > CONNECTED`; equal severity **MAY** name/count; count **MUST NOT** outrank severity. |
| **INV-PRES-009** | `UserVisibleConnectivityState` is **display-only**; hard admission remains domain-owned; projection **MUST NOT** grant/deny protocol actions. |

---

## 10. Grill decisions (frozen)

| Q | Decision |
|---|----------|
| Q1 | **D** — independent User Visible Connectivity Projection |
| Q2 | **P-2** — read coarse domain facts only; map; no mutate |
| Q3 | **G-3** — peer-level truth; meeting = aggregate consumer |
| Q4 | **S-2** — `SYNCING` may appear on pill without masquerading as RECONNECTING |
| Q5 | **L-1** — projection owns no lifecycle / timers |
| Q6 | **F-4** — dual axis (media × control); hard veto ≡ F-1 |
| Q7 | **E-2** — V1 four states; `UNAVAILABLE` reserved |
| Q8 | **A-1 + A-3** — fixed severity; equal severity may name/count |
| Q9 | **C-2 + C-4** — display vs admission; soft hint only |

---

## 11. Relationship to other ADRs

| ADR | Role relative to 0034 |
|-----|------------------------|
| **0022** | Recovery / completion authority — **CLOSED**; facts may feed coarse control axis only |
| **0028** | Sole presentation owner problem; purity — still in force |
| **0030** | LocalReachability composition — **Rule 2 / Level 2 partially superseded** for connectivity UX |
| **0025** | Conference presence aggregates — pill must not use `recoveringPeers` as connectivity truth |
| **0032 / 0033** | Dispatch / completion reachability — untouched |
| **QUALIFICATION_SIG_V1** | Peer readiness admission — untouched; may inform Control=`DEGRADED` coarse fact only |

```text
0022  Recovery obligation / edge facts / completion
0030  Local presence synthesis (narrowed for connectivity UX)
0034  User-visible connectivity (media × control) ← this ADR
SIG   Peer readiness → admission (not repair, not UI reconnecting)
```

Consumers of **connectivity copy** (meeting pill `connectingHint`, member "reconnecting/syncing/degraded" strings) **MUST** read ADR-0034 projection output, **not** `recoveringPeers` / `sessionEdgeRecovering` / ADR-0030 Rule 2 alone.

`LocalReachability` / membership presentation dimensions outside connectivity UX remain governed by ADR-0028 / 0030 until a follow-up explicitly migrates them.

---

## 12. Consequences

- **Positive:** Smoke class (media OK + control pending → false reconnecting) becomes illegal by contract; Completion Authority need not be weakened to clear UI.
- **Positive:** `SYNCING` / `DEGRADED` become first-class, preventing new collapses.
- **Negative:** Temporary dual paths during migration until all UI consumers switch; review must reject reintroduction of lifecycle→pill shortcuts.
- **Neutral:** Soft hint may diverge from admission; that divergence is intentional.

---

## 13. Implementation checklist (non-normative seam)

1. Add pure `UserVisibleConnectivityProjection` (peer map + meeting aggregate).
2. Replace UI connectivity consumers of `sessionEdgeRecovering` / `recoveringPeers`.
3. Leave admission / recovery / SIG paths unchanged.
4. Land Cases A–D as unit tests before soak.
5. Do not touch `closeObligation` / `markRecovered` / `NEGOTIATION_CAN_EXECUTE`.

---


---

## 14. Soak / field gates

| Gate | Criterion | Status |
|------|-----------|--------|
| **G-PRES-A** | media OK + control/recovery pending → pill `SYNCING`, not `RECONNECTING` | **PASS** `logs/obs-pres-uvcp-20260729-120549` (M02: `media=CONNECTED` + `sessionEdgeRecovering=true` → `connectingHint=M03 syncing...`) |
| **G-PRES-E** | after control facts clear (`recovering=false` / obligation CLOSED), projection → `CONNECTED` (no UI timer) | **PENDING** (domain-driven; not a PRES bug if SYNCING persists while facts still justify) |

### Observation (archived)

`	ext
Media restored while control recovery pending
is a supported steady intermediate state.
`

Do **not** open a bug for long-lived `SYNCING` while `sessionEdgeRecovering` / obligation remains open.
Do **not** add projection timeouts to force `CONNECTED` (violates INV-PRES-005 / Q5=L-1).
If `SYNCING` persists after media restore, investigate negotiation/completion episode closure — not UI hiding.


### Architecture acceptance (PR #97)

**PASS WITH FIELD G-PRES-E OBSERVATION** (2026-07-29).

`	ext
QUALIFICATION_SIG_V1        CLOSED
B3 Completion Authority      CLOSED
PRES / UVCP                  IMPLEMENTED
G-PRES-A                     PASS
G-PRES-E                     PENDING FIELD VALIDATION
`

Remaining risk class: **fact-chain completeness**, not semantic ownership error.

G-PRES-E field goal (when observed): prove

`	ext
domain fact transition → projection recompute → visible state transition
`

not `SYNCING` auto-hide / timer / `RECOVERED` forced UI rewrite.

If obligation stays OPEN after media restore: **PRES correct; investigate completion**.
If obligation CLOSED but CONTROL_SYNCING input remains: **PRES input-contract defect**.

Frozen non-actions: no SYNCING timeout; no Projection `closeObligation`; no UI-driven recovery retry; keep `sessionEdgeRecovering` as recovery diagnostic only.


### Maintenance observation mode (2026-07-29)

**Entered maintenance observation.** Do not reopen PRES / SIG / B3 grill on this line.

`	ext
ADR-0034 Presence / UVCP

Status:       IMPLEMENTED
Validation:   G-PRES-A PASS ; G-PRES-E PENDING FIELD VALIDATION
Acceptance:   PASS WITH FIELD G-PRES-E OBSERVATION
Risk class:   fact-chain completion validation — NOT semantic boundary risk
`

Forward-only actions:

1. Natural soak for G-PRES-E evidence (no manufactured protocol path; no UI timeout).
2. On anomaly, triage layer first:
   - `SYNCING` persists → domain facts
   - facts cleared but UI stuck → projection
   - UI ok but action wrong → admission

G-PRES-E field criteria (final):

1. Completion domain closes naturally: negotiation resolved → resolved evidence → `OBLIGATION_CLOSED(RECOVERED)` (not UI timer → CONNECTED).
2. Projection inputs: `CONTROL_SYNCING` disappears (media stays CONNECTED; control → STABLE).
3. UI follows passively: `M03 syncing...` → connected / no extra hint (either ok for pill design).

Forbidden reverse couplings remain frozen: UI state ↛ completion / qualification repair / admission.

## References

- Grill: Presence Projection / Recovery UX Q1–Q9 (2026-07-29)
- Smoke: `logs/obs-smoke-post-merge-20260729-104309`
- `CONTEXT.md` — User Visible Connectivity Projection; INV-PRES-001..009
- [ADR-0030](./0030-presence-projection-contract.md) — partially superseded Rule 2 / Level 2
- [ADR-0028](./0028-participant-presence-ownership-local-reachability-projection.md)
- [ADR-0022](./0022-recovery-completion-ownership.md)
- [ADR-0025](./0025-conference-presence-plane-projection-contract.md)