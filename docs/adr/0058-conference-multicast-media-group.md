# ADR-0058: Conference Multicast Media Group Architecture

## Status

**ACCEPTED — CONTRACT ONLY** (2026-08-27)

**Grill:** **COMPLETE · Q1–Q13 PASS**  
**Underlay dependency:** **ADR-0059 ACCEPTED — PRODUCT RISK ASSUMPTION**  
**Implementation:** **AUTHORIZED** (Profiles 01/02/03 scope only; E2b PENDING)  
**ADR-0056 deletion:** **HOLD**

```text
ADR-0058
Decision:              ACCEPTED — CONTRACT ONLY
Domain:                Conference media plane (multicast group)
Problem class:         Anchor / per-edge WebRTC topology is the wrong
                       media model given fixed-IP, reliable efficient
                       underlay multicast
Does NOT reopen:       ADR-0057 GBC · GROUP PTT media · WiFi recovery /
                       RNA / completion · Track A R3 · Track B
Implementation:        AUTHORIZED for Profiles 01/02/03 only
                       (see docs/analysis/0058-implementation-authorization.md)
Does NOT authorize:    ADR-0056 deletion · ADR-0057/GROUP PTT/WiFi changes ·
                       E2b PASS · field/runtime PASS · Profile reopen
```

**Supersedes at implementation cutover:** ADR-0056 **Conference Anchor / SFU-lite / ActualMediaEdgeSet / AdmittedRecoveryTarget / Anchor failover / per-edge Conference recovery** decisions only. ADR-0056 code remains until the capacity gates pass.

**Does not supersede (keep from ADR-0056 and parents):**

- Membership fact ≠ media fact ≠ health fact (no mutual impersonation)
- Conference lifecycle ≠ media health
- Atomic snapshots for control facts
- Non-current generation MUST NOT resurrect
- Control plane MUST NOT synchronously wait for media execution
- ADR-0010 roster vs media projection vocabulary (projection target changes; principle stays)
- ADR-0019 signaling vs media lifecycle (control edges remain non-recovery)

**Parents / boundaries:**

- [ADR-0056](./0056-conference-topology-first.md) — **KEEP as current implemented Conference path** until this ADR is ACCEPTED **and** capacity gates PASS. Then delete Anchor path. **No long-term dual architecture.**
- [ADR-0057](./0057-group-bootstrap-convergence-contract.md) — **ACCEPTED — CONTRACT ONLY.** GROUP bootstrap generation only. MUST NOT merge FSMs. Conference uses `conferenceEpoch`, not GROUP bootstrap generation.
- [ADR-0059](./0059-underlay-multicast-product-assumption.md) — fixed-IP reliable efficient multicast is a product dependency and explicit risk assumption, not a completed lab qualification.
- GROUP PTT media / Floor / ProgramAudioBus / GROUP Anchor — **FROZEN this track.** Out of scope.
- WiFi recovery main chain — **CLOSED.** Do not merge.

**Normative amendment:**

- Late-Join Source Timeline Alignment: [0058-late-join-source-timeline-alignment-amendment.md](./0058-late-join-source-timeline-alignment-amendment.md) — source-incarnation ingress sequence mapping into the shared session playout domain; [implementation plan](../analysis/0058-late-join-source-timeline-alignment-implementation-plan.md) authorized (code not authorized).

**Premise (ADR-0059 — Underlay Product Dependency):**

The product owner guarantees:

```text
fixed Module IP addresses
reliable efficient multi-hop multicast
underlay-owned routing and bounded replication
no App-layer next-hop or forwarding tree
```

This is accepted product risk, not laboratory evidence. Implementation remains separately gated, and ADR-0056 remains until product field capacity gates pass.

---

## Why this ADR exists

Underlay already delivers a shared multicast domain. Conference media therefore MUST be a **single group media domain**, not an elected media Anchor and not N−1 WebRTC edges.

ADR-0056 correctly separated topology truth from roster-derived recovery. That separation **stays**. The **media topology** (star / MCU-lite / edge recovery) does **not** stay as Conference V1 default.

---

## Decision — Conference Multicast V1

```text
Fixed IP
Reliable efficient multi-hop multicast
No Conference Anchor
Each admitted Module independently sends RTP/SRTP
Every Module locally mixes
```

```text
Channel Membership
        │
        ▼
Conference Session
        │
        ├── MembershipView
        ├── conferenceEpoch
        ├── mediaKeyEpoch
        └── Multicast Endpoint
                │
       ┌────────┼────────┐
       ▼        ▼        ▼
     Module RTP streams (independent senders)
                │
                ▼
        Local mix (Top-K decode)
```

**Deleted from Conference V1 target (after cutover):**

```text
ConferenceAnchor · AnchorRanking · anchorEpoch
ConferenceTopologySnapshot as media-edge authority
ActualMediaEdgeSet · AdmittedRecoveryTarget
ConferenceAudioBus (MCU-lite fan-out)
Conference Anchor succession / 换锚
Per-member PeerConnection
Conference ICE recovery edge
```

**Retained:**

```text
Conference Membership · Conference Lifecycle · ConferenceHealth
Membership vs media-state separation
Control plane vs media plane separation
Reject non-current epoch / key
Media execution MUST NOT block control
```

MCU-lite remains a **paper B-plan** only if a later capacity gate **fails**. It is **not** a V1 dual stack.

---

## Three unique control facts

### MembershipView

Who may be in the conference and who may send media.

```text
MembershipView {
    conferenceId
    conferenceEpoch
    membershipVersion
    members[]   // each: moduleId + membershipIncarnationId (Q4/Q9)
}
```

Temporary unreachability **MUST NOT** remove membership. Absence of RTP **MUST NOT** imply leave.

### conferenceEpoch

Instance identity of this Conference. Non-current epoch **MUST** reject:

```text
invites · membership snapshots · media packets ·
key updates · terminate messages
```

**MUST NOT** increment `conferenceEpoch` for: mesh route change, transient loss, silent sender, SSRC restart, underlay repair.

### mediaKeyEpoch

Group media key generation. Distinct from `conferenceEpoch`.

```text
Any Mutation that actually changes completeMembershipView
  → membershipVersion MUST + 1
  → mediaKeyEpoch MUST + 1
  (ADD, REMOVE, REVOKE — Q4)
Audit-only updates MUST NOT bump either counter.
```

Rotating media keys **MUST NOT** create a new Conference. Plaintext group key MUST NOT appear on broadcast Facts. Staged key MAY be pre-distributed; activation requires the matching Membership Fact (**Q4**).

---

## Conference Owner (control writer, not media)

Dynamic membership still needs **one writer** to avoid conflicting snapshots.

```text
Conference Owner
  = create, invite, membership mutate, key rotate, terminate

Conference Owner
  ≠ media Anchor
  ≠ mixer
  ≠ forwarder
  ≠ required RTP path
```

Owner unreachable:

```text
Existing members CONTINUE multicast media
Membership FROZEN
No other Module MAY mutate membership or rotate keys
Owner return → resume control
```

Owner explicit terminate or leave-with-terminate: **Q3** (Termination Fact origin = contract TERMINATED; local stop on accept).

**V1 Owner permanent disappearance without Termination Fact:**

```text
ORPHAN Conference MAY continue for current members
Membership stays frozen
No succession
```

**Conference Authority Succession** is a **Non-goal** of this ADR.

## Creation Fact (Grill Q1 — ACCEPTED)

```text
Only a Module that already satisfies local Conference-create admission
AND is acting on an explicit create intent
MAY originate a Conference Creation Fact.

That Module becomes Conference Owner at origin time.
Owner is not an election result.
Owner MUST NOT be inferred or inherited by any other Module.
```

The Creation Fact **MUST** bind atomically:

```text
conferenceId
conferenceEpoch
ownerModuleId
Media Group Descriptor:
  multicastAddress
  media/control ports
  codec / traffic profile
initial MembershipView + membershipVersion
initial mediaKeyEpoch
media key commitment / distribution descriptor
```

```text
Owner MAY generate the group media key.
Owner MUST NOT publish plaintext key material in a broadcast Creation Fact.
Creation Fact publishes key epoch, commitment, and distribution semantics only.
Actual key material MUST be provided securely to admitted members only.
```

Other Modules:

```text
MAY: verify a complete Creation Fact; accept and join; forward verified Fact unmodified;
     request missing Membership / key material
MUST NOT: mint conferenceEpoch; alter ownerModuleId; replace group address or ports;
          alter initial MembershipView; mint mediaKeyEpoch; treat forward as re-origin
```

A Conference **exists for a receiving Module** only after it accepts one **complete, atomic, verified** Creation Fact. MUST NOT assemble Owner, address, Membership, and key epoch from unrelated messages (**no Frankenstein Conference**).

Same `(conferenceId, conferenceEpoch)` with different Owner or different immutable create semantics:

```text
→ CREATION_CONFLICT
→ MUST NOT silent LWW, first-arrival pick, or join either candidate
```

V1 **keeps** `CREATION_CONFLICT` (no silent LWW / first-arrival pick / join-either). How to *dissolve* the conflict is **not** a remaining behavior axis (**Q13**). Address commit / collision / reuse: **Q2** / **Q10**. Q1 only adds: **Owner is the sole role that may commit the Media Group Descriptor inside the Creation Fact.**

Owner loss after others have accepted the Creation Fact: existing members continue multicast; membership/key mutation frozen; no auto-succession; no Termination Fact → V1 orphan (already frozen).

If Owner fails **before originating** a Creation Fact:

```text
local Descriptor Candidate only  ≠  an originated Conference
```

Q1 “nobody has accepted yet” is **not** Owner’s immutability boundary (Owner cannot prove that). **Q2** freezes Owner commit at **origin / first observability**. After origin, same `(conferenceId, conferenceEpoch)` Descriptor MUST NOT be rewritten even with zero ACKs. Receivers still have no Conference until they accept (Q1).

---

## Media Group Descriptor (Grill Q2 — ACCEPTED)

### Commit split

```text
Owner commit
  = the instant the Creation Fact is first originated
    and becomes observable to the network

Receiver commit
  = this Module accepts one complete, verified Creation Fact
```

```text
Creation Fact not yet originated
  → Descriptor Candidate only
  → Owner MAY replace the Candidate freely

Creation Fact originated
  → Descriptor immutable for that (conferenceId, conferenceEpoch)
  → Owner MUST NOT rewrite it, including when no ACK is observed
```

MUST NOT: treat “media not started and Owner believes nobody accepted” as license to re-originate the same epoch with a different Descriptor. A delayed or partitioned receiver MAY already have accepted the first Fact.

### Candidate Claim (non-authoritative)

```text
Before origin, Owner MAY publish an authenticated Descriptor Candidate Claim
to probe occupancy / collision.

Claim MAY be withdrawn or replaced.
Claim is NOT a Creation Fact.
Claim does NOT create a Conference.
Observing no conflict ≠ proof of global uniqueness.
```

### V1: no descriptorVersion

```text
Media Group Descriptor is identified by (conferenceId, conferenceEpoch).
It is immutable for the life of that epoch.
V1 MUST NOT introduce descriptorVersion.
Same-epoch relocate, if ever allowed, requires a new ADR.
```

### MediaGroupEndpoint

```text
MediaGroupEndpoint {
  underlayScopeId
  ipFamily
  multicastAddress
  mediaPort
  hopLimit / scope
  trafficProfileId
}
```

Local interface names are not shared network fields. Every Module MUST bind the specified Mesh underlay. `controlPort` remains a Creation Fact field (Q1) and is part of immutable create semantics when present; collision matching for media uses `MediaGroupEndpoint` as defined above.

### Two conflict domains (must not substitute)

```text
CREATION_CONFLICT
  = same (conferenceId, conferenceEpoch)
    with different Owner or different immutable Creation semantics
  independent of whether addresses match

MEDIA_GROUP_COLLISION
  = different (conferenceId, conferenceEpoch)
    using the same MediaGroupEndpoint on the same underlayScopeId
    and the two Conferences’ lifetimes MAY overlap
```

Both MAY be true at once. A verified-terminated Conference that has completed **data-plane** isolation MAY later reuse an Endpoint only under **Q10**. Control Facts never expire by time. If reuse evidence is insufficient, treat as **possibly still active**. Orphan Conferences (no Termination Fact) MUST NOT be treated as reusable.

### Detection

```text
Before origin:
  Owner MAY emit Candidate Claim
  Observed occupancy / conflict → replace local Candidate
  No observation ≠ uniqueness proof

After Creation Fact origin:
  Any Module that observes two possibly concurrent Creation Facts
  (or equivalent authenticated Descriptor Claims bound to those Facts)
  using the same MediaGroupEndpoint
  → MUST record MEDIA_GROUP_COLLISION
```

