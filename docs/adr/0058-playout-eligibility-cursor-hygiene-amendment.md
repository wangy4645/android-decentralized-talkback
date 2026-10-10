# ADR-0058 Amendment — Playout Eligibility & Cursor Hygiene (B8)

**Track:** RCA5-B8 · design track also referred to as **ADR-0059 (eligibility)** — *repository file `0059` is [underlay multicast](./0059-underlay-multicast-product-assumption.md); this amendment extends Profile 03 playout under ADR-0058.*

**Status:** **ACCEPTED — DESIGN FROZEN** (implementation authorized only within §Decision; no mixer/gain/VAD/Top-K sort/REORDER cap changes)

**Parent:** [0058-conference-multicast-media-group.md](./0058-conference-multicast-media-group.md), Layer A/B (F1–F8, F9.x), [0058-active-playout-ingress-deadline-alignment-amendment.md](./0058-active-playout-ingress-deadline-alignment-amendment.md)

**Field evidence:** B7 session `e0985d81-…` — VERDICT `field-logs/lq-a-level-clip-20261010/b7-m04-rx-empty-field-20261010/VERDICT-B7-M04-RX-EMPTY.md`

**Date:** 2026-10-10

---

## Context

B3–B6 fixed per-source RTP playout indexing, LATE/REORDER deadlocks, and aged-hole live-edge recovery. REORDER remains ~4–5% in field; **AUDIO still FAIL** on continuity.

B7 (expired-only off-Top-K cursor hygiene) **partially helped** main listeners (`next max_stuck ≤ 2` vs B6) and **must be retained**. It does **not** address:

1. **B8-A:** `topk=none` (or sparse Top-K) while jitter **`bySlot > 0`** → starvation / `empty>real` — *eligible buffered media not mixed, not necessarily expired.*
2. **B8-B:** **`nextExpected` fixed, `bySlot = 0`** (empty buffer cursor) — *discard-pull has nothing to drop; e.g. M04 `max_stuck ≈ 214`.*

These are **two fault domains**. One `pullSlot` patch cannot subsume both.

**Out of scope for B8 code:** mixer gain, VAD, Top-K ranking/hold constants, `MAX_REORDER_PACKETS`, `MAX_PLAYOUT_DELAY_MS`, P1 AEC/啸叫. **Parallel field risk:** M02 ~50% / M04 ~18% `SHADOW_RX_ADMISSION` reject — separate RCA; must not block interpreting B8 on M01/M03 only.

---

## Decision — Three actions (frozen product semantics)

| Action | When allowed | Product meaning |
|--------|----------------|-----------------|
| **A1 — Top-K mix pull** | Source passes existing **decode eligibility**, **incarnation**, **admission**, **executable** fence, and playout slot resolution for Top-K members | Decode → mix → `AudioTrack` (unchanged contract) |
| **A2 — off-Top-K non-mix release** | Frame is **past playable utility** (§Utility window) **or** slot is **above Layer-A playhead cap** for current tick; **same incarnation** as admitted source | Remove stale buffer / advance cursor **without** speaker output |
| **A3 — hold** | Frame is still within playable utility **and** at/before Layer-A playhead cap | **MUST NOT** discard to advance `nextExpected` |

**Forbidden equivalences:**

```text
❌ off-Top-K ⇒ unconditional discard
❌ cursor hygiene ⇒ mix non-Top-K into AudioTrack
❌ advance nextExpected across holes without A2 rules (B8-B: no fantasy skip to session playoutTarget)
❌ reuse pullSlot as blind drain of all peeked frames (B7 field constraint reaffirmed)
```

### Utility window (implementation must use existing types)

- **Playable utility end:** `AdmittedMediaFrame.usefulDeadlineMs()` = `mediaTimeMs + MAX_PLAYOUT_DELAY_MS` (120 ms contract — **frozen**).
- **Layer-A playhead cap:** `PerIncarnationIngressTimelineRegistry.mediaSlotForPlayoutTickMs(source, incarnation, tickMediaTimeMs)`; if null, session Layer-B target for telemetry only — **must not** index cross-source pulls.

A frame is **A2-eligible** iff `nowMs > usefulDeadlineMs()` **or** `nextExpectedSlot > playheadSlotCap` (strictly past cap — not “within reorder gap”).

A frame is **A3-protected** iff buffered at/after `nextExpected`, `nowMs ≤ usefulDeadlineMs()`, and `slot ≤ playheadSlotCap`.

### Concurrency & identity invariants

