# GCT IA-001 Clean Start — Adjudication

**ID:** gct-ia001-clean-start-adjudication-20260825  
**Date:** 2026-08-25  
**Type:** Adjudication / Evidence Freeze  
**Status:** **ADJUDICATED** · **FROZEN**  
**Implementation:** **NOT AUTHORIZED**

**Session:** `3fe43938-67ff-4192-bd71-10af55723b38` · CH-01  
**Host:** M01 `HTUBB21B09220661`  
**Peers:** M02 `DSJ-2407041` · M03 `MDX0220416001963` · M04 `DSJ-2407011` (control only; no USB log)  
**Logs:** `talkback/logs/gct-ia001-clean-start-20260825-102652/`  
**Build:** IA-001 observability (`cac628d`) · `pm clear` on M01/M02/M03 before capture

**Parent chain:**

- [m03-host-pc-generation-ownership-lineage-rca-20260824.md](./m03-host-pc-generation-ownership-lineage-rca-20260824.md) — CLOSED · A vs B INCONCLUSIVE
- [gct-m03-lifecycle-observation-ia-001.md](./gct-m03-lifecycle-observation-ia-001.md) — field COMPLETE
- Prior field (dirty baseline): `talkback/logs/gct-ia001-lifecycle-obs-20260825-101530/`

---

## Status

```text
ADJUDICATED / FROZEN

Primary defect reframed:
  Shared Native SRD Fault
  (shared-factory + sequential SRD → nondeterministic native setRemoteDescription non-return)

NOT AUTHORIZED:
  native SRD recovery / containment
  PC reset / force-release / Factory partition
  lifecycle de-dup / pending drain fix / duplicate-offer suppression
```

---

## Frozen result

```text
Clean Start
    ↓
offer ↔ answer MATCH (all host SRD edges)
    ↓
gen / pcHash consistent and correlatable
    ↓
M03 SRD PASS (49ms)
    ↓
M02 SRD HANG (ENTER, no EXIT)
    ↓
M02 EDGE_FAILED · native domain lease retained
    ↓
M04 NATIVE_DOMAIN_BUSY / DOMAIN_BLOCKED
```

Cross-run corroboration (Round 1 dirty + Round 2 clean):

| Run | 1st host SRD | 2nd host SRD | 3rd edge |
|-----|--------------|--------------|----------|
| Round 1 (`314a316e…`) | M02 **PASS** 69ms | M03 **HANG** → TIMEOUT | M04 DOMAIN_BLOCKED |
| Round 2 (`3fe43938…`) | M03 **PASS** 49ms | M02 **HANG** → TIMEOUT | M04 DOMAIN_BLOCKED |

**Both runs:** first sequential `shared-factory` SRD succeeds; **second** hangs. Failure edge tracks accept / apply order, not a fixed device identity.

---

## Primary defect (frozen wording)

**Do not write:** “M03 SRD hang” · “M03 stale PC” · “participant X always fails” as primary defect.

**Write:**

```text
Shared Native SRD Fault

shared-factory
    + sequential host SRD (setRemoteDescription answer)
    → one edge's native call may never return (JNI non-return)

failure edge     = affected participant (varies with SRD order)
blast radius     = lease-contending edges on same shared-factory domain
```

Prior framing (“M03 stale PC / lineage / lifecycle corruption”) is **superseded** for primary-defect purposes. Lineage and lifecycle anomalies remain **separate tracks**.

---

## Seven frozen conclusions

### 1. M03 is not the unique failure edge

Clean start: M03 host SRD **PASS**. Round 1 (dirty): M03 **HANG**. Same build, same topology, same lineage contract — different failure edge.

### 2. Failure edge varies with sequential SRD order

Observed accept / apply order determines which participant becomes `failure edge`. In both captured sessions, the **second** host SRD on `shared-factory` hung; the first always completed.

### 3. Lineage MATCH on all adjudicated SRD paths

Round 2 host apply:

```text
M03: APPLY_REMOTE_ANSWER lineageCorrelation=MATCH  offerLineageId=CR4  pcGen=4  pcHash=116723838
M02: APPLY_REMOTE_ANSWER lineageCorrelation=MATCH  offerLineageId=CR2  pcGen=4  pcHash=5820985
```

Peer M03: `PEER_SELECTED_OFFER` CR4 on `pcGeneration=1`. No SDP lineage mismatch on failing or succeeding edges.

### 4. generation / pcHash normally correlatable (IA-001 P1/P2 met)

Conference provision (Round 2, per peer on host):

```text
MEDIA_PENDING_DRAIN count=2
  items=CONFERENCE:requestEngine, CONFERENCE:releaseInFlight

MEDIA_PROVISIONED requestEngine      gen=3  factoryHit=NEW
MEDIA_PROVISIONED releaseInFlight    gen=4  factoryHit=REUSE   (same pcHash)
```

SRD uses final generation (`pcGeneration=4` for M02/M03). `factoryHash=131771833` shared across edges in session.

### 5. shared-factory is the common execution condition

All host SRD paths:

```text
NATIVE_DOMAIN_EXECUTION_ENTER domainId=shared-factory
SRD_NATIVE_CALL_ENTER type=ANSWER
attachedPcCount=3
trackId=tb_shared_audio
```

Hang states: `state=NATIVE_DOMAIN_EXECUTION_ACTIVE` · `holderEdge=<failure edge>`.

### 6. M02 hang → M04 DOMAIN_BLOCKED — field-verified blast radius

Round 2 sequence:

