# Reconnect Convergence Fence Validation Run Card

**Status:** AUTHORIZED — BASELINE FIELD RUN (no instrumentation PR)  
**Case:** `reconnect-convergence-fence-validation`  
**Date:** 2026-08-07  
**Parent:** [RCA-M03-Reconnect](./rca-m03-wifi-reconnect-membership-divergence.md)  
**Semantics:** [Q3-lite four-view audit](./q3-lite-membership-view-semantics-audit.md) (CLOSED)  
**Evidence freeze:** `0a91ab2` + `967e042` on `main` · **current APK only**

**Goal:** Prove that during the post-reconnect membership non-ready window, outbound mesh invite continues (or does not). **Not** WiFi reconnect success. **Not** recovery lifecycle. **Not** fence implementation.

```text
TransportReady  ≠  SignalingAdmissionReady
```

**Baseline discipline (signed):** do **not** add `MESH_ADMISSION_DECISION` before this run. Existing logs (`MESH_OFFERED`, `CALL_REJECT`, `CANONICAL_MISMATCH`, `GROUP_RESYNC_REQUEST`, `rosterEpoch`, `memberHash`) are sufficient to answer H1. Instrumentation is deferred until after H1 field confirmation (implementation prep), not for proving the problem exists.

---

## Architecture boundary (frozen)

```text
IN SCOPE:
  membership convergence window after M03 WiFi flap
  outbound mesh invite / MESH_OFFERED during NOT_READY
  CALL_REJECT storm correlation

OUT OF SCOPE:
  ADR-0040 / recovery lifecycle / completion / RNA-5/6
  membership self-heal / append-self
  fence gate implementation (behavior change)
  BUSY reason refactor
  PR-UI-2 / banner / UVCP as runtime verdict
  time-based sleep as readiness
```

**Q3-lite discipline (do not reintroduce):**

```text
TOPOLOGY_SNAPSHOT members=M01,M02 + localModuleId=M03
  ≠  proven self roster loss

members     = groupMembers view (may be remotes-only)
memberModules = group + local augmentation
```

---

## Topology / devices

| Role | Module | Serial |
|------|--------|--------|
| Host | M01 | `HTUBB21B09220661` |
| Peer | M02 | `2d73067a` |
| DUT  | M03 | `MDX0220416001963` |

- SSID: **`happy`** only (not `happy_5G`)
- Channel: `CH-01` three-party conference (M01 host)

---

## Objective (single question)

> In the membership **not-ready** window, does the system still produce **outbound mesh invite**?

```text
NOT_READY :=
  membershipDigestAligned=false
  OR membershipResyncInFlight=true
  OR groupTopologyReadiness=MEMBERSHIP_PENDING
  OR CANONICAL_MISMATCH observed
  OR GROUP_RESYNC_REQUEST in flight
```

---

## Observation enrichment — DEFERRED

`MESH_ADMISSION_DECISION` and extra inviteAttemptId correlation are **not** part of this baseline.

```text
Baseline run  = current main APK + existing tokens
Instrumentation = only after H1 field-confirmed, before gate impl
```

Adjudicate from:

```text
TOPOLOGY_SNAPSHOT + membershipDigestAligned + MEMBERSHIP_PENDING
CANONICAL_MISMATCH / GROUP_RESYNC_REQUEST
MESH_OFFERED / CALL_REJECT / mesh retry schedule
rosterEpoch / memberHash (already on topology / digest paths)
```

---

## T0 — Baseline

Conference stable on all three nodes. Confirm:

```text
members projection consistent with Q3-lite (do not flag remotes-only as FAIL)
membershipDigestAligned=true
no GROUP_RESYNC_REQUEST in flight
MESH_OFFERED = 0 in quiet window
CALL_REJECT = 0 in quiet window
conference OPERATIONAL
```

Capture:

```text
TOPOLOGY_SNAPSHOT
rosterEpoch / membershipEpoch
digest / memberHash
MESH_OFFERED
CALL_REJECT
```

**PASS:** no anomalous signaling before flap.

---

## T1 — Trigger

On **M03 only**:

```text
WiFi OFF → wait disconnect observed → WiFi ON
```

Do not leave meeting. Do not USER_LEAVE. Do not change membership intentionally.

Capture:

```text
NETWORK_CHANGED / onLost / NETWORK_CAPABILITY_AVAILABLE
DISCOVERY_REBIND / TRANSPORT_READY
ICE_STATE
membership state (digest / readiness / resync)
```