Network partition can hide Claims; runtime detection remains mandatory.

Foreign RTP / SRTP decrypt failure:

```text
MAY be collision suspicion / telemetry
MUST NOT alone prove collision
MUST NOT alone prove collision resolved
```

Formal evidence remains two verified Creation Facts, or equivalent authenticated Descriptor Claims.

MUST NOT: truncated `conferenceId` hash as uniqueness; receiver-local address rewrite; change address/port without a new epoch; own-media loopback as no-collision proof; long-term SRTP-fail filtering as the collision detector or as resolution.

### Local conduct (affiliation)

```text
This Module has accepted neither colliding candidate:
  → MUST NOT pick one and join
  → stay COLLISION_BLOCKED

This Module has already been admitted to one Conference:
  → MAY continue media strictly bound to the accepted Creation Fact
  → MUST reject the other Conference’s media
  → MUST project COLLISION_DEGRADED
  → MUST NOT claim the collision is resolved
  → MUST NOT join the second candidate
```

SRTP / Conference-identity filtering remains mandatory **isolation**, not resolution:

```text
Isolated continuation of already-admitted media
  ≠ treating a shared endpoint as healthy/normal
```

If the collision drives the declared product workload or underlay dependency outside its supported envelope, the existing Conference MUST NOT be projected healthy.

### Who may replace / relocate

In-place address change after origin is forbidden. Relocate = terminate this epoch, then Replacement (**Q3**): new Conference + verified `replacesCreationDigest`; not same-epoch successor.

```text
Current Owner
  MAY explicitly terminate the current Conference
  MAY originate a Replacement Conference only under Q3
      (new id/epoch/Creation/Descriptor/key; verified replacesCreationDigest)

Non-Owner
  MUST NOT claim a new Conference is successor of the old one

Any other Module with create admission
  MAY originate a brand-new Conference
  MUST use a new conferenceId
  MUST NOT inherit the old Owner, Membership, key, or lifecycle continuity
```

Owner gone (orphan, already frozen):

```text
Descriptor frozen
No in-place relocate
No follower auto-succession
```

---

## Termination and Replacement (Grill Q3 — ACCEPTED)

### Who may terminate

```text
Only the current Conference Owner
MAY originate a Termination Fact.

Member leave
  → local exit and/or request Membership removal
  → MUST NOT terminate the Conference

Media silence, Owner temporarily unreachable, Mesh partition
  → are not termination
  → MUST NOT originate Termination Fact
  → MUST NOT infer contract TERMINATED
```

### Termination Fact (atomic)

Authoritative terminal semantics:

```text
conferenceId
conferenceEpoch
ownerModuleId
terminatedCreationDigest
terminal disposition = TERMINATED
```

`terminationReason` is **audit metadata**, not a gate on whether the Conference is terminated.

`terminatedCreationDigest` binds the Creation Fact being closed. Digest mismatch for this Module’s accepted Creation → `NOT_APPLICABLE` / `INVALID_TARGET`, not a Termination Conflict.

### Contract vs local knowledge

```text
The instant a legal Termination Fact is originated and becomes observable:
  the Conference is TERMINATED in contract.

A Module that has not yet obtained that Fact
  MAY continue old-epoch execution temporarily.
  That is termination not yet visible,
  not continued lawful existence of the Conference.

After this Module accepts a matching Termination Fact:
  Local Lifecycle Knowledge → TERMINATED
```

```text
Contract Terminated
  ≠ every Module already knows
  ≠ every Module has finished cleanup
```

Same shape as ADR-0057: **Superseded ≠ locally known Stale**.

### Re-provide (Owner need not stay online)

```text
Owner that originates a Termination Fact
  MUST make it obtainable by other members.

Any Module that has accepted that Termination Fact
  MUST retain full verified semantics
  MUST re-provide the Fact unmodified to reachable requesters.

Forward / re-provide
  ≠ originate termination
  ≠ acquire Owner authority
```

Without this, Owner terminate-then-disappear leaves never-notified members on an orphaned live group forever.

### Local accept conduct

On accept of a matching legal Termination Fact, this Module MUST:

```text
stop local send
leave the Media Group
fence all Sender Sources
refuse late media of that epoch
refuse non-termination operational control of that epoch:
  Membership mutation
  key rotation
  Descriptor / media admission
  Sender admission
MUST NOT resurrect old Membership / key / Descriptor
```

Still MAY:

```text
idempotently accept a matching Termination Fact
forward / re-provide termination evidence
offer audit / diagnostic facts
```

### Conflicts (do not swallow other domains)

```text
Same Owner
+ same conferenceId / conferenceEpoch
+ same terminatedCreationDigest
+ both declare TERMINATED
→ terminal result is idempotent
→ Local Lifecycle MAY become TERMINATED on first matching Fact

Differing terminationReason (or other audit metadata)
  → TERMINATION_METADATA_CONFLICT
  → retain both audit evidences
  → MUST NOT silently pick a reason
  → MUST NOT block Lifecycle TERMINATED
```

Not Termination Conflict:

```text
Creation Digest does not match this Module’s Creation
  → NOT_APPLICABLE / INVALID_TARGET

Signature / authentication failure
  → INVALID_FACT

This Module’s Creation Fact itself conflicts
  → CREATION_CONFLICT
```

MUST NOT merge `TERMINATION_METADATA_CONFLICT`, `CREATION_CONFLICT`, and `MEDIA_GROUP_COLLISION`.

There is **no** V1 `TERMINATION_CONFLICT` that reopens a Conference already declared TERMINATED on the same Creation.

### Two independent Facts

```text
Termination Fact and Replacement Creation Fact are independent.

A new Creation Fact MUST NOT implicitly terminate an old Conference.
Failure to create a Replacement MUST NOT revoke an originated Termination.
```

### Replacement is a new Conference

```text
Replacement Conference
  = new conferenceId
  + new conferenceEpoch
  + new Creation Fact
  + new Descriptor
  + new mediaKeyEpoch / key
```

Association metadata (verifiable, not lifecycle continuity):

```text
replacesConferenceId
replacesConferenceEpoch
replacesCreationDigest
replacementReason
replacesTerminationDigest   (MAY, if a Termination Fact already exists)
```

```text
Only if the new Conference’s originator
    == Owner on the replaced Creation Fact
AND replacesCreationDigest matches
→ MAY project a formal Replacement relation

Old Creation Fact unavailable or digest mismatch
  → the new Conference MAY still be independently valid
  → replacement relation = UNKNOWN / UNVERIFIED
  → MUST NOT claim formal succession
```

Association metadata ≠ inherit Membership, Owner, auto-admit old members, or permission to migrate old media.

```text
Only the old Owner MAY declare formal Replacement.

Any other create-admitted Module
  MAY originate a brand-new Conference (new conferenceId)
  MUST NOT set replaces* as inheritance of the old Conference
  MUST NOT claim successor
```

### No relocate dual-send

```text
A Replacement relation itself
  MUST NOT start a media bridge
  MUST NOT auto-send this Module’s PCM to both groups
  MUST NOT copy old Sender Sources into the new Conference
```

A user simultaneously in two independent Conferences follows existing Foreground / media-admission contracts. That is **not** an address-migration mechanism.

```text
Replacement created before old Conference is terminated
  → MUST NOT implicitly terminate the old Conference
  → two independent Conferences
```

### Owner permanently gone

```text
No legal Termination Fact
  → V1 orphan continues for current members
  → Descriptor / Membership / key frozen
  → follower MUST NOT terminate the Conference
  → follower MUST NOT originate a formal Replacement

Local leave and local resource cleanup remain allowed
  → that is not global Termination
```

**Conference Authority Succession** remains a Non-goal of this ADR.

---

## Membership Mutation and Media Key Rotation (Grill Q4 — ACCEPTED)

### Writer

```text
Only the current Conference Owner
MAY originate a Membership Mutation Fact
and the complete Membership Snapshot it carries.

Other Modules:
  MAY request join / leave / remove
  MAY forward a verified Fact unmodified
  MUST NOT edit MembershipView
  MUST NOT mint membershipVersion / mediaKeyEpoch
```

### Owner MUST remain in Membership

```text
Any not-TERMINATED Membership Snapshot
  MUST include the current Conference Owner.

Owner MUST NOT remove themselves via Membership Mutation.
Owner leave / Owner remove
  → MUST use Q3 Termination
  → MUST NOT create a still-living Conference with no Owner.
```

V1 orphans arise only from abnormal Owner disappearance, never from a legal Mutation.

Owner gone: Membership and key **frozen**. Followers MUST NOT originate Mutation to “repair”. Join/leave requests MUST NOT become a new snapshot until Owner returns (or the Conference is Q3-terminated).

### Atomic complete Fact (no Frankenstein)

```text
Conference Membership Fact {
  conferenceId
  conferenceEpoch
  ownerModuleId
  membershipVersion
  previousMembershipDigest
  completeMembershipView
  mediaKeyEpoch
  mediaKeyCommitment
  mutationReason
}
```

Snapshot MUST be the complete member set. MUST NOT assemble Membership from incremental patches.

Each entry in `completeMembershipView`:

```text
Membership Entry {
  moduleId
  membershipIncarnationId
}
```

```text
First admit (Creation initial View or ADD)
  → mint a new membershipIncarnationId

Retained across later Snapshots
  → membershipIncarnationId unchanged

REMOVE then later ADD of the same moduleId
  → MUST mint a fresh membershipIncarnationId
```

Initial members’ incarnation MAY bind the Creation Fact digest. Encoding deferred.

Leave Request targets the **incarnation**, not a frozen Snapshot digest (**Q9**).

Creation Fact initial `MembershipView` is Membership v0 (or equivalent) for the epoch. Each later Fact’s `previousMembershipDigest` MUST bind this Module’s currently accepted Membership Fact (or that initial snapshot).

Plaintext group key MUST NOT appear on the broadcast Membership Fact. Only `mediaKeyEpoch` + commitment; key material only via secure distribution.

### Every real membership change rotates the key

```text
Any Mutation that actually changes completeMembershipView:
  membershipVersion MUST + 1
  mediaKeyEpoch MUST + 1

Includes ADD, REMOVE, REVOKE.
```

**Profile 01 Q7 SECURITY_REKEY amendment:** a verified compromise of a Module
key-establishment private key may expose the current group secret through a
captured per-recipient Package. The Owner MAY therefore originate a
`SECURITY_REKEY` Membership Mutation whose `completeMembershipView` is
identical. This is not audit-only: `membershipVersion` and `mediaKeyEpoch` MUST
both increment, and the media secret, context digest, commitment and Packages
MUST all be new. If the affected Module is removed, normal REMOVE/REVOKE remains
the preferred path and already rotates the key.

Rationale: a new member MUST NOT decrypt pre-join group ciphertext (V1 outsider threat). A removed member MUST NOT decrypt post-remove media. One activation rule for Membership and key.

Audit-only updates that do not change `completeMembershipView` MUST NOT increment `membershipVersion` or `mediaKeyEpoch`.

### Contract vs local knowledge

```text
Owner originates a legal Membership Fact
  → contract Membership becomes that complete snapshot immediately
  → contract mediaKeyEpoch becomes the Fact’s epoch immediately
  → REMOVE/REVOKE targets immediately lose send eligibility in contract
  → there is no second legal Membership

A Module that has not obtained the Fact
  MAY still execute the old snapshot temporarily
  → termination/mutation not yet visible,
    not continued legality of the old Membership
```

Same shape as Q1/Q3: contract change ≠ locally known ≠ local key switched.

### Staged key vs activation

```text
Staged Media Key
  = securely delivered to intended members
  + bound to the pending:
      conferenceId
      conferenceEpoch
      next membershipVersion
      next mediaKeyEpoch
      expected membership digest
      key commitment

Staged ≠ Active.
```

```text
Owner MAY pre-distribute the staged key to members of the next snapshot
before originating the Membership Fact.

Membership Fact origin
  → contract Membership and mediaKeyEpoch take effect atomically.

Receivers:
  MAY activate a staged key only after accepting the matching Membership Fact.
  Key material without that Fact
    → MUST NOT self-switch the active key.
```

A retained member missing the key after Fact accept → `MEDIA_KEY_PENDING` only if still in View. MUST NOT roll back Membership. Package origin, commitment context, invalid vs cryptographic conflict, and key retirement: **Q7**. Staged Packages MUST bind the **final** Membership Fact digest; MUST NOT be moved across candidate Facts.

### Chain gaps (not a conflict; no skip)

