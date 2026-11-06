# GKE-CODE-REUSE-ASSESSMENT

**Title:** Group-Key Establishment — code reuse & trust-path assessment  
**Status:** APPROVED for Phase 0 Spike planning  
**Product target:** 新装 App → **现有设置**（moduleId、频道、sharedSecret 等）→ 开服务 → **无运维** → Establishment binding 自动就绪 → 现有会议/media  
**Date:** 2026-11-05  

**Related:**

- `ESTABLISHMENT-ENROLLMENT-PRODUCTIZATION-001.md` — **主 UX 暂停**；已实现 PAP/Base64 路径 = **Operator Recovery**
- `meeting-profile01-establishment-provisioning-design-slice.md` — Establishment **语义冻结**（Signing≠Establishment，禁止 HELLO-TOFU 生产路径）
- `REGRESSION-SOURCE-ORIGIN-RETRY-001` — **独立**，GKE 不替代 672ea22 裁决

---

## 1. 问题陈述（架构共识）

| 层 | 作用 | 清数据后 |
|----|------|----------|
| Channel / Mesh | 发现同组设备 | 通常仍可用（配置一致即可） |
| Signing Trust | CREATION / 成员事实签名 | `LabDeploymentTrustBootstrap` 自动恢复 |
| **Establishment Trust** | Host 对 peer 的 RSA 公钥 binding → factType=11 | **常缺失** → `UNKNOWN_ESTABLISHMENT_MODULE` |

**第三层精确定义（冻结）：**

> 经 **组级凭据认证** 的 **moduleId ↔ Establishment SPKI** 绑定写入 `AcceptedLocalEstablishmentTrustStateStore`，使 `Profile01ProfileBackedRecipientEstablishmentKeyLookup.activeEstablishmentKey(peer)` → `Found`。

**GKE 不做：** 替换 factType=11、改音频、改 SOURCE coalescer。  
**GKE 做：** 发现之后、media 之前，**自动**建立第三层（用户 UI 与现在一致，复用 **sharedSecret** 等现有字段作为 PSK 原料，**不新增**「组名/组密钥」页面除非产品后改口）。

---

## 2. 目标数据流（GKE 完成后）

```text
现有 AppConfig（moduleId, channelId, sharedSecret, …）
  → 开 TalkbackForegroundService / runtime
  → LabDeploymentTrustBootstrap（Signing，已有）
  → 本机 Keystore establishment 身份（E1，可已有 Ensurer）
  → Mesh/HELLO 发现同 secret 组内 peer
  → GroupProof 协议（PSK = f(sharedSecret, domain, …)）证明 moduleId 身份
  → 交换 establishment SPKI（带 MAC/签名，防冒充）
  → Trust Acceptance → AcceptedLocalEstablishmentTrustStateStore.atomicReplace
  → Host lookup Found → 现有 factType=11 → 媒体
```

---

## 3. 五项只读审查（代码事实 + 结论）

### 3.1 `sharedSecret` 在哪里用？能否当 GKE PSK？

| 位置 | 用途 |
|------|------|
| `SignalSecurity` | 对 `SignalEnvelope` 做 **HmacSHA256**（type/from/to/payload/nonce…） |
| `TalkbackCoordinator` | 出站信令签名 / 入站 `verify` |
| `MeshSweepGossipDiscovery` | Gossip 包签名与校验 |
| `AppConfig` / `TalkbackForegroundService` | 启动服务 **必填**（`validateSecret` 仅 **非空**，无最小长度/熵） |
| 存储 | SharedPreferences + Intent extra；**非** Android Keystore |

**结论：**

- **UI 复用：** 用户已配置的 sharedSecret = 自然「组密码」入口，**无需新页面**。
- **协议复用：** 现有 HMAC **只保护信令/gossip**，**不等于** Establishment 已认证；GKE **必须新增**「PSK + 挑战 + moduleId↔SPKI 绑定」消息，可 **派生密钥** 自 sharedSecret（例如 HKDF），与 `SignalSecurity` 共用原料但 **分离 context/string**。
- **安全：** 评估文档记录 — 当前 secret 策略偏弱；GKE ADR 应定最低长度/轮换；**不**假定「有 secret = 媒体 trust 已就绪」。

**代码索引：** `com.talkback.core.security.SignalSecurity`；`TalkbackCoordinator` ~22279；`MeshSweepGossipDiscovery`；`SettingsActions.validateSecret`。

---

### 3.2 HELLO / Mesh 是否有扩展入口？

| 事实 | 路径 |
|------|------|
| HELLO 已携带 trust 材料 | `HelloPayload.meshTrust` → `MeshPeerTrustHelloDigest`（signing + establishment SPKI/version） |
| 编码 | `MeshPeerTrustHelloDigest` JSON；`HelloPayloadTest.encodeDecode_meshTrust_roundTrip` |
| 本地发出 | `TalkbackCoordinator` ~17864 `buildLocalHelloDigest()` |
| 对端处理 | ~9599 `meshHelloPeerTrustSupplement?.applyFromHello(peerModuleId, payload.meshTrust)` |
| 装配 | `TalkbackRuntimeFactory` Production wiring 时 `attachMeshHelloPeerTrustSupplement` |

