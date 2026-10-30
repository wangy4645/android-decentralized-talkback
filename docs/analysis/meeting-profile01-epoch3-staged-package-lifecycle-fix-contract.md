# Meeting Profile01 — Epoch3 STAGED Package Lifecycle Fix Contract

**Parent ADR:** `talkback/docs/adr/0058-conference-multicast-media-group.md` §Q7  
**Impl profile:** `talkback/docs/analysis/0058-implementation-profile-01-control-crypto.md` §Recipient verification and staging  
**RCA basis:** Epoch3 rotation field evidence — `talkback/logs/slice1-latejoin-field-20260920-151925/` (session `e8fced3c-469b-4415-8356-fb74d61efa6a`; M03 @15:21:34)

**Status:** **ACCEPTED — CONTRACT ONLY**  
**Implementation:** **NOT AUTHORIZED**  
**Field verification:** **NOT AUTHORIZED**

```text
Epoch3 RCA                         CLOSED enough for fix contract

Root cause:
  Q7 future-context package lifecycle missing STAGED / deferred completion owner

Primary fix (contract):
  E3-A STAGED + membership catch-up drain     AUTHORIZED FOR CONTRACT ONLY

Potential downstream:
  E3-B peer member-succession hook            CONDITIONAL / NOT AUTHORIZED

Path B fresh republish                        FALLBACK / NOT CURRENT SLICE

Implementation                                NOT AUTHORIZED
Tests                                         NOT AUTHORIZED
Field                                         NOT AUTHORIZED
```

**Authorizes:** contract definition for Q7 `ENCRYPTED_STAGED` package lifecycle and
membership catch-up drain on peer wire ingress — **E3-A only**.

**Does NOT authorize:** implementation · unit/desk execution · field rerun · validator
relaxation · TX fence relaxation · generic rotation framework · Path B host republish slice ·
E3-B production code without IG-2 proof.

---

## 1. Frozen root-cause chain (Epoch3)

```text
M04 joins
→ legitimate membership v1→v2 / mediaKeyEpoch 2→3

epoch3 key reaches M03 before membership v2
→ future context recognized only as mismatch
→ validator correctly refuses installation
→ BUT Q7 STAGED obligation is not represented
→ package discarded

membership v2 later APPLIED
→ dependency is now satisfied
→ no peer rotation-completion owner for retained package
→ no retained package to reverify
→ no guaranteed fresh package delivery
→ supplement@3 absent
→ session/wiring remains epoch2
→ local source successor@3 cannot legally complete
→ stale source state survives into new membership head
→ TX SOURCE_GENERATION_MISMATCH safety fence
→ silence
```

**Root-cause class:** `dependency-before-fact + missing deferred completion owner`  
(single chain — not two independent root bugs)

**Confirmed downstream safety:** `SOURCE_GENERATION_MISMATCH` TX fence is **correct**;
it is a manifestation of incomplete rotation, not a separate defect to relax.

**Confirmed correct behavior:** validator permanent reject when attempting immediate ACTIVE
install without matching membership generation.

**Confirmed gap:** ADR Q7 requires `Package verified, Fact not yet accepted → STAGED`;
peer runtime has no `ENCRYPTED_STAGED` retention or membership catch-up drain for
`MEDIA_KEY_PACKAGE`.

---

## 2. Completion-owner design (C1–C3 — frozen)

### C1 — Ownership

| Layer | Owns STAGED? |
|-------|--------------|
| `MeetingProfile01FactWireIngress` + `Profile01ConferenceMediaFactIngress` (media-key wire ingress) | **YES** |
| `Profile01MembershipConvergenceRegistry` | NO — membership fact chain only |
| `Profile01SessionMediaSupplementRegistry` | NO — ACTIVE supplement material only |
| Shadow wiring / TX seam | NO — downstream consumers |

**Pattern:** symmetric to P1-E `MeetingProfile01IncompleteApplyContinuation` for CREATION,
but scoped to `MEDIA_KEY_PACKAGE` and `ENCRYPTED_STAGED` ciphertext — not decrypted secret.

**Minimum retained identity:**

```text
conferenceId
conferenceEpoch / membershipVersion / mediaKeyEpoch   // wire-declared future context
membershipFactDigest                                  // bound target digest
packageIdentity + factDigest
recipientModuleId + recipientKeyVersion
signedFactBytes                                       // full authenticated package
processingState = ENCRYPTED_STAGED
```