```text
This Module has accepted v0
and receives a legal v2 whose previousDigest points at unknown v1
  → MEMBERSHIP_CHAIN_PENDING
  → retain the v2 Fact
  → request and verify the missing chain
  → MUST NOT apply v2 because it is a “complete snapshot”
```

Once `v0 → v1 → v2` is fully verified, this Module MAY advance continuously to v2. It is not required to replay old media side-effects of intermediate versions.

After v2 is accepted:

```text
Late v0 / v1
  → history / audit only
  → MUST NOT roll back Membership, key, or Admission Binding
```

Duplicate identical Fact → idempotent.

### Branch conflict

```text
Same previousMembershipDigest
with two different legal successor snapshots
  → MEMBERSHIP_BRANCH_CONFLICT
  → MUST NOT execute either
  → MUST NOT LWW by version, arrival order, or keyEpoch
```

Same `membershipVersion` with different authoritative snapshot fields is a common form of this conflict; version number alone is not the definition.

`mutationReason` audit-only disagreement with identical authoritative fields → `MEMBERSHIP_METADATA_CONFLICT`; MUST NOT roll back an already accepted authoritative snapshot.

MUST NOT substitute `MEMBERSHIP_BRANCH_CONFLICT` / `MEMBERSHIP_CHAIN_PENDING` / `CREATION_CONFLICT` / `MEDIA_GROUP_COLLISION` / `TERMINATION_METADATA_CONFLICT`.

Pre-grill “higher membershipVersion / mediaKeyEpoch wins on sight” is **void**.

### Re-provide

```text
Any Module that accepted a Membership Fact
  MUST retain full verified semantics
  MUST re-provide the Fact unmodified to reachable requesters.

Re-provide
  ≠ originate Mutation
  ≠ acquire Owner authority
  ≠ edit the Snapshot
```

### Local accept — still a member vs removed

This Module still in `completeMembershipView`:

```text
accept the new Membership
activate the matching staged key, or enter MEDIA_KEY_PENDING
while pending: stop send and refuse old-key media
```

This Module not in `completeMembershipView`:

```text
stop send immediately
fence all local send eligibility
leave the Media Group
MUST NOT request or activate the new key
Local Membership = REMOVED / REVOKED
```

Remote member removed:

```text
on Fact accept, fence that Sender Source immediately
MUST NOT wait for this Module to obtain the new key
```

Send eligibility:

```text
New member MAY send locally only after:
  accepted Membership Fact listing them
  AND holding the Fact’s current key material

Removed member loses send:
  in contract = instant of Owner origin
  locally     = instant of Fact accept (not when new key arrives)
```

Key distribution failure for one member → that member (or this Module) stays `MEMBERSHIP_ACCEPTED / MEDIA_KEY_PENDING`. MUST NOT roll back group Membership. MUST NOT mint a lower `membershipVersion`.

### Termination outranks late Mutation

```text
This Module has already accepted a Q3 Termination Fact
  → MUST NOT apply any later Membership Fact for that epoch
  → even if signature, digest chain, and version are otherwise legal
```

Termination is terminal relative to Membership Mutation.

---

## Sender Source Admission (Grill Q5 — ACCEPTED)

### Three layers (must not substitute)

```text
Membership Fact
  → which Modules have send eligibility (Q4)

Sender Source Declaration
  → binds senderModuleId to one SSRC / source incarnation
    with verifiable predecessor lineage

SRTP group authentication
  → proves the sender holds the current group key
  → does NOT prove it is the claimed Module
```

Pre-grill `admitted Sender Source = (senderModuleId, SSRC)` is **void**. SSRC is not persistent Module identity.

### Sender Source Identity

```text
Sender Source Identity {
  conferenceId
  conferenceEpoch
  membershipVersion
  mediaKeyEpoch
  senderModuleId
  sourceGeneration
  sourceInstanceId
  ssrc
  previousSourceDigest
  mediaGroupDescriptorDigest
}
```

```text
mediaGroupDescriptorDigest
  binds the Q2 committed Descriptor / Traffic Profile.

Sender MUST NOT independently declare codecProfile.
The Conference has one Traffic Profile.
```

`sourceInstanceId` MUST be globally high-probability unique and **not reused**.

```text
sourceGeneration
  monotonic +1 for the same senderModuleId in the same conferenceEpoch

previousSourceDigest
  explicitly binds the Declaration being superseded
  (absent / zero only for the first incarnation in the epoch)
```

Together they prevent last-arrival overwrite, generation skip, two new Sources both claiming to replace one old Source, and unordered random IDs after restart.

### Atomic Declaration Fact

```text
The sending Module originates a Source Declaration and makes it observable
  → the new incarnation is in effect in contract
  → the incarnation named by previousSourceDigest is immediately Superseded.

This Module accepts a complete, verified, chain-continuous Declaration
  → fence the local old Source
  → install the new Source Admission.
```

A Module that has not obtained the Fact MAY temporarily execute the old Source. That is Fact not yet visible, not continued legality of the old Source.

```text
This Module holds generation 2
and receives generation 4
  → SOURCE_CHAIN_PENDING
  → request generation 3
  → MUST NOT skip-chain because the number is larger
```

Any accepted holder MUST retain full verified semantics and re-provide the Declaration unmodified. Forward / re-provide ≠ originate Source.

### Who may originate (protocol rule, not crypto)

```text
Only the Module whose senderModuleId is claimed,
that is in the current Membership View in contract,
and that holds the current active group key,
MAY originate its own Sender Source Declaration.

Owner MUST NOT mint Source for others.
Other Modules MUST NOT rewrite another Module’s Source Identity.
MEDIA_KEY_PENDING / REMOVED / local TERMINATED
  → MUST NOT originate an active Source Declaration.
```

This is **protocol legality**, not V1 cryptographically enforced intra-group identity.

### New incarnation ⇒ new SSRC (crypto invariant)

```text
Each new sourceInstanceId
  MUST use a new random SSRC.

Within the same Conference and same mediaKeyEpoch:
  an SSRC already used by any incarnation
  MUST NOT be reused.
  An SSRC confirmed in SSRC_COLLISION is BURNED (Q6)
  and MUST NOT be restored.

Media send-pipeline rebuild
  → new sourceInstanceId
  → new SSRC
  → new SRTP sender context / packet index.
```

RTP sequence wrap:

```text
Legal wrap
  → SRTP rollover counter continues
  → MUST NOT create a new incarnation.

Sequence / ROC reset
  → MUST create a new incarnation and a new SSRC.
```

Reusing SSRC under the same `mediaKeyEpoch` while resetting the packet index risks nonce / keystream reuse. This is a cryptographic requirement, not only a state-machine nicety.

Must new `sourceInstanceId` + new SSRC + new Declaration:

```text
send-pipeline rebuild
membershipVersion change (after Q4 Fact + new key active)
mediaKeyEpoch change (same)
ROC / SRTP index reset
```

MUST NOT treat as remap / new incarnation:

```text
Mesh route change
VAD silence / resume
local Decoder rebuild
legal RTP sequence wrap
```

Late RTP of a Superseded / fenced / quarantined incarnation MUST NOT resurrect it. Independent timeout MUST NOT replace explicit supersede.

### Activation order after Membership / key change

```text
New Membership Fact accepted
AND new key activated
  → sender MAY originate a new Source Declaration
  → only then MAY send RTP for the new incarnation

MUST NOT send new-key RTP before the Source Declaration.
Receivers MUST NOT auto-create a Declaration from unknown RTP.
```

V1: at most one **active** send Source per Module per Conference at a time. A new incarnation MUST explicitly supersede via `sourceGeneration` + `previousSourceDigest`. MUST NOT overwrite because RTP arrived later, level is louder, or SSRC is newer.

### Conflicts — MUST quarantine (resolution later)

```text
SOURCE_BRANCH_CONFLICT
  = two different Declarations share the same previousSourceDigest
    and both claim to be the next sourceGeneration
  = lineage authority conflict

SOURCE_MULTI_ACTIVE
  = execution / projection sees multiple unfenced Sources
    for one senderModuleId
```

They MAY both be true. MUST NOT substitute.

```text
SOURCE_IDENTITY_CONFLICT
  = same sourceInstanceId with different immutable semantics

SSRC_COLLISION
  = same SSRC declared (or still unfenced) for different senderModuleId
    or overlapping incarnations
```

On conflict, this Module MUST quarantine every candidate in scope and MUST NOT send any candidate to decode / mix:

```text
SOURCE_MULTI_ACTIVE     → all conflicting incarnations of that senderModuleId
SOURCE_BRANCH_CONFLICT  → both successor Declarations (and their RTP)
SSRC_COLLISION          → every incarnation using that SSRC
SOURCE_IDENTITY_CONFLICT → every semantic candidate for that sourceInstanceId
```

`SSRC_COLLISION` MUST stop admission immediately: same group key + SSRC + overlapping packet index can reuse an SRTP nonce.

MUST NOT pick a winner by RTP arrival, audio level, or SSRC numeric value. Convergence is **Q6** (cover listed claims only; collided SSRC BURNED; no restore of the unmoved party).

MUST NOT merge these with `CREATION_CONFLICT` / `MEDIA_GROUP_COLLISION` / `MEMBERSHIP_BRANCH_CONFLICT`.

### Local decode / mix admission

RTP/SRTP MAY enter decode / mix only if:

```text
current Membership contains senderModuleId
AND group-member auth passes for current mediaKeyEpoch
AND Source Declaration chain is continuous and names the current incarnation
AND the Source is not superseded / fenced / quarantined
AND mediaGroupDescriptorDigest matches the committed Descriptor
AND an authenticated RTP discriminator uniquely maps to that Declaration
AND packet SSRC matches the Declaration
```

Header-extension layout is **Q11**. Discriminator MUST uniquely map the Declaration after SRTP auth; SSRC alone MUST NOT guess incarnation. Unauthenticated Packet Context Hint MUST NOT create state or fetch Facts.

Q4 REMOVE accepted locally → fence all Sources for that Module immediately. Q3 local TERMINATED → fence all Sources; refuse new Declarations.

### Threat model (unchanged in kind)

```text
Source Declaration + SRTP provide:
  outsider isolation
  accidental SSRC collision detection
  protocol state binding (generation / predecessor / fence)

They do NOT provide unique Module Source Authentication
against a malicious admitted member.

A malicious member can still forge:
  senderModuleId
  sourceInstanceId
  Source Declaration
  matching SRTP
```

The system MAY detect state conflict. It MUST NOT reliably attribute the forger to a physical Module. Intra-group impersonation defense requires a **separate identity-signature contract**. Shared SRTP MUST NOT be described as having solved CM-AUTH-3. RTP HE `senderModuleId` is in the group-auth scope and is likewise **not** intra-group source authentication.

---

## Source Conflict Convergence (Grill Q6 — ACCEPTED)

Timeout, VAD, RTP arrival order, audio level, and SSRC numeric value MUST NOT resolve conflicts.

### Two domains (must not mix)

```text
Same senderModuleId
  SOURCE_BRANCH_CONFLICT
  SOURCE_MULTI_ACTIVE
  SOURCE_IDENTITY_CONFLICT confined to that sender
  SOURCE_RESOLUTION_CONFLICT
→ only the claimed sender MAY originate a Source Resolution Fact
→ Owner MUST NOT mint Resolution
→ other Modules MUST NOT rewrite another sender’s lineage

Different senderModuleId
  SSRC_COLLISION
  sourceInstanceId collision across Modules
→ each sender MAY supersede only its own Source
→ Owner MUST NOT pick a winner
→ a Resolution MUST NOT merge two Modules into one Source
```

Owner MAY Q4 REMOVE/REVOKE a persistently offending member. That revokes send eligibility; it is **not** Source adjudication. After that Membership Fact, that sender’s Declarations / Resolutions bound to the old `membershipVersion` / `mediaKeyEpoch` lose admission automatically.

### Collided SSRC / instance are BURNED (no restore)

Confirmed `SSRC_COLLISION`:

```text
That SSRC is BURNED for the current (conferenceId, mediaKeyEpoch)
Every incarnation that used it is permanently fenced
MUST NOT un-quarantine and resume that SSRC
```

Same group key + SSRC + overlapping SRTP packet index may already have reused nonce / keystream. Stopping one party does **not** make the old SSRC safe.

Every Module that still needs to send:

```text
MUST originate a new Source incarnation
+ new sourceInstanceId
+ new random SSRC that is unused and not BURNED
```

```text
Not “one yields, the other resumes the old Source”
but “the collided SSRC is retired; both redeclare”
```

Cross-Module `sourceInstanceId` collision: that `sourceInstanceId` is retired for this `conferenceEpoch`; every conflicting incarnation MUST use a new instance id.

