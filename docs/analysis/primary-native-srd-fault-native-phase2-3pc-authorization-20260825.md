# 9b Phase-2 Round-2 — 3PC Minimal Lab (FINAL expansion)

**ID:** primary-native-srd-fault-native-phase2-3pc-authorization-20260825  
**Date:** 2026-08-25  
**Status:** **AUTHORIZED · ONE ROUND ONLY**  
**Parent:** [primary-native-srd-fault-native-phase2-repro-authorization-20260825.md](./primary-native-srd-fault-native-phase2-repro-authorization-20260825.md)

---

## Question

> Does **N=3** attached PC + shared track + sequential SRD reproduce production ordinal hang where 2PC did not?

---

## Topology (fixed)

```text
1 Factory · 1 ADM · 3 PC · shared local track (attachedPcCount=3)
Sequential: SRD#1(A) → E3 surrogate → SRD#2(B) → E3 → SRD#3(C)
```

---

## Arms (only two)

| Arm | PC-A media before B/C SRD |
|-----|---------------------------|
| **3PC-A** | inactive |
| **3PC-B** | active ICE/media (CHECKING+) |

---

## Outcome routing

| Result | Next |
|--------|------|
| 3PC-B HANG @ ord 2/3, 3PC-A PASS | native/runtime capability review — **stop Java patches** |
| 3PC all PASS | **stop lab expansion** — wait for production hang + native stack (C) |
| FAIL_SETUP | fix harness only (one retry) |

**NOT authorized after this round:** 4PC+ · production replay · field · IA · Java re-prime

---

## Harness

`com.talkback.lab.pnsrd.PnsrdLabThreePcInstrumentedTest`
