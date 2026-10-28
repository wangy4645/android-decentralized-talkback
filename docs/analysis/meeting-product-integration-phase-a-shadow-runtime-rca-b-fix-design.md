# Phase A Shadow Runtime — RCA-B Fix Design (PR-PA-SR-B1)

**Parent RCA:** `meeting-product-integration-phase-a-shadow-runtime-rca-b-handoff.md`  
**Field evidence:** `talkback/logs/phase-a-shadow-runtime-20260909-111359/` (post PR-PA-SR-A1)  
**Status:** **DESIGN FROZEN** (2026-09-09)  
**Implementation:** **AUTHORIZED** — `meeting-product-integration-phase-a-shadow-runtime-pr-pa-sr-b1-implementation-authorization.md`

```text
Meeting J0               CLOSED / FIELD-PROVEN
Profile01 Meeting chain  CLOSED / FIELD-PROVEN

RCA-A / PR-PA-SR-A1    CLOSED · PA-SR1/SR2 FIELD VERIFIED (peer)
RCA-B                  CLOSED (read-only) · TYPE A
PR-PA-SR-B0            CLOSED / UNIT-PROVEN
Pre-auth gate          A / PASS
PR-PA-SR-B1            CLOSED / UNIT-PROVEN · FIELD FAIL (origin gate)
B1-R1                  CLOSED / UNIT-PROVEN
PA-SR3                 AUTHORIZED FOR FIELD RE-RUN
PA-SR4…SR6             HOLD (downstream)
J0 / P1-C / P1-E       UNCHANGED_CLOSED

ADR-0056                 KEEP
Meeting media cutover    NOT AUTHORIZED
```

---

## B1 contract goal (one sentence — frozen)

> 对已建立的 Meeting Profile01 session，为每个需要进入 shadow media runtime 的 remote member 产生 authoritative `SOURCE_DECLARATION`，经现有签名事实传输和 Profile01 ingress apply 后建立 `registry.member` binding，使既有 `MEMBER_MEDIA_READY` hook 能消费真实 binding。

### B1 responsibility boundary (non-goal)

B1 **does not** own “make `MEMBER_MEDIA_READY` APPLIED.” That is a **downstream acceptance signal**, not the PR contract.

B1 stops at:

```text
authoritative source state
→ SOURCE_DECLARATION artifact
→ signed factType=9
→ CONFERENCE_SIGNED_FACT transport
→ verify / apply
→ registry.publishMember
```

Existing shadow hooks consume the registry product. **No hook semantics change in B1.**

### Frozen authority boundaries (design sign-off)

```text
SOURCE truth   ≠ ICE connectivity
SOURCE origin  = self-declaration (per module)
B1 completion  = registry.publishMember
```

These boundaries prevent inverting Profile01 authority merely to make shadow hooks pass.

---

## Implementation authorization — pre-auth gate (REQUIRED · NOT YET SIGNED OFF)

**Status:** `PR-PA-SR-B1` remains **DESIGN FROZEN / IMPL NOT AUTHORIZED**.

Structural co-location (`authority[4]` used for both key lookup and `senderModuleId` decode) **does not** constitute a signed-off security contract. Self-declaration must be **receiver-enforceable**, not an origin coding convention.

The **implementation authorization document** must record exactly one outcome — no third path:

```text
PRE-AUTH RESULT

A. PASS
   Proven: receiver enforces executable binding
     authenticatedSignerModuleId == declaration.senderModuleId
   → PR-PA-SR-B1 implementation MAY be authorized

B. NOT PASS
   Current verify chain cannot explicitly prove signer identity binds to senderModuleId
   → authorize narrow trust/validate fence PR first (independent of SourceOriginBridge)
   → fence PASS + B1-2 hard test → then PR-PA-SR-B1 implementation MAY be authorized
```

**Do not** authorize `MeetingProfile01SourceOriginBridge` (or any B1 origin slice) until **PRE-AUTH RESULT = A** is signed off, or **B** has completed with fence PASS.

### Pre-auth read-only audit (2026-09-09) — informs but does not sign off

| Check | Finding |
|-------|---------|
| Trust lookup module | `Profile01ProductionSignedFactTrustBoundary` resolves signer from `authority[SIGNER_KEY_SOURCE_DECLARATION]` (index **4**) |
| Declared sender module | `Profile01WireCborDecoder.decodeSourceDeclarationMember` reads `senderModuleId` from `authority[4]` |
| Structural co-location | Same CBOR field — **insufficient alone for PRE-AUTH PASS** |
| Explicit semantic assert | **Absent** in `validateMember`; `Verified` carries digest only, not authenticated signer module id |
| Origin build-time fence | `Profile01MediaKeyPackageBuilder` has `ownerModuleId == signer.signerModuleId`; no SOURCE encoder equivalent yet |

