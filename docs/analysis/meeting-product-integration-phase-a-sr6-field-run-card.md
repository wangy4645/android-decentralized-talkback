# PA-SR6 — Field Run Card (Meeting End Teardown / Runtime Quiescence)

**Status:** **CLOSED / FIELD-PROVEN** (2026-09-10)  
**Closure:** `meeting-product-integration-phase-a-sr6-field-closure.md`  
**Evidence:** `talkback/logs/phase-a-sr6-field-20260910-130854/`  
**Prerequisite:** PA-SR5 **CLOSED / UNIT+FIELD-PROVEN**  
**Parent:** `meeting-product-integration-phase-a-shadow-runtime-adjudication-criteria.md` (SR6 — superseded where this card is stricter)

```text
PA-SR1–SR6        CLOSED / FIELD-PROVEN
Phase A           CLOSED / FIELD-PROVEN
PaSr6 harness     CLOSED (no re-run unless new ADR)
NEXT              Replacement Readiness Review
ADR-0056          KEEP
Meeting cutover   NOT AUTHORIZED
NO SR7            integration track CLOSED
```

---

## 1. Question this gate answers

> After a **fully operational** shadow Meeting (TX / RX / ingress / playout proven), does **product Meeting end** tear down the new media runtime **completely and keep it quiescent**?

**Not** synthetic `stopSession` broadcast. **Not** service restart / `pm clear` / harness registry clear. **Is** host UI Meeting end → shadow `SESSION_STOPPED` → `TEARDOWN_EMPTY` → bounded quiescence with **no resurrection**.

---

## 2. Frozen stimulus (field)

```text
Stimulus:
Operational Meeting session H (Window A baseline)
→ M01 host Meeting end (hangup / leave controls)
→ RX disarm + playout clock stop + revoke/drain + epoch retire + transport close
→ TEARDOWN_EMPTY / A4 empty
→ bounded quiescence (no RX / playout / admit / scope revival)
```

**Driver entry:** UI taps on **M01** — `btnHangup` · `btnMeetingLeave` · `btnMeetingLeaveTop` (first present).  
Harness may surface Meeting overlay (`btnPtt` / `btnMeetingBack` navigation) **only** for end-control readiness — **no** service restart.

**Rejected stimuli:**

| Stimulus | Why rejected for SR6 |
|----------|----------------------|
| Debug synthetic stop | Not product end path |
| M02-only leave | Not full session teardown |
| `PA_SR5_ROTATE_LOCAL_SOURCE` | SR5 gate |
| Service force-stop / app kill | Harness abort / invalid |

---

## 3. Core invariant

```text
Meeting end ⇒ old session H media execution capability = 0
Quiescence window ⇒ remains 0 (no spontaneous revival)
```

---

## 4. Adjudication plane

**M02 peer primary** + **M01 host corroboration** for `SESSION_STOPPED` / `TEARDOWN_EMPTY`.

---

## 5. Three observation windows

| Window | Marker | Intent |
|--------|--------|--------|
| **A — Operational baseline** | `sr6Window=A` | SR4-class TX + peer ingress + playout; session/conference/generation captured |
| **B — End transition** | `sr6Window=B` | Product end → disarm / revoke / teardown chain |
| **C — Quiescence** | `sr6Window=C` | Bounded silence; no metric / scope resurrection |

**Anti-false-pass:** Window A **must** prove operational media. Never-started runtime → `INVALID_BASELINE`, not PASS.

---

## 6. Hard evidence (adjudicator)

**Window A**

- M01 `SHADOW_TX_ACTIVITY packetsSent>0`
- M02 `INGRESS_ACTIVITY packetsReceived>0`
- M02 `PLAYOUT_ACTIVITY successfulPlayoutWrites>0`
- M02 `WIRING_SESSION_STARTED transportScopeActive=true`

**Window B**

- `SHADOW hook=SESSION_STOPPED session=H outcome=APPLIED` (M01 and/or M02)
- `PROFILE01_SHADOW_RUNTIME phase=SHADOW_PLAYOUT_DISARMED sessionId=H`
- `PROFILE01_SHADOW_RUNTIME phase=TEARDOWN_EMPTY sessionId=H`
  - `transportScopeActive=false`
  - `catalogEntries=0` · `admittedCount=0`
  - `activeJitterSources=0` · `liveDecoders=0` · `jitterBufferCount=0`
  - `ingressBlocked=true` (when present)
- Member revoke observed (`WIRING_MEMBER_REVOKED`) **or** admitted already zero at teardown

**Window C**

- No new `INGRESS_ACTIVITY` / `PLAYOUT_ACTIVITY` / `SHADOW_RX_ACTIVITY` / `SHADOW_PLAYOUT_CYCLE` for H **after** last `TEARDOWN_EMPTY`
- `successfulPlayoutWrites` / `packetsReceived` max **unchanged** across quiescence
- No `WIRING_MEMBER_INSTALLED` / `SHADOW_RX_ARMED outcome=APPLIED` after teardown
- No `transportScopeActive=true` snapshot for H after teardown

**RX disarm:** inferred as `SESSION_STOPPED` before `TEARDOWN_EMPTY` with no post-teardown RX arm/activity (no dedicated `SHADOW_RX_DISARMED` log today).

---

## 7. Harness discipline (frozen)

- Real Meeting end UI only
- `Ensure-TalkPagePttReady` / `Ensure-HostMeetingEndReady` for UI readiness
- UI failure → `INVALID_UI_PREREQUISITE` / `HARNESS_ABORT`
- No service restart · no registry clear · no synthetic stop
- Default quiescence observation: **30s** (`ShadowQuiescenceSec`)

---

## 8. Verdict routing

| Verdict | Meaning |
|---------|---------|
| `PASS_PA_SR6` | A+B+C satisfied |
| `FAIL_PA_SR6` | Stimulus ran; teardown/quiescence incomplete |
| `INVALID_BASELINE` | Window A not operational |
| `INVALID_UI_PREREQUISITE` | Harness could not trigger/observe |
| `INVALID_*` | Capture / precondition / wrong stimulus |

---

## 9. Closure chain

```text
PA-SR6 PASS
→ PA-SR1–SR6 CLOSED / FIELD-PROVEN
→ Phase A CLOSED
→ Replacement Readiness Review (capacity / G-RES-3 independent)
→ cutover policy / ADR-0056 rollback strategy
```
