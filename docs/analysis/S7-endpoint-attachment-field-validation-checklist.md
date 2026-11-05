# S7：Endpoint / Channel 附件五机现场验证

分支建议：`feat/endpoint-text-enhancements`（与 S0～S6 一致）

**不在范围：** P1 ACK、离线补发、>512 KB、频道授权改造、Mesh 建链、PTT/Meeting 调度变更。

## 附件测试前置 — Mesh 成员与 ICE（2026-11-05 裁决）

**每条附件 run 开始前**，在发送端与每个接收端分别确认（log 或 TOPOLOGY 快照）：

| 检查项 | 要求 |
| --- | --- |
| `sessionAccepted` | `true` |
| 本地 roster | 与 Primary canonical roster 一致（rosterEpoch / memberHash 对齐） |
| 频道成员 | 预期成员均已准入（无长期 `NO_ACCEPTED_GROUP_*`） |
| 目标 peer ICE | 对该 run 的每条传输边为 `CONNECTED`（或 TOPOLOGY `meshIceConnectedPeers` 含目标） |
| 实际拓扑 | 记录 `members` / `meshIceConnectedPeers`；**三机可达 ≠ 五机频道 PASS** |

未满足时：该 run 标 **`BLOCKED_BY_MESH`**（成员/session 或 ICE 前置），**不是 `ATTACHMENT_FAIL`**。

Mesh RCA 分两份独立文档（勿合并为「ICE 建链失败」）：

- 协商层 ~11:42：`field-logs/s7-endpoint-attachment-20261105-113652/M02_M05_ICE_STUCK_RCA.txt`
- 成员/Primary ~11:50–11:56：`field-logs/s7-endpoint-attachment-20261105-113652/M02_MEMBER_PRIMARY_SYNC_RCA.txt`
- 索引：`field-logs/s7-endpoint-attachment-20261105-113652/MESH_RCA_INDEX.txt`

冷启动恢复验收（文档 only）：`MESH-COLDSTART-RECOVERY-ACCEPTANCE-001.txt`

**当前 S7 裁决摘要：**

- 已通边附件 / 音频隔离：继续（记录实际可达设备）。
- M02↔M05：仅当该边 ICE 重新确认通后再测。
- 五机全员频道附件：五机成员准入 + 预期 ICE 边全部满足后再测。
- S6 附件实现：不因上述 Mesh 项降级。

---

## 验收拆成两条独立结论

1. **功能可靠性**：发送、接收、SHA、落盘、SQLite、重启恢复是否正确；失败时是否无「半成品当完整附件」。
2. **业务隔离性**：附件传输期间 PTT / Meeting 听感与异常日志；附件全成功但会议卡顿 → **隔离 FAIL**。

发送端 UDP 提交成功 ≠ 对端收齐 ≠ 本地持久化成功 — 五机分别取证。

## 场景矩阵（至少覆盖）

| # | 场景 | 说明 |
| ---: | --- | --- |
| 1 | 私信 M01 → M02 | 小文件 + 接近 512 KB 各至少 1 次 |
| 2 | 频道 M01 → M02/M03/M04/M05 | 同上 |
| 3 | 发送中短暂断网 | best-effort 失败可接受；不得 UI/DB 展示未校验完整附件 |
| 4 | 接收端收齐后杀进程重启 | 消息 + 文件仍可用 |
| 5 | 附件发送期间 PTT 或 Meeting | 记录听感 + 相关 logcat 片段 |

## 每轮记录模板

```
run_id:
date:
build: (commit hash / apk label)
scenario: (1-5 / 自定义)
path: (direct | channel)
sender:
receivers:

--- 发送端 ---
attachmentId:
file_name:
file_size_bytes:
sha256_expected:
chunks_sent:
send_submit_ok: (yes/no)
local_commitVerified: (ok|fail|n/a outbound)
local_sqlite_insert: (ok|fail|n/a)
notes:

--- 接收端 <device> ---
meta_sender_key:
meta_attachment_id:
chunks_expected:
chunks_received:
chunks_missing:
chunks_duplicate:
reassembly_ok:
sha256_expected:
sha256_actual:
sha256_match:
commitVerified: (ok|fail)
sqlite_insert: (ok|fail)
ui_shows_complete_message: (yes/no — must be no if sha/commit/db fail)
after_reboot_message_visible: (yes/no/n/a)
after_reboot_file_readable: (yes/no/n/a)
notes:

--- 音频隔离（本 run） ---
ptt_or_meeting_active: (ptt|meeting|none)
user_audible_glitch: (none|minor|severe)
log_snippet_path:
verdict_isolation: (pass|fail|inconclusive)

--- run 结论 ---
functional:
isolation:
```

## 建议 logcat 关键字（按需）

- `MESSAGE_ATTACHMENT`
- `MESSAGE_PERSIST`
- `ENDPOINT_ATTACHMENT` / `CHANNEL_ATTACHMENT`（若已有）
- PTT/Meeting 现有 field 标签（与现场惯例一致即可）

## 产出目录

将每轮填好的记录与 log 片段放入：

`field-logs/s7-endpoint-attachment-<YYYYMMDD>-<run_id>/`

（本 checklist 仅为 S7 启动模板；**现场 PASS/FAIL 以填完的证据为准。**）
