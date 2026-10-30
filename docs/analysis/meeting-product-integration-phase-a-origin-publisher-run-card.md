# Meeting Product Integration — Phase A Profile01 Fact Origin Publisher Run Card

## Status

```text
Meeting J0 / Profile01 chain         CLOSED / FIELD-PROVEN
  evidence: talkback/logs/j0-meeting-operational-20260909-103707/

P1-A..E / Establishment / P1-C field   CLOSED / FIELD-PROVEN or UNIT-PROVEN
Hook inventory                         COMPLETE
Origin Publisher (CREATION)            CLOSED (J0 supersedes peer E2E gap)

Phase A Shadow Runtime Gate            AUTHORIZABLE / NEXT
Phase A Shadow Integration (full)      BLOCKED pending Shadow Runtime
Phase B                                HOLD
ADR-0056                               KEEP
Meeting media cutover                  NOT AUTHORIZED
```

**Inventory:** `meeting-product-integration-phase-a-origin-publisher-hook-inventory.md`  
**Session-bridge RCA:** `meeting-profile01-deferred-session-bridge-rca.md`  
**Pre-bind retention slice:** `meeting-profile01-pre-bind-fact-retention-drain-design-slice.md`  
**Apply-completeness RCA:** `meeting-profile01-creation-apply-completeness-rca.md`

## Frozen posture

```text
CREATION transport/origin       PASS
CREATION pre-bind retention     PASS (…-132834)
CREATION session-bind drain     PASS (…-132834)
CREATION verify/re-ingress      PASS (…-132834)
CREATION publish/apply          REJECTED_INCOMPLETE

Session-bridge silent-drop gap  CLOSED
CREATION peer E2E               VERIFY_APPLY_GAP (C: predecessor missing)
J0 end-to-end                   NOT PASS

Next                            Path 1 design slice (hook gap map COMPLETE)
Hook gap map                    meeting-profile01-media-key-package-origin-hook-gap-map.md
Path 1 design slice             meeting-profile01-media-key-package-origin-path1-design-slice.md (SIGNED OFF)

```text
Hook gap map                  COMPLETE
Path 1 design slice           SIGNED OFF
P1-A Establishment Authority  IMPLEMENTED (PR-1; A1–A4 PASS)
P1-B MEDIA_KEY_PACKAGE Builder IMPLEMENTED (PR-2; B1–B6 PASS)
P1-C..E                       HOLD
Implementation scope          PR-1 ONLY (complete)
```
ADR-0061                        FREEZE
Retention/drain code            FREEZE
CREATION origin/replay          FREEZE
MEMBERSHIP                      HOLD
Phase A                         BLOCKED
```

**No product code / no MEMBERSHIP** until J0 PASS and CREATION peer E2E PASS.

## Purpose

Close **ORIGIN_GAP** for **CREATION first** — publish Profile01 authoritative facts from
**existing** Meeting / GBC state onto the frozen wire — without redesigning Profile01,
membership authority, or shadow wiring.

```text
existing authoritative Meeting / GBC state
        ↓
Meeting Profile01 Fact Origin Publisher   ← THIS SLICE
        ↓
Profile01 production encoder / signer (Step 0)
        ↓
CONFERENCE_SIGNED_FACT
        ↓
existing Meeting wire (receive → MeetingProfile01FactWireIngress → shadow)
```

**Not in scope:** ingress rework · registry rework · shadow delegate rework · Phase B ·
ADR-0056 cutover/delete · harness injection to claim PASS · MEMBERSHIP bridge (Step 2) until
CREATION field PASS.

## Step 0 — IMPLEMENTED

```text
Profile01SignedFactSigner / Profile01PersistedSignedFactSigner
Profile01WireCborEncoder.encodeCreationFullFact
Profile01SignedFactEnvelopeBuilder
Profile01MembershipKeyContextDigest / Profile01MediaGroupDescriptor
Unit: Profile01WireCborEncoderTest
```

Signer loads field-established PKCS8 from `gbc-trust/`; signs with Profile01 `SIGNATURE_DOMAIN`.

## Step 1 — IMPLEMENTED