```text
INV-1: Per (sourceIdentity, incarnationId), at most one consumer advances nextExpected per pipeline lock tick
       (Top-K mix pull XOR A2 release on that source in the same tick — never both).

INV-2: A2 release MUST use the same incarnation and admission registry as A1; HARD_FENCED / wrong incarnation ⇒ no-op.

INV-3: A2 MUST NOT call decode/mix/PLC synthesis for speaker path (late-drop / takeFrame only).

INV-4: B7 expired-only maintenance is a subset of A2; B8 MUST NOT widen it to A3 frames.
```

### B8-A — Top-K empty window + buffered hunger

**Goal:** Reduce integrated time where `topk=none` (or source off-Top-K) **and** `bySlot > 0` **and** frames are A2-eligible, without mixing them.

**Allowed implementation seams (pick minimal):**

- Extend playout tick **before** `resolvePlayoutMixSlot`: run **A2-only** pass for admitted non-Top-K sources (bounded per tick, same cap as B7: `MAX_REORDER_PACKETS` unless desk proves insufficient).
- If `resolvePlayoutMixSlot` returns null solely because Top-K is empty but A3 frames exist → **still no mix**; optional A2 pass may run; **do not** fabricate `BufferedMixSlot`.

**Not allowed:** Promote off-Top-K sources into Top-K; change VAD; widen jitter.

### B8-B — Empty-buffer cursor stall (M04 class)

**Goal:** When `nextExpected = N`, `bySlot = 0`, `executable = true`, and ingress resumes, cursor **must** accept new QUEUED frames without minutes-long `N` freeze.

**Preferred order of investigation (code + desk, before new “chase target” logic):**

1. `pullSlot` EMPTY path when `peekFrame(N) == null` — does it advance only after hole deadline (B5 wall skew guard)? Document outcome.
2. `SessionMediaLiveEdgeAligner` — resync when empty gap + live ingress; gated when prefix empty?
3. Ingress path — packets received but rejected (`LATE`, `REORDER`, admission, incarnation mismatch) while cursor unchanged.
4. M04 RX reject — **correlation TBD**; add **minimal** funnel/starve reason tag only if logs cannot distinguish (see §Observability).

**Forbidden:** Skip RTP sequence to match session `playoutTarget`; force `advanceExpectedTo(live)` without aligner contract.

---

## Observability (minimal, only if desk blocked)

Add **one** structured reason on starvation when `resolve == null`, e.g.:

`starvationResolveReason = TOPK_EMPTY | NO_ELIGIBLE_SLOT | INGRESS_BLOCKED | …`

No new shadow phase; extend existing `SHADOW_PLAYOUT_BUFFER_STARVATION` fields only.

---

## Verification — desk repro (required before merge)

See: `field-logs/lq-a-level-clip-20261010/b8-playout-eligibility-desk-repro.md`

Two **independent** test classes / stories; one PR delivery allowed, **two verdict columns** in field.

---

## Field acceptance (split verdicts)

| Gate | B8-A | B8-B |
|------|------|------|
| **ENV** | Four DUTs same SHA, same session, `MULTICAST_PRODUCTION` | Same |
| **TIMELINE** | ↓ duration of (`topk=none` ∧ `bySlot>0` ∧ A2-eligible backlog); no A3 frame loss attributable to release | M04 ↓ empty-buffer stall duration; `max_stuck` ↓ without fantasy slot jump |
| **PRODUCTION** | A2 not in mix chain; no double-pull | After stall, QUEUED → pull REAL without long `next` pin at empty |
| **AUDIO** | Continuity: ↓ dropout / long silence; ↑ effective REAL density | M04: continuity + post-segment speech density (not global RMS) |

**AUDIO sub-gates (both domains):**

- **Continuity:** intermittent gaps, long silence, `empty>real` windows.
- **Loudness:** RMS on **REAL-contributing** windows only — not a B8 pass/fail on clip zero.

**Whole-meeting PASS:** B8-A **and** B8-B **and** explicit note on M02 RX reject risk (may be FAIL on M02 listen path while PASS on M01).

---

## Relationship to B7

B7 **remains**: `discardExpiredBufferedPrefixBounded` + playhead cap. B8 **does not** expand B7 to drain playable frames. B8-A may **unify** naming around A2 but must respect A3.

---

## Consequences

- Implementers add A2 path and/or B8-B aligner/ingress fixes under `ConferenceSessionMediaWiring` / `MediaExecutionPipeline` / `SessionMediaLiveEdgeAligner` with INV-1..4 tests.
- Field team runs **one** session, reports **two** timeline/production columns + M02 RX footnote.
- P1 (AEC/啸叫) and RX reject RCA proceed in parallel.