**Current audit posture:** **PRE-AUTH RESULT = B (NOT PASS)** — do not reinterpret as PASS.

**Unblock path:** `PR-PA-SR-B0` — see `meeting-product-integration-phase-a-shadow-runtime-rca-b0-fence-design.md`.

### B1-2 — owned by B0 (reference only in B1)

Impersonation reject tests (**T2/T3** in B0) close pre-auth. B1 does **not** re-litigate fence; B1 requires **PRE-AUTH RESULT = A** signed after B0 merge.

---

## Failure chain (RCA-B — frozen)

```text
MEMBER_MEDIA_READY
→ factPort.memberBinding(sessionId, remoteModuleId)
→ registry.member(conferenceId, remoteModuleId) == null
→ DEFERRED_NO_BINDING

Because:
SOURCE_DECLARATION never originated (product)
→ never factType=9 ingress
→ never registry.publishMember
```

**Out of B1 re-investigation:** `mediaConnectedModules` ordering, generic deferred replay, host `SESSION_STARTED` origin replay.

---

## Design cut 1 — SOURCE origin authority

### Who may declare?

| Rule | Decision |
|------|----------|
| **Declaring module** | **Self only** — `authority.senderModuleId` / wire `moduleId` must equal the **local module** that signs the fact |
| **Signer key** | `SIGNER_KEY_SOURCE_DECLARATION` on the **declaring module** (trust boundary already enforces this shape) |
| **Host CREATION authority** | **Does not** extend to declaring remote members’ sources |
| **Remote on behalf** | **Forbidden** — host cannot sign M02’s SOURCE; M02 cannot sign M01’s |

```text
M01 origin  → declares moduleId=M01  → fanout to peers
M02 origin  → declares moduleId=M02  → fanout to peers (incl. host)
```

### When does source become declarable?

Declarable **only** when all hold:

| Gate | Requirement |
|------|-------------|
| **G1 — session wire context** | Local session has bound `conferenceId` + applied CREATION context (`membershipVersion`, `mediaKeyEpoch`, `conferenceEpoch`) readable from creation/supplement chain |
| **G2 — roster membership** | Declaring `moduleId` ∈ CREATION membership view (validator `hasMember`) |
| **G3 — local source truth** | Authoritative **local** RTP source identity committed: `ssrc`, `sourceInstanceId` (id128), `mediaGroupDescriptorDigest` — from conference media provision / encoder allocation seam, **not** synthesized at ICE CONNECTED |
| **G4 — material** | `Profile01ConferenceMediaKeyMaterialAuthority.materialForSession(sessionId)` present (same class of gate as MEDIA_KEY_PACKAGE origin) |

**Anti-pattern (frozen out):**

```text
ICE CONNECTED → invent SSRC/sourceInstanceId → sign SOURCE   ❌
```

ICE / media-ready may coincide in time, but **ICE state is not the authority** that creates source truth.

### Epoch / generation fences

| Field | Fence |
|-------|-------|
| `conferenceEpoch` | Must match current membership convergence |
| `membershipVersion` | Must match current membership convergence |
| `mediaKeyEpoch` | Must match current membership convergence |
| `sourceGeneration` | Monotonic per `(conferenceId, moduleId)`; stale generations → `STALE_SOURCE_GENERATION` (existing validator) |
| `moduleId` | Must be in roster at apply time (`MEMBER_NOT_IN_ROSTER` otherwise) |

Apply path unchanged: `ingestSourceDeclarationSignedFact` → `validateMember` → `publishMemberDeclaration` → `registry.publishMember`.

---

## Design cut 2 — SOURCE identity (frozen fields)

Wire / registry identity for one member binding:

| Field | Role |
|-------|------|
| `conferenceId` | id128 hex registry key |
| `moduleId` | Sender / binding owner (`senderModuleId` on wire) |
| `sourceGeneration` | Monotonic source incarnation; maps to `membershipIncarnationId` in `AuthoritativeConferenceMediaMemberDeclaration` |
| `sourceInstanceId` | id128 stable source instance (wire authority field 6) |
| `ssrc` | RTP SSRC bound into admission derivation |
| `mediaGroupDescriptorDigest` | 32-byte digest (wire authority field 9) — part of `Profile01SourceAdmissionDeriver` input |
| `membershipVersion` | Profile01 membership generation (CREATION v0 at origin) |
| `mediaKeyEpoch` | Media key epoch from CREATION / supplement chain |
| `conferenceEpoch` | Conference generation from CREATION context |
| `sourceAdmissionKey48` | **Derived at decode** — not signed independently; must match deriver output |