```text
Trigger: CONFERENCE_TOPOLOGY_PUBLISHED only (not meshCallInternal / UI create)
Gate: isMembershipAuthority && localModuleId == snapshot.hostModuleId
Idempotent: one CREATION v0 per session

MeetingProfile01ConferenceSessionIndex.ensureConferenceIdHex(sessionId)
  → deterministic id128 (Profile01ConferenceIdAuthority)
Profile01ConferenceMediaKeyMaterialAuthority.ensureMaterial (authority side, not Publisher)
MeetingProfile01CreationOriginBridge → MeetingProfile01CreationOriginPublisher
  → broadcastConferenceSignedFact

Observability: PROFILE01_ORIGIN_EMIT factType=1 outcome=EMITTED|ORIGIN_SOURCE_GAP
Unit: MeetingProfile01CreationOriginBridgeTest
```

## CREATION-only field checkpoint (AUTHORIZED — J0 gated)

**Do not** run full Origin Discovery / A1–A4 on this pass. **Do not** fail on MEMBERSHIP /
SOURCE / MEDIA_KEY_PACKAGE gaps or perpetual shadow `DEFERRED_*` after CREATION (supplement
still placeholder until MEDIA_KEY_PACKAGE origin).

**Do not** adjudicate CREATION ingress when J0 fails — `WIRE_INGRESS=0` has no diagnostic
value if peer is not on the host session.

### J0 — Same-session prerequisite (HARD GATE)

```text
M02 JOIN_MEETING_TRACE chosenPath != CREATE
AND
M02 resolved sessionId == M01 authoritative host sessionId
```

| J0 | Action |
|----|--------|
| **FAIL** | Entire run = **`ENV_INVALID`**. Stop. Do not score ORIGIN / WIRE / VERIFY. |
| **PASS** | Continue CREATION chain adjudication below. |

UI stuck on Connecting while `chosenPath=CREATE` on peer is expected under J0 FAIL
(two different conferences).

### Chain under test (only after J0 PASS)

```text
M01 PROFILE01_ORIGIN_EMIT factType=1 outcome=EMITTED
→ M02 CONFERENCE_SIGNED_FACT observed
→ WIRE_INGRESS factType=CREATION
→ APPLIED
```

### Topology

```text
M01 host (HTUBB21B09220661) — CREATE via Meeting mode + main PTT button
M02 peer (DSJ-2407041)      — TapToJoin via channelOnlineArea (NOT CREATE)
CH-01 · field-established GBC trust on both devices
```

**Field prep order:**

1. Cold-stop **both** (clear in-memory session).
2. Start **both** services via UI (M02 may need extra swipe to reach Start Service).
3. Wait until discovery / HELLO both ways.
4. M01 Meeting mode + CREATE → confirm host `EMITTED` **before** peer join.
5. M02 TapToJoin → **must** pass J0 before any CREATION ingress scoring.

### Why TapToJoin → CREATE (J0 blocker — read-only)

`TalkViewModel.joinMeeting` new-path order:

```text
pendingConferenceInvite?  → ACCEPT_PENDING
hasRejoinableConference?  → REJOIN
shouldLocalInitiateConference? → CREATE   ← field path taken
else → REJOIN
```

`shouldLocalInitiateConference` (`TalkbackCoordinator`):

```text
authority == null || authority == localModuleId  → true → CREATE allowed
```

Field evidence (`…-165500`):

```text
intent=TapToJoin authorityModuleId=null shouldLocalInitiateConference=true
→ chosenPath=CREATE pathReason=NEW_CONFERENCE_READY authorityModuleId=M02
→ M02 session ≠ M01 host session
```

So TapToJoin does **not** mean “join remote host meeting.” With no pending invite and no
rejoinable record, and authority still null, peer is authorized to **create**. That is the
current Meeting join/convergence blocker — **not** a Profile01 origin defect.

**Next field knife (still no product code):** make J0 true — get M02 onto M01’s
authoritative session (invite / rejoin / authority-visible path). Only then re-test CREATION
peer ingress.

### J0-only field objective (CURRENT — not a CREATION adjudication)

Prove **one** thing before any Profile01 scoring:

```text
Before M02 TapToJoin:
  M02 already has invite / rejoin / authority context for M01’s conference

After M02 join action:
  chosenPath != CREATE
  AND sessionId == M01 host sessionId
→ J0 PASS
```

#### How M01’s conference becomes visible to M02 (read-only)

Host CREATE path (`TalkbackRuntimeManager.initiateChannelMeshCall` conference branch):

