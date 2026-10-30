# ADR-0057: GROUP Bootstrap Convergence Contract

## Status

**ACCEPTED — CONTRACT ONLY** (2026-08-27)

**Grill:** COMPLETE · Q1–Q9 PASS  
**Implementation:** **NOT AUTHORIZED**  
**Field Validation:** **NOT AUTHORIZED**

```text
ADR-0057
Decision:              ACCEPTED — CONTRACT ONLY
Domain:                GROUP channel bootstrap convergence
Problem class:         no cross-node, generation-aware, monotonic bootstrap protocol
Does NOT reopen:       ADR-0053 · ADR-0056 · ADR-0058 · WiFi recovery / RNA / completion
                       · Conference native factory / CFC · Track A R3 · Track B
Does NOT authorize:    code · telemetry schema · wire format · timeouts · FSM impl
```

**Parents / boundaries:**

- [ADR-0053](./0053-group-bootstrap-authority-reconciliation.md) — **KEEP, scope frozen.** Primary-side stale *authority ownership* only. Does **not** cover cross-node generation alignment. **MUST NOT** be enlarged to absorb this ADR.
- [ADR-0008](./0008-group-runtime-health-projection.md) — readiness *projection*; does not own bootstrap generation.
- [ADR-0009](./0009-group-session-identity-consistency.md) — endpoint identity *after* a session is admitted; does not define bootstrap generation.
- [ADR-0027](./0027-canonical-session-lineage-and-bootstrap-convergence.md) — post-meeting lineage / canonical publish; **orthogonal admission path.** Does not substitute for a current-generation contract on cold reopen / asymmetric process lifetime.
- [ADR-0056](./0056-conference-topology-first.md) — Conference topology-first. **Orthogonal.** Do not merge Conference runtime / media-edge work into this ADR.
- GROUP media topology debt (`full mesh` vs bounded Anchor) — **independent track.** This ADR does not restore or change media topology policy.

**Evidence (desk + field, Round 1–2):**

- RCA Round 1: `talkback/docs/analysis/group-bootstrap-primary-convergence-rca-20260826.md`
- RCA Round 2: code-path pin (2026-08-27) — four observed seams are **projections of one gap**, not four independent defects
- Observation: `talkback/logs/group-readiness-observation-20260826-175700/`

---

## Why this ADR exists

Decentralized Talkback means **no infrastructure server**, not “every node independently decides bootstrap truth.”

Primary / Anchor roles already exist and remain valid. What is missing is a **shared notion of which bootstrap generation is current**, so that local observation, gossip, and retry cannot leave nodes in incompatible generations indefinitely.

Field (3 active participants, idle after reopen) already showed split generations while election agreed on the same primary. Retry counters rose without producing new authoritative facts. That is a **convergence-protocol gap**, not a scale-out problem and not an ICE-root problem.

Closing this gap **before** 8–10 node expansion is required to keep complexity on membership and bounded media edges, rather than on combinatorial local-state × generation × retry space.

---

## Problem this ADR solves

**In scope — the missing protocol:**

```text
For a GROUP channel, nodes must converge onto one current bootstrap
generation, with monotonic admission, without luck-based retry.
```

Observed failure class (illustrative, not a required repro script):

```text
Node A: current generation (acts as primary)
Node B: prior generation (waits)
Node C: no generation (cannot admit)
retry increments; no shared generation fact; no monotonic progress
```

The four field/code seams are **the same gap seen from different admission paths:**

| Projection | Meaning under this contract |
|------------|-----------------------------|
| Stale session realignment | A node cannot stay forever on a non-current generation |
| Bootstrap invite admission | A valid bootstrap intent must survive delayed prerequisites |
| Late discovery admission | Absence of a local accepted session during an active generation is not permanent failure |
| Identity propagation | A node must be able to tell whether local bootstrap state is current |

**Out of this problem statement:** media ICE algorithms, Conference hangup, native SRD / mutex, UI CONNECTING presentation, WiFi recovery completion.

