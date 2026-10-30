# ADR-0063: Negotiation Transaction Integrity — Cross-Topology Contract

## Status

**FROZEN** (2026-09-18) · Q1–Q10 adjudicated

```text
Decision:              FROZEN (cross-topology NTI contract)
Contract:              Q1–Q10 complete (see § Contract Freeze)
Reference impl:        PARTIAL — JOIN_FIRST + INVITE conform; JOIN/RECONNECT/GROUP gap (Q10)
Field qualification:   NOT AUTHORIZED by this ADR
Progression policy:    OUT OF SCOPE (retry / resend / watchdog)
RecoveryController:    OUT OF SCOPE

Next authorized work:
  ADR-0056 topology-first implementation
  (NTI prerequisite satisfied; anchor seams MUST implement Q1–Q10 on first touch)

Parent incident:
  Conference Rejoin Cross-Plane Glare / Ownership Stall — CLOSED / STOP

Evidence basis:
  P1 targeted regression PASS (CrossPlaneGlareAnswererGateTest R1/R2/R5/R7)
  G2 collision safety TEST PROVEN
  G2 collision field exercise NOT EXERCISED
  existing CR4 progression FIELD PROVEN SUFFICIENT
```

**Relation:**

- [ADR-0019](./0019-conference-signaling-media-separation.md) — signaling vs media lifecycle; NTI governs **when signaling facts may be published**
- [ADR-0050](./0050-negotiation-admission-handoff.md) — negotiation lease / admission handoff; **progression layer above NTI**
- [ADR-0056](./0056-conference-topology-first.md) — topology-first architecture; **MUST comply with NTI on every admitted media edge**
- [ADR-0022](./0022-recovery-completion-ownership.md) — INV-NEG-* negotiation stabilization / deferred drain; **operational subset of NTI at recovery seams**
- WiFi recovery main chain — **CLOSED**; do not merge membership / RNA / completion predicate changes here

```text
Meeting maturity ladder (frozen):

  Foundation
    NTI cross-topology invariants          ← this ADR
          ↓
  Architecture implementation
    ADR-0056 topology-first
    4+ participant Anchor / SFU-lite path
          ↓
  Scale convergence
    8–9 participant establishment
    + leave/rejoin + representative recovery
    + bounded reconvergence
          ↓
  Architecture maturity decision
```

**Red lines (frozen):**

```text
New Meeting bug MUST NOT invalidate established multicast media feasibility.
N=9 media qualification PASS MUST NOT claim 8–9 lifecycle convergence proven.
Future production incidents reopen here ONLY on matching
  transaction ownership / outcome fidelity fingerprint.
```

---

## Context

Field incident **Conference Rejoin Cross-Plane Glare / Ownership Stall** proved a class of defect that is **not topology-specific**:

```text
concurrent remote offer
→ WebRTC retained authoritative local CR4 (HAVE_LOCAL_OFFER)
→ Coordinator ignored settlement outcome
→ false GROUP_ACCEPT(answer)
→ answerer commit failed
→ false ACCEPT_FAILED
→ existing progression destroyed
→ prolonged CONNECTING
```

The defect was **Negotiation Transaction Integrity (NTI) violation**, not missing retry.

Industry WebRTC already defines polite/impolite glare resolution at the engine layer. The product defect was that **Coordinator published negotiation outcomes contradicting engine settlement truth**.

This contract freezes NTI as the **correctness foundation** across:

| Topology | Edge shape | NTI applies? |
|----------|------------|--------------|
| Conference MESH (< threshold) | pairwise PeerConnection | **YES** |
| Conference Anchor / SFU-lite (ADR-0056) | uplink / downlink / relay edges | **YES** |
| Group PTT mesh | pairwise | **YES** |

Topology changes **who** negotiates and **which** edges exist. It does **not** relax outcome fidelity.

---

## Decision

**Negotiation Transaction Integrity is a cross-topology correctness foundation.**

Coordinator and admission layers **MUST** treat WebRTC engine settlement as authoritative before publishing:

- `GROUP_ACCEPT` / answer-bearing signaling
- answerer transaction commit side effects
- success-derived facts (`meshCompleted`, `participantActive`, etc.)
- terminal admission transitions derived from accept/commit (`ACCEPT_FAILED`)

**Retry, resend, watchdog, and successor creation are progression policy.** They **MUST NOT** substitute for NTI. A system with retry but without NTI can still destroy authoritative transactions via false state transitions.