```text
remotes = collectChannelRemotes(config)
conferenceCall(local, emptyList(), channelId)   // solo session first
if (remotes != null) sendConferenceInvites(sessionId, remotes)
```

| Host CREATE condition | Wire effect on M02 | TapToJoin likely path |
|-----------------------|--------------------|------------------------|
| `remotes == null` (no dialable teammate) | **No invite** | `shouldLocalInitiate=true` → **CREATE** (J0 FAIL) |
| `remotes` includes M02 | `GROUP_INVITE` → pending / accept | **ACCEPT_PENDING** (J0 candidate) |
| Prior `rejoinableConference` on M02 | rejoin hint | **REJOIN** (J0 candidate) |

Field evidence matches solo-host CREATE: host `members=1`, no peer pending → TapToJoin→CREATE.

#### J0 field procedure (ops only)

1. Both services UI-started; wait discovery **both ways**.
2. **Pre-CREATE gate on M01:** confirm M02 dialable / teammate visible (e.g. online roster, or log that remotes would be non-empty). Do **not** CREATE while M01 is alone.
3. M01 Meeting CREATE → immediately grep host:
   - `Conference invites sent=` **must be ≥ 1**
   - host sessionId from `PROFILE01_ORIGIN_EMIT` / topology (emission optional for J0-only)
4. **Pre-TapToJoin gate on M02:** grep for invite context **before** tapping online area:
   - `GROUP_INVITE` / `Conference invite pending` / `pendingConferenceInvite` / admission `INVITED`
   - If absent → **abort ENV_INVALID** (do not TapToJoin; would CREATE)
5. M02 join (Accept invite UI if shown, else TapToJoin only when pending exists).
6. **J0 score only:**
   - `JOIN_MEETING_TRACE chosenPath` ∈ {`ACCEPT_PENDING`,`REJOIN`,`REUSE_EXISTING`,…} **≠ CREATE**
   - `M02 sessionId == M01 sessionId`
7. If J0 PASS → **stop or continue** to CREATION chain in a **separate** adjudication pass. If J0 FAIL → `ENV_INVALID`, no CREATION scoring.

#### J0 log greps

```text
M01: Conference invites sent=
M01: CONFERENCE_TOPOLOGY / PROFILE01_ORIGIN_EMIT session=
M02: GROUP_INVITE | Conference invite pending | INVITE_RECEIVED | JOIN_MEETING_TRACE
M02: chosenPath=  (must not be CREATE)
```

### Field attempts

| RunId | Verdict | Notes |
|-------|---------|-------|
| `…-162700` / `…-163100` | ENV_INVALID* | Host `SERVICE_STOPPED` |
| `…-164500` | ENV_INVALID* (was miscored WIRE_OR_VERIFY_GAP) | Host EMITTED; peer not same session / service miss — **not** wire gap |
| `…-165200` | ENV_INVALID* | Log rotation / timing; not clean J0 |
| `…-165100` | **ENV_INVALID** | M01 SERVICE_STOPPED; M02 CREATE |
| `…-165500` | **ENV_INVALID** | Host EMITTED mid-run; M02 TapToJoin→CREATE; J0 FAIL; UI Connecting expected |
| `j0-…-170633` | **ENV_INVALID** | Dialable OK; `Conference invites sent=0` (async) + `MEETING_START TIMED_OUT` during GROUP→CONFERENCE hangup race; **no wire GROUP_INVITE** |
| `j0-…-171300` | **ENV_INVALID** (invite-visible only) | After leave+settle: peer got `GROUP_INVITE session=e9714a30` + `Conference invite pending` + `ADMISSION INVITED`. Host still `joined=1 pending=1`. **No peer JOIN_MEETING_TRACE / accept.** Auto script falsely claimed J0_PASS — corrected. |
| `j0-…-171817` | **ENV_INVALID** | Accept knife aborted: recreate CREATE produced host session `4ddfdc5f` but **no** peer `GROUP_INVITE` / Accept UI. |
| `j0-…-172654` | **ENV_INVALID** | Clean leave then CREATE `48a1fac8`: live host showed `MEDIA_RELEASE_DEFERRED_CREATE module=M02` + `Conference invites sent=0` + topology already `members=M01,M02` + `pending=0`. Peer never armed pending / no `btnJoinMeeting`. |
| `j0-…-173456` | **ENV_INVALID** (near-miss) | Idle~20s → CREATE `ecedf8d3` with `pending=1` + invite dispatch frozen. M02 `btnJoinMeeting` Accept → `USER_ACCEPT` → `SESSION_CREATED creationSource=INVITE_ACCEPT` same session → peer `ACTIVE`. **Host `pending→0` not retained** (EDGE_TASK flood). Auto script under-scored accept path. |
| `j0-…-174509` / `…-175048` | **ENV_INVALID** | Pending-capture attempts: EDGE_TASK/`findstr` pipe miss; sticky conference → no Accept UI. |
| `j0-…-175549` | **J0_PASS** | Conference cleared → CREATE `dafbbac9` → `pending=1` retained → Accept → `pending=0 joined=2 ACTIVE`. Peer `INVITE_ACCEPT` / same session. **CREATION:** host `EMITTED` at `members=1`; peer no wire → refined below to **ORIGIN_FANOUT_GAP**. |