### Replacement / rejoin posture (B1 scope)

| Event | Identity rule |
|-------|----------------|
| **First declare** | `sourceGeneration = 1` (or first publishable generation per module policy — must be > 0 for `membershipIncarnationId`) |
| **Replace / new source instance** | **Bump `sourceGeneration`**; new `sourceInstanceId` + `ssrc` if media layer reallocates |
| **PA-SR5 rejoin** | **Out of B1** — do not design rejoin re-declare semantics here; only ensure monotonic generation prevents silent reuse of stale bindings |

Registry read: `memberMatchesSession` requires aligned `membershipVersion` + `mediaKeyEpoch` between session and member control facts.

---

## Design cut 3 — Origin trigger & build seam

### New components (design names — mirror CREATION / package pattern)

```text
Profile01SourceDeclarationAuthoritySnapshot   (canonical fact payload inputs)
Profile01WireCborEncoder.encodeSourceDeclarationFullFact   (GAP today — in B1 scope)
MeetingProfile01SourceOriginPublisher         (sign with module SOURCE key)
MeetingProfile01SourceOriginBridge            (build-once, per-peer publication ledger)
```

Factory wiring: `TalkbackRuntimeFactory` + `TalkbackCoordinator` attach hook (parallel to creation/package bridges).

### Trigger contract

**Build trigger (creates artifact once per generation):**

```text
onLocalConferenceSourceCommitted(
  sessionId,
  ssrc,
  sourceInstanceId,
  mediaGroupDescriptorDigest,
)
```

Sourced from **conference media provision** when local send path has committed RTP source identity. Coordinator/media layer emits this; bridge **does not** subscribe to ICE state enums directly.

**Publication trigger (fanout only — does not rebuild truth):**

```text
onEligiblePeers(…)   // same eligibility surface as CREATION replay / MEDIA_KEY_PACKAGE
```

Called when eligible peer set changes or session bind completes — **re-sends cached signed bytes** to peers not yet in `publishedToPeers` ledger (CREATION bridge pattern).

```text
built (local source committed)
  ≠ published (per-peer send)
  ≠ peer-applied (WIRE_INGRESS APPLIED)
```

### Per-direction field minimum (PA-SR3)

For host-create + peer-join topology:

| Observer | Needs remote SOURCE applied |
|----------|------------------------------|
| M02 (peer) | M01’s self-declared SOURCE |
| M01 (host) | M02’s self-declared SOURCE |

B1 must enable **both self-origins** for bilateral shadow binding; not host-only fanout.

---

## Design cut 4 — Publication / replay / pre-bind

### Transport

**Reuse** existing `CONFERENCE_SIGNED_FACT` + `MeetingProfile01FactWireIngress` factType=9 route. **No new transport.**

### Pre-bind retention (frozen — do not expand)

Per `meeting-profile01-pre-bind-fact-retention-drain-design-slice.md` v1 boundary:

```text
factType=9 + no session binding
  → PRE_BIND_NOT_RETAINED reason=UNAUTHORIZED_FACT_TYPE
  → NOT retained
```

**B1 does not authorize SOURCE pre-bind retention** even if field timing would benefit. If pre-bind loss becomes field-proven after B1, escalate as **separate** design/ADR — not a B1 field-PASS shortcut.

### Post-bind ordering scenarios

| Scenario | B1 behavior |
|----------|-------------|
| SOURCE arrives after session bind | Normal apply → `registry.publishMember` → wire `replayAfterPublish(SOURCE)` per existing ingress (gated by `mediaConnectedModules` for wire replay only) |
| SOURCE arrives before bind | **Drop** (pre-bind policy) — peer must receive **republication** after bind via eligible-peer fanout |
| Product ICE hook fires before SOURCE applied | `MEMBER_MEDIA_READY` → `DEFERRED_NO_BINDING` (expected until apply) — **no B1 retry obligation** |
| Product ICE hook fires after SOURCE applied | Existing hook should succeed if `memberBinding` non-null — **acceptance evidence for PA-SR3** |

**Wire replay vs product hook (unchanged):**

- Wire ingress: `onConferenceMemberMediaReady` only if `moduleId ∈ mediaConnectedModules`
- Product ICE path: unconditional hook (RCA-B closed — not B1 problem)

B1 does **not** add generic “replay when media connected” worker.

### Republication

Mirror CREATION / package ledger shape:

- `publishedToPeers` ledger per `(sessionId, sourceGeneration, factDigest)`
- `replayProfile01SourceToEligiblePeers` (or sibling) on same eligibility events as CREATION replay — **send only**

**Unlike MEDIA_KEY_PACKAGE:** post-bind republication **must re-send the cached signed SOURCE bytes** for the same `(sourceGeneration, factDigest)`. **No fresh artifact** unless `onLocalConferenceSourceCommitted` advances generation (replacement).

```text
republication  → byte-identical signedFactBytes replay
replacement    → new sourceGeneration / sourceInstanceId → new signed artifact
```

**B1-6** locks byte-identical republication.

---

## Design cut 5 — Apply → consumption chain

### Registry write path (existing — no change)

```text
ingestSourceDeclaration
  → publishMemberDeclaration
  → registry.publishMember
```

### Registry read path (existing — no change)

```text
MeetingProfile01AwareConferenceSessionMediaFactPort.memberBinding
  → registry.session + registry.member
  → adapter.memberMatchesSession
```

### Shadow consumption (existing — no change)

```text
ConferenceSessionMediaCoordinatorDelegate.onConferenceMemberMediaReady
  → factPort.memberBinding
  → ConferenceSessionMediaBridge.installMember
```

B1 **must not** weaken `memberBinding` gates or bypass registry.

---

## PR-PA-SR-B1 — frozen scope

### Implementation slice (narrow — when authorized)

```text
PR-PA-SR-B1
├─ encodeSourceDeclarationFullFact
├─ MeetingProfile01SourceOriginBridge
├─ onLocalConferenceSourceCommitted
├─ per-peer publication / post-bind republication (byte-identical replay)
├─ factType=9 origin OBS (PROFILE01_ORIGIN_EMIT)
└─ focused unit tests (B1-1 … B1-7)
```

```text
PR-PA-SR-B1

IN
- SOURCE_DECLARATION production origin authority (self-declare only; B0 fence on receive)
- Profile01SourceDeclarationAuthoritySnapshot + encodeSourceDeclarationFullFact
- MeetingProfile01SourceOriginPublisher + SourceOriginBridge
- per-peer publication ledger + eligible-peer republication (cached bytes)
- onLocalConferenceSourceCommitted trigger seam (media provision → bridge)
- existing CONFERENCE_SIGNED_FACT send + factType=9 ingress apply (no ingress rewrite)
- unit tests B1-1 … B1-7 (below)
- field OBS: PROFILE01_ORIGIN_EMIT factType=9 (minimal)

OUT
- SOURCE pre-bind retention
- generic deferred replay / retry framework
- host SESSION_STARTED origin replay (RCA-A host residual)
- mediaConnectedModules semantic change
- MEMBER_MEDIA_READY hook behavior change
- PA-SR4 ingress/playout repair
- PA-SR5 rejoin semantics repair
- PA-SR6 teardown repair
- J0 / P1-C / P1-E contract changes
- ADR-0056 cutover / AudioTrack ownership change
```

### Likely touch surfaces (implementation hint only)

| File / area | Change class |
|-------------|--------------|
| `Profile01WireCborEncoder.kt` | Add `encodeSourceDeclarationFullFact` |
| New `MeetingProfile01SourceOriginBridge.kt` | Build-once + per-peer ledger |
| New `MeetingProfile01SourceOriginPublisher.kt` | Sign with SOURCE module key |
| `TalkbackRuntimeFactory.kt` | Wire bridge + publisher |
| `TalkbackCoordinator.kt` | `onLocalConferenceSourceCommitted` + eligible-peer republication |
| Conference media provision seam | Emit committed SSRC / sourceInstanceId (read-only contract in B1; minimal emitter) |
| Unit tests adjacent to `MeetingProfile01FactWireIngressTest` / ingress chain | Apply + binding read |

**No change** to `MeetingProfile01FactWireIngress` replay gates unless a separate audit finds a bug — B1 assumes existing factType=9 path is correct once bytes arrive.

---

## Unit test matrix (frozen — pre-field)

| Id | Property | Expected |
|----|----------|----------|
| **B1-1** | Local module self-origin | Valid SOURCE artifact; signs `moduleId == local` only |
| **B1-2** | *(B0 T2/T3)* | Satisfied by **PR-PA-SR-B0** — B1 references PRE-AUTH = A, does not duplicate unless ingress e2e smoke desired |
| **B1-3** | Same committed source | Stable identity; republication / re-entrant publish does **not** bump `sourceGeneration` |
| **B1-4** | Replacement source | New `sourceGeneration` + new `sourceInstanceId` (and new signed artifact) |
| **B1-5** | Pre-bind send | `PRE_BIND_NOT_RETAINED` — does **not** authorize retention |
| **B1-6** | Post-bind republication | **Byte-identical** `signedFactBytes` replay to second peer / second send — not fresh encode |
| **B1-7** | Peer apply | `WIRE_INGRESS factType=9 APPLIED` → `registry.member` populated → `memberBinding` non-null |

