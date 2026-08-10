# RCA-001 — Media action ownership (engineering)

**Status:** acceptance patch **NOT VALIDATED** (prereq not reached)

```text
Phase-1 Progress                 CLOSED
Phase-2 Delivery                 VERIFIED
Media Action Ownership           VERIFIED
CONFERENCE_SAME_SESSION_REJOIN_ACCEPTANCE_MISSING
  PATCHED · field NOT VALIDATED
  (20260810-180016: invite=0 · REATTACH EXPIRED · hangup ENV)
```

Field inconclusive: `logs/conf-same-session-rejoin-20260810-180016/`  
Patch note: `docs/analysis/conference-same-session-rejoin-acceptance-patch.md`  
Asymmetry audit: `docs/analysis/conference-same-session-rejoin-acceptance-audit-001.md`  
Symptom audit: `docs/analysis/post-handoff-invite-busy-audit-001.md`

---

## Causal chain

**Before (closed):**

```text
HOST_RESTART → media owner gate → PARTICIPANT_REATTACH owns → REJECT
  → CALL_REJECT → FAILED_MEDIA
```

**After ownership (remaining):**

```text
HOST_RESTART → SUPERSEDED → invite dispatch → ?
  ├─ EDGE_RECOVERED (ICE path; e.g. 17:39)
  └─ CALL_REJECT reason=BUSY → FAILED_MEDIA (e.g. 17:34)
```

Not clearOwner / not timeout / not global always-win.

---

## Field checks (ownership layer) — PASS

| Check | Result |
|-------|--------|
| `PARTICIPANT_REATTACH → SUPERSEDED → HOST_RESTART` | PASS |
| `RECOVERY_MEDIA_OWNER_REJECTED` (PARTICIPANT←HOST) | **0** |
| Every flap → `EDGE_RECOVERED` | PARTIAL (post-handoff open) |

Do **not** treat early `CALL_REJECT` as supersede failure when the two ownership markers above hold.

---

## Post-handoff matrix (same SUPERSEDED, diverge after invite)

| Time | supersede | invite received | reject reason | answer | recovered |
|------|-----------|-----------------|---------------|--------|-----------|
| 17:34:06 | yes | yes (2055 SDP) | **BUSY** (INV-MEM-001) | no | no |
| 17:35:56 | yes | no in window (M01 edge) | n/a / prior BUSY loop | no | no (M03 timeout) |
| 17:39 | HOST assigned (no PARTICIPANT clash) | yes: 741 snapshot then 2055 | BUSY **after** recover | no GROUP_ACCEPT | **yes** via ICE CONNECTED |

Question answered in part: divergence is **not** ownership; reject class is **Case 2 `BUSY`**, not `RECOVERY_MEDIA_OWNER_REJECTED`. Success did **not** accept the SDP invite — ICE reconnected first; post-recover BUSY is ignored as duplicate.

Code seam (next attribution, not yet patched): `handleGroupInvite` reconnect path is **GROUP-only**; same-session **CONFERENCE** invite falls through `prepareForGroupInvite` → `BUSY`.

---

## If still failing

Next layer: invite/busy/session-state gate — **not** recovery infrastructure reopen.

Corpus seed: `logs/rra005-field-ep-posthoc-20260810-170452/`  
Retest: `logs/rca001-ownership-handoff-20260810-173234/`