---

## Vocabulary (semantic only)

These names are **contract terms**. Encoding, transport, and class names are **deferred**.

| Term | Meaning |
|------|---------|
| **Channel identity** | Which logical GROUP the node belongs to |
| **Bootstrap generation** | Which generation of bootstrap topology is being formed or held for that channel |
| **Current generation** | The unique *contract* generation that is current for the channel at a given logical epoch — not a claim that every node already knows it |
| **Authority / Primary** | Bounded coordination role. Distinct from **Bootstrap-origin authority** (who may originate a generation) and from Current Generation itself |
| **Bootstrap-origin authority** | Eligibility to **originate** a new Current Generation. Determined by election. Holding it is not originating. Originating is not the election result |
| **Session trace** | Diagnostic instance identity of a local run; **MUST NOT** be treated as a substitute for generation |
| **Stale** | Local knowledge that local bootstrap state is **not** the Current Generation. Requires a fact that can justify that knowledge. Not the same as contract **Superseded** |
| **Superseded** | Contract status of a former Current after a successor has originated. Immediate at origin instant. Other Modules may not yet know |
| **Unknown** | Local inability to determine whether a current generation exists or whether local state belongs to it. **Unknown ≠ stale. Unknown ≠ current.** |
| **Authoritative generation fact** | Makes SAME / SUPERSEDED / INSUFFICIENT EVIDENCE knowable. Not roster, media, ICE, or session trace. Transport deferred |
| **Bootstrap intent** | Cross-conflict logical obligation to admit a peer into *some* Current generation. Identity persists across CONFLICT. Distinct from **Admission Binding** |
| **Admission Binding** | One generation-scoped execution instance of a Bootstrap Intent. Late completion is valid only for that binding identity |
| **Admission state** | Per-node (or per-peer) progress toward belonging to the current generation |

**Frozen distinction:**

```text
decentralized
  = no infrastructure single point
    + distributed membership
    + bounded authority role
    + explicit current generation
    + local execution

decentralized
  ≠ every node independently deciding bootstrap truth
  ≠ follower usurping authority
```

**Frozen distinction (invalidation):**

```text
Follower MAY locally abandon stale generation state.
Follower MUST NOT claim or mutate bootstrap authority
merely because it invalidated local stale state.
```

---

## Invariants

Normative. Grill may tighten wording; **MUST NOT** replace these with implementation mechanisms.

### GBC-1 — Single current generation

```text
For each channel, the contract admits exactly one current
bootstrap generation at a given logical epoch.
```

This is the state nodes **MUST converge to**, not a claim that every node already holds it.

Nodes **MAY** temporarily be **unknown**, or temporarily hold a **wrong** (stale) generation. That is **cognitive split**, not two legal currents.

```text
UNKNOWN ≠ STALE
STALE ≠ CURRENT
CURRENT = globally unique contract state
```

Two Modules claiming different currents is a **visibility / alignment failure** (GBC-2 / GBC-3). It does **not** make both claims legitimate.

**Origin (Grill Q1 — ACCEPTED):**

```text
Election
  → determines who has bootstrap-origin authority

Bootstrap-origin authority
  → MAY originate a successor Current Generation

Generation
  ≠
Election result
```

Only a Module that currently holds **bootstrap-origin authority** MAY originate a new Current Generation.

Every other Module **MUST NOT** originate a generation, and **MUST NOT** promote a local generation to Current. They **MAY** only learn, verify, align to, or forward an **authoritative generation fact**.

```text
follower learns G2     ≠  follower creates G2
follower abandons G1   ≠  follower becomes authority
follower forwards G2   ≠  follower originates G2
```

This clause answers **who may originate**. How other Modules obtain a fact is GBC-2 / Grill Q5 (visibility and verification). Transport, wire format, and timeouts remain deferred.

**Successor origin (Grill Q2 — ACCEPTED):**

