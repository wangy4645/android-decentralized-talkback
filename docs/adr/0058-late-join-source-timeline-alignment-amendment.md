# ADR-0058 Amendment: Late-Join Source Timeline Alignment

## Status

**ACCEPTED — CONTRACT ONLY** (2026-09-20)

**Parent:** [ADR-0058](./0058-conference-multicast-media-group.md)

**Amends:** Profile 02 ingress sequence semantics (receiver interpretation); Profile 03 jitter ingress mapping; RCA5-B2 session playout slot domain (clarification only — session clock **retained**)

**Trigger:** Meeting field audible defect — session `c5baab40-bc82-421c-ac5f-b8141954c86d` (2026-09-20). X7 data-plane observation + jitter bootstrap RCA.

**Evidence:**

- `talkback/logs/x7-dataplane-obs-20260920-145154/`
- Prior incident capture: `talkback/logs/meeting-field-test-20260920-135135/`

**Implementation:** **AUTHORIZED — SLICE 1 MERGED** (2026-09-20; per-incarnation ingress mapping)

**Implementation plan:** [0058-late-join-source-timeline-alignment-implementation-plan.md](../analysis/0058-late-join-source-timeline-alignment-implementation-plan.md) (**Slice 1 complete**; field not authorized)

**Does NOT authorize:** sender conference-global sequence alignment; global replacement of session playout clock; LiveEdgeAligner scope expansion; epoch3 rotation fix; field rerun for maturity upgrade

```text
Meeting audible RCA (ADR-0058)     CLOSED
Fix contract (this amendment)      ACCEPTED — CONTRACT ONLY
Implementation                       NOT AUTHORIZED
Epoch3 rotation backlog              SEPARATE / OPEN / FROZEN
X5 off-wire binding-null replay      SEPARATE / CONFIRMED ARCH DEFECT
```

---

## Summary

Profile 02 correctly defines RTP sequence and media-time grid as **Source-incarnation scoped**. The receiver currently maps every admitted source's RTP sequence through a **single session-global** `RelativeMediaTimeline` anchor (`baseSeq` + `baseArrivalMs` from the first accepted packet of any source). That coordinate-domain mismatch assigns stale `mediaTimeMs` to late-join sources, causing immediate and permanent `LATE_FOR_PLAYOUT`, empty jitter, and inaudible cross-peer mix contribution despite valid TX/RX/admission/Top-K selection.

This amendment freezes a **two-layer timeline model**: per-incarnation ingress mapping, then shared session playout domain.

---

## Confirmed root cause (RCA closed)

```text
Profile 02:
  RTP sequence / media timeline = Source-incarnation scoped     VALID

Receiver (pre-amendment):
  RelativeMediaTimeline = session scoped
  mediaTimeMs(anySourceSeq) =
    sessionBaseArrival + (anySourceSeq - sessionBaseSeq) * 20ms

Mechanism:
  M01 establishes session anchor (seq 20481 @ 27.609)
  session playout advances ~5.7s
  M03 joins with independent source seq ≈ 0x5000 (20480)
  receiver maps M03 using M01/session baseSeq
  computed media time ≈ anchor + 220ms
  actual arrival ≈ anchor + 6040ms
  delta ≈ constant -280..-286 slots (NOT clock drift)
  → LATE_FOR_PLAYOUT from first observed packet
  → jitter never queues M03
  → Top-K includes M03 but sourceBySlotSize = 0
  → M02 cannot hear M03
```

**Formally excluded from this incident root cause:**

```text
cutover not happening · Anchor relay · declaration/catalog/binding missing
runtime admission missing · multicast TX/RX missing · SRTP/auth reject
Top-K forgot M03 · sender must align conference-global sequence
network jitter · clock-rate drift
```

---

## Architectural model (frozen)

Two domains — **must not be conflated**:

```text
Source domain (per Source incarnation)
────────────────────────────────────
M01 seq ─┐
M02 seq ─┼─> per-incarnation ingress sequence → media-time mapping
M03 seq ─┘
              ↓
Session domain (per conference session)
───────────────────────────────────────
common playout clock / mediaSlot cursor
→ jitter (per incarnation buffer, shared playout pulls)
→ Top-K
→ mix / AudioTrack
```

**KEEP (unchanged by this amendment):**

- Session playout clock and `RelativeMediaTimeline` **session** slot projection for playout ticks
- Source-local RTP sequence production (Profile 02 Q1)
- `MaxPlayoutDelayMs = 120` jitter/deadline semantics **after** correct mapping
- `SessionMediaLiveEdgeAligner` as reorder recovery only (RCA5-B2)

**INTRODUCE (contract level):**

- Per-incarnation ingress mapping state owned by receiver
- Late-admitted source bootstrap of **that incarnation's** ingress origin without resetting existing sources

**DO NOT:**

- Require conference-global sender sequence or shared random initial seq across Modules
- Convert entire `RelativeMediaTimeline` to per-source (session playout domain stays)
- Enlarge `LiveEdgeAligner` as late-join bootstrap shortcut
- Mix epoch3 `MEMBERSHIP_CONTEXT_MISMATCH` / `SOURCE_GENERATION_MISMATCH` into this fix

---

## Frozen fix contract — F1 through F8

### F1 — Sequence scope

RTP sequence remains **Source-incarnation scoped** per Profile 02 Q1/Q6. Receivers MUST NOT require a conference-global sequence origin or shared initial sequence across admitted Modules.

### F2 — Ingress mapping ownership

