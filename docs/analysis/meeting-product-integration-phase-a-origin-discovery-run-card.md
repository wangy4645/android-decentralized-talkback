# Meeting Product Integration — Phase A Origin Discovery + Shadow Adjudication Run Card

## Status

```text
COMPLETE / FIELD-ADJUDICATED
Nature: ORIGIN DISCOVERY (no product code changes)

Meeting Profile01 wire ingress     IMPLEMENTED / UNIT PASS
Origin Discovery field             COMPLETE
Profile01 fact origin              GAP CONFIRMED
Verdict                            INCOMPLETE / ORIGIN_GAP

CONFERENCE_SIGNED_FACT             0
WIRE_INGRESS                       0
MEETING_MEDIA_SHADOW               0
REJECTED_VERIFY                    0
SHADOW_FAILED                      0

WIRING_OR_ORDER_GAP                NO  (no wire fact reached ingress)
CRYPTO_OR_TRUST_GAP                NO  (no fact to verify)
Shadow implementation FAIL         NO  (shadow never fact-driven)

Phase A Shadow Integration         BLOCKED BY ORIGIN GAP
A1–A4                              NOT ADJUDICATED

Meeting Profile01 Origin Publisher AUTHORIZED / NEXT
Phase B                            HOLD
ADR-0056                           KEEP
```

## Purpose

Answer the **last open Phase A question**:

```text
Does the real Meeting host produce and broadcast Profile01 wire facts on
CONFERENCE_SIGNED_FACT?

  MEDIA_KEY_PACKAGE
  → CREATION
  → MEMBERSHIP
  → SOURCE_DECLARATION
```

- If **YES** and shadow converges → adjudicate **A1–A4** on this run.
- If **NO** → record **ORIGIN_GAP** (not shadow FAIL, not ingress FAIL, not ADR-0056 FAIL).
  Next authorized slice: narrow **Meeting Profile01 Fact Origin Publisher** only.

**Forbidden on this run:**

```text
field harness manual Profile01 injection (product-chain / directed fixture)
reopen Profile01 ingress / registry / shadow wiring
Phase B / ADR-0056 cutover
claim PASS by synthetic wire injection
```

## Evidence header (hard)

```text
MEETING PRODUCT INTEGRATION — PHASE A
ORIGIN DISCOVERY + SHADOW ADJUDICATION
Real Meeting UI / TalkbackCoordinator lifecycle
ADR-0056 remains user-hearable authority

NOT product-chain directed fixture injection
NOT Real Mesh E2E re-proof
NOT Phase B controlled media enable
```

## Topology

```text
Minimum: 2 devices (host + 1 participant) on same signaling reachability
Preferred: M01 host + M02 participant (Real Mesh–validated wlan0 segment when available)
M03: optional; ENV_BLOCK if different L3 (do not force 3-device claim)
```

Devices (field conventions):

| Label | Serial |
|-------|--------|
| M01 | HTUBB21B09220661 |
| M02 | 2d73067a / DSJ-2407041 |
| M03 | MDX0220416001963 |

## Run sequence (fixed)

Execute on **product build** with Phase A shadow wiring active (`TalkbackRuntimeFactory` → `forShadow`).

```text
1. create (host)
   → wait up to 30s for wire origin / shadow APPLIED on host
2. join (participant)
   → wait ICE media ready
3. talk (optional short floor / audio — ADR-0056 path only; no shadow AudioTrack)
4. leave (participant) OR authority prune
5. rejoin (participant) — JOIN_RESTORE path
6. stale successor check (if replace pair ever published)
7. end (host hangup)
8. teardown — confirm shadow stop + wiring empty if APPLIED occurred
```

Between steps, capture observability (logcat) — do not advance until step timeout or explicit PASS marker.

## Primary adjudication matrix