---

## Contract Freeze — Q1–Q10 (2026-09-18)

Authoritative adjudication. Implementation **MUST** conform; gaps **MUST NOT** block ADR-0056 start but **MUST** be closed on first seam touch or dedicated NTI seam PR.

### Q1 — What is a Negotiation Transaction?

A **Negotiation Transaction** is one bounded WebRTC signaling unit on a single media edge `(sessionId, remoteModuleId)`:

```text
offer ingress (local createOffer OR remote applyRemoteOffer)
→ optional glare resolution (polite rollback / impolite retain)
→ answerer settlement OR offerer stable convergence
→ optional GROUP_ACCEPT / GROUP_JOIN answer handoff
→ answerer commit (release settlement; INV-NEG-005)
→ success-derived facts (meshCompleted, participantActive, admission READY)
```

**Not** a Negotiation Transaction: ICE restart intent, recovery obligation episode, delivery assurance attempt, admission phase label, UI projection state.

**Settlement fact (answerer):** `engine.justSettledAsAnswerer() == true` after remote offer applied and SRD(OFFER)+SLD(ANSWER) completed inside engine.

**Commit fact (answerer):** `engine.commitAnswererTransaction() == true` after successful GROUP_ACCEPT handoff (Coordinator-owned seam).

### Q2 — Who is the authoritative owner?

| Layer | Owner | Owns |
|-------|-------|------|
| **Engine** | `WebRtcAudioEngine` / PeerConnection | signalingState, local/remote description, polite/impolite glare outcome, `justSettledAsAnswerer`, commit precondition |
| **Coordinator** | `TalkbackCoordinator` | when signaling facts may be published; handoff send; commit invocation; classification logs |
| **Progression** | existing invite/resend/recovery paths (ADR-0050, recovery controller) | retry/resend/watchdog **after** NTI truth preserved |
| **Admission** | `ConferenceAdmissionTracker` | phase projection `(sessionId, peerId)`; handoff latch |

**Rule:** Coordinator **MUST NOT** override engine winner. Progression **MUST NOT** invent negotiation truth. Admission **MUST NOT** imply settlement or commit.

### Q3 — How is the unique winner determined under concurrent offers?

Winner selection is **delegated to WebRTC polite/impolite semantics** at the engine; Coordinator **reads** the outcome:

| Engine outcome after concurrent remote offer | Winner | Coordinator action |
|---------------------------------------------|--------|---------------------|
| impolite + `HAVE_LOCAL_OFFER` + local OFFER | **LOCAL** | retain local transaction; NOT_SETTLED class A |
| polite + rollback + settled answerer | **REMOTE** | normal accept → commit path |
| impolite + `STABLE` (no local offer) | **NONE** (no owner) | NOT_SETTLED class B; no false ACCEPT |
| transaction rejected (busy/closed) | **NONE** | NOT_SETTLED class C |

**Forbidden:** Coordinator picks winner by joinIntent, recovery priority, or membership role. **Forbidden:** two simultaneous authoritative transactions on one edge.

Maps to **INV-NTI-005**.

### Q4 — How must the loser be dispositioned?

| Loser | Required disposition | Coordinator MUST NOT |
|-------|---------------------|----------------------|
| Remote offer when local wins (class A) | `REMOTE_REJECTED_KEEP_LOCAL` — remote not applied; local offer retained | send GROUP_ACCEPT(answer); drain remote ICE |
| Local offer when remote wins (polite rollback) | `LOCAL_ROLLED_BACK_REMOTE_WINS` — local rolled back; remote settled | retain stale local offer facts |
| Superseded recovery intent (lineage cut) | `SUPERSEDED` per recovery domain | revive superseded lineage |

Loser disposition is **observation + preservation**, not a new admission transition reason.

Maps to **INV-NTI-006**.

### Q5 — When may success facts be published?

Success facts **MUST** publish only after **all** of:

```text
1. engine.justSettledAsAnswerer() == true
2. GROUP_ACCEPT handoff success (answerer paths)
3. engine.commitAnswererTransaction() == true
```

Success facts include:

```text
markMeshLinkCompleted
markConferenceParticipantActive
ConferenceAdmissionPhase.READY (from accept commit)
finalizeConferenceInviteAccept side effects that assume converged edge
```

**Forbidden:** success facts on NOT_SETTLED, on handoff failure before settlement, or on commit failure.

`ACCEPT_FAILED` is **not** a success fact; see Q4 / INV-NTI-004.

