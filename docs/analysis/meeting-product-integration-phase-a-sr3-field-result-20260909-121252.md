# PA-SR3 Field Run — FAIL / RCA Handoff (SOURCE origin gate)

**LogDir:** `talkback/logs/phase-a-sr3-field-20260909-121252/`  
**Stamp:** 2026-09-09-121252  
**Build:** `cac628d` + PR-PA-SR-B1 (local)  
**Devices:** M01 `HTUBB21B09220661` · M02 `DSJ-2407041`  
**Adjudicator:** `phase-a-sr3-field-adjudicate.ps1`

---

## Verdict

```text
VERDICT=FAIL_PA_SR3
FIRST_RUN_EXIT=1
PA-SR3=FAIL
PR-PA-SR-B1=UNIT-PROVEN_FIELD_FAIL

PRE-G / PRE-I / PRE-J   PASS
J0 prerequisite         APPLIED (peer CREATION factType=1)
PA-SR4…SR6              OBSERVE ONLY (not closed)

J0_status               UNCHANGED_CLOSED
Profile01_Meeting_chain UNCHANGED_CLOSED
B0 / A1                 UNCHANGED
ADR-0056                KEEP
```

**Not INVALID** — preconditions green; product SOURCE chain tested and broken.  
**Not** J0 / P1-C / B0 regression.

**OBS v1 limitation (recorded, non-blocking):** `ssrc` / `sourceInstanceId` not in field OBS; correlation would use `factDigest` + `sourceGeneration` if EMITTED had occurred. This run never reached that stage.

---

## Frozen chain score

| Step | Result | Evidence |
|------|--------|----------|
| M01 SOURCE self-origin | **FAIL** | `ORIGIN_SOURCE_GAP reason=MEMBERSHIP_NOT_CONVERGED` then `NO_BUILT_SOURCE` |
| factType=9 EMITTED | **FAIL** | zero `outcome=EMITTED` / `BUILT` for factType=9 |
| M02 factType=9 APPLIED | **FAIL** | no wire SOURCE datagram |
| registry.member | **FAIL** | proxy: `MEMBER_MEDIA_READY … DEFERRED_NO_BINDING` |
| MEMBER_MEDIA_READY(M01) APPLIED | **FAIL** | M02: `module=M01 outcome=DEFERRED_NO_BINDING` |

Pre-bind republication path **not reached** (no factType=9 send).

---

## Authoritative timeline

### M01 host (SOURCE origin attempt)

```text
12:16:44.372  PROFILE01_ORIGIN_EMIT factType=1 EMITTED conferenceId=8d640af3… members=1
12:16:44.635  PROFILE01_ORIGIN_EMIT factType=11 EMITTED target=M02
12:16:44.638  PROFILE01_ORIGIN_EMIT factType=9 moduleId=M01 ORIGIN_SOURCE_GAP reason=MEMBERSHIP_NOT_CONVERGED
12:16:44.639  PROFILE01_ORIGIN_EMIT factType=9 moduleId=M01 ORIGIN_SOURCE_GAP reason=NO_BUILT_SOURCE
12:16:56.003  SHADOW MEMBER_MEDIA_READY module=M02 DEFERRED_NO_BINDING
```

Host never emits factType=9. Local `membershipConvergence` is not seeded by host CREATION origin (only by CREATION *ingress*).

### M02 peer (supplement-ready race)

```text
12:16:56.690  PROFILE01_MEDIA_SUPPLEMENT state=READY
12:16:56.695  PROFILE01_ORIGIN_EMIT factType=9 moduleId=M02 ORIGIN_SOURCE_GAP reason=NOT_IN_ROSTER
12:16:56.732  WIRE_INGRESS factType=1 outcome=APPLIED   ← AFTER SOURCE attempt
12:16:56.801  SHADOW SESSION_STARTED APPLIED
12:16:56.864  SHADOW MEMBER_MEDIA_READY module=M01 DEFERRED_NO_BINDING
```

Peer SOURCE attempt fires from `onMediaSupplementReady` **before** incomplete CREATION drain seeds roster → `NOT_IN_ROSTER`. No later SOURCE retry after CREATION APPLIED.

---

## RCA cut (SOURCE origin / apply chain only)

```text
TYPE: SOURCE origin gate / ordering gap (PR-PA-SR-B1 field)

Host:
  CREATION origin emits signed fact
  → SOURCE origin reads membershipConvergence.current()
  → null (host does not ingress-apply own CREATION)
  → MEMBERSHIP_NOT_CONVERGED → no BUILT / EMITTED

Peer:
  package decrypt → SUPPLEMENT READY → onMediaSupplementReady
  → SOURCE origin before CREATION APPLIED drain
  → NOT_IN_ROSTER
  → no retry after CREATION APPLIED

Downstream (expected):
  no factType=9 wire → no registry.member → DEFERRED_NO_BINDING
```

**Out of this RCA:** PA-SR4 ingress/playout · leave/rejoin · teardown · B0 fence · J0 / P1-C / P1-E · ADR-0056.

---

## Status board

```text
PR-PA-SR-B1     CLOSED / UNIT-PROVEN · FIELD FAIL (origin gate)
B1-R1           CLOSED / UNIT-PROVEN
PA-SR3          AUTHORIZED FOR FIELD RE-RUN
PA-SR4…SR6      HOLD (observe-only this run)
```

## Next (frozen)

1. B1-R1 unit closure: `meeting-product-integration-phase-a-shadow-runtime-pr-pa-sr-b1-r1-unit-closure.md`
2. Re-run `phase-a-sr3-field-first-run.ps1` (PA-SR3 still VALID FAIL until field PASS).
3. Do **not** authorize manual replay / pre-bind retention / SR4 work.

---

## Artifacts

- `ADJUDICATION.txt` · `PA_SR3_IDENTITY.txt` · `PA_SR3_OBSERVE_ONLY.txt`
- `SHADOW_RUNTIME_MANIFEST.txt` · `hostSessionId=af82f3d7-6a3b-49ad-97e8-1a9b7354a60b`
- `conferenceId=8d640af31f95133fe55834a402d24636`
