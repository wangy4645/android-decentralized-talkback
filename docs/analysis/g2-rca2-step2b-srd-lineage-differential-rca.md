# G2-RCA2 Step 2b — SRD Lineage / Native-State Differential RCA

**ID:** g2-rca2-step2b-srd-lineage-differential-rca  
**Date:** 2026-09-11  
**Type:** RCA · Design · Instrumentation Authorization  
**Status:** **AUTHORIZED — OBSERVATION + NARROW FIX IF INVARIANT VIOLATION PROVEN**  
**Implementation:** **NOT AUTHORIZED** until hypothesis ranked + unit fixtures pass

**Authoritative field repro:** `talkback/logs/ga-g2-real-mesh-field-20260911-100610/`  
**Session:** `a5a5e25c-e59e-4f70-894a-95fc5a6297bd`  
**Build:** `cac628d`

---

## Frozen verdict board

```text
G2                         OPEN / VALID_FAIL
G2-RCA2 Step 1             CLOSED
G2-RCA2 Step 2             REOPEN / FIELD-DISPROVEN
095524                       INVALID / HARNESS_ABORT
100610                       VALID_FAIL / authoritative repro
A/B                          CLOSED / FIELD-PROVEN / FREEZE
Step 3 (WAIT_HOST_ICE)       NOT OPEN — host not stable
```

Step 2 proved **shared-factory serialization alone is not sufficient**.  
Step 2b asks **why M04 second-edge SRD passes and M02 second-edge SRD SIGABRTs** under the same fence.

---

## Core question

```text
Same session a5a5e25c on M01 host:

M04 @ attachedPcCount=2:
  SRD_NATIVE_CALL_ENTER → EXIT
  NATIVE_DOMAIN_EXECUTION_ENTER → EXIT
  RESULT: PASS

M02 @ attachedPcCount=2:
  SRD_NATIVE_CALL_ENTER → SIGABRT (signaling_thread / libjingle_peerconnection_so.so)
  RESULT: FAIL

Both edges:
  - same conferenceGeneration=1
  - same lease / mutex fence (Step 2)
  - Kotlin lineageCorrelation=MATCH at APPLY_REMOTE_ANSWER
  - signalingState=HAVE_LOCAL_OFFER, localDesc=OFFER, remoteDesc=NONE at SRD_ENTER
```

**Not the question:** whether two PCs can coexist on shared-factory (M04 already proves they can).  
**The question:** what pre-native state on the **M02 conference edge** is invalid despite Kotlin admission saying MATCH.

---

## Differential matrix (100610 · pre-SRD snapshot)

Evidence taken at `SRD_NATIVE_CALL_ENTER` unless noted.  
Log source: `M01-logcat.txt`, session `a5a5e25c`.

| Field | M04 PASS (`10:12:55.304`) | M02 FAIL (`10:13:03.173`) | Notes |
|-------|---------------------------|---------------------------|-------|
| **Outcome** | ENTER → EXIT (55 ms) | ENTER → SIGABRT (~13 ms) | M02 never reaches EXIT |
| **edgeKey** | `…\|M04` | `…\|M02` | Same session |
| **pcGeneration** | 9 | 5 | Different edges; both fresh conference PCs |
| **pcHash** | 108600122 | 254255717 | Distinct PC instances |
| **offerLineageId** | CR2 | CR1 | Both report MATCH to host local offer |
| **realizationAttemptId** | RA2 | RA1 | |
| **lineageCorrelation** | MATCH | MATCH | **Kotlin admission green; native red** |
| **signalingState** | HAVE_LOCAL_OFFER | HAVE_LOCAL_OFFER | Same |
| **localDesc / remoteDesc** | OFFER / NONE | OFFER / NONE | Same |
| **iceConnectionState** | NEW | NEW | Same at ENTER |
| **connectionState** | **CONNECTING** | **NEW** | **Divergence** |
| **attachedPcCount** | 2 | 2 | Same |
| **factoryHash** | 258969460 | 258969460 | Same factory instance |
| **answerSdpBytes** | 1450 | 1147 | Different SDP size |
| **offer→answer latency** | ~25 s | ~33 s | M02 waited for join |
| **SRD taskId** | 251 | 333 | Single SRD_APPLY each; no duplicate task |
| **queueWaitMs** | 3 | 0 | |
| **executor thread** | `tb-edge-…\|M04` | `tb-edge-…\|M02` | Same pattern |
| **MUTEX waitMs** | 0 | 0 | Fence acquired immediately |
| **PC provision** | CREATE_CONTINUATION | CREATE_CONTINUATION | Same intent |
| **MEDIA_PROVISIONED** | gen=9 pcHash=108600122 | gen=5 pcHash=254255717 | Both `factoryHit=NEW` |

### Timeline divergence (high signal)