```text
Bootstrap-Origin Authority
        │
        ├─ no legitimate Current
        │       ↓
        │   MAY originate successor
        │
        └─ existing Current
                ↓
        MUST first complete the applicable
        origin-side authority invalidation (ADR-0053)
                ↓
        MAY originate successor
```

Holding bootstrap-origin authority (still being elected Primary) is **not** a sufficient condition to mint a successor.

Origin condition **≠** propagation completion. Originating G2 does **not** require every Module to know G1 has ended. Transient cognitive split **MAY** exist; the origin Module **MUST NOT** create two legitimate Currents.

ADR-0053 owns **origin-side** authority invalidation. This ADR owns **generation convergence**. ADR-0053 **MUST NOT** be extended to follower alignment.

**Supersede instant (Grill Q3 — ACCEPTED):**

```text
G2 originates
     ↓
G2 = Current
G1 = Superseded  (contract)
     ↓
other Modules MAY still execute G1
     ↓
G1 is no longer a legal Current
```

```text
Current
  ≠  what every Module currently knows
  ≠  what every Module is currently executing
```

A Module that has **not** observed the G2 fact **MUST NOT** be required to already “know STALE.” It **MAY** still locally believe G1 is current. That is **incomplete knowledge / stale execution**, not a second legal Current.

**STALE** is local knowledge. **SUPERSEDED** is contract status of the old generation. They **MUST NOT** be collapsed.

Residual local copies of non-current generations **MAY** exist in memory only as objectively superseded execution until the Module can classify them via GBC-2.

### GBC-2 — Generation visibility

```text
A node MUST be able to determine whether its local bootstrap
state belongs to the current generation.
```

Visibility **MAY** be incomplete (unknown). Incomplete is not “assume current” and is not “assume permanently invalid.”

**Fact semantics (Grill Q4 — ACCEPTED):**

An **Authoritative Generation Fact** MUST make this relation knowable:

```text
local generation  vs  attested generation

  SAME
  SUPERSEDED
  UNKNOWN / INSUFFICIENT EVIDENCE
```

It MUST identify the generation and that it is the Current (including successor superseding a prior generation). It is **not** a roster snapshot, media topology, ICE state, or Session Trace.

**Obtain / verify (Grill Q5 — ACCEPTED):**

```text
Origin MUST make the Current-generation Fact
observable or requestable when G2 originates.

Follower MAY request, receive, verify, or forward that Fact.
Forward  ≠  originate
Verify fail / insufficient evidence  →  UNKNOWN

MUST NOT substitute:
  roster · ICE · Session Trace · election result
  · media topology · path/reachability change

Mesh route change, transient loss, and underlay repair
MUST NOT originate a new generation.
```

This ADR still does **not** define transport, encoding, timers, or class names.

**UNKNOWN conduct (Grill Q6 — ACCEPTED):**

```text
UNKNOWN is a legal cognitive state. It is not a third Current.

UNKNOWN MAY persist while evidence is insufficient.
A given bootstrap admission attempt MUST NOT remain in
retry / waiting-for-Primary indefinitely: it MUST progress
or reach an explicit terminal for that attempt.
```

While UNKNOWN, a Module **MUST NOT**:

```text
originate a generation from UNKNOWN alone
promote a local generation to Current
classify a local session as SAME
treat insufficient evidence as SUPERSEDED
treat bootstrap intent as permanently failed solely because UNKNOWN
treat waiting for Primary, scheduler ticks, or retry count as progress
forward material that has not passed Q5 verification
  as an Authoritative Generation Fact
```

While UNKNOWN, a Module **MAY**:

```text
request, receive, and verify an Authoritative Generation Fact
forward a Fact that has already passed Q5 verification
retain bootstrap intent (GBC-4)
gate emission while a prerequisite is unmet, without destroying intent
keep local media / execution stopped or isolated so a wrong
  generation cannot take effect
```

Exit UNKNOWN **only** via:

```text
1. Verified Current Fact
     ├─ local generation == attested generation
     │      → SAME
     ├─ local generation superseded by attested successor
     │      → SUPERSEDED → realignment
     └─ no local generation / no local accepted session
            → PENDING / ADMISSION onto the attested Current
            (not local origin; not local promotion to Current)

2. This Module holds Bootstrap-Origin Authority AND independently
   satisfies Q2:
     ├─ can determine that no legitimate Current exists
     └─ OR origin-side invalidation (ADR-0053) is complete
            → MAY originate successor
            → after the new Fact exists, this Module is on Current

3. Explicit terminal failure of the current attempt
     → ends this attempt
     ≠ Channel permanently unable to bootstrap
     → a later attempt MUST be driven by a new Fact, a new intent,
        or a legal origin event
     → MUST NOT auto-revive from retry counter
```

**Frozen sentences:**

```text
UNKNOWN alone is never origin authority evidence.

Absence of a local accepted session is not evidence that no
legitimate Current exists.

Attempt terminality bounds execution; it does not manufacture
generation truth and does not permanently consume channel-level
bootstrap capability.
```

Q2 origin remains: Authority **MAY** leave UNKNOWN by originating **only** when Q2 is independently satisfied. Forbidden is origin **from UNKNOWN alone**, not origin by a Q2-qualified Authority.

GBC-5: no local accepted session during an attested Current is the Q6 exit-1 third branch (PENDING/ADMISSION), not SAME and not SUPERSEDED.

**Competing Facts (Grill Q7 — ACCEPTED):**

```text
Two Q5-verified Facts
  ≠  two legal Currents
```

A Fact's **immutable semantics** (encoding deferred) MUST include at least:

```text
generationIdentity
predecessor          // unique prior generation in normal lineage; none if origin
originAuthorityIdentity
semanticDigest
```

Resolution Facts **additionally** MAY carry:

```text
resolvesConflictSet { conflictingGenerationIdentities[] }
```

`predecessor` **MUST NOT** become a multi-parent DAG. Conflict displacement is **only** `resolvesConflictSet`.

**Idempotence vs identity conflict:**

```text
same generationIdentity
+ same predecessor
+ same originAuthorityIdentity
+ same semanticDigest
  → idempotent duplicate Fact

same generationIdentity
+ any of those fields disagree
  → GENERATION_IDENTITY_CONFLICT
  → MUST NOT treat as idempotent
```

**Successor proof:**

```text
Direct:     G2.predecessor = G1
Chain:      verified Facts G3→G2→G1
Missing middle (G3.predecessor=G2, isolated G1, no G2 Fact)
  → MUST NOT infer G3 successor-of G1
  → INSUFFICIENT_ORDER
```

**MUST NOT** order by name, numeric magnitude, wall clock, arrival order, or retry majority.

**Adjudication:**

```text
1. Verified lineage chain proves G2 successor-of G1
     → G2 Current · G1 Superseded

2. Immutable semantics identical
     → idempotent repeat

3. Same generationIdentity, disagreeing immutable semantics
     → GENERATION_IDENTITY_CONFLICT

4. Same predecessor, different successor identities
     → BRANCH_CONFLICT

5. Lineage order not provable
     → INSUFFICIENT_ORDER

6. GENERATION_IDENTITY_CONFLICT / BRANCH_CONFLICT / INSUFFICIENT_ORDER
     → this Module MUST NOT choose a Current
     → execution semantics = Q6 UNKNOWN gating
     → MUST retain an explicit auditable conflict reason
```

```text
CONFLICT
  = UNKNOWN execution semantics
  + explicit auditable conflict evidence

CONFLICT is not a nameless subtype that discards the evidence.
```

**During CONFLICT / INSUFFICIENT_ORDER this ADR owns:**

```text
MUST NOT classify either candidate as SAME
MUST NOT complete bootstrap admission onto either candidate
MUST NOT start new generation-scoped bootstrap side effects for a candidate
MUST NOT project either candidate as authoritative Current
```

