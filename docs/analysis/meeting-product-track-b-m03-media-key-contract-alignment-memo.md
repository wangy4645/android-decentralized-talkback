# Track B — M03 Media-Key Contract Alignment Memo

**Status:** CONTRACT ALIGNMENT **+ P1–P4 FROZEN + TRACK B RUN #1 ADJUDICATED** (2026-09-16)  
**Purpose:** Resolve cross-contract gaps blocking Track B adjudication of Run #1 mixed-path  
**Evidence base:** `talkback/logs/rc3-c1q-field-manual-20260916-112211/` (Run #1) · mechanism RCA (established)  
**Final verdict:** `talkback/logs/rc3-c1q-field-manual-20260916-112211/TRACK_B_RUN1_VERDICT.txt`  
**Does NOT:** code change · provisioning change · field run · new qualification · reopen RC-3 · change ADR-0058 GA authorization

```text
ARCHITECTURAL DECISION = COMPLETE (P1–P4 FROZEN)
Track B Run #1 Final Adjudication = COMPLETE / FROZEN
Meeting Audio Asymmetry = Phase A COMPLETE · OPEN / DEFERRED (Phase B NOT AUTHORIZED)
```

```text
MECHANISM RCA (already established — not re-litigated here)

M03 SKIPPED_LOOKUP
← M01 establishment store has no M03 binding
← Phase -1 establishment topology = duo (M01↔M02 only)

NOT signer-trust regression
NOT media-key decrypt failure
NOT multicast transport failure

BY-DESIGN LOCAL BEHAVIOR (code + Profile01 slice):
missing establishment binding → no MEDIA_KEY_PACKAGE → retain/fallback VIA_ANCHOR
```

**Discipline:** This memo does **not** pre-judge Run #1 as setup error, product defect, or authorized GA mixed-path behavior. It only maps frozen contract text to Q1–Q4 and marks what is derivable vs what requires architecture decision.

---

## 1. Contract facts (frozen sources)

### 1.1 GA / N≤9 production envelope

Source: `meeting-product-ga-authorization-review.md` (FROZEN / AUTHORIZED 2026-09-15)

```text
GA DEFAULT:     ADR-0058 multicast Meeting
ROLLBACK:       ADR-0056 Anchor/WebRTC retained
SUPPORTED:      N <= 9 · real same-L3 mesh · >=3 nodes field-proven
                  production cutover / audible ownership · stability >=15 min
OUT OF GA:      N>9 · ADR-0056 deletion · G3b >=2h (retirement only)
```

GA scope table authorizes **N ≤ 9 admitted sources** and **≥3 nodes** under real mesh — it does **not** define establishment provisioning scope or per-peer multicast prerequisite text.

Source: `meeting-product-r2-release-smoke-run-card.md` (R2-A GA default path)

```text
Preflight: 3 nodes online · same real underlay L3 ·
           establishment trust present on M01/M02/M03
Expected:  AUTO_CUTOVER_SUCCEEDED · MULTICAST_ACTIVE · multi-source audio
```

R2-A binds **3-node GA qualification** to **establishment trust on all three modules** — a stronger statement than GA review’s high-level envelope table alone.

### 1.2 Profile01 establishment / provisioning contract

Source: `meeting-profile01-establishment-provisioning-design-slice.md` (SIGNED OFF)

```text
Q1: LOCAL device generates RSA key; provisioning authority approves;
    distributes authorized PUBLIC bindings to all Modules in deployment scope

Q2: Peer bindings enter ONLY via authenticated Profile revision →
    AcceptedLocalEstablishmentTrustStateStore
    FORBIDDEN: HELLO · CREATION · MEDIA_KEY_PACKAGE · signaling · TOFU · signer fallback

Q4 reference topology: bilateral M01 ↔ M02 (not in-session proof)

§4 prohibited shortcuts: NO runtime-generated peer trust · NO signer-key fallback
Violation symptom: ORIGIN_SOURCE_GAP / SKIPPED_LOOKUP = correct fail-closed until provisioned
```

Source: `MeetingProfile01MediaKeyPackageOriginBridge.kt` (implementation mirrors slice)

```text
Does not send unless establishment binding resolves (no signing-key fallback)
SKIPPED_LOOKUP when activeEstablishmentKey(recipient) !is Found
```

Source: `Profile01ProfileBackedRecipientEstablishmentKeyLookup.kt`

```text
Reads only AcceptedLocalEstablishmentTrustStateStore — never signing bindings
UnknownModule when moduleId absent from establishment store
```

### 1.3 ADR-0058 Meeting membership vs media-key admission

Source: `0058-conference-multicast-media-group.md` (Q6 / Q7 / Invite admission)

**Four facts (must not substitute):**

```text
Invite           ≠ Membership ≠ Media Group joined
Membership Fact  = sole admission authority (Q4)
Media Key Package = admitted member obtains media-execution material (Q7)
```

**Orthogonal projections:**

```text
Join Projection:  NOT_INVITED / INVITED / JOIN_REQUESTED / MEMBERSHIP_ACCEPTED
Key Projection:   NO_KEY / STAGED / MEDIA_KEY_PENDING / ACTIVE
```

**Multicast media readiness:**

```text
MEDIA_READY only when:
  Membership Fact accepted AND View contains this Module
  AND matching key is ACTIVE
```

**Key distribution failure (member still in View):**

```text
MEMBERSHIP_ACCEPTED / MEDIA_KEY_PENDING
MUST NOT roll back group Membership
```

**Package without usable binding at host:**

```text
Host cannot wrap/send MEDIA_KEY_PACKAGE without establishment lookup Found
(not stated in ADR-0058 prose; implied by Profile01 Q7 + bridge contract)
```

ADR-0058 **does not** define `VIA_ANCHOR`, WebRTC anchor path, or ADR-0056 CPP projection.

### 1.4 ADR-0056 rollback / CPP projection (adjacent, not GA default)

Source: `0056-cpp-conference-presence-projection-contract.md` · GA review posture

```text
ADR-0056 = retained rollback path · NOT deleted at GA authorization
VIA_ANCHOR = conference presence / per-edge media relation vocabulary (ADR-0056 family)
ADR-0058 GA default ≠ ADR-0056 deletion
```

GA review does **not** freeze “mixed-path Meeting (multicast + VIA_ANCHOR peers) = GA steady-state target.”

### 1.5 RC-3 Phase -1 qualification provisioning

Source: `rc3-c1q-phase-minus1-run-card.md`

```text
Goal: verify Profile01 trust prerequisites on frozen rc.1 DUT (M02)
      before C1Q Meeting/teardown qualification
Phase -1 = environment preparation, NOT C1 test content
Does NOT re-prove: Meeting GA · R2-A · RC-1/RC-2 · teardown contract
M01: FTPH + establishment duo batch
M02: FTPH + establishment + P-1A POST identity
Follow existing operator duo batch — do not invent M02-only variant
```

Source: `establishment-batch/PROVISIONING_MANIFEST.txt` (Phase -1 PASS evidence)

```text
provisioning_topology=duo
M01.establishmentKeyVersion=1
M02.establishmentKeyVersion=1
(no M03.establishmentKeyVersion row)
```

Source: `establishment-batch/PRE_CHECK.txt`

```text
topology=duo
thirdPeerModule=M03
PRE-4 M01_lookup_M02=True
(no M01_lookup_M03)
```

Phase -1 PASS proves **duo establishment chain for C1Q DUT path**, with M03 referenced as third peer in batch metadata but **not** included in duo establishment manifest rows.

### 1.6 Run #1 observable contract-relevant facts (reference only)

Session `6b913faf-df91-4a62-a4b6-68fc268e712b` · invalid for RC-3 C1Q topology (separate RC-3 verdict).

Mechanism timeline on M01 (11:20:53):

```text
target=M02  PROFILE01_ORIGIN_EMIT factType=11 outcome=EMITTED establishmentKeyVersion=1
target=M03  PROFILE01_ORIGIN_EMIT factType=11 outcome=ORIGIN_SOURCE_GAP reason=UNKNOWN_ESTABLISHMENT_MODULE
target=M03  PROFILE01_PACKAGE_POST_BIND_REPUBLISH outcome=SKIPPED_LOOKUP (repeated)
```

M03 presence: `JOINED:VIA_ANCHOR` · shadow `NO_SESSION_FACT` / `DEFERRED_NO_BINDING`  
M01/M02: multicast-side activity · M01 `MULTICAST_ACTIVE`

---

## 2. Cross-contract consistency

### 2.1 Aligned (no contradiction found)

| Topic | Contracts aligned |
|-------|-------------------|
| Establishment source | Deployment / authenticated profile only; not runtime |
| Host wrap gate | No establishment binding → no MEDIA_KEY_PACKAGE |
| Signer vs establishment | Separate stores; signer trust for M03 does not substitute establishment |
| Membership vs key state | ADR-0058 explicitly orthogonal Join vs Key projections |
| RC-3 Phase -1 purpose | Qualification precondition for C1Q duo — not labeled GA deployment sign-off |
| GA default vs rollback | ADR-0058 default + ADR-0056 retained coexist by explicit GA freeze |

### 2.2 Tension / undefined seams (blocks Track B adjudication)

| Seam | Contract A says | Contract B says | Gap |
|------|-----------------|-----------------|-----|
| **Deployment scope definition** | Establishment slice: all Modules in **deployment scope** | Phase -1 / manifest: **duo** subset; PRE_CHECK lists `thirdPeerModule=M03` but no M03 establishment row | Term **deployment scope** never defined vs channel roster vs operator subset |
| **3-node GA vs duo RC provisioning** | R2-A: establishment on **M01/M02/M03** for 3-node GA path | Phase -1 PASS: **M01↔M02 only** | Same devices, different provisioning claims for different qualification tracks |
| **Missing binding + admitted member** | ADR-0058: may stay `MEMBERSHIP_ACCEPTED` + `MEDIA_KEY_PENDING`; not `MEDIA_READY` | Implementation: VIA_ANCHOR + WebRTC playback | ADR-0058 silent on whether anchor fallback is **normative GA steady-state** vs **rollback-only** |
| **Admission guard** | ADR-0058: Owner sole membership authority; no establishment check stated at invite | Profile01: package origin fail-closed | No frozen rule: block invite vs allow join + pending key |
| **GA “ordinary Meeting bug”** | GA review: bugs within GA envelope → production defect | Missing contract for “bug” when provisioning incomplete vs product guard missing | Defect class undefined without Q1–Q3 resolution |

### 2.3 Critical distinction (must not collapse)

```text
Meeting membership admission     (ADR-0058 Membership Fact / Join Projection)
        ≠
ADR-0058 multicast media admission   (Key Projection ACTIVE → MEDIA_READY)
        ≠
ADR-0056 VIA_ANCHOR presence projection   (rollback / legacy media relation)
```

Run #1 may simultaneously satisfy (or fail) each layer independently. Track B adjudication requires which layer’s contract governs the mixed-path observation.

---

## 3. Q1–Q4 decision matrix

Legend:

```text
DERIVED / ALREADY IMPLIED     = frozen text supports one reading; no new ADR needed to read it
PARTIALLY DERIVED             = one sub-question resolved; parent term still open
ARCHITECTURAL DECISION REQUIRED = two+ readings consistent with frozen text; need explicit freeze
```

---

### Q1 — Deployment Scope

> “deployment scope 内所有 Module” 指什么？谁有资格进入 ADR-0058 multicast eligibility？

**Options:**

```text
A. channel / authorized roster 中所有可能参会 Module
B. 本次 operator 明确 provisioning 的 Module 子集
C. 其他
```

**Contract citations:**

- Establishment design slice Q1/Q2: authority distributes bindings to all Modules in **deployment scope** (term undefined).
- ADR-0057 PAP-T1: **deployment trust scope** (signing/provisioning namespace) — related but not mapped to channel roster in prose.
- R2-A preflight: **M01/M02/M03 establishment** required for **3-node GA qualification path**.
- Phase -1 manifest: `provisioning_topology=duo` — operator subset **M01+M02** only.
- PRE_CHECK: `thirdPeerModule=M03` + `topology=duo` — M03 in channel context but **not** in establishment manifest rows.

**Analysis:**

- For **RC-3 C1Q duo qualification**, Phase -1 + manifest **DERIVED / ALREADY IMPLIED** as **Option B subset (M01+M02)**.
- For **R2-A / GA 3-node qualification path**, R2 preflight **DERIVED / ALREADY IMPLIED** that **all three participating modules** must have establishment trust at qualification time — closest to **Option A for that specific GA gate**, but only where R2 card applies, not globally from establishment slice alone.
- For **general production deployment** (N≤9, arbitrary admitted subset), parent term **deployment scope** is **ARCHITECTURAL DECISION REQUIRED** — frozen materials support both A-like (R2) and B-like (duo batch / operator subset) readings without a single normative definition.

**Q1 status (pre-ruling analysis):** PARTIALLY DERIVED + ARCHITECTURAL DECISION REQUIRED  
**P1 ruling:** **A / FROZEN** — see §4

---

### Q2 — Missing Establishment Admission Semantics

> 合法 Meeting member 没有 establishment binding 时，冻结语义是 A/B/C/D 哪个？

**Options:**

```text
A. 可成为 Meeting member，但必须 MEDIA_KEY_PENDING，不得 MEDIA_READY
B. 应在 invite/admission 前被拒绝
C. 可以长期 VIA_ANCHOR，mixed-path 属于正式 GA steady-state
D. 其他
```

**Contract citations:**

- ADR-0058 Join vs Key projections; `MEDIA_KEY_PENDING` for in-View member with key failure; `MEDIA_READY` requires key **ACTIVE**.
- ADR-0058: key distribution failure → **MUST NOT roll back Membership**.
- ADR-0058: no text requiring establishment precondition at Invite / Membership Fact.
- ADR-0058: no text authorizing `VIA_ANCHOR` or steady-state mixed multicast+anchor Meeting.
- GA review: ADR-0058 default + ADR-0056 rollback retained — rollback existence **≠** mixed-path authorized as GA target.
- Implementation Run #1: membership accepted + key path blocked + `VIA_ANCHOR` presence.

**Analysis:**

- **Option A (multicast eligibility half)** — **DERIVED / ALREADY IMPLIED** from ADR-0058 Q6/Q7 for the **ADR-0058 media plane only**:
  - Member may exist in Membership View without ACTIVE key (`MEDIA_KEY_PENDING`).
  - Must not be `MEDIA_READY` / full Profile01 multicast execution without ACTIVE key.
- **Option B (reject at admission)** — **NOT DERIVED**; no frozen ADR-0058 invite/membership gate on establishment binding found.
- **Option C (long-term VIA_ANCHOR = GA steady-state)** — **NOT DERIVED**; ADR-0056 path retained but GA freeze does not promote mixed-path to default production target. **ARCHITECTURAL DECISION REQUIRED** whether C is ever normative vs rollback-only transient.
- **Composite steady-state** (membership accepted + indefinite anchor media while others multicast) — **ARCHITECTURAL DECISION REQUIRED**; consistent with A + implementation fallback, **not** explicitly blessed or forbidden in ADR-0058 GA text.

**Q2 status (pre-ruling analysis):** PARTIALLY DERIVED + ARCHITECTURAL DECISION REQUIRED  
**P2 ruling:** **A / FROZEN** (not B; not C) — see §4

---

### Q3 — Responsibility Boundary

> 谁负责保证 establishment prerequisite？

**Options:**

```text
A. deployment/operator provisioning
B. Meeting runtime
C. admission layer
D. fallback layer
```

**Contract citations:**

- Establishment slice Q1–Q2: provisioning authority distributes bindings; devices hold private keys; **no runtime discovery**.
- Establishment slice §4: prohibited shortcuts include runtime-generated peer trust, TOFU, signaling import.
- ADR-0057 PAP: operational authority + authenticated profile enrollment — deployment-side.
- ADR-0058: Owner responsible for **Membership Facts** and **Media Key Package distribution when able** — not for manufacturing peer establishment keys.
- ADR-0056 / RC1: fallback path exists at implementation layer when Profile01 path incomplete.

**Analysis:**

- **Option B (Meeting runtime auto-establishment)** — **DERIVED EXCLUSION** (contract forbids; not a responsible party).
- **Option A (deployment/operator provisioning)** — **DERIVED / ALREADY IMPLIED** as **primary owner** of establishment binding existence before runtime.
- **Option C (admission layer)** — **NOT DERIVED** as owner; ADR-0058 does not assign admission the job of verifying establishment store on host before Membership Fact. **ARCHITECTURAL DECISION REQUIRED** whether admission **should** guard (product policy), not who currently owns it in frozen text.
- **Option D (fallback layer)** — **DERIVED** as **consumer of failure**, not **provider of prerequisite**; ADR-0056 fallback executes when Profile01 path unavailable — responsibility for **having** binding remains upstream.

**Q3 status (pre-ruling analysis):** PARTIALLY DERIVED + ARCHITECTURAL DECISION REQUIRED  
**P3 ruling:** **A + narrow C / FROZEN** — see §4

---

### Q4 — Qualification Scope Compatibility

> RC-3 Phase -1 duo provisioning：仅 C1Q duo qualification 有效，还是可解释为 N≤9 GA deployment manifest？

**Contract citations:**

- Phase -1 run card: environment preparation · not C1 test · does not re-prove Meeting GA · duo batch · DUT-centric M02 scope.
- Phase -1 PASS manifest: `provisioning_topology=duo` — no M03 establishment rows.
- R2-A: separate gate requiring M01/M02/M03 establishment for 3-node GA path.
- Run #2 C1Q PASS: duo Meeting + same Phase -1 inheritance — proves **duo rollback qualification**, not three-node GA provisioning completeness.

**Analysis:**

- **DERIVED / ALREADY IMPLIED:** Phase -1 is **qualification-local prerequisite** for **RC-3 C1Q duo path** (M01 host + M02 DUT establishment chain). It is **not** labeled or scoped as **N≤9 GA deployment manifest**.
- Phase -1 PASS **cannot**, without architectural stretch, be read as evidence that **M01 host holds M03 establishment binding** or that **three-node GA provisioning** is complete.
- PRE_CHECK `thirdPeerModule=M03` documents channel context for batch preflight; combined with `topology=duo` and absent M03 manifest row, it **supports** “M03 present in environment but **outside** this establishment batch” — not “M03 provisioned.”

**Q4 status (pre-ruling analysis):** DERIVED / ALREADY IMPLIED  
**P4 ruling:** **FROZEN / ALREADY IMPLIED** — see §4

---

## 4. P1–P4 architecture ruling (FROZEN — 2026-09-16)

```text
ARCHITECTURAL DECISION = COMPLETE
CODE CHANGE              = NONE
FIELD RUN                = NONE
PROVISIONING CHANGE      = NONE
RC-3 / ADR-0058 GA       = NOT REOPENED
```

### P1 — Deployment Scope = **A / FROZEN**

```text
DeploymentScope
= modules authorized/provisioned for participation
  in the applicable operational Meeting domain

NOT = "who joined this Meeting right now"
NOT = ad-hoc operator subset without domain authorization framing

For every module eligible for ADR-0058 multicast media:
required establishment bindings
MUST already exist per Profile01 deployment provisioning contract.
```

Does **not** require provisioning all N≤9 modules globally. It requires: **whoever is deployment-authorized for the operational domain must be establishment-provisioned before multicast eligibility.**

Qualification-local scopes remain valid subsets:

```text
DeploymentScope(C1Q Phase -1) = {M01, M02}
  → legal duo batch
  → MUST NOT extrapolate to M03
```

### P2 — Missing-establishment admission = **A / FROZEN** (not B; not C)

```text
Meeting membership ≠ ADR-0058 media readiness

Missing establishment binding:

MEMBERSHIP              MAY be accepted
MEDIA_KEY_PENDING       MAY persist while prerequisite unavailable
MEDIA_READY / multicast audible eligibility
                        MUST NOT be asserted

No signing-key fallback
No synthetic establishment
No runtime authority invention
```

**Not B:** establishment prerequisite MUST NOT be re-coupled to Meeting membership admission.

**Not C:** `VIA_ANCHOR` MAY exist as fallback **result**, but MUST NOT be defined as ADR-0058 GA mixed-path **steady-state contract**.

### P3 — Responsibility = **A + narrow C / FROZEN**

```text
Primary responsibility:
  deployment / operator provisioning

Runtime responsibility:
  consume established authority only
  fail closed when unavailable
  never self-establish

Meeting/media admission responsibility (narrow C):
  guard ADR-0058 media eligibility
  do NOT promote peer to MEDIA_READY without establishment/key prerequisites
```

**Narrow C is NOT:** reject M03 from Meeting membership.  
**Narrow C IS:** `no establishment → no multicast media admission` — consistent with existing `MEDIA_KEY_PENDING → MEDIA_READY` contract; no new runtime protocol invented.

### P4 — Qualification scope = **FROZEN / ALREADY IMPLIED**

```text
RC-3 Phase -1 = C1Q qualification-local duo prerequisite
Scope           = M01 + M02

It is NOT:
  N≤9 GA deployment provisioning
  3-node deployment manifest
  evidence that M03 establishment existed on M01
```

---

## 5. Normative contract (FROZEN)

Merged contract after P1–P4:

```text
An admitted Meeting member does not automatically
become eligible for ADR-0058 multicast media.

ADR-0058 multicast media eligibility requires
the Profile01 establishment prerequisites for that
module to have been provisioned within the applicable
deployment scope.

Provisioning is an operator/deployment responsibility,
not a runtime convergence responsibility.

When the prerequisite is absent, runtime MUST fail closed:
the member may remain admitted / MEDIA_KEY_PENDING,
but MUST NOT become MEDIA_READY through fallback authority
or synthetic establishment.

ADR-0056 retention does not by itself authorize
mixed ADR-0056/ADR-0058 operation as the intended
GA steady-state for a correctly provisioned deployment.
```

Supporting fixed facts (unchanged):

```text
1. Establishment binding is deployment prerequisite; not runtime convergence.
2. Host MEDIA_KEY_PACKAGE origin fail-closed without establishment lookup Found.
3. Signer trust ≠ establishment trust.
4. Join Projection ≠ Key Projection ≠ VIA_ANCHOR CPP projection.
5. R2-A 3-node GA gate requires M01/M02/M03 establishment (gate-specific).
```

---

## 6. Track B Run #1 — Final Adjudication (FROZEN)

**Authoritative artifact:** `talkback/logs/rc3-c1q-field-manual-20260916-112211/TRACK_B_RUN1_VERDICT.txt`

### Primary classification

```text
PROVISIONING / DEPLOYMENT-SCOPE MISMATCH

DeploymentScope(C1Q) = {M01, M02}
Actual Meeting       = {M01, M02, M03}

M03 establishment prerequisite = NOT PROVISIONED
M03 ADR-0058 multicast eligibility = NOT ESTABLISHED
```

### Product-defect classification (narrow — M03 media-key chain only)

```text
rc.1 media-key bridge defect          = NOT ESTABLISHED
Profile01 runtime convergence defect    = NOT ESTABLISHED
UNKNOWN_SIGNER_KEY regression         = NOT ESTABLISHED
crypto/decrypt defect                 = NOT ESTABLISHED
multicast transport defect            = NOT ESTABLISHED
```

Do **not** infer whole-Meeting `rc.1 product defect = NO` from this adjudication.

### Mixed-path semantics

```text
VIA_ANCHOR fallback mechanics         = BY DESIGN / FAIL-CLOSED
Mixed ADR-0056 + ADR-0058 steady-state = NOT AUTHORIZED GA TARGET STATE
```

### Track closure vs open observations

```text
M03 media-key / SKIPPED_LOOKUP RCA    = ADJUDICATED / CLOSED
Mixed-path cause                      = EXPLAINED (provisioning-scope mismatch)
Human audio asymmetry ("only M03")    = NOT ADJUDICATED / OPEN
  → separate track: Meeting Audio Asymmetry
```

### GA / RC posture (unchanged)

```text
ADR-0058 GA · N≤9 envelope · rc.1 artifact = UNCHANGED
RC-3 = PASS/CLOSED (Run #2) · RC-4 = UNLOCKED
Production code change · new qualification = NOT REQUIRED
```

### Deployment action (contract clarification)

Any Module in operational DeploymentScope requiring ADR-0058 multicast eligibility **must** have Profile01 establishment bindings provisioned before runtime. Operator/deployment responsibility — not runtime auto-provision.

---

## 7. Next step

```text
Track B (M03 media-key / SKIPPED_LOOKUP) = CLOSED

OPEN (separate — Phase A COMPLETE / DEFERRED):
  Meeting Audio Asymmetry — human-audible layer NOT ADJUDICATED
  Phase B = NOT AUTHORIZED
  verdict: talkback/logs/rc3-c1q-field-manual-20260916-112211/AUDIO_ASYMMETRY_PHASE_A_RCA.txt

RC-4 Engineering RC sign-off = PASS / CLOSED (see rc4-engineering-rc-sign-off.md)
```

---

## References

| Document | Role |
|----------|------|
| `meeting-product-ga-authorization-review.md` | GA envelope · default/rollback posture |
| `meeting-product-r2-release-smoke-run-card.md` | 3-node GA gate · establishment preflight |
| `meeting-profile01-establishment-provisioning-design-slice.md` | Establishment prerequisite · deployment scope phrase |
| `0058-conference-multicast-media-group.md` | Membership vs key projections · MEDIA_READY |
| `0056-cpp-conference-presence-projection-contract.md` | VIA_ANCHOR vocabulary (adjacent) |
| `rc3-c1q-phase-minus1-run-card.md` | Phase -1 qualification-local scope |
| `rc3-c1q-phase-minus1-20260916-110044/establishment-batch/` | Duo manifest evidence |
| `talkback/logs/rc3-c1q-field-manual-20260916-112211/` | Run #1 mechanism evidence |
| `talkback/logs/rc3-c1q-field-manual-20260916-112211/TRACK_B_RUN1_VERDICT.txt` | Track B Run #1 final adjudication |