Maps to **INV-NTI-003**.

### Q6 — When negotiation owner disappears, who owns progression?

```text
NTI layer (this ADR):
  MUST preserve truthful owner state
  MUST NOT assign retry authority

Progression layer (separate ADR / incident):
  MUST provide successor when owner absent
```

| State after NOT_SETTLED | Owner | Progression authority |
|-------------------------|-------|----------------------|
| Class A — local offer retained | **LOCAL** (existing CR4 / invite offer) | **Existing** invite-answer / peer polite answer path |
| Class B — no local owner | **NONE** | **UNASSIGNED in NTI** — separate progression ADR required before inventing retry |
| Class C — transaction rejected | **NONE** | **UNASSIGNED in NTI** — re-probe when engine available |
| Settled + commit success | **REMOTE accepted** | normal mesh / recovery completion |

**NTI-4 rule (frozen):** a required admitted edge **MUST NOT** simultaneously lack negotiation owner, lack successor/progression obligation, and lack explicit terminal authority.

NTI **does not** implement retry. It **prevents false terminals from killing** valid owners (incident proof: CR4 progression was sufficient).

### Q7 — Admission handoff lifecycle binding

**Scope:** `(sessionId, peerId)` = `ConferenceAdmissionKey` per `ConferenceAdmissionTracker`.

**Purpose:** block offer-lineage mutation (`canRestartConferenceEdge`) while ACCEPT→ICE_CONNECTED is in flight.

| Event | Handoff |
|-------|---------|
| Local sends conference invite / offer dispatched | `beginAdmissionHandoff` |
| Inbound invite received (conference) | `beginAdmissionHandoff` |
| Answerer commit → READY | `completeAdmissionHandoff` (via commit success path) |
| ICE CONNECTED (edge media ready) | `completeAdmissionHandoff` (secondary release) |
| ACCEPT_FAILED (legitimate, post-settlement) | `completeAdmissionHandoff` |
| NOT_SETTLED class A (retain owner) | **handoff retained** — do **not** complete |
| Session terminate | `terminateSession` clears handoff |

**Binding rule:** handoff lifetime **MUST** track the active offerer-side or answerer-side admission attempt for that edge, **not** recovery obligation lifetime and **not** global session lifetime.

While handoff active: `CONFERENCE_EDGE_RESTART_SUPPRESSED reason=ACCEPT_HANDOFF_ACTIVE` is **expected**, not a defect.

### Q8 — Illegal states (explicit)

The following are **contract violations** (illegal states):

| ID | Illegal state |
|----|---------------|
| **IL-1** | `GROUP_ACCEPT` sent while `justSettledAsAnswerer() == false` |
| **IL-2** | `ACCEPT_FAILED` emitted solely because answerer never settled |
| **IL-3** | `markMeshLinkCompleted` or `markConferenceParticipantActive` before successful commit |
| **IL-4** | `drainPendingIce` on rejected / not-applied remote offer |
| **IL-5** | Zero negotiation owner + zero successor + non-terminal admission on required edge |
| **IL-6** | Two authoritative transactions on one edge without explicit supersession |
| **IL-7** | `CROSS_PLANE_GLARE` label without `HAVE_LOCAL_OFFER` + local OFFER fact |
| **IL-8** | Steady-state `ANSWERER_COMMIT_PRECONDITION_BREACH` (caller failed INV-NTI-002) |

Illegal states **MUST** be logged and **MUST NOT** be "repaired" by inventing false terminals.

### Q9 — MESH / Anchor / SFU-lite unified applicability

**YES — unified contract.**

| Topology | Edge examples | NTI |
|----------|---------------|-----|
| Conference MESH | pairwise PC | **FULL** |
| Group PTT mesh | pairwise PC | **FULL** |
| Anchor uplink | publisher → anchor | **FULL** |
| Anchor downlink | anchor → subscriber | **FULL** |
| SFU-lite relay | admitted media edge | **FULL** |

ADR-0056 changes **which edges exist** and **recovery target derivation**. It **does not** grant exceptions to Q1–Q8.

New anchor seams (`ANCHOR_*`) **MUST** implement INV-NTI-002..010 on first implementation; no "anchor bypass."

### Q10 — Seam conformance matrix (as of freeze)