Existing candidate-generation execution **MUST** be marked **non-authoritative / quarantined**. Whether media stops **immediately** is **not** defined here; ADR-0008 and existing execution-ownership contracts own teardown. This ADR **MUST NOT** specify synchronous media teardown.

**Who may emit a resolving Fact:**

```text
Only the current Bootstrap-Origin Authority
AND after applying Q2-required invalidation to the conflict candidates
MAY originate a resolution successor
```

That Fact **MUST**:

```text
declare a new generationIdentity
declare the normal predecessor
list the complete resolvesConflictSet
prove origin authority
```

After it originates:

```text
the one prior legal Current (if known) → Superseded
other conflicting claims → InvalidatedClaim
(they MAY never have been legal Currents)

If this Module cannot tell which candidate was once legal:
  project all listed identities as
  DISPLACED_BY_AUTHORITATIVE_RESOLUTION
  MUST NOT fabricate historical legality
```

**MUST NOT:**

```text
originate merely because CONFLICT was observed
resolve only the preferred branch
Follower-minted resolution
election result as automatic resolution
timestamp / arrival / retry majority / silent LWW
```

**Intent across CONFLICT (Grill Q8 — ACCEPTED):**

```text
Bootstrap Intent
  = logical admission obligation; identity persists across CONFLICT

Admission Binding
  = one generation-scoped execution instance of that intent
```

On CONFLICT:

```text
Bootstrap Intent identity kept
all candidate Admission Bindings fenced
MUST NOT complete or emit side effects on fenced bindings
intent → conflict-blocked pending
```

`conflict-blocked pending` **MUST NOT** be treated as Q6 attempt terminal failure.

On a legal Resolution Fact:

```text
validate Resolution Fact
+ verify it covers locally known conflict candidates
+ fence all old bindings
+ install one new Admission Binding on attested Current
= one atomic admission transition
```

```text
MUST NOT mint a duplicate Bootstrap Intent
MUST mint a new generation-scoped binding / attempt identity
```

**MUST NOT** observe:

```text
old binding still completable
AND
new binding already started
```

Delayed results **MUST** carry the original generation / binding identity and **MUST** be rejected by the new state.

Late candidate traffic:

```text
generation ∈ resolvesConflictSet
  OR proven superseded by verified lineage from the Resolution
→ MUST NOT rebind the intent

generation not covered by this Resolution
  AND not provably superseded
→ MUST NOT silent-drop as stale
→ re-enter CONFLICT / UNKNOWN gating
→ keep the same Bootstrap Intent
→ request a new authoritative Resolution
```

**MUST:**

```text
old binding completion / failure MUST NOT apply to the new binding
semantically identical repeat Resolution → idempotent
  (already bound to that Current ⇒ no second transition)
Resolution restores eligibility to admit onto attested Current
  ≠ SAME
  ≠ admission complete
unmet prerequisite → keep intent; gate emission (GBC-4)
```

### GBC-3 — Stale local state cannot block forever

```text
A stale follower session MUST have a defined local invalidation
or realignment path.
```

Invalidation **is not** convergence (read with GBC-6). Abandoning stale state **MUST** leave the node in a state that a later **authoritative generation fact** can advance: unknown / pending toward the current generation, or a **terminal failure** of that attempt.

```text
Legal:     STALE → abandon → UNKNOWN/PENDING → generation fact → forward
Forbidden: STALE → abandon → no generation → retry → abandon → retry
```

The forbidden loop is `waitingForPrimary` renamed. Waiting for the primary **alone**, without a generation fact that can change the waiter’s local state, **MUST NOT** be a stable outcome.

Follower **MAY** locally abandon stale generation state. Follower **MUST NOT** claim or mutate bootstrap authority merely because it invalidated local stale state.

### GBC-4 — Intent survives prerequisite delay

```text
A valid bootstrap intent MUST NOT be lost merely because a
prerequisite (including signaling / link readiness) is not
yet satisfied, or becomes satisfied later.
```