A sender that does not redeclare stays with **no active Source**. That MUST NOT block other senders that have already moved to safe SSRCs.

### Source Resolution Fact (covers listed claims only)

Do not stuff multiple conflict candidates into a normal Declaration’s single `previousSourceDigest`.

```text
SourceConflictClaim
  = Source Declaration digest
  | Source Resolution Fact digest
```

```text
Source Resolution Fact {
  conferenceId
  conferenceEpoch
  membershipVersion
  mediaKeyEpoch
  senderModuleId
  lastUncontestedSourceDigest   // MAY be explicit none
  resolvesSourceConflictSet[]   // SourceConflictClaim
  newSourceGeneration
  newSourceInstanceId
  newSSRC
  mediaGroupDescriptorDigest
}
```

```text
Resolution origin
  → claims listed in resolvesSourceConflictSet
    lose execution eligibility in contract immediately
  → claims not in the set are NOT silently resolved
```

The new incarnation MAY be projected as this sender’s current Source **locally** only if there is **no known uncovered conflict claim**.

If uncovered branches exist in contract but this Module has not observed them:

```text
this Module MAY temporarily admit the new Source
  → conflict Fact not yet visible
later observation of an uncovered claim
  → MUST re-quarantine immediately
  → MUST NOT claim the prior Resolution globally resolved the conflict
```

Same coverage shape as ADR-0057 competing Facts: listed set only.

### lastUncontestedSourceDigest

```text
explicit none
  = Source lineage starts from the Conference / Membership baseline
    (two initial Declarations branched with no predecessor)
  ≠ unknown / missing predecessor

unknown / missing predecessor
  → still SOURCE_CHAIN_PENDING
  → MUST NOT treat as explicit none
```

A later complete Resolution MAY set:

```text
lastUncontestedSourceDigest = last uncontested lineage fact
resolvesSourceConflictSet   = all known conflicting Declaration and Resolution digests
newSourceGeneration         > every conflict-claim generation
```

Subsequent ordinary Declaration:

```text
previousSourceDigest = digest of the accepted Resolution Fact
```

```text
newSourceInstanceId MUST be fresh and not reused
newSSRC MUST be fresh random, unused, and not BURNED
in this Conference + mediaKeyEpoch
membershipVersion / mediaKeyEpoch / mediaGroupDescriptorDigest
MUST match the current accepted Membership and Q2 Descriptor
else NOT_APPLICABLE / INVALID_TARGET
```

### Same-sender lift of quarantine

This Module lifts lineage quarantine if and only if:

```text
Resolution verifies
AND covers every locally known related conflict claim
AND new generation / instance / SSRC are legal
AND new SSRC is unused and not BURNED
AND Membership / key / Descriptor are still current
```

Then: atomically fence the listed set, install the new Source Admission, **then** that sender MAY send the new incarnation’s RTP.

Identical immutable Resolution Facts → idempotent.

### SOURCE_RESOLUTION_CONFLICT

```text
Two semantically different Resolutions on the same base
  → SOURCE_RESOLUTION_CONFLICT
  → quarantine both new incarnations

Later resolution
  → a new Resolution MUST list both conflicting Resolution Fact digests
    in resolvesSourceConflictSet
```

MUST NOT LWW. MUST NOT substitute for `SOURCE_BRANCH_CONFLICT`.

### Cross-sender SSRC collision lift

Lift is **not** restoring old Sources:

```text
All old incarnations that used the collided SSRC stay fenced
Each sender that still needs to send declares a new Source
New SSRCs do not collide and are not BURNED
  → admit those new incarnations separately
```

### Re-provide and terminals

Accepted holders MUST retain full verified semantics and re-provide Resolution Facts unmodified. Forward ≠ originate Resolution ≠ acquire that sender’s authority.

Q3 local TERMINATED → late Resolution MUST NOT apply.  
Q4 local REMOVE of that sender → MUST NOT install its new Source.

### Threat model

Resolution + SRTP remain **group-member auth only**. “Only that Module MAY originate its Resolution” is protocol legality, not cryptographically enforced intra-group identity. A malicious admitted member can still forge another Module’s Resolution. Conflict detection ≠ physical attribution.

---

## Group Media Key Distribution (Grill Q7 — ACCEPTED)

### Four layers (must not substitute)

```text
Membership Fact
  → who is eligible to obtain the key (Q4)

Media Key Package
  → delivers key material to one Module

Key Commitment
  → verifies material against the Membership Fact

Activation
  → STAGED → ACTIVE only after matching Fact (Q4)
```

Commitment is published on the Membership Fact (and Creation initial snapshot). It is **not** a second Membership authority. A Package is **not** a Membership Fact.

### Who may originate

```text
Only the current Conference Owner
MAY originate a Media Key Package
(or an explicitly Owner-authorized equivalent that still binds Owner origin).

Other Modules:
  MAY relay an authenticated encrypted Package unmodified
  MAY re-provide Membership Facts (including commitment)
  MUST NOT decrypt then re-wrap for another recipient
  MUST NOT modify a Package
  MUST NOT forward plaintext group key to other members
```

Relay ≠ originate ≠ Owner authority.

Owner gone: followers MUST NOT mint Packages for a new key epoch (Q4 freeze). Undelivered members stay `MEDIA_KEY_PENDING` if still in View. Membership is not rolled back.

### Media Key Package

```text
Media Key Package {
  conferenceId
  conferenceEpoch
  membershipVersion
  mediaKeyEpoch
  membershipFactDigest
  mediaKeyCommitment
  recipientModuleId
  recipientKeyVersion
  encryptedKeyMaterial
  packageIdentity
}
```

MUST bind exactly one `recipientModuleId`. MUST NOT broadcast plaintext group key.

Recipient MUST be in the current accepted View **or** in the pending complete snapshot being pre-distributed. A REMOVE/REVOKE target MUST NOT be recipient of a **new** Package.

### Commitment is contextual

`mediaKeyCommitment` MUST NOT be a bare `Hash(key)`.

```text
mediaKeyCommitment
  = one-way commitment over key material AND full key context

context at least:
  conferenceId
  conferenceEpoch
  membershipVersion
  mediaKeyEpoch
  membershipKeyContextDigest
```

Same key bytes MUST NOT verify as matching across Conference / epoch / Membership / keyEpoch. The parent ADR leaves the algorithm to an accepted Implementation Profile; Profile 01 Q5 now fixes it for V1.

**Profile 01 Q5 non-circular clarification:** `membershipFactDigest` is not a
commitment input. The commitment is itself authoritative content of that Fact,
so including the final Fact digest would form an impossible cycle. Profile 01
defines a domain-separated `membershipKeyContextDigest` over the complete
Membership authority core excluding the commitment. Each Package still binds
the final Creation/Membership `factDigest` as an external reference. This
clarification changes no Owner, Membership, staging or activation authority.

### Minimum security properties (mechanism deferred)

```text
Confidentiality        non-recipient MUST NOT obtain key material
Integrity              tamper MUST be detectable
Recipient binding      a Package for M02 MUST NOT be usable as M03’s key
Context binding        MUST NOT replay across conferenceId / epoch /
                       membershipVersion / mediaKeyEpoch / Fact digest
Owner origin binding   recipient MUST verify the Package was originated
                       or explicitly authorized by the current Conference Owner
                       AND bound to the target Membership Fact
Replay protection      duplicate delivery MUST NOT create a second key epoch
```

Recipient-public-key encryption alone is **not** Owner authentication: anyone can encrypt a fake key to that public key. Owner origin MUST be proven separately (or in the same authenticated construction).

Mesh Shared Secret that lets every Module forge an Owner Package is **group-level** origin at most. It does **not** satisfy this contract. Concrete pairwise / Module-public-key / control-channel choice is deferred, but **implementation authorization requires a scheme that meets all six properties**. V1 does **not** accept “every Module on the app subnet can unwrap the Conference key” as satisfying Confidentiality / Recipient binding (that would enlarge the threat model).

CM-AUTH-3 unchanged: group key still does not provide intra-group Module source authentication.

### Pre-distribution binds the final Fact

Owner MAY, before originating the Membership Fact:

```text
construct the final Candidate Membership authority core
compute membershipKeyContextDigest
generate the new media secret and compute mediaKeyCommitment
insert commitment and compute the final membershipFactDigest
mint per-recipient Packages
Packages enter STAGED
Owner originates a Membership Fact with identical authoritative semantics
```

If Owner changes members, version, commitment, or any other authoritative field before origin:

```text
prior staged Packages MUST NEVER activate
MUST mint a new Fact digest and new Packages
```

Recipients MUST NOT move a Package from one candidate Fact to another.

### Local states

```text
Package verified, Fact not yet accepted (including MEMBERSHIP_CHAIN_PENDING)
  → STAGED
  → MUST NOT ACTIVE

Fact accepted, matching Package not yet usable
  AND this Module still in View
  → MEDIA_KEY_PENDING

Fact + Package + commitment + recipient + Owner origin all match
  → ACTIVE

This Module not in the new View
  → REMOVED / REVOKED
  → MUST NOT enter MEDIA_KEY_PENDING
  → MUST NOT request or activate a new key
```

`MEDIA_KEY_PENDING` applies only to Modules still in Membership.

### Fault domains (must not merge)

**INVALID_KEY_PACKAGE** (ordinary mismatch — not a key conflict):

```text
Package commitment ≠ accepted Membership Fact commitment
OR decrypted material does not verify the Fact commitment
→ reject and audit
→ NOT MEDIA_KEY_CONFLICT
```

The accepted Fact already named the sole commitment. A bad Package has no standing to compete.

**MEMBERSHIP_BRANCH_CONFLICT:**

```text
Two Membership successors declare the same mediaKeyEpoch
with different commitments
→ MEMBERSHIP_BRANCH_CONFLICT
→ Key Packages stay pending/quarantined with their branch
```

MUST NOT relabel this as an independent key conflict to hide the Membership branch.

**KEY_PACKAGE_IDENTITY_CONFLICT:**

```text
Same packageIdentity with different recipient / context /
ciphertext / immutable fields
→ MUST NOT pick one
```

**CRYPTOGRAPHIC_KEY_CONFLICT** (commitment/crypto assumption failed):

```text
For the same accepted Membership Fact and same recipient,
two different key materials both verify the same commitment
→ quarantine the entire mediaKeyEpoch
→ MUST NOT activate either key
→ Owner MUST originate a new Membership Mutation:
     new membershipVersion
     new mediaKeyEpoch
     new key and commitment
```

MUST NOT overwrite with a later Package.

Do not use a catch-all `MEDIA_KEY_CONFLICT` that mixes INVALID, BRANCH, identity clash, and cryptographic failure.

### Resend (not conflict)

```text
Identical packageIdentity + identical immutable semantics
  → idempotent (byte-level or Fact-level duplicate)

Different packageIdentity or different encryption randomness
but same decrypted key
AND same recipient / context / commitment / Fact digest
  → equivalent resend, not a key conflict
```

Immutable fields of a given `packageIdentity` MUST NOT change.

### Key retirement (no keep-old-for-reorder)

On accept of a new Membership Fact, the old key **immediately** loses ACTIVE eligibility. Q4 already forbids admitting old-`mediaKeyEpoch` media. Brief retention of old keys for network reordering is **rejected**.

```text
Matching new staged key already present:
  atomically retire old key + activate new key

New key not yet present:
  retire old key + MEDIA_KEY_PENDING (if still in View)
```

MUST:

```text
old ACTIVE key MUST NOT continue send or receive
old staged key MUST NOT be re-activated
REMOVED / REVOKED local MUST make old active and staged keys unusable
TERMINATED MUST make all key material of that Conference unusable
late Key Packages MUST NOT activate
```

Physical memory zeroization is mechanism. Contract: media execution MUST NOT use or restore retired keys.

Single-member delivery failure affects only that member. MUST NOT roll back group Membership. MUST NOT block others who are ACTIVE (their Sources still follow Q5).

Join projection (`INVITED` / `JOIN_REQUESTED`) MAY coexist with Key `STAGED` (**Q8**). STAGED still MUST NOT join the Media Group, send RTP, or originate Source.

---

## Invite / Join Control Path (Grill Q8 — ACCEPTED)

### Four facts (must not substitute)

```text
Invite
  = Owner invites a Module to submit join intent
  ≠ Membership
  ≠ Media Group joined

Join Request
  = invitee requests to join
  ≠ admitted

Membership Fact
  = Owner’s sole admission authority (Q4)

Media Key Package
  = admitted (or candidate) member obtains media-execution material (Q7)
```

