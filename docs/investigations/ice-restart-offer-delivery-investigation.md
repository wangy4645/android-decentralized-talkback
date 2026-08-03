# Recovery Signaling Delivery Assurance

**Workstream:** Recovery Signaling Delivery Assurance  
**Status:** ADR-0035 **Accepted** — PR1–PR4 **CLOSED** — Completion convergence **OPEN** (ADR-0022)  
**ADR:** [0035-recovery-scoped-delivery-assurance.md](../adr/0035-recovery-scoped-delivery-assurance.md) (Appendix A = PR2 contract)

## ADR Q1–Q5 — CLOSED

| Q | Decision |
|---|----------|
| Q1 | YES — Recovery-Scoped Lineage Reliable Signaling |
| Q2 | A — `RECOVERY_REATTACH_ACK(L*)` sole delivery confirmation |
| Q3 | YES — `DELIVERY_PENDING` delivery phase |
| Q4 | YES — Recovery-owner bounded retransmit |
| Q5 | A — `DELIVERY_EXHAUSTED` fact → Episode policy |

## PR1 Observability — PASS / CLOSED (2026-07-29)

Established fact chain:

```text
REQUESTED → LOCAL_ACCEPT → DELIVERY_PENDING → (REMOTE_RECEIVED + ACK) → DELIVERY_CONFIRMED
```

PR1 soak (`logs/signal-path-20260729-200203/`): all recovery edges `classification: DELIVERY_PENDING`; zero `RECOVERY_REATTACH_ACK`; `REMOTE_RECEIVED: FAIL` on M01→M03 and M02→M03 L1.

**Excluded:** ACK-loss hypothesis; lineage-mismatch-as-root-cause; negotiation answer path as D1 explanation.

Report: `RECOVERY_DELIVERY_REPORT.txt` per session (analyzer: `scripts/analyze-recovery-delivery.ps1`).

## PR2 Bounded Retransmission — Grill CLOSED (2026-07-29)

