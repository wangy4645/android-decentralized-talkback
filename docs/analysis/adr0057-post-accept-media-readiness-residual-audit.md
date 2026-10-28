# ADR-0057 Track C — Post-Accept Media Readiness Residual Audit

## Status

**READ-ONLY AUDIT COMPLETE** (2026-08-31)

```text
Classification:  B — ICE / MEDIA EXECUTION GAP

F7:                Post-Accept PC / ICE Lineage Desynchronization
                   CONFIRMED IMPLEMENTATION GAP
                   (seam audit: adr0057-post-accept-pc-ice-lineage-seam-audit.md)

NOT:
  A — Signaling completion gap
  C — Readiness projection gap
```

**Evidence:** `talkback/logs/adr0057-track-c-case-a-20260831-173143/`  
**Primary edge:** M03 ↔ M02  
**Control edge:** M03 ↔ M01 (PASS)

**Principle frozen:**

```text
Signal completion ≠ media readiness
```

Control/bootstrap through `GROUP_ACCEPT` is **FIELD PASS** on this run.  
`transmitReady=false` is **not** an invite/bootstrap residual.

---

## Audit scope

Read-only. No implementation authorized.

```text
GROUP_ACCEPT completed
→ offer/answer applied on both peers
→ admitted media edge exists
→ ICE candidates generated / signaled / applied
→ PeerConnection ICE state progresses
→ edge usability projected
→ transmitReady
```

**Forbidden this round:** ICE restart, extra retry, force CONNECTED/readiness, generation change, GIE/IGIE/GIDR reopen, membership changes.

---

## Preconditions (173143)

```text
M03 @ post-accept:
  membershipReady=true
  waitingForPrimary=false
  joinedMembers=M01,M02,M03
  GROUP_INVITE received (M01 + M02)
  GROUP_ACCEPT sent (M01 + M02)
  transmitReady=false
  peerIceStates: M01=CONNECTED; M02≠CONNECTED
```

Clean post-accept observation window. M04 absent.

---

## Q1 — SDP bilateral-complete?

### M03 → M02 (answerer)

```text
17:32:52.550  SRD OFFER  (pcGeneration=1) signalingState STABLE→HAVE_REMOTE_OFFER
17:32:52.562  SLD ANSWER phase=BEFORE
17:32:52.788  SLD ANSWER phase=AFTER  localDesc=ANSWER remoteDesc=OFFER
17:32:52.788  SETTLING ANSWERER_SETTLED applyRemoteOfferComplete
17:32:52.807  IGIE_GROUP_ACCEPT_WIRE_SENT → M02
```

M03 side: **SDP apply complete** on fresh `pcGeneration=1`.

### M02 → M03 (offerer)

```text
17:32:46.608  GIE_GROUP_INVITE_WIRE_SENT (offer pcGeneration=4)
17:32:58.566  GROUP_ACCEPT received from M03 (+6s after M03 handoff)
17:32:58.593  SRD ANSWER phase=BEFORE  signalingState=HAVE_LOCAL_OFFER
17:32:58.637  SRD ANSWER phase=AFTER   localDesc=OFFER remoteDesc=ANSWER signalingState=STABLE
17:32:58.642  GROUP_ACCEPT_EXEC EXIT reason=GROUP_SYNC_SDP
```

M02 side: **SDP eventually STABLE** — but on `pcGeneration=5`, not the generation that sent the invite (`pcGeneration=4`).

### Verdict Q1

**Partial PASS with lineage caveat.**

Both peers reach `signalingState=STABLE` with offer+answer descriptors present.  
However the M02 offer that M03 answered (received ~6s late) was produced on **pcGeneration=4**, while M02's eventual answer application landed on **pcGeneration=5** after additional GIDR-driven engine churn. SDP completion is **not proven to be pairwise-consistent across the same PC lineage**.

`GROUP_ACCEPT sent` alone would overstate completion; logs show a **6s accept delivery skew** (M03 handoff 17:32:52.807 → M02 ingress 17:32:58.566).

---

## Q2 — ICE candidate lifecycle complete?

### M03 ← M02 (inbound to M03)

```text
17:32:52.220  WEBRTC_ICE received from M02 (pre-accept, with INVITE)
17:32:52.252  MEDIA_SIGNAL_CANDIDATE_RECEIVED queued=true (×2)
17:32:52.550  SRD OFFER on pcGeneration=1
17:32:52.791  MEDIA_ICE_CANDIDATE_APPLIED remote=M02 pcGeneration=1 applied=true (×2)
17:32:52.857  MEDIA_ICE_CANDIDATE_GENERATED remote=M02 pcGeneration=1 (local)
17:32:52.859  MEDIA_SIGNAL_CANDIDATE_SENT remote=M02 pcGeneration=1
```

Pre-accept candidates **were drained** after SRD on M03 — not lost at queue layer.

**But:** M02 had emitted candidates on `pcGeneration=1..4` during GIDR retries **before** M03 existed. The candidates M03 applied were tagged `pcGeneration=1` on a **new** engine, while M02's wire candidates at invite time were `pcGeneration=4`.

### M02 ← M03 (inbound to M02)

```text
17:32:46.641  MEDIA_SIGNAL_CANDIDATE_SENT remote=M03 pcGeneration=4 (never received by M03)
17:32:58.582  WEBRTC_ICE received from M03 (with delayed GROUP_ACCEPT)
17:32:58.688  MEDIA_ICE_CANDIDATE_APPLIED remote=M03 pcGeneration=5 applied=true
```

M02 eventually applies M03 candidates on **gen=5**, not gen=4.

### ICE state outcome

