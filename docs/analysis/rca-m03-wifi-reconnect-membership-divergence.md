# RCA-M03-Reconnect: WiFi Reconnect Induced Membership/Signaling Divergence

**Status:** OPEN — Phase 2 audit (Q3-lite COMPLETE; directed run PENDING; no code changes authorized)  
**Date:** 2026-08-07  
**Trigger condition:** WiFi flap on M03 during active 3-party conference (not a problem domain)  
**Field evidence:** Call/Messaging smoke test (`logs/call-msg-smoke-20260807-201740`)  
**DUT:** M03 (`MDX0220416001963`)  
**Topology:** M01 (host) / M02 / M03 on channel `CH-01`, SSID `happy`

## Positioning

**WiFi flap is a trigger condition, not the problem domain.**

```text
WiFi flap (trigger)
    |
    +-- Recovery lifecycle        VERIFIED (ADR-0040; no regression)
    +-- Transport state           OBSERVED OK (rebind / TRANSPORT_READY)
    +-- Membership convergence    INVESTIGATION (current)
    +-- Signaling admission       INVESTIGATION (current)
```

This is **not** a recovery lifecycle RCA (ADR-0040 / PR-LIFE / completion).

It documents a **recovery-adjacent runtime incident**:

```text
transport recovered
    → membership / topology convergence window
    → business signaling not gated
    → mesh invite / CALL_REJECT storm
```

**Excluded regression:** `WiFi flap → recovery lifecycle broken` — not observed in this run. Phase 3.2 recovery architecture remains intact.

**Adjacent problem (not same as ADR-0040):** ADR-0040 fixed *recovery responsibility loss*; this RCA investigates *signaling readiness after recovery* — when the cluster may resume business interaction.

---

## 1. Scope

### In scope

| Area | Notes |
|------|-------|
| WiFi disconnect / reconnect on M03 | `networkId=300` → loss → `networkId=301` rebind |
| Topology / membership snapshot divergence | `members=M01,M02` on M03 — **reframed** (Q3-lite: likely remote-only `groupMembers` projection; not proven self-loss) |
| `CANONICAL_MISMATCH` + `GROUP_RESYNC_REQUEST` | Membership layer self-detection |
| `CALL_REJECT` storm | ~930 total log lines across three devices |
| Mesh invite retry during unstable window | `MESH_OFFERED` while `membershipDigestAligned=false` |

### Out of scope

| Area | Reason |
|------|--------|
| ADR-0040 obligation convergence | No regression evidence |
| Recovery completion predicate | Not implicated |
| UI projection / Conference Banner | Separate track (PR-UI-2) |
| Recovery lifecycle / RNA-5/6 | Frozen; no directed re-run |

### Method

Read-only log analysis. **No code changes. No field re-run required for Phase 1.**

---

## 2. Field evidence

| Artifact | Path |
|----------|------|
| M01 log | `logs/call-msg-smoke-20260807-201740/M01-logcat.txt` |
| M02 log | `logs/call-msg-smoke-20260807-201740/M02-logcat.txt` |
| M03 log | `logs/call-msg-smoke-20260807-201740/M03-logcat.txt` |

### CALL_REJECT volume

| Device | Count | Window (approx.) |
|--------|-------|------------------|
| M01 | 422 | 20:24:59 – 20:26:49 |
| M02 | 258 | 20:26:16 – 20:26:49 |
| M03 | 250 | 20:25:02 – 20:26:05 |

---

## 3. Timeline

```text
[pre-flap — conference OPERATIONAL on all three nodes]

20:24:50   M03 still on networkId=300, HEARTBEAT with M01
20:24:59   M01 begins receiving CALL_REJECT from M03 and M02
20:25:02   M03 → M01 CALL_REJECT burst begins (networkId=300, still connected)
20:25:19   M03 WiFi DISCONNECTED (networkId=300 onLost)
20:25:38   M03 WiFi reconnect: networkId=301, socketId=6, rebindGeneration=2
           DISCOVERY_REBIND_SUCCESS / SIGNAL_SOCKET_BOUND / TRANSPORT_READY
20:25:47   M03 ← M01 CALL_REJECT; M03 logs:
           "Mesh invite rejected by M01 reason=BUSY (host-owned conference kept)"
20:26:16   M02 MESH_OFFERED → M03 (membershipDigestAligned=false, MEMBERSHIP_PENDING)
           M02 receives repeated "Mesh invite rejected by M03 reason=BUSY (session kept)"
20:26:53   M03 TOPOLOGY_SNAPSHOT members=M01,M02  ← self absent from local roster
           groupTopologyReadiness=BUILDING, meshIceConnectedPeers=M02 only
20:26:55   M03 CANONICAL_MISMATCH
           local=[M01,M02] authority=[pending_resync]
           → GROUP_RESYNC_REQUEST → M01
```