**结论：**

- **传输层可复用：** 在 HELLO 或 adjacency 信令上挂 **GKE 认证字段** 可行（或独立 SignalType，需评估与现有 gate 兼容性）。
- **信任层不可复用 supplement 现状**（见 §4）。

---

### 3.3 `AcceptedLocalEstablishmentTrustStateStore` 能否安全、幂等地「加成员」？

| 事实 | 说明 |
|------|------|
| 写入 API | `atomicReplace(next)`；可选 persistence；已有 `AcceptedEstablishmentTrustStateListener` |
| 语义 acceptor | `EstablishmentProfileRevisionAcceptor.acceptAuthenticatedRevision` — **整包** snapshot，单调 `taskProfileRevision`，rollback/conflict/idempotent 规则 |
| 增量 | 新成员 = **新 revision**，payload 含 **原有 + 新** `establishmentBindings`，不是单条 patch |

**结论：**

- GKE 写入应 **产出合法 `AcceptedLocalEstablishmentTrustState`**（或通过 **统一 Trust Acceptance** 调同一 acceptor 语义），保证与 PAP 路径 **同 store 形状**。
- 幂等：靠 revisionIdentity / taskProfileRevision；GKE 协议需定义 **epoch/generation** 防旧 SPKI 覆盖新设备。
- **清数据空 store：** 第一条 binding 必须来自 **GKE 认证会话** 或 PAP；**不能**依赖 supplement 从 0 引导（见下）。

---

### 3.4 `Profile01ProfileBackedRecipientEstablishmentKeyLookup` 写入后是否立即可查？

| 事实 | 说明 |
|------|------|
| Lookup | 读 `establishmentStore.currentSnapshot()`，ACTIVE binding，`max establishmentKeyVersion` |
| 时序 | `atomicReplace` 后同进程 **立即可 Found** |
| 缓存 | App 层 `EstablishmentReadinessGate` 有 localCache；写入后应 `invalidateLocal()` / listener |
| Media 唤醒 | Store listener 已存在；**Supplement Obligation 订阅** 仍为 Phase 3，GKE 后 Host 可能需 **显式 invalidate conference** 或 fanout 重试（Spike 验证） |

**结论：** Store + lookup **可复用**；GKE 完成后关注 **Gate 缓存** 与 **Host fanout 触发**，不是 lookup 本身。

---

### 3.5 PAP 哪些可抽成「统一 Trust Acceptance」？

| 组件 | 职责 | GKE 复用建议 |
|------|------|----------------|
| `EstablishmentProfileOperationalAuthenticator` | PAP 验签 delivery | GKE **替换为** `GroupProofVerifier`（输入不同，输出统一） |
| `EstablishmentProfileRevisionDeliveryAcceptor` | 验签 + acceptor | Recovery 保留；GKE 可能 **绕过 delivery bytes**，直接 `acceptAuthenticatedRevision` 或新 **GroupRevision** 类型 |
| `EstablishmentProfileRevisionAcceptor` | semantic validate + `atomicReplace` | **强烈建议复用** |
| `EstablishmentProfileTrustSemanticValidator` | payload 规则 | 评估是否扩展「GKE-signed payload」或 GKE 只产出与 EP 同形的 payload |
| `Profile01VerifiedLocalEstablishmentIdentity` | 本机 Keystore ↔ profile | **复用**（E1 不变） |

**冻结建议：** 生产路径 **仅允许两入口** 写入 establishment store：

1. **GKE GroupProof**（主路径，自动）  
2. **PAP signed delivery**（Recovery / 运维，已实现 App 粘贴）

**禁止作为生产第三入口：** `MeshHelloPeerTrustSupplement.extendEstablishment` 的 HELLO-TOFU 语义（见 §4）。

---

## 4. 关键警示：`MeshHelloPeerTrustSupplement` ≠ GKE

代码行为（`MeshHelloPeerTrustSupplement.kt`）：

- `extendEstablishment` 要求 **本机已有** `establishmentTrustStore.currentSnapshot()`；**空 store → false**（greenfield 无效）。
- 将 peer HELLO 中的 `establishmentPublicKeySpki` **按 `peerModuleId` 写入 ACTIVE binding**，**无** PAP、**无** sharedSecret 证明 moduleId 与 SPKI 绑定。
- 设计注释：late mesh / roster 变大后补 binding；**不是**安全入组模型。

| 维度 | 可复用 | 禁止复用 |
|------|--------|----------|
| HELLO 携带 SPKI 字段 | ✓ 传输 | |
| `applyFromHello` 写 store | | ✗ 信任语义 |
| 空 store 入组 | | ✗ 不支持 |

GKE Spike **不得** 以「已能 supplement」为由跳过 GroupProof。

---

## 5. 与当前 App 实现的关系

