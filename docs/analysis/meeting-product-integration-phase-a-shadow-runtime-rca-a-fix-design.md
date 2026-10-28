# Phase A Shadow Runtime — RCA-A Fix Design (PR-PA-SR-A1)

**Parent RCA:** `meeting-product-integration-phase-a-shadow-runtime-rca-handoff.md`  
**Field evidence:** `talkback/logs/phase-a-shadow-runtime-20260909-105513/`  
**Status:** **VERIFIED (PR-PA-SR-A1)** (2026-09-09) — peer field PA-SR1+SR2 PASS (`phase-a-shadow-runtime-20260909-111359`)

```text
Meeting J0               CLOSED / FIELD-PROVEN
Profile01 Meeting chain  CLOSED / FIELD-PROVEN

Phase A Shadow Runtime   RCA-A CLOSED · PR-PA-SR-B1 DESIGN FROZEN
RCA-B                    CLOSED (TYPE A)
PA-SR1/SR2               FIELD PASS (peer)
PA-SR3…SR6               NOT CLOSED

ADR-0056                 KEEP
Meeting media cutover    NOT AUTHORIZED
```

---

## Fix goal (one sentence)

> **CREATION 成功 apply 后，shadow replay 的可读性 gate 必须使用该已发布 CREATION 所携带的 authoritative `membershipVersion`，不得拿 TalkbackSession 的本地初始 `rosterEpoch` 代替。**

---

## Semantic freeze — two epoch domains (non-interchangeable)

| Domain | Symbol | Meaning | Authority | Used by |
|--------|--------|---------|-----------|---------|
| **Profile01 membership version** | `membershipVersion` | Wire/registry generation counter for Profile01 control facts; seeded by CREATION (`0` at origin); advanced by MEMBERSHIP facts | Published `ConferenceMediaSessionControlFact` / CREATION wire | `factPort.sessionFact` via `rosterEpochMatches(control, rosterEpoch)` where **parameter must carry `membershipVersion`** |
| **Meeting product roster epoch** | `TalkbackSession.rosterEpoch` | Local Meeting session lifecycle / topology roster counter (`INITIAL_ROSTER_EPOCH = 1`) | Talkback session model | Meeting admission, topology, UI — **not** Profile01 wire read gate |

```text
membershipVersion ≠ rosterEpoch

Numeric coincidence (both may be small integers) does NOT imply semantic interchange.
```

**Anti-pattern (current bug):** `MeetingProfile01ConferenceSessionIndex.Binding.rosterEpoch` populated from `TalkbackSession.rosterEpoch` at register, then passed to `sessionFact(..., rosterEpoch)` after CREATION apply when registry holds `membershipVersion = 0`.

**Correct contract:** After CREATION publish, shadow replay read gate uses **published** `membershipVersion` from the same registry fact that made `WIRE_INGRESS outcome=APPLIED` true.

---

## Design cut 1 — Who is authoritative

### For `sessionFact` lookup after CREATION APPLIED

| Source | Authoritative? | Notes |
|--------|----------------|-------|
| `ConferenceSessionMediaControlFactRegistry.session(conferenceId).membershipVersion` | **YES** | Written by `publishSessionDeclaration` on successful CREATION apply |
| CREATION wire / `AuthoritativeConferenceMediaSessionDeclaration.membershipVersion` | **YES** (same value at apply instant) | Origin bridge emits `membershipVersion = 0L` for initial CREATION |
| `TalkbackSession.rosterEpoch` / `INITIAL_ROSTER_EPOCH = 1` | **NO** for Profile01 read gate | Product-local; unrelated to Profile01 membership fact chain |
| `MeetingProfile01ConferenceSessionIndex.Binding.rosterEpoch` at session register | **NO** for CREATION replay | Unless explicitly redefined (this design does **not** redefine it) |

### Implication