Prerequisites **MAY** gate *emission*. They **MUST NOT** swallow the intent. Gate present + prerequisite later true **MUST** allow the intent to progress (subject to GBC-6). CONFLICT handling of the same intent is Grill Q8 (identity kept; bindings fenced and replaced atomically).

### GBC-5 — Bootstrap pending is first-class

```text
During an active current generation, absence of a local
accepted session MUST NOT be treated as permanent admission
failure.
```

Pending **MUST** be bounded **per Admission Binding / attempt**: it **MUST** leave by a Q6 exit — not because time passed or retry counted up. Channel-level UNKNOWN/CONFLICT **MAY** persist while no Fact holder is reachable (GBC-8); that persistence is incomplete knowledge, not a second Current, and **MUST NOT** be advanced by wait/retry.

### GBC-6 — Monotonic admission

```text
Retry MAY re-evaluate.
Retry alone MUST NOT constitute progress.
```

Progress **MUST** be a change in generation membership, admission state, or an authoritative fact about the current generation. Scheduler ticks and attempt counters are observation, not convergence.

### GBC-7 — One generation truth across stages

```text
Under one current generation, these stages MAY lag in time:

  bootstrap membership
  media-edge state
  existing readiness projection (ADR-0008)

They MUST NOT carry contradictory generation truth.
Each step MAY only advance (GBC-6).
```

**Not required:** bootstrap admission success ⇒ media edge immediately ready ⇒ transmit-ready.

This ADR **does not** redefine the transmit-ready predicate and is **not** a successor readiness ADR. ADR-0008 remains readiness projection authority.

### GBC-8 — Authoritative Fact Availability

Named amendment (Grill Q9). Does **not** change GBC-1…7.

```text
When originating a Current Generation, Origin MUST make the
corresponding Authoritative Generation Fact obtainable by
other Modules. Creating the Fact only in Origin-local memory,
never making it observable, is not a completed origin.
```

Any Module that has **accepted** that Current Fact, while it still treats that Fact as Current:

```text
MUST retain the Fact's full verification semantics
MUST re-provide the unmodified Fact to a reachable requester
```

```text
Re-providing a Fact
  ≠ re-originating
  ≠ acquiring Bootstrap-Origin Authority
  ≠ creating a new generation
```

If no communication path exists to any Fact holder, the requester **MAY** remain UNKNOWN.

If a reachable holder lacks sufficient evidence, it **MAY** return INSUFFICIENT. INSUFFICIENT, no response, waiting, or retry **MUST NOT** constitute progress.

Origin **MUST NOT** be required to stay online forever. Successor origin after Origin loss remains Q2 / ADR-0053.

Transport, request encoding, persistence medium, and timeouts remain deferred.

### GBC-9 — Non-Resurrection

Named amendment (Grill Q9).

Once any of the following has lost admission standing:

```text
Superseded generation
generation claim listed in a Resolution resolvesConflictSet
fenced Admission Binding
```

a late copy **MUST NOT**:

```text
restore it as Current
rebind a Bootstrap Intent to it
complete admission on it
emit new generation-scoped bootstrap side effects
```

Late Fact with the **same** `generationIdentity` and **identical** immutable semantics as a known Fact → reject as an **expired duplicate**.

Same `generationIdentity` but different predecessor / origin / semanticDigest → **Q7 GENERATION_IDENTITY_CONFLICT**. **MUST NOT** use Non-Resurrection to silently swallow conflict evidence.

### GBC-10 — Intent After Attempt Terminal

Named amendment (Grill Q9).

Q6.3 attempt terminal:

```text
MUST fence the current Admission Binding
MUST reject delayed results of that Binding
MUST NOT automatically destroy Bootstrap Intent identity
```

If the Bootstrap Intent is still valid, it **MAY** become dormant / unbound.

Retry, scheduler tick, and waiting for Primary **MUST NOT** automatically create a new Admission Binding.

