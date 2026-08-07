# Reconnect Convergence Fence Validation Run Card

**Status:** AUTHORIZED (baseline / unfenced observation)  
**Case:** `reconnect-convergence-fence-validation`  
**Date:** 2026-08-07  
**Parent:** [RCA-M03-Reconnect](./rca-m03-wifi-reconnect-membership-divergence.md)  
**Semantics:** [Q3-lite four-view audit](./q3-lite-membership-view-semantics-audit.md) (CLOSED)  
**Evidence freeze:** commit `0a91ab2` (Q3-lite + RCA Phase 2)

**Goal:** Prove that during the post-reconnect membership non-ready window, outbound mesh invite continues (or does not). **Not** WiFi reconnect success. **Not** recovery lifecycle. **Not** fence implementation.

```text
TransportReady  ≠  SignalingAdmissionReady
```

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

## Observation enrichment (behavior-neutral; preferred)

If a build is cut for this run, add **logs only** (no gate behavior):

### 1. `MESH_ADMISSION_DECISION`

Emit at the decision site before `offerGroupMeshJoin()` / mesh retry:

```text
MESH_ADMISSION_DECISION
  ready=<bool>
  reason=<MEMBERSHIP_PENDING|DIGEST_MISALIGNED|RESYNC_IN_FLIGHT|TRANSPORT_ONLY|ALLOWED>
  sessionId=
  channelId=
  remote=
  rosterEpoch=
  memberHash=
```

Purpose: prove *what a fence would decide* without implementing suppress.

Without this log, adjudicate from existing `TOPOLOGY_SNAPSHOT` + `MESH_OFFERED` timing only (weaker but acceptable for baseline).

### 2. Convergence epoch correlation

On each invite attempt and each topology snapshot, record:

```text
rosterEpoch / membershipEpoch
topologyDigest (or memberHash)
inviteAttemptId (or mesh offer correlation id if present)
```

Purpose:

```text
invite happened during epoch N
epoch N was unstable
```

**Unauthorized:** wiring `ready=false` into actual suppress. Observation only.

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

Correlate each invite with:

```text
rosterEpoch + memberHash at offer time
membershipDigestAligned / groupTopologyReadiness
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

**Do not** treat UI ONLINE / banner as authority.

---

## T4 — Adjudication

### Baseline (no fence) — this card’s default run

| Outcome | Verdict |
|---------|---------|
| T2: NOT_READY + MESH_OFFERED + CALL_REJECT storm | **H1 SUPPORTED** — fence design authorized next |
| T2: NOT_READY + no MESH_OFFERED, but storm persists | **H1 REFUTED** — widen RCA (other admission path) |
| T2: NOT_READY + no MESH_OFFERED + no storm | Unexpected healthy path; document; recheck instrumentation |
| T3: converges then offers resume cleanly | Convergence eventual; fence still may be needed for window |

### Post-fence target (NOT this run; future card)

```text
NOT_READY → MESH_OFFERED = 0 (or MESH_OFFER_SUPPRESSED)
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
MESH_OFFERED
MESH_ADMISSION_DECISION
CALL_REJECT
TRANSPORT_READY
```

---

## Authorization board

```text
0a91ab2 Q3-lite audit                 PUSHED

RCA-M03                               OPEN
Q3-lite                               CLOSED (semantic clarification)
H1 convergence fence                  SUPPORTED (static) · field verify THIS RUN

This run authorizes:                  baseline observation only
Next if H1 field-supported:           Post-Reconnect Convergence Contract draft
                                      + SignalingAdmissionReady predicate

Unauthorized:
  fence implementation
  BUSY refactor
  membership patch / append-self
  recovery / completion / RNA changes
  PR-UI-2 as fix signal
```

---

## Status after this card

```text
PR-UI-2                               implemented · independent · waiting review
Directed Run T0–T4                    AUTHORIZED · execute next
```