| Seam | Function | Caller NOT_SETTLED gate | Commit-before-publish | Conformance |
|------|----------|-------------------------|----------------------|-------------|
| **JOIN_FIRST** | `acceptGroupJoin` (new mesh) | **YES** | **YES** | **CONFORM** |
| **INVITE** | `completeConferenceAcceptWithEngine` | **YES** | **YES** | **CONFORM** |
| **JOIN** | `acceptGroupJoin` (meshCompleted) | **NO** | partial — no pre-commit meshCompleted; relies on shared commit helper | **GAP** — helper-only; WiFi recovery path |
| **RECONNECT** | `acceptGroupInviteReconnect` | **NO** | **NO** — `markMeshLinkCompleted` before commit | **GAP** |
| **GROUP INVITE/RECONNECT** | `completeGroupAcceptWithEngine` | **NO** | **NO** — `markMeshLinkCompleted` before commit | **GAP** |
| **ANCHOR_*** | not implemented | n/a | n/a | **PENDING** (ADR-0056) |

**Shared commit helper** (`commitAnswererTransactionAndDrain`): **CONFORM** — INV-NTI-004/009 (no false FAILED on NOT_SETTLED; precondition breach logged).

**Gap closure policy (frozen):**

```text
ADR-0056 implementation MAY proceed.
Remaining MESH gaps (JOIN / RECONNECT / GROUP) MUST be closed
  on first modification of that seam OR before anchor seam ships.
No new seam may ship without full Q5 ordering.
```

Reference tests: `CrossPlaneGlareAnswererGateTest` R1/R2/R5/R7.

---

## Layer model (frozen)

```text
WebRTC Engine
  settlement truth: justSettledAsAnswerer(), signalingState, local/remote description
        ↓
NTI (this ADR)
  Coordinator MUST NOT publish facts contradicting engine truth
        ↓
Progression policy
  retry / resend / watchdog / successor — separate ADR / incident only
        ↓
Topology architecture (ADR-0056)
  which edges exist, anchor epoch, admitted recovery targets
```

Forbidden collapse:

```text
"has retry" ⇒ convergence guaranteed                     // forbidden
"topology-first" ⇒ NTI optional on anchor edges          // forbidden
NOT_SETTLED ⇒ ACCEPT_FAILED to avoid silent stall        // forbidden
SLD(answer) success ⇒ GROUP_ACCEPT without settlement    // forbidden
commit failure after never-settled ⇒ ACCEPT_FAILED       // forbidden
```

---

## Frozen invariants

### INV-NTI-001 — Engine settlement is negotiation truth

For any answerer path after `applyRemoteOffer*`:

```text
engine.justSettledAsAnswerer() == true
  ⇔ remote offer was applied and answerer transaction reached settled state
```

Coordinator **MUST NOT** infer settlement from:

- non-empty returned SDP string
- `createAnswer` callback completion alone
- local signaling lock held
- handoff queue acceptance

### INV-NTI-002 — Universal NOT_SETTLED gate (Outcome Fidelity)

After `applyRemoteOfferWithLineage()` (or topology-equivalent seam):

```text
if justSettledAsAnswerer() == false:

  MUST NOT:
    send GROUP_ACCEPT(answer)
    commit answerer transaction
    markMeshLinkCompleted
    markConferenceParticipantActive
    drainPendingIce for the rejected remote transaction
    emit ACCEPT_FAILED solely because answerer did not settle

  MUST:
    classify why settlement did not occur
    preserve engine transaction truth
    return from answerer success path without fabricated outcomes
```

This gate is **unconditional**. Glare is a **classification** of NOT_SETTLED, not the gate condition.

Maps to incident **G2 No false accept**.

### INV-NTI-003 — Commit-before-publish

Success-derived facts **MUST** occur only after successful answerer commit on the same path:

```text
remote offer applied
→ settled as answerer
→ GROUP_ACCEPT handoff success
→ commitAnswererTransaction succeeds
→ markMeshLinkCompleted / markConferenceParticipantActive
```

Forbidden ordering:

```text
markMeshLinkCompleted before commit
participantActive before commit
ACCEPT_FAILED from commit helper when caller never reached settled gate
```

### INV-NTI-004 — Truthful failure semantics

```text
ACCEPT_FAILED  ⇔  legitimate accept path failure AFTER answerer settlement

Includes:
  handoff failure after settlement
  commitAnswererTransaction == false after settlement

Excludes:
  NOT_SETTLED without ever becoming answerer
  cross-plane glare retain-local-owner path
  STABLE + impolite with no local owner (observation only in P1)
  transaction onRejected / engine busy without settlement
```