`ConferenceSessionMediaFactAdapter.rosterEpochMatches(control, rosterEpoch)` is a **misnamed parameter** at the Profile01 boundary: the second argument must be **`membershipVersion` for read purposes**, not Meeting `rosterEpoch`. This design does **not** rename the adapter API in A1 (minimize diff); it fixes **what value is passed at the CREATION replay call site**.

---

## Design cut 2 — Where to fix

### Preferred (A1 scope): replay / read gate parameter source

**Location:** `MeetingProfile01FactWireIngress` — CREATION post-apply replay path.

```text
ingestCreation
  → publishSessionDeclaration (registry write)
  → recordPublishAndReplay(CREATION)
  → replayAfterPublish(CREATION)
  → replaySessionStarted(sessionId, sessionBinding)   ← fix here
```

**Required behavior:**

After `publishOutcome ∈ {ACCEPTED, IDEMPOTENT}` and before `onConferenceSessionStarted`:

1. Obtain **published** `membershipVersion` from the CREATION apply result (declaration on `ReadySession` validation, or registry read-back by `conferenceId` — same value).
2. Call coordinator replay with **`membershipVersion`**, not `sessionBinding.rosterEpoch`.

```text
replaySessionStarted(
  sessionId,
  channelId = sessionBinding.channelId,
  rosterEpochForRead = publishedMembershipVersion,   // NOT sessionBinding.rosterEpoch
)
```

**Parity reference:** `ingestMembership` already does the right shape:

```text
sessionIndex.updateRosterEpoch(...)            // optional index side-effect
replayAfterMembership(..., copy(rosterEpoch = generation.membershipVersion))
```

A1 **does not require** mirroring the index `updateRosterEpoch` side-effect for CREATION unless a separate audit shows index consumers need it. **Index field remains Meeting-local semantics.**

### Explicitly out of A1 — do NOT

| Approach | Why excluded |
|----------|--------------|
| Update `MeetingProfile01ConferenceSessionIndex.rosterEpoch` to `0` on CREATION to “match” wire | Conflates domains; future readers assume index.rosterEpoch == Meeting roster |
| Change `TalkbackSession.rosterEpoch` default to `0` | Product lifecycle regression risk; wrong layer |
| Generic deferred replay / retry queue | RCA scope creep; masks ordering bugs |
| `sleep` / poll until fact visible | Registry is synchronous; not a visibility problem |
| Rename `rosterEpoch` parameter globally | Out of A1 minimal scope |

### SOURCE / binding paths

**Out of scope for PR-PA-SR-A1.** RCA-B remains **HOLD** until A1 field re-run.

---

## Design cut 3 — Regression surface (must lock)

### Must PASS after A1

| Case | Setup | Expected |
|------|-------|----------|
| **CREATION-only readability** | Index `rosterEpoch=1`, CREATION `membershipVersion=0`, registry publish ACCEPTED | `sessionFact(sessionId, channelId, 0)` returns non-null **without** any MEMBERSHIP wire fact |
| **CREATION replay** | Same | `onConferenceSessionStarted` → `SHADOW hook=SESSION_STARTED outcome=APPLIED` (harness/unit) |
| **CREATION→MEMBERSHIP progression** | CREATION `membershipVersion=0`, then MEMBERSHIP advances to `1` | Replay after membership uses `membershipVersion=1`; session fact at v1 readable; stale v0 read rejected |
| **Stale read rejection** | Registry at `membershipVersion=1`, caller passes `rosterEpochForRead=0` | `sessionFact` returns null (gate still works) |
| **Idempotent CREATION** | Second CREATION apply IDEMPOTENT | Replay still uses correct published version; no duplicate wiring harm |

### Must NOT change

| Surface | Constraint |
|---------|------------|
| J0 continuation chain | PRE-G/I/J, P1-E drain, digest fence — **unchanged** |
| `WIRE_INGRESS factType=1 outcome=APPLIED` semantics | Still means registry publish succeeded |
| Incomplete / supplement / pre-bind retention | No behavior change |
| MEMBERSHIP replay path | Existing `updateRosterEpoch` + `copy(rosterEpoch=generation.membershipVersion)` preserved |
| ADR-0056 / Meeting audible path | No AudioTrack / cutover touch |