```text
10:30:54.551  SRD_NATIVE_CALL_ENTER  edge=M02
10:30:57.555  SRD_WATCHDOG_GAP       holderEdge=…|M02  missing=NATIVE_CALL_EXIT
10:30:57.555  CONFERENCE_MEDIA_EDGE_FAILED peer=M02 reason=SRD_TIMEOUT
10:30:57.570  CONFERENCE_FAILURE_L1_CLASSIFIED terminal=EDGE_FAILED
10:30:58.102  CONFERENCE_MEDIA_EDGE_FAILED peer=M04 reason=EDGE_LOCAL_FAILURE
              detail=NATIVE_DOMAIN_BUSY holder=…|M02
10:33:00.577  REACHABILITY_PROBE M04 displayState=VISIBLE_DOMAIN_BLOCKED
```

Native domain lease **retained on failure edge** after watchdog timeout. Contending edges fail without entering native SRD.

Round 1 exhibited the same propagation pattern with M03 as holder and M04 blocked.

### 7. Double createOffer / pending double-drain — confirmed lifecycle anomaly, not native hang cause

Still present in clean start:

```text
per peer: createOffer ×2 (CR1/CR2, CR3/CR4, CR5/CR6)
pending:  requestEngine + releaseInFlight → gen++ twice
```

Present on **both** PASS and HANG edges with MATCH lineage. **Do not** write `pending drain → native hang` or `duplicate offer → native hang`.

Separate future track: lifecycle de-dup IA (not authorized here).

---

## Evidence chain (Round 2, host M01)

### Conference entry

```text
10:30:47.737  MEDIA_PENDING_DRAIN M02/M03/M04  (CONFERENCE ×2 each)
10:30:47–48   createOffer ×2 per peer · GROUP_INVITE with offerLineageId
10:30:50.396  M03 USER_ACCEPT (M03 log)
10:30:51.034  host APPLY_REMOTE_ANSWER M03  MATCH
10:30:51.067  SRD_NATIVE_CALL_ENTER  M03
10:30:51.116  SRD_NATIVE_CALL_EXIT   M03  elapsedMs=49  result=SUCCESS
10:30:51.129  CONFERENCE_MEDIA_EDGE_CONNECTING M03
10:30:54.514  host APPLY_REMOTE_ANSWER M02  MATCH
10:30:54.551  SRD_NATIVE_CALL_ENTER  M02
              (no SRD_NATIVE_CALL_EXIT)
10:30:57.555  SRD_TIMEOUT / EDGE_FAILED M02
10:30:58.102  M04 NATIVE_DOMAIN_BUSY
```

### UI vs media (M03 participant)

M03 admission / conference UI can show “in meeting” while other edges are still connecting or blocked. **Admission ≠ all host SRD complete.**

End state (host projection @10:33:00):

```text
M03 media=CONNECTED   displayState=VISIBLE_CONNECTED
M02 media=CONNECTING  displayState=VISIBLE_EDGE_FAILED
M04 media=CONNECTING  displayState=VISIBLE_DOMAIN_BLOCKED
Meeting pill: connected=2 joined=4
```

---

## Superseded / rejected hypotheses

| Hypothesis | Verdict |
|------------|---------|
| M03-specific stale / corrupt PC | **REJECTED** as primary (M03 PASS on clean start) |
| SDP offer ↔ answer lineage mismatch | **REJECTED** (MATCH all edges, both runs) |
| Dirty-state ownership pollution required for hang | **REJECTED** (hang on 2nd SRD in clean session) |
| High Java generation alone causes hang | **REJECTED** (clean gen=4 still hangs on 2nd edge) |
| pending drain / double offer sufficient cause | **REJECTED** (same shape on PASS and HANG edges) |

Prior RCA A vs B remains **INCONCLUSIVE** at PC-object level; clean-start adjudication **does not** reopen “stale PC vs lifecycle race” as the primary label. Primary label is now **Shared Native SRD Fault** on `shared-factory`.

---

## Out of scope (remain frozen)

```text
lifecycle de-dup
双 createOffer suppression
pending drain semantics change
PC reset / force-release
Factory partition
SRD timeout / retry / lease semantic change
WiFi recovery four protected domains
```

Round 2 also observed **pre-conference** M04 GROUP `MEDIA_PENDING_DRAIN` churn (gen 258→413+). That is a **separate** GROUP-lifecycle anomaly; not merged into this primary defect.

---

## Architecture decision (resolved)

Minimal production capability adjudicated in [native-srd-recovery-decision-review-20260825.md](./native-srd-recovery-decision-review-20260825.md):

```text
PRIMARY:     B — Edge SRD Recovery (B1 intent + B2 safe re-realization)
PROTECTION:  D — Domain quarantine (adjunct)
ESCAPE:      C — Native domain reset (deferred)
TERMINAL:    A — Permanent DOMAIN_BLOCKED (failure state only)
```

**Recovery IA still NOT AUTHORIZED** until Edge SRD Recovery IA is drafted from this decision.

**Update:** B1 authorized — [edge-srd-recovery-ia-v0.1-b1.md](./edge-srd-recovery-ia-v0.1-b1.md). B2 still NOT AUTHORIZED.

---

## Next step (authorized path)

```text
This adjudication FROZEN
        ↓
[native-srd-recovery-decision-review-20260825.md] DECIDED
        ↓
[edge-srd-recovery-ia-v0.1-b1.md] B1 AUTHORIZED
        ↓
impl · desk · field (run card)
        ↓
B2 IA (NOT STARTED)
```

---

## Frozen wording (grep / docs)

```text
Shared Native SRD Fault
failure edge = affected participant (order-dependent)
blast radius = lease-contending edges
lineage MATCH ≠ hang immunity
provision churn / duplicate offer = confirmed lifecycle anomaly ≠ native hang cause
Do NOT write: M03 SRD hang (primary) · stale PC confirmed · pending drain → native hang
```