### J0 status after 175549

```text
authoritative invite visible           PASS
invite acceptance                      PASS (USER_ACCEPT / INVITE_ACCEPT)
same-session identity                  PASS (session=dafbbac9…)
host pending 1 → 0                     PASS (CAPTURED)

J0 overall                             J0_PASS / FIELD-PROVEN
```

**Host pending transition (authoritative):**

```text
joined=1 pending=1 phase=CONNECTING
→ Accept
→ joined=2 pending=0 phase=ACTIVE connected=1
artifact: talkback/logs/meeting-product-j0-same-session-20260907-175549/host-pending-lines.txt
```

### CREATION wire fan-out investigation (read-only; post J0)

**Field timeline (`175549` / `m01-projection-live.txt`):**

```text
Conference solo host by M01
→ TOPOLOGY members=M01  PUBLISHED (apply_group_topology)
→ PROFILE01_ORIGIN_EMIT factType=1 EMITTED members=1     ← emit here
→ TOPOLOGY members=M01,M02 PUBLISHED (+6ms)               ← no second ORIGIN_EMIT
→ Conference invites sent=0 (async log; invite still delivered later)
```

**Code chain:**

```text
PROFILE01_ORIGIN_EMIT
→ emitToPeers(signedFactBytes)
→ broadcastConferenceSignedFact(session, bytes)
→ targetsForSession(session) = session.remotePeersByModule  (mesh)
→ forEach → sendSignalHandoff(CONFERENCE_SIGNED_FACT)
→ signalingChannel.send
→ peer handleSignal(CONFERENCE_SIGNED_FACT)
```

| Segment | Finding |
|---------|---------|
| Recipient resolution | `targetsForSession` = `remotePeersByModule` only — **not** topology `snapshot.members`, **not** pending invitees (`hangupTargetsForSession` style) |
| Emit timing | Product path `conferenceCall(local, emptyList())` → solo host → first `apply_group_topology` publishes with roster **M01 only** |
| `members=1` | CREATION fact roster from `snapshot.members` at first Published — **primary hypothesis confirmed as solo-snapshot**, and **correlates** with empty fan-out targets at same instant |
| Once-only emit | `creationEmitted` per sessionId → later `members=M01,M02` publish is **SKIPPED_ALREADY_EMITTED** (no rebroadcast when peer appears) |
| Signaling gate | `CONFERENCE_SIGNED_FACT` is **not** in `PeerControlSignalingAdmission.isHardGatedControl` → peer-edge READY gate does **not** block this type |
| Peer receive | No `CONFERENCE_SIGNED_FACT` on M02 (expected if recipient count was 0) |

**Classification (user taxonomy):**

```text
recipient count = 0 at EMITTED
→ ORIGIN_FANOUT_GAP CONFIRMED

NOT SIGNALING_GATE_GAP   (type not hard-gated)
NOT SIGNALING_DELIVERY_GAP (no send attempt to attempt delivery)
NOT verify/decode        (peer never received)
```

Coarse field label `WIRE_DELIVERY_GAP` is **superseded** by `ORIGIN_FANOUT_GAP` for this CREATION miss.

**Frozen posture:**

```text
J0 overall                PASS / FIELD-PROVEN
CREATION peer E2E         ORIGIN_FANOUT_GAP CONFIRMED
MEMBERSHIP                HOLD
Phase A                   BLOCKED
```

**Working field recipe (175549):** leave until no CONFERENCE digest → short idle → Meeting CREATE → Accept `btnJoinMeeting` immediately; capture host projections via continuous PS stream (exclude EDGE_TASK flood).

