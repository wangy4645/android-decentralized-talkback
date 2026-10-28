# ADR-0057 Track C — Production Convergence Field Validation Run Card (Case A)

## Status

**AUTHORIZED — READY FOR FIELD** (2026-08-31)

```text
ADR-0057 — Track C
Production Convergence Field Validation

MODE:
  FIELD VALIDATION

IMPLEMENTATION:
  FROZEN (no architecture / trust code changes for this run)

Parent RCA:
  group-bootstrap-primary-convergence-rca-20260826.md
  Case A — clean reopen idle observe (NOT post-hangup Case B)
```

**Goal:** Prove whether GROUP bootstrap **generation convergence** self-recovers after
coordinated reopen when production trust wiring is active — the original ADR-0057 field
problem. This run is **not** a trust-implementation or UI polish verdict.

**Does NOT authorize:** code changes, contract amendments, completion predicate changes,
or ADR-0057 COMPLETE on desk review alone.

---

## Topology / devices

| Role | Module | Serial |
|------|--------|--------|
| Primary (expected) | M01 | `HTUBB21B09220661` |
| Peer | M02 | `2d73067a` |
| DUT (observe + correlate) | M03 | `MDX0220416001963` |

Same three-device LAN topology as 2026-08-26 observation. **All three MUST be
force-stopped** before reopen (2026-08-26 M02 stale-session caveat).

---

## 1. Preconditions (hard)

Record before exercise. Any violation → **INCOMPLETE** (do not claim FIELD PASS/FAIL).

### Build / wiring

```text
□ Same candidate build on M01, M02, M03 (record git SHA / versionName)
□ ProductionGbcTrustWiring active (NOT TestGbcTrustWiring / injectable verifier)
□ No debug stub of generation-fact verification on any device
```

### Trust / provisioning state (record explicitly)

Per device, record **before** force-stop:

```text
operational-trust-anchor.bin     present / absent / corrupt
accepted-local-trust-state.bin   present / absent / corrupt
deploymentTrustDomainId          (from build config)
Profile trust accepted           yes / no / unknown
```

```text
filesDir/gbc-trust/
  operational-trust-anchor.bin
  accepted-local-trust-state.bin
```

If anchor or accepted trust is absent, production verification **MAY** return
`UNVERIFIABLE` until provisioned. That is valid field evidence — classify under
**provisioning / trust evidence**, not as GBC semantic failure.

### Session posture

```text
□ NO Meeting / NO Conference active before exercise
□ Talk page reachable on all devices after reopen
□ Do NOT manually trigger media recovery, ICE nudge, or UI workarounds
□ Do NOT tap Join / Start Meeting / PTT during observation window
□ WiFi stable; no intentional flap during observation window
```

### Collectors

```text
□ Fresh log directory per device before force-stop
□ Record wall-clock start (T0) and observation window end (T1)
□ ADJUDICATION.txt template prepared before run (see §4)
```

Suggested log dir:

```text
talkback/logs/adr0057-track-c-case-a-<yyyyMMdd-HHmmss>/
  M01-talkback.log
  M02-talkback.log
  M03-talkback.log
  PRECONDITIONS.txt
  ADJUDICATION.txt
```

---

## 2. Procedure (Case A — frozen)

```text
T0  Record preconditions (§1) on all devices

T1  force-stop Talkback on M01, M02, M03
    (adb shell am force-stop <package> — all three, no exceptions)

T2  Start log collectors on all three devices

T3  Reopen Talkback on all three (order: M01 → M02 → M03 within ~30s)

T4  Navigate each device to Talk page

T5  Idle observe — NO user interaction
    Minimum window: 5 minutes wall clock
    Preferred: 5 minutes after last device reaches Talk page

T6  Stop collectors; capture PRECONDITIONS + ADJUDICATION before log rotation
```

**Forbidden during T5:**

```text
USER_LEAVE / Hangup
Meeting / Conference start
Membership mutation experiments
WiFi flap (reserved for other tracks)
Manual FACT stub / test harness hooks
```

---

## 3. Evidence (mandatory observe chain)

