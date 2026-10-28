# GCT M03 Lifecycle Observation IA-001

**ID:** gct-m03-lifecycle-observation-ia-001  
**Date:** 2026-08-25  
**Type:** Implementation Authorization · Read-Only Observability  
**Status:** **COMPLETE** · field **ADJUDICATED** · see [gct-ia001-clean-start-adjudication-20260825.md](./gct-ia001-clean-start-adjudication-20260825.md)

**Parent RCA:** [m03-host-pc-generation-ownership-lineage-rca-20260824.md](./m03-host-pc-generation-ownership-lineage-rca-20260824.md)  
**RCA verdict:** CLOSED · A vs B **INCONCLUSIVE**  
**Session seed:** `e55028df-a68f-4bec-81a9-42a3b73be8e1` · `logs/group-conference-transition-field-20260824-174918/`

---

## Goal

把一条边上的：

```text
pending drain → provision generation → createOffer/SLD → GROUP_INVITE → peer selected offer → GROUP_ACCEPT → host applyRemoteAnswer
```

用同一组 id 串起来。只观测。不修 SRD。不 reset PC。

**要钉死的问题：** answer 对应哪一个 offer？

---

## Two layers (do not merge)

```text
CONFIRMED anomaly (lifecycle):
  pending drain 重放多条请求
  → 多代 provision
  → 多次 createOffer

UNCONFIRMED cause (native):
  M03 gen=73 setRemoteDescription(answer) JNI never returns
  Cause = UNKNOWN
```

禁止写成：

```text
pending drain → native hang
stale PC → native hang
```

---

## Authorized scope (read-only)

Behavior-neutral：只加日志字段 / 新 grep 行。不改变 pending 兑现策略、不合并 duplicate offer、不改 SRD/lease/timeout。

### P1 — Offer ↔ answer lineage（第一优先）

在 host `createOffer` / `SLD type=OFFER` BEFORE+AFTER 打：

```text
pcHash
pcGeneration
offerLineageId
realizationAttemptId
```

`offerLineageId`：本轮 createOffer 产出的稳定 id（可与 nonce / SDP fingerprint 同键）。  
`realizationAttemptId`：同一次 `CONFERENCE_MEDIA_REALIZATION_OFFER` / `onReady` 尝试。双 offer 必须是两个不同 attempt。

同一组 id 必须出现在：

```text
createOffer / SLD OFFER
GROUP_INVITE 发送（含 envelope bytes + nonce）
peer 选中并 applyRemoteOffer 的那一份
GROUP_ACCEPT / answer 发送
host applyRemoteAnswer / SRD_NATIVE_CALL_ENTER
```

Peer 侧 `applyRemoteOffer` 禁止再打 `offerLineageId=NONE`（本路径）。必须能回答：

```text
M03: offer #1 → answer #1 ?
     offer #2 → answer #2 ?
host 当前 localDesc 对应哪一个 offerLineageId
```

**Adjudication after field（未授权跑，仅定义）：**

```text
MISMATCH:
  answer lineage ≠ host current local offer lineage
  → 新 RCA：Conference realization lineage race
  → 不进入 PC recovery

MATCH:
  answer lineage = host current local offer
  → 排除本缺口
  → 仍不得推断 native hang 根因
```

### P2 — Pending drain snapshot（第二优先）

`scheduleDeferredProvision` drain **之前** 一行：

```text
MEDIA_PENDING_DRAIN module=… count=N
  items=scope:origin,scope:origin,…
```

每条 `provisionNew` 后一行：

```text
MEDIA_PROVISIONED … generation=… pcHash=… factoryHit=NEW|REUSE origin=…
```

`factoryHit`：`getOrCreate` 是新建还是命中已有 engine。

`PC_CLOSE_ENTER/EXIT` 保持现有；P2 要求 M03/M04 在 GCT hangup/provision 路径上 **能 grep 到 close 或明确 SKIP reason**（例如 `alreadyReleased` / `factoryMiss`）。不得静默。

P2 **不**授权把 drain 改成 “只兑现 latest CONFERENCE”。那是未来单独 lifecycle de-dup IA。

---

## Explicitly not authorized

```text
PC hard reset / factory rebuild
force-release
pending de-dup / 只兑现 latest scope
抑制第二次 createOffer / 第二次 GROUP_INVITE
SRD timeout / retry / rollback
LEASE 语义 / DOMAIN_BLOCKED 分类变更
WiFi recovery 四域
D-β/γ · Directed #5
field capture（本 IA 实现落地后另开 run card）
```

---

## Post-impl gate（仍不是 field 授权）

Desk：单元或 log 契约测试证明 P1/P2 字段出现在合成路径。  
Field：须另开 run card；M01 host + M03 DUT + M04 对照，四机同一构建。

Field grep：

```text
MEDIA_PENDING_DRAIN
MEDIA_PROVISIONED ... factoryHit=
CONFERENCE_OFFER_LINEAGE stage=CREATE_OFFER|GROUP_INVITE|PEER_SELECTED_OFFER|PEER_INVITE_SUPERSEDED|APPLY_REMOTE_ANSWER
lineageCorrelation=MATCH|MISMATCH|OFFER_LINEAGE_UNKNOWN
PC_CLOSE_SKIPPED
SRD_NATIVE_CALL_ENTER ... offerLineageId=
```

Field 时除 `SRD_TIMEOUT` 外必须先判 P1 lineage。

```text
Current RCA CLOSED / INCONCLUSIVE
        ↓
This OBS IA COMPLETE
        ↓
field Round 1 (dirty) + Round 2 (clean)
        ↓
[gct-ia001-clean-start-adjudication-20260825.md] FROZEN
        ↓
Native SRD Recovery / Containment IA  (NOT STARTED)
```

---

## Frozen wording

```text
provision churn / duplicate offer = confirmed anomaly ≠ confirmed cause of native hang
Cause of M03 SRD JNI hang = UNKNOWN until P1/P2 field
```
