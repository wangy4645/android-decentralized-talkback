# Meeting Epoch3 Field Setup Blocker — RCA

**ID:** meeting-epoch3-field-setup-blocker-rca-20260920  
**Status:** **ANALYSIS / RCA ONLY — CLOSED** (P1-E + R0 + R1 appendices 2026-09-20)  
**Scope:** B1–B4 + P0/P1/P1-E read-only RCA for epoch3 EG-1 field setup failure  
**Trigger:** Field attempts #1 and #2 (same blocker shape); third field **NOT AUTHORIZED**

```text
P1-E                              COMPLETE / PASS
T1 deterministic post-ACTIVE add NOT FOUND

Old EG-1 late-invite field        RETIRED / INVALID TEST DESIGN
                                  (not BLOCKED — formally withdrawn)

EG-1 desk                         PASS
EG-1 dedicated field (old gate)   NO LONGER REQUIRED BY OLD PROCEDURE
EG-1 final closure                HOLD

M04 establishment                 CONFIRMED DEPLOYMENT READINESS GAP

NORMAL MEETING BOOTSTRAP          epoch2 succeeds; ACTIVE shrink rotation breaks
                                  (R0 COMPLETE · R1 COMPLETE)

ISSUE A (epoch2 poor audio)       OPEN / separate from silence chain
ISSUE B (post-expiry silence)     ROOT DOMAIN CONFIRMED (R1)

Field attempt #1                  INCONCLUSIVE (setup pollution)
Field attempt #2                  INCONCLUSIVE (same blocker shape)

Third field / new field           NOT AUTHORIZED
Production implementation         NOT AUTHORIZED
M04 provisioning change           NOT AUTHORIZED
New ADR                           NOT AUTHORIZED
E3-B                              NOT AUTHORIZED
```

**Does NOT authorize:** production fix · implementation · third field · new ADR · E3-B · Path B · M04 provisioning change

**Scope boundary:** B1–B4 + P1-E explain **why the old EG-1 field gate was invalid**. R0/R1 appendices explain **ACTIVE-session shrink rotation failure** (Issue B). They do **not** explain **epoch2 poor audio quality** (Issue A).

**Isolated from:** GROUP Patch B session-liveness · WiFi recovery domains

---

## Executive summary

Two independent field runs failed to reach the **authorized epoch3 verification precondition** (stable 3-node audible baseline, then legitimate M04-driven epoch advance). The dominant finding is **not** that EG-1 regressed in the field, and **not** that the product violated roster authority contract on the default UX path.

**Primary conclusion:** the **original epoch3 field procedure is invalid for default product UX**. What failed first is our field assumption, not a proven product contract breach.

**Verdict:** **CASE C (mixed)** — contract-correct channel-wide initial roster + admit-before-establishment semantics, combined with an **unexecutable field procedure** and **M04 unprovisioned establishment state**. Establishment recovery lifecycle defect is **NOT ESTABLISHED**.

---

## Evidence runs

| Run | Log path | Session | Outcome |
|-----|----------|---------|---------|
| #1 | `talkback/logs/epoch3-eg1-field-20260920-160851/` | — | INCONCLUSIVE (setup pollution) |
| #2 | `talkback/logs/epoch3-eg1-field-20260920-163328/` | `3512e52c-89c0-43a6-845c-2c5a9e047bca` | INCONCLUSIVE (same blocker shape) |

**Confirmed reproducible symptom (both runs):**

```text
Meeting initial authoritative roster includes M04
→ host attempts KEY for M04
→ UNKNOWN_ESTABLISHMENT_MODULE
→ downstream media/material state incomplete
→ cannot establish authorized 3-node audible baseline
```