**Note:** Transport recovery is **trigger confirmation**, not pass criterion.

---

## T2 — Critical window (primary evidence)

While **any** NOT_READY condition holds, count per device (especially M01/M02/M03):

```text
MESH_OFFERED
MESH_RETRY / scheduleGroupMeshRetries activity
GROUP_INVITE_SENT / Group mesh join offered
CALL_REJECT (decode reason if possible; expect BUSY)
MESH_ADMISSION_DECISION (if instrumented)
```

Correlate on three axes (primary adjudication):

```text
Axis A — Membership readiness
  CANONICAL_MISMATCH · MEMBERSHIP_PENDING · GROUP_RESYNC_REQUEST
  MEMBERSHIP_SNAPSHOT_APPLY · digest aligned / rosterEpoch / memberHash

Axis B — Signaling admission
  MESH_OFFERED · CALL_REJECT · retry schedule

Axis C — Recovery boundary (exclusion only)
  transport recovered · ICE connected · EDGE_RECOVERED
  (do not use as H1 pass/fail)
```

**Expected (current unfenced main):**

```text
NOT_READY → MESH_OFFERED++ → CALL_REJECT storm
```

---

## T3 — Recovery convergence

Wait until:

```text
membershipDigestAligned=true
AND !membershipResyncInFlight
AND groupTopologyReadiness != MEMBERSHIP_PENDING
```

Then observe:

```text
mesh signaling resumes normally
CALL_REJECT burst stops
conference returns toward OPERATIONAL
```

**Do not** treat UI ONLINE / banner / PR-UI-2 as authority.

---

## T4 — Adjudication

### Baseline (no fence) — this card

| Case | Pattern | Verdict |
|------|---------|---------|
| **1** | NOT_READY + MESH_OFFERED + CALL_REJECT storm | **H1 CONFIRMED** → Post-Reconnect Convergence Contract |
| **2** | NOT_READY + no MESH_OFFERED, but reject continues | **H1 weakened** → Q1.2 (stale invite / retry queue / ownership) |
| **3** | authority epoch=N, local digest=N, topology content diverges | **Reopen Q3-lite** only if true four-view contradiction |
| — | NOT_READY + no MESH_OFFERED + no storm | Unexpected healthy path; document |

### Post-fence target (NOT this run)

```text
NOT_READY → MESH_OFFERED = 0
         → CONVERGED
         → MESH_OFFERED resume
         → CALL_REJECT storm absent/reduced
```

---

## Forbidden interpretations

```text
WiFi reconnect success                         ≠ H1 PASS
members omits local in TOPOLOGY_SNAPSHOT       ≠ self-loss FAIL
UVCP / peer reconnecting hint                  ≠ runtime fixed
ADR-0040 / EDGE_RECOVERED                      ≠ signaling admission ready
INTENT_TERMINAL / FACT                         ≠ this RCA domain
```

---

## Execution notes

Log directory:

```text
talkback/logs/rca-m03-fence-validation-YYYYMMDD-HHMMSS/
```

Suggested capture:

```powershell
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$LogDir = "talkback\logs\rca-m03-fence-validation-$stamp"
# start collectors on M01/M02/M03 → T0 annotate → T1 flap → soak through T3 → stop
```

Mandatory greps (all three device logs):

```text
TOPOLOGY_SNAPSHOT
membershipDigestAligned
MEMBERSHIP_PENDING
CANONICAL_MISMATCH
GROUP_RESYNC_REQUEST
MEMBERSHIP_SNAPSHOT_APPLY
MESH_OFFERED
CALL_REJECT
TRANSPORT_READY
EDGE_RECOVERED
```

---

## Authorization board

```text
ADR-0040 Recovery Lifecycle           VERIFIED
PR-LIFE-1 / PR-LIFE-2                 CLOSED

0a91ab2 Q3-lite audit                 PUSHED
967e042 run card                      PUSHED

PR-UI-1                               MERGED
PR-UI-2                               READY (independent · waiting · not this run)

RCA-M03                               OPEN
Q3-lite                               CLOSED
H1 convergence fence missing          SUPPORTED STATIC
H1 FIELD VALIDATION                   RUNNING (baseline · no instrumentation PR)

Unauthorized:
  admission gate implementation
  MESH_ADMISSION_DECISION before H1 confirm
  BUSY semantic change
  membership mutation
```
