# ADR-0057 Track C Case A — Field Adjudication

## Status (latest: GIDR field recovery run 20260831-173143)

**Generation Convergence FIELD PASS** · **F4/GIE CLOSED** · **F5/IGIE CLOSED** · **F6/GIDR FIELD RECOVERY PASS / CLOSED** · **Case A PARTIAL** (2026-08-31)

```text
F2 / M04 isolation:                    PASS
Acquisition Send-on-Locate:            FIELD CONFORMANCE PASS / CLOSED
Cold-Start Origin (CSO):               FIELD CONFORMANCE PASS / CLOSED FOR THIS DEFECT
Generation convergence (same Current): FIELD PASS
F4 Group Invite Execution (GIE):       FIELD PASS / CLOSED FOR THIS DEFECT
F5 Inbound accept (IGIE):              FIELD PASS / CLOSED FOR THIS DEFECT
F6 Group Invite Delivery Recovery:     FIELD RECOVERY PASS / CLOSED FOR THIS DEFECT
Group bootstrap through GROUP_ACCEPT:  FIELD PASS
Group bootstrap end-to-end:            PARTIAL / OPEN
Post-accept media readiness (F7 / GPLB):  ACCEPT PASS / ICE FAIL — GPLB-LC AUTHORIZED
Media readiness / transmitReady:       DOWNSTREAM RESIDUAL (not invite/bootstrap)
ADR-0057 Generation-Convergence Objective:
                                       FIELD PASS
Case A End-to-End Bootstrap:           PARTIAL / OPEN
Residual:                              post-accept media readiness / transmitReady
ADR-0057 COMPLETE:                     NO
```

**Evidence (GIDR — authoritative):** `talkback/logs/adr0057-track-c-case-a-20260831-173143/`  
**Evidence (IGIE):** `talkback/logs/adr0057-track-c-case-a-20260831-165729/`  
**PRECONDITIONS:** `talkback/logs/adr0057-preconditions-20260831-165718/`  
**Build / APK:** `cac628d` + GIDR cut · installed for 173143 run

**Prior (GIE):** `talkback/logs/adr0057-track-c-case-a-20260831-163552/` — F4 PASS; F5 inbound accept gap CONFIRMED (superseded by IGIE cut)

**GIE auth:** [Group invite execution narrow authorization](./adr0057-group-invite-execution-narrow-authorization.md)

**IGIE auth:** [Inbound group invite execution narrow authorization](./adr0057-inbound-group-invite-execution-narrow-authorization.md)

**F6 audit:** [Post-IGIE group invite delivery residual audit](./adr0057-post-igie-group-invite-delivery-residual-audit.md)

**GIDR auth:** [Group invite delivery recovery narrow authorization](./adr0057-group-invite-delivery-recovery-narrow-authorization.md)

**Post-convergence audit:** [Group bootstrap residual audit](./adr0057-post-convergence-group-bootstrap-residual-audit.md)

**Post-accept media audit:** [Post-accept media readiness residual audit](./adr0057-post-accept-media-readiness-residual-audit.md)

**F7 seam audit:** [Post-accept PC / ICE lineage seam audit](./adr0057-post-accept-pc-ice-lineage-seam-audit.md)

**F7 GPLB contract:** [Group PC lineage binding narrow authorization](./adr0057-group-pc-lineage-binding-narrow-authorization.md) — **Option A signed · parent cut delivered**

**F7a GPLB-LC:** [Binding lifecycle correction narrow authorization](./adr0057-gplb-binding-lifecycle-correction-narrow-authorization.md) — **AUTHORIZED**

---

## Frozen — generation convergence (do not re-open)

```text
M01:  CSO_ORIGIN Gx → local Current = Gx
M02/M03: FACT_REQUEST → CANDIDATE Gx → Verification PASS → Current = Gx

Unique generation (all nodes): 347f8f51135f5877da73ae82b4d96776
FACT_RESPONSE_INSUFFICIENT: 0
```

`waitingForPrimary` / membership / invite / `transmitReady` **must not** be used to negate generation convergence.

---

## F2 — M04 isolation

```text
M04 in HELLO / currentMembers / device logs: ABSENT (165729, 173143)
F2_RESULT: PASS
```

---

## F4 / GIE — field timeline (authoritative: 163552)

