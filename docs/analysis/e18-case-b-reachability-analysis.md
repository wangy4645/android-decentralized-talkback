# E.18 Case-B Reachability Analysis

**Status:** `ARCHAEOLOGY_CLOSED` (2026-08-03)  
**Does not:** modify ADR · modify code · validate adoption · validate R4 · label Case-B PASS/FAIL  
**Companion:** [e18-targeted-field-validation-plan.md](./e18-targeted-field-validation-plan.md) · [e18-case-c-closure.md](./e18-case-c-closure.md) · [e18-closure-review.md](./e18-closure-review.md)

**Archaeology closure (naming):**

```text
E.18 Case-B Reachability Archaeology
status = CLOSED

finding:
  digest projection identity mismatch (wiring drift vs ADR-0022 Q7-1 A)

scope:
  explains current Attempt-4c-B BLOCKED_BY_PRECONDITION
```

**Not named:** `Case-B root cause` — the Case-B contract did not fail; the probe selected the wrong projection for comparison.

---

## §1 Scope

Single question:

> Is §E.21 Case-B (`membershipProbeDisposition=CHECKED` **and** `membershipEpochConverged=true` / `converged=true`) **reachable** under the **current field recovery topology** (M02 host / M01+M03 / Attempt-4c WiFi-flap stimulus)?

**Out of scope:**

- Adoption / R4-impl proof
- Fixing hash lag, delaying completion, or forcing digest refresh to “get a B pass”
- Labeling `NOT_REACHABLE` until archaeology closes (observed absence ≠ protocol impossibility)

**Case-B status (informative):**

```text
Case-B:
  not field verified

reason:
  current production probe path compares
  CONFERENCE runtime projection
  against GROUP authority projection

classification:
  REACHABILITY_EXCEPTION
  (projection identity mismatch under current wiring)

deferral:
  deferred due to invalid comparison domain
  (not waived — follow-up requires separate projection alignment slice)

follow-up:
  Probe projection alignment — FOLLOW_UP_REQUIRED
```

**Attempt-4c-B replay:** `STOPPED` (2026-08-03). Do not retry flap to surface B; precondition is structural, not stimulus-sensitive.