`NOT_SETTLED` **MUST NOT** be converted to `ACCEPT_FAILED` to manufacture a terminal state.

Maps to incident requirement: preserve real R5 commit-failure semantics without false failures.

### INV-NTI-005 — Single authoritative transaction

After concurrent offers on one edge, **exactly one** authoritative negotiation transaction **MUST** remain visible to progression:

```text
local outstanding offer retained   OR
remote offer accepted and settled  OR
explicit successor obligation with authority
```

Forbidden:

```text
zero owner + zero successor + silent non-terminal admission
false ACCEPT pretending remote won when engine retained local offer
```

Maps to incident **G1 Single winner** and **G4 Progress owner**.

### INV-NTI-006 — Loser disposition respects WebRTC role

When concurrent offers collide, loser disposition **MUST** follow engine polite/impolite outcome:

| Disposition | Meaning |
|-------------|---------|
| `REMOTE_REJECTED_KEEP_LOCAL` | impolite local retained outstanding offer |
| `LOCAL_ROLLED_BACK_REMOTE_WINS` | polite local rolled back; remote settled |
| `SUPERSEDED` | explicit lineage/intent supersession (recovery domain) |

Coordinator **MUST NOT** override engine winner selection by publishing loser-side accept facts.

Maps to incident **G3 Loser disposition**.

### INV-NTI-007 — NOT_SETTLED must not kill progression via false terminal

When classification is **cross-plane glare** (local owner retained):

```text
signalingState = HAVE_LOCAL_OFFER
localDescriptionType = OFFER
polite = false
```

Then:

```text
admission: remain NEGOTIATING (or enter NEGOTIATING if handoff active)
handoff: retained
progression: existing local owner / invite-answer path
ACCEPT_FAILED: NO
```

Progression liveness is **not guaranteed by NTI**; NTI guarantees progression is **not destroyed by false facts**.

Maps to incident **G5 Bounded convergence** at the **integrity** layer (not retry layer).

### INV-NTI-008 — NOT_SETTLED classification (no false CROSS_PLANE_GLARE)

| Class | Condition | Log | ACCEPT_FAILED |
|-------|-----------|-----|---------------|
| **A** Cross-plane glare | `HAVE_LOCAL_OFFER` + local `OFFER` + impolite | `CROSS_PLANE_GLARE winner=LOCAL` | **NO** |
| **B** No local owner | `STABLE` + impolite | `ANSWERER_NOT_SETTLED reason=NO_LOCAL_OWNER` | **NO** (P1) |
| **C** Transaction rejected | engine busy / closed / onRejected | `ANSWERER_NOT_SETTLED reason=TRANSACTION_REJECTED` | **NO** (P1) |

Class **A** requires outstanding local offer fact. **`STABLE + impolite` MUST NOT** be labeled `CROSS_PLANE_GLARE`.

Classes **B/C** may indicate independent engine-contract defects. Do **not** merge with cross-plane glare incident fixes without new evidence.

### INV-NTI-009 — Commit precondition breach is observability, not auto-repair

```text
commitAnswererTransactionAndDrain():
  precondition = settled answerer transaction exists

  if !settled:
    log ANSWERER_COMMIT_PRECONDITION_BREACH
    return false
    MUST NOT emit ACCEPT_FAILED
```

Caller **MUST** enforce INV-NTI-002 before calling commit. Helper **MUST NOT** swallow caller bugs by silently retaining NEGOTIATING.

Expected steady-state count after correct gating: **0** breach logs in targeted regression.

### INV-NTI-010 — Rejected remote transaction MUST NOT drain pending ICE

When remote offer was **not** applied (NOT_SETTLED):

```text
remote description absent
→ remote transaction superseded or never started
→ MUST NOT drainPendingIce against that rejected remote lineage
```

Pending ICE for a future authoritative negotiation **MAY** remain; lineage gates already in product code are preserved unchanged.

---

## Answerer seam enumeration (normative)

Every Coordinator answerer path **MUST** implement INV-NTI-002..004 and INV-NTI-009:

| Seam label | Trigger | Topology |
|------------|---------|----------|
| `JOIN_FIRST` | `GROUP_JOIN` first attach / recovery reattach answerer | MESH conference edge |
| `INVITE` | inbound conference invite answerer | MESH conference edge |
| `JOIN` | mesh ICE-restart / recovery accept on completed mesh | MESH group/conference |
| `RECONNECT` | invite reconnect answerer | group/conference |
| `ANCHOR_*` | ADR-0056 anchor uplink/downlink answerer (future) | Anchor / SFU-lite |

