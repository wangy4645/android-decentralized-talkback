# ADR-0057 Track C — Group PC Lineage Binding Narrow Authorization (F7 / GPLB)

## Status

**AUTHORIZED — IMPLEMENTATION CUT** (2026-09-02)

```text
Finding F7:
  POST-ACCEPT PC / ICE LINEAGE DESYNCHRONIZATION
  CONFIRMED IMPLEMENTATION GAP

Wire Correlation:
  OPTION A — ACCEPTED / FROZEN

Implementation cut:
  AUTHORIZED

Required:
  GPLB-T1..T4 + A1..A3
  GPLB-EG1..EG7

F4 / GIE:    CLOSED — RETAINED
F5 / IGIE:   CLOSED — RETAINED
F6 / GIDR:   CLOSED — RETAINED

F7 field:
  ACCEPT CORRELATION FIELD PASS (20260902-095312)
  FULL FIELD CONFORMANCE FAIL / OPEN

F7a / GPLB-LC:
  AUTHORIZED — see binding lifecycle correction doc

Required (parent cut — delivered):
  GPLB-T1..T4 + A1..A3
  GPLB-EG1..EG7

Required (LC cut):
  GPLB-L1..L4
  LC-EG1..EG6

ADR-0057 COMPLETE:
  NO
```

**Parents:**

- [Post-accept media readiness residual audit](./adr0057-post-accept-media-readiness-residual-audit.md)
- [Post-accept PC / ICE lineage seam audit](./adr0057-post-accept-pc-ice-lineage-seam-audit.md)
- [GIDR authorization](./adr0057-group-invite-delivery-recovery-narrow-authorization.md)
- [GPLB-LC binding lifecycle correction](./adr0057-gplb-binding-lifecycle-correction-narrow-authorization.md)
- [Case A field adjudication](./adr0057-track-c-case-a-field-adjudication.md)

---

## Wire correlation (Option A — frozen)

```text
GROUP_INVITE:
  carries offerLineageId = L

GROUP_ACCEPT:
  MUST echo L unchanged (answerer retains; never generates new L)

WEBRTC_ICE for that negotiation:
  MUST carry L stamped at candidate pipeline entry

L:
  negotiation correlation identity
  NOT authority
  NOT pcGeneration
```

### A1 — Echo is offer-originated

```text
GROUP_INVITE(L) → answerer retains L → GROUP_ACCEPT(L) → ICE(L)
```

### A2 — L ≠ pcGeneration

```text
offerLineageId L → GroupOfferBinding → pcLineage / pcGeneration (local runtime)
```

### A3 — ICE stamped at generation time

```text
candidate on PC-A → stamp L-A before wire
FORBIDDEN: stamp current binding at send time if PC rotated
```

### Fail-closed (no legacy fallback)

```text
GROUP_ACCEPT without offerLineageId → UNCORRELATABLE → fence
WEBRTC_ICE without offerLineageId     → UNCORRELATABLE → fence

FORBIDDEN: missing lineage → assume latest
```

---

## Frozen root cause

```text
GROUP signaling = peer/session-level
Media execution = PC-generation scoped

Missing bindings:
  Offer → PC lineage
  GROUP_ACCEPT → offer lineage
  ICE → PC lineage
```

**GIDR:** CORRECT / RETAINED — exposed F7, did not invalidate delivery contract.

---

## GPLB-T1..T4

See prior draft sections (unchanged). Implementation adds `GroupOfferBinding`, `GroupPcLineageBindingSupport`, `GroupPcLineageWire`.

---

## Correlation gates (frozen)

**Initiator GROUP_ACCEPT:**

```text
GROUP_ACCEPT(L) → lookup GroupOfferBinding(L)
  LIVE + peer/session + pcLineage match → applyRemoteAnswer on bound PC
  STALE / UNKNOWN → fence (never current-PC guess)
```

**ICE:**

```text
WEBRTC_ICE(L) → binding lookup
  LIVE → apply or lineage-scoped queue (binding known, PC not ready)
  STALE / UNKNOWN → fence (no queue-for-future-current-PC)
```

Exception: pre-accept ICE may queue when `pendingGroupInviteOfferLineageByPeer` matches L (binding not yet recorded).

---

## GPLB-EG1..EG7

| Gate | Requirement |
|------|-------------|
| EG1 | Offer A → binding A → ACCEPT-A → PC-A |
| EG2 | Retry B does not overwrite A identity |
| EG3 | Late ACCEPT-A fenced when B current |
| EG4 | ACCEPT-B → PC-B |
| EG5 | Late ICE-A fenced from PC-B |
| EG6 | Queued ICE-B drains on PC-B |
| EG7 | GIDR/GIE/IGIE + release regression PASS |

---

## Implementation seam (2026-09-02)

| Component | Change |
|-----------|--------|
| `GroupOfferBinding` | Durable offer ↔ pcLineage binding |
| `GroupPcLineageBindingSupport` | Record / correlate / fence |
| `GroupPcLineageWire` | GROUP_ACCEPT + ICE JSON correlation |
| `TalkbackSession` | `groupOfferBindingsByKey`, `pendingGroupInviteOfferLineageByPeer` |
| `TalkbackCoordinator` | GIE binding record; IGIE echo L; GPLB accept gate; lineage ICE |

---

## Not authorized

```text
ICE restart · PC resurrection · current-PC fallback
SDP inference · peer-only correlation
GIDR/GIE/IGIE redesign · Conference authority reuse
transmitReady semantic changes
```

---

## Field gate

```text
GROUP_INVITE(L) → IGIE → GROUP_ACCEPT(L) → bound PC → ICE(L)
→ ICE_CONNECTED (M03↔M02) → transmitReady observation

PASS → F7 / GPLB FIELD PASS / CLOSED FOR THIS DEFECT
```

---

## Sign-off

```text
☑ Option A wire correlation frozen
☑ GPLB-T1..T4 + A1..A3 frozen
☑ Parent implementation cut delivered
☑ ACCEPT correlation field pass (20260902-095312)
☑ F7a / GPLB-LC authorized (binding lifecycle)
□ LC-EG1..EG6 PASS
□ GPLB-LC FIELD CONFORMANCE (Case A)
```