Owner ACK, Invite ACK, UI consent, and peer “seen request” are **not** Join Success.

### Orthogonal projections (not one linear chain)

```text
Join Projection:
  NOT_INVITED / INVITED / JOIN_REQUESTED / MEMBERSHIP_ACCEPTED

Key Projection:
  NO_KEY / STAGED / MEDIA_KEY_PENDING / ACTIVE
```

Allowed:

```text
INVITED or JOIN_REQUESTED
+ Package bound to the final Candidate Membership Fact digest
→ Key = STAGED
→ MUST NOT join Media Group, send RTP, or originate Source Declaration
→ MUST NOT be treated as a legal Sender
```

`MEDIA_READY` only when:

```text
Membership Fact accepted AND View contains this Module
AND matching key is ACTIVE
```

Q7 stands: Package MAY be pre-distributed before Fact origin; without accepted matching Fact, key is at most STAGED. `MEDIA_KEY_PENDING` remains only for Modules **already** in the accepted View and missing usable key.

### V1: Invite-based Join only

```text
Join Request MUST bind a valid Invite.
Unsolicited Join Request
  → V1 NOT SUPPORTED
  → MUST NOT drive Membership Mutation
```

Reasons: Join spam; arbitrary Mesh members must not keep forcing Owner decisions; every real membership change rotates the group key (Q4); one Admission FSM.

A future “apply to join” path needs an Amendment. It MUST NOT change Membership Fact as sole admission authority.

### Invite (Owner only)

Only the current Conference Owner MAY originate Invite. Forward ≠ originate.

Invite MUST bind atomically:

```text
conferenceId
conferenceEpoch
ownerModuleId
creationFactDigest
inviteIdentity              // not reusable
targetModuleId
membershipBaselineDigest    // current accepted Membership
                            // or pending Candidate Fact digest
```

```text
Same inviteIdentity + identical immutable semantics → idempotent
Same inviteIdentity + different immutable semantics
  → INVITE_IDENTITY_CONFLICT
  → MUST NOT treat either as the Invite
```

Non-`targetModuleId` MUST NOT use the Invite. Mismatch of digest / epoch / Owner → `NOT_APPLICABLE` / `INVALID_TARGET`.

**Origin binding:** the target MUST verify the Invite was originated by the current Conference Owner and bound to this `targetModuleId`. Name fields alone do not prove origin.

V1 SHOULD reuse the same Module-level identity capability required by Q7. Mesh Shared Secret that only gives group-level origin MUST be described as: **V1 cannot cryptographically attribute requester/Owner beyond group membership**. That is insufficient if any Mesh member can forge Joins and force key-rotation DoS. Implementation authorization requires a scheme that meets Invite Owner-origin and Join Request requester-origin below.

### Invite lifetime (no wall-clock expiry in V1)

V1 MUST NOT depend on per-Module wall clocks. Invite stays valid until one of:

```text
Owner originates Invite Revocation Fact
corresponding Membership Fact admits the target (Invite consumed)
Membership baseline advances so the Invite is SUPERSEDED
Conference TERMINATED
```

Invite Revocation Fact at least:

```text
conferenceId
conferenceEpoch
ownerModuleId
inviteIdentity
inviteDigest
```

Origin = revoked in contract. Local accept → stop using it. Holders MAY re-provide unmodified. Forward ≠ originate revoke.

Future time-expiry needs a separate clock contract.

**No auto-rebase:**

```text
membershipBaselineDigest is no longer current
  → Invite SUPERSEDED
  → Owner MUST originate a new Invite
  → MUST NOT rebase the old Invite onto the new Membership
```

Pre-invite bound to a Candidate Fact digest: if that Candidate is not originated with identical authoritative semantics, both the Invite and staged Packages MUST NOT continue.

This prevents concurrent Invites from the same parent snapshot each minting a Membership branch.

### Join Request

```text
Join Request {
  joinRequestIdentity
  inviteIdentity
  requesterModuleId
  conferenceId
  conferenceEpoch
  creationFactDigest
  inviteDigest
}
```

`inviteDigest` prevents stitching an `inviteIdentity` onto a different Invite Fact.

```text
Same joinRequestIdentity + identical semantics → idempotent
Same joinRequestIdentity + different immutable semantics
  → JOIN_REQUEST_IDENTITY_CONFLICT
  → Owner MUST NOT process either candidate
```

**Origin binding:** Owner MUST verify the Request was originated by the Module named `requesterModuleId` and that the requester is responding to the bound Invite.

Owner MAY refuse or delay. A Request MUST NOT become Membership and MUST NOT increment `membershipVersion`.

### Contract admit vs local success

```text
Owner originates a legal Membership Fact that includes the requester
  → requester is a member in contract

Requester accepts that Fact
  → Local Membership = MEMBERSHIP_ACCEPTED

Requester activates the matching key
  → MEDIA_READY
```

A requester that has not accepted the Membership Fact MUST NOT join the Media Group because Owner said “agreed”.

Late Invite / Join ACK after local `MEMBERSHIP_ACCEPTED` → ignore idempotently. MUST NOT cause a second membership change.

Join success MUST NOT wait for ACK from all existing members. Sole admission Fact remains the Owner-originated complete Membership Snapshot.

### Join Request terminal states

Membership Fact is the only **accept** outcome.

Owner MAY originate:

```text
Join Rejection Fact {
  conferenceId
  conferenceEpoch
  ownerModuleId
  joinRequestIdentity
  joinRequestDigest
  rejectionReason
}
```

Rejection terminates **that Request only**:

```text
≠ Membership Mutation
≠ Conference Termination
≠ permanent ban on a future new Invite
```

```text
Join Request:
  PENDING
  ACCEPTED_BY_MEMBERSHIP_FACT
  REJECTED
  CANCELLED_BY_INVITE_REVOKE
  SUPERSEDED_BY_BASELINE
  TERMINATED_WITH_CONFERENCE
```

Owner gone: Request MAY stay PENDING. Followers MUST NOT admit, mint Invite, or write Membership. This Module MUST NOT join the Media Group for that reason.

### Re-provide

Verified Invite / Request / Revoke / Rejection MAY be forwarded unmodified. MUST NOT alter target, identities, or digests. Forward ≠ originate ≠ admit.

Rejoin after Local Leave requires Owner REMOVE of the old `membershipIncarnationId`, then a **new** Invite / ADD / incarnation / key (**Q9**). Same incarnation MUST NOT resume.

---

## Local Leave and Owner REMOVE (Grill Q9 — ACCEPTED)

### Three facts (must not substitute)

```text
Local Leave
  = this Module immediately stops participating
  ≠ global Membership deleted
  ≠ Conference TERMINATED

Leave Request
  = ask Owner to drop this Module’s current incarnation
  ≠ Membership Mutation

Owner REMOVE / REVOKE Fact
  = Q4 complete Membership Snapshot
  = sole global removal authority
```

Silence, Source timeout, RTP gone, unreachability, app crash, idle decoder are **not** global REMOVE and **not** Local Leave unless this Module explicitly Leaves.

### Local Leave is immediate and sticky on this incarnation

Does not wait for Owner ACK or a new Membership Fact:

```text
stop RTP send
fence all local Sources
leave the Media Group
retire local ACTIVE / STAGED keys (Q7: unusable by media execution)
Local Participation(current membershipIncarnationId) = LEFT
originate Leave Request so it is observable
```

This LEFT state MUST hold across:

```text
app restart
late Membership Fact
Key Package
Invite
Source Declaration
Owner not yet processing the Request
```

It MUST NOT auto-restore. Same incarnation’s Local Leave is **not revocable**. There is no `CANCELLED_BY_REJOIN`.

Rejoin MUST be:

```text
Owner REMOVE of the old incarnation
→ new Invite (Q8)
→ new ADD Membership Fact
→ new membershipIncarnationId
→ new key
```

Late Key Package / Source / Invite after Local Leave MUST NOT auto-rejoin, re-ACTIVE, or resume RTP on the left incarnation.

Local Leave MUST NOT be projected as Conference `TERMINATED`.

### Other Modules (lag)

```text
Until they accept an Owner REMOVE Fact dropping that incarnation
they MAY still treat the Module as a member under the old View
they will only observe no active Source (Q5)

MUST NOT locally delete the member or mint REMOVE because RTP is gone
```

Contract removal still occurs when Owner originates a Membership Fact whose View lacks that incarnation (Q4).

### Leave Request binds incarnation, not a dead Snapshot

```text
Leave Request {
  leaveRequestIdentity
  requesterModuleId
  membershipIncarnationId
  conferenceId
  conferenceEpoch
  creationFactDigest
  observedMembershipFactDigest
}
```

```text
membershipIncarnationId     = the removal target
observedMembershipFactDigest = requester’s context at request time
  ≠ a requirement that Owner mutate that exact old Snapshot
```

Owner MAY apply the Request to any later legal Membership Snapshot iff the current View still contains `(requesterModuleId, membershipIncarnationId)`. Unrelated member churn MUST NOT swallow the leave intent.

```text
View no longer contains that incarnation
  → SATISFIED_BY_REMOVE_FACT or ALREADY_ABSENT / NOT_APPLICABLE

View contains same moduleId with a different membershipIncarnationId
  → SUPERSEDED_BY_NEW_INCARNATION
  → the old Request MUST NOT delete the new incarnation
```

`SUPERSEDED_BY_NEW_INCARNATION` does **not** allow skipping a new Invite to rejoin.

```text
Same leaveRequestIdentity + identical semantics → idempotent
Same identity + different immutable semantics
  → LEAVE_REQUEST_IDENTITY_CONFLICT
  → Owner MUST NOT process either candidate
```

Only the requester MAY request removal of **its own** incarnation. Owner-initiated drop uses Q4 REMOVE/REVOKE and MUST NOT impersonate another Module’s Leave Request.

Owner MUST verify requester origin (same Module-level origin property as Q8).

### Owner duty (no permanent ignore)

Owner MUST NOT refuse a verified self-leave Request. `PENDING` is not a final rejection. No timeout or retry count is frozen here.

After accepting a legal Leave Request, Owner MUST eventually produce one of:

```text
a Membership Fact that removes the target incarnation
an already-existing Fact proving that incarnation is gone
a Conference Termination Fact
```

While Owner is unreachable the duty cannot complete; Local Leave is still immediately effective. Followers MUST NOT write REMOVE.

### Request must survive until Owner can see it

After Local Leave the requester may stop media and UI. The Request MUST NOT be a one-shot ephemeral datagram.

```text
Requester originates the Leave Request and makes it observable.
Requester / accepted holders MAY re-provide it unmodified.

Relay
  ≠ originate Request
  ≠ mutate Membership
  ≠ acquire requester authority
```

Resend only improves visibility. It does not mean Membership has changed.

Local Leave MUST NOT wait for delivery. The observability duty lasts until the incarnation is removed, the Conference is terminated, or the Request is proven to have no applicable target.

### Leave Request terminals

```text
PENDING
SATISFIED_BY_REMOVE_FACT
ALREADY_ABSENT
SUPERSEDED_BY_NEW_INCARNATION
TERMINATED_WITH_CONFERENCE
INVALID
```

A REMOVE Fact already originated without that incarnation: late Leave Request → ignore idempotently. MUST NOT cause a second Membership Mutation.

### Owner self-leave (unchanged in kind)

```text
Owner MUST NOT originate an ordinary Leave Request / REMOVE
against their own incarnation.

Owner leave MUST use Q3 Termination.
(Q4: a not-TERMINATED Snapshot MUST include Owner.)
```

If Owner only stops local media and originates no Termination Fact: protocol violation. The Conference remains unterminated in contract. Other Modules MUST NOT infer a new Owner.

### After others accept REMOVE

Immediately fence all Sources of the departed incarnation (Q4/Q5), rotate/activate the new key (Q7). MUST NOT wait for the leaver’s ACK.

If the target has not Local Left yet: on Fact accept they follow Q4 “not in View” (stop send, leave group, do not take the new key).

---

## Post-termination identity isolation and reuse (Grill Q10 — ACCEPTED)

### Identities vs digests

Never **reallocate** (V1 lifetime, including process/app/device restart):

```text
conferenceId
Creation Fact identity
membershipIncarnationId
Invite / Join Request / Leave Request / Key Package identity
sourceInstanceId
```

Digest is a **content address**, not a scarce identity:

```text
Same immutable Fact
  → retransmission MUST yield the same digest
  → idempotent, not illegal reuse

Different immutable Facts
  → MUST NOT be declared as the same digest
  → if they are, that is digest / integrity failure
```

MUST NOT write “Creation Fact digest is never reused.” Write: digest MUST remain a stable content binding. One digest MUST NOT name two semantics; repeat propagation of the same Fact MAY reuse that digest.

