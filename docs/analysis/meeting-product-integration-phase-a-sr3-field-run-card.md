# Phase A Shadow Runtime — PA-SR3 Field Run Card (PR-PA-SR-B1)

**Parent:** `meeting-product-integration-phase-a-shadow-runtime-gate-authorization.md`  
**B1 authorization:** `meeting-product-integration-phase-a-shadow-runtime-pr-pa-sr-b1-implementation-authorization.md`  
**B1 design:** `meeting-product-integration-phase-a-shadow-runtime-rca-b-fix-design.md`  
**Status:** **AUTHORIZED FOR FIELD** (post B1 unit closure)

```text
PR-PA-SR-B1     CLOSED / UNIT-PROVEN
PA-SR3          AUTHORIZED FOR FIELD (this run card)
PA-SR4…SR6      OBSERVE ONLY — not closed on this run
ADR-0056        KEEP
cutover         NOT AUTHORIZED
```

---

## 1. Question this gate answers

After B1 SOURCE origin lands in product, does the **SOURCE → apply → registry binding → MEMBER_MEDIA_READY** chain work on a real Meeting session?

**Authoritative PASS chain (this run only):**

```text
local SOURCE committed
→ PROFILE01_ORIGIN_EMIT factType=9 outcome=EMITTED
→ peer WIRE_INGRESS factType=9 outcome=APPLIED
→ registry.member exists (proxy: memberBinding non-null at hook)
→ SHADOW hook=MEMBER_MEDIA_READY outcome=APPLIED
```

**Not in scope for this run:** PA-SR4 ingress/playout · PA-SR5 leave/rejoin · PA-SR6 teardown · full Shadow Runtime rollup.

---

## 2. Hard boundaries (non-negotiable)

| # | Boundary |
|---|----------|
| **1** | **PRE-G / PRE-I / PRE-J + J0 prerequisite** must all PASS. Any PRE failure → `INVALID_*` · `PA-SR3=NOT_TESTED`. |
| **2** | **PA-SR4…SR6** — capture and record in field result doc; **do not adjudicate PASS/FAIL** on this run. |
| **3** | If `factType=9` hits **pre-bind drop** (`PRE_BIND_NOT_RETAINED`), PASS requires **automatic post-bind byte-identical republication** (`PROFILE01_ORIGIN_EMIT` with same `sourceGeneration` + `factDigest`). **No manual replay / harness inject.** |

---

## 3. Prerequisites

| Prereq | Requirement |
|--------|-------------|
| PR-PA-SR-B1 unit | B1-1…B1-7 PASS (see unit closure doc) |
| Meeting J0 | CLOSED / FIELD-PROVEN (`20260909-103707`) |
| B0 fence | CLOSED / PRE-AUTH = A |
| Topology | M01 host · M02 peer (frozen) |
| `MeetingProductMediaShadow.enabled` | true (product default Phase A) |
| Build | includes PR-PA-SR-B1 origin wiring |

**Reuse:** PRE-G / PRE-I / PRE-J harness from J0 / Shadow Runtime driver — no new orchestration.

---

## 4. Primary witness direction (frozen)

Host-create + peer-join — **authoritative direction for PA-SR3 PASS:**

```text
M01 (host)  self-origin SOURCE  →  fanout to M02
M02 (peer)  WIRE_INGRESS factType=9 APPLIED
M02 (peer)  MEMBER_MEDIA_READY module=M01 outcome=APPLIED
```

M02→M01 self-origin is **encouraged evidence** but **not required** for PA-SR3 closure on this run.

---

## 5. Identity correlation keys

Adjudicator must join the chain on these keys (not time-only stitching):

| Key | Source |
|-----|--------|
| `conferenceId` | `SHADOW_RUNTIME_MANIFEST` · `PROFILE01_ORIGIN_EMIT` |
| `sessionId` (`hostSessionId`) | `JOIN_GATE` / manifest |
| `senderModuleId` | `PROFILE01_ORIGIN_EMIT … moduleId=` |
| `sourceGeneration` | `PROFILE01_ORIGIN_EMIT … sourceGeneration=` |
| `factDigest` | `PROFILE01_ORIGIN_EMIT … factDigest=` (republication anchor) |
| `membershipVersion` | manifest / CREATION context (`membershipVersion=0` at origin unless membership advanced) |
| `mediaKeyEpoch` | manifest |
| `ssrc` | **OBS v1 gap** — correlate via `factDigest` + `sourceGeneration` surrogate if absent |
| `sourceInstanceId` | **OBS v1 gap** — correlate via `factDigest` surrogate if absent |