### Must NOT add

- retry / sleep / background replay worker
- SOURCE_DECLARATION / `mediaConnectedModules` timing fixes
- RCA-B binding fixes bundled in same PR

---

## PR-PA-SR-A1 — frozen scope

```text
PR-PA-SR-A1

Scope:
- correct CREATION post-apply replay epoch/version source
  (published membershipVersion → sessionFact read gate)
- add focused unit coverage:
    membershipVersion=0 vs local rosterEpoch=1
    CREATION-only SESSION_STARTED APPLIED without MEMBERSHIP wire

Out of scope:
- SOURCE_DECLARATION replay
- mediaConnectedModules timing
- generic deferred replay framework
- MeetingProfile01ConferenceSessionIndex.rosterEpoch redefinition
- TalkbackSession.rosterEpoch default change
- J0 / P1-C / P1-E contract changes
- Meeting audio ownership / cutover
- RCA-B / DEFERRED_NO_BINDING
```

### Likely touch surfaces (implementation hint only)

| File | Change class |
|------|----------------|
| `MeetingProfile01FactWireIngress.kt` | CREATION `replayAfterPublish` passes published `membershipVersion` |
| `MeetingProfile01FactWireIngressTest.kt` (or adjacent) | New cases: v0 read with index rosterEpoch=1; CREATION-only APPLIED replay |

**No production OBS / field driver changes required for A1** — existing `PROFILE01_SHADOW_RUNTIME` + PA-SR adjudicator sufficient for re-run.

---

## Narrow acceptance criteria (A1 unit + field re-run gate)

### Unit / harness (pre-field)

```text
CREATION APPLIED (publish ACCEPTED)
→ same-registry sessionFact(sessionId, channelId, membershipVersion=0) succeeds
  when index.rosterEpoch=1
→ deferred SESSION_STARTED replay → APPLIED

no MEMBERSHIP fact required merely to make CREATION readable
```

### Field re-run (post-A1, Shadow Runtime only)

Re-run `phase-a-shadow-runtime-first-run.ps1` on same topology. **A1 success signal:**

```text
PRE-G/I/J + J0 prerequisite: still PASS
PA-SR1: SHADOW SESSION_STARTED outcome=APPLIED (both devices if applicable)
PA-SR2: PROFILE01_SHADOW_RUNTIME wiring live snapshot
```

**Not required for A1 closure:** PA-SR4 ingress/playout, PA-SR5 leave/rejoin, PA-SR6 teardown — those may remain open pending RCA-B or later runs. **Do not expand A1 acceptance** to full PA-SR1…SR6 PASS.

---

## RCA-B posture

```text
RCA-B: HOLD

Trigger to reopen:
  A1 field re-run shows SESSION_STARTED / wiring live
  AND MEMBER_MEDIA_READY still DEFERRED_NO_BINDING
  → separate RCA-B design / PR (not bundled with A1)
```

---

## Status board update (after design freeze)

```text
Phase A Shadow Runtime   RCA-A DESIGN FROZEN / PR-PA-SR-A1 READY
RCA-B                    HOLD
Implementation           PR-PA-SR-A1 · unit + peer field verified
```

---

## References

- RCA handoff: `meeting-product-integration-phase-a-shadow-runtime-rca-handoff.md`
- Field fail: `meeting-product-integration-phase-a-shadow-runtime-field-result-20260909-105513.md`
- Origin `membershipVersion=0`: `MeetingProfile01CreationOriginBridge.kt`
- Meeting `INITIAL_ROSTER_EPOCH=1`: `GroupMembershipSupport.kt` / `TalkbackSession.kt`
- Replay site: `MeetingProfile01FactWireIngress.replayAfterPublish` / `replaySessionStarted`
- Read gate: `MeetingProfile01AwareConferenceSessionMediaFactPort.sessionFact` + `ConferenceSessionMediaFactAdapter.rosterEpochMatches`
