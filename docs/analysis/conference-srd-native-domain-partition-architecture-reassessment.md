# Native Domain Partition — Architecture Reassessment

**ID:** conference-srd-native-domain-partition-architecture-reassessment  
**Date:** 2026-08-21  
**Version:** v0.2  
**Type:** Architecture Reassessment · **Decision Required**  
**Status:** **OPEN** — no implementation · no lab spike authorization

**Prerequisite:** [D-α lab FAIL](../../logs/dalpha-lab-spike-20260821-155612/ADJUDICATION.txt) · [Model C/D spike](./conference-srd-model-cd-partition-design-spike.md) · [B2-2-A FAIL](./conference-srd-b2-2a-native-parallel-domain-audit.md) · [B2-1 IA-001](./conference-srd-native-domain-isolation-implementation-authorization-001.md)  
**Code baseline:** `stream-webrtc-android:1.3.10` · `WebRtcSharedFactory.kt` · `SharedLocalAudio.kt`

---

## Executive summary

> **B2-1 has established ownership, admission, and failure visibility. D-α FAIL proves the current WebRTC Android runtime offers no verified safe per-edge native partition path. The decision now required is not "D-β vs D-γ" — it is whether the product retains native-level failure containment (original P0.1g) or accepts containment uplifted to the Conference layer.**

---

## Frozen ruling

```text
RCA-B                    CLOSED
PO-1 / PO-2 / PO-2b      COMPLETE
PO-3                     ACCEPTED

B2-1                     RETAIN
D-α                      REJECTED (FAIL)
D-β                      ON TABLE / UNKNOWN / NOT AUTHORIZED
D-γ                      ON TABLE / NOT AUTHORIZED
                         (long-term media architecture — not P0 default)
Upper-layer containment  ON TABLE

P0.1g                    HOLD — ORIGINAL CONTRACT
Implementation           NOT AUTHORIZED
Lab                      NOT AUTHORIZED
```

---

## Why this document exists

D-α did not fail as a minor API limitation. It failed at **native construction** of a second `PeerConnectionFactory` bound to the same `JavaAudioDeviceModule`:

```text
2 Factory + 1 ADM
        ↓
native construction (Factory B)
        ↓
libjingle_peerconnection_so.so
SIGSEGV / SIGABRT (worker_thread)
```

Reproduced on M01 + M03. V4 (native SRD independence while A stuck) was **never reached**.

This is evidence of a **WebRTC Android runtime boundary**, not a missing Java wrapper. The correct next step is **architecture decision**, not another lab spike to "try out an answer."

---

## Primary decision fork (Decision 1)

The reassessment does **not** begin with a four-way technical vote. It begins with one product/architecture question:

> **Does Conference require that one edge's native SRD hang must not prevent another edge's independent native SRD realization?**

This is the original P0.1g failure-containment contract.

### Option X — Retain original P0.1g

**Requires:**

```text
M03 native SRD hang
AND
M04 independent native realization
```

**Implication:**

```text
D-α  REJECTED
D-β  ON TABLE — UNKNOWN (not "likely feasible")
D-γ  ON TABLE — long-term media architecture investigation
```

Native/media architecture investigation continues. No path is currently verified safe.

**Constraint:** D-β lab PASS on 2 edges **does not** authorize production. Must pass 4–10p resource model review (see Q4).

---

### Option Y — Amend P0.1g

**Accept:**

```text
single native domain (shared signaling worker)
```

**Allow:**

```text
M03 native hang
  →
M04 cannot realize native SRD
  →
M04 must receive explicit edge-local failure state
  →
no invisible wait
```

**P0.1g redefined as:**

> Failure must be **explicit and bounded**, not necessarily **independently recoverable at native SRD level**.

**Implication:**

```text
Single native domain + B2-1
  →
Define EDGE_LOCAL_FAILURE semantics
  →
Separate P0.1g contract amendment (ADR — not authorized here)
```

**Critical boundary:**

```text
Single native domain + upper-layer isolation
        ≠
P0.1g original acceptance
```

Unless P0.1g is **formally amended** with product approval. This is a **contract decision**, not a technical workaround.

---

### P0.1g status (frozen — do not amend now)

```text
P0.1g    HOLD — ORIGINAL CONTRACT
```

No contract relaxation to enable further testing. After Decision 1:

```text
retain original P0.1g
  OR
amend P0.1g (separate ADR + product sign-off)
```

---

## Q1 — Where did D-α fail?

### Observed sequence (authoritative)

| Step | Result | Evidence |
|------|--------|----------|
| `PeerConnectionFactory.initialize` (once) | PASS | Harness |
| `JavaAudioDeviceModule` create (once) | PASS | Harness |
| Factory A `createPeerConnectionFactory()` + ADM | PASS | `onNetworkThreadReady` / `onSignalingThreadReady` / `onWorkerThreadReady` |
| Factory B `createPeerConnectionFactory()` + **same ADM** | **FAIL** | SIGSEGV (M03) / SIGABRT (M01) in `worker_thread` |