Offerer-side paths **MUST** respect INV-NTI-005/006 at the engine boundary (polite rollback vs retain local offer). Coordinator **MUST NOT** publish remote-accept facts when local offer remains authoritative.

---

## Observability contract (frozen)

| Event | Meaning | Terminal? |
|-------|---------|-----------|
| `CROSS_PLANE_GLARE` | NOT_SETTLED class A; local owner retained | **NO** |
| `ANSWERER_NOT_SETTLED` | NOT_SETTLED class B/C | **NO** (P1) |
| `ANSWERER_COMMIT_PRECONDITION_BREACH` | invariant breach; caller bug | audit |
| `ANSWERER_TRANSACTION_COMMIT result=FALSE` | real commit failure after settlement | may → `ACCEPT_FAILED` |
| `GROUP_ACCEPT_HANDOFF result=SUCCESS commit=PENDING` | settled; commit not yet attempted | **NO** |

Engine-level glare observation (behavior-neutral):

```text
op=GLARE outcome=REJECTED_GLARE_KEEP_LOCAL polite=false state=HAVE_LOCAL_OFFER
op=GLARE outcome=REJECTED_IMPOLITE polite=false state=STABLE
```

---

## Does NOT authorize

```text
watchdog / resend / retry budget for cross-plane glare
new ConferenceAdmissionTransitionReason for collision
RecoveryController semantic refactor
ADR-0056 topology-first implementation
membership / RNA / completion predicate changes
additional field collision reproduction
UI / banner / timeout changes
```

Progression defects after correct NTI **MAY** become separate incidents with separate ADRs.

**Downstream (topology implementation):**

- [0056-phase-05-nti-topology-authority-mapping.md](../analysis/0056-phase-05-nti-topology-authority-mapping.md) — NTI × topology fact ownership + migration seam list (**FROZEN**)
- [0056-phase-05-contract-mapping.md](../analysis/0056-phase-05-contract-mapping.md) — recovery / epoch / provider matrix (**FROZEN**)

---

## Appendix A — Reference implementation map

Normative conformance: **Q10**. This appendix mirrors Q10 for code navigation.

| Seam | File / symbol | INV-NTI-002 gate | Commit-before-publish | Tests |
|------|---------------|------------------|----------------------|-------|
| `JOIN_FIRST` | `TalkbackCoordinator.acceptGroupJoin` | **YES** | **YES** | R1, R2, R5 |
| `INVITE` | `completeConferenceAcceptWithEngine` | **YES** | **YES** | R7 (stub) |
| `JOIN` | `acceptGroupJoin` meshCompleted branch | **NO** | partial | — |
| `RECONNECT` | `acceptGroupInviteReconnect` | **NO** | **NO** | — |
| `GROUP` | `completeGroupAcceptWithEngine` | **NO** | **NO** | — |
| Engine glare obs | `RealWebRtcAudioEngine.applyRemoteOfferInTransaction` | n/a | n/a | stub unit |
| `ANCHOR_*` | ADR-0056 (pending) | pending | pending | — |

Gap closure: see **Q10 gap closure policy**. ADR-0056 **AUTHORIZED** to proceed.

---

## Appendix B — Regression matrix (reference)

| ID | Scenario | Expect |
|----|----------|--------|
| R1 | CR4 outstanding + RECOVERY_REATTACH L2 impolite | no false ACCEPT; no false FAILED; local owner retained |
| R2 | normal JOIN_FIRST settled | commit → READY / meshCompleted |
| R3 | meshCompleted RECOVERY_REATTACH JOIN | unchanged legacy path |
| R4 | INVITE polite answerer | unchanged |
| R5 | settled answerer commit failure | **ACCEPT_FAILED** preserved |
| R6 | CR4 normally answered | no spurious resend |
| R7 | polite glare remote wins | settled → accept → commit |

Test class: `CrossPlaneGlareAnswererGateTest`

---

## Appendix C — Incident closure cross-reference

Frozen incident verdict (do not re-open without matching fingerprint):

```text
ROOT CAUSE:   NTI violation
FIX:          universal NOT_SETTLED gate + commit-before-publish + truthful failure
EVIDENCE:     targeted PASS; CR4 progression field sufficient; G2 field NOT EXERCISED
P2:           NOT NEEDED
STATUS:       CLOSED / STOP
```
