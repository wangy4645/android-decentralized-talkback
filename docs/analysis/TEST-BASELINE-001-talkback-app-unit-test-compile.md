# TEST-BASELINE-001：修复 talkback-app Debug Unit Test 编译基线

## 背景

`:talkback-app:testDebugUnitTest` 无法进入测试执行阶段，受到仓库既有测试源码编译错误阻挡。

已知错误涉及：

- `EpisodeCompletionAxisAdapterTest`
- `resetForTest`
- `episodeCompletion`

本问题独立于 `feat/endpoint-text-enhancements` 的附件 S0～S6 实现。

## 目标

恢复 `talkback-app` 单元测试编译能力，使指定测试能够独立执行。

## 修改边界

1. 只修复既有测试编译 / 执行环境问题。
2. 不修改附件生产代码。
3. 不修改 PTT、Meeting、Mesh 建链和音频调度。
4. 不改变 Episode 业务逻辑（core 层 `EpisodeCompletionProjectionFacade` 等保持不变）。
5. 不修改附件 S0～S6 提交。

## 修复摘要（commit `0271aad`）

| 项 | 处理 |
| --- | --- |
| `EpisodeCompletionAxisAdapterTest` | 对齐当前 `MeetingPresenceDisplay.ParticipantPresentationFacts` API；completion 投影改由 core 单测覆盖 |
| `InboundAttachmentPersistenceTest` | Robolectric 使用 `Application` 而非 manifest 中的 `TalkbackApp`，避免 Application 构造期 `filesDir` NPE |

**说明：** `0271aad` 解决的是 **编译** 与 **定向 Robolectric 执行** 问题。全量跑测后暴露 **另一层** Application 初始化问题（见下文「全量运行」），与附件生产代码无关。

## 验收标准（本 issue 范围）

- [x] `:talkback-app:compileDebugUnitTestKotlin` 编译通过
- [x] `InboundAttachmentPersistenceTest` 三项测试实际执行并通过（S6b **TEST PASS**）
- [x] 定向 5 项（含 Episode 适配 2 项）执行通过
- [x] 修复独立 commit（`0271aad`）
- [ ] `:talkback-app:testDebugUnitTest` **全量**通过 — **未达成**（57/136 FAIL，见下）；**不能**据此判附件回归

## 定向测试运行记录（2026-11-05）

命令（offline Gradle，`GRADLE_USER_HOME=talkback\.gradle-home`）：

```text
gradle --offline -p talkback :talkback-app:testDebugUnitTest
  --tests "com.talkback.appprod.endpointtext.InboundAttachmentPersistenceTest"
  --tests "com.talkback.appprod.ui.EpisodeCompletionAxisAdapterTest"
```

结果：`BUILD SUCCESSFUL`（5 tests，0 failures）

报告：`talkback-app/build/test-results/testDebugUnitTest/`

## 全量测试运行记录（2026-11-05）

| 项 | 值 |
| --- | --- |
| 完成时间 | 2026-11-05（本地执行，约 1m 25s，**未中断**） |
| 任务 | 仅 `:talkback-app:testDebugUnitTest`（未并行其它模块测试） |

命令（offline Gradle）：

```text
gradle --offline -p talkback :talkback-app:testDebugUnitTest
```

完整控制台日志：`field-logs/TEST-BASELINE-001-full-talkback-app-unit-test.log`

HTML 报告：`talkback-app/build/reports/tests/testDebugUnitTest/index.html`

### 汇总

| 指标 | 结果 |
| --- | --- |
| 总计 | 136 |
| PASS | 79 |
| FAIL | 57 |
| 编译 | 通过 |
| 附件回归证据 | **无**（endpointtext 相关用例全 PASS） |

### 57 项失败 — 共同根因

- 异常：`java.lang.NullPointerException: Cannot invoke "android.content.Context.getFilesDir()" because "this.mBase" is null`（及同类 `ContextWrapper` 行号 239/282/286）
- 机制：Robolectric 按 manifest 实例化 `TalkbackApp`，在 `Application` 构造阶段调用 `MessageAttachmentStorage.fromContext(this)` 时 base context 尚未 attach
- **归因：** Robolectric × Application 初始化 harness 问题；**不是** S6 附件生产逻辑回归
- **与 `0271aad` 关系：** 定向类已用 `@Config(application = Application::class)`；全量中未加该配置的 Robolectric 类仍触发 `TalkbackApp` 路径

### 失败分布（57）

| 包 / 区域 | 失败数 | 代表类 |
| --- | ---: | --- |
| `com.talkback.appprod.data` | 8 | `AppLocaleStoreTest`, `TaskProfileCodecTest`, `TaskProfileStoreTest` |
| `com.talkback.appprod.ui` | 49 | `UserVisibleConnectivityProjectionTest`, `MeetingPresenceDisplayTest`, `LocalReachabilityTest`, … |

### 附件 / 消息相关（全量中）

| 测试类 | 结果 |
| --- | --- |
| `InboundAttachmentPersistenceTest` | 3/3 PASS |
| `ConversationStoreTest` | 全部 PASS（纯 JVM，无 Robolectric Application） |
| 其它 endpointtext | 无失败 |

## 裁决（与 S6 / S7 衔接）

| 结论 | 说明 |
| --- | --- |
| S6b 证据 | **TEST PASS**（定向 + 全量中的附件用例） |
| talkback-app 全量 | **FAIL（57/136）** — 不能写 PASS |
| 57 FAIL vs 附件 | **无证据**将失败归因于附件代码 |
| S7 五机 | **可启动** — 不以前置「全量单测清零」为 gate |
| 合并 main | **暂缓** |

## 后续（不在本 issue）

- **TEST-BASELINE-002**（建议单独 issue/commit）：统一 Robolectric 默认 `Application` 或批量 `@Config`，消除 57 项 harness 失败；**不修改** `TalkbackApp` 生产构造逻辑。
- `:android-board-talkback:testDebugUnitTest`：Controller 等编译/执行问题仍 **BLOCKED**，与 talkback-app 基线分开处理。

## 未纳入本 issue

- `EpisodeCompletionAxisAdapterTest` 类名保留以减少 churn；行为已改为 UVCP 连通性轴断言。
- P1 ACK、附件上限、频道授权、Mesh/PTT 生产变更。

## 完成定义（TEST-BASELINE-001）

- 编译基线恢复：**DONE**（`0271aad`）
- S6b 定向执行：**TEST PASS**
- 全量结果：**已记录**；全量 **NOT PASS**（57 FAIL，已归因 harness，非附件）
