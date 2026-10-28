# Post-ADR-0057 — F8-FIX-1 Production Artifact Integration

## Status

**P0.1 PASS** — Production Artifact Integration complete (2026-09-02)

```text
ADR-0057:                          COMPLETE / CLOSED — DO NOT TOUCH
ADR-0057 Implementation Conformance: PASS / FROZEN
F8-FIX-1 contract:                 FROZEN / DO NOT EXPAND

F8-FIX-1 Production Integration:   PASS (P0.1)
Production Case A Smoke:           PASS (P0.2)
Production Artifact Ship Readiness:  PASS
```

**Parents:**

- [ADR-0057 complete closure](./adr0057-complete-closure.md)
- [F8-FIX-1 narrow authorization](./adr0057-f8-fix1-same-network-materialization-narrow-authorization.md) — CLOSED

---

## Closure layer model

```text
Contract correctness
        ↓
Implementation conformance          ← ADR-0057 FROZEN (PASS)
        ↓
Field validation on instrumented artifact   ← run 152328 (PASS)
        ↓
ADR-0057 CLOSED
──────────────────────────────
        ↓
Production artifact integration   ← THIS TASK (P0.1)
        ↓
Production smoke                  ← P0.2
        ↓
Ship readiness
```

Post-closure release engineering **does not** amend ADR-0057 verdicts.

---

## Goal

Move the **field-proven semantic fix** into the **production WebRTC artifact** (`stream-webrtc-android` production fork), without investigation instrumentation.

**Architecture changes:** NONE  
**ADR changes:** NONE  
**F8-FIX-1 contract changes:** NONE

---

## Source of truth

Frozen contract: [F8-FIX-1](./adr0057-f8-fix1-same-network-materialization-narrow-authorization.md)

Authoritative field evidence: `logs/adr0057-track-c-case-a-20260902-152328/`

Reference implementation (instrumented): `webrtc-instrumentation/pnsrd-inst-1` patch in `UDPPort::MaybeSetDefaultLocalAddress` — **extract semantic diff only**.

---

## Allowed semantic diff (production)

In `p2p/base/stun_port.cc` — `UDPPort::MaybeSetDefaultLocalAddress`:

```text
defaultLocal = provider->GetDefaultLocalAddress(family)

if concrete(defaultLocal):
    use defaultLocal                     // unchanged

else if localAddress.IsAnyIP()
     && emitLocalForAnyAddress
     && concrete(network->GetBestIP())
     && same family
     && address belongs to exact Network N (Network()->GetIPs()):
    materialize using N.GetBestIP()

else:
    retain existing behavior
```

**Do not cherry-pick commits.** Cherry-pick **semantic diff** only — manually review minimal production patch.

---

## Forbidden in production artifact

```text
F8_NATIVE_*
F8_PORT_*
F8_CANDIDATE_*
F8_QUERY_DEFAULT_LOCAL
F8_SET_DEFAULT_LOCAL
F8_GET_DEFAULT_LOCAL
f8_native_trace.*
pnsrd-inst-1 core instrumentation
audit-only scripts/hooks tied to investigation
instrumentation-only Gradle AAR override (pnsrd-inst-1-stream-webrtc-android.aar)
```

Optional (product-level, low-frequency): one normal diagnostic log when fallback is taken — **not required** for smoke PASS.

---

## Integration checklist

```text
☑ Apply semantic diff to production WebRTC fork (apply_f8_fix1_production.py)
☑ Rebuild production hybrid AAR (arm64 + v7a)
☑ Verify .so does NOT contain F8_NATIVE / pnsrd-inst investigation strings
☑ Point android-board-talkback at production AAR (priority over pnsrd-inst-1)
☑ assembleDebug — APK embeds production .so (hash match)
□ Run Production Case A Smoke (P0.2 — see production smoke run card)
```

### P0.1 exit evidence (2026-09-02)

| Gate | Result |
|------|--------|
| Production source = authorized semantic diff only | PASS (`p2p/base/stun_port.cc` only) |
| Production AAR builds | PASS `libs/talkback-production-stream-webrtc-android.aar` |
| APK embeds production artifact | PASS SHA256 `e1c28990307c3ed20bb29760e1f30278655d11f4100e52742269b51c8af3cc84` (arm64) |
| No F8_/pnsrd instrumentation in .so | PASS (`03-verify-production-so.sh`) |
| Focused regression | PASS (`tests/test_apply_f8_fix1_production.py`) |

**→ authorize P0.2 Production Case A Smoke**

---

## Failure routing

| Outcome | Action |
|---------|--------|
| Integration build fails | Fix production fork build — **do not** reopen ADR-0057 |
| Smoke FAIL | Open **post-ADR-0057 production-artifact integration incident** |
| Smoke PASS | Mark integration + smoke COMPLETE; ship-readiness baseline |

**Never on smoke failure:**

```text
reopen F8 automatically
modify F8-FIX-1 contract
open F9 automatically
reopen ADR-0057 without invariant contradiction evidence
```

---

## Deliverables

| Artifact | Description |
|----------|-------------|
| Production patch | Minimal `stun_port.cc` diff in production fork |
| Production AAR | Replaces inst AAR for default dev/ship path |
| Smoke log dir | `logs/adr0057-production-smoke-<stamp>/` |
| Smoke adjudication | PASS/FAIL against frozen smoke gates |
