# P0′ SOURCE-INCARNATION-ON-MEDIA-KEY-EPOCH — RCA

**Status:** CONTRACT ADJUDICATED · ORIGIN LIFECYCLE FIXED · UNIT VERIFIED · FIELD PENDING  
**Date:** 2026-09-15  
**Field trigger:** `talkback/logs/p0prime-local-source-registry-r2a-20260915-140434/` (session `a7d909a7…`)

## Q1 — Who owns `sourceGeneration`?

`Profile01LocalConferenceSourceIdentityAuthority` (origin side). It is a per `(sessionId, moduleId)`
monotonic counter that advanced **only** when the committed identity tuple
`(ssrc, sourceInstanceId, mediaGroupDescriptorDigest)` changed.

The class had **no notion of `mediaKeyEpoch`**. On epoch 1→2 the coordinator called
`commitLocalSource(sessionId, moduleId)` with no identity override, so the existing commitment was
returned verbatim: `sourceGeneration=1`, same SSRC, same `sourceInstanceId`. The origin bridge then
re-signed a *new* Declaration body (`mediaKeyEpoch=2`) under the *old* incarnation number.

Not hardcoded, not a default — a genuine missing lifecycle input.

## Q2 — Why `REJECTED_INCOHERENT`?

`Profile01ConferenceMediaFactValidator` maps `membershipIncarnationId = wire.sourceGeneration`.
`ConferenceSessionMediaControlFactRegistry.publishMember` then evaluates, in order:

```text
factGeneration <  current                        → REJECTED_STALE
factGeneration == current && memberFactsEqual    → IDEMPOTENT
membershipIncarnationId <  current               → REJECTED_STALE
membershipIncarnationId == current
  && !memberFactsEqual                           → REJECTED_INCOHERENT   ← hit
```

`memberFactsEqual` compares `moduleId · membershipIncarnationId · ssrc · mediaKeyEpoch ·
membershipVersion · sourceAdmissionKey48`. Same incarnation with a changed `mediaKeyEpoch` is
therefore, by construction, an incoherent fact. **The registry was right.**

## Q3 — Frozen contract: does an epoch change require a successor incarnation?

**YES.** ADR-0058 `0058-conference-multicast-media-group.md`:

```text
Must new sourceInstanceId + new SSRC + new Declaration:

send-pipeline rebuild
membershipVersion change (after Q4 Fact + new key active)
mediaKeyEpoch change (same)
ROC / SRTP index reset
```

plus:

```text
sourceGeneration
  monotonic +1 for the same senderModuleId in the same conferenceEpoch

Each new sourceInstanceId
  MUST use a new random SSRC.
```

So `mediaKeyEpoch` advance ⇒ new source incarnation is **mandated**, and the crypto rationale is
explicit (reusing an SSRC under a new key while resetting packet index risks nonce/keystream reuse).

**Verdict:** origin lifecycle bug. The registry coherence key MUST NOT be relaxed.

## Fix — origin/rebuild authority only

`Profile01LocalConferenceSourceIdentityAuthority.commitLocalSource` now takes `mediaKeyEpoch`, and
`Commitment` carries the epoch it was committed against.

```text
epoch unchanged        → idempotent, same incarnation
epoch advanced         → successor: sourceGeneration+1, new random SSRC, new sourceInstanceId
epoch regressed (late) → no new incarnation; head retained
```

`MeetingProfile01SourceOriginBridge.currentAuthoritativeMediaKeyEpoch(sessionId)` exposes the
converged epoch, so the coordinator reads the epoch from the same membership convergence used to
build the signed Declaration rather than guessing.

### Disclosed consequence — SSRC allocation

The previous allocator was `AtomicInteger(0x10203000).incrementAndGet()`: deterministic and
identical on every device. Field evidence shows M01 **and** M02 both declared
`ssrc=270544897` (`0x10203001`) — a cross-module `SSRC_COLLISION` under ADR-0058. Honouring
"each new incarnation uses a new random SSRC" required replacing this allocator, so it changed as
part of the incarnation fix. This may independently affect M02 admission; **M02 was not
investigated and remains parked.**

## Regression coverage

`Profile01SourceIncarnationOnMediaKeyEpochTest` (7 tests):

| Test | Asserts |
|------|---------|
| `mediaKeyEpochAdvance_commitsSuccessorIncarnation` | gen 1→2, new SSRC, new instance id |
| `sameMediaKeyEpoch_isIdempotentIncarnation` | no spurious incarnation churn |
| `lateLowerMediaKeyEpoch_doesNotCommitNewIncarnation` | stale-epoch fence at origin |
| `distinctIncarnations_doNotReuseSsrc` | no SSRC reuse across incarnations |
| `successorGenerationAtNewEpoch_isAcceptedAndReplacesPredecessor` | registry ACCEPT at head |
| `sameGenerationAtNewEpoch_isRejectedIncoherent` | **fence not relaxed** (field break reproduced) |
| `lateIncarnationAtOldEpoch_cannotReplaceCurrentSource` | late gen1/epoch1 cannot replace gen2/epoch2 |

Full `com.talkback.core.conference.session.*`: **236 tests, 0 failures.**

## Chain status

```text
epoch propagation        FIXED
rotation                 WORKS
local projection         WORKS (seam wired, structurally ordered)
registry coherence       CORRECTLY FENCES (unchanged)
SOURCE incarnation       FIXED — successor on mediaKeyEpoch advance
```

**STOP.** No field rerun, no F8, no M02/M03/Anchor work in this cut.