**E.18 safety note:** Repeated Case-C is **not** a failure signal. Wired authority + mismatch + completion hold (no silent-open) is the primary guard — **verified** (#115 and supplementary). Case-B is **positive convergence coverage**, not the safety boundary.

---

## §2 Evidence set

**Failure surface split (do not merge):**

| Family | Primary resolver signal | Typical epoch | B-target? |
|--------|-------------------------|---------------|-----------|
| Case-C canonical (#115) | `EPOCH_MISMATCH` | `localEpoch < authorityEpoch` | No |
| B-target / hash divergence | `HASH_MISMATCH` | often `localEpoch == authorityEpoch` | Yes (miss) |

Both collapse to `membershipEpochConverged=false` and fact `reason=MEMBERSHIP_EPOCH_MISMATCH`. Always read resolve-trace `reason` separately.

### 2.1 Canonical Case-C (#115)

| Run | Session | Resolve reason | Epoch (local / authority) | Hashes (local / authority) | Control fact |
|-----|---------|----------------|---------------------------|----------------------------|--------------|
| `phase3c-b-attempt4c-20260803-150349` | `ef942fbe-…` | `EPOCH_MISMATCH` | 1 / 4 | `-1909318575` / `-1399100200` | `CHECKED`, `converged=false`, `MEMBERSHIP_EPOCH_MISMATCH` |
| `phase3c-b-attempt4c-20260803-144151` | `9737c285-…` | `EPOCH_MISMATCH` | 1 / 2 | `-1909318575` / `889465109` | `CHECKED`, `converged=false`, `MEMBERSHIP_EPOCH_MISMATCH` |

**Pattern:** Authority **wired** (`DefaultMembershipAuthorityResolver`, `lastSeenAuthorityDigestByChannel`). Resolver fails on **epoch** and/or **hash**. Fact reason collapses to `MEMBERSHIP_EPOCH_MISMATCH` when `membershipEpochConverged=false` (see `EdgeRecoveryModels`).

### 2.2 B-target runs (not promoted)

| Run | Session | Stimulus | `recoveryAttemptId` | Resolve reason | Epoch | Hashes | Promotion |
|-----|---------|----------|---------------------|----------------|-------|--------|-----------|
| `…-181357` | `9d04f888-…` | manual M03 WiFi | 6 | `HASH_MISMATCH` | 1 / 1 | `-1909318575` / `-528664596` | `NOT_PROMOTED` (`ATTEMPT4C_B_CANDIDATE_MISS`) |
| `…-183251` | `c978c6c0-…` | manual M03 WiFi (new session) | 9 | `HASH_MISMATCH` | 1 / 1 | `-1909318575` / `-528664596` | `NOT_PROMOTED` |

Unified control surface:

```text
membershipProbeDisposition=CHECKED
membershipEpochConverged=false
reason=MEMBERSHIP_EPOCH_MISMATCH   # aggregate label; resolve trace says HASH_MISMATCH
```

### 2.3 Cross-run invariant (structural signal)

```text
localHash = -1909318575
```

Appears across:

- Canonical Case-C runs (`150349`, `144151`)
- B-target runs (`181357`, `183251`)
- Multiple Joint / 4c-S logs on **2026-08-03** field day (50+ log files)

**B-target pair (stable across two sessions):**

```text
localHash      = -1909318575
authorityHash  = -528664596
epoch aligned  = 1 == 1
resolver       = HASH_MISMATCH → converged=false
```

This is **not** “bad luck on one flap”; it is a **repeatable digest pair** under current recovery reconciliation.

### 2.4 Counter-evidence: ALIGNED has occurred in field (different context)

`logs/phase3b-jb-joint-20260802-130006/auth-stream.log`:

```text
localHash=-528664596 authorityHash=-528664596
reason=ALIGNED result=true
```

**Implication:** Protocol path for `converged=true` **exists** in some sessions. Current 2026-08-03 recovery reconciliation consistently compares `localHash=-1909318575` against `authorityHash=-528664596`. Reachability question is **topology / digest-source alignment**, not “resolver never aligns.”

### 2.5 INCONCLUSIVE B-target runs (stimulus miss)

`175944`, `180732`, `175846` — `NO_ABSENT` / Control `NONE`. Excluded from mismatch archaeology; do not count as B attempts.

---

## §3 Question revision

**Retired framing:**

> Why did expectedEpoch fail to propagate?

**Current framing:**

> Why does reconciliation see **epoch-aligned** (or epoch-mismatched) facts while **digest convergence** (`memberHash` alignment) does not hold — and why is `localHash=-1909318575` invariant while `authorityHash` varies?

**Label hygiene:** `MEMBERSHIP_EPOCH_MISMATCH` on `RECOVERY_CONTROL_RECONCILIATION_FACT` means `!membershipEpochConverged`, not necessarily `expectedEpoch != observedEpoch`. Always read `MEMBERSHIP_AUTHORITY_RESOLVE_TRACE.reason` (`EPOCH_MISMATCH` vs `HASH_MISMATCH` vs `ALIGNED`).

---

## §4 Trace chain (code + log anchors)

```text
TopologyDigest (local membership view at probe time)
        |
        v
lastSeenAuthorityDigestByChannel[channelId]   (observation cache on M02)
        |
        v
DefaultMembershipAuthorityResolver.evaluateMembershipConvergence
        |
        v
CONTROL_RECONCILIATION_MEMBERSHIP_CHECKED
        |
        v
RECOVERY_CONTROL_RECONCILIATION_FACT
```

| Layer | Question | Current field observation |
|-------|----------|---------------------------|
| **Local digest producer** | What inputs form `localMembershipView` / `TopologyDigest.fromSession`? | `localHash=-1909318575` stable across sessions on M02 auth during 2026-08-03 recovery probes |
| **Authority digest source** | What populates `lastSeenAuthorityDigestByChannel`? (`TalkbackCoordinator` ~3062, ~11010) | `authorityHash` varies by run; B-target pair uses `-528664596` |
| **LastSeen cache** | Same channel (`CH-01`), same generation? Stale vs live? | Cache present (not `AUTHORITY_DIGEST_MISSING`); mismatch is **value** not absence |
| **Resolver** | Correct authority selected? | `DefaultMembershipAuthorityResolver`, `authorityId=M01` on facts for `remote=M03` |
| **Probe → fact** | Strict epoch then hash compare? | Yes per `MembershipAuthorityResolver.kt`: epoch match then `memberHash` compare; failure → `converged=false` |

Resolver predicate (informative):

```kotlin
local.rosterEpoch != authorityDigest.rosterEpoch -> EPOCH_MISMATCH
local.memberHash != authorityDigest.memberHash   -> HASH_MISMATCH
else -> ALIGNED
```

---

## §5 Timeline template (`183251`, `recoveryAttemptId=9`)

| T | Timestamp | Event |
|---|-----------|-------|
| T0 | 18:33:23.596 | `RECOVERY_DELIVERY_REQUESTED` L5 → M03 |
| T1 | 18:33:23.611 | `RECOVERY_DELIVERY_LOCAL_ACCEPTED` / `PENDING` |
| T2 | 18:33:23.615 | `MEMBERSHIP_AUTHORITY_RESOLVE_TRACE` `HASH_MISMATCH` (epoch 1==1) |
| T3 | 18:33:23.616 | `CONTROL_RECONCILIATION_MEMBERSHIP_CHECKED` `converged=false` |
| T4 | 18:33:23.616 | `RECOVERY_CONTROL_RECONCILIATION_FACT` `CHECKED` / `membershipEpochConverged=false` |

**Open timing question:** Did `HASH_MISMATCH` exist **before** T0 (pre-recovery steady state), or only after delivery opened the reconciliation window? Steady-state preflight on `c978c6c0` showed **no** reconciliation lines — mismatch appears **inside** recovery episode, but `localHash` invariant suggests local view may be structurally fixed for this topology.

---

## §6 Branch conclusions (archaeology closed 2026-08-03)

### Path A — Timing / propagation — **RULED OUT**

`localHash=-1909318575` is hash of **empty** canonical roster (`memberHash(CH-01, epoch=1, [])`), stable across sessions and authority epoch changes. HELLO cache is fresh at recovery T0. Not explained by stale authority, epoch propagation lag, or digest timing.

**Do not fix at:** epoch retry, HELLO timing, completion delay, membership convergence timeout.

### Path B — Structural non-reachability under current topology — **REFRAMED**

Case-B positive consumption is **blocked by precondition** (wrong projection wired), not by random field failure. Under current M02-host + active GROUP session + conference recovery, `HASH_MISMATCH` is **expected** until probe reads GROUP domain.

### Path C — Wiring drift / projection mismatch — **CONFIRMED**

```text
CONFERENCE runtime:
    ConferenceParticipantManager.roster() -> [M01,M02,M03]

Recovery probe:
    TopologyDigest.fromSession(conferenceSession)
        -> session.groupMembers -> []

Authority:
    HELLO cache -> GROUP membership -> [M01,M02,M03]
```

**Classification:** `wrong projection selected for comparison` — not hash algorithm defect, not hash collision, not authority lag.

**Then:** E.18 §E.21 observation contract is satisfied at the **safety** layer (Case-C); positive B consumption requires separate wiring slice — not field replay.

---

## §7 What we are not concluding

| Statement | Status |
|-----------|--------|
| `Case-B failed` | **Reject** — contract not exercised; probe projection mismatch |
| `Case-B root cause found` | **Reject** — use `digest projection identity mismatch` (wiring drift) |
| `Case-B NOT_REACHABLE` (protocol-wide) | **Reject** — historical `ALIGNED` exists |
| `Case-B PENDING` (keep flapping) | **Reject** — replay stopped; precondition known |
| `E.18 unsafe` | **Reject** — Case-C guard behavior consistent |

**Working statement:**

```text
Case-B field verification blocked by projection mismatch under current wiring.

Observed under Attempt-4c-B:
  CHECKED + converged=false (Case-C family surface)

Not observed under current wiring:
  CHECKED + converged=true + membershipEpochConverged=true

Historical counter-example:
  ALIGNED with matching hashes in joint-20260802 (different exercise context)
```

---

## §8 Archaeology findings (2026-08-03, code + log)

### 8.1 `TopologyDigest.fromSession` (local producer)

**Code:** `TopologyDigest.fromSession` → `GroupMembershipSupport.memberHashForSession(session)` over `canonicalMemberModuleIds(session)` (sorted module ids, FNV-1a; **excludes** pending invitees and evicted members).

**Recovery wiring** (`TalkbackCoordinator`):

```text
WiredMembershipEpochProbe.resolveContext(conferenceSessionId)
  → localMembershipView = TopologyDigest.fromSession(conferenceSession)
```

Probe uses the **conference session** record (`session=c978c6c0-…`), not `grp:CH-01` group session id.

**Log signal — localHash invariant:**

```text
localHash = -1909318575   (stable across conference sessions on 2026-08-03 recovery probes)
```

**Roster identity (confirmed by unit hash replay, 2026-08-03):**

```text
memberHash("CH-01", rosterEpoch=1, [])              = -1909318575   ← recovery localHash
memberHash("CH-01", rosterEpoch=1, [M01,M02,M03])   = -528664596    ← authority / GROUP snapshot
```

`-1909318575` is **not** a partial or lagging 3-member roster. It is the digest of **zero canonical module ids** — i.e. `session.groupMembers` empty at probe time.

**Why conference `groupMembers` is empty while `joined=3`:**

At `meshCallInternal` for `SessionType.CONFERENCE`, roster is seeded into `ConferenceParticipantManager.initSession`, **not** `session.groupMembers` (GROUP path sets `groupMembers`; CONFERENCE path does not — see `TalkbackCoordinator` ~1874–1878).

```text
CONFERENCE_RUNTIME_PROJECTION joined=3     ← conferenceParticipantManager.roster()
TopologyDigest.fromSession(conference)     ← session.groupMembers (empty)
```

`canonicalMemberModuleIds` reads **only** `session.groupMembers` (`GroupMembershipSupport.kt`); it does **not** consult `meshRoster()` / `conferenceParticipantManager`.

Invariant across **both** canonical Case-C (`150349`, authorityEpoch=4) and B-target (`183251`, authorityEpoch=1) → local digest is **not** tracking authority epoch propagation; it reflects a **stable empty conference-session `groupMembers` projection**, independent of conference join count.

### 8.2 `lastSeenAuthorityDigestByChannel` (authority cache)

**Code:** `recordAuthorityDigestFromHello` writes cache when:

```text
payload.moduleId == session.anchorModuleId (or bootstrap primary)
payload.rosterEpoch > 0
```

Digest fields come **directly from HELLO** (`rosterEpoch`, `memberHash`, …) — not recomputed from local session.

**Field topology note:** `resolveMembershipAuthorityId` / reconciliation `authorityId=M01` on M02 auth facts → anchor is **M01**, while M02 is conference host. Authority digest cache reflects **M01 HELLO**, not M02 local roster.

**Log signal — authorityHash for B-target pair:**

```text
authorityHash = -528664596   (stable in 181357 + 183251)
```

Same value as `TOPOLOGY_SNAPSHOT` on M02 for **group channel** `grp:CH-01`:

```text
members=M01,M02,M03  rosterEpoch=1  memberHash=-528664596
membershipDigestAligned=true
```

So channel-level topology believes **3-member aligned digest**, while recovery probe local side uses **different hash** (`-1909318575`).

### 8.3 Resolver compare target

**Code:** `DefaultMembershipAuthorityResolver` compares epoch then `memberHash` on:

```text
local  = TopologyDigest.fromSession(conferenceSession)
authority = lastSeenAuthorityDigestByChannel[channelId]   // from anchor HELLO
```

**B-target (`183251`, recoveryAttemptId=9):**

```text
T0 18:33:23.596  RECOVERY_DELIVERY_REQUESTED
T2 18:33:23.615  RESOLVE_TRACE reason=HASH_MISMATCH epoch 1==1
T3 18:33:23.616  CHECKED converged=false
T4 18:33:23.616  FACT MEMBERSHIP_EPOCH_MISMATCH
```

Mismatch appears **inside** recovery window (not visible in steady-state preflight logcat). `sessionEpochMatched=true` — conference session epoch matches; **membership** digest does not.

### 8.4 Finding — projection identity mismatch (Path C confirmed)

**Not timing-only:** `localHash` does not chase `authorityEpoch` across canonical Case-C runs.

**Finding (wiring drift — not Case-B contract failure):**

```text
Recovery probe local digest  ← TopologyDigest.fromSession(CONFERENCE) over empty groupMembers
Authority cache digest       ← M01 HELLO memberHash for GROUP roster [M01,M02,M03]
Group TOPOLOGY_SNAPSHOT      ← TopologyDigest.fromSession(GROUP grp:CH-01) — same as authority
```

Recovery reconciliation is comparing **channel authority membership** to **conference-session `groupMembers` digest** — not to the GROUP session digest that `membershipDigestAlignedWithAuthority` already uses for `TOPOLOGY_SNAPSHOT`.

**Counter-evidence (blocks `NOT_REACHABLE`):** `phase3b-jb-joint-20260802-130006` achieved `ALIGNED` with both hashes `-528664596` — Case-B **reachable in at least one historical topology** (trace shows `localGroupSessionId=null`; may reflect older field state or digest wiring — does not invalidate the structural split above on current APK).

**Correct statement:**

```text
Case-B reachable in at least one historical topology.
Current Attempt-4c field recovery setup fails reachability precondition.
Digest projection alignment under investigation → closed as implementation/contract drift (§8.5).
```

### 8.5 Digest semantics matrix — archaeology **CLOSED**

```text
E.18 Case-B Reachability Archaeology
status = CLOSED
finding = digest projection identity mismatch
scope  = explains Attempt-4c-B BLOCKED_BY_PRECONDITION
```

#### Q1 — What does `TopologyDigest.fromSession(session)` mean?

| Input field | Source | GROUP `grp:CH-01` | CONFERENCE `c978c6c0` |
|-------------|--------|-------------------|------------------------|
| `rosterEpoch` | `session.rosterEpoch` | channel GROUP session | conference session (often `1` at create) |
| `memberHash` | FNV over `channelId` + `rosterEpoch` + **sorted** `canonicalMemberModuleIds` | `session.groupMembers` minus evicted | **`session.groupMembers`** (typically **empty**) |
| Excludes | pending invitees, evicted | yes | yes (vacuous when empty) |
| Does **not** use | — | `ConferenceParticipantManager.roster`, ICE edges, recovery obligation view | same |

**Printed `members=M01,M02,M03` on `TOPOLOGY_SNAPSHOT` is GROUP-session `groupMembers`.** It is **not** evidence that the conference session fed the same ids into `fromSession` at recovery probe time.

**Hash identity for `183251` / `c978c6c0`:**

| Projection | Session id | Canonical ids at probe | `memberHash` |
|------------|------------|------------------------|--------------|
| Recovery `localHash` | `c978c6c0` (CONFERENCE) | `[]` (empty `groupMembers`) | `-1909318575` |
| `authorityHash` / snapshot | `grp:CH-01` (GROUP) + HELLO cache | `[M01,M02,M03]` | `-528664596` |
| `CONFERENCE_RUNTIME_PROJECTION` | `c978c6c0` | roster via `ConferenceParticipantManager` (`joined=3`) | *(not used by digest)* |

#### Q2 — Should the two digests be compared as wired today?

**ADR-0022 Q7-1 (frozen, E.12):** `membershipEpochConverged` = recovery participant view matches **channel GROUP topology authority digest** — **not** conference runtime roster.

| Question | Answer |
|----------|--------|
| Should authority HELLO digest be compared to **something local** for Case-B? | **Yes** — channel membership convergence is the Q6-2 gate |
| Should the local side be `TopologyDigest.fromSession(conferenceSession)`? | **No** — Q7-1 **rejected** option B (conference as membership authority); selected option A (GROUP session / `membershipDigestAlignedWithAuthority` domain) |
| What does current code do? | `WiredMembershipEpochProbe.resolveContext` sets `localMembershipView = TopologyDigest.fromSession(**conferenceSession**)` — **implementation drift** from Q7-1 A (already documented as wiring gap in ADR-0022 E.12) |
| Is `HASH_MISMATCH` therefore “convergence failure”? | **Not interpretable as field membership divergence** under current wiring — it is largely **compare-object mismatch** (empty conference `groupMembers` vs 3-member GROUP authority view) |
| What pair **should** be equal for Case-B? | `TopologyDigest.fromSession(grp:CH-01)` ↔ `lastSeenAuthorityDigestByChannel[CH-01]` (same predicate as `membershipDigestAlignedWithAuthority` on GROUP session) |

**Trace hygiene:** `MembershipAuthorityResolveTraceTest` examples use `localGroupSessionId=grp:CH-01`. B-target field traces show `localGroupSessionId=c978c6c0-…` because `WiredMembershipEpochProbe` passes `conferenceSessionId` into `evaluateMembershipConvergence(…, localGroupSessionId)` — the label matches the **wrong** session object used for the digest.

#### Semantic alignment matrix

| Digest / flag | Owner | Session / cache | Input roster | Meaning | `183251` value | Should equal |
|---------------|-------|-----------------|--------------|---------|----------------|--------------|
| `authorityHash` | `lastSeenAuthorityDigestByChannel` | channel `CH-01` | M01 HELLO `payload.memberHash` (GROUP authority view) | Observed channel membership authority | `-528664596`, epoch `1` | — |
| `localHash` (recovery probe) | `WiredMembershipEpochProbe` | CONFERENCE `c978c6c0` | `session.groupMembers` (empty) | **Miswired** local membership view for Q7 | `-1909318575`, epoch `1` | *Should* match authority via **GROUP** bridge, *does not* as wired |
| `TOPOLOGY_SNAPSHOT` `memberHash` | `GroupRuntimeHealthProjector` | GROUP `grp:CH-01` | `session.groupMembers` `M01,M02,M03` | Channel topology health projection | `-528664596`, epoch `1` | `authorityHash` ✅ |
| `membershipDigestAligned` | `membershipDigestAlignedWithAuthority` | GROUP `grp:CH-01` | GROUP digest vs authority cache | Channel-level alignment flag | `true` | consistent with GROUP ↔ authority |
| `CONFERENCE_RUNTIME joined` | conference projector | CONFERENCE `c978c6c0` | `ConferenceParticipantManager.roster` | Runtime conference join count | `3` | **orthogonal** to digest compare |

**Which two should be equal (per contract):**

```text
authorityHash  ==  TopologyDigest.fromSession(grp:CH-01).memberHash     ← Case-B positive path
```

**Not:**

```text
authorityHash  ==  TopologyDigest.fromSession(conferenceUuid).memberHash   ← current wiring (structural mismatch)
```

#### Authority cache write chain vs recovery T0 (`183251`, attempt `9`)

```text
18:33:21.779  HELLO from M01          ← last cache refresh before probe (~1.8s before T0)
18:33:23.596  RECOVERY_DELIVERY_REQUESTED   (T0)
18:33:23.615  RESOLVE_TRACE HASH_MISMATCH
```

HELLO cadence is continuous; authority cache is **fresh** and carries the 3-member GROUP digest. Failure is **not** explained by “authority not updated” or “epoch not propagated” at T0.

#### Architectural boundary (R4-relevant)

```text
MembershipAuthority / HELLO
        |
        v
Channel membership view (GROUP grp:CH-01)     memberHash=-528664596  aligned=true

ConferenceRuntime / ConferenceParticipantManager
        |
        v
Session topology / join projection (CONFERENCE) joined=3  groupMembers=[]  digest=-1909318575
```

Q7-1 requires an explicit **bridge** from recovery reconciliation to the GROUP authority domain. Current probe skips that bridge and reads conference `groupMembers` instead — so Case-B precondition failure on Attempt-4c is **expected** under M02-host + active GROUP session, not a mystery hash lag.

#### §8.5 closure actions (documentation only — no code / ADR status change)

| Action | Recommendation |
|--------|----------------|
| E.18 Case-B label | `REACHABILITY_EXCEPTION` — projection identity mismatch; not field verified |
| Field replay | **No further Attempt-4c-B** — invalid comparison domain |
| E.18 closure | Option 1 adopted — see [e18-closure-review.md](./e18-closure-review.md) |
| Probe wiring | `FOLLOW_UP_REQUIRED` — separate slice; does not roll back Case-C |
| R4 | Conference-local digest must not serve as authority membership proof |

---

## §9 Status board (informative)

```text
E.18
 ├─ E.18.1 Observable Gap             LANDED
 ├─ E.18.2 Authority Wiring           VERIFIED
 ├─ Case-C Membership Mismatch        FIELD_VERIFIED (#115)
 ├─ Case-B Match                      REACHABILITY_EXCEPTION
 │                                     projection identity mismatch
 └─ Case-A Unwired                    PENDING

E.18 Overall                         OPEN
E.18 Closure Review                  IN_REVIEW

Probe projection alignment           FOLLOW_UP_REQUIRED

R4-def                               DEFINED
R4-impl                              WAITING
```

---

## §10 Conclusion — Case-B reachability note

**Purpose:** Document reachability under current production wiring. **Does not** classify Case-B PASS or FAIL.

### Reachability verdict

| Question | Answer |
|----------|--------|
| Is Case-B protocol path impossible? | **No** — `ALIGNED` observed (`joint-20260802-130006`) |
| Is Case-B reachable under Attempt-4c-B + current APK wiring? | **No** — probe uses CONFERENCE `groupMembers` (empty) vs GROUP authority |
| Why is Attempt-4c-B blocked? | **Precondition:** wrong projection selected for comparison |
| Does this invalidate E.18 safety evidence? | **No** — Case-C guard (wired + mismatch + hold) verified separately |

### Positive outcome (architecture)

> E.18 authority convergence semantics must bind to **GROUP membership projection**, not CONFERENCE runtime projection (`ConferenceParticipantManager.roster`).

Exposing this boundary before R4 adoption is a **forward result**, not a validation failure.

### What this note does **not** claim

```text
✗ Case-B PASS
✗ Case-B FAILED
✗ Case-B waived
✗ Case-B NOT_REACHABLE (protocol-wide)
✗ Fix by epoch retry / HELLO timing / completion delay / convergence timeout
```

Case-B positive field verification is **deferred due to invalid comparison domain**, not waived.

### Follow-up (out of scope for this archaeology)

```text
E.18 follow-up implementation slice:
  wire WiredMembershipEpochProbe local view to GROUP session (Q7-1 A)
  does not roll back Case-C or safety conclusions from this round
```

---

*Evidence boundary document. Does not amend ADR status, alter E.18 acceptance criteria, or unlock R4-impl. See [e18-closure-review.md](./e18-closure-review.md) for Option 1 decision record.*