| Question | PASS criterion | FAIL / GAP criterion |
|----------|----------------|----------------------|
| **Wire facts originated by host** | ≥1 `CONFERENCE_SIGNED_FACT` received; `WIRE_INGRESS … outcome=APPLIED` per fact type | Zero wire ingress events after create+join window → **ORIGIN_GAP** |
| **MEDIA_KEY_PACKAGE decrypt** | `WIRE_INGRESS factType=11 outcome=APPLIED` on recipient | `REJECTED_VERIFY` / `DECRYPT_SEAM_UNAVAILABLE` — record reason; not Meeting FAIL |
| **DEFERRED_* → APPLIED** | After facts, shadow shows `outcome=APPLIED` for SESSION_STARTED / MEMBER_MEDIA_READY | Perpetual `DEFERRED_NO_FACT` / `DEFERRED_NO_BINDING` with no wire APPLIED → origin or ordering gap |
| **A1 lifecycle parity** | Hook counts track create/join/leave/rejoin/end 1:1 | Hook missing or shadow exception without Meeting impact |
| **A2 generation correctness** | replaceMember only with registry pair; stale source rejected | Stale admitted or replace without pair |
| **A3 ADR-0056 isolation** | Meeting audio/ICE normal; no coordinator throw; `SHADOW_FAILED` isolated | User-hearable Meeting broken attributable to shadow |
| **A4 teardown** | After end: no session in shadow wiring if APPLIED | Socket/jitter/decoder leak after APPLIED session |

## Verdict routing

```text
ORIGIN_PRESENT + shadow APPLIED + A1–A4 PASS
  → Phase A Shadow Integration PASS / FIELD-VALIDATED
  → Gate: ready for Phase B authorization discussion (still HOLD until explicit sign-off)

ORIGIN_ABSENT (no host CONFERENCE_SIGNED_FACT / no WIRE_INGRESS APPLIED)
  → Phase A field INCOMPLETE / ORIGIN_GAP
  → Next: Meeting Profile01 Fact Origin Publisher (encode+sign from authoritative Meeting/GBC state)
  → NOT: harness injection · NOT: ingress rework · NOT: ADR-0056 change

ORIGIN_PRESENT but shadow never APPLIED
  → Phase A field INCOMPLETE / WIRING_OR_ORDER_GAP
  → Route: fact ordering, decrypt seam, sessionId↔conferenceId binding evidence
  → Do NOT label ORIGIN_GAP if wire bytes clearly arrived

WIRE_REJECTED_* only (facts present, verify/publish fail)
  → Phase A field PARTIAL / CRYPTO_OR_TRUST_GAP
  → NOT shadow FAIL · NOT ADR-0056 FAIL
```

## Log capture (required)

Tag filter:

```text
MEETING_MEDIA_SHADOW
WIRE_INGRESS
CONFERENCE_SIGNED_FACT   (if coordinator logs signal type on receive)
```

Observability lines to grep:

```text
SHADOW hook=… outcome=…
WIRE_INGRESS session=… factType=… outcome=…
SHADOW_FAILED
```

Launcher: `talkback/scripts/meeting-product-phase-a-origin-discovery-step.ps1`

```powershell
# prep
.\talkback\scripts\meeting-product-phase-a-origin-discovery-step.ps1 -Step prep

# start log capture (host + participant)
.\talkback\scripts\meeting-product-phase-a-origin-discovery-step.ps1 -Step capture -RunId meeting-product-origin-discovery-YYYYMMDD-HHMMSS

# after manual Meeting UX sequence → pull
.\talkback\scripts\meeting-product-phase-a-origin-discovery-step.ps1 -Step pull -RunId <same>
```

Evidence path: `talkback/logs/<RunId>/`

## Report template (fill after run)

```json
{
  "runId": "",
  "evidenceScope": "MEETING_PRODUCT_PHASE_A_ORIGIN_DISCOVERY",
  "devices": { "host": "M01", "participant": "M02" },
  "wireFactsOriginatedByHost": "YES | NO",
  "factTypesObserved": [],
  "mediaKeyPackageDecrypt": "PASS | REJECTED | N/A",
  "deferredToApplied": "YES | NO | N/A",
  "A1_lifecycleParity": "PASS | FAIL | INCOMPLETE",
  "A2_generationCorrectness": "PASS | FAIL | INCOMPLETE",
  "A3_adr0056Isolation": "PASS | FAIL",
  "A4_teardown": "PASS | FAIL | INCOMPLETE",
  "verdict": "ORIGIN_GAP | WIRING_OR_ORDER_GAP | CRYPTO_OR_TRUST_GAP | PHASE_A_PASS",
  "nextSlice": "Meeting Profile01 Fact Origin Publisher | field re-run | Phase B HOLD"
}
```