### Sequence diagram

```text
M03 WiFi lost (20:25:19)
    |
    +-- transport offline (socketId=4 / networkId=300)
    |
M03 WiFi returns (20:25:38)
    |
    +-- socket rebind (socketId=6 / networkId=301)
    |
    +-- mesh invite traffic resumes (before topology stable)
    |       |
    |       +-- CALL_REJECT reason=BUSY (bidirectional)
    |
    +-- M03 local snapshot: members=M01,M02 (remote-only projection; localModuleId=M03 separate)
    |
    +-- CANONICAL_MISMATCH detected
    |
    +-- GROUP_RESYNC_REQUEST emitted
```

**Note:** CALL_REJECT activity begins ~17s before formal `onLost` (20:24:59 vs 20:25:19). Phase 2 should determine whether this is pre-disconnect degradation, overlapping user action, or early transport impairment.

---

## 4. Observations (facts, not conclusions)

### O1 — System detects divergence; signaling does not damp

Membership layer exhibits expected self-awareness:

```text
CANONICAL_MISMATCH localEpoch=1 authorityEpoch=2
GROUP_RESYNC_REQUEST → M01
```

Signaling layer simultaneously allows high-volume `CALL_REJECT` traffic. No observed `RECOVERY_FENCE` or equivalent signaling suppression during the convergence window.

### O2 — CALL_REJECT is bidirectional; payload is BUSY

| Direction | Evidence |
|-----------|----------|
| M03 → M01 | `SIGNAL_DATAGRAM_SENT signalType=CALL_REJECT` (250 on M03) |
| M01 → M03 | `REMOTE_RECEIVE_OBSERVED signalType=CALL_REJECT` on M03 at 20:25:47 |
| M02 ↔ M03 | M02 `MESH_OFFERED` + M03 `reason=BUSY (session kept)` |

Decoded reject reason in application logs: **`BUSY`** — not `STALE_SESSION`, `WRONG_GENERATION`, `NOT_MEMBER`, or `ICE_STATE_INVALID`.

Handler path: `TalkbackCoordinator.handleCallReject()` → mesh conference branch → `evictMeshInvitee` / `host-owned conference kept`.

### O3 — Topology snapshot `members` omits local (Q3-lite: Semantic A, not violation)

At 20:26:53 on M03:

```text
TOPOLOGY_SNAPSHOT localModuleId=M03 members=M01,M02
membershipDigestAligned=true
membershipReconciled=true
```

**Q3-lite verdict:** `TOPOLOGY_SNAPSHOT.members` is projected from `groupMembers` only (`canonicalMemberModuleIds()`). After `applyMembershipSnapshot()` with a remote-only roster, `groupMembers=[M01,M02]` while `memberModules` includes M03 — **Semantic A (remotes only)**. `localModuleId=M03` is a separate field. `membershipDigestAligned=true` means remote roster hash matches authority, **not** that local ∈ `groupMembers`.

**Not established:** self-invariant violation. **Do not patch** with force-append-self.

**Audit:** [q3-lite-membership-view-semantics-audit.md](./q3-lite-membership-view-semantics-audit.md)

### O4 — M02 continues mesh offers while membership not aligned

At 20:26:16 on M02:

```text
TOPOLOGY_SNAPSHOT reason=MESH_OFFERED
members=M01,M02,M03
membershipDigestAligned=false
membershipReconciled=false
groupTopologyReadiness=MEMBERSHIP_PENDING
meshDesiredLinks=M02->M03
```

Invite traffic proceeds despite `membershipDigestAligned=false`.

### O5 — No recovery lifecycle regression signal

This run does not show ADR-0040 completion-path failure, obligation lifecycle gap, or `RECOVERY_EDGE_RECOVERED` mis-emission. The incident sits at the **topology / mesh-signaling convergence boundary**, not the frozen recovery completion layer.

---

## 5. Hypothesis (H1 — not root cause)

### H1: Reconnect convergence fence missing

```text
network returns
    |
node not fully converged (membershipDigestAligned=false)
    |
mesh invite / re-dial traffic resumes
    |
CALL_REJECT reason=BUSY (storm)
    |
local topology view may lag authority (epoch/hash mismatch)
    |
CANONICAL_MISMATCH → GROUP_RESYNC_REQUEST (membership self-repair)
```

