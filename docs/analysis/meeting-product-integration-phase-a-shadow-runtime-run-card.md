# Meeting Product Integration — Phase A Shadow Runtime Run Card

**Parent authorization:** `meeting-product-integration-phase-a-shadow-runtime-gate-authorization.md`  
**Shadow design:** `meeting-product-integration-phase-a-shadow-run-card.md`  
**Status:** **VALID FAIL / RCA OPEN** (2026-09-09) — first field `20260909-105513` · RCA: `meeting-product-integration-phase-a-shadow-runtime-rca-handoff.md`

```text
Meeting J0               CLOSED / FIELD-PROVEN
Profile01 Meeting chain  CLOSED / FIELD-PROVEN
Phase A Shadow Runtime   VALID FAIL / RCA OPEN
PA-SR1…SR6               NOT CLOSED
Phase A (full)           BLOCKED pending Shadow Runtime
ADR-0056                 KEEP
Meeting media cutover    NOT AUTHORIZED
```

---

## 1. Question this gate answers

After J0 proves Profile01 control chain, does the **shadow media runtime** operate correctly for the full Meeting lifecycle while ADR-0056 remains user-audible?

```text
create/join → CREATION APPLIED (prereq)
→ shadow SESSION_STARTED / wiring live
→ admitted sources on wire
→ ingress + shadow playout metrics
→ member leave / rejoin / end → clean teardown
```

---

## 2. Prerequisites (hard)

| Prereq | Status |
|--------|--------|
| Meeting J0 field PASS | **CLOSED** (`20260909-103707`) |
| EP provisioning / establishment trust | reuse J0 / P1-C baseline |
| `MeetingProductMediaShadow.enabled=true` | product default Phase A |
| Same topology M01 host · M02 peer | frozen |

**Reuse harness:** PRE-G / PRE-I / PRE-J from J0 run (frozen) — GROUP clear · invite join · same-session bind.

**Do NOT re-adjudicate:** P1-C package leg · P1-E continuation · digest fence (J0 closed).

---

## 3. Frozen positive chain (PA-SR1…PA-SR6)

```text
PA-SR1  PRE-G/I/J PASS + Profile01 CREATION APPLIED
        → SHADOW hook=SESSION_STARTED outcome=APPLIED

PA-SR2  Wiring becomes live
        → PROFILE01_SHADOW_RUNTIME phase=SHADOW_SESSION_STARTED|WIRING_SESSION_STARTED
        → transportScopeActive=true · identity keys aligned

PA-SR3  MEMBER_MEDIA_READY → real admitted remote source(s)
        → SHADOW hook=MEMBER_MEDIA_READY outcome=APPLIED
        → catalog/admittedCount >= 1

PA-SR4  Real shadow ingress + playout runtime (gate-critical)
        → PROFILE01_SHADOW_RUNTIME phase=INGRESS_ACTIVITY (packets/ingress > 0; jitter/decoders active)
        → PROFILE01_SHADOW_RUNTIME phase=PLAYOUT_ACTIVITY (successfulPlayoutWrites > 0)
        → audioTrackOwner=SHADOW_METRICS_ONLY
        → NOT sufficient: SESSION_STARTED / catalog non-empty alone

PA-SR5  Leave → rejoin
        → MEMBER_REMOVED APPLIED + WIRING_MEMBER_REVOKED
        → replacement MEMBER_MEDIA_READY APPLIED (no stale generation ingress)

PA-SR6  Meeting end
        → SESSION_STOPPED APPLIED
        → TEARDOWN_EMPTY (transport/catalog/admitted/jitter/decoders all zero)
        → block ingress → revoke/drain → stop playout → retire epoch → endScope → assert empty
```

Hard fence: shadow must not own production AudioTrack.
`AUDIO_TRACK_OWNERSHIP owner=PRODUCTION_ACTIVE` = ADR-0056 expected owner (not FAIL).
FAIL only if shadow `PLAYOUT_ACTIVITY`/`INGRESS_ACTIVITY` claims `audioTrackOwner=PRODUCTION*`.

---

## 4. Identity keys

```text
sessionId
conferenceId
mediaKeyEpoch
peerModuleId
```

Shadow events must correlate to J0 `hostSessionId` / `conferenceId` — not time-only stitching.

---

## 5. Observability (v1)

| Area | Tag / pattern |
|------|----------------|
| Profile01 wire | `MEETING_MEDIA_SHADOW` · `WIRE_INGRESS` |
| Shadow hooks | `SHADOW hook=SESSION_STARTED` … `outcome=APPLIED` |
| Shadow failures | `SHADOW_FAILED` — must not propagate to Meeting |
| Playout seam | `RecordingPlayoutMetricsSeam` metrics (no AudioTrack) |
| ADR-0056 isolation | no shadow AudioTrack; Meeting talk path unchanged |

Evidence scope: `MEETING_PRODUCT_SHADOW_PHASE_A`

---

## 6. Verdict model

| Verdict | When |
|---------|------|
| **PASS** | PRE-G/I/J PASS · PA-SR1…PA-SR6 PASS · no forbidden cutover |
| **INVALID_*** | GROUP / invite / capture / harness inject / missing OBS |
| **FAIL_SHADOW_RUNTIME** | PRE green · shadow/runtime chain break · Meeting may still be OK |

PRE failure → `SHADOW_RUNTIME=NOT_TESTED` — do not blame J0 / Profile01 chain.

---

## 7. Hard prohibitions

```text
Harness-injected Profile01 facts
Manual shadow hook triggers
Opening AudioTrack on shadow path
ADR-0056 bypass or deletion
Claiming Phase A complete or cutover eligible on PASS
Re-opening J0 / P1-C / P1-E contracts on shadow FAIL
```

---

## 8. Run sequence (operator outline)

```text
1. Reuse J0 first-run harness via `talkback/scripts/phase-a-shadow-runtime-first-run.ps1`
2. Host CREATE → peer JOIN (same as J0)
3. Wait CREATION APPLIED (should follow J0-class chain automatically)
4. Hold Meeting active — verify shadow SESSION_STARTED + MEMBER_MEDIA_READY APPLIED
5. Talk / PTT window — verify ingress + playout metrics on shadow path
6. Peer leave OR host end — verify MEMBER_REMOVED / SESSION_STOPPED + A4 clean
7. Stop logcat → adjudicate
```

Log dir (proposed): `talkback/logs/phase-a-shadow-runtime-<stamp>/`

---

## 9. After PASS

```text
Phase A Shadow Runtime     CLOSED / FIELD-PROVEN
Phase A (A1–A4 rollup)     ELIGIBLE FOR NEXT GATE
Meeting media cutover      NOT AUTHORIZED
ADR-0056                   KEEP
```

**FAIL** → RCA inside shadow runtime / wiring / ingress only.  
**INVALID** → fix harness / capture.

---

## 10. References

- Authorization: `meeting-product-integration-phase-a-shadow-runtime-gate-authorization.md`
- J0 evidence: `talkback/logs/j0-meeting-operational-20260909-103707/`
- Shadow boundaries: `meeting-product-integration-phase-a-shadow-run-card.md`
- J0 harness: `talkback/scripts/j0-meeting-operational-first-run.ps1`