A new Admission Binding **MAY** be created only by:

```text
1. A newly obtained, verified legal Current / Resolution Fact
2. A legal new intent event that reactivates the same obligation
3. This Module satisfying Q2 and legally originating a new Current
```

New intent events **MUST** be deduplicated by intent identity:

```text
same logical obligation → reactivate the existing Intent
different logical obligation → only then mint a new Intent
```

A Bootstrap Intent **MAY** itself become terminal by explicit cancel, Session/Channel lifecycle end, or other existing intent-authority decision.

```text
attempt terminal  ≠  intent terminal
```

---

## Non-goals

- Implementation design, APIs, class ownership, callbacks, wire fields, timeouts, retry budgets, FSM tables
- Enlarging ADR-0053 to cover follower generation alignment
- Changing ADR-0008 transmit-ready / OPERATIONAL predicate
- GROUP media topology (mesh vs Anchor) or `8–10` media-edge count
- Conference topology, ConferenceAudioBus, native factory / signaling-worker isolation, CFC
- Track A Primary Native SRD / mutex / R3
- Track B MESH LIVE presentation
- WiFi recovery, RNA-5/6, completion admission, lease, HangupMediaBarrier
- Consensus / voting / durable cluster membership (Raft and similar)
- Making Primary a permanent central server or the sole store of all state
- Authorizing Meeting / hangup as the GROUP bootstrap repair API
- New field campaigns on the current broken GROUP baseline

---

## Acceptance boundary (contract grill)

Grill Q9 audit. **Item 1 PASS** via named amendments GBC-8…10. **Implementation still NOT authorized.**

| # | Criterion | Q9 |
|---|-----------|-----|
| 1 | GBC-1…7 necessary and sufficient, or named amendment | **PASS** — GBC-8 Fact Availability (distributed holders; Origin not a permanent Fact server); GBC-9 Non-Resurrection (+ identity collision → Q7); GBC-10 attempt terminal ≠ intent terminal |
| 2 | ADR-0053 scope unchanged | **PASS** |
| 3 | No usurpation; no generationless retry loop | **PASS** |
| 4 | Prerequisites gate emission, do not swallow intent | **PASS** |
| 5 | Pending first-class and bounded (not renamed retry) | **PASS** — bound per attempt/Binding; channel UNKNOWN may persist without a holder |
| 6 | Channel / generation / authority / session trace distinct | **PASS** |
| 7 | Conference / media-topology / Track A / Track B outside | **PASS** (ADR-0058 also outside) |

```text
GBC-1…7 + GBC-8…10 + Q1…Q8
= contract description SUFFICIENT
```

Still not specified: transport, request messages, persistence, timeouts, retry counts, classes/threads, media recovery.

**Passing grill / sufficiency does not authorize implementation.** This ADR is **ACCEPTED as contract only**. Mapping to mechanisms and code requires a **separate** implementation authorization.

---

## Conditions to enter implementation (later phase)

A **separate** explicit authorization is required. At minimum all of:

```text
1. This ADR is ACCEPTED — CONTRACT ONLY (grill complete; invariants frozen).
2. A follow-on design phase exists (still not this document)
   mapping invariants → mechanisms — without changing GBC-1…10.
3. Implementation authorization is written (PR / issue / session).
4. Track C Case B (post-hangup) and Track A R3 remain independently gated.
5. No GROUP readiness / ICE / UI predicate change unless a further ADR
   (or an accepted amendment here) says so.
```

Until then:

```text
Implementation HOLD
Field HOLD
Do not patch the four seams as independent bugfixes
```

---

## Track posture (informative)

```text
Track A R3     HOLD (GROUP preflight still not a GBC concern to ungate here)
Track B        CLOSED
Track C        architectural gap CONFIRMED; this ADR is the next artifact
```

---

## Decision log