Scope-limited non-reuse:

```text
SSRC
  → not reused inside (conferenceId, mediaKeyEpoch)
  → BURNED SSRC stays retired in that mediaKeyEpoch (Q6)

mediaKeyEpoch / that epoch’s key material
  → monotonic inside conferenceEpoch
  → a retired epoch MUST NOT become ACTIVE again

MediaGroupEndpoint
  → after legal Termination MAY enter Endpoint Quarantine
  → MUST NOT be handed to a new Conference at the Termination instant
```

### Tombstone (no highest-version authority)

After this Module accepts a matching Termination Fact, it MUST create a locally irreversible tombstone:

```text
Conference Tombstone {
  conferenceId
  conferenceEpoch
  creationFactDigest

  terminationAuthorityDigest
  observedTerminationFactDigests[]

  lastAcceptedMembershipFactDigest
  lastAcceptedMembershipVersion
  lastActiveMediaKeyEpoch

  retiredMediaGroupEndpoint
  observedRetiredOrBurnedSsrcSet
}
```

```text
terminationAuthorityDigest
  = stable binding of Q3 authoritative terminate fields
  = MUST NOT include conflicting terminationReason metadata

observedTerminationFactDigests
  MAY retain multiple audit Facts with different reasons
  that all declare the same terminate result
```

Version / epoch fields on the tombstone are **diagnostic only**:

```text
MUST NOT accept a late “higher” version
MUST NOT reopen a terminated epoch
```

After local accept of legal Termination, **any** operational Fact bound to the same `(conferenceId, conferenceEpoch, creationFactDigest)` is refused. No version comparison is required.

Tombstone MUST:

```text
refuse late Creation / Membership / Key / Source / Invite / Request
  and old-epoch media for that Creation
survive process restart so late media/Facts are not a new Conference
MAY re-provide Termination Fact unmodified
MUST NOT store recoverable plaintext group key
MUST NOT mean media-execution resources are still held
```

Compression is allowed if it still retains: conference identity, creation binding, authoritative termination binding, retired endpoint, and enough non-resurrection evidence to refuse old operational Facts.

Tombstone MAY be discarded only under a separate data-lifecycle contract that ends the whole Identity Domain **and** old RF/control Facts cannot re-enter that Domain. Process restart, ordinary app upgrade, and device reboot are **not** discard reasons.

Tombstone lifetime MUST NOT be shorter than the evidence needed to argue Endpoint reuse. Dropping it early ⇒ MUST NOT claim the Endpoint is reusable.

### Endpoint Quarantine is data-plane only

```text
Endpoint Quarantine covers underlay / data-plane residue only:
  in-flight UDP/RTP
  RF / driver queues
  multicast forwarding cache
  group-membership / route-convergence residue
  socket leave/join residue

Old control Facts
  → refused forever by conference identity, digest, and tombstone
  → MUST NOT be assumed to expire by waiting
```

```text
Endpoint Quarantine duration
  ≥ declared MaxUnderlayDatagramResidence
  + multicast membership / forwarding convergence envelope
```

ADR-0059 does not declare or qualify `MaxUnderlayDatagramResidence`. Therefore:

```text
V1 Endpoint reuse = NOT QUALIFIED
```

V1 MUST allocate a fresh Endpoint for every Conference and MUST NOT reuse a terminated Conference Endpoint. A future reuse decision requires a separately declared and evidenced residence envelope.

A same `MediaGroupEndpoint` MAY be used by a new Conference only if all hold:

```text
old Conference has a verified Termination Fact (contract TERMINATED)
this Module has left the group and released media-execution resources
declared Endpoint Quarantine has elapsed (and is QUALIFIED)
new Conference uses new conferenceId, epoch, key, and Source identities
Q2 Candidate Claim / runtime collision detection is run again before origin
```

### Quarantine does not prove old senders stopped

A partitioned member that never obtained Termination MAY keep sending old Conference media after the quarantine envelope.

```text
Quarantine complete
  → only: in-flight / underlay-cache envelope has passed
  ≠ all old Modules have stopped
  ≠ Endpoint is currently unoccupied
```

The new Owner still MUST re-run Candidate Claim, collision detection, and new identities. If an old sender later resumes:

```text
→ MEDIA_GROUP_COLLISION
→ the new Conference MUST NOT swallow that fact
```

### No global address-release authority

There is no address server.

```text
Termination Fact
  ≠ a global address-release record
```

A new Conference Owner may treat a Candidate as committable only from: known Termination Facts, Quarantine evidence, Candidate Claim, and **locally visible** collision state.

Other Modules, on accepting the new Creation Fact, MAY still observe conflict against their own knowledge and enter Q2 `COLLISION_BLOCKED` / `COLLISION_DEGRADED`. A new Owner’s claim that “the address is released” MUST NOT override local verified facts.

Orphan Conference (no legal Termination Fact): Endpoint MUST NOT be treated as reusable. Local Leave does not release the global Endpoint. Media silence MUST NOT start Endpoint Quarantine.

Reuse, if QUALIFIED, still MUST isolate old packets by `conferenceId` / `conferenceEpoch` / `mediaKeyEpoch` / tombstone. Old-epoch media or old Creation-family Facts: drop and MAY count; MUST NOT extend the old Conference lifecycle; MUST NOT lift the tombstone.

MUST NOT use “all members ACKed Termination” as a terminate condition or as a reuse precondition (Q3: contract TERMINATED ≠ everyone knows).

---

## RTP Authenticated Discriminator (Grill Q11 — ACCEPTED)

### What a packet must determine (not all as full HE values)

After a packet is admitted, this Module MUST uniquely determine:

```text
conferenceId / conferenceEpoch
membershipVersion
mediaKeyEpoch
senderModuleId
sourceInstanceId
SSRC (RTP native)
voiceActive
audioLevel
```

Short identifiers or local maps from Creation / Membership / Source Declaration are allowed iff:

```text
unambiguous in this Conference + this mediaKeyEpoch
constrained by an accepted Source Declaration
not replayable across Conference or mediaKeyEpoch
missing map → drop this packet (and MAY bounded SOURCE_FACT_PENDING only after group auth — below)
MUST NOT guess incarnation / Module / epoch
```

RFC numbers and bit layout remain deferred. HE **version** MUST be explicit; unknown version **fail closed**.

### Pre-auth Hint vs authenticated semantics

Before SRTP verify, the receiver typically must know which Conference, `mediaKeyEpoch`, and HE version to try. A minimal **untrusted** plaintext hint is allowed:

```text
Packet Context Hint {
  heVersion
  conferenceDiscriminator
  mediaKeyEpochDiscriminator
  sourceDiscriminator
}
```

```text
Hint is fully untrusted before SRTP verification.
It MAY only select a candidate from key contexts this Module
has already accepted and installed.

MUST NOT, based on an unauthenticated Hint:
  create a Conference
  install a key
  create a Source
  mutate Membership
  start arbitrary network Fact fetches
  allocate unbounded state
```

After SRTP verification succeeds, Hint semantics MUST lie in the integrity scope and MUST match the selected Conference / key / Source context exactly — only then are they trusted discriminators.

If a layout cannot be “visible for selection, authenticated after verify,” that HE layout is **not acceptable**.

Semantics that MUST be in SRTP integrity after verify:

```text
SSRC
sourceInstance discriminator
senderModule discriminator
mediaKeyEpoch / key-context discriminator
conference / session discriminator
voiceActive
audioLevel
```

RTP / HE MUST NOT originate or modify Membership, Source Declaration / Resolution, `mediaKeyEpoch`, Creation, Termination, or Invite.

### Unknown discriminator: drop this packet

```text
Unknown or missing discriminator
  → this RTP packet MUST be dropped
  → MUST NOT cache it to decode later when a Fact arrives
```

Fact fetch:

```text
Pre-auth: key context cannot even be selected
  → drop / rate-limited count only
  → MUST NOT fetch Facts from the unauthenticated Hint alone

Group authentication already passed
AND Source Declaration map is missing
  → MAY raise bounded SOURCE_FACT_PENDING
  → MAY request the control Fact
  → this media packet is still dropped
```

This blocks random short-ID request storms.

Mismatch after auth vs admitted Declaration:

```text
SOURCE_PACKET_IDENTITY_MISMATCH
MUST NOT try another Source
MUST NOT rebind because SSRC happens to match another incarnation
```

Old source / old key / old epoch packets MUST NOT re-admit because the SSRC is currently unused.

### Short discriminator lifecycle (like SSRC)

If full `sourceInstanceId` is not on the wire, Source Declaration MUST bind:

```text
packetSourceDiscriminator
```

```text
unique inside (conferenceId, mediaKeyEpoch)
binds exactly one sourceInstanceId / senderModuleId / SSRC
after the Source is fenced, MUST NOT reallocate that discriminator
in the current mediaKeyEpoch
```

Two Declarations claiming the same short discriminator:

```text
PACKET_DISCRIMINATOR_COLLISION
→ quarantine both Sources
→ MUST NOT pick by SSRC, arrival, or Module ID
```

Same shape as Q6 SSRC collision: the collided discriminator is **BURNED** for this `mediaKeyEpoch`; new Sources MUST use a new discriminator. Bit width MUST NOT be treated as collision-proof.

### audioLevel semantics (Traffic Profile)

Authentication is not enough. Top-K compares across devices. The committed Traffic Profile MUST bind a single:

```text
audioLevel range
numeric direction (louder = higher or lower)
silence / unknown sentinels
measurement point
update rate
consistency rules between voiceActive and audioLevel
```

Measurement point for V1:

```text
after AEC / NS / AGC
before Opus encode
```

Illegal or out-of-range values:

```text
HE_SEMANTIC_INVALID
this packet MUST NOT enter Top-K / decode / mix
MUST NOT clamp to a legal max and continue
```

Numeric range and RFC encoding deferred; the semantic profile MUST be unique.

Top-K has **no fairness** against a malicious admitted member (they can forge levels). Q11 only gives protocol state plus outsider integrity.

### Admission order (fail closed)

```text
1. Parse fixed-length / bounded HE safely
2. Read untrusted Packet Context Hint
3. Select candidate key only from locally installed contexts
4. SRTP authentication / replay check
5. Authenticated discriminator matches that context
6. Current Membership / Source Declaration / Descriptor
7. voiceActive / audioLevel semantics
8. Then Top-K, jitter, decode / mix
```

Any step fails: this packet fail-closed. MUST NOT try a second Source “for luck.”

Trying a **bounded** set of already-installed key contexts for rotation overlap is an implementation-contract later. It MUST NOT become a walk of all historical keys.

### Implementation traffic envelope

The RTP / Wire Profile MUST declare the final workload envelope:

```text
packets per second per active Source
IP/UDP/RTP/SRTP total size distribution
burst behavior
DSCP / underlay priority mapping
```

Header-extension bytes and control/media packet overhead MUST be included. Product field capacity evidence is valid only for the measured implementation profile; changing packetization, rate, burst shape or size invalidates that evidence for the affected capacity gates.

---

## ConferenceHealth Projection (Grill Q12 — ACCEPTED)

Health is **read-only**. It MUST NOT own timeouts, retries, key switch, REMOVE, Termination, Source admission, or any repair. UI `READY` / `ACTIVE` / `ONLINE` MUST NOT reverse-originate Facts, activate keys, admit Sources, end waits, or count as recovery/underlay field evidence.

### Orthogonal axes

```text
Lifecycle Knowledge
  UNKNOWN / EXISTS / TERMINATED
  = this Module’s knowledge of Creation / Termination
  ≠ ConferenceExists(contract) (Q3)

Creation Alignment
  NOT_ACCEPTED / ACCEPTED / CONFLICT
  CREATION_CONFLICT lives here, not on Membership Alignment

Local Participation
  NOT_MEMBER / INVITED / JOIN_REQUESTED /
  MEMBER / LEFT / REMOVED

Membership Alignment
  CURRENT / CHAIN_PENDING / CONFLICT

Current Key Readiness
  NO_KEY / KEY_PENDING / ACTIVE / CRYPTO_CONFLICT
  keyed only to the current accepted Membership Fact

Future Key Staging
  NONE / STAGED / STAGING_CONFLICT
  next Candidate Packages (Q7); MUST NOT drive WAITING_KEY
  while current key is ACTIVE

Media Group Binding
  NOT_JOINED / JOINED

Transport Health
  HEALTHY / IMPAIRED / UNAVAILABLE

Source Readiness
  NO_REMOTE_SOURCE / ACTIVE / PARTIAL / SOURCE_CONFLICT

Collision State
  CLEAR / COLLISION_BLOCKED / COLLISION_DEGRADED
```

