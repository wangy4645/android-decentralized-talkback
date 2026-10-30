# Conference SRD Post-Admission Native Execution Failure

**ID:** conference-srd-post-admission-native-execution-failure-rca  
**Date:** 2026-08-21  
**Version:** v0.2 (RCA-B **CLOSED**)  
**Type:** RCA / Decision  
**Implementation:** **NOT AUTHORIZED**

**Parent track:** [conference-control-media-execution-isolation.md](./conference-control-media-execution-isolation.md)  
**Prior adjudication:** [ia002-admission-20260821-120004/ADJUDICATION.txt](../../logs/ia002-admission-20260821-120004/ADJUDICATION.txt)  
**Confirmation:** [rca-b-confirm-srd-native-20260821-133115/ADJUDICATION.txt](../../logs/rca-b-confirm-srd-native-20260821-133115/ADJUDICATION.txt) · session `5ce616de-…`

---

## Status

```text
CLOSED:
  Conference Initial Anchor semantics
  Edge executor starvation
  IA-002 MEDIA_CRITICAL admission
  RCA-OBS-001
  RCA-B native SRD non-return

PASS:
  IA-002 field validation
  RCA-B Confirmation-001

OPEN:
  Conference Native Failure Containment Decision ([decision](./conference-native-failure-containment-decision.md)) — Decision 1
  Partition architecture reassessment ([reassessment](./conference-srd-native-domain-partition-architecture-reassessment.md)) — context complete
  IA field Commit 1–3 (ALLOWABLE · P0.1g HOLD)

COMPLETE:
  D-α lab ([adj](../../logs/dalpha-lab-spike-20260821-155612/ADJUDICATION.txt)) — **FAIL**
  Model C/D spike ([spike](./conference-srd-model-cd-partition-design-spike.md)) — C rejected; D-α FAIL
  B2-2-A ([audit](./conference-srd-b2-2a-native-parallel-domain-audit.md)) — FAIL Model B

COMPLETE:
  B2 design review ([b2-review](./conference-srd-native-resource-domain-isolation-b2-design-review.md))
  B2-1 Commit 1–3 ([IA-001](./conference-srd-native-domain-isolation-implementation-authorization-001.md))
  PO-3 ([po-003](./conference-srd-native-domain-ownership-po-003.md)) — ACCEPTED
  PO-1 ([po-001](./conference-srd-native-ownership-inventory-po-001.md)) — BLOCKED
  PO-2 ([po-002](./conference-srd-native-isolation-boundary-po-002.md)) — containment FAIL
  PO-2b ([po-2b](./conference-srd-native-worker-characterization-po-2b.md)) — B → B2

HOLD:
  P0.1g Acceptance 1
    — one edge SRD native block still pollutes other edge execution (M03 → M04)
    — requires fix authorization + validation; RCA-B CLOSED does not unblock

NOT AUTHORIZED:
  Any implementation fix

RCA-B (authoritative · session 5ce616de):
  M03: SRD_MUTEX_ACQUIRED → SRD_NATIVE_CALL_ENTER → (no EXIT) → missing=NATIVE_CALL_EXIT
  M04: derived / coupled — not independent RCA
```

---

## Fact chain (accepted)

```text
Conference admission          PASS
Edge executor admission       PASS  (IA-002)
SRD_APPLY START               PASS  (M02/M03/M04)
Native WebRTC SRD execution   FAIL  (M03/M04; M02 OK)
SRD completion / timeout      FAIL  (M03/M04 MEDIA_EDGE_FAILED)
```

---

## RCA question (single)

> In the same conference generation, why do **M03/M04** fail to complete SRD **after** `SRD_APPLY START`, while **M02** succeeds on the same path?

**Not in scope:**

```text
Anchor · Admission · Executor queue · refreshAudioLevel lane
Recovery · Ready · Topology · retry · timeout budget change
```

---

## Authorized investigation boundary

Only this chain:

```text
SRD_APPLY START
        |
        v
applyRemoteAnswer / setRemoteDescription (native)
        |
        v
completion callback / native return / latch wait
        |
        v
SRD_EXIT or watchdog SRD_TIMEOUT
```

**Failure attribution split (frozen):**

| Symptom location | Domain |
|------------------|--------|
| No `EDGE_TASK_START` | Edge executor admission (CLOSED — IA-002) |
| `START` but no `SRD_ENTER` | Pre-native apply path |
| `SRD_ENTER` but no `SRD_JNI_RETURN` | Native call blocked / not returned |
| `SRD_JNI_RETURN` but no `SRD_CALLBACK_*` | Callback not delivered |
| `SRD_CALLBACK_*` but latch timeout | Java wait / ordering |
| `SRD_EXIT SUCCESS` but edge failed later | Out of this RCA |

---

## Required lifecycle chain (per edge)

Each edge must be reconstructable as:

```text
SRD_DISPATCH
SRD_EXECUTOR_ENTER
SRD_ENTER
(signaling state timeline)
SRD_MUTEX_* (if held)
SRD_JNI_RETURN
SRD_CALLBACK_ENTER / SRD_CALLBACK_FAILURE
SRD_EXIT
```

---

## Seed evidence — session `034563a9-…` (M01 log)

**Authoritative run:** `logs/ia002-admission-20260821-120004/` · IA-002 APK · Anchor M01

### M02 — SUCCESS (reference)