```text
M01 → M03 (late peer invite execution)

16:37:37.977  MEDIA_RELEASE_DEFERRED_CREATE module=M03 (GROUP queued)
16:37:38.013  MEDIA_RELEASE_COMPLETE → MEDIA_PENDING_DRAIN → PROVISIONED
16:37:38.078  GIE_EXECUTION_OPPORTUNITY (first; wire blocked NEVER_OBSERVED — transient)
16:37:38.981  GROUP_LATE_PEER_DISCOVERED peer=M03
16:37:38.985  MEDIA_RELEASE_DEFERRED_CREATE origin=mediaSessionReuse
16:37:39.008  RELEASE_COMPLETE → PENDING_DRAIN
16:37:39.067  MEDIA_PROVISIONED + GIE_EXECUTION_OPPORTUNITY
16:37:39.103  GIE_GROUP_INVITE_WIRE_SENT peer=M03
              M03 receives GROUP_INVITE from M01 on wire

Also: M01→M02 wire at 16:37:33.942; M02→M03 wire at 16:37:46.988
Dedup: latePeer events ≫ wire sends; GIE_PROVISION_SKIPPED_DUPLICATE observed
```

```text
GIE FIELD PASS
  = deferred / queued invite intent retained
    → release/provision lifecycle complete
    → execution opportunity
    → valid GROUP_INVITE on wire
```

`transmitReady` **not** required for this PASS.

---

## F5 / IGIE — field (20260831-165729)

```text
M02 answerer (IGIE exercised):
  GROUP_INVITE received
  → accept entered
  → MEDIA_PROVISIONED (async)
  → IGIE_EXECUTION_OPPORTUNITY
  → GROUP_ACCEPT_HANDOFF SUCCESS
  → IGIE_GROUP_ACCEPT_WIRE_SENT
  → M01 received GROUP_ACCEPT
  Duplicate → SKIPPED gate=ACCEPT_COMPLETE
  Zero sync-engine-pending errors

M03 late-peer @ 165729:
  INVITE SENT by M01/M02
  INVITE RECEIVED on M03: 0
  → delivery residual (cold-start socket rebind)
  → not IGIE accept-lifecycle FAIL
```

```text
IGIE FIELD CONFORMANCE: PASS (M02)
F5 CLOSED FOR THIS DEFECT: YES (implementation class)
M03 IGIE @ 165729: NOT EXERCISED (GROUP_INVITE received = 0)
```

**Superseding field evidence @ 20260831-173143:**

```text
M03 IGIE: EXERCISED / PASS
  GROUP_INVITE received (M01 + M02)
  → IGIE_EXECUTION_OPPORTUNITY
  → IGIE_GROUP_ACCEPT_WIRE_SENT (M01 + M02)
```

---

## F6 / GIDR — field recovery (authoritative: 20260831-173143)

```text
M01 → M03 (natural datagram miss + bounded stale retry)

17:32:45.077  INVITE #1 local handoff (GIE_GROUP_INVITE_WIRE_SENT)
              M03 cold-start socket rebind / waitingForPrimary — ingress miss
17:32:45.126  GIDR phase=ACTIVE SuppressActive

17:32:52.208  M03 receives GROUP_INVITE from M02
17:32:52.232  M03 receives GROUP_INVITE from M01
17:32:52.542  M03 IGIE_EXECUTION_OPPORTUNITY peer=M02
17:32:52.807  M03 IGIE_GROUP_ACCEPT_WIRE_SENT → M02
17:32:52.841  M03 IGIE_GROUP_ACCEPT_WIRE_SENT → M01

17:32:55.436  GIDR phase=STALE_RETRY_ELIGIBLE gate=PermitRetry
17:32:55.436  GIDR_EXECUTION_OPPORTUNITY origin=delivery_retry
17:32:55.538  INVITE #2 local handoff (GIE_GROUP_INVITE_WIRE_SENT)

17:32:56.840  GIDR_DELIVERY_SATISFIED peer=M03 reason=GROUP_ACCEPT_RECEIVED
```

```text
GIDR FIELD RECOVERY PASS
  = INVITE #1 local handoff
    → peer ingress miss
    → ACTIVE / SuppressActive
    → existing 10s stale boundary
    → STALE_RETRY_ELIGIBLE
    → GIDR_EXECUTION_OPPORTUNITY
    → INVITE #2
    → peer receives
    → IGIE accept
    → GROUP_ACCEPT
    → GIDR_DELIVERY_SATISFIED
```

Proves GIDR-T1..T4 field semantics. Normal completion alone would also suffice; this run additionally captured full recovery chain.

```text
F6 / GIDR:
  UNIT PASS
  IMPLEMENTATION CONFORMANCE PASS
  FIELD RECOVERY PASS
  CLOSED FOR THIS DEFECT
```

---

## CSO — Cold-Start Origin Production Seam (FIELD PASS / CLOSED)

Observed chain:

```text
M01: RequestFact → DEFERRED_NO_HOLDER
  → CSO_ORIGIN issued generation=347f8f51135f5877da73ae82b4d96776
  → GBC_EFFECT RealignToCurrent (same generation)
  → ObligationClosed
  → GROUP bootstrap proceeds with legal Current

M02: DEFERRED_NO_HOLDER → HELLO locate → FACT_REQUEST sent
  → FACT_RESPONSE_CANDIDATE verified (same generation)
  → RealignToCurrent / ObligationClosed

M03: FACT_REQUEST sent (holders=[M01])
  → FACT_RESPONSE_CANDIDATE verified (same generation)
  → RealignToCurrent / ObligationClosed
```

### Field matrix vs CSO-EG

| Gate | Field proof | Result |
|------|-------------|--------|
| EG1 | M01 candidate + no Current → exactly one genesis (`CSO_ORIGIN issued`; single generation id) | PASS |
| EG2 | M02/M03: **zero** `CSO_ORIGIN`; no follower origin | PASS |
| EG3 | No duplicate genesis identity; INSUFFICIENT count=0 this run | PASS |
| EG4 | M01 RealignToCurrent retains issued generation | PASS |
| EG5 | M02/M03 FACT_REQUEST → CANDIDATE verified → same Current | PASS |
| EG6 | Unit/restart covered; not re-proved this field idle run | N/A (unit PASS) |

CSO does **not** use INSUFFICIENT-as-authority: origin ran on bootstrap-candidate reevaluation after DEFERRED/reconcile, then re-provide served followers.

---

## Bootstrap chain — current field posture (173143)

```text
Generation Current              PASS
Membership ready                PASS
GROUP_INVITE execution          PASS
GROUP_INVITE delivery/recovery  PASS
Inbound IGIE                    PASS
GROUP_ACCEPT                    PASS
────────────────────────────────────
Media readiness                 OPEN
```

Do **not** describe `transmitReady=false` as a Group bootstrap / invite residual. It is:

```text
Post-Accept Media Readiness Residual
```

---

## Residual — post-accept media readiness (current)

**Authoritative @ 173143:**

```text
M03 @ 17:33:02:
  membershipReady=true
  joinedMembers=M01,M02,M03
  waitingForPrimary=false

  GROUP_INVITE received
  GROUP_ACCEPT sent

  transmitReady=false
  M02 ICE != CONNECTED on M03
```

**Post-accept audit:** [adr0057-post-accept-media-readiness-residual-audit.md](./adr0057-post-accept-media-readiness-residual-audit.md)

**F7 seam audit:** [adr0057-post-accept-pc-ice-lineage-seam-audit.md](./adr0057-post-accept-pc-ice-lineage-seam-audit.md)

```text
Classification: CONFIRMED IMPLEMENTATION GAP (ICE / Media Execution)
F7:             Post-Accept PC / ICE Lineage Desynchronization — CONFIRMED
NOT:            signaling gap (A) or projection gap (C)

Invariant violated:
  answer must apply to same PC lineage that originated the offer
```

**F7 field run (GPLB @ 20260902-095312):** [ADJUDICATION.txt](../../../logs/adr0057-track-c-case-a-20260902-095312/ADJUDICATION.txt)

```text
F7 / GPLB parent:
  CONTRACT DIRECTION RETAINED
  ACCEPT CORRELATION FIELD PASS
  FULL FIELD CONFORMANCE FAIL / OPEN

F7a / GPLB Binding Lifecycle Gap:
  CONFIRMED IMPLEMENTATION GAP
  GPLB-LC narrow cut AUTHORIZED

Tier-1 (M03↔M02):
  ACCEPT correlate CURRENT + SRD pcLineage=2:  PASS
  ICE lineage + ICE_CONNECTED:                FAIL
  NO_LIVE_BINDING: 360 | STALE post-ACCEPT: 264

Tier-2 transmitReady: FAIL / OPEN (separate score)
```

Correction contract: binding-before-candidate + signaling completion ≠ binding termination (GPLB-L1..L4). **Not** “TERMINAL waits for ICE_CONNECTED.”

---

## F8 — Talkback-selected network bridge + native consumption (20260902)

**Authoritative native run:** `talkback/logs/adr0057-track-c-case-a-20260902-125547/`  
**Prior ENV_INVALID:** `123401` (instrumentation `.so` not delivered — excluded)

```text
F8:
  CONTRACT RETAINED
  A2 Java propagation: FIELD PROVEN / CLOSED
  Native NetworkMonitor consumption: FIELD PROVEN / CLOSED
  wlan0 enumeration: PASS
  native network map: PASS
  android_setsocknetwork: PASS
  FIELD OBJECTIVE: FAIL / OPEN

Residual seam:
  ICE port allocator / UDPPort host candidate emission
  downstream of successful wlan0 bind

F9:
  NOT OPEN
```

**Proven chain (M02/M03):** wlan0 visible → map correct → bind `192.168.10.23/.24` SUCCESS → GENERATED loopback only.

