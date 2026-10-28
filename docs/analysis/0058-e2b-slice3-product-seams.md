# ADR-0058 E2b Slice 3 — C-IG-01 Product Seams

## Status

**PASS / CLOSED** (2026-09-02)

```text
Target:   R13 + R14 only
Contract: FROZEN — no changes
ADR-0057: OUT OF SCOPE
ADR-0056: no Meeting cutover
GROUP PTT: untouched
```

**Change:** Product Android seams behind existing C-IG-01 injectors.

- `AndroidWifiMulticastLockSeam` — `WifiManager.MulticastLock` (non-refcount; transport-scope owned)
- `MulticastSocketTransportRebindSeam` — real `MulticastSocket` rebuild + group re-join
- `AndroidRuntimeResources.forAndroidProduct(Context)` — product assembly (not Fake defaults)

**Tests:** `Profile03E2bSlice3ProductSeamsTest`

---

## Slice 3 EXIT

| Gate | Result |
|------|--------|
| R13 lock held across silence / V=0 / empty Top-K | PASS (real `MulticastLock`) |
| R13 transport-scope end releases lock + closes socket | PASS |
| R14 socket rebuild / re-join; authority + HARD FENCE preserved | PASS |
| R14 rebind does not release MulticastLock | PASS |
| Product factory does not install Fake seams | PASS |
| R01–R12, R15, R16 regression | PASS (existing harnesses) |

```text
E2b after Slice 3:
  PASS: 16 (R01–R16)
  GAP:   0
  CONTRACT_CONTRADICTION: 0
```

---

## Out of scope (unchanged)

```text
TalkbackSession / Meeting UI wiring
ADR-0056 Anchor cutover
Field / 8–10 member multicast conference
AudioTrack product seam (R15 remains evidence-path + RecordingAudioTrackSeam)
```

## Next

Product session wiring — **not authorized by this slice**. Re-discuss after E2b 16/16.