| Phase | M04 | M02 |
|-------|-----|-----|
| CREATE_OFFER | `10:12:30.531` | `10:12:30.362` (first) |
| Peer bind / accept | M04 bound earlier in harness | M02 `ACCEPT_DISPATCHED` `10:13:02.998` |
| GROUP_ACCEPT → APPLY_REMOTE_ANSWER | `10:12:55.206` | `10:13:03.111` |
| ICE before SRD | Candidates **queued** `10:12:55.283` **before** native call | **No** candidate received before SRD |
| ICE during SRD | None observed on coordinator | **`MEDIA_SIGNAL_CANDIDATE_RECEIVED` queued=true `10:13:03.241`** — **during in-flight native SRD** |
| ICE after SRD | Applied inside edge task post-EXIT | N/A (crash) |
| inviteResend suppression | N/A (already accepted) | **~33 s** of `CONFERENCE_EDGE_RESTART_SUPPRESSED reason=ACCEPT_HANDOFF_ACTIVE source=inviteResend` while offer outstanding |

### What did **not** differentiate

```text
- Serialization fence (both acquired mutex, same factoryHash)
- attachedPcCount=2 (M04 proves 2-PC SRD can succeed)
- Kotlin lineageCorrelation=MATCH (both)
- Duplicate SRD_APPLY tasks (single task per edge)
- Harness join path (PRE-J1..J5 PASS; auto tap)
```

### Prior grp:CH-01 control (same run, different scope)

Earlier in the same M01 log, `grp:CH-01|M02` SRD at `attachedPcCount=2` (`10:09:53.736`) **passed** on a **different** PC (`pcHash=66994152`).  
This refutes a naive model of “any M02 second SRD on factory aborts”. Failure is **conference-scoped edge state**, not peer identity alone.

---

## Ranked hypotheses (Step 2b)

### H1 — ICE ingress concurrent with in-flight SRD (leading)

M02 SRD starts with `connectionState=NEW` and no pre-queued remote candidates.  
Remote ICE arrives on **coordinator thread** while `SRD_NATIVE_CALL_ENTER` is active (`10:13:03.241`, `queued=true`).  
M04 had candidates queued **before** native entry and drained **after** EXIT inside the same edge task.

**Mechanism:** `addIceCandidate` / coordinator ICE dispatch not fully fenced against in-flight `setRemoteDescription` on the **other** attached PC, or re-enters native on signaling thread during SRD.

**Discriminate:** pre-SRD snapshot + log whether any `addIceCandidate` / `MEDIA_ICE_CANDIDATE_APPLIED` fires between ENTER and EXIT.

### H2 — Stale / mismatched answer admitted despite lineageCorrelation=MATCH

Kotlin reports MATCH on envelope lineage id (CR1/RA1) but does not prove:

- answer SDP `ice-ufrag` / `ice-pwd` matches current local offer generation
- answer fingerprint matches outstanding `pcGeneration=5` native object
- answer is not from an earlier invite-resend generation

M02 had **33 s** of outstanding local offer with continuous `inviteResend` while `ACCEPT_HANDOFF_ACTIVE`.

**Discriminate:** snapshot local/remote SDP credential fingerprints + `lastOfferGeneration` vs answer generation at admission gate.

### H3 — Long-lived outstanding offer left native PC in valid-for-Kotlin / invalid-for-native state

`connectionState=NEW` vs M04 `CONNECTING` may indicate M02 PC never progressed ICE checking before answer apply.  
Native may reject answer apply when signaling thinks offer is valid but transport sub-state differs.

**Discriminate:** `iceGatheringState`, `pendingLocalDescription`, transport generation counters at ENTER.

### H4 — Conference PC lifecycle / CREATE_CONTINUATION stale object

Both edges use `CREATE_CONTINUATION`, but M02 is the **first conference edge provisioned** in session (`10:12:30.217`) and sits idle ~33 s before answer.  
M04 provisioned milliseconds later and accepted sooner.

**Discriminate:** edge incarnation counter, time-since-offer, `mediaSessionReuse` / release history on same `pcHash`.

### Deprioritized / excluded

```text
EXCLUDED: generic global mutex (Step 2 field-disproven)
EXCLUDED: harness/UI (100610 PRE-J PASS)
EXCLUDED: duplicate SRD task dispatch (single taskId 333)
UNLIKELY:  M02 peer-specific identity (grp:CH-01 M02 SRD passed at attachedPcCount=2)
```

---

## Pre-SRD invariant snapshot (instrumentation spec)

**Emit one line immediately before `SRD_NATIVE_CALL_ENTER`:**

```text
SRD_PRE_NATIVE_SNAPSHOT
```

### Required fields

| Group | Fields |
|-------|--------|
| Edge identity | `session`, `edgeKey`, `remote`, `edgeIncarnation`, `conferenceGeneration` |
| PC identity | `pcGeneration`, `pcHash`, `factoryHash`, `attachedPcCount`, `trackId` |
| Signaling | `signalingState`, `iceConnectionState`, `iceGatheringState`, `connectionState` |
| Descriptions | `localDescription.type`, `remoteDescription.type`, `pendingLocalDescription.type`, `pendingRemoteDescription.type` |
| Lineage | `offerLineageId`, `realizationAttemptId`, `hostLocalOfferLineageId`, `lineageCorrelation`, `lastOfferGeneration`, `lastAppliedRemoteGeneration`, `iceRestartGeneration` |
| SDP credentials | `localIceUfrag`, `localIcePwdFingerprint`, `expectedAnswerIceUfrag`, `expectedAnswerIcePwdFingerprint` (from parsed answer about to apply) |
| Task chain | `srdTaskId`, `predecessorTaskId`, `origin`, `queueWaitMs`, `timeSinceOfferMs` |
| Factory domain | `leaseHolderEdgeKey`, `domainExecutionState`, `nativeDomainOccupancy` |
| Ingress pressure | `queuedRemoteIceCount`, `coordinatorIceIngressDuringSrd` (boolean latch) |