| Edge | M03 | M02 |
|------|-----|-----|
| M03↔M01 (control) | ICE_CONNECTED @ 17:32:53.231 | ICE_CONNECTED @ 17:32:33.881 |
| M03↔M02 (primary) | ICE_ENTER_CHECKING @ 17:32:52.851; **never CONNECTED** | ICE_ENTER_CHECKING @ 17:32:58.719; **never CONNECTED** |

M03 `GROUP_MEDIA_EDGE_HEALTH` for M02: `iceState=CHECKING` through observation end (~4.5 min), `RECOVERY_SUPPRESSED` (L0).

### Verdict Q2

**FAIL — lifecycle present but generation-misaligned.**

Candidate generate → signal → receive → apply **occurs**, but across **mismatched pcGeneration** values on the M02↔M03 edge. ICE never progresses past CHECKING.

---

## Q3 — Engine / lineage mismatch?

### M02 offer-side churn (GIDR retry window)

```text
17:32:43.332  GIE wire attempt #1 → pcGeneration=1
17:32:45.045  signalingState=CLOSED (pcGeneration=1 dead)
17:32:45.093  GIE wire attempt #2 → pcGeneration=2
17:32:45.572  CLOSED
17:32:45.642  GIE wire attempt #3 → pcGeneration=3
17:32:46.501  CLOSED
17:32:46.608  GIE wire attempt #4 → pcGeneration=4  ← INVITE M03 finally receives
```

### Accept-time lineage fork

```text
M03 accept path:
  fresh engine → pcGeneration=1 (sessionTraceId=b97256fd)
  answers offer received from M02 gen=4 wire

M02 accept path (GROUP_ACCEPT from M03):
  applyRemoteAnswer → pcGeneration=5 (sessionTraceId=752307f2)
  offer on wire was gen=4; answer applied on gen=5
```

### Pattern observed

```text
SDP applied / candidates generated on PC-A (gen=N)
ICE candidates later applied / answer processed on PC-B (gen=N+k)
```

This matches the suspected failure mode: signaling **appears** complete while media execution operates on **desynchronized PC instances**.

### Verdict Q3

**CONFIRMED — primary suspect.**

M01 edge (stable gen=1 both sides, timely accept) converges.  
M02 edge (multi-gen churn during GIDR + delayed accept) does not.

---

## Q4 — Projection gap vs native ICE failure?

### Native ICE

```text
M03: zero ICE_CONNECTED for M02 (entire run)
M02: zero ICE_CONNECTED for M03 (entire run)
M03: ICE_CONNECTED for M01 (control) — repeated, stable
```

### Readiness projection

```text
M03 @ 17:33:02:
  transmitReady=false
  transmitConnectedPeers=M01
  peerIceStates=M01=CONNECTED;M02=CHECKING

M02 @ 17:32:58:
  transmitReady=true (only M01 required)
  peerIceStates=M01=CONNECTED;M03=UNKNOWN
  meshIceConnectedPeers=M01 only
```

Projection **correctly** reflects missing native ICE on M02↔M03. No evidence that `ActualMediaEdgeSet` / `transmitReady` is stale-positive or stale-negative relative to PC state.

### Verdict Q4

**Not C.** Failure is upstream in ICE execution, not readiness projection.

---

## Control edge — M03 ↔ M01

For contrast (same run, same M03 session):

```text
17:32:52.816  M03 IGIE accept M01 (Reconnect path)
17:32:52.834  SLD ANSWER → STABLE pcGeneration=1
17:32:52.836  ICE candidates applied pcGeneration=1
17:32:53.231  ICE_CONNECTED M01
```

M01 path: single generation, timely SDP, ICE CONNECTED.  
Proves M03 media stack **can** converge post-accept when lineage is stable.

---

## Classification

| Class | Result |
|-------|--------|
| **A. Signaling completion gap** | NO — GROUP_ACCEPT + SRD/SLD eventually complete both sides |
| **B. ICE / media execution gap** | **YES** — ICE stuck CHECKING; pcGeneration mismatch |
| **C. Readiness projection gap** | NO — projection matches native ICE |

### F7 — CONFIRMED

```text
F7 — Post-Accept PC / ICE Lineage Desynchronization
Classification: CONFIRMED IMPLEMENTATION GAP

Invariant violated:
  answer must apply to the same PC lineage that originated the offer

Seam audit:
  adr0057-post-accept-pc-ice-lineage-seam-audit.md
```

---

## Causal chain (M03↔M02)

```text
GIDR retries on M02→M03
→ multiple GROUP_INVITE wire attempts
→ pcGeneration 1..4 create/close churn
→ INVITE #4 + ICE(gen=4) emitted while M03 still cold-start

M03 finally receives INVITE + pre-accept ICE
→ fresh IGIE engine pcGeneration=1
→ SRD/SLD complete; GROUP_ACCEPT sent

M02 receives GROUP_ACCEPT +6s late
→ applyRemoteAnswer on pcGeneration=5 (not gen=4 offer PC)
→ ICE candidates cross generations
→ both sides CHECKING indefinitely
→ transmitReady=false (M03)
```

---

## Implementation authorization

```text
NOT AUTHORIZED

Seam audit complete:
  adr0057-post-accept-pc-ice-lineage-seam-audit.md

GPLB contract (draft, not authorized):
  adr0057-group-pc-lineage-binding-narrow-authorization.md
```

Do **not** authorize ICE restart as first fix.

---

## Formal status contribution

```text
GROUP control/bootstrap through GROUP_ACCEPT:  FIELD PASS
Post-accept media readiness:                 FAIL / OPEN
Case A E2E:                                  PARTIAL / OPEN
ADR-0057 COMPLETE:                           NO

Residual:
  F7 Post-Accept PC/ICE Lineage Desynchronization (CONFIRMED)
```
