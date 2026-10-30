# RCA5-B2 — Playout Funnel Observability (OPEN)

## Status

```text
RCA5-A  CLOSED / FIELD-PROVEN   (CME = 0; lock fix retained)
RCA5-B2 FIELD-PROVEN / CLOSED   (slot-domain alignment; 2026-09-14)
```

Field evidence (B2 fix): `talkback/logs/controlled-pilot-run1-field-20260914-101929`
(session `da4e1cfe-23da-4b44-8796-4679084d295d`).

Pre-fix starvation evidence: `talkback/logs/controlled-pilot-run1-field-20260911-172145`
(session `5ffe9458-188d-434c-8690-82801bacdb66`, build `faf8884`).

## Symptom (field-confirmed)

```text
ingress continues
playout clock/tick exists
only 1 successful SHADOW_PLAYOUT_CYCLE
cutover succeeds
production AudioTrack acquired
successfulProductionWrites = 0
→ silent starvation
```

## Narrow funnel (this change)

Behavior-neutral OBS only — **no jitter algorithm change, no reset/recovery**.

```text
WIRE_INGRESS / RX_ADMISSION
        ↓
orchestrator.admitFrame
        ↓
jitter.admit
        ↓
bySlot size / slot range
        ↓
resolveEarliestBufferedMixSlot
        ↓
promotionDiscardLateForPlayout   (not yet separately logged)
        ↓
executeTimedMixCycle
        ↓
decoder
        ↓
AudioTrack write
```

### Log phases

| Phase | When | Purpose |
|-------|------|---------|
| `INGRESS_FUNNEL` | rate-limited on admit | sourceId, mediaSlot, jitterOutcome, bySlot, earliest/latest, nextExpected |
| `PLAYOUT_FUNNEL_CYCLE` | rate-limited on successful mix | full funnel + resolvedMixSlot |
| `PLAYOUT_FUNNEL_STARVATION_ONSET` | first starvation after last cycle | compares `lastCycle*` vs `starvation*` slot domain |
| `SHADOW_PLAYOUT_BUFFER_STARVATION` | rate-limited ongoing | enhanced with funnel slot-domain fields |

### B2 classification (field adjudication)

| Class | Signal |
|-------|--------|
| **B2-a** | `INGRESS_FUNNEL jitterOutcome != QUEUED` or `bySlotSize=0` after admit |
| **B2-b** | jitter has slots but `nextExpected` advances past `latestBuffered`; promotion discard suspected |
| **B2-c** | jitter `bySlotSize>0` but `resolveEarliestBufferedMixSlot` null; TopK / selection gap |

Compare `PLAYOUT_FUNNEL_CYCLE` (last) vs `PLAYOUT_FUNNEL_STARVATION_ONSET` (first).

## Field gate (RCA5 overall closure)

```text
CME = 0
+ ingress sustained
+ playout cycles grow
+ cutover
+ successfulProductionWrites > 0
+ M02 audible
```

**2026-09-14 focused PASS** (`da4e1cfe…`):

| Gate | Result |
|------|--------|
| CME | 0 |
| QUEUED / REORDER | 502 / 12 (no storm) |
| SHADOW_PLAYOUT_CYCLE | 495 (rate-limited); funnel cycleOrdinal → 12375 |
| cutover | SUCCESS · MULTICAST_ACTIVE |
| production writes | max 8325 under MULTICAST_PRODUCTION |
| pilot harness | PASS · TEARDOWN_EMPTY |

Then RCA4 → FIRST 3-NODE MEETING PASS.

## Fix (RCA5-B2 — AUTHORIZED)

```text
first QUEUED frame → RelativeMediaTimeline anchor (baseSeq + baseArrivalMs)
playout tick → mediaSlotForPlayoutTickMs(tickMediaTimeMs)
resolvePlayoutMixSlot → earliest buffered slot <= tick target in media domain
ingress promotion → playoutReferenceMs (last tick or media anchor), not raw rxWallMs alone
REORDER + empty gap past 120ms → SessionMediaLiveEdgeAligner (re-anchor nextExpected)
```

Files:
- `RelativeMediaTimeline.mediaSlotForPlayoutTickMs`
- `ConferenceSessionMediaWiring.resolvePlayoutMixSlot`
- `SessionMediaLiveEdgeAligner`
- `Profile01ShadowPlayoutClockSeam` → `resolvePlayoutMixSlot`

## Out of scope

- PR #200 lock fix rollback or expansion
- reorder cap != 4
- 120ms deadline change
- TopK policy change
- full G2 or RCA4 until B2 field PASS
