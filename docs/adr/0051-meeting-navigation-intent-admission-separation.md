# ADR-0051: Separate Meeting Navigation Intent from Conference Admission

## Status

**ACCEPTED** (2026-08-12) · Field verified F1–F6 PASS (M01/M02/M03 · SSID `happy`)

**PROPOSED** (2026-08-11) · Field incident: M01 Talk → Meeting → More accidental `MEETING_START` (2026-08-11)

**Field evidence:** `talkback/logs/adr0051-pr1-20260812-063622` (F1 navigation) · `talkback/logs/adr0051-pr1-20260812-074344` (F3 partial) · F2/F4/F5/F6 operator-confirmed PASS (2026-08-12)

**Companion (future):** User termination preempts meeting admission — separate ADR; not in scope here.

---

## Context

Talk 页面将 **Navigation** 与 **Conference Admission** 混在同一入口：

```text
openMeetingScreen(OPTIONS)
        |
        +--> joinMeeting(reason = "ui.openMeetingScreen")
                |
                v
        MEETING_START / GROUP_INVITE
```

用户执行「打开会议设置 / 成员」时，系统隐式创建 admission transaction。在无中心拓扑下，这会向 M02/M03 发出 invite，而用户并未表达「开始会议」意图。

现场（M01, 2026-08-11）：

```text
User:  Talk → Meeting mode → More
Expect: Open meeting options
Actual: JOIN_MEETING_TRACE reason=ui.openMeetingScreen
        MEETING_START · CONNECTING · GROUP_INVITE
```

Grep 结论：生产代码中 `joinMeeting()` **仅有一处调用**（`TalkFragment.openMeetingScreen`）。问题不是 join 分散，而是 **单一汇聚点将 navigation 解释为 admission**。

---

## Decision

> **Navigation actions MUST NOT create or mutate Conference Admission. Conference admission MUST originate from explicit `JoinMeetingIntent`. This separates UI navigation from distributed session transactions.**

### 1. Navigation 不产生 session mutation

```text
requestMeetingScreen(target)  →  UI destination only
```

### 2. Admission 仅经显式 intent

```kotlin
sealed interface JoinMeetingIntent {
    data object PttMeeting : JoinMeetingIntent   // 主交互按钮
    data object TapToJoin : JoinMeetingIntent    // online 区 JOIN_MEETING
}
```

```kotlin
suspend fun joinMeeting(intent: JoinMeetingIntent)
```

禁止字符串 reason（如 `ui.openMeetingScreen`）。`joinMeeting` 对 Fragment 层不可直接调用。

### 3. 废弃 `openMeetingScreen()`

Phase 1：`@Deprecated` + 开发期 `error(...)` 或 `check(false)`，禁止静默转发。

### 4. Idle Options（未开会时打开 More）

**选项 B：** 允许进入 Options；session-dependent 项（Mute All、End For All 等）disabled。More 是查看入口，不是 toast 拒绝。

不在本 ADR 引入 Options 内「Start Meeting」CTA。

---

## Entry Matrix

| 入口 | 允许创建 Session | PR1 行为 | Intent / 命令 |
|------|------------------|----------|---------------|
| Talk → More | 否 | `requestMeetingScreen(OPTIONS)` | Navigate |
| Talk → Members | 否 | `requestMeetingScreen(MEMBERS)` | Navigate |
| Meeting 主按钮 | 是 | `joinMeeting(PttMeeting)` | `PttMeeting` |
| Online 区（`JOIN_MEETING`） | 是 | `joinMeeting(TapToJoin)` | `TapToJoin` |
| Online 区（`OPEN_MEETING_CONTROL`） | 否 | `requestMeetingScreen(MAIN)` | Navigate |
| 切 Meeting Tab / auto-nav | 否 | 仅 navigate（已有） | Navigate |
| 接受邀请 | 是（独立路径） | `acceptIncomingMeeting()` — PR1 不改 | Out of scope |
| Meeting Options 页 | 否 | Navigate / settings only | Navigate |

**Future consideration:** explicit Start CTA inside Meeting Options — not part of this ADR.

---

## Non-goals

- ICE negotiation · invite confirm policy · peer admission timeout closure (→ PR4 / companion ADR)
- WiFi recovery · RNA · mesh recovery · completion predicate
- Existing governance transition IDs (T2/T3) semantics
- `USER_LEAVE` preempts admission (companion ADR)
- UI projection invariant after hangup (PR3)

---

## Acceptance

| Case | 操作 | 必须 | 禁止 |
|------|------|------|------|
| **F1** | Meeting mode → More | `OPEN_MEETING_OPTIONS` / navigate | `JOIN_MEETING_TRACE`, `MEETING_START`, `GROUP_INVITE` |
| **F2** | Meeting mode → Members | navigate MEMBERS | 同上 |
| **F3** | Meeting mode → 主按钮 | `JOIN_MEETING_TRACE` `intent=PttMeeting` | `reason=ui.openMeetingScreen` |
| **F4** | Meeting mode → Online（JOIN_MEETING） | `JOIN_MEETING_TRACE` `intent=TapToJoin` | 同上 |
| **F5** | 已在会中 → More | 只打开 Options | 二次 `MEETING_START` / CREATE |
| **F6** | 冷启动 → Meeting mode → More | navigate；`conferenceActive=false` | `MEETING_START`, `GROUP_INVITE` |

Field 模板（F1/F6）：M01 host · M02/M03 peers · SSID `happy` · 抓 M01 logcat `JOIN_MEETING_TRACE|MEETING_START|GROUP_INVITE|OPEN_MEETING`.

---

## Implementation (PR1 scope)

| 文件 | 变更 |
|------|------|
| `JoinMeetingIntent.kt` | 新增 sealed interface |
| `TalkViewModel.kt` | `joinMeeting(intent)` · `requestMeetingScreen()` · `endMeetingForAll` → `HOST_END_FOR_ALL` |
| `TalkFragment.kt` | More/Members → navigate；主按钮/online → `joinMeeting(intent)` |
| `MeetingFragment.kt` | navigation-only overlay（idle Options/Members 不显示 Connecting） |
| `MeetingOptionsFragment.kt` | idle disable · Settings 子页样式 · End for All 不 sync dismiss |
| `MainActivity.kt` | dismiss 前清子 Fragment back stack |
| `TalkbackRuntimeManager.kt` | host `endMeetingForAll` → `leaveConference` |
| `ChannelObservabilityLog` | `OPEN_MEETING_*` · `ADMISSION_INTENT_RECEIVED` · `intent=` trace |

不修改：`TalkbackCoordinator` · ICE · invite topology · recovery 域。

---

## Consequences

- More / Members 不再误发起 distributed invite transaction
- Admission 观测可按 `PttMeeting` vs `TapToJoin` 分桶
- 未开会时仍可查看 Options（部分控件 disabled）
- 显式「从 Options 开始会议」留待后续 ADR/产品决策
