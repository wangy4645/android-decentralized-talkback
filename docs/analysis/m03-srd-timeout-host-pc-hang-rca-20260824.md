# M03 SRD_TIMEOUT Host PC Hang RCA

**ID:** m03-srd-timeout-host-pc-hang-rca-20260824  
**Date:** 2026-08-25  
**Version:** v0.1  
**Type:** RCA · Field Evidence Freeze  
**Status:** **EVIDENCE FROZEN** · Implementation **NOT AUTHORIZED**

**Session:** `e55028df-a68f-4bec-81a9-42a3b73be8e1`  
**Channel:** `CH-01`  
**Logs:** `talkback/logs/group-conference-transition-field-20260824-174918/`  
**Devices:** M01 `HTUBB21B09220661` (host) · M03 `MDX0220416001963` · M04 `DSJ-2407011` · M02 `2d73067a` (no peer log; host attribution sufficient)

**Downstream (separate gates):**

```text
1. This RCA (evidence freeze)
        ↓
2. Edge → Native Domain Failure Propagation Contract
        ↓
3. Domain lease terminal semantics (NOT this document)
        ↓
4. Implementation Authorization
```

---

## Status

```text
FROZEN:
  GCT accept async field = PASS
  Case A = M03 host edge-local native SRD hang
  Case B = lease retention → M02 DOMAIN_BLOCKED
  attachedPcCount=3 is NOT root cause
  M04 completed before M03 hang (no global sync failure)

NOT THIS DOCUMENT:
  GCT START/ACCEPT implementation
  SRD timeout / retry / Factory / new gate / new barrier
  force-release of domain lease
  GCT host PC hard reset vs reuse gen-N PC
  M02 peer log as RCA prerequisite
```

---

## Frozen facts

```text
1. GCT accept async = PASS
2. M03 failure = edge-local native SRD hang
3. attachedPcCount=3 is NOT the root cause
4. M03 peer fresh gen=1 succeeded; host stale/churned gen=73 failed
5. shared-factory lease was NOT released on SRD_TIMEOUT
6. lease retention propagated Edge failure into Domain contention
7. M04 had already completed SRD; this was not a global synchronous failure
```

Facts 5 and 6 are the only inputs to the next contract:

```text
EDGE_FAILED
    ↓
lease retained
    ↓
DOMAIN_BLOCKED
```

---

## RCA questions (two layers)

### Case A — cause (edge-local)

> Why did host M01 `applyRemoteAnswer` on the M03 edge hang inside native `setRemoteDescription` and terminalize as `SRD_TIMEOUT`?

### Case B — blast radius (domain)

> Why did that edge-local hang still block M02 native admission with `NATIVE_DOMAIN_BUSY holder=M03`?

These are **not** the same question. Case A is the cause. Case B is the observed propagation.

---

## GCT layer (closed — do not re-litigate)

M03 peer accept (M03-talkback.log):

```text
USER_ACCEPT
    → SESSION_CREATED
    → MEDIA_RELEASE_DEFERRED_CREATE(CONFERENCE)
    → MEDIA_PROVISIONED (~12ms)
    → applyRemoteOffer
    → GROUP_ACCEPT SUCCESS
    → ICE M01 CONNECTED (~250ms)
```

Host START / peer ACCEPT / engine PENDING / no sync provision throw: **PASS**.

This RCA starts **after** GCT admission. Failure is host media realization / native SRD, not GROUP_ACCEPT scheduling.

---

## Case A — M01 host M03 edge timeline

Authoritative host log: `M01-talkback.log`.

