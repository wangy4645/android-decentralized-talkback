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

## 修复摘要

| 项 | 处理 |
| --- | --- |
| `EpisodeCompletionAxisAdapterTest` | 对齐当前 `MeetingPresenceDisplay.ParticipantPresentationFacts` API；completion 投影改由 core 单测覆盖 |
| `InboundAttachmentPersistenceTest` | Robolectric 使用 `Application` 而非 manifest 中的 `TalkbackApp`，避免 Application 构造期 `filesDir` NPE |

## 验收标准

- [x] `:talkback-app:compileDebugUnitTestKotlin` 编译通过
- [ ] `:talkback-app:testDebugUnitTest` 全量通过（未在本 issue 范围强制）
- [x] `InboundAttachmentPersistenceTest` 三项测试实际执行并通过
- [x] 修复独立 commit

## 测试运行记录（2026-11-05）

命令（offline Gradle）：

```text
:talkback-app:testDebugUnitTest
  --tests "com.talkback.appprod.endpointtext.InboundAttachmentPersistenceTest"
  --tests "com.talkback.appprod.ui.EpisodeCompletionAxisAdapterTest"
```

结果：`BUILD SUCCESSFUL`（5 tests，0 failures）

报告路径：`talkback-app/build/test-results/testDebugUnitTest/`

## 未纳入本 issue

- `:android-board-talkback:testDebugUnitTest` 仍有其它历史编译错误（与 talkback-app 基线分离）。
- `EpisodeCompletionAxisAdapterTest` 类名保留以减少 churn；行为已改为 UVCP 连通性轴断言。

## 完成定义

`InboundAttachmentPersistenceTest` 三项 **TEST PASS** 后，S6b 可从 CODE COMPLETE 更新为 **TEST PASS**。
