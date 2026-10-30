# PR-PA-SR4-TX-A1 — Unit Closure

**Authorization:** `meeting-product-integration-phase-a-shadow-runtime-pr-pa-sr4-tx-a1-host-local-supplement-materialization-authorization.md`  
**RCA:** `meeting-product-integration-phase-a-sr4-tx-rca-host-projection-20260909-164833.md`  
**Status:** **CLOSED / UNIT-PROVEN**

---

## Scope delivered

| Slice | Implementation | Notes |
|-------|----------------|-------|
| TX-A1 | `Profile01HostLocalMediaSupplementMaterializer` | Authority → `Profile01DerivedMediaKeyMaterial` via `Profile01PackageCrypto` |
| Registry | `Profile01SessionMediaSupplementRegistry.put` | Same downstream fact as peer decrypt |
| Continuation | `Profile01ConferenceMediaFactIngress.notifySupplementReady` | Reused by peer decrypt + host materialize |
| Wiring | `MeetingProfile01CreationOriginBridge` post-`ensureMaterial` | Failures log-only; CREATION origin unchanged on reject |
| Factory | `TalkbackRuntimeFactory` | Materializer → ingress `notifySupplementReady` → coordinator hook |
| OBS | `PROFILE01_HOST_LOCAL_SUPPLEMENT outcome=READY\|ALREADY_PRESENT\|STALE_IDENTITY\|DERIVE_FAILED\|NO_AUTHORITY_MATERIAL` | Behavior-neutral |

**Not changed:** HLSP supplement lookup path · TX-B/TX-3/TX-4 · peer P1-D decrypt · PACKAGE emit · ADR-0056.

---

## Hard constraints (unit-proven)

```text
A1-HC1  Authority-only — no host self-decrypt of MEDIA_KEY_PACKAGE
A1-HC2  Profile01PackageCrypto derivation — same semantic as decrypt path
A1-HC3  conferenceId binding + commitment fence — STALE_IDENTITY on mismatch
A1-HC4  Duplicate materialize → ALREADY_PRESENT + idempotent HLSP ALREADY_LIVE
```

---

## Unit tests (`Profile01HostLocalMediaSupplementMaterializerTest`)

| ID | Scenario | Result |
|----|----------|--------|
| A1-U1 | Host material → registry hit → HLSP `APPLIED` | PASS |
| A1-U1b | Derived fields match `Profile01PackageCrypto` | PASS |
| A1-U2 | No supplement → `DEFERRED`; after materialize + registry → `APPLIED` | PASS |
| A1-U3 | Conference binding / commitment mismatch → reject, registry unchanged | PASS |
| A1-U4 | Duplicate materialize → `ALREADY_PRESENT`; HLSP idempotent | PASS |
| A1-U5 | Peer `ingestMediaKeyPackageSignedFact` unchanged (P1D harness) | PASS |

**TX regression (`Profile01ShadowSr4TxTest` TX-U1…U8):** PASS.

---

## Key paths

```text
talkback/android-board-talkback/src/main/java/.../integration/
  Profile01HostLocalMediaSupplementMaterializer.kt
  MeetingProfile01CreationOriginBridge.kt   (post-emit materialize hook)

talkback/android-board-talkback/src/main/java/.../profile01/
  Profile01ConferenceMediaFactIngress.kt  (notifySupplementReady)

talkback/android-board-talkback/src/main/java/com/talkback/app/
  TalkbackRuntimeFactory.kt

talkback/android-board-talkback/src/test/java/.../
  Profile01HostLocalMediaSupplementMaterializerTest.kt
```

---

## Status board

```text
TX-A1            CLOSED / UNIT-PROVEN
PR-PA-SR4-TX     CLOSED / UNIT+FIELD-PROVEN
PA-SR4           VALID FAIL / RX NEXT
PR-PA-SR4-RX     DESIGN NEXT / NOT AUTHORIZED
PLAY             HOLD
ADR-0056         KEEP
```

**Field run (20260909-171232):** `PASS_PA_SR4_TX` — `talkback/logs/phase-a-sr4-tx-field-20260909-171232/`

**Next:** PR-PA-SR4-RX design + authorization (separate).