**Operator clarification (#2):** M04 did **not** accept Meeting (INVITED only). Prior “M04 joined Meeting” framing was incorrect — `joined=4` reflected **host roster/presence projection**, not M04 membership acceptance.

---

## Frozen main chain

```text
CHANNEL remotes
→ default Meeting initial invite set
→ sendConferenceInvitesInternal
→ addToRoster before acceptance
→ ConferenceTopologyAuthority publishes M01..M04
→ legitimate initial authoritative roster

M04 establishment binding absent
→ KEY origin lookup
→ UNKNOWN_ESTABLISHMENT_MODULE
→ M04 remains media-key pending / incomplete

therefore:
authorized 3-node baseline never exists
→ original epoch3 field procedure is not executable
→ EG-1 field remains untested
```

---

## Three explicit NOTs (anti-drift)

```text
NOT:
GROUP runtime roster directly contaminating Meeting

NOT:
M04 Meeting acceptance causing initial roster inclusion

NOT:
EG-1 endpoint-authority regression evidence
```

---

## B1 — Roster authority

**Question:** Who may place a module in Meeting **initial authoritative roster**, and from what source?

**Trace:**

```text
UI Meeting start (TalkbackRuntimeManager.initiateChannelMeshCall)
→ conferenceCall(local, emptyList(), channelId)     // defer: solo CREATE roster = M01
→ sendConferenceInvites(sessionId, remotes)         // remotes = collectChannelRemotes
→ sendConferenceInvitesInternal:
     session.memberModules.add(remote)
     conferenceParticipantManager.addToRoster(...)
→ apply_group_topology (CONFERENCE → applyConferenceTopologyAdmission)
→ ConferenceTopologyAuthority.publish*
→ MeetingProfile01MembershipOriginBridge:
     authoritativeMemberIds = snapshot.members
```

**Direct source of `authoritativeMemberIds`:** `ConferenceTopologySnapshot.members` at topology publish — populated from `conferenceParticipantManager.roster` after **invite-time `addToRoster`**, not from peer Meeting accept.

**M04 inclusion cause:** **CHANNEL configured/discoverable remotes** (`staticPeers` + dialable `remoteModuleStates` via `collectChannelRemotes`), expanded into roster by **`sendConferenceInvitesInternal` before invite acceptance**.

**Not the direct cause:** GROUP session runtime roster (secondary align path `conference_align_from_group_snapshot` exists under ADR-0036 but is not the observed primary chain in attempts #1/#2).

**Code anchors:**

- `TalkbackRuntimeManager.collectChannelRemotes` / `initiateChannelMeshCall`
- `TalkbackCoordinator.meshCallInternal` (`deferConferenceInviteDispatch`)
- `TalkbackCoordinator.sendConferenceInvitesInternal` (`addToRoster` → `applyGroupTopology`)
- `TalkbackCoordinator.anchorAdmissionContextFromSession` (`members = meshRoster(session)`)
- `MeetingProfile01MembershipOriginBridge.onTopologyPublished` (`authoritativeMemberIds = snapshot.members`)

**B1 ruling:** **CONFIRMED / CONTRACT-CORRECT** — roster authority flows through host topology publish after channel-derived invite roster admission.

---

## B2 — Cross-mode authority

**Question:** Is GROUP → Meeting roster projection contractually intended?

**Primary path in field evidence:** CHANNEL remotes → Meeting invite set → roster — **not** GROUP roster direct injection.

**Contract ownership (from ADR/code, not behavior反推):**

| Source | Ruling |
|--------|--------|
| ADR-0002 R8 | Session create uses frozen `Channel.members` snapshot as initial invite set |
| ADR-0017 | `MEETING_START` Declaration: `MULTI_PARTY` + `expectedInviteTargets` |
| `deferConferenceInviteDispatch` | Solo CREATE first, then invite dispatch (documented product path) |
| `sendConferenceInvitesInternal` | Adds invitees to participant roster before wire accept |

**B2 ruling:** **CONFIRMED / CONTRACT-INTENDED (Option A)** — full-channel initial invite + pre-accept roster placement is **design behavior**, not implementation shortcut or stale contamination.

**Naming note:** `apply_group_topology` on `SessionType.CONFERENCE` calls `applyConferenceTopologyAdmission` only — historical name, not GROUP authority merge in the primary chain.

---

## B3 — Establishment ordering

**Question:** Must establishment readiness precede authoritative roster admission?

**Frozen contract (ADR-0058 Q6/Q7 + Track B memo P2 ruling A):**

```text
Invite           ≠ Membership ≠ Media Group joined
Membership Fact  = sole membership admission authority
Media Key Package = admitted member obtains media-execution material

Member may exist in Membership View without ACTIVE key (MEDIA_KEY_PENDING)
Host MUST NOT wrap/send MEDIA_KEY_PACKAGE without establishment lookup Found
MUST NOT roll back Membership on key distribution failure
```

**Implementation:** `MeetingProfile01MediaKeyPackageOriginBridge` — `UnknownModule` → `ORIGIN_SOURCE_GAP reason=UNKNOWN_ESTABLISHMENT_MODULE`; does not send unless `Profile01RecipientEstablishmentKeyLookup.activeEstablishmentKey` is `Found`.

**B3 ruling:**

| Item | Status |
|------|--------|
| admit-before-establishment | **CONTRACT-CORRECT** |
| M04 `UNKNOWN_ESTABLISHMENT_MODULE` when unprovisioned | **EXPECTED UNDER CURRENT UNPROVISIONED STATE** |
| Establishment recovery lifecycle defect after provision | **NOT ESTABLISHED** (no clean field evidence) |

**Causal link B1 → B3:** roster includes M04 (channel invite path) → host KEY fanout to eligible peers → lookup fails for unprovisioned M04 → expected gap emission, not proven roster-admission bug.

**Out of scope for this RCA (downstream of invalid bootstrap):** M02/M03 `NO_MEDIA_MATERIAL`, `NOT_IN_ROSTER`, `SOURCE_GENERATION_MISMATCH`, `NO_SHADOW_SESSION` — **not** promoted to independent root causes here.

---

## B4 — Production-supported roster transition

**Question:** Does production support `{M01,M02,M03}` → later `{M01,M02,M03,M04}` authoritative roster transition **without** M04 in initial roster?

| Path | Supported? |
|------|------------|
| Default Meeting start (`initiateChannelMeshCall` → `sendConferenceInvites(all channel remotes)`) | **No** — M04 in roster at first invite dispatch |
| UI subset at CREATE | **No** |
| `invitePeersToMeeting` / `inviteMeetingMembers` (modules not in `memberKeys`) | **Yes** — incremental invite + `addToRoster` |
| Remove M04 from channel config temporarily | Operator/setup mutation — **not** a product capability; excluded as field procedure |

**B4 ruling:** **Original 3-node → M04 epoch3 field procedure is NOT EXECUTABLE through default Meeting start UX.**

The authorized field gate assumed a precondition the default product path does not provide.

---

## Verdict table

```text
Verdict:
FIELD PROCEDURE INVALID FOR DEFAULT PRODUCT UX

B1 roster authority:
CONFIRMED / CONTRACT-CORRECT

B2 CHANNEL initial projection:
CONFIRMED / CONTRACT-INTENDED

B3 admit-before-establishment:
CONTRACT-CORRECT

M04 UNKNOWN_ESTABLISHMENT_MODULE:
EXPECTED UNDER CURRENT UNPROVISIONED STATE

Establishment recovery defect:
NOT ESTABLISHED

B4 original 3-node → M04 procedure:
NOT EXECUTABLE THROUGH DEFAULT MEETING START UX

Old EG-1 late-invite dedicated field:
RETIRED / INVALID TEST DESIGN

Valid epoch3 field runs (old gate):
0

EG-1 final closure:
HOLD (pending R0 vs EG-1 relevance)
```

**CASE C (tightened):** contract-correct channel roster + establishment-pending semantics, plus **invalid original field procedure** and **unprovisioned M04**. Do **not** classify as “product bug in roster authority” without new ADR-bound evidence.

**Not closed by this document:** M02/M03 bootstrap failure despite KEY EMITTED/INGESTED — **NORMAL MEETING BOOTSTRAP REGRESSION UNEXPLAINED**.

---

## P0 — Establishment readiness (read-only)

**Host M01 KEY fanout prerequisite** (epoch3 field attempts #1/#2 evidence + EP probes):

| Module | Verdict |
|--------|---------|
| M01 | **READY** |
| M02 | **READY** (Found; KEY EMITTED + INGEST APPLIED) |
| M03 | **READY** (Found; KEY EMITTED + INGEST APPLIED) |
| M04 | **NOT_READY** (M01 lookup **UnknownModule** → `UNKNOWN_ESTABLISHMENT_MODULE`) |

**P0-5:** M04 local keystore may exist on device, but **M01 lacks authorized M04 recipient binding** in `AcceptedLocalEstablishmentTrustStateStore` (operator provisioning gap — duo batch had no M04 row). Symptom is **contract-correct**, not runtime recovery defect.

**Provisioning change:** NOT AUTHORIZED.

---

## P1 — Incremental invite feasibility (read-only)

**P1-D FAIL:** default 4-node UX (`staticPeers` → `collectChannelRemotes` always non-null → `MULTI_PARTY` + invite-all on first `joinMeeting`). Cannot reach `{M01,M02,M03}` baseline then add M04 through default Meeting start.

**P1 ruling:** subset-create for EG-1 qualification is **not default product semantics** (ADR-0002/0017 support invite-all). **Do not change product for qualification.**

---

## Appendix P1-E — Production post-ACTIVE roster advance inventory

**Question answered:** Is there a **clean deterministic T1** path for EG-1 field qualification under default product UX?

**Default product semantics (frozen):**

```text
Meeting start
→ channel members already enter authoritative roster (invite-all)
```

**Therefore:**

```text
stable ACTIVE N
→ user adds never-before-rostered N+1
→ no clean deterministic T1 path
```

**Event inventory (production code owners only):**

| Event | Roster Δ | membership/key advance | Default UX | Class |
|-------|----------|------------------------|------------|-------|
| Meeting start + `sendConferenceInvites(all)` | expand at CREATE | yes | yes | CREATE-phase, not post-ACTIVE T1 |
| `invitePeersToMeeting` / Invite Members UI | add if ∉ roster | yes | UI exists; **blocked when invite-all already rostered** | **T3** |
| `commitAuthorityPrune` | shrink | yes | recovery/failure gated | **T2** |
| Rejoin / counter-invite / ring-timeout restore | restore | maybe | passive | **T2** |
| `ensureConferenceParticipantInRoster` / `onLateJoin` | restore roster row | no direct topology publish alone | recovery | **T2** |
| GROUP snapshot → `alignConferenceSessionsFromMembershipSnapshot` | `groupMembers` align; authoritative roster = participantManager | conditional | requires GROUP active | **T2/T3** |
| `invite_accept` (peer) | does **not** expand host roster N→N+1 | usually no roster Δ | yes | not T1 add |
| `EightNodeControlledLifecycleContract` | sim | yes | no | **T4** |

**P1-E verdict:** **T1 NOT FOUND.** Old procedure `M01/M02/M03 stable → invite M04` is **RETIRED** — not BLOCKED waiting for P2.

**Architectural boundary:** P1-E answers **how EG-1 could be field-qualified**. It does **not** explain **why normal default-UX Meeting is currently unusable** for M02/M03.

---

## Retired procedures (do not revive without new ADR)

```text
Original epoch3 EG-1 field:
  3-node baseline → late M04 invite → F1-F6
  STATUS: RETIRED / INVALID TEST DESIGN

Candidate SOLO → incremental invite field:
  STATUS: RETIRED with original gate (P1-D + P0 blockers)
  Do not use “remove M04 from channel config” as workaround
```

---

## Appendix — R0 Differential Addendum (2026-09-20)

**Status:** COMPLETE · read-only · does **not** revise B1–B4 setup RCA verdicts

**Evidence:** BAD `#2` `talkback/logs/epoch3-eg1-field-20260920-163328/` session `3512e52c-…`  
**GOOD comparator (same APK):** `talkback/logs/meeting-field-test-20260920-135135/` (`5B74BC04…`)

### R0 finding — bootstrap is not the break

```text
Both paths:
  default start → invite-all → transient NO_MEDIA_MATERIAL (recoverable)
  → mediaKeyEpoch=2 SUPPLEMENT READY → source BUILT → MULTICAST_ACTIVE @ epoch2

BAD #2 additionally:
  ~27s audible window (16:35:44–16:36:11) with liveDecoders=3 / MULTICAST_PRODUCTION
  then M04 invite pending → expired → host roster 4→3
  → membershipVersion 1→2 · mediaKeyEpoch 2→3
  → SHADOW_TX_FENCED (M01 NO_MEMBER_BINDING; M02/M03 SOURCE_GENERATION_MISMATCH)
  → silence
```

### R0 first divergence (sticky)

| | GOOD 135135 | BAD #2 |
|---|-------------|--------|
| M04 conference | **accepted** @ 13:54:33 | **never accepted**; expired @ 16:36:12 |
| Membership head | stays `members=4 v1 epoch2` | `members=3 v2 epoch3` @ 16:36:11 |
| M04 establishment gap | present (`UNKNOWN_ESTABLISHMENT_MODULE`) | same — **not differentiator** |

**Issue split (frozen):**

```text
ISSUE A — epoch2 ~27s audible but poor quality     OPEN (separate track)
ISSUE B — invite expiry → shrink → rotation fence  CONFIRMED (R1 domain)
```

**EG-1 closure:** HOLD — desk covers same-cardinality epoch advance, not ACTIVE shrink.

---

## Appendix — R1 Shrink-Rotation RCA (2026-09-20)

**Status:** COMPLETE · read-only · **NOT AUTHORIZED:** production change · field rerun

**Scope:** `4→3 shrink rotation → shadow convergence` on BAD `#2` only.

### Trigger chain (host)

```text
16:36:11.658  CONFERENCE_TOPOLOGY_SNAPSHOT members=M01,M02,M03 (M04 dropped)
16:36:11.678  factType=2 members=3 membershipVersion=2 mediaKeyEpoch=3
              reason=r4a_host_membership_projected / presence_read
16:36:12.346  M04 Conference invite expired
```

### Per-node rotation trace

**M01 host @ 16:36:11.678–.853**

```text
membership v2/e3 EMITTED + supplement@3 READY
→ HOST_SESSION_PROJECTED MEMBERSHIP_REGISTRY_PREPARED
→ TX still ACTIVE on epoch2 catalog (packetsSent=1500)
→ SHADOW_TX_FENCED reason=NO_MEMBER_BINDING          ← first fence (.713)
→ SHADOW_TX_BINDING_INSTALLED (epoch3) AFTER fence
→ WIRING_MEDIA_KEY_EPOCH_ROTATED catalogEntries=0    ← epoch2 catalog cleared
→ MEMBER_REPLACED M01 outcome=WIRING_REJECTED
→ WIRING_MEMBER_INSTALLED M01 sourceGeneration=4
→ RX REJECTED reason=no catalog binding
```

**M02 / M03 peer @ 16:36:14.8–16:36:15.0** (~3s after host emit; membership wire lag)

```text
PACKAGE_STAGED → WIRE_INGRESS factType=2 APPLIED
→ PACKAGE_ACTIVATED epoch3 + supplement@3 READY
→ (M03) SHADOW_TX_FENCED SOURCE_GENERATION_MISMATCH BEFORE source BUILT
→ sourceGeneration 2→3 BUILT + SHADOW_TX_BINDING_INSTALLED
→ SHADOW_TX_FENCED SOURCE_GENERATION_MISMATCH (sustained)
```

Peers **never** logged `WIRING_MEDIA_KEY_EPOCH_ROTATED` during shrink; host did.

### R1 adjudication

| ID | Question | Verdict |
|----|----------|---------|
| **R1-1** | Same rotation owner as EG-1 desk? | **Partial.** Same **pipeline names** (`r4a_host_membership_projected`, supplement@N+1, sourceGeneration+1). **Not** same **lifecycle:** desk = static existing-session, **same member cardinality**, no concurrent production TX; field = **ACTIVE MULTICAST + cardinality shrink**. |
| **R1-2** | M01 `NO_MEMBER_BINDING` — missing reinstall? | **Yes.** `MEMBERSHIP_REGISTRY_PREPARED` / epoch rotation **invalidates epoch2 TX catalog** while TX thread still transmitting. `SHADOW_TX_BINDING_INSTALLED` and `WIRING_MEMBER_INSTALLED` arrive **after** first fence; `MEMBER_REPLACED … WIRING_REJECTED` on local M01. |
| **R1-3** | M02/M03 post-`PACKAGE_ACTIVATED` mismatch? | **Yes.** TX encoder still on **epoch2 / sourceGeneration=2** binding; supplement@3 installs **gen=3** binding on control thread **without atomic TX swap**. Fence at `PACKAGE_ACTIVATED` (M03) or immediately after binding install (M02). |
| **R1-4** | EG-1 ExistingPublishedSession covers SHRINK? | **No.** Desk `runIg2E3APreconditionToSupplementActive` uses `buildEpoch3Artifacts(includeFourthMember = false)` — **v+1 / epoch+1 with unchanged members**, no removal, no live audible session under rotation. **Fixture E proves expansion-class epoch advance only.** Shrink 4→3 on ACTIVE session is **unfixture’d**. |
| **R1-5** | First invariant violation (one)? | **M01 @ 16:36:11.713:** *active multicast TX MUST retain a valid member binding through epoch rotation* — violated when host membership projection clears epoch2 catalog **before** epoch3 TX binding is installed. Subsequent peer fences are **downstream** of the same non-atomic rotation. |

### R1 domain statement (frozen)

```text
Meeting establishment @ epoch2: WORKS
Destructive transition: invite expiry → authoritative roster shrink → v2/e3 rotation
Failure class: ACTIVE-session epoch rotation not atomically converged with shadow TX/RX catalog
EG-1 desk PASS does not contradict field FAIL — shrink lifecycle not covered
```

---

## References

**Field evidence:**

- `talkback/logs/epoch3-eg1-field-20260920-160851/`
- `talkback/logs/epoch3-eg1-field-20260920-163328/`

**Contract / prior analysis:**

- `talkback/docs/adr/0002-channel-session-membership.md` (R8)
- `talkback/docs/adr/0017-transition-declaration-and-media-lifecycle.md`
- `talkback/docs/analysis/meeting-product-track-b-m03-media-key-contract-alignment-memo.md` (Q2-A)
- `talkback/docs/analysis/meeting-profile01-establishment-provisioning-design-slice.md`
- `talkback/docs/analysis/meeting-product-integration-phase-a-origin-publisher-run-card.md`
- `talkback/docs/analysis/meeting-profile01-epoch3-staged-package-lifecycle-fix-contract.md` (EG-1 / E3-A separate track)
- `talkback/docs/analysis/meeting-profile01-peer-existing-session-endpoint-authority-fix-contract-amendment.md` (EG-1 desk)

**Code:**

- `talkback/talkback-app/.../TalkbackRuntimeManager.kt` (`collectChannelRemotes`, `invitePeersToMeeting`)
- `talkback/android-board-talkback/.../TalkbackCoordinator.kt` (`meshCallInternal`, `sendConferenceInvitesInternal`, `applyConferenceTopologyAdmission`)
- `talkback/android-board-talkback/.../MeetingProfile01MediaKeyPackageOriginBridge.kt`
- `talkback/android-board-talkback/.../MeetingProfile01MembershipOriginBridge.kt`