Each admitted Source incarnation MUST have **independent receiver-side** ingress state mapping `(RTP sequence) → (mediaTimeMs for jitter admission)` (and derived slot coordinates for that incarnation's jitter buffer). Session-global `baseSeq` MUST NOT interpret foreign source sequence values.

### F3 — Late-admit bootstrap

When a Source incarnation is first admitted to an **already-running** session playout clock, its **first valid admitted media frame** establishes that incarnation's ingress origin / live-edge mapping. Bootstrap MUST NOT re-anchor or invalidate ingress mapping of **already-active** incarnations.

### F4 — Session playout clock retention

The session playout clock (`RelativeMediaTimeline` session slot projection, playout tick → `mediaSlotForPlayoutTickMs`, mix pull slot domain) is **retained**. Late source bootstrap adjusts **incarnation ingress mapping into** the session domain; it does not reset session playout for peers.

### F5 — Incarnation replacement

Source incarnation replacement with new random RTP state (Profile 02 Q1/Q5) creates a **new** ingress mapping. The new incarnation MUST NOT inherit the previous incarnation's ingress `baseSeq` / origin. Hard fence and catalog/wiring lifecycle remain governed by existing Profile 02/03 contracts.

### F6 — Deadline application point

`MaxPlayoutDelayMs` (120 ms) late-for-playout classification applies to frames **after** source-local sequence has been mapped into the session playout time domain. Mis-mapping at ingress MUST NOT be compensated by widening the deadline or replay window.

### F7 — LiveEdgeAligner boundary

`SessionMediaLiveEdgeAligner` remains authorized only for **same-source** reorder displacement recovery (`REORDER_DISPLACEMENT_EXCEEDED` + empty gap past reorder window), per RCA5-B2. It is **not** the late-join bootstrap owner and MUST NOT be silently expanded to absorb F3.

### F8 — Non-disruption on late join

When a late source completes F3 bootstrap, **already-audible** active sources MUST NOT audible-bounce, lose jitter queued state, hard-fence, or restart AudioTrack solely because a new peer incarnation joined.

---

## Normative receiver obligation (post-implementation)

When implementation is authorized, receiver ingress MUST satisfy:

```text
∀ admitted incarnation I:
  mediaTimeMs(I, seq) = f_I(seq, arrival, I.ingressOrigin)
  where f_I is scoped to I only

session playout tick T:
  mediaSlot(T) = g_session(T)        // existing session RelativeMediaTimeline role

jitter.admit(frame_I):
  evaluate 120ms deadline using mapped mediaTimeMs(I, seq)
  NOT using (sessionBaseSeq, foreign seq)
```

Exact `f_I` initialization (first-frame anchor vs live-edge resync into session domain) is an implementation detail **provided** F1–F8 hold.

---

## Regression fixtures (required before implementation PASS)

Implementation MUST NOT be marked VERIFIED without harness or integration coverage for:

### A — Simultaneous sources

Two (or more) sources start within normal join skew, each with **independent** Profile 02–legal initial sequence. **Expect:** both `QUEUED` under sustained ingress; audible mix contribution when selected in Top-K.

### B — Late join

Source A establishes session playout; playout advances ≥ ~5 s; Source B admitted with **independent** initial sequence (e.g. shadow/harness 0x5000 or random). **Expect:** B's first valid frames `QUEUED` (not permanent `LATE_FOR_PLAYOUT`); A remains uninterrupted (F8).

### C — Incarnation replacement

Same moduleId, new `sourceGeneration` / incarnation, new random sequence. **Expect:** new ingress mapping bootstrap; no permanent `LATE_FOR_PLAYOUT`; prior incarnation jitter fenced per existing hard-fence rules, not session-global seq pollution.

**Note:** Phase 1 LIVE_SLOT harness requirement of identical `baseSeq` across devices is **instrumentation-only** and MUST NOT be treated as production contract (see F1).

---

## Relationship to existing documents

| Document | Relationship |
|----------|----------------|
| `0058-implementation-profile-02-rtp-wire.md` Q1/Q6 | **Unchanged** — confirms source-local sequence; this amendment fixes receiver interpretation |
| `rca5b2-playout-funnel-observability.md` | Session playout slot domain ** retained**; ingress mapping clause ** clarified** |
| `WireIngressMediaMapper.kt` / `RelativeMediaTimeline` | Implementation defect locus; amendment defines required split |
| `SessionMediaLiveEdgeAligner.kt` | **Unchanged responsibility** (F7) |
| G-RES-4-P4 PublicationBootstrap | **Out of scope** — liveness fact recovery, not media slot alignment |

---

## Separate backlog (not merged)

```text
1. X5 off-wire binding-null lost replay
   CONFIRMED architecture defect
   NOT this incident root cause

2. epoch3 rotation failure
   REJECTED_MEMBERSHIP_CONTEXT_MISMATCH
   SOURCE_GENERATION_MISMATCH @ 14:54:54
   SEPARATE / OPEN / FROZEN
```

---

## Consequences

- Profile 02 sender contract remains valid; no sender sequence coordination protocol is introduced.
- Receiver MUST gain per-incarnation ingress mapping before late-join cross-peer audio can be correct in production.
- Session playout observability (`PLAYOUT_FUNNEL_CYCLE`, `RelativeMediaTimeline`) remains meaningful without redesign.
- Tests that rely on shared `baseSeq` across sources prove harness alignment only; fixtures A/B/C become mandatory for production regression.

---

## Implementation authorization gate (future)

Implementation MAY begin only when explicitly authorized. Minimum deliverables:

1. Per-incarnation ingress mapping per F2–F5
2. Fixtures A/B/C PASS
3. No F7/F8 regression on RCA5-B2 field-verified playout path
4. No change to WiFi recovery / RNA / completion / membership frozen domains
