# ADR-0058 Late-Join Source Timeline Alignment — Implementation Plan

**Status:** **SLICE 1 IMPLEMENTED** (2026-09-20)  
**Code implementation:** **Slice 1 complete** (P0 ingress mapping; field not authorized)  
**Field rerun:** **NOT AUTHORIZED**

**Amendment:** [0058-late-join-source-timeline-alignment-amendment.md](../adr/0058-late-join-source-timeline-alignment-amendment.md) (**ACCEPTED — CONTRACT ONLY**)  
**Parent ADR:** [0058-conference-multicast-media-group.md](../adr/0058-conference-multicast-media-group.md)  
**Field RCA evidence:** `talkback/logs/x7-dataplane-obs-20260920-145154/` (session `c5baab40…`)

```text
Amendment contract              ACCEPTED
Main ADR index                  COMPLETE
Impact audit                    COMPLETE
Implementation plan             AUTHORIZED (this document)
Code change                     NOT AUTHORIZED
Unit/integration tests          NOT AUTHORIZED (with code)
Field                           NOT AUTHORIZED

X5 off-wire binding-null        FROZEN SEPARATE
Epoch3 rotation                 FROZEN SEPARATE
```

---

## 1. Objective

Implement amendment **F1–F8**: per-incarnation ingress RTP sequence → `mediaTimeMs` mapping, while **retaining** session-global playout clock (`RelativeMediaTimeline` for tick → `mediaSlotForPlayoutTickMs` only).

**Primary field failure fixed:** late-join source packets classified `LATE_FOR_PLAYOUT` at ingress because session `baseSeq` interpreted source-local sequence.

**Non-goals:**

- Sender conference-global sequence alignment
- Replacing session playout clock with per-source timeline
- Expanding `SessionMediaLiveEdgeAligner` for late join
- Epoch3 rotation · X5 replay · WiFi recovery / RNA / completion changes

---

## 2. Design (minimal — audit at impl time)

### 2.1 Two layers (frozen)

```text
Layer A — Per-incarnation ingress mapping (NEW)
  Key: sourceIdentity#incarnationId (same as jitterKey)
  Input: (seq, rxWallMs) for one incarnation
  Output: mediaTimeMs for AdmittedMediaFrame + jitter deadline

Layer B — Session playout domain (KEEP)
  RelativeMediaTimeline on SessionState
  playout tick → mediaSlotForPlayoutTickMs
  resolvePlayoutMixSlot / Profile01ShadowPlayoutClockSeam
```

### 2.2 Layer A bootstrap (F3)

On **first wire-admitted media frame** for incarnation `(I, inc)`:

```text
ingressOriginSeq(I, inc)   := seq
ingressOriginWall(I, inc)  := rxWallMs
mediaTimeMs(seq)           := ingressOriginWall + (seq - ingressOriginSeq) * 20ms
```

Subsequent frames for same incarnation use the same origin until cleared (F5).

**Must not** read or write session `RelativeMediaTimeline.baseSeq` for Layer A.

### 2.3 Lifecycle (F2, F5, F8)

| Event | Action |
|-------|--------|
| First admitted frame for incarnation | Create / anchor Layer A mapping |
| `hardFence` / incarnation superseded | Clear Layer A + existing jitter invalidate (already) |
| Late member `installMember` | **No** session timeline reset; **no** playout stop |
| Session `startSession` | Session Layer B anchor unchanged behavior |

Registry ownership: **`ConferenceSessionMediaWiring.SessionState`** (or colocated helper next to session pipeline lock). Exact class name deferred to implementation audit.

### 2.4 Admit path change (P0)

Replace in `ConferenceMulticastMediaPipeline.admitProtectedDatagram`:

```text
mediaTimeline?.mediaTimeMs(ingress.sequence, rxWallMs)   // REMOVE for production path
→ sessionState.ingressMediaTimeMs(sourceIdentity, incarnationId, seq, rxWallMs)
```

Session `mediaTimeline` continues to be passed **only** where playout slot projection is needed (playout tick / funnel), not for per-frame jitter input.

### 2.5 pullSlot follow-up (P1 — verify during impl)

Field showed M03 `nextExpected` advanced via Top-K `pullSlot` at session mix slots while jitter empty. After Layer A fixes `QUEUED`, implementation MUST verify fixture **B** end-to-end:

```text
INGRESS_FUNNEL jitterOutcome=QUEUED
resolvePlayoutMixSlot selects late source buffered slot
decode/mix participant includes late source when in Top-K
```

If B passes ingress but fails mix selection, add **narrow** pullSlot guard (implementation audit):

- Do not advance incarnation `nextExpected` on session mix slot pulls when `slot` is ahead of that incarnation's buffered/latest admitted slot domain without a queued frame (preserve F7 — not LiveEdgeAligner).

Do not spec the guard in advance; decide only if fixture B fails post-P0.

---

## 3. Implementation slices

### Slice 0 — Scaffold (no behavior change)

- Add ingress mapping type + registry on `SessionState`
- Wire registry through `admitProtectedDatagram` behind feature flag **or** direct swap if tests cover (prefer direct swap + fixture B immediately)
- Unit test: mapping isolation (two incarnations, different seq origins → different `mediaTimeMs`)