## Static pre-check (non-authoritative)

Code review before field (hypothesis only — **field log is authoritative**):

```text
CONFERENCE_SIGNED_FACT receive path: IMPLEMENTED (TalkbackCoordinator + MeetingProfile01FactWireIngress)
CONFERENCE_SIGNED_FACT send / host broadcast: NOT FOUND in product path at authoring time
→ field likely to record ORIGIN_GAP unless unpublished branch exists on device build
```

Field run overrides this hypothesis — **CONFIRMED**: receive side present; product send/origin absent.

## Field evidence (signed off)

```text
RunId:   meeting-product-origin-discovery-20260907-153810
Path:    talkback/logs/meeting-product-origin-discovery-20260907-153810/
Devices: M01 (HTUBB21B09220661) + M02 (DSJ-2407041)
L3:      192.168.10.26 / 192.168.10.21 — 2 online, CH-01
Build:   com.talkback.appprod (field product UX; no harness Profile01 injection)
```

### Finding 1 — Origin (authoritative for this gate)

```text
Host CREATE occurred (multiple sessions; e.g. 4f7ad4c1…, acac4204…, f48b579f…)
→ entire run window CONFERENCE_SIGNED_FACT = 0
→ WIRE_INGRESS = 0, MEETING_MEDIA_SHADOW = 0
→ ORIGIN_GAP CONFIRMED

Origin adjudication does NOT require conference media CONNECTED.
Host create + zero wire facts is sufficient.
```

### Finding 2 — Lifecycle (does NOT invalidate Finding 1)

```text
M02 shouldLocalInitiateConference=true → dual HOST / yielding collisions
Conference media never reached connected=1 / mediaEverLive=true
leave partial; rejoin not cleanly completed
→ A1–A4 NOT ADJUDICATED on this run
→ NOT written as "Origin Discovery INVALID"
```

### Frozen grep counts (M01 + M02 full logcat)

| Tag / outcome | Count |
|---------------|------:|
| `CONFERENCE_SIGNED_FACT` | 0 |
| `WIRE_INGRESS` | 0 |
| `MEETING_MEDIA_SHADOW` | 0 |
| `REJECTED_VERIFY` | 0 |
| `SHADOW_FAILED` | 0 |

Representative lifecycle (M02):

```text
JOIN_MEETING_TRACE … shouldLocalInitiateConference=true chosenPath=CREATE
CONFERENCE_LIFECYCLE_TIMELINE event=SESSION_CREATED … localRole=HOST creationSource=USER_CREATE
CONFERENCE_RUNTIME_PROJECTION … connected=0 … awaiting=true
CONFERENCE_LIFECYCLE_TIMELINE event=SESSION_TERMINATED … reason=LOCAL_HANGUP
```

### Filled report

```json
{
  "runId": "meeting-product-origin-discovery-20260907-153810",
  "evidenceScope": "MEETING_PRODUCT_PHASE_A_ORIGIN_DISCOVERY",
  "devices": { "host": "M01 (intended); M02 also initiated CREATE", "participant": "M02" },
  "wireFactsOriginatedByHost": "NO",
  "factTypesObserved": [],
  "mediaKeyPackageDecrypt": "N/A",
  "deferredToApplied": "N/A",
  "A1_lifecycleParity": "INCOMPLETE",
  "A2_generationCorrectness": "INCOMPLETE",
  "A3_adr0056Isolation": "INCOMPLETE",
  "A4_teardown": "INCOMPLETE",
  "verdict": "ORIGIN_GAP",
  "nextSlice": "Meeting Profile01 Fact Origin Publisher"
}
```

## References

- Shadow run card: `meeting-product-integration-phase-a-shadow-run-card.md`
- Gate: `meeting-product-integration-gate.md`
- Wire ingress: `MeetingProfile01FactWireIngress.kt`
- ADR-0058 wire: `0058-profile-01-q4-wire-registry.md`