| Topic | Decision |
|-------|----------|
| Nature of gap | Architectural: missing generation-aware monotonic bootstrap protocol |
| Four seams | Projections of the same gap; not four independent product bugs |
| ADR-0053 | KEEP; do not enlarge |
| ADR-0056 / Conference runtime | KEEP; do not merge |
| Primary role | Bounded coordination role; not infrastructure; not sole state |
| Follower invalidation | Local stale abandon allowed; authority usurpation forbidden |
| Prerequisites / gates | Allowed; swallowing intent forbidden |
| Q1 origin | A ACCEPTED: only bootstrap-origin authority may originate; others learn/verify/align/forward; generation ≠ election |
| Q2 successor origin | A ACCEPTED: mint only if no legitimate Current, or after origin-side invalidation (ADR-0053); still-elected is not enough; no cluster ACK |
| Q3 supersede instant | A ACCEPTED: G2 origin ⇒ G2 Current, G1 superseded immediately; lagging execution is incomplete knowledge; STALE is local knowledge |
| Q4 fact semantics | A ACCEPTED: fact attests generation identity + current/successor relation; SAME/SUPERSEDED/INSUFFICIENT; not roster/media/ICE/trace; transport deferred |
| Q5 obtain / verify | A ACCEPTED: origin makes Fact observable/requestable; follower request/receive/verify/forward; forward ≠ originate; fail → UNKNOWN; no roster/ICE/trace/election/media/path substitute; route change MUST NOT mint generation; transport still deferred |
| Q6 UNKNOWN conduct | ACCEPTED (改 A): UNKNOWN legal, not a Current; attempt must progress or explicit terminal; MUST NOT originate from UNKNOWN alone / promote / SAME / fake SUPERSEDED / consume intent / retry-as-progress / forward unverified Fact; MAY request/verify/forward verified Fact / retain intent / gate emission / isolate execution; exit via verified Fact (SAME / SUPERSEDED / no-local → PENDING admission), Q2-qualified origin, or attempt terminal; no local session ≠ no Current; terminal ≠ channel dead |
| Q7 competing Facts | ACCEPTED (改 A): two verified Facts ≠ two Currents; idempotent iff identity+predecessor+origin+digest; else GENERATION_IDENTITY_CONFLICT; successor by verified chain only; missing middle → INSUFFICIENT_ORDER; BRANCH_CONFLICT on two successors of one predecessor; predecessor stays unique; resolvesConflictSet displaces claims; Superseded vs InvalidatedClaim (or DISPLACED_… without forged legality); CONFLICT = UNKNOWN gating + audit evidence; no SAME/admission/new side effects; quarantine not sync media teardown (ADR-0008); only Q2-qualified origin authority mints resolution; no LWW/timestamp/vote/election-as-Fact |
| Q8 intent vs binding | ACCEPTED (改 A): Intent identity persists; Admission Binding is generation-scoped and fenced on CONFLICT; Resolution atomically fences old bindings and installs one Current binding; no duplicate Intent; delayed old completion cannot finish new binding; late covered candidates rejected; uncovered new branch re-enters CONFLICT (same Intent, request new Resolution); conflict-blocked ≠ terminal; Resolution ≠ SAME/complete; GBC-4 gates remain |
| Q9 sufficiency | ACCEPTED (改 A): GBC-1…7 insufficient alone; add GBC-8 distributed Fact availability (holders re-provide; Origin not perpetual server); GBC-9 non-resurrection but identity collision still Q7; GBC-10 attempt terminal fences Binding, Intent may dormant, intent terminal separate, dedupe reactivation; then SUFFICIENT; Acceptance #1 PASS; no impl |
| Status stamp | ACCEPTED — CONTRACT ONLY (2026-08-27); Implementation/Field still NOT AUTHORIZED |
| Follower invalidation | Must land in fact-advancable UNKNOWN/PENDING or terminal fail; not generationless retry |
| GBC-7 | Same-generation lag allowed; not a new readiness ADR; no contradictory generation truth |
| Impl / field | NOT AUTHORIZED (ACCEPTED is contract-only) |
