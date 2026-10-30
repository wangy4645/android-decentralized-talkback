# RCA-B Confirmation Authorization-001

**ID:** conference-srd-rca-b-confirmation-authorization-001  
**Date:** 2026-08-21  
**Type:** Implementation Authorization · Read-Only Observability  
**Status:** **COMPLETE** (field adjudicated · RCA-B CLOSED)

**Parent RCA:** [conference-srd-post-admission-native-execution-failure-rca.md](./conference-srd-post-admission-native-execution-failure-rca.md) (v0.2 · RCA-B **CLOSED**)  
**Prerequisite:** [RCA-OBS-001](./conference-srd-post-admission-native-execution-rca-obs-001-authorization.md) **PASS** (session `88e22b07`)  
**Evidence:** [rca-b-confirm-srd-native-20260821-133115/ADJUDICATION.txt](../../logs/rca-b-confirm-srd-native-20260821-133115/ADJUDICATION.txt) · session `5ce616de-…`

---

## Goal (single sentence)

> Determine whether SRD native invocation **returns** before watchdog expiry.

**Not:** fix SRD · change timeout · adjust mutex · change WebRTC · retry · ICE restart · Recovery · Ready · Topology · executor redesign.

---

## Authorized scope (read-only)

Wrap the actual `setRemoteDescription` JNI invocation (inside `sdpApplyMutex`):

```text
SRD_NATIVE_CALL_ENTER
SRD_NATIVE_CALL_EXIT   elapsedMs=
```

Minimum fields (correlate with existing chain):

```text
sessionId
edgeKey
pcHash
thread / tid
elapsedMs   (EXIT only)
```

Extend `SRD_WATCHDOG_GAP` / attempt tracking to distinguish:

| Observation | Adjudication |
|-------------|--------------|
| `ENTER` without `EXIT` | RCA-B **CLOSED** — native invocation non-return under mutex |
| `ENTER` + `EXIT`, no `JNI_RETURN` | RCA-B **not成立** — Java/JNI completion gap |
| `EXIT` elapsedMs > watchdog budget | Different RCA — latency exceeds watchdog contract |

---

## Explicitly not authorized

```text
SRD timeout change
sdpApplyMutex scope change
retry / rollback policy
ICE restart
Recovery / Ready / Topology / Anchor
executor / observation lane changes
M04 independent investigation
```

**M04:** remains **derived candidate** only.

---

## Field acceptance

Clean 4p · M01 anchor · one session.

**Must reconstruct M03 chain:**

```text
SRD_ENTER
SRD_MUTEX_ACQUIRED
SRD_NATIVE_CALL_ENTER
    ?
SRD_NATIVE_CALL_EXIT
SRD_JNI_RETURN
SRD_CALLBACK_ENTER
SRD_EXIT
```

**M02:** full chain through `SRD_NATIVE_CALL_EXIT` + `JNI_RETURN` (reference).

---

## Posture

```text
RCA-B candidate          FROZEN (HIGH confidence pre-confirmation)
This authorization       OPENED — read-only only
P0.1g Acceptance 1       HOLD
IA-002 / OBS-001         PASS / CLOSED
```

---

## State diagram

```text
RCA-B candidate FROZEN
        |
        v
RCA-B Confirmation Auth-001  ← this document
        |
        v
Field capture + adjudication
        |
        v
RCA-B CLOSED | alternate RCA (latency / Java gap)
```
