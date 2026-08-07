# RCA-M03-Reconnect: WiFi Reconnect Induced Membership/Signaling Divergence

**Status:** OPEN — Phase 1 evidence only (no root cause, no code changes)  
**Date:** 2026-08-07  
**Trigger:** Call/Messaging smoke test (`logs/call-msg-smoke-20260807-201740`)  
**DUT:** M03 (`MDX0220416001963`) — WiFi disconnect/reconnect during active conference  
**Topology:** M01 / M02 / M03 on channel `CH-01`, SSID `happy`

## Positioning

This is **not** a recovery lifecycle RCA (ADR-0040 / PR-LIFE / completion).

It documents a **recovery-adjacent runtime incident**:

```text
WiFi reconnect
    → topology convergence window
    → membership snapshot divergence
    → mesh invite / CALL_REJECT signaling storm
```

**Excluded regression:** `WiFi flap → recovery lifecycle broken` — not observed in this run. Phase 3.2 recovery architecture remains intact.

---

## 1. Scope

### In scope

| Area | Notes |
|------|-------|
| WiFi disconnect / reconnect on M03 | `networkId=300` → loss → `networkId=301` rebind |
| Topology / membership snapshot divergence | `members=M01,M02` on M03 (self absent) |
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
    +-- M03 local snapshot: members=M01,M02 (self missing)
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

### O3 — Self identity absent from M03 local topology

At 20:26:53 on M03:

```text
TOPOLOGY_SNAPSHOT localModuleId=M03 members=M01,M02
membershipDigestAligned=true
membershipReconciled=true
```

M03's local module ID is not in `members`. Canonical expectation: `[M01, M02, M03]`.

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
local topology may diverge (self ∉ members)
    |
CANONICAL_MISMATCH → GROUP_RESYNC_REQUEST (membership self-repair)
```

Membership has mismatch detection and resync request. Signaling appears to lack a **convergence fence** that suppresses invite retry until topology/membership stabilizes.

**Status:** Hypothesis only. Requires static code audit + directed re-run to confirm or refute.

---

## 6. Open questions

### Q1 — Why does topology instability allow invite retry?

**Ask:** Is there a gate equivalent to `membershipConverged == false` or `membershipDigestAligned == false` on outbound mesh invite / re-dial?

**Audit targets:**

- Mesh planner / topology scheduler (`MESH_OFFERED` emission path)
- `membershipDigestAlignedWithAuthority()` usage in `TalkbackCoordinator`
- `groupTopologyReadiness=MEMBERSHIP_PENDING` → invite admission

**Field signal:** M02 emitted `MESH_OFFERED` with `membershipDigestAligned=false`.

---

### Q2 — Is BUSY semantically correct here?

**Ask:** Does `CALL_REJECT reason=BUSY` mean "target has active incompatible call" or is it overloaded for "cannot accept transient mesh invite during convergence"?

**Field signal:**

```text
Mesh invite rejected by M03 reason=BUSY (session kept)
Mesh invite rejected by M01 reason=BUSY (host-owned conference kept)
```

Conference is already active — peers are re-inviting each other, not starting a new incompatible call. Possible **semantic pollution** of BUSY.

**Audit targets:**

- `buildSignedEnvelope(SignalType.CALL_REJECT, …, "BUSY")` call sites in `TalkbackCoordinator`
- Conditions that select BUSY vs DECLINED vs canonical-yield payloads

---

### Q3 — Why does M03 lose self from local members?

**Ask:** Which stage drops M03 from `members`?

| Candidate | Question |
|-----------|----------|
| Snapshot source | Does incoming snapshot omit self? |
| Merge / apply | Does apply filter self under epoch competition? |
| Epoch race | Does stale authority view overwrite local self-inclusion? |

**Field signal:** `members=M01,M02` with `membershipDigestAligned=true` and `membershipReconciled=true` — suggests local view considers itself aligned while self is absent. Contradiction worth tracing.

**Related concept (do not change):** Phase 3.2 `canonicalRoster` / `onlineMembers()` invariant — self ∈ canonical roster.

---

## 7. Phase 2 plan (not authorized)

| Step | Action |
|------|--------|
| P2-1 | Static audit: mesh invite emission gates vs `membershipDigestAligned` |
| P2-2 | Static audit: BUSY reject call sites and semantic mapping |
| P2-3 | Static audit: topology snapshot `members` construction — self inclusion |
| P2-4 | Directed re-run: M03 WiFi flap only, same topology, capture Q1–Q3 tokens |
| P2-5 | If fence gap confirmed → new ADR (signaling convergence fence); not ADR-0040 amendment |

**Forbidden in Phase 2 without new ADR:**

- Completion predicate change
- RNA-5/6 change
- UI banner as workaround
- Enlarged recovery budget

---

## 8. Relationship to other tracks

| Track | Relationship |
|-------|--------------|
| PR-UI-1 | Independent — conversation overlay lifecycle fix (merged separately) |
| PR-UI-2 | Independent — peer reconnecting hint from UVCP; must not read `canonicalMismatch` |
| ADR-0040 | No regression; do not conflate |
| Phase 3.2 baseline | Architecture CLOSED; this is next-layer engineering maturity |

---

## 9. Status board entry

```text
Runtime:
  WiFi reconnect membership divergence     RCA OPEN (this document)
  CALL_REJECT storm                        RCA OPEN (this document)

Hypothesis:
  reconnect convergence fence missing      UNDER INVESTIGATION

Not claimed:
  membership bug                           (no root cause yet)
  recovery lifecycle regression            (excluded by evidence)
```