Optional harness-only (not B1 field gate): `onConferenceMemberMediaReady` → `APPLIED` when hook runs after B1-7 apply.

---

## Acceptance criteria

### Field gate — **PA-SR3 only**

Re-run `phase-a-shadow-runtime-first-run.ps1` after B1 impl. **Success chain (authoritative):**

```text
SOURCE self-origin
→ CONFERENCE_SIGNED_FACT factType=9
→ peer WIRE_INGRESS factType=9 outcome=APPLIED
→ registry.member exists
→ MEMBER_MEDIA_READY outcome=APPLIED
→ PA-SR3 PASS
```

**Also require:** PRE-G / PRE-I / PRE-J + J0 prerequisite unchanged; PA-SR1 / PA-SR2 no regression.

**SR4 activity:** save as downstream evidence only — **do not close SR4 on B1 field run**.

### Explicit non-goacceptance (field)

| Step | B1 field run |
|------|----------------|
| PA-SR4 ingress/playout | Observe only — **do not close** |
| PA-SR5 leave/rejoin | **HOLD** |
| PA-SR6 teardown | **HOLD** |
| Full PA-SR1…SR6 PASS | **Not required** |

If SOURCE binding lands and ingress/playout appear opportunistically, capture as **downstream evidence** in field result doc — not B1 closure criteria.

---

## Regression surface (must lock)

### Must PASS after B1

| Case | Expected |
|------|----------|
| Peer receives host self-SOURCE | M02 registry member slot for M01 |
| Host receives peer self-SOURCE | M01 registry member slot for M02 |
| `MEMBER_MEDIA_READY` after apply | `APPLIED` on at least one device per PA-SR3 adjudicator |
| Pre-bind SOURCE datagram | `PRE_BIND_NOT_RETAINED` — unchanged |
| CREATION / package origin | No regression to J0 / P1-C / P1-E chains |
| RCA-A replay | `SESSION_STARTED APPLIED` preserved on peer |

### Must NOT change

| Surface | Constraint |
|---------|------------|
| `memberBinding` read gates | No bypass |
| Pre-bind retention policy | CREATION-only retain |
| ADR-0056 production audio | No cutover |
| Wire ingress factType routing | Reuse existing |

### Must NOT add

- sleep / poll / background retry queue for member hooks
- host signing remote SOURCE facts
- ICE CONNECTED as source truth authority

---

## OBS sufficiency (field)

Existing thin OBS likely **insufficient** for origin proof. B1 may add **minimal** lines only:

```text
PROFILE01_ORIGIN_EMIT session=… factType=9 moduleId=… outcome=… target=…
```

Reuse `MEETING_MEDIA_SHADOW` tag; no new adjudication contract unless PA-SR3 cannot be scored from existing patterns.

---

## Frozen routing

```text
PR-PA-SR-B0        CLOSED / UNIT-PROVEN
Pre-auth gate      A / PASS
PR-PA-SR-B1        CLOSED / UNIT-PROVEN · FIELD AUTHORIZED

PA-SR3             AUTHORIZED FOR FIELD (run card ready)
PA-SR4…SR6         HOLD
J0 / P1-C / P1-E   UNCHANGED_CLOSED
ADR-0056           KEEP
cutover            NOT AUTHORIZED
```

**Active path:** `phase-a-sr3-field-first-run.ps1` → PA-SR3 adjudicator only.

---

## References

- RCA-B handoff: `meeting-product-integration-phase-a-shadow-runtime-rca-b-handoff.md`
- RCA-A design: `meeting-product-integration-phase-a-shadow-runtime-rca-a-fix-design.md`
- Field result (A1): `meeting-product-integration-phase-a-shadow-runtime-field-result-20260909-111359.md`
- Pre-bind policy: `meeting-profile01-pre-bind-fact-retention-drain-design-slice.md`
- Origin gap map: `meeting-profile01-media-key-package-origin-hook-gap-map.md`
- Code: `Profile01ConferenceMediaFactValidator.validateMember` · `Profile01WireCborDecoder.decodeSourceDeclarationMember` · `MeetingProfile01CreationOriginBridge` · `MeetingProfile01MediaKeyPackageOriginBridge` · `ConferenceSessionMediaCoordinatorDelegate`