`INVALID_KEY_PACKAGE` is not `CRYPTO_CONFLICT`. Membership branch stays on Membership Alignment.

Current `ACTIVE` + future `STAGED` ⇒ current media still READY/ACTIVE. UI MUST NOT show `WAITING_KEY`. After the next Membership Fact is accepted, staged material becomes current `ACTIVE` atomically if it matches; else current `KEY_PENDING`.

Route repair: Binding stays `JOINED`; Transport may HEALTHY → IMPAIRED/UNAVAILABLE → HEALTHY. MUST NOT change Lifecycle, Membership, or key.

### Configured vs usable

```text
LocalMediaConfigured =
  LocalLifecycleKnowledge = EXISTS
  AND Creation Alignment = ACCEPTED
  AND Local Participation = MEMBER
  AND Membership Alignment = CURRENT
  AND Current Key Readiness = ACTIVE
  AND Media Group Binding = JOINED
  AND Collision State ≠ COLLISION_BLOCKED
  AND no global blocking conflict
```

`EXISTS` is required. “Not TERMINATED” is not enough.

```text
LocalMediaUsable =
  LocalMediaConfigured
  AND Transport Health ≠ UNAVAILABLE
```

```text
Transport IMPAIRED
  → Configured=true; Usable MAY still be true; user DEGRADED

Transport UNAVAILABLE
  → Configured=true; Usable=false; user DEGRADED
  → MUST NOT roll back Membership / key
```

Solo or all silent: `READY` + `NO_REMOTE_SOURCE` is normal, not a fault. Temporary lack of `voiceActive` in Top-K is silence, not media failure, not Transport impairment.

### Failure domains

Global blocking `CONFLICT` (user `CONFLICT`) **only**:

```text
Creation Alignment = CONFLICT
Membership Alignment = CONFLICT
Current Key Readiness = CRYPTO_CONFLICT
Collision State = COLLISION_BLOCKED
```

Source conflicts (`SOURCE_BRANCH_CONFLICT`, `SOURCE_RESOLUTION_CONFLICT`, `SOURCE_MULTI_ACTIVE`, `SSRC_COLLISION`, `PACKET_DISCRIMINATOR_COLLISION`) default to:

```text
Source Readiness = PARTIAL or SOURCE_CONFLICT
user state = DEGRADED
```

Other legal Sources MAY continue mix (Q5/Q6 quarantine scope). If **all** remote Sources conflict: still media `DEGRADED`, not Membership/Conference truth conflict.

`MEDIA_GROUP_COLLISION`: unaffiliated → `COLLISION_BLOCKED` → user `CONFLICT`; already admitted → `COLLISION_DEGRADED` → user `DEGRADED`. MUST NOT claim collision resolved (Q2).

One Source silent/timeout: that Source only. MUST NOT conference-wide `RECOVERING`.

### User projection (single result)

```text
TERMINATED
REMOVED
LOCAL_LEFT
CONFLICT
WAITING_ADMISSION
WAITING_MEMBERSHIP
WAITING_KEY
WAITING_MEDIA
DEGRADED
ACTIVE
READY
```

```text
WAITING_ADMISSION   INVITED / JOIN_REQUESTED; Membership does not yet list this Module
WAITING_MEMBERSHIP  Membership CHAIN_PENDING
WAITING_KEY         MEMBER of current View but current key KEY_PENDING
WAITING_MEDIA       Membership+key ready; Media Group Binding not JOINED
REMOVED             Owner Snapshot removed/revoked this incarnation
LOCAL_LEFT          sticky Local Leave on this incarnation (Q9)
```

MUST NOT merge `REMOVED` and `LOCAL_LEFT`.

Priority (higher wins):

```text
TERMINATED
> REMOVED
> LOCAL_LEFT
> CONFLICT
> WAITING_ADMISSION
> WAITING_MEMBERSHIP
> WAITING_KEY
> WAITING_MEDIA
> DEGRADED
> ACTIVE
> READY
```

```text
READY
  LocalMediaConfigured + Transport HEALTHY
  + no legal remote voiceActive
  + no degradation

ACTIVE
  LocalMediaConfigured + Transport HEALTHY
  + ≥1 legal remote voiceActive Source
  + no degradation

DEGRADED
  LocalMediaConfigured holds
  + Transport IMPAIRED/UNAVAILABLE
    or Source PARTIAL/SOURCE_CONFLICT
    or COLLISION_DEGRADED
```

Local accept of Termination → user `TERMINATED`. Media activity MUST NOT restore READY/ACTIVE.

`KEY_PENDING` shows wait-for-key only. MUST NOT roll back Membership or show “not joined.”

---

## Grill closure (Q13 — ACCEPTED)

Behavior contract is **sufficient**. Do **not** add further Conference behavior axes. Remaining work is Implementation Profile, not new semantics.

```text
ADR-0058 Contract Grill:
  COMPLETE · Q1–Q13 PASS

ADR Status:
  ACCEPTED — CONTRACT ONLY
  UNDERLAY DEPENDENCY — ADR-0059 PRODUCT RISK ASSUMPTION
  Implementation AUTHORIZED (Profiles 01/02/03; E2b PENDING)
  ADR-0056 RETAINED
```

Contract ACCEPTED ≠ underlay field evidence ≠ implementation complete.
Authorization doc: `../analysis/0058-implementation-authorization.md`.

### Implementation Profiles (Profiles 01/02/03 frozen — CONTRACT DESIGN COMPLETE)

After this ADR and ADR-0059 are accepted, freeze three profiles **before** code authorization:

```text
1. Control / Crypto Profile — **FROZEN · CONTRACT COMPLETE**
   Fact encoding and stable digest
   Module / Owner origin binding
   signatures, trust roots, key wrap
   SRTP cipher / KDF / nonce / replay
   tombstone and Fact retention

2. RTP / Wire Profile — **FROZEN · CONTRACT COMPLETE / CLOSED · Q1–Q10 PASS**
   payload type, clock rate, SafeUdpPayloadBytes=120
   HE version, field IDs, bit widths
   short discriminator allocation and collision
   audioLevel / voiceActive wire format
   no RTCP / no RTP retransmission / no media-plane CC feedback
   UDP multicast MediaGroupEndpoint; one IP family per incarnation
   NominalPps=50; WirePpsCeiling=60 (anomaly only); current-slot send only
   WIRE_ENVELOPE_OVERSIZE; no umbrella ingress reject class
   Q9 byte-exact GoldenOrNegativeVector schema + minimum corpus coverage
   hex corpora = post-freeze implementation-authorization prerequisite
   replayWindowPackets = 64 (Profile 02 Q6 security/admission only)
   packet-size / pps / burst aligned to the declared product workload

3. Media Runtime Profile — **FROZEN · CONTRACT COMPLETE / CLOSED · Q1–Q10 PASS**
   AdmittedMediaSource is the sole authority-bearing runtime input
   no packet-arrival Source discovery / no unbound media execution
   HARD FENCE ≠ DESTROY; silence ≠ authority loss
   key-epoch retirement fences old execution incarnation
   MaxPlayoutDelayMs=120; MaxReorderPackets=4; AdaptiveJitterWindow=OFF
   LATE_FOR_PLAYOUT = runtime disposition, not Health/Source failure
   Top-K before decode; TopK=4; HoldMs=200; HysteresisLevel=3
   Hold among V=1 only; V=0 does not keep a slot by Hold alone
   MaxConsecutivePlcFrames=5; DecoderWarmupMs=40 max allowance
   MixMaxSources=4 absolute; OutputPeakAbs<=0.89 FS (algo impl-defined)
   MaxLiveDecoders=5; MaxJitterSources=10
   MulticastLock = Conference media transport scope
   rebind ≠ admission/resurrection/generation change
   Q8 = runtime evidence producer; ADR Q12 = Health semantic owner
   HealthAggregationWindowMs=1000 evidence window only
   Q9 R01–R16 contract-proving matrix; no new classes/parameters
   R01–R16 fixtures = post-freeze implementation-authorization prerequisite
   post-replay consumer only; cites Profile 02 replayWindowPackets=64
```

Cross-Profile Consistency Review: **PASS**
(C1–C5 CONSISTENT after LIMITED AMEND relocating replay-window ownership)

Stage: **CONFORMANCE EVIDENCE**
  doc: talkback/docs/analysis/0058-conformance-evidence-gate.md
  E1  Profile 02 Q9 hex corpus — **PASS**
      vectors: talkback/docs/analysis/0058-profile-02-q9-golden-vectors.json
      manifest: talkback/docs/analysis/0058-profile-02-q9-corpus-manifest.md
  E2a Profile 03 R01–R16 evidence specs — **PASS**
      spec: talkback/docs/analysis/0058-profile-03-r01-r16-acceptance-spec.md
      IG-01/IG-02 non-blocking; CONTRACT_CONTRADICTION NONE
  E2b Implementation R01–R16 — **PENDING** (implementation AUTHORIZED)

Conformance Evidence Gate (evidence-spec level): **PASS**
Implementation Readiness Review: **PASS WITH IMPLEMENTATION CONDITIONS**
  doc: talkback/docs/analysis/0058-implementation-readiness-review.md
  conditions: C-IG-01 .. C-EVID-01 frozen
Implementation Authorization: **AUTHORIZED**
  doc: talkback/docs/analysis/0058-implementation-authorization.md
Implementation: **AUTHORIZED**
E2b: **PENDING**
Implementation Complete: **NO**
Product code: may change only inside authorization scope

Next formal gate:

```text
E2b Implementation Conformance
```

No Profile 04. No further design Grill is authorized by Profile closure alone.

```text
Profiles MUST map to and satisfy Q1–Q12 / CM-*.
Profiles MUST NOT change Membership, Owner, Source, Key, or Health semantics.
If a mechanism cannot meet an existing invariant
  → return to ADR Grill
  → MUST NOT add a silent bypass in code.
```

### MaxUnderlayDatagramResidence

```text
Not declared and qualified
  → Endpoint reuse NOT QUALIFIED
  → V1 MUST NOT reuse a multicast Endpoint

This does not block a Conference implementation that never reuses Endpoints.
```

### Order (normative for authorization, not a lab schedule)

```text
ADR-0059 product dependency accepted
→ ADR-0058 contract accepted
→ freeze the three Implementation Profiles
→ explicit implementation authorization
→ implement multicast as the selected Conference mode while retaining
  ADR-0056 source for rollback; never run both media paths in one Conference
→ after 3/4/6/8/10 capacity gates PASS, delete ADR-0056 Anchor path
```

---

## Media transport (semantic)

Each Conference has:

```text
multicastAddress
mediaPort
controlPort (MAY be distinct)
codecProfile
conferenceEpoch
mediaKeyEpoch
```

Send:

```text
Mic → AEC/NS/AGC → VAD → Opus → RTP → SRTP → IP multicast
```

Receive:

```text
IP multicast
→ bounded HE parse + untrusted Hint lookup of installed context (Q11)
→ SRTP / group-member authentication (CM-AUTH-1)
→ authenticated discriminator vs context
→ conferenceEpoch / mediaKeyEpoch / Membership / Source (CM-AUTH-2)
→ audioLevel semantics
→ demux by admitted incarnation (not SSRC guess)
→ replay window
→ Top-K select (authenticated level signals)
→ Opus decode ONLY for active Top-K
→ local mixer → limiter → speaker
```

Sender identity at the media layer: **Q5** (`Sender Source Identity` + Declaration lineage). SSRC is not Module identity.

Conference lifecycle **MUST NOT** change because a Source is superseded, fenced, or quarantined.

---

## Top-K decode budget (normative)

Compressed Opus **cannot** yield true audio level without decode. Receivers **MUST NOT** infer Top-K solely from local decode of all N−1 streams.

Each sender **MUST** emit authenticated discriminators per **Q11** (HE version fail-closed; `voiceActive` / `audioLevel` in SRTP integrity scope with a single Traffic-Profile level semantics). Encoding / RFC numbers remain deferred.

Receiver:

```text
Receive N−1 RTP (lightweight)
→ authenticate
→ select Top-K from voiceActive / audioLevel
→ maintain live Decoders for Top-K only
→ others: sequence, loss, activity stats only
```

K is a **mix policy**, not Floor:

```text
K = 3..4   (product freeze for V1)
N−1        lightweight receive (not a magic 9)
```

Decoder churn **MUST** be bounded:

```text
Top-K selection
+ hysteresis
+ minimum hold time
+ fast decoder warm-up
```

All admitted members MAY speak at any time (full duplex). VAD:

```text
Speech   → send RTP immediately
Silence  → stop continuous audio; MAY keep low-rate source/state
```

**MUST NOT** require PTT Floor for Conference.

---

## Authentication / threat model (normative)

Shared SRTP group key proves **possession of the current group media key**. It does **not** cryptographically prove the packet was produced by a specific Module. Any current member can forge `senderModuleId` if that field is only inside a group-authenticated blob.

### V1 threat model (ACCEPTED for this DRAFT)

```text
V1 defends against non-members (no current media key).
V1 does NOT provide intra-group Source Authentication.
```

Call this **group-member authentication**. **MUST NOT** call it “Module cryptographic binding” or “source authentication.”

Intra-group impersonation resistance (per-Module sender keys / packet or batch signatures) is **out of V1**. A later ADR MAY add it; this ADR MUST NOT pretend the group key already provides it.

### CM-AUTH

**CM-AUTH-1** — Media packets MUST pass group-member authentication for the current `mediaKeyEpoch`.

**CM-AUTH-2** — RTP MAY enter decode/mix only if it maps to a **Q5-admitted** current Source Declaration (Membership + lineage + Descriptor digest + discriminator + SSRC). Unadmitted, superseded, fenced, or quarantined sources MUST NOT enter decode or mix. This is **admission mapping**, not intra-group source authentication.

**CM-AUTH-3** — Whether legitimate members may impersonate another Module is decided by the threat model. **A shared group key MUST NOT be described as providing intra-group Source Authentication.** V1 = does not prevent it.

---

## Join (incremental)

Informative order only; Q8 projections are orthogonal. V1 is Invite-based.

```text
1. Verify Owner-originated Invite (target = this Module; Q8)
2. MAY receive Key Package STAGED if bound to final Candidate digest (Q7)
   — MUST NOT join multicast / send / declare Source yet
3. Originate Join Request bound to inviteDigest (Q8)
4. Wait for Owner Membership Fact listing this Module (Q4)
5. Accept complete Membership Fact → MEMBERSHIP_ACCEPTED
6. Activate matching key → MEDIA_READY (Q7)
7. Join multicast; start receive
8. Originate Source Declaration then send (Q5)
```

Join **MUST** add at most one potential Sender Source (V1: one active Source per Module). Existing members **MUST NOT** rebuild sockets, other sources, or pause the conference. Receivers MUST NOT auto-create a Declaration from unknown RTP. Unsolicited Join Request is V1 NOT SUPPORTED.

---

## Partition and heal

Partition:

```text
Islands KEEP the same conferenceEpoch, mediaKeyEpoch, multicast group
In-island media continues
Partition MUST NOT mint a new Conference
```

Heal:

```text
RTP of a Q5-admitted current incarnation may resume mix.
Fenced / superseded / quarantined incarnation MUST NOT resurrect on SSRC return.
Unknown RTP MUST NOT auto-create a Source Declaration.
```

If Owner mutated membership or keys during partition:

```text
Apply Q4 Membership Fact chain (previousMembershipDigest).
MUST NOT “higher version / keyEpoch wins on sight”.
Gap → MEMBERSHIP_CHAIN_PENDING; request missing Facts; MUST NOT skip-chain.
BRANCH_CONFLICT → apply neither.
Local Termination (Q3) already accepted → ignore late Membership Facts.
```

**Forbidden:**

```text
Route heal → everyone re-join
Media silence → new conferenceEpoch
Source reappear → restore a revoked member
```

---

## Health (not edge recovery)

Normative projection is **Q12**. Pre-grill triples (`MediaGroupReady` / `RemoteMediaActive` as lifecycle) are **void** where they conflict.

---

## Module seam (`ConferenceGroupMedia`)

**Informative** until Implementation Profile maps Q7/Q8. The names below MUST NOT be read as extra admission or key-activation authority.

Upper layers MAY only call a seam that **implements** Q1–Q12 (join after Membership+ACTIVE key, etc.):

```text
join(snapshot, keyMaterial)
publish(localPcmFrame)
observeMembership(view)
rotateMediaKey(keyEpoch, keyMaterial)
leave(reason)
```

MUST hide: multicast socket, RTP/SRTP, SSRC, Opus, jitter, replay, multi-source decode, VAD, local mix, Android `MulticastLock`, interface rebind.

UI, Session FSM, and Membership **MUST NOT** operate SSRC, sockets, or decoders.

Control plane **MUST NOT** synchronously wait for multicast join, codec, or AudioTrack.

---

## Invariants

```text
CM-1   Exactly one legal Current conferenceEpoch per Conference
CM-2   Non-current conferenceEpoch media never admitted
CM-3   Non-current mediaKeyEpoch media never admitted
CM-4   Non-membership sender never enters decode or mix
CM-5   One Sender Source failure MUST NOT affect other sources
CM-6   Sender Source failure MUST NOT change Conference lifecycle
CM-7   Mesh route change MUST NOT increment conferenceEpoch
CM-8   Membership change MUST NOT require rebuild of other legal sources
CM-9   Control MUST NOT synchronously wait for multicast / codec / AudioTrack
CM-10  Owner MUST NOT forward media or own a special media path
CM-11  After revoke, old group key MUST NOT send or receive
CM-12  Media silence MUST NOT imply Conference ended
CM-AUTH-1..3  as above
```

**Complexity budget (steady state):**

```text
Allowed:  members O(N); senders O(active talkers); recv state O(N); epochs O(1)
Forbidden: O(N²) media edges; O(N²) recovery edges; O(N²) generations
Forbidden: discovering the conference by opening pairwise media
```

---

## Non-goals

- Implementation, APIs, RFC numbers, cipher suites, timeouts, FSM tables
- GROUP PTT multicast, Floor change, GROUP Anchor change
- Merging ADR-0057 GBC into Conference
- Conference Owner succession / Owner-death invite continuation
- Intra-group Source Authentication (beyond V1 threat model)
- App-layer mesh routing, forwarding trees, Raft
- Immediate deletion of ADR-0056 runtime
- Dual V1 stacks (multicast + Anchor MCU live together)
- WiFi recovery / RNA / completion predicate changes
- Making Conference Owner a media server

---

## Conditions to enter implementation (later)

All of, **in this order**:

```text
1. ADR-0059 product dependency / risk assumption ACCEPTED
2. This ADR ACCEPTED — CONTRACT ONLY
3. Three Implementation Profiles frozen (Q13) mapping Q1–Q12 / CM-*
4. Explicit implementation authorization (PR / issue / session)
5. GROUP PTT and ADR-0057 remain untouched by that authorization
6. ADR-0056 retained for migration / capacity fallback until
   3/4/6/8/10 capacity gates PASS; then delete Anchor path
```

Until then:

```text
Implementation HOLD
Field HOLD
Do not delete Conference Anchor path
Do not dual-stack
```

`MaxUnderlayDatagramResidence` undeclared/unqualified → Endpoint reuse NOT QUALIFIED; V1 uses fresh Endpoints and therefore does not wait for a lab-derived quarantine value.

---

## Intended later sequence (informative, not authorization)

```text
ADR-0059 ACCEPTED
→ ADR-0058 ACCEPTED — CONTRACT ONLY
→ freeze three Implementation Profiles
→ explicit implementation authorization
→ keep ADR-0056 for migration / capacity fallback
→ capacity 3/4/6/8/10 PASS
→ delete ADR-0056 Conference Anchor path
```

---

## Decision log

| Topic | Decision |
|-------|----------|
| Conference V1 media | Multicast group; no Anchor; local mix |
| ADR-0056 Anchor decisions | Supersede on ACCEPT + cutover; keep fact-separation principles |
| ADR-0057 / GROUP PTT | Untouched |
| Owner | Control writer only; not media path |
| Owner gone, no terminate | V1 orphan + frozen membership; succession Non-goal |
| Decoder budget | N−1 lightweight; decode Top-K only; K=3..4; hysteresis |
| Level signals | Q11: authenticated voiceActive/audioLevel + discriminators; untrusted Hint lookup only; single Traffic-Profile level semantics |
| Threat model V1 | Non-members; NOT intra-group source auth |
| Shared group key | Group-member auth only; MUST NOT claim Module source auth |
| Dual stack | Forbidden |
| Underlay dependency | ADR-0059 ACCEPTED as product risk assumption; no lab qualification claim; implementation still separately authorized; 0056 deletion HOLD |
| Q1 Creation Fact | ACCEPTED (改 A): create-intent+admission Module originates atomic Creation Fact and becomes Owner; no plaintext group key on broadcast Fact; no Frankenstein assemble; same id/epoch disagree → CREATION_CONFLICT; address allocation deferred; pre-origin crash ≠ originated Conference |
| Q2 Media Group Descriptor | ACCEPTED (改 A): Candidate mutable until origin; Owner commit = first observable Creation Fact; Receiver commit = local accept; same epoch Descriptor immutable; no descriptorVersion in V1; CREATION_CONFLICT vs MEDIA_GROUP_COLLISION distinct; Claim + runtime detect; SRTP fail = suspicion only; unaffiliated COLLISION_BLOCKED; admitted COLLISION_DEGRADED isolate-continue ≠ resolved; relocate only via terminate + successor contract |
| Q3 Termination / Replacement | ACCEPTED (改 A): Owner origin = contract TERMINATED; local TERMINATED on accept; holders re-provide unmodified Termination Fact; reason mismatch = METADATA_CONFLICT does not block terminate; Replacement = new Conference + replacesCreationDigest; no implicit terminate/inherit/dual-send; orphan = no formal Replacement |
| Q4 Membership / key rotation | ACCEPTED (改 A): Owner-only writer and MUST remain in View; real member change bumps version+keyEpoch together; Profile 01 Q7 also permits same-View SECURITY_REKEY after establishment-key compromise, with both counters and all key material advanced; staged key pre-dist then Fact activates; chain gap PENDING no skip; holders re-provide; removed local leaves group without new key; Termination outranks late Mutation |
| Q5 Sender Source admission | ACCEPTED (改 A): Membership vs Declaration vs SRTP; generation+previousSourceDigest lineage; new instance MUST new SSRC; no SSRC reuse in same mediaKeyEpoch; Declaration before RTP; conflicts MUST quarantine; SRTP still group-member auth only |
| Q6 Source conflict convergence | ACCEPTED (改 A): same-sender Resolution covers listed claims only; Resolution Facts are claims; explicit none vs missing pred; collided SSRC/instance BURNED no restore; each sender redeclares new SSRC; Owner does not pick a winner |
| Q7 Group media key distribution | ACCEPTED (改 A): Owner-originated per-recipient Package bound to recipientModuleId + recipientKeyVersion; Fact-digest bound; commitment includes non-circular key context; INVALID vs MEMBERSHIP_BRANCH vs CRYPTOGRAPHIC_KEY_CONFLICT; old key retired on new Fact; ciphertext relay OK, plaintext MUST NOT hop |
| Q8 Invite / Join control | ACCEPTED (改 A): Invite≠Request≠Membership≠Key; orthogonal Join vs Key projections; V1 invite-only join; identities+Owner/requester origin; contract admit at Membership origin; explicit Revoke/Reject; no auto-rebase Invite |
| Q9 Local Leave / Owner REMOVE | ACCEPTED (改 A): Local Leave sticky per membershipIncarnationId; Leave Request binds incarnation not dead snapshot; Owner MUST eventually REMOVE/terminate; old Request MUST NOT delete new incarnation; Owner self-leave = Q3 only |
| Q10 Post-termination isolation / reuse | ACCEPTED (改 A): identities not reallocated; digest is stable content binding; tombstone has no highest-version LWW; quarantine is data-plane only; control Facts refused by identity/tombstone forever; quarantine ≠ old senders stopped; no global address-release authority |
| Q11 RTP authenticated discriminator | ACCEPTED (改 A): untrusted Hint only selects installed key context; auth-then-trust; unknown packet drop no cache; short discriminator unique/BURNED like SSRC; audioLevel profile unique; wire overhead belongs to the product workload profile; intra-group forge still possible |
| Q12 ConferenceHealth projection | ACCEPTED (改 A): orthogonal axes incl. Creation Alignment, Current vs Future key, Binding vs Transport; Source conflict = DEGRADED; global CONFLICT only Creation/Membership/crypto-key/BLOCKED collision; REMOVED ≠ LOCAL_LEFT; Health read-only |
| Q13 Contract closure | ACCEPTED (A), amended by ADR-0059: behavior grill COMPLETE; no new Conference behavior axes; three Implementation Profiles; contract ACCEPTED; impl NOT AUTHORIZED; 0056 RETAINED |
| Impl / field | NOT AUTHORIZED from this contract-only acceptance |