### Comparison protocol

1. Capture snapshot for **M04 PASS** and **M02 FAIL** in same session.
2. Diff field-for-field; any delta not explained by peer identity becomes candidate root cause.
3. Attach snapshot to field log adjudication file (`G2_RCA2_FIELD_VERDICT.txt`).

**Read-only in Step 2b phase 1** — no behavior change.

---

## Native call admission invariant (design)

> Only enter `setRemoteDescription(ANSWER)` when the answer **cryptographically and generationally** matches the PC's **current outstanding local offer**.

### Proposed gate (Kotlin · SRD lineage / admission layer)

```text
ADMIT_ANSWER iff ALL:
  pcGeneration == expectedPcGeneration
  signalingState == HAVE_LOCAL_OFFER
  localDescription.type == OFFER
  remoteDescription == NONE
  answer.offerLineageId == hostLocalOfferLineageId
  answer.iceUfrag/pwd consistent with parsed answer SDP
  answer.iceUfrag/pwd != local offer ice (role sanity)
  no superseded SRD task for same (edgeKey, offerGeneration)
  srdTaskId == latest admitted task for (edgeKey, offerGeneration)
```

On violation: **reject before native** with `SRD_ADMISSION_REJECT reason=…` (log-only first; narrow drop second).

**Explicitly not:** catch native crash, sleep/retry, M02 special case skip.

### Unit fixtures (authorized)

| Fixture | Scenario | Expected |
|---------|----------|----------|
| F1 | Valid answer matching outstanding offer gen | ADMIT |
| F2 | Duplicate answer same generation | REJECT `DUPLICATE_ANSWER` |
| F3 | Answer for prior offer generation after re-offer | REJECT `STALE_ANSWER_GENERATION` |
| F4 | Answer lineage id MATCH but ice-ufrag mismatch | REJECT `SDP_CREDENTIAL_MISMATCH` |
| F5 | SRD task superseded but still dispatched | REJECT `SUPERSEDED_TASK` |
| F6 | ICE candidate queued during active SRD on sibling PC | snapshot flags ingress; fence test |

---

## Implementation phases (authorized scope)

| Phase | Work | Product change |
|-------|------|----------------|
| **2b-1** | `SRD_PRE_NATIVE_SNAPSHOT` logging only | Observability only |
| **2b-2** | Unit fixtures F1–F6 | Test-only + admission helper |
| **2b-3** | Field rerun with snapshot diff | None if logs sufficient |
| **2b-4** | Narrow admission reject (only if 2b-3 proves invariant violation) | SRD lineage / admission only |

---

## NOT AUTHORIZED (Step 2b)

```text
- Additional global mutex / sleep / retry SRD
- Catch or mask native SIGABRT
- M02-specific skip answer
- Topology / Profile01 / ICE policy redesign
- WebRTC replacement
- Cutover / shadow / runtime changes
- Step 3 WAIT_HOST_ICE work
```

---

## Field rerun gate (after 2b-1 + 2b-2)

Re-run **one** G2-RCA2 field reverify only when:

1. `SRD_PRE_NATIVE_SNAPSHOT` present in build.
2. Unit fixtures F1–F6 PASS.
3. Snapshot diff checklist attached to run card.

**Pass criteria unchanged:**

```text
M02 ACCEPT_DISPATCHED
→ M01 edge=M02 SRD_NATIVE_CALL_ENTER
→ SRD_NATIVE_CALL_EXIT + NATIVE_DOMAIN_EXECUTION_EXIT
→ M01 PID stable + crash buffer empty
→ cutover chain
```

---

## Immediate next actions

1. Implement `SRD_PRE_NATIVE_SNAPSHOT` at `RealWebRtcAudioEngine` / conference apply boundary.
2. Add admission helper + fixtures (no field behavior change yet).
3. Re-read `100610` with snapshot template applied retroactively (manual diff in this doc).
4. If H1 confirmed (ICE during SRD), scope fix to **ICE apply fence vs SRD native window**, not new global lock.

---

## References

- Step 2 field repro: `talkback/logs/ga-g2-real-mesh-field-20260911-100610/G2_RCA2_FIELD_VERDICT.txt`
- Harness abort (non-product): `talkback/logs/ga-g2-real-mesh-field-20260911-095524/G2_RESULT.txt`
- Prior native SRD RCA pattern: [primary-native-srd-fault-rca-20260825.md](./primary-native-srd-fault-rca-20260825.md)
- Conference SRD admission track: [conference-srd-post-admission-native-execution-failure-rca.md](./conference-srd-post-admission-native-execution-failure-rca.md)