| 资产 | 状态 | GKE 阶段裁决 |
|------|------|----------------|
| `EstablishmentEnrollmentCoordinator` + Base64 UI | 已合入 | **冻结**；不作为主路径；保留 **Recovery** |
| `EstablishmentReadinessGate`（LOCAL / CONFERENCE） | 已合入 | **复用**；GKE 写入后驱动 READY |
| `LabDeploymentTrustBootstrap` | 已有 | **保留** |
| EP androidTest / 工作站脚本 | 已有 | **Recovery / 合规**，非日常 |
| factType=11 / Mesh 协商 / AQC | 不变 | Spike **不修改** |

---

## 6. 架构裁决表（D1–D11，Phase 0 前签字）

| ID | 议题 | 建议默认（待签字） |
|----|------|-------------------|
| D1 | PSK 原料 | HKDF(sharedSecret, deploymentDomain, "gke-v1") |
| D2 | 发现 | 现有 Mesh + HELLO；GKE 消息可嵌 HELLO 或新 SignalType |
| D3 | 认证 | 挑战-应答 + HMAC( PSK, moduleId \|\| nonce \|\| spkiHash ) |
| D4 | 冒充防护 | SPKI 仅在 Verify(GroupProof) 通过后写入 store |
| D5 | Store 写入 | 经 `EstablishmentProfileRevisionAcceptor` 等价语义 → `atomicReplace` |
| D6 | 入口数量 | **仅 GKE + PAP Recovery** |
| D7 | UI | **不新增**组密钥页；沿用 sharedSecret + 现有设置 |
| D8 | 组密钥轮换 | V1：改 sharedSecret → 全组 re-GKE（文档化） |
| D9 | 10 台动态 | V1 Spike 2 台；V1 产品 ≤10 台同 secret |
| D10 | 001 文档 | 主 UX **SUPERSEDED**；Recovery 附录 |
| D11 | SOURCE regression | 独立 RCA，GKE PR **不夹带** 672ea22 |

---

## 7. Phase 0 Spike（最小范围，两机）

**目标：** 空 `accepted-local-establishment-trust-state.bin`，两台仅配置 **相同 sharedSecret + 可互相 HELLO**，**无 PAP 粘贴**，完成后：

1. 两机 `Profile01VerifiedLocalEstablishmentIdentity` → Verified  
2. M01 `activeEstablishmentKey(M02)` → Found（反之亦然）  
3. Log：`GKE_*` 无 `UNKNOWN_ESTABLISHMENT_MODULE`（建会可选，**E5/E6 非 Spike 阻塞**）

**明确不做：**

- 改 factType=11 实现  
- 改音频 / AQC  
- 扩展 Enrollment 粘贴 UX  
- 启用 supplement TOFU 作为捷径  

**建议交付：**

- 新包 `com.talkback.core.session.gbc.trust.group`（或 `mesh.gke`）：GroupProof + GroupTrustAcceptance（desk test 优先）  
- Coordinator 挂钩：`TalkbackRuntimeFactory` / HELLO 处理 seam（具体 Spike PR 定）  
- 场测 log：`field-logs/gke-spike-<id>/`

---

## 8. Phase 1 产品化（Spike 通过后）

- 后台自动 GKE（服务启动 + mesh 就绪后运行状态机）  
- 隐藏或降级 Enrollment 粘贴为「高级/恢复」  
- 五机 **同 secret** 清数据验收（§13.1 等价 E1–E4，但 **无 Base64**）  
- CONFERENCE gate 与 Readiness **保留**

---

## 9. 验收对照（与用户确认的目标）

| 用户目标 | GKE 完成后 |
|----------|------------|
| 新手机安装，无 adb / 无第二 app | ✓ |
| UI 与现在一样（sharedSecret 等） | ✓ |
| 配好组密码开服务即可对讲 | ✓（需 mesh 发现 + GKE 完成 binding） |
| 不理解 RSA/PAP/delivery | ✓（后台完成） |

| 当前仓库（Spike 前） | |
|---------------------|---|
| 仅 sharedSecret **不能** 自动 Establishment | ✗ 仍缺 GKE 协议 |

---

## 10. 建议文档链（下一步）

1. **本文** — 代码复用与禁止项（**当前**）  
2. **`GROUP-KEY-ENROLLMENT-ADR-001.md`**（待写）— D1–D11 签字 + 消息格式 + 威胁模型  
3. Spike PR → `field-logs/` 证据  
4. 更新 `ESTABLISHMENT-ENROLLMENT-PRODUCTIZATION-001.md` §0 状态为 **Recovery-only**

---

## 11. 变更记录

| 日期 | 变更 |
|------|------|
| 2026-11-05 | 初版；五项审查 + supplement 警示 + Phase 0 Spike 范围；产品目标确认 |
| 2026-11-05 | Phase 0 desk：`group/*` PSK+proof+merger；`GroupEstablishmentTrustMergerTest` PASS（`-PgkeDeskOnly`） |
| 2026-11-06 | Phase 0 HELLO：`GroupEstablishmentHelloTrustService` + `meshTrust.gkeNonceHex/gkeProofHex`；Production + sharedSecret 自动 bootstrap + HELLO 交换；enrollment 与 runtime 共享 establishment store |