**Scope fence:** `conferenceId` + `recipientModuleId`; primary dedupe
`(conferenceId, mediaKeyEpoch, membershipFactDigest, recipientModuleId, packageIdentity)`.
Membership apply triggers **drain/reverify only** — it does not hold staged bytes.

### C2 — Path selection

| Path | Role |
|------|------|
| **Path A** — retain → membership catch-up → reverify → supplement install → existing completion chain | **Contract-primary** — natural Q7 implementation |
| **Path B** — reject/defer → membership catch-up → authoritative fresh republish → normal ingest | **Fallback only** — not current slice; requires separate host republish obligation and ledger semantics (pre-bind `isPublished` skip is insufficient today) |

Path B MUST NOT be implemented as a substitute for Path A or as a parallel peer recovery
mechanism.

### C3 — STAGE vs ACTIVATION eligibility

**STAGE eligibility** (retain as `ENCRYPTED_STAGED`):

```text
future-looking relative to current membership convergence head
AND Q4 Owner signature verifies
AND package schema decodes
AND recipient / establishment binding structurally valid
AND NOT stale / branch / invalid (see permanent reject below)
```

Future-looking uses monotonic authority signals (`membershipVersion` and/or `mediaKeyEpoch`
advance relative to local head) **as necessary but not sufficient** classification input.
Numeric `>` alone MUST NOT authorize activation.

**ACTIVATION eligibility** (transition STAGED → decrypt → supplement ACTIVE):

```text
wire.conferenceEpoch        == current authoritative generation.conferenceEpoch
wire.membershipVersion      == current authoritative generation.membershipVersion
wire.mediaKeyEpoch          == current authoritative generation.mediaKeyEpoch
wire.membershipFactDigest   == current authoritative generation.generationFactDigest
AND full impl-profile recipient verification steps 4–10 pass
```

A package MAY be STAGED while membership head still names an earlier generation. It MUST NOT
activate until the authoritative membership head **exactly matches** the package's declared
full generation context. This prevents activation of numerically newer but authority-unaccepted
candidate contexts.

**Permanent REJECT (never enter staged ledger):**

```text
STALE          wire version/epoch behind local head
BRANCH         same version/epoch, incompatible digest / competing successor
INVALID        signature, schema, factDigest, recipient, establishment failures
REMOVED        module not in accepted view at head (when determinable)
commitment mismatch when determinable without accepted fact
```

**Do NOT** convert all `MEMBERSHIP_CONTEXT_MISMATCH` outcomes into deferred staging.
Classifier MUST split `AHEAD_OF_CONVERGENCE` (stage-eligible) from
`STALE | BRANCH | INVALID` (permanent reject).

**Supersession / eviction** (from membership authority ordering — no TTL as correctness):

```text
membership head advances to generation G
  → evict staged where staged.membershipFactDigest != G.generationFactDigest
  → evict staged where staged.mediaKeyEpoch < G.mediaKeyEpoch

branch conflict recorded
  → quarantine; staged packages on losing branch never activate

same slot, different packageIdentity
  → KEY_PACKAGE_IDENTITY_CONFLICT (Q7: MUST NOT move package across candidate facts)

Owner revises candidate before origin (ADR Q7)
  → prior staged MUST NEVER activate

local convergence to higher legal head with staged no longer matching
  → discard (same semantic family as IncompleteApplyContinuation.discardBelowMediaKeyEpoch)
```

---

## 3. Fix contract (FC-1 — FC-6)

### FC-1 — Ownership

Package staging ledger lives at **media-key wire ingress** (`MeetingProfile01FactWireIngress`
ingress path into `Profile01ConferenceMediaFactIngress`).

Membership apply triggers **drain only** — not retention storage.

`Profile01SessionMediaSupplementRegistry` remains **ACTIVE-only** post-decrypt material.

Shadow wiring, TX seam, and membership convergence registry MUST NOT absorb STAGED bytes.

### FC-2 — Primary path (Path A / Slice E3-A)

On `MEDIA_KEY_PACKAGE` ingress after Q4 verify:

1. Classify membership context outcome: `AHEAD | STALE | BRANCH | INVALID | MATCH`.
2. `MATCH` → existing direct decrypt/install path (no staging).
3. `AHEAD` + structural validity → retain `ENCRYPTED_STAGED`; MUST NOT unwrap/decrypt/activate.
4. On membership converge to generation **exactly matching** staged context → reverify full
   pipeline (impl profile steps 4–10) → supplement install → invoke existing completion chain:
   `onSupplementReadyForConference` → `onMeetingProfile01MediaSupplementReady` → downstream
   machinery already wired in `TalkbackRuntimeFactory`.

Membership catch-up drain MUST NOT require host fresh republish when a valid staged package
exists for the converged generation.

### FC-3 — Safety

- `STALE | BRANCH | INVALID` → permanent reject; no staged ledger entry.
- STAGE vs ACTIVATION gates per §2 C3 — no numeric-only activation.
- Supersession per membership ordering; **no TTL as correctness mechanism**.
- Prior staged packages MUST NEVER activate if Owner revises candidate semantics (ADR Q7).
- Staged material MUST NOT overwrite valid ACTIVE key or roll back membership convergence.

### FC-4 — Completion chain reuse (peer)

After staged → ACTIVE, reuse existing hooks without a peer `replayHostMembershipWiring` clone:

```text
supplementRegistry install
→ onSupplementReadyForConference
    (drain CREATION incomplete + maybeMaterializeSessionAtMembershipHead
     + replaySessionStarted + onCreationWireApplied)
→ onMeetingProfile01MediaSupplementReady
    (commitLocalSource + publishSourceDeclaration + host shadow projection if applicable)
→ existing member binding / TX machinery
```

**Do NOT** add a parallel peer orchestrator duplicating host membership wiring replay.

**Slice E3-B (conditional — see §4):** a narrow `replace → ready` member-succession hook
is **not** part of FC-2 primary obligation.

### FC-5 — Fallback (Path B — not current slice)

Host rotation-completion republish when a peer had verify-reject and **no** retained staged
package MAY be defined in a future contract slice. Requires:

- explicit obligation distinct from pre-bind fanout skip (`publicationLedger.isPublished`);
- delivery-complete semantics that treat verify-reject as incomplete, not satisfied pre-bind publish.

Path B is **NOT AUTHORIZED** for implementation alongside E3-A.

### FC-6 — Explicit exclusions

```text
Validator relaxation (accept mismatch as ACTIVE)              FORBIDDEN
TX fence / SOURCE_GENERATION_MISMATCH relaxation              FORBIDDEN
commitLocalSource-only shortcut skipping supplement chain     FORBIDDEN
Generic rotation framework                                    FORBIDDEN
E3-B production code without IG-2 proof                       FORBIDDEN
Field rerun / Slice 1 re-adjudication                         NOT AUTHORIZED
WiFi recovery / RNA-5 / ADR-0038 domain changes               OUT OF SCOPE
```

---

## 4. Implementation slices

### Slice E3-A — REQUIRED

```text
Q7 STAGED package lifecycle
+ membership-context classification (AHEAD vs STALE/BRANCH/INVALID)
+ membership catch-up drain
+ existing supplement completion chain (no new orchestrator)
```

This slice closes the **field-proven first breakpoint**:

```text
future epoch3 package arrives
→ no STAGED retention
→ membership catches up
→ no package available
→ no supplement@3
```

### Slice E3-B — CONDITIONAL

```text
peer member-succession completion hook (replace → ready at membership head)
ONLY IF desk/unit execution after E3-A proves existing completion chain
cannot reach replace → ready correctly
```

**Rationale:** field never executed `staged drain → supplement@3 ACTIVE → session rotate@3 →
media_supplement_ready → peer succession`. C4 identified a **static potential downstream gap**
(`replayAuthoritativeMemberBindingsAtHead`-class replace before ready on peer). That gap is **not
proven blocking** until E3-A is exercised.

**E3-B MUST NOT** be bundled into E3-A implementation authorization.

---

## 5. Implementation gates

### IG-1

Implement **E3-A first** only:

- staging ledger;
- AHEAD / STALE / BRANCH / INVALID classification;
- membership catch-up drain into existing supplement completion chain.

No E3-B code in the same production change set unless IG-2 is separately satisfied first.

### IG-2

Before **E3-B** production code:

Prove by **unit or desk execution** that after staged → ACTIVE + supplement completion,
the existing peer succession path **cannot** complete rotation (e.g. `replacePending` /
`SOURCE_GENERATION_MISMATCH` persists with supplement@N ACTIVE and session rotated).