Collect per device unless noted. Missing critical evidence → **INCOMPLETE**.

### 3.1 Acquisition / wire (Coordinator logs)

| Marker | Meaning |
|--------|---------|
| `GBC_EFFECT RequestFact` | GBC opened acquisition; FACT wire scheduled |
| `FACT_REQUEST sent` | Outbound acquisition to located holder |
| `FACT_REQUEST received` | Inbound acquisition request |
| `FACT_RESPONSE_CANDIDATE sent` | Holder reprovided candidate |
| `FACT_RESPONSE_CANDIDATE verified` | Local VB promoted SUCCESS |
| `FACT_RESPONSE_CANDIDATE not promoted` | VB non-success (record outcome class) |
| `FACT_RESPONSE_INSUFFICIENT` | Holder had no promotable Current |

Correlate by `channelId` and `correlation` across devices.

### 3.2 Verification outcome (production path)

From `FACT_RESPONSE_CANDIDATE not promoted` lines or harness-equivalent state:

```text
SUCCESS
MALFORMED
UNVERIFIABLE
VERIFY_FAIL
INTEGRITY_ANOMALY
```

If trust-not-ready at T0, expect `UNVERIFIABLE` until trust becomes available.
Re-acquired candidate **MAY** verify later (PW-IA-T1). Record T-first-UNVERIFIABLE
and T-first-SUCCESS if both occur.

### 3.3 GBC generation authority (core ADR-0057)

| Evidence | Record |
|----------|--------|
| `GBC_EFFECT ObligationClosed` | per channelId |
| `GBC_EFFECT RealignToCurrent` | current generation identity |
| `GBC_EFFECT FenceGeneration` | fenced generation (stale resurrection check) |
| Accepted Current generation | per device per channelId |
| Knowledge state | Known / Unknown / Conflict |
| F1 classifier outcome | SAME / SUPERSEDED / UNKNOWN (from GBC consumption path) |
| Permanent UNKNOWN | yes / no after window |
| Permanent acquisition obligation OPEN | yes / no after window |
| Stale-generation resurrection | yes / no (fenced gen re-adopted?) |

**HELLO holder hint:** record modules appearing as locate targets before FACT_REQUEST
(Coordinator locate path — not authority by itself).

### 3.4 GROUP bootstrap (downstream of generation, separate score)

| Evidence | Record |
|----------|--------|
| `sessionTraceId` / `sessionLineageId` | per device |
| `waitingForPrimary` | true / false over time |
| `bootstrapAttemptCount` | monotonic stall? |
| `GROUP_SESSION_CREATE` / invite / admission | blocked reason if any |
| `HELLO_CHANNEL_ABSENT` / late admission skip | if present |
| Unified channel session | same trace across participants? |

### 3.5 Media readiness (downstream only — NOT generation authority)

| Evidence | Record |
|----------|--------|
| `transmitReady` | true / false at T1 |
| `meshIceConnectedPeers` | set per device |
| `meshMissingIce` | if present |

**Do NOT use `transmitReady=true` as sole Track C Core PASS.**

---

## 4. Verdict

### 4.1 Track C Core PASS (generation convergence)

**ALL** participating modules, by end of observation window:

```text
□ Authoritative generation knowledge converges
□ Same accepted Current generation (per channelId)
□ No permanent UNKNOWN generation knowledge
□ No permanent acquisition obligation OPEN
□ No stale-generation resurrection (fenced gen must not re-enter as Current)
```

If any box fails → **Track C Core: FAIL** (record failure domain §4.3).

### 4.2 Layered scores (record separately)

```text
Generation convergence:  PASS / FAIL / INCOMPLETE
Group bootstrap:         PASS / FAIL / INCOMPLETE
Media readiness:         PASS / FAIL / INCOMPLETE
```

Example valid decomposition:

```text
Generation convergence:  PASS
Group bootstrap:         PASS
Media readiness:         FAIL   (transmitReady=false — ICE domain)
```

This is **not** an ADR-0057 generation convergence failure unless Core (§4.1) fails.

### 4.3 Failure-domain classification (if Core or layer FAIL)