**Exit:** compiles; no production behavior change until Slice 1 connects path.

### Slice 1 — P0 ingress fix (F1–F6)

**Files (expected touch):**

| File | Change |
|------|--------|
| `WireIngressMediaMapper.kt` | Extract / add per-incarnation mapping helper; document session vs ingress split |
| `ConferenceSessionMediaWiring.kt` | Registry lifecycle; pass into pipeline admit |
| `ConferenceMulticastMediaPipeline.kt` | Use Layer A for `resolvedMediaTimeMs` |
| `ConferenceSessionMediaSourceSuccessionLifecycle.kt` | Clear mapping on succession / fence if not already via jitter teardown |

**Exit gates:**

- Fixture **B** (late join): first frames `QUEUED`, not sustained `LATE_FOR_PLAYOUT`
- Fixture **A** (simultaneous independent seq): both sources `QUEUED`
- Existing `Profile01ShadowRca5B2SlotDomainAlignmentTest` PASS (session playout domain regression)

### Slice 2 — P1 mix path verification (F8)

- Fixture **B** extended: playout cycles > 0 with late source in Top-K; no audible-bounce on early source (metric: early source `successfulPlayoutWrites` monotonic non-reset)
- Fixture **C** (incarnation replacement): new mapping after new generation; no inherited `baseSeq`
- If needed: minimal `pullSlot` / mix resolution adjustment per §2.5

**Exit gates:**

- A/B/C PASS
- `SessionMediaLiveEdgeAlignerTest` + live-edge aligner wiring test PASS unchanged (F7)
- No change to `MaxPlayoutDelayMs`, reorder cap, Top-K policy

### Slice 3 — Observability (optional, behavior-neutral)

- `INGRESS_FUNNEL` optional fields: `ingressOriginSeq`, `ingressOriginWallMs` for field debug (only if zero behavioral change)

**Not required for impl PASS.**

---

## 4. Test plan (mandatory before impl VERIFIED)

New harness (suggested location: `session/integration/Profile01LateJoinIngressTimelineTest.kt` or split A/B/C):

### A — Simultaneous sources

- Two members, tone packets with **different** base seq (e.g. `0x5000` vs `0xA000`), same session, aligned wall within join skew
- Expect: both `QUEUED`; jitter `bySlotSize > 0` for both

### B — Late join (field regression)

- Member A ingress + playout advance simulated wall **≥ 5 s** (or tick loop)
- Member B installed late; packets from independent seq (`0x5000` ladder)
- Expect: B `QUEUED`; sustained ingress without permanent `LATE_FOR_PLAYOUT`; A playout not reset

### C — Incarnation replacement

- Member B generation 1 ingress + mapping
- Succession to generation 2 with new seq origin
- Expect: new mapping; generation 1 fenced; generation 2 `QUEUED`

**Regression suite (minimum):**

- `Profile01ShadowRca5B2SlotDomainAlignmentTest`
- `SessionMediaLiveEdgeAlignerTest`
- `Profile03E2b03JitterPlcHarnessTest` (deadline semantics unchanged)

---

## 5. Verification checklist (pre-merge)

```text
[ ] F1: no sender / conference-global seq requirement added
[ ] F2: mapping keyed per incarnation
[ ] F3: late first frame anchors that incarnation only
[ ] F4: session RelativeMediaTimeline playout projection unchanged
[ ] F5: succession clears old incarnation mapping
[ ] F6: 120ms gate unchanged; fix is mediaTimeMs input only
[ ] F7: LiveEdgeAligner trigger unchanged
[ ] F8: late join does not reset early source jitter / playout seam
[ ] A/B/C fixtures PASS
[ ] RCA5-B2 slot alignment tests PASS
```

---

## 6. Authorization ladder (after plan)

| Stage | Authorization |
|-------|----------------|
| This plan | **AUTHORIZED** |
| Slice 0–1 code | Requires **「授权实现 Slice 1」** or blanket **「授权实现」** |
| Slice 2 pullSlot adjustment | Only if fixture B mix gate fails post-P0 |
| Field rerun | Separate explicit authorization |
| Amendment status → IMPLEMENTED | After checklist + A/B/C PASS |

---

## 7. Risks

| Risk | Mitigation |
|------|------------|
| pullSlot advances late source `nextExpected` past buffered low slots | §2.5 verify in fixture B; narrow guard only if proven |
| Dual timeline confusion in future edits | Comment + test A; amendment F4 doc |
| Harness tests still use aligned wall | A/B/C must **not** reuse shared baseSeq precondition |
| Epoch3 rotation conflated into fix | Stay out of scope; separate backlog |

---

## 8. Out of scope (frozen)

```text
Epoch3 MEMBERSHIP_CONTEXT_MISMATCH / SOURCE_GENERATION_MISMATCH
X5 off-wire binding-null lost replay
SessionMediaLiveEdgeAligner scope expansion
Sender SHADOW_TX_INITIAL_SEQ / production seq policy change
Field rerun for X7 maturity
```