```text
17:49:45.901  GROUP_ACCEPT from M03
17:49:45.908  CONFERENCE_MEDIA_EDGE_SRD_APPLYING peer=M03
17:49:45.915  SRD_DISPATCH  pcGeneration=73  executor=tb-edge-...|M03  tid=7378
17:49:45.918  SRD_EXECUTOR_ENTER  queueWaitMs=2
17:49:45.918  NATIVE_DOMAIN_LEASE_REQUEST  domainId=shared-factory
17:49:45.919  NATIVE_DOMAIN_LEASE_GRANTED
17:49:45.919  NATIVE_DOMAIN_EXECUTION_ENTER  pcHash=84622929
17:49:45.925  SRD_ENTER type=ANSWER  signalingState=HAVE_LOCAL_OFFER
              attachedPcCount=3  factoryHash=225170913
17:49:45.930  SRD_MUTEX_ACQUIRED  waitMs=0
17:49:45.931  SRD_NATIVE_CALL_ENTER
17:49:45.933  WEBRTC_NEGOTIATION signalingState=STABLE
              ← PC observer; NOT SRD_CALLBACK_ENTER
              ── missing for remainder of capture ──
              SRD_NATIVE_CALL_EXIT
              SRD_JNI_RETURN
              SRD_CALLBACK_ENTER / SRD_EXIT
              NATIVE_DOMAIN_EXECUTION_EXIT
17:49:48.921  SRD_WATCHDOG_GAP
              lastEvent=SRD_NATIVE_CALL_ENTER
              missing=NATIVE_CALL_EXIT
              state=NATIVE_DOMAIN_EXECUTION_ACTIVE
              holderEdge=e55028df-…|M03
17:49:48.921  CONFERENCE_MEDIA_EDGE_FAILED peer=M03 reason=SRD_TIMEOUT
17:49:48.956  CONFERENCE_FAILURE_CHAIN_VALIDATED scenario=E cause=M03
              L1 terminal=EDGE_FAILED  blockedByDomain=false
```

**Watchdog meaning (code):** `missing=NATIVE_CALL_EXIT` means the JNI call itself did not return. It is not “callback late after JNI return.”

**Edge task:** M03 `taskId=79` `SRD_APPLY` has `EDGE_TASK_START` and **no** `EDGE_TASK_END`. The per-edge executor thread remained inside native.

**A0.7 reminder (do not collapse):**

```text
watchdog terminalization
    ≠
native execution actually stopped
```

`SRD_TIMEOUT` / `EDGE_FAILED` is a **control-plane terminal**. Native `setRemoteDescription` may still be inside JNI.

---

## Three-edge comparison (same host, same factory)

| Edge | SRD native enter | pcGeneration | Native return | Callback | Domain lease | Terminal |
|------|------------------|--------------|---------------|----------|--------------|----------|
| M04 | 17:49:42.083 | 9 | EXIT 49ms | signaling_thread +47ms | EXECUTION_EXIT @42.146 | ICE CONNECTED |
| M03 | 17:49:45.931 | **73** | **no EXIT** | **none** | **no EXIT** | SRD_TIMEOUT / EDGE_FAILED |
| M02 | 17:49:49.052 | 578 | never entered native | — | LEASE_BUSY holder=M03 | DOMAIN_BLOCKED |

Shared at SRD time:

```text
domainId=shared-factory
factoryHash=225170913
attachedPcCount=3
```

M04 succeeded with the same `attachedPcCount=3` and the same factory. Count is **not** the cause of M03 hang.

---

## Asymmetry: peer success vs host hang

| Side | Operation | pcGeneration | Result |
|------|-----------|--------------|--------|
| M03 device | `applyRemoteOffer` (answerer) | **1** (fresh) | ~150ms complete; ICE CONNECTED @45.883 |
| M01 host | `applyRemoteAnswer` (offerer) | **73** | native hang → SRD_TIMEOUT |

Peer media to M01 connected. Failure is **host offerer PC** applying answer, not peer accept.

Host M03 provision churn (context only — **not** a PC-lifecycle decision):

```text
17:49:38.389  GROUP mediaSessionClose
17:49:38.510  MEDIA_RELEASE_DEFERRED_CREATE scope=CONFERENCE
17:49:38.571  GROUP provision  gen=71
17:49:38.620  CONFERENCE provision gen=72
17:49:38.621  releaseInFlight provision gen=73  ← SRD target PC
```

**Out of this RCA:** whether GCT must hard-reset host PC vs reuse gen-N. That is PC lifecycle / native recovery capability, not this evidence freeze.

---

## Case B — lease retention → domain contention