Membership has mismatch detection and resync request. Signaling appears to lack a **convergence fence** that suppresses invite retry until topology/membership stabilizes.

**Status:** Hypothesis only. Requires static code audit + directed re-run to confirm or refute.

---

## 6. Open questions (priority order)

Investigation order: **Q3-lite → Q1 → Q2**. Define readiness facts before gating; reduce storm by suppressing bad invites, not by prettifying reject strings.

### P0 — Q3-lite: Membership view semantics — **COMPLETE**

**Audit:** [q3-lite-membership-view-semantics-audit.md](./q3-lite-membership-view-semantics-audit.md)

**Q3.1 Snapshot semantics:** Path-dependent — **Semantic A (remote-only)** on broadcast/observation; **Semantic B (full roster)** possible on `payload.members` apply path. `members=M01,M02` on M03 is **not** proven violation.

**Q3.2 Digest ownership:** Single source for `TopologyDigest.memberHash` — `groupMembers` only (not `memberModules`). `membershipDigestAligned` vs `CANONICAL_MISMATCH` are different probes; not necessarily contradictory.

**Q3.3 Fence inputs:** `membershipDigestAlignedWithAuthority`, `membershipReconciled`, `groupTopologyReadiness`, `isMembershipResyncInFlight` **exist** but are **not wired** to `completeGroupMesh()` / `offerGroupMeshJoin()`.

**Forbidden (unchanged):** membership self-heal patch, force-append-self, epoch/digest changes.

---

### P1 — Q1: Invite admission fence (H1 validation target)

**Ask:** When membership is not converged, should outbound mesh invite be suppressed?

**Static audit (preliminary — supports H1, not root cause):**

| Path | Membership gate? |
|------|------------------|
| `completeGroupMesh()` → `offerGroupMeshJoin()` | **No** `membershipDigestAligned` / `membershipReconciled` check |
| `GroupMeshReconciler.canOfferJoin()` | ICE checking / backoff only |
| `scheduleGroupMeshRetries()` (500/1500/3000 ms) | Retries mesh without membership gate |
| `shouldDeferConferenceFullMesh()` | Non-host + edge recovering only; not general convergence |

**Field signal:** M02 `MESH_OFFERED` while `membershipDigestAligned=false`, `groupTopologyReadiness=MEMBERSHIP_PENDING`.

**H1 to validate:** blocking outbound mesh invite while not ready eliminates reject storm.

---

### P2 — Q2: BUSY reject semantics (quality; not storm root)

**Ask:** Is `CALL_REJECT reason=BUSY` overloaded for convergence/transient reject?

**Static audit (preliminary):** `sendGroupBusyReject()`, `handleGroupInvite` failure paths, and mesh `handleCallReject` use `BUSY` for existing session / cannot-accept-now — no `REJECT_CONVERGENCE_PENDING` or `REJECT_RECOVERY_TRANSIENT`.

**Defer** until P1 fence validated. BUSY storm is a **symptom**; primary fix is not sending invites during non-ready window.

---

## 7. Signaling readiness model (fence design constraint)

**Forbidden approach:** time-based grace as readiness.

```text
WiFi reconnect → sleep 5s → allow invite   ❌
```

**Required approach:** fact-based readiness predicate.

```text
TransportReady
      +
MembershipConverged      (membershipDigestAlignedWithAuthority + membershipReconciled)
      +
TopologyDigestStable     (authorityDigestKnown + epoch/hash match)
      +
NoPendingResync          (!isMembershipResyncInFlight)
          ↓
    SignalingAdmissionReady   (design sketch — see Q3-lite §5)
```

> Time is not readiness. Facts are readiness.

Likely gate sites (implementation **not authorized** until directed run passes): `completeGroupMesh()`, `offerGroupMeshJoin()`, mesh retry scheduler.

**Not in scope:** new FSM, recovery lifecycle change, completion change.

---

## 8. Phase 2 directed run card

**Case:** `reconnect-convergence-fence-validation`  
**Goal:** Validate H1 — when membership not converged, suppressing outbound mesh invite eliminates `CALL_REJECT` storm.

### T0 — Baseline

Conference: M01 host, M02 peer, M03 peer (DUT). SSID `happy`.

Confirm before flap:

```text
membershipDigestAligned=true
MESH_OFFERED=0 (in critical window baseline)
CALL_REJECT=0 (in critical window baseline)
conference OPERATIONAL
```