**Next (still no MEMBERSHIP / no Profile01 contract change):** CREATION fan-out lifecycle only — emit-once vs solo-first create vs `targetsForSession` empty. Observability for recipient count optional; product delta only under new scoped ADR/PR if authorized.

\*Retrospective: prior `WIRE_OR_VERIFY_GAP` labels are **superseded** — J0 was not met. Coarse `WIRE_DELIVERY_GAP` after J0 is **superseded** by `ORIGIN_FANOUT_GAP` for `175549`.

### Verdict taxonomy (after J0)

| Verdict | Criterion |
|---------|-----------|
| **ENV_INVALID** | J0 FAIL (incl. peer `chosenPath=CREATE` or sessionId mismatch) |
| **ORIGIN_EMIT_GAP** | J0 PASS + host topology Published but no `EMITTED` |
| **ORIGIN_FANOUT_GAP** | J0 PASS + host `EMITTED` + recipient resolution count = 0 (no per-peer send attempt) |
| **SIGNALING_GATE_GAP** | J0 PASS + recipients > 0 but send blocked before enqueue |
| **SIGNALING_DELIVERY_GAP** / coarse **WIRE_DELIVERY_GAP** | J0 PASS + send attempted + peer never observes `CONFERENCE_SIGNED_FACT` |
| **VERIFY_DECODE_OR_SESSION_BRIDGE_GAP** | J0 PASS + peer sees wire / ingress but CREATION not `APPLIED` |
| **PASS** | J0 PASS + host `EMITTED` + peer `WIRE_INGRESS factType=1 … outcome=APPLIED` |

Do **not** collapse delivery vs verify into one `WIRE_OR_VERIFY_GAP` going forward.

### Log capture

```text
Talkback:I          PROFILE01_ORIGIN_EMIT, CONFERENCE_TOPOLOGY_PUBLISHED, JOIN_MEETING_TRACE
MEETING_MEDIA_SHADOW WIRE_INGRESS factType=1 …
```

### Launcher

```powershell
# prep: build + install + FTPH provision + clear logcat
.\talkback\scripts\meeting-product-phase-a-creation-origin-field-step.ps1 -Step prep

# automated narrow run (M01 CREATE → M02 TapToJoin → pull → adjudicate)
.\talkback\scripts\meeting-product-phase-a-creation-origin-field-step.ps1 -Step run

# manual UX variant
.\talkback\scripts\meeting-product-phase-a-creation-origin-field-step.ps1 -Step capture
# … manual sequence …
.\talkback\scripts\meeting-product-phase-a-creation-origin-field-step.ps1 -Step pull -RunId <id>
.\talkback\scripts\meeting-product-phase-a-creation-origin-field-step.ps1 -Step adjudicate -RunId <id>
```

Evidence: `talkback/logs/meeting-product-creation-origin-<timestamp>/run-header.json`

### After CREATION field PASS

Authorize **Step 2 — MEMBERSHIP bridge** only. Do not stack MEDIA_KEY_PACKAGE /
SOURCE_DECLARATION origin in the same field pass.

## Publisher allowed / forbidden (unchanged)

**Allowed:** read authoritative state · construct frozen Profile01 facts · sign · broadcast ·
idempotent observability.

**Forbidden:** decide authority · advance membership independently · UI-create emit ·
manufacture source incarnation · duplicate GBC convergence policy.

## References

- Origin Discovery (baseline ORIGIN_GAP): `meeting-product-integration-phase-a-origin-discovery-run-card.md`
- Evidence: `talkback/logs/meeting-product-origin-discovery-20260907-153810/`
- Shadow run card: `meeting-product-integration-phase-a-shadow-run-card.md`
- Gate: `meeting-product-integration-gate.md`
- Hook inventory: `meeting-product-integration-phase-a-origin-publisher-hook-inventory.md`
- Wire ingress: `MeetingProfile01FactWireIngress.kt`
- CREATION bridge: `MeetingProfile01CreationOriginBridge.kt`
- ADR-0058 wire: `docs/adr/0058-profile-01-q4-wire-registry.md`
- Origin Discovery script: `talkback/scripts/meeting-product-phase-a-origin-discovery-step.ps1`
- **CREATION field script:** `talkback/scripts/meeting-product-phase-a-creation-origin-field-step.ps1`
