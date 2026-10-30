# R2 — Release Smoke Run Card

**Status:** **ACTIVE** (2026-09-15)  
**Prerequisite:** R1 implementation **COMPLETE** · cutover unit tests **PASS**  
**Blocks:** RELEASE CANDIDATE

```text
R1 implementation     COMPLETE
R2-A field (release)  PENDING — requires 3-node real mesh
R2-B kill-switch      PASS (unit/debug focused evidence)
```

---

## Goal

Final release integration gate before **RELEASE CANDIDATE**:

> **Normal user Meeting flow, no adb/debug cutover assistance, automatically enters `MULTICAST_ACTIVE`.**

```text
R2-A release field PASS        REQUIRED
R2-B kill-switch behavior PASS REQUIRED
  └─ unit/debug focused evidence acceptable on release build
```

**Not in scope:** extra manual release round · G1/G2/G3 rerun · long soak · new architecture gate · exposing debug receiver in release.

---

## Devices

| Module | Serial | Role |
|--------|--------|------|
| M01 | `HTUBB21B09220661` | Host |
| M02 | `DSJ-2407041` | Peer |
| M03 (DUT) | `MDX0220416001963` | Third peer |

Alternate third peer (establishment module **M04**): `-ThirdPeer M04 -M04Serial <wireless>` when M03 unavailable.

**Preflight:** 3 nodes online · same real underlay L3 (`wlan0` IPv4) · establishment trust present on M01/M02/M03.

---

## R2-A — GA Default Path

```text
release build
NO adb cutover broadcast

3-node normal Meeting
→ SESSION_STARTED
→ readiness satisfied
→ AUTO_CUTOVER_SUCCEEDED
→ MULTICAST_ACTIVE
→ real bidirectional / multi-source audio
→ 1–2 min soak
→ normal End
→ SESSION_STOPPED
→ TEARDOWN_EMPTY

Expected: PASS
```

### Driver

- **Script:** `talkback/scripts/meeting-product-r2-release-smoke-field-first-run.ps1`
- **Driver mode:** `PaR2` (via `p1c-package-field-first-run.ps1`)
- **Build:** `assembleRelease` + install on M01/M02/M03
- **Soak:** default 90s (min adjudication 60s)

### Adjudication signals (M02 DUT logs)

| Check | Pattern / gate |
|-------|----------------|
| Shadow session | `PROFILE01_SHADOW_RUNTIME … SHADOW_SESSION_STARTED` |
| Auto cutover | `RC1_FIELD outcome=AUTO_CUTOVER_SUCCEEDED` |
| Ownership | `AUDIBLE_OWNERSHIP_STATE … MULTICAST_ACTIVE` |
| Production | playout writes / `obsProductionWrites=observed` |
| Multi-source | M01 + M03 ingress applied |
| Teardown | `SESSION_STOPPED` + `TEARDOWN_EMPTY` (M01 + M02) |
| **Must NOT** | `PILOT_ENABLED` · dual owner · `AUTO_ROLLBACK_SUCCEEDED` |

**Exit:** `R2A_RESULT=PASS` in `ADJUDICATION.txt` · script exit 0.

---

## R2-B — Kill-switch

```text
multicastAudibleEnabled=false
→ normal Meeting
→ shadow runtime remains active
→ ownership remains ANCHOR_ACTIVE
→ NO AUTO_CUTOVER_SUCCEEDED
→ NO MULTICAST_ACTIVE
→ Anchor audio works
→ normal End

Expected: PASS
```

### Evidence path (release build)

Release APK **does not** register debug broadcast for kill-switch. **Do not** expose debug receiver in release for R2-B.

| Evidence | Acceptable |
|----------|------------|
| `MulticastAudibleAutomaticCutoverTest` | **YES** |
| `AudibleOwnershipControllerTest.cutover_rejectsWhenMulticastAudibleDisabled` | **YES** |
| Release field + adb broadcast | **NO** |

R2-B is executed automatically by the R2 orchestrator after R2-A adjudication.

---

## One-command execution

From `talkback/scripts`:

```powershell
.\meeting-product-r2-release-smoke-field-first-run.ps1 -SoakSec 90 -PreferHeadsetPlayback
```

Options:

| Flag | Use |
|------|-----|
| `-SkipBuildInstall` | Re-run field only (release APK already on devices) |
| `-SkipServiceColdStart` | Preserve warm services between iterations |
| `-ThirdPeer M04` | Alternate third peer when M03 offline |

Logs: `talkback/logs/ga-r2-release-smoke-field-<timestamp>/`

Artifacts: `RUN_META.txt` · `preflight.json` · `ADJUDICATION.txt` · `R2A_RESULT.txt` · `R2B_RESULT.txt` · `R2_RESULT.txt`

---

## Closure rule

```text
R2-A release field PASS        → REQUIRED
R2-B kill-switch PASS          → REQUIRED (unit/debug OK)

Both PASS:
  R1  CLOSED / FIELD-PROVEN
  R2  PASS
  Meeting GA default release integration COMPLETE
  → RELEASE CANDIDATE
```

---

## References

- R1 design: `meeting-product-r1-ga-default-path-release-integration.md`
- GA authorization: `meeting-product-ga-authorization-review.md`
- G2 real mesh baseline: `talkback/logs/ga-g2-real-mesh-field-20260914-175047`
- G3a teardown: `talkback/logs/ga-g3a-t1-teardown-field-20260915-091534`