### T1 — Trigger

On M03 only:

```text
disable WiFi → wait disconnect observed → enable WiFi
```

Capture on all nodes:

```text
NETWORK_CHANGED / onLost / NETWORK_CAPABILITY_AVAILABLE
ICE_STATE
MEMBERSHIP_EPOCH / rosterEpoch
TOPOLOGY_DIGEST / memberHash
CANONICAL_MISMATCH
GROUP_RESYNC_REQUEST
```

### T2 — Critical window (primary evidence)

While **any** of:

```text
membershipDigestAligned=false
OR groupTopologyReadiness=MEMBERSHIP_PENDING
OR CANONICAL_MISMATCH observed
OR GROUP_RESYNC_REQUEST in flight
```

Count per device:

```text
MESH_OFFERED
GROUP_INVITE_SENT / Group mesh join offered
CALL_REJECT (by reason if decodable)
```

**Expected (current behavior, unfenced):**

```text
membership not ready → MESH_OFFERED++ → CALL_REJECT storm
```

### T3 — Fence expected behavior (post-fix validation)

When fence implemented, same run should show:

```text
membership not ready → MESH_OFFER_SUPPRESSED reason=CONVERGENCE_PENDING
```

Not:

```text
offer → reject → retry → offer → reject
```

### T4 — Recovery confirmation

Wait until:

```text
membershipDigestAligned=true
canonical match (no CANONICAL_MISMATCH)
```

Then:

```text
MESH_OFFERED allowed
CALL_REJECT burst = 0
conference returns to OPERATIONAL
```

### Pass / fail

| Outcome | Verdict |
|---------|---------|
| T2 shows offers during not-ready + storm | H1 supported; fence authorized for design |
| T2 shows no offers during not-ready but storm persists | H1 refuted; widen RCA |
| T3 post-fix: suppressed + no storm | Fence fix verified |
| T3 post-fix: suppressed but storm persists | Additional admission path exists |

**Log directory naming:** `logs/rca-m03-fence-validation-YYYYMMDD-HHMMSS/`

---

## 9. Phase 2 plan and authorization boundary

| Step | Status | Action |
|------|--------|--------|
| P0 | **COMPLETE** | Q3-lite: [four-view audit](./q3-lite-membership-view-semantics-audit.md) |
| P1 | **NEXT** | Directed run T0–T4 (H1 validation) |
| P2 | PENDING | If H1 confirmed → lightweight Post-Reconnect Convergence Contract draft |
| P3 | NOT AUTHORIZED | Invite admission gate implementation |
| P4 | NOT AUTHORIZED | BUSY reason refactor |

**Forbidden without new contract / explicit authorization:**

- Recovery lifecycle / ADR-0040 changes
- Completion / RNA-5/6 changes
- Membership self-heal patch
- Time-based sleep fence
- UI workaround as runtime fix

**Effort estimate (if Q3-lite finds no hidden conflict):** readiness predicate ~0.5d · invite gate ~1–2d · UT/directed test ~1d · doc ~0.5d · BUSY reason deferred.

---

## 10. Relationship to other tracks

| Track | Relationship |
|-------|--------------|
| PR-UI-1 | Independent — conversation overlay lifecycle fix (merged separately) |
| PR-UI-2 | Independent — peer reconnecting hint from UVCP; must not read `canonicalMismatch` |
| ADR-0040 | No regression; do not conflate |
| Phase 3.2 baseline | Architecture CLOSED; this is next-layer engineering maturity |

---

## 11. Status board entry

```text
ADR-0040 Recovery Lifecycle        VERIFIED (no regression evidence)

PR-UI-1                            MERGED
PR-UI-2                            VALID UX · independent · not runtime fix

RCA-M03-WIFI-RECONNECT             OPEN
  Phase:                             Phase-2 Audit
  Q3-lite:                            COMPLETE (q3-lite-membership-view-semantics-audit.md)
  Next:                              Q1 directed run (fence validation)

Hypothesis H1 (convergence fence)    SUPPORTED (static + Q3-lite) · field verify PENDING
Q2 (BUSY semantic overload)          CONFIRMED (static) · fix NOT AUTHORIZED
Q3 (self roster)                     CLOSED — Semantic A; not proven violation

Not authorized:
  recovery changes · completion changes · membership self-heal patch
  BUSY semantics refactor · time-based sleep fence

Discipline:
  WiFi flap = trigger only
  define signaling readiness before fixing failure appearance
```