**Stale-binding guard:** `MEMBER_MEDIA_READY APPLIED` must occur **after** peer `WIRE_INGRESS factType=9 APPLIED` for the **same** `senderModuleId` + `sourceGeneration` (+ matching `factDigest` when present). Earlier `DEFERRED_NO_BINDING` for the same module is expected until SOURCE apply; APPLIED after a prior DEFERRED without intervening SOURCE APPLIED → `FAIL_PA_SR3`.

---

## 6. Observability patterns

| Step | Pattern |
|------|---------|
| Local commit + build | `PROFILE01_ORIGIN_EMIT … factType=9 … outcome=BUILT` |
| Fanout | `PROFILE01_ORIGIN_EMIT … factType=9 … outcome=EMITTED target=<peer>` |
| Coordinator | `PROFILE01_SOURCE_ORIGIN reason=… target=<peer>` |
| Pre-bind drop | `PRE_BIND_NOT_RETAINED … factType=9 reason=UNAUTHORIZED_FACT_TYPE` |
| Peer apply | `WIRE_INGRESS session=<H> factType=9 outcome=APPLIED` |
| Registry proxy | `SHADOW hook=MEMBER_MEDIA_READY session=<H> module=<sender> outcome=APPLIED` |
| Install snapshot | `PROFILE01_SHADOW_RUNTIME phase=SHADOW_MEMBER_INSTALLED … moduleId=<sender>` |

Tag: `MEETING_MEDIA_SHADOW`

---

## 7. Verdict model (three-state)

| Verdict | When |
|---------|------|
| **PASS** | PRE-G/I/J PASS · J0 prereq green · PA-SR3 chain PASS · republication rule satisfied if pre-bind drop seen |
| **INVALID_*** | PRE/capture/driver/harness insufficiency → `PA-SR3=NOT_TESTED` |
| **FAIL_PA_SR3** | PRE green · SOURCE origin/apply/binding/hook chain broken |

```text
FAIL_PA_SR3 ≠ J0 regression
FAIL_PA_SR3 ≠ PA-SR4 failure
```

---

## 8. On PASS — status board

```text
PR-PA-SR-B1     CLOSED / UNIT+FIELD-PROVEN
PA-SR3          CLOSED / FIELD-PROVEN
PA-SR4…SR6      NEXT / still not closed
ADR-0056        KEEP
cutover         NOT AUTHORIZED
```

---

## 9. Run sequence (operator)

```text
1. talkback/scripts/phase-a-sr3-field-first-run.ps1
   (reuses Shadow Runtime driver — same PRE-G/I/J + observation window)
2. Do NOT inject facts · do NOT manual CONFERENCE_SIGNED_FACT replay
3. Hold meeting active through ICE/media-ready window (≥45s observation)
4. Stop logcat → adjudicator runs automatically
5. Record PA-SR4…SR6 lines in field result doc (observe only)
```

**Log dir:** `talkback/logs/phase-a-sr3-field-<stamp>/`

---

## 10. Hard prohibitions

```text
Harness-injected SOURCE facts
Manual shadow hook triggers
Manual CONFERENCE_SIGNED_FACT replay for factType=9
SOURCE pre-bind retention workaround
Closing PA-SR4…SR6 on this run
ADR-0056 bypass / cutover claims
Re-opening J0 / P1-C / P1-E on FAIL_PA_SR3
```

---

## 11. Scripts

| Script | Role |
|--------|------|
| `talkback/scripts/phase-a-sr3-field-first-run.ps1` | Thin entry → Shadow Runtime driver (`DriverMode=PaSr3`) |
| `talkback/scripts/phase-a-sr3-field-adjudicate.ps1` | PA-SR3-only adjudicator |
| `talkback/scripts/p1c-package-field-first-run.ps1` | Shared orchestration (PRE-G/I/J) |

**Criteria:** `meeting-product-integration-phase-a-sr3-adjudication-criteria.md`

---

## 12. References

- B1 unit closure: `meeting-product-integration-phase-a-shadow-runtime-pr-pa-sr-b1-unit-closure.md`
- Prior fail baseline: `talkback/logs/phase-a-shadow-runtime-20260909-111359/`
- RCA-B handoff: `meeting-product-integration-phase-a-shadow-runtime-rca-b-handoff.md`