```text
17:49:48.921  M03 SRD_TIMEOUT (scenario=E, EDGE_FAILED)
              lease still held (no NATIVE_DOMAIN_EXECUTION_EXIT)
17:49:49.034  M02 GROUP_ACCEPT
17:49:49.052  M02 NATIVE_DOMAIN_LEASE_REQUEST  domainId=shared-factory
17:49:49.054  M02 NATIVE_DOMAIN_LEASE_BUSY  holderEdgeKey=…|M03
17:49:49.054  M02 CONFERENCE_MEDIA_EDGE_FAILED
              reason=EDGE_LOCAL_FAILURE
              detail=NATIVE_DOMAIN_BUSY holder=…|M03
17:49:49.062  CONFERENCE_FAILURE_CHAIN_VALIDATED scenario=D
              causeEdgeKey=M03  impactEdgeKey=M02
              L2 terminal=DOMAIN_BLOCKED
              causeFact=LEASE_HELD_BY_CAUSE
```

M02 peer log is **corroboration, not a prerequisite**. Host already has holder attribution.

**M04 blast radius:** none. M04 SRD finished 3.8s before M03 `SRD_NATIVE_CALL_ENTER`. During the hang, M04 only ran observation-lane tasks.

---

## Causal graph (frozen)

```text
Case A (cause):
  M03 host PC gen=73
      ↓
  setRemoteDescription JNI never returns
      ↓
  missing=NATIVE_CALL_EXIT
      ↓
  EDGE_FAILED / SRD_TIMEOUT   (scenario=E)

Case B (observed propagation):
  M03 lease not released
      ↓
  shared-factory lease held
      ↓
  M02 NATIVE_DOMAIN_BUSY holder=M03
      ↓
  DOMAIN_BLOCKED              (scenario=D)
```

---

## Root-cause ranking (Case A)

| Rank | Hypothesis | Status |
|------|------------|--------|
| 1 | Host M03 PC (gen 73) native `setRemoteDescription` blocked / never returned | **CONFIRMED** (watchdog + no JNI_RETURN + no EDGE_TASK_END) |
| 2 | GCT provision churn / stale PC vs peer fresh gen=1 | **SUSPECT** (asymmetric gens). Not decided here. |
| 3 | Concurrent SRD from M02/M04 caused M03 hang | **REJECTED** (M04 already EXIT; M02 had not entered native) |
| 4 | `attachedPcCount=3` as root cause | **REJECTED** (M04 same count succeeded) |

Case B does **not** invent a second native hang on M02. M02 never entered native. It was denied lease while M03 still held `shared-factory`.

---

## What this RCA does **not** decide

```text
SRD_TIMEOUT should / should not force-release domain lease
Lease state NONE is / is not a safe admission for the next edge
Native kill / interrupt / second owner on same factory
PC hard reset after GCT
Timeout budget / retry
```

Observed lease behavior after timeout:

```text
control terminal:  EDGE_FAILED
lease observation: RETAINED (holder still M03 at M02 LEASE_BUSY)
native execution:  UNKNOWN / still inside JNI (no EXIT)
```

Do not promote this observation to architecture until the propagation contract separates:

```text
Lease state
    ≠
Release transaction state
```

---

## Evidence pointers

| Claim | Where |
|-------|--------|
| M03 SRD enter / no exit | `M01-talkback.log` 17:49:45.931 → 17:49:48.921 |
| scenario=E | `CONFERENCE_FAILURE_CHAIN_VALIDATED scenario=E` 17:49:48.956 |
| M02 LEASE_BUSY holder=M03 | 17:49:49.054 |
| scenario=D | `cause=M03 impact=M02` 17:49:49.062 |
| M04 success same factory | 17:49:42.083–42.146 |
| M03 peer ICE CONNECTED | `M03-talkback.log` 17:49:45.883 |
| GCT accept async PASS | M03 17:49:45 accept chain; no sync provision throw |

---

## Next document

**Sole evidence source for:** [Edge → Native Domain Failure Propagation Contract](./edge-native-domain-failure-propagation-contract.md)

That contract answers when/how/with what attribution an edge-local native failure may become domain contention. It does **not** re-open GCT. It does **not** authorize lease force-release.