Harness: `DAlphaLabFactories.create()` — Factory A then Factory B, same ADM reference.

### What we can conclude

```text
Factory A ready
  →
Factory B + same ADM construction
  →
libjingle worker crash
```

```text
D-α = FAIL    (frozen)
D-β = UNKNOWN (not tested; different variable set)
```

### What we cannot conclude (evidence boundary)

| Claim | Status | Reason |
|-------|--------|--------|
| "Multi `PeerConnectionFactory` is unsupported" | **NOT PROVEN** | Factory A succeeded; crash is at B + shared ADM |
| "Shared ADM across factories is definitively forbidden" | **STRONG RUNTIME EVIDENCE** | Crash at B construction with same ADM; not API rejection |
| "Per-edge native SRD independence is achievable via factory partition" | **NOT PROVEN** | V4 never reached |
| "D-β will work" | **UNKNOWN** | Not tested |

**Tight verdict:** failure is localized to **Factory B + shared ADM construction stage**. Do not upgrade to "multi-Factory itself unsupported."

### Tombstone gap

Backtraces show `libjingle_peerconnection_so.so` / `worker_thread` only. Wording stays at **construction stage**, not internal attach API names.

---

## Q2 — Verified implementation paths (not "is the goal over-demanding?")

### What evidence actually proves

```text
1 Factory + N PC
    →
native SRD shared domain (B2-2-A, PO-2b)
    →
does NOT satisfy original P0.1g

2 Factory + 1 ADM
    →
construction crash (D-α)
    →
no verified safe path
```

### What evidence does NOT prove

> Per-edge native isolation is **theoretically over-demanding**.

That would be an architecture negation. We have **no basis** for that claim.

### Formal assessment

> Under current `stream-webrtc-android:1.3.10` / Android runtime / existing audio architecture, **there is no verified safe per-edge native isolation implementation path.**

This is **implementation capability unknown / runtime-constrained** — not a proof that native-level containment is impossible in principle.

| Path | Status |
|------|--------|
| Model C (1 Factory, N PC) | **Proven insufficient** for original P0.1g |
| D-α (N Factory + 1 ADM) | **REJECTED** — construction crash |
| D-β (N Factory + N ADM) | **UNKNOWN** |
| D-γ (PCM / relay) | **UNKNOWN** — large scope; media architecture redesign |
| Single domain + upper-layer | **Viable only under amended P0.1g** |

### Why original P0.1g cannot PASS on single native domain

Current WebRTC native domain behavior:

```text
M03 native hang
       ↓
shared signaling worker
       ↓
M04 blocked (before sdpApplyMutex — PO-2b)
```

Without native partition:

```text
Single native domain + upper-layer isolation
        ≠
P0.1g original acceptance
```

Upper-layer containment changes the **isolation layer**, not the native fact. Original P0.1g requires M04 **native realization** — which single-domain architecture has not demonstrated.

**B2-1 remains valuable** regardless: ownership, admission, failure visibility. Not withdrawn because D-α failed.

---

## Q3 — Technical directions (subordinate to Decision 1)

Evaluation dimensions (all required):

```text
native isolation capability
+
4–10p resource model
+
Android lifecycle safety
+
audio architecture complexity
+
failure containment (P0.1g intent — original or amended)
```

### Directions matrix

| Direction | Role | Native isolation | 4–10p | P0.1g (original) | Status |
|-----------|------|------------------|-------|------------------|--------|
| **D-α** | Factory partition + shared ADM | Intended per-edge | Plausible (1 ADM) | Would satisfy if proven | **REJECTED** — [adj](../../logs/dalpha-lab-spike-20260821-155612/ADJUDICATION.txt) |
| **D-β** | Per-edge Factory + per-edge ADM | Intended per-edge | **N×Factory×ADM** — questionable at 8–10p | Would satisfy if proven | **ON TABLE / UNKNOWN / NOT AUTHORIZED** |
| **D-γ** | PCM / relay | Architectural — decouples per-PC native SRD domain requirement | Moderate–high impl | Achievable at media layer | **ON TABLE** — **long-term media architecture**; not P0 default; not parallel to D-β as "another partition fix" |
| **Single domain + upper-layer** | Containment uplift | Admits shared worker | Low (status quo) | **Does NOT satisfy original** | **ON TABLE** — requires Option Y + P0.1g amendment |
| **B2-1** | Safety / ownership / observability | No native independence | Negligible | Enables explicit failure under Option Y | **RETAIN** — foundation, not final isolation |

### Layering

```text
B2-1  =  safety / ownership / observability foundation (RETAIN)

Decision 1  →  Option X or Option Y

Option X  →  D-β / D-γ investigation (none authorized here)
Option Y  →  single domain + B2-1 + EDGE_LOCAL_FAILURE semantics + P0.1g ADR
```