**F8b provider run (authoritative):** `talkback/logs/adr0057-track-c-case-a-20260902-150414/`

```text
F8b: ROOT CAUSE CLOSED
Root cause: default-route-based local-address discovery insufficient for MANET
M01: query success → materialized → GENERATED 192.168.10.26
M02/M03: query fail → cache empty → lookup_failed → ANY survives → filter reject
```

```text
F8:
  CONTRACT RETAINED
  ROOT CAUSE PROVEN
  FIX VERIFIED
  CANDIDATE-GENERATION LAYER CLOSED

F8b:
  ROOT CAUSE CLOSED

F8-FIX-1:
  FIELD PASS / CLOSED FOR THIS DEFECT

F9:
  NOT OPEN
```

**F8-FIX-1:** [Same-network materialization narrow authorization](./adr0057-f8-fix1-same-network-materialization-narrow-authorization.md) — **CLOSED**

**Track C E2E:** [End-to-end close adjudication](./adr0057-track-c-e2e-close-adjudication.md) — **FIELD PASS / ADR-0057 COMPLETE** (run `152328`)

**Post-closure (ship readiness — does not amend ADR):** [F8-FIX-1 production integration](./adr0057-f8-fix1-production-artifact-integration.md) · [Production smoke](./adr0057-production-case-a-smoke-run-card.md)

---

## Prior-run residual — superseded (historical only)

The following observations are **prior-run evidence** from early Case A runs (pre-GIE/IGIE/GIDR). They are **superseded** by `173143` and must not be read as current state:

```text
transmitReady=true: never observed          → superseded; M03 now has membership + accept
M01 membership stayed M01,M02               → superseded; joinedMembers=M01,M02,M03 @ 173143
M02/M03: zero inbound GROUP_INVITE          → superseded; M03 received + accepted @ 173143
M02/M03: waitingForPrimary=true persists    → superseded; M03 waitingForPrimary=false @ 173143
```

See [post-convergence group bootstrap residual audit](./adr0057-post-convergence-group-bootstrap-residual-audit.md) for seam analysis (historical context through F4 discovery).

---

## Domain split (current)

| Observation | Domain |
|-------------|--------|
| DEFERRED → FACT_REQUEST / CANDIDATE | ACQ PASS / CLOSED |
| CSO_ORIGIN + local RealignToCurrent | CSO PASS / CLOSED |
| Follower CANDIDATE verified same gen | CSO EG5 + VB/F1 PASS |
| Same-Current on M01/M02/M03 | Generation convergence PASS |
| GROUP_INVITE execution (GIE) | F4 PASS / CLOSED |
| GROUP_INVITE delivery + stale retry (GIDR) | F6 PASS / CLOSED |
| Inbound accept (IGIE) | F5 PASS / CLOSED |
| GROUP_ACCEPT on wire | Control/bootstrap PASS |
| F8 host gathering / transport edge | **F8-FIX-1 PASS / CLOSED** |
| Track C E2E close (C1–C6) | **FIELD PASS** — run `152328` |
| `transmitReady` / OPERATIONAL (triad) | **PASS** — stable ~5m post 15:24:03 |
| No M04 | F2 PASS |

---

## Formal status

```text
ADR-0057 — Group Bootstrap Convergence:  COMPLETE / CLOSED

Contract:                 PASS
Implementation:           PASS
Production Trust Chain:   PASS
Track C E2E Field:        PASS

Generation Convergence:   PASS
Acquisition:              PASS
Control Bootstrap:        PASS
PC / ICE Lineage:         PASS
Transport:                PASS
Readiness Convergence:    PASS

SELF_RECOVER:             YES
Case A:                   PASS
```

**Historical seams (archived — not open work):**

```text
F4/GIE · F5/IGIE · F6/GIDR · F7/GPLB · F7a/GPLB-LC · F8 · F8b · F8-FIX-1: CLOSED
F9: NOT OPEN
```

**Layer closures (frozen context):**

```text
Generation Fact Acquisition · CSO · PV/VB/PTI/PAP/OE/PW · GBC Core: CLOSED / FROZEN
GROUP Membership Admission · GIE · GIDR · IGIE: FIELD PASS / CLOSED
```

**Authoritative E2E run:** `talkback/logs/adr0057-track-c-case-a-20260902-152328/`

See [ADR-0057 complete closure](./adr0057-complete-closure.md) · [Track C E2E close adjudication](./adr0057-track-c-e2e-close-adjudication.md)

---

## Prior run (pre-CSO) — archived summary

`153654`: ACQ FIELD PASS; generation stuck at INSUFFICIENT; F3 CONFIRMED → authorized CSO cut.  
See also: [CSO narrow authorization](./adr0057-cold-start-origin-production-seam-narrow-authorization.md)