| Observation | Route |
|-------------|-------|
| No `GBC_EFFECT RequestFact` / no `FACT_REQUEST` | Acquisition trigger / orchestration gap |
| `FACT_REQUEST` but no `FACT_RESPONSE_*` | Delivery / holder availability gap |
| Candidate arrives, persistent `UNVERIFIABLE` | Production trust / provisioning evidence gap |
| `VERIFY_FAIL` / `INTEGRITY_ANOMALY` with good trust | Crypto / candidate integrity issue |
| Verified Fact but F1 does not converge | GBC semantic / consumption defect |
| GBC converged, `transmitReady=false` | Downstream bootstrap / ICE / media domain |
| Old generation resurrects after fence | F3 fence defect |
| Split `sessionTraceId` / stale lineage after **all** force-stopped | GROUP bootstrap session authority (pre-GBC or parallel) |

### 4.4 Final field adjudication

```text
FIELD PASS
  Track C Core PASS (§4.1)
  + evidence sufficient (§3)

FIELD FAIL
  Track C Core FAIL
  + failure domain (§4.3)
  + layered scores (§4.2)

INCOMPLETE
  Preconditions violated, missing logs, or observation window not met
  → no PASS/FAIL claim; re-run only
```

**ADR-0057 COMPLETE:** remains **NO** until Core PASS + governance acceptance.
Field Ceremony / Transport: **NOT CLAIMED** by this run.

---

## 5. ADJUDICATION.txt template

```text
ADR-0057 Track C Case A — Production Convergence Field Validation
Run: adr0057-track-c-case-a-<timestamp>
Build: <sha> / <version>
Observer:

PRECONDITIONS
  M01 anchor:       present|absent
  M01 trust state:  present|absent
  M02 anchor:       present|absent
  M02 trust state:  present|absent
  M03 anchor:       present|absent
  M03 trust state:  present|absent
  All force-stopped: yes|no
  NO Meeting:       yes|no

OBSERVATION WINDOW
  T0 force-stop:  <time>
  T1 last Talk:   <time>
  T2 window end:  <time>  (duration: __ min)

GENERATION (per device / channelId)
  M01 Current:     <gen|none>
  M02 Current:     <gen|none>
  M03 Current:     <gen|none>
  Converged same:  yes|no
  Obligation:      OPEN|CLOSED per device
  Permanent UNKNOWN: yes|no
  Stale resurrection: yes|no

WIRE / VERIFY
  FACT_REQUEST observed:     yes|no  (devices: ...)
  FACT_RESPONSE observed:    yes|no
  First verification:        SUCCESS|UNVERIFIABLE|...
  Late verification:         <if applicable>

GROUP BOOTSTRAP
  sessionTraceId aligned:    yes|no
  waitingForPrimary cleared: yes|no

MEDIA (downstream)
  transmitReady @ T2:        yes|no

SCORES
  Generation convergence:  PASS|FAIL|INCOMPLETE
  Group bootstrap:         PASS|FAIL|INCOMPLETE
  Media readiness:         PASS|FAIL|INCOMPLETE

VERDICT
  Track C Core:              PASS|FAIL|INCOMPLETE
  Failure domain (if FAIL):  <§4.3 class>
  ADR-0057 COMPLETE:         NO
```

---

## 6. Dependency / freeze record

```text
Implementation chain FROZEN through Production Verifier Wiring CLOSED / PASS
Production Trust Integration Implementation Conformance: PASS

This run card does NOT reopen:
  PAP / OE / Layer B / PW contracts
  GBC convergence predicate
  Completion admission / RNA tracks

Next after field:
  Adjudicate ADJUDICATION.txt
  If Core PASS → integrated production regression note + governance review
  If Core FAIL → route per §4.3 (no casual patch)
```

**Related:** [GROUP Bootstrap Convergence Contract](../adr/0057-group-bootstrap-convergence-contract.md) · [RCA 2026-08-26](./group-bootstrap-primary-convergence-rca-20260826.md) · [PW Narrow Authorization](../adr/0057-production-verifier-wiring-narrow-authorization.md)
