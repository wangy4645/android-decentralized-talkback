# Post-ADR-0057 — Production Case A Smoke Run Card

## Status

**REGRESSION SMOKE** (post-P0; not investigation)

```text
ADR-0057:                COMPLETE / CLOSED / FROZEN
P0.1 / P0.2:             PASS / CLOSED
Production smoke (auth): PASS — run 20260902-161608
Role:                    version-change regression gate only
```

**Authoritative run:** `logs/adr0057-production-smoke-20260902-161608/`

**Parent:** [P0 engineering closure](./adr0057-p0-engineering-closure.md) · [ADR-0057 complete closure](./adr0057-complete-closure.md)

---

## Purpose

**Regression smoke** after WebRTC / GROUP bootstrap artifact changes — confirms production AAR still yields triad transport + readiness. **Not** ADR-0057 re-validation or F-chain investigation.

```text
ADR-0057 field validation (inst):  CLOSED — run 152328
Production smoke (prod AAR):       CLOSED — run 20260902-161608
Future runs:                     REGRESSION ONLY
```

---

## Artifact gate (hard)

```text
Artifact: production WebRTC AAR
NOT:      pnsrd-inst-1 instrumentation AAR

Gradle: android-board-talkback must use stream-webrtc-android:1.3.10
        (no libs/pnsrd-inst-1-stream-webrtc-android.aar override)

.so verification: no F8_NATIVE_* / pnsrd-inst investigation strings
```

---

## Scenario

Same as ADR-0057 Case A field validation:

| Item | Value |
|------|-------|
| Topology | MANET · M01/M02/M03 |
| M04 | force-stopped / absent |
| Procedure | cold start M01→M02→M03, Talk page, idle ~5 min |
| Script | `scripts/adr0057-track-c-case-a-start-run.ps1` (observe without F8 inst tags required) |

---

## PASS gates

All required for PASS:

```text
G1  M02/M03: concrete LAN host candidates (192.168.10.x GENERATED or equivalent)
G2  Required triad ICE edges: ICE_CONNECTED
      M01↔M02 · M01↔M03 · M02↔M03
G3  M01/M02/M03: transmitReady=true with joinedMembers=M01,M02,M03
G4  M01/M02/M03: meshRecoveryState=OPERATIONAL (triad)
G5  Post-triad stability (~5 min after last device reaches triad OPERATIONAL):
      no sustained transmitReady=false (triad)
      no ICE terminal failure on required edges
      no acquisition obligation reopen
```

Bounded bootstrap transients (CHECKING, brief BUILDING) **do not** void PASS per ADR-0057 convergence contract.

---

## FAIL routing

```text
FAIL → post-ADR-0057 production-artifact integration incident

Never automatically:
  reopen ADR-0057
  modify F8-FIX-1 contract
  open F9
```

---

## Observe (minimal — do not expand)

```text
GROUP_INVITE · GROUP_ACCEPT · IGIE_
ICE_CONNECTED · ICE_TRANSPORT_CANDIDATE
transmitReady · GROUP_TRANSITION_READINESS_SNAPSHOT
GENERATED (LAN host)   # auxiliary only
```

**Forbidden:** add `F8_*` observe tags, native trace, or new F10/F11 investigation hooks for regression runs.

**Scripts:** `scripts/adr0057-production-smoke-*.ps1` (start / cold-start / stop / g1-verify / adjudicate)

---

## Adjudication output

```text
logs/adr0057-production-smoke-<stamp>/
  PRODUCTION_SMOKE_AUDIT.txt
  PRODUCTION_SMOKE_ADJUDICATION.txt
```

Verdict: **PASS** → integration + smoke COMPLETE · **FAIL** → integration incident