B2-1 must **not** be grouped with D-β/D-γ as interchangeable isolation solutions.

### B2-1 — upper-layer containment is not evasion

B2-1 already demonstrates value under single native domain:

```text
M04:
  LEASE_BUSY
  EDGE_LOCAL_FAILURE
```

vs prior behavior:

```text
M04:
  signalingState() JNI
    ↓
  opaque block
    ↓
  SRD_TIMEOUT
```

Under Option Y, containment model:

```text
unrecoverable shared native obstruction
        ↓
explicit detection (B2-1)
        ↓
edge-local failure
        ↓
no invisible timeout
```

This is **changing the fault-isolation layer**, not avoiding the problem.

### D-β — frozen constraints

```text
D-β = UNKNOWN

2 Factory + 2 ADM lab PASS
        ↓
does NOT authorize production

Must answer at N=8, N=10:
  Factory count · ADM count · thread count
  memory · CPU · audio device lifecycle
  startup latency · join/leave churn
```

D-β is **not** the recommended immediate next step. No lab authorization in this document.

### D-γ — repositioned

D-γ is not a peer "implementation option" to D-β. It is:

> **Media architecture redesign so Conference no longer requires each PeerConnection to own an independent native SRD fault domain.**

Mark as:

```text
D-γ = long-term media architecture option
```

Not P0 fix default. Not authorized here.

---

## Q4 — 4–10 person Conference resource model

If Option X is chosen and D-β is the leading native path:

```text
N people → N Factory → N ADM → N native signaling domains
```

| Concern | Assessment |
|---------|------------|
| Memory / threads | Likely unsustainable on conference Android devices |
| Audio capture | Multiple ADM / capturer — platform instability risk |
| Lifecycle | Factory create/destroy under edge churn |
| Product fit | 2-edge lab PASS insufficient for production authorization |

**Frozen:**

> D-β cannot obtain production authorization from a 2-edge lab PASS alone.

---

## Retained assets

| Asset | Role | Status |
|-------|------|--------|
| B2-1 Commit 1–3 | Lease · admission gate · observability | **RETAIN / PASS** |
| PO-3 contract | Native SRD domain ownership semantics | **ACCEPTED** |
| RCA-B | Native SRD non-return on stuck edge | **CLOSED** |
| D-α lab harness | Read-only proof artifact | **COMPLETE — FAIL** |

---

## Decision required (this document does not decide)

**Decision 1 (primary):** Retain original P0.1g (Option X) or amend (Option Y)?

**Subordinate (only after Decision 1):**

| If Option X | If Option Y |
|-------------|-------------|
| D-β feasibility + 4–10p resource review | P0.1g contract amendment ADR |
| D-γ long-term media architecture scoping | EDGE_LOCAL_FAILURE semantics definition |
| Separate lab/impl authorization per path | B2-1 + upper-layer containment design |

---

## Prohibited (frozen)

```text
Production D-α implementation
D-β / D-γ lab without separate authorization
Commit 4 slot reuse / quarantine wiring
P0.1g field rerun
Amend P0.1g without product decision (contract still HOLD — ORIGINAL)
Assume "just add another Factory" fixes native isolation
Revoke B2-1 because D-α failed
Use 2-edge D-β lab PASS as production authorization
```

---

## Posture

```text
RCA-B                    CLOSED
PO-1 / PO-2 / PO-2b      COMPLETE
PO-3                     ACCEPTED

B2-1                     RETAIN
D-α                      REJECTED
D-β                      ON TABLE / UNKNOWN / NOT AUTHORIZED
D-γ                      ON TABLE / NOT AUTHORIZED (long-term media architecture)
Upper-layer containment  ON TABLE

P0.1g                    HOLD — ORIGINAL CONTRACT
Partition implementation HOLD
Commit 4                 HOLD
Implementation           NOT AUTHORIZED
Lab                      NOT AUTHORIZED
```

---

## Next

**Active:** [Conference Native Failure Containment Decision](./conference-native-failure-containment-decision.md) — Decision 1 only (Option X vs Y).

Not authorized by reassessment or decision documents:

- D-β lab spike
- D-γ implementation
- P0.1g amendment (unless Option Y recorded)
- Production changes
- Commit 4

Optional (does not close P0.1g): IA field Commit 1–3 (BUSY path adjudication).

---

## Changelog

| Date | Version | Change |
|------|---------|--------|
| 2026-08-21 | v0.1 | Post D-α FAIL reassessment; partition HOLD; Q1–Q4; decision required |
| 2026-08-21 | v0.2 | Decision 1 fork (Option X/Y); Q2 tightened; D-β UNKNOWN; D-γ repositioned; P0.1g HOLD original; B2-1 upper-layer value; D-β prod gate frozen |