```text
12:03:43.422  SRD_ENTER
12:03:43.423  SRD_MUTEX_ACQUIRED
12:03:43.462  SRD_CALLBACK_ENTER  (signaling_threa tid=4681)
12:03:43.462  SRD_EXIT result=SUCCESS
12:03:43.462  SRD_MUTEX_RELEASED holdMs=39
12:03:43.463  SRD_JNI_RETURN
12:03:43.471  EDGE_TASK_END success=true
```

### M03 — TIMEOUT (primary suspect)

```text
12:03:45.716  SRD_ENTER
12:03:45.717  SRD_MUTEX_ACQUIRED
12:03:45.719  SIGNALING_STATE STABLE  (signaling_threa — 3ms after mutex)
              (no SRD_JNI_RETURN · no SRD_CALLBACK_* · no SRD_MUTEX_RELEASED)
12:03:48.712  CONFERENCE_MEDIA_EDGE_FAILED reason=SRD_TIMEOUT
```

**Hypothesis class (not decided):** native apply may have progressed (`STABLE`) but Java completion path did not close — or JNI call never returned while holding `sdpApplyMutex`.

### M04 — TIMEOUT (secondary / coupled)

```text
12:03:48.868  SRD_EXECUTOR_ENTER / EDGE_TASK_START
              (no SRD_ENTER in window before watchdog)
12:03:51.867  CONFERENCE_MEDIA_EDGE_FAILED reason=SRD_TIMEOUT
```

**Hypothesis class:** may be blocked behind M03 mutex hold or pre-`SRD_ENTER` apply path; **must not assume independent native failure until decoupled.**

---

## Lateral compare matrix (to fill)

| Item | M02 | M03 | M04 |
|------|-----|-----|-----|
| pcGeneration | 3 | 3 | 9 |
| pcHash | 92659670 | 53530907 | (no SRD_ENTER) |
| factoryHash | 247524958 | 247524958 | — |
| executor thread | tb-edge-\|M02\| | tb-edge-\|M03\| | tb-edge-\|M04\| |
| polite | (log) | false | (log) |
| signaling @ SRD_ENTER | HAVE_LOCAL_OFFER | HAVE_LOCAL_OFFER | — |
| SRD_JNI_RETURN | yes ~41ms | **no** | **no** |
| SRD_CALLBACK | SUCCESS | **absent** | **absent** |
| sdpApplyMutex released | yes | **no** | — |

---

## PeerConnection identity checks

Per edge, align:

```text
sessionId · conferenceGeneration · edgeKey · pcGeneration
pcHash · factoryHash · observerHash
executor thread · callback thread
```

Goal: exclude **SRD_ENTER PC ≠ callback PC** (native path re-verify).

---

## Explicitly not authorized (this RCA)

```text
retry SRD · SRD timeout increase · ICE restart policy change
Ready fix · Recovery · topology / Anchor change
executor rewrite · observation lane changes
sdpApplyMutex removal without new ADR
```

---

## Completion criteria (RCA close)

**Current:** ROOT CAUSE CANDIDATE FROZEN — not CLOSED.

Frozen root cause (CLOSED):

```text
RCA-B: Native SRD completion does not return while holding sdpApplyMutex

Evidence (authoritative):
  session 5ce616de · M03
  SRD_MUTEX_ACQUIRED → SRD_NATIVE_CALL_ENTER → (no SRD_NATIVE_CALL_EXIT)
  SRD_WATCHDOG_GAP missing=NATIVE_CALL_EXIT → SRD_TIMEOUT

Corroboration:
  session 88e22b07 · M03 (OBS-001: missing=JNI_RETURN — pre-confirmation)

Confidence: CLOSED
```

Confirmation matrix (session 5ce616de):

```text
ENTER without EXIT (M03)              HIT → RCA-B CLOSED
ENTER+EXIT without JNI_RETURN         not observed on fail edges
EXIT elapsed > watchdog budget        not observed on fail edges
```

Other criteria:

```text
A  callback lost after native STABLE        CLOSED — NOT PROVEN (B2 not hit)
C  pre-SRD_ENTER applyRemoteAnswer exit     CLOSED — NO (M03)
D  M04 failure from M03 mutex coupling      DERIVED — not independent RCA
```

---

## Next

**RCA phase:** closed.

1. ~~RCA-OBS-001~~ — **PASS** (`logs/rca-obs001-srd-native-20260821-131854/`)
2. ~~RCA-B confirmation~~ — **PASS** (`logs/rca-b-confirm-srd-native-20260821-133115/`) · RCA-B **CLOSED**
3. **PO-3** C on **B2** domain · [PO-2b](./conference-srd-native-worker-characterization-po-2b.md) · IA **NOT OPENED**
4. P0.1g Acceptance 1 — **HOLD** until fix authorization + field validation

Do **not** reopen IA-002 / executor admission layers.
Do **not** treat RCA-B CLOSED as P0.1g unblock.

---

## Changelog

| Date | Version | Change |
|------|---------|--------|
| 2026-08-21 | v0 | OPEN — authorized from IA-002 adjudication; seed evidence session 034563a9 |
| 2026-08-21 | v0.1 | RCA-OBS-001 PASS session 88e22b07; RCA-B candidate FROZEN (HIGH); M04 derived |
| 2026-08-21 | v0.2 | RCA-B Confirmation-001 PASS session 5ce616de; RCA-B **CLOSED**; fix NOT AUTHORIZED |