Full contract: [ADR-0035 Appendix A](../adr/0035-recovery-scoped-delivery-assurance.md#appendix-a--pr2-bounded-retransmission-grill-closed-2026-07-29).

| PR2-Q | Decision |
|-------|----------|
| Q1 | A — Episode Owner policy + scheduling; Coordinator dispatch; Transport facts only |
| Q2 | C — Timer backstop + reachability hint early re-evaluate; neither implies delivery success |
| Q3 | A — Independent clocks; count budget; fixed interval; no watchdog/obligation coupling |
| Q4 | A — `DELIVERY_EXHAUSTED` → `WAITING`; no auto-supersede / new lineage / recovery terminal |
| Q5 | A — `maxDeliveryAttempts=3`; `deliveryRetryIntervalMs=3000`; in-memory only |

Invariants added: INV-DELIVERY-004 .. INV-DELIVERY-007.

### PR2 implementation checklist

1. **State** — per-lineage delivery state (`PENDING` / `RETRY_PENDING` / `CONFIRMED` / `EXHAUSTED`) on Episode; same `offerLineageId` on retry.
2. **Episode** — `deliveryRetryTimer`; hint → decision point; exhaustion → `WAITING(DELIVERY_EXHAUSTED)`; no watchdog refresh on retry.
3. **Coordinator** — `dispatchRecoveryOffer(identity)` with `deliveryAttempt++`; defer when `!canDispatchRecoverySignal()`; late ACK discard.
4. **Logs** — `RECOVERY_DELIVERY_RETRY_PENDING`, `RETRY_DEFERRED`, `DELIVERY_EXHAUSTED`, etc.
5. **Analyzer** — `D1_RETRY_CONFIRMED`, `D1_EXHAUSTED`, per-attempt timeline.
6. **UT** — Cases A (hint retry + ACK), B (single ACK, no retry), C (exhaust + late ACK discard).

### PR2 review risks (must not ship if violated)

- retry in Transport
- delivery timer touches `RECOVERY_WATCHDOG` or `obligationDeadlineAt`
- ACK / `DELIVERY_CONFIRMED` → `RECOVERED`
- delivery retry allocates new `offerLineageId`

## NEXT

**Completion convergence** — M03 local obligation not closing when peer RECOVERED (ADR-0022 ownership). Out of PR4 delivery scope. See [Follow-up Workstream](#follow-up-workstream) below.

Frozen (do not reopen): ADR-0034 UVCP semantics; Drain; Completion policy rewrite; **PR2**; **PR3-1**; **PR4** (Grill + soak **PASS** — **CLOSED**).

## D1 Ingress Investigation — CLOSED (classification) (2026-07-29)

**Evidence:** `logs/signal-path-20260729-212225/`  
**Session:** `189b7d5e-722c-4b29-9eda-1f72db30a7c8`  
**Primary edge:** M02→M03, `offerLineageId=L1`, `recoveryAttemptId=1`

### Breakpoint (confirmed)

```text
M02 RECOVERY_OFFER_SENT / LOCAL_ACCEPT (×3 deliveryAttempt)
        |
        X  no RECOVERY_REATTACH_RECEIVED on M03
        |
M03 ingress silent during delivery window
```

PR2 retry/exhaustion **works as designed**: `deliveryAttempt=1→2→3`, `DELIVERY_EXHAUSTED`, `d1: D1_EXHAUSTED`. Failure is **before** ACK/negotiation.

### Code path: RECOVERY_REATTACH vs GROUP_MESH

**No separate recovery UDP path.** Both use:

```text
dispatchRecoveryOffer / attemptConferencePeerOffer
  → buildSignedEnvelope(SignalType.GROUP_JOIN, …)
  → signalingChannel.send(peer, envelope)   // UdpSignalingChannel
```

| Aspect | RECOVERY_REATTACH | GROUP_MESH |
|--------|-------------------|------------|
| Signal type | `GROUP_JOIN` | `GROUP_JOIN` |
| Transport | `UdpSignalingChannel.send` | same |
| Payload diff | `joinIntent=RECOVERY_REATTACH`, lineage fields | `joinIntent=NORMAL_JOIN` |
| Observation | `pathKind=RECOVERY_REATTACH` | `pathKind=GROUP_MESH` |

Ingress on peer: `UdpSignalingChannel` receive loop → decode → `REMOTE_RECEIVE` for any `GROUP_JOIN` (lineage in payload). Recovery-specific handling is **Coordinator** (`observeRecoveryOfferIngress`, ACK), not a different socket/route.

### Soak timeline — M02→M03 L1

| Time (M03) | Event |
|------------|-------|
| 21:23:19 | Last `REMOTE_RECEIVE_OBSERVED` from M02 (HELLO/HEARTBEAT, socketId=4) |
| 21:23:21.548 | `NETWORK_LOST` socketId=4 |
| 21:23:21.885 | `SIGNAL_SOCKET_REBIND` socketId=5, `boundNetworkId=unbound`, `localAddress=::` |
| 21:23:21+ | Outbound `ENETUNREACH` to 192.168.31.214 (M02) on socketId=5 |
| 21:23:33–42 | M02 sends GROUP_JOIN recovery (2484/2173 bytes) → 192.168.31.190:50000; **zero** inbound from M02 on M03 |
| 21:23:54.754 | `networkId=174` available |
| 21:23:54.806 | socketId=6 rebound, `boundNetworkId=174`, `localAddress=192.168.31.190` |
| 21:23:55+ | HELLO/HEARTBEAT from M02 resume; GROUP_MESH from M01 on socketId=6 |

M02 sender during window: `SIGNAL_DATAGRAM_SENT` socketId=2, `localIp=192.168.31.214`, `dstIp=192.168.31.190` — destination **correct**; not D1-D.

### D1 ingress subclass (analyzer — delivery classification)

| Class | Meaning | M02→M03 soak |
|-------|---------|--------------|
| **D1-A** | Sender LOCAL_ACCEPT ok; peer ingress not receiving (NETWORK_LOST, unbound rebind, no UDP from sender in window) | **MATCH** (`d1_ingress: D1-A_PEER_INTERFACE_DOWN`) |
| D1-B | Peer interface up; UDP lost before socket (best-effort still flows in window) | — |
| D1-C | Socket received datagram; recovery log chain missing | — |
| D1-D | Wrong destination / stale binding | — |

`D1-A` is the correct **delivery classification**. Investigation root cause should not stop at “peer interface down” — that is the observable symptom.

### Root cause (investigation — refined)

```text
Recovery dispatch raced with peer signaling ingress recovery window
```

（中文：Recovery dispatch 与 peer signaling ingress 重建存在时序竞争。）

Not “RECOVERY_REATTACH special path failure.” Both RECOVERY_REATTACH and GROUP_JOIN share `UdpSignalingChannel`; dispatch timing landed in a window where **peer ingress capability = false** while **sender dispatch eligibility = true**.

```text
M03                          M02
21:23:21 NETWORK_LOST
21:23:21 SOCKET_REBIND
     boundNetworkId=unbound
     localAddress=::
        |
        |  ~12–33s ingress capability false
        |
21:23:33 ─────────────────── recovery dispatch (attempt 1)
21:23:36 ─────────────────── retry
21:23:39 ─────────────────── retry
        |
21:23:54 networkId=174
21:23:55 HELLO / GROUP_MESH resume
```

This is a **capability freshness** problem, not packet loss:

```text
M03 signaling ingress capability = false
while
M02 recovery dispatch eligibility   = true
```

Note: `canDispatchRecoverySignal()` already consumes `peerSignalingReachable` ([EdgeReachabilitySnapshot](../../android-board-talkback/src/main/java/com/talkback/core/session/EdgeReachabilitySnapshot.kt)) — but that fact is **sender-local and post-fact** (recent inbound from peer on *this* device). It does not observe *peer’s* ingress rebuild after `NETWORK_LOST`. M02 dispatched while M03 was in unbound-rebind limbo; M03 later logged `peerSignalingReachable=false` for the reverse edge — asymmetric stale windows.

### PR2 soak validates mitigation boundary

```text
D1 → retry ×3 → still D1_EXHAUSTED
```

Bounded retransmission fixes **brief miss** (peer recovers in seconds; retry catches window). It does **not** fix **sustained peer ingress unavailable** (~30s). PR2 positioning remains correct: **mitigation, not repair**. Do not expand PR2.

### Analyzer upgrades

- `scripts/analyze-recovery-delivery.ps1` — adds `d1_ingress:` per transaction (delivery-window correlated)
- `scripts/analyze-ice-restart-offer-delivery.ps1` — `d1Ingress=` replaces R1/R2/R3 labels

### Non-goals (D1 session)

- No UVCP / Completion / Drain / ICE gate changes
- No ingress repair in this slice

---

## PR3 Grill — Recovery Dispatch Admission Freshness — CLOSED (2026-07-29)

**Not** a reopen of ADR-0035 PR2. **Not** “defer until peer ingress ready” as a new authority — there is no `REMOTE_SIGNALING_READY` fact today; HELLO/HEARTBEAT are post-fact only.

**Boundary (MUST hold):**

```text
Admission  ≠  Delivery
Delivery   ≠  Negotiation
Completion ≠  Recovery
```

Peer reachability hint MUST NOT become delivery guarantee (frozen error path).

### PR3-Q1 — CLOSED (2026-07-29)

| 项 | 决策 |
|----|------|
| peer evidence | ✅ 引入 |
| 形态 | **Admission Evidence Layer**（非并列 gate） |
| 类型 | Hint / evidence only |
| hard gate | ❌ |
| delivery truth | ❌ 不参与 |
| ACK contract | ❌ 不改变 |
| PR2 retry | ❌ 不修改 |
| 命名 | `PeerSignalingReachabilityEvidence`（收敛 `peerSignalingReachable` / `routeConverged`） |

```text
canDispatchRecoverySignal()
  = local dispatch capability
  + routing/discovery confidence
  + peer reachability evidence (confidence)

capability  → 能不能尝试发送
confidence  → 当前是否值得立即尝试
delivery    → 仍仅 RECOVERY_REATTACH_ACK
```

```kotlin
// discussion shape
data class PeerSignalingReachabilityEvidence(
    val lastInboundSignalAt: Instant?,
    val observedNetworkEpoch: Long?,
    val confidence: Confidence  // HIGH | MEDIUM | LOW — dispatch confidence only
)
```

**禁止：** `confidence=HIGH` → `DELIVERY_CONFIRMED`；`confidence=LOW` → peer unreachable fact；`confidence=LOW` → block forever。

Soak：M02 dispatch=true 因 stale inbound → **confidence 过高**；A 是正确层。

### PR3-Q2 — CLOSED (2026-07-29)

LOW 影响 **admission decision**，不是 admission truth。

| 项 | 决策 |
|----|------|
| LOW 影响初始 dispatch | ✅ |
| LOW 影响 retry scheduling | ✅ |
| LOW = hard unreachable forever | ❌ |
| LOW 产生 delivery failure / exhaustion | ❌ |
| LOW 消耗 deliveryAttempt | ❌ |
| LOW 创建新 lineage | ❌ |
| evidence 升级后 re-evaluate | ✅ |

```text
confidence LOW → WAITING(ADMISSION_CONFIDENCE_LOW)
                 → 未 dispatch 则无 delivery transaction

PR3: SHOULD WE SEND NOW?
PR2: DID PEER RECEIVE?
```

### PR3-Q3 — CLOSED (2026-07-29)

**Q3 = A**；`T_dispatch_fresh = 5s` 起步（soak-tunable）；`moduleStaleMs` 保持 peer liveness 职责，不与 PR3 freshness 共用。

```text
Peer facts → PeerEdgeSignalingReadiness / Evidence → Confidence → Episode admission
（不是 confidence → peer online/offline → delivery truth）
```

| Tier | 条件 | Admission |
|------|------|-----------|
| HIGH | inbound 足够新鲜 + generation/epoch 对齐 | dispatch now |
| MEDIUM | 曾可见，freshness 或 epoch 未完全确认 | `WAITING(ADMISSION_CONFIDENCE_STALE)` |
| LOW | 无近期证据 / flap 后无恢复证据 | `WAITING(ADMISSION_CONFIDENCE_LOW)` |

`MEDIUM ≠ unreachable`；`LOW ≠ unreachable`。

禁止：`MEDIUM → DELIVERY_EXHAUSTED`；`LOW → PEER_UNREACHABLE`；`HIGH → DELIVERY_CONFIRMED`；projection → new peer authority。

`T_dispatch_fresh`（5s）= recovery admission freshness；`moduleStaleMs` = peer liveness stale boundary。

### PR3-Q4 — CLOSED (2026-07-29)

**Q4 = A**；epoch 推进后 confidence ceiling = **LOW**（非 MEDIUM——旧 generation 已 invalidate，当前 epoch 无 peer ingress confirmation）。

```text
local epoch++ → invalidateGeneration() → ceiling LOW
→ post-flap inbound (current gen + fresh) → HIGH eligible
```

| Confidence | 条件 | 含义 |
|------------|------|------|
| HIGH | `observedGeneration == localRebindGeneration` + inbound after gen start + `lastInboundAge ≤ T_dispatch_fresh` | dispatch now |
| MEDIUM | 历史有效 evidence，freshness 不足 | peer 可能存在，不立即冒险 |
| LOW | 无当前 epoch evidence / invalidated | 暂缓 |

`LINK_READY` → `RE_EVALUATE`，**≠** `HIGH`。

禁止：`invalidateGeneration → DELIVERY_FAILED`；`LINK_READY → HIGH`；`confidence LOW → PEER_UNREACHABLE fact`。

### PR3-Q5 — CLOSED (2026-07-29)

**Q5 = A** + 镜像 PR2-Q2 backstop（hint + safety net）；**无** admission deadline / `ADMISSION_TIMEOUT`。

```text
WAITING(ADMISSION_CONFIDENCE_*)
  → materiality hint | admissionReevaluateBackstop
  → Episode decision point（不是 dispatch trigger）
```

`admissionReevaluateBackstop` ∈ Episode reevaluation scheduling；**不是**第四类 recovery clock（≠ `deliveryRetryTimer` / `RECOVERY_WATCHDOG` / `obligationDeadline`）。

禁止：admission defer → `DELIVERY_PENDING` / `deliveryAttempt++` / `RECOVERY_WATCHDOG` start / `obligationDeadline` refresh；`LINK_READY` → immediate dispatch。

---

## PR3 Grill — CLOSED (2026-07-29)

| Q | 决策 |
|---|------|
| Q1 | A — Admission Evidence Layer；`PeerSignalingReachabilityEvidence`；hint only |
| Q2 | A — 初始 dispatch + retry scheduling 共用 confidence；LOW defer 不产生 delivery lifecycle |
| Q3 | A — 仅 HIGH dispatch；MEDIUM/LOW defer；`T_dispatch_fresh=5s` ≠ `moduleStaleMs` |
| Q4 | A — epoch++ 后 ceiling LOW；HIGH 需 current-generation inbound + fresh |
| Q5 | A — 无 admission deadline；event hint + backstop re-evaluate |

**分层边界（冻结）：**

```text
PR3 Admission     → 现在发不发
PR2 Delivery      → 发了有没有到
Negotiation       → offer/answer 是否完成
Completion        → episode 是否结束
```

**Soak 反事实（M02→M03 L1）：** 21:23:33 MEDIUM → `WAITING(STALE)`；21:23:54 inbound → HIGH → dispatch → PR2 `DELIVERY_PENDING`；避免 send×3 → `DELIVERY_EXHAUSTED`。

---

## PR3-0 — Observation Projection — IMPLEMENTED (2026-07-29)

**Scope:** 纯观测；**不接** dispatch gate；**不**触发 PR2 delivery state。

### Delivered

| Item | Path |
|------|------|
| Projection + types | `RecoveryAdmissionFreshness.kt` |
| UT (A–E) | `PeerSignalingReachabilityProjectionTest.kt` |
| Observation logs | `TalkbackCoordinator` → `PEER_SIGNALING_REACHABILITY_CONFIDENCE` |
| Config | `recoveryAdmissionFreshnessMs = 5000` (`TalkbackCoordinatorConfig` / `TalkbackRuntimeConfig`) |
| Analyzer replay | `scripts/analyze-admission-confidence.ps1` → `ADMISSION_CONFIDENCE_REPORT.txt` |

### Verification

- **UT:** `PeerSignalingReachabilityProjectionTest` — BUILD SUCCESSFUL
- **Soak replay** (`signal-path-20260729-212225`, M02→M03):
  - `21:23:33` → MEDIUM / `WAITING_STALE` (not HIGH) — PASS
  - First inbound after dispatch (`21:23:59.620`) → HIGH / `DISPATCH_NOW` — PASS
  - `status: PR3-0_REPLAY_PASS`
  - Historical soak still shows `delivery_pending_count_historical: 3` (expected — gate not wired)

### Non-goals (unchanged)

- No `attemptConferencePeerOffer` gate
- No `WAITING(ADMISSION_CONFIDENCE_*)` emission
- No `admissionReevaluateBackstop` scheduler

**Next:** PR3-1 Admission Gate Integration (after explicit grill authorization)

---

## PR3-1 Admission Gate — PASS (2026-07-30)

**Evidence:** `logs/signal-path-20260730-072237/`  
**Scenario:** M02 host, M03 WiFi flap, primary edge M02→M03

### Admission semantics — PASS

| Lineage | Admission | First dispatch |
|---------|-----------|----------------|
| L1 | `07:30:13` `WAITING(ADMISSION_CONFIDENCE_STALE)` → `07:30:37` first `RECOVERY_OFFER_SENT` | No premature send×3 |
| L2 | `07:31:51` STALE defer → `07:32:16` first dispatch | Same |

Premature dispatch (stale → immediate `DELIVERY_PENDING` ×3) **eliminated**. PR3-1 admission gate works as designed.

**Do not conflate** with D1 / delivery outcome — admission PASS does not imply delivery CONFIRMED.

---

## D1 Ingress — CLOSED (classification upgraded)

**Original D1 scope:** ingress failure (`D1-A` peer interface down through `D1-D` correlation).

**PR3-1 soak L2 (`ad5a701f…`, M02→M03):** ingress **not** the breakpoint.

```text
UDP receive              OK
Envelope decode          OK
Recovery handler ENTER   OK
Handler decision           DROP_DUPLICATE_ICE_CONNECTED
Delivery ACK               false (no RECOVERY_REATTACH_ACK)
M02 deliveryState          DELIVERY_EXHAUSTED
```

### D1-E — `RECOVERY_HANDLER_REJECTED_AFTER_INGRESS` (frozen name)

**Definition:**

```text
RECOVERY_REATTACH
    → UDP receive OK
    → envelope decode OK
    → RECOVERY_HANDLER_ENTER
    → terminal handler decision ≠ ACCEPT
```

**Evidence chain facts:**

```text
REMOTE_RECEIVE_OBSERVED     = true
RECOVERY_HANDLER_ENTER      = true
RECOVERY_HANDLER_ACCEPTED   = false
RECOVERY_ACK                = false
deliveryState                 = not confirmed
```

**Does NOT mean:** network delivery failed.

**Means:** Delivery Assurance lacks a **handled** fact from the recovery handler layer.

ADR-0035 §7 `D2_HANDLER_REJECT` assumed ACK present + handler failed downstream. PR3-1 soak shows **no ACK** — classify as D1-E, not legacy D1 ingress.

### L2 soak — layered no-ACK causes (M03)

| Layer | Event | Effect |
|-------|-------|--------|
| Delivery ingress (pre-handler) | `RECOVERY_DELIVERY_ACK_SKIPPED reason=STALE_OBLIGATION_GENERATION` | M03 `recoveryAttemptId=3`, offer `restartAttemptId=2` |
| Handler | `DROP_DUPLICATE_ICE_CONNECTED` (`ice=CONNECTED`, `meshCompleted=true`) | Offer not applied; observation only |

`observeRecoveryDeliveryIngress()` runs **before** `acceptGroupJoin()` handler branch — lineage skip and handler drop are **independent** gates; both block ACK today.

### D1 ingress subclasses — status

| Class | PR3-1 L2 |
|-------|----------|
| D1-A peer interface down | ✗ rebind + `BIDIRECTIONAL_READY` before offers arrive |
| D1-B packet lost | ✗ `bytes=2484` GROUP_JOIN received |
| D1-C routing/filter drop | ✗ full chain to `RECOVERY_HANDLER_ENTER` |
| D1-D correlation only | ✗ explicit drop reasons logged |
| **D1-E handler reject after ingress** | **MATCH** |

---

## PR4 Grill — Handler Handled Facts — Q1 CLOSED (2026-07-30)

**Not** PR2 retry reopen. **Not** UVCP / Completion / Drain.

Full contract: [ADR-0035 Appendix B](../adr/0035-recovery-scoped-delivery-assurance.md#appendix-b--pr4-handler-handled-facts-q1-closed-2026-07-30).

### Core contract (frozen)

```text
ACK = peer received + handler processed
  ≠ recovered ≠ completion ≠ obligation closed

Only current-identity-scope handler decisions → RECOVERY_REATTACH_ACK
```

### PR4-Q1 — CLOSED: A (split)

| Sub-Q | Decision |
|-------|----------|
| **Q1a** stale lineage / split-brain | **No ACK.** `RECOVERY_HANDLER_REJECTED(STALE_OBLIGATION_GENERATION)` observation only. No fake `DELIVERY_CONFIRMED`. |
| **Q1b** `DROP_DUPLICATE_ICE_CONNECTED` | **ACK** `handlerOutcome=ALREADY_SATISFIED` → `DELIVERY_CONFIRMED` → **not** `RECOVERED` |
| **Q1c** post-CONFIRMED | Episode **reevaluate** → `RECOVERED` only if Completion policy satisfied |

### Observation taxonomy (frozen)

```text
RECOVERY_HANDLER_ACCEPTED
RECOVERY_HANDLER_REJECTED
  - STALE_OBLIGATION_GENERATION   (no ACK)
  - DUPLICATE_ICE_CONNECTED       (ACK handlerOutcome=ALREADY_SATISFIED)
  - INVALID_SESSION               (Q2)
  - INVALID_GENERATION            (Q2)
```

Not `DELIVERY_REJECTED`.

### INV-DELIVERY-006 (revised)

A recovery delivery ACK MUST only acknowledge a request belonging to the **active** recovery identity. Stale lineage → rejection observation; MUST NOT produce `DELIVERY_CONFIRMED`.

### PR4-Q2 — CLOSED (2026-07-30)

| Q | Decision |
|---|----------|
| **Q2-1** | **A** — add `handlerOutcome`: `ACCEPTED` \| `ALREADY_SATISFIED` |
| **Q2-2** | Strict identity match (all four fields); stale ACK → `RECOVERY_ACK_IGNORED`; no loose peer/episode/lineage match |
| **Q2-3** | **A** — ACK = handler outcome only; no phase / negotiation / `recovered` |
| **Q2-4** | Terminal handled → ACK; stale / malformed / unknown → observation only |
| **Q2-5** | UT Cases A–D (normal, soak B, stale C, late D) |

**ACK payload (frozen):**

```text
RECOVERY_REATTACH_ACK(
    offerLineageId,
    recoveryAttemptId,
    obligationGeneration,
    deliveryAttemptId,
    handlerOutcome    // ACCEPTED | ALREADY_SATISFIED
)
```

**INV-DELIVERY-008:** CONFIRMED requires `handlerOutcome ∈ {ACCEPTED, ALREADY_SATISFIED}` + strict identity match.

Full contract: Appendix B §B.9.

### PR4-Q3 — CLOSED (2026-07-30): A / A / A / A

| Q | Decision |
|---|----------|
| **Q3-1** | **A** — `DELIVERY_CONFIRMED` **MUST** trigger `RECOVERY_REEVALUATE(required)` |
| **Q3-2** | **A** — `ACCEPTED` / `ALREADY_SATISFIED` equally trigger; no bypass / no close |
| **Q3-3** | **A** — `deliveryConfirmedOutcome` as policy input; not completion signal |
| **Q3-4** | Episode owner decides; ACK ingress handler 不越权 `closeObligation` / `RECOVERED` |
| **Q3-5** | UT Cases A–D |

**INV-DELIVERY-009:** CONFIRMED → mandatory reevaluate; not auto-RECOVERED.

**Frozen chain:**

```text
DELIVERY_CONFIRMED
    → RECOVERY_REEVALUATE(required, deliveryConfirmedOutcome=…)
    → RECOVERED | WAITING | CONTINUE_RECOVERY   (Episode policy)
```

Core:

```text
DELIVERY_CONFIRMED closes delivery uncertainty, not recovery uncertainty.
RECOVERY_REEVALUATE resolves recovery uncertainty, not ACK handler.
```

Full contract: Appendix B §B.10.

### PR4-Q4 — CLOSED (2026-07-30): A / A / A

| Q | Decision |
|---|----------|
| **Q4-1** | **A** — `ALREADY_SATISFIED` 不直接影响 UVCP；仅 Recovery policy input |
| **Q4-2** | **A** — `DELIVERY_CONFIRMED` 不清 UVCP；经 REEVALUATE → Completion projection |
| **Q4-3** | **A** — 仅 Recovery/Completion projection owner 清除 `sessionEdgeRecovering` |
| **Q4-4** | `RECOVERY_REEVALUATE_STARTED` + `RECOVERY_PROJECTION_RESULT`；禁止 `UVCP_OVERRIDE_FROM_ACK` |
| **Q4-5** | UT Cases A–D |

**INV-DELIVERY-010:** ACK/CONFIRMED 不得直连 UVCP 或 `sessionEdgeRecovering`。

**Frozen layering:**

```text
Handler outcome → Recovery policy → Completion projection → UVCP
No reverse shortcut.
```

Full contract: Appendix B §B.11.

### PR4 Grill — CLOSED (2026-07-30)

Q1 (A split) · Q2 (A/A/A/A/A) · Q3 (A/A/A/A) · Q4 (A/A/A) — all frozen. Implementation landed; soak **PASS** — see [PR4 Delivery Contract Soak Validation](#pr4-delivery-contract-soak-validation-pass).

### PR4 non-goals

- Change PR2 retry count / interval / exhaustion semantics
- Map `DELIVERY_CONFIRMED` → `RECOVERED`
- Ingress repair / UVCP / Completion changes in this slice

---

## PR4 Delivery Contract Soak Validation (PASS)

Test: `logs/signal-path-20260730-183447`

Session: `103c9ef2-bb0d-44c4-aac9-d3c3d22244a1`

### Scope

Validated:

- RECOVERY_REATTACH handler outcome contract
- ALREADY_SATISFIED ACK semantics
- sender delivery confirmation
- no ACK → exhaustion regression

Not validated:

- completion convergence
- UVCP state transition
- obligation ownership arbitration

This soak proves the delivery chain is closed:

```text
PR3 admission
        ↓
offer dispatch
        ↓
handler decision
        ↓
delivery handled fact
        ↓
DELIVERY_CONFIRMED
```

---

### M02 → M03

Result: `D1_CONFIRMED`

Timeline @18:38:46.800:

```text
M03:
DROP_DUPLICATE_ICE_CONNECTED
    |
    v
RECOVERY_REATTACH_ACK_SENT
    handlerOutcome=ALREADY_SATISFIED

M02:
RECOVERY_DELIVERY_CONFIRMED
    handlerOutcome=ALREADY_SATISFIED
```

Conclusion: Handler terminal decision is now observable by sender.

Previous behavior:

```text
DROP_DUPLICATE_ICE_CONNECTED
        |
        v
silent
        |
        v
DELIVERY_EXHAUSTED
```

PR4 behavior:

```text
DROP_DUPLICATE_ICE_CONNECTED
        |
        v
ACK(ALREADY_SATISFIED)
        |
        v
DELIVERY_CONFIRMED
```

### M03 → M01

Result: `D1_CONFIRMED` @18:38:44.437 — same `ALREADY_SATISFIED` → `DELIVERY_CONFIRMED` chain.

---

### Frozen Contract

`DELIVERY_CONFIRMED` means:

> peer received and processed recovery intent

It does **NOT** mean:

- recovery completed
- obligation closed
- UVCP CONNECTED
- completion invariant satisfied

---

### Ordering Observation

Observed:

```text
ICE_RESTORED
    >
DELIVERY_CONFIRMED(ALREADY_SATISFIED)
```

This ordering is valid.

Reason: Delivery confirmation and recovery completion are independent facts.

If obligation already closed:

```text
RECOVERY_REEVALUATE
    → ignored(reason=no_open_obligation)
```

No contract violation.

---

## Follow-up Workstream

M03 local obligation convergence remains open.

Observed (same soak, **out of PR4 scope**):

```text
media=CONNECTED
+
obligationOpen=true
+
FAILED_MEDIA_RECOVERY after timeout
```

Ownership: [ADR-0022](../adr/0022-recovery-completion-ownership.md) Completion / Recovery ownership.

Do **not** classify as PR4 failure. UI asymmetry (e.g. M03→M02 reconnecting while M02→M03 delivery confirmed) is a completion projection issue, not a delivery contract gap.

---

## Investigation Status (final)

| Item | Status |
|------|--------|
| D1-A/B/C/D | Closed |
| D1-E handler rejection | Resolved by PR4 |
| PR2 Delivery Retry | PASS |
| PR3 Admission Freshness | PASS |
| PR4 Delivery Contract | **PASS** |
| Completion convergence | **PR5-0 authorized** (Q1–Q5 CLOSED) · PR5-1+ gated |

**PR4 is CLOSED.** No further PR4 patches planned.

---

## Session Handoff (2026-07-30)

Signal path investigation boundary — frozen stack:

```text
Transport
    ✅ UDP ingress / socket / routing excluded

Admission
    ✅ PR3 freshness gate — premature dispatch resolved

Delivery
    ✅ PR2 bounded retry
    ✅ PR4 handled ACK contract
    ✅ ALREADY_SATISFIED → DELIVERY_CONFIRMED

Recovery Policy
    ✅ DELIVERY_CONFIRMED → RECOVERY_REEVALUATE

Completion
    OPEN
    ↓
    ADR-0022 ownership / convergence
```

| Slice | Scope | Status |
|-------|-------|--------|
| PR1 | delivery observation | CLOSED |
| PR2 | bounded retransmit; `DELIVERY_EXHAUSTED` semantics | CLOSED |
| PR3 | admission freshness; confidence projection | CLOSED |
| PR4 | handler outcome ACK contract; `DELIVERY_CONFIRMED` semantics | CLOSED |
| ADR-0022 | completion convergence — **Q1 CLOSED (A)** · Q2 OPEN | **OPEN** |

**Do not modify PR4.**

Evidence soak for handoff: `logs/signal-path-20260730-183447` (session `103c9ef2-bb0d-44c4-aac9-d3c3d22244a1`).

Next workstream: **PR5-0** (completion observation projection — authorized; zero runtime change). Grill: [ADR-0022 Appendix E §E.10](../adr/0022-recovery-completion-ownership.md#e10-implementation-authorization-final).

ADR-0022 investigation framing — do **not** ask "why didn't recovery complete?" as a single question. Decompose:

```text
Delivery confirmed?
        |
        v
Recovery obligation owner?
        |
        v
Who owns completion decision?
        |
        v
Which invariant allows close?
```

Frozen separations (carry forward):

```text
peer fact              ≠ local completion decision
delivery fact            ≠ recovery completion
media connected          ≠ obligation satisfied
```

PR4 lesson for ADR-0022: **missing fact ≠ failure.** PR4 fixed `no ACK → DELIVERY_EXHAUSTED` by emitting `ACK(ALREADY_SATISFIED) → DELIVERY_CONFIRMED`. ADR-0022 may be the same class: `completion fact missing ≠ recovery failed`.