If existing chain succeeds after E3-A, **E3-B is NOT AUTHORIZED**.

Desk/unit execution for IG-2 is authorized only **after** E3-A implementation authorization
— not by this contract document alone.

---

## 6. Contract fixtures (required before implementation authorization)

These fixtures MUST pass at unit/desk layer before field authorization. **Not authorized to
implement now** — listed as contract obligations only.

### A — KEY before MEMBERSHIP (primary Epoch3 class)

```text
KEY(epoch N+1) arrives while membership head == N
→ STAGED (ENCRYPTED_STAGED)

MEMBERSHIP(N+1) applied; generation exactly matches staged context
→ drain → reverify → ACTIVE → supplement@N+1
→ session rotate@N+1 → media_supplement_ready
→ rotation completion (source/wiring/TX at head)
```

### B — MEMBERSHIP before KEY (direct path)

```text
MEMBERSHIP(N+1) applied first
→ KEY(N+1) arrives with matching generation
→ direct ACTIVE path; no staging required
→ same completion chain as today when verify MATCH
```

### C — Supersession — staged candidate never activates

```text
Package staged for candidate A context
→ authority converges to candidate B / higher head incompatible with A
→ A never activates; staged entry evicted or quarantined per §2 C3
```

### D — Stale / branch / invalid never staged

```text
stale package (behind local head)           → permanent reject; ledger empty
branch conflict package                     → permanent reject or quarantine; never STAGED→ACTIVE
malformed / bad signature / wrong recipient → permanent reject
```

---

## 7. Idempotence requirement (contract-level)

Same authenticated package lifecycle MUST be idempotent:

```text
first receive (AHEAD)           → STAGED
duplicate receive               → IDEMPOTENT_STAGED (no second ledger slot)

membership catch-up + drain     → ACTIVE (single supplement install)
duplicate drain / replay        → IDEMPOTENT_ACTIVE

post-ACTIVE duplicate wire      → IDEMPOTENT_ACTIVE (no re-decrypt side effects)
```

**MUST NOT occur on duplicate or replay:**

```text
double-install supplement at same (conferenceId, mediaKeyEpoch)
double-rotate session / wiring at same mediaKeyEpoch
double-increment sourceGeneration / double local successor commit
```

Drain MUST follow **consume-before-re-entry** semantics (same family as P1-E
`detachForSupplementReady`) so membership catch-up cannot recursively re-enter activation.

---

## 8. Observability (contract minimum)

E3-A implementation MUST emit distinguishable outcomes (names illustrative):

```text
PACKAGE_STAGED
PACKAGE_STAGE_REJECTED_<STALE|BRANCH|INVALID>
PACKAGE_STAGE_SUPERSEDED
PACKAGE_STAGE_DRAIN_ATTEMPT
PACKAGE_ACTIVATED
PACKAGE_ACTIVATE_IDEMPOTENT
PACKAGE_STAGE_IDEMPOTENT
```

Wire ingress outcome MUST distinguish `INCOMPLETE_PENDING` / staged retain from
`REJECTED_VERIFY` permanent reject — mirroring CREATION P1-E observability split.

---

## 9. Related artifacts (read-only)

| Artifact | Role |
|----------|------|
| `meeting-profile01-p1-e-creation-incomplete-apply-continuation-authorization.md` | Retain/drain pattern reference (CREATION) |
| `meeting-profile01-pre-bind-fact-retention-drain-design-slice.md` | Pre-bind retention boundary |
| `Profile01HostLocalSessionFactProjection.replayHostMembershipWiring` | Host completion reference — not to clone on peer |
| `Profile01SourceSuccessionCompletionTest` | Host succession proof — IG-2 peer baseline |

---

## 10. Authorization summary

| Item | Status |
|------|--------|
| This fix contract document | **ACCEPTED — CONTRACT ONLY** |
| Slice E3-A implementation | **NOT AUTHORIZED** |
| Slice E3-B implementation | **CONDITIONAL — NOT AUTHORIZED** |
| Path B host republish slice | **NOT AUTHORIZED** |
| Unit / desk fixtures A–D | **NOT AUTHORIZED** (defined only) |
| Field rerun | **NOT AUTHORIZED** |

**Next authorized step (when explicitly requested):** implementation authorization for E3-A
only, preceded by fixture definition review — not by this document alone.